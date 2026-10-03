package com.stockmanager.sales.application;

import com.stockmanager.sales.domain.PosSale;
import com.stockmanager.sales.domain.PosSaleLine;
import com.stockmanager.sales.domain.PosSaleStatus;
import com.stockmanager.sales.infrastructure.InventoryClient;
import com.stockmanager.sales.infrastructure.InventoryClient.PosDeductionRequest;
import com.stockmanager.sales.infrastructure.PosSaleRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class PosSaleService {

    private static final Logger log = LoggerFactory.getLogger(PosSaleService.class);

    private static final String REF_TYPE = "POS";

    private final PosSaleRepository posSaleRepository;
    private final InventoryClient inventoryClient;
    private final CircuitBreaker inventoryCircuitBreaker;

    PosSaleService(PosSaleRepository posSaleRepository, InventoryClient inventoryClient,
                   CircuitBreaker inventoryCircuitBreaker) {
        this.posSaleRepository = posSaleRepository;
        this.inventoryClient = inventoryClient;
        this.inventoryCircuitBreaker = inventoryCircuitBreaker;
    }

    public PosSale sell(String receiptNo, List<PosSaleLine> lines) {
        return DeadlockRetry.run(() -> sellOnce(receiptNo, lines));
    }

    private PosSale sellOnce(String receiptNo, List<PosSaleLine> lines) {
        Optional<PosSale> sold = posSaleRepository.findByReceiptNo(receiptNo);
        if (sold.isPresent()) {
            return sold.get();
        }

        PosSale sale;
        try {
            sale = posSaleRepository.save(PosSale.sell(receiptNo, lines));
        } catch (DataIntegrityViolationException exception) {
            return posSaleRepository.findByReceiptNo(receiptNo).orElseThrow(() -> exception);
        }
        return deduct(sale);
    }

    public void confirmPending(Instant createdBefore) {
        for (PosSale sale : posSaleRepository.findByStatusAndCreatedAtBefore(PosSaleStatus.PENDING, createdBefore)) {
            deduct(sale);
        }
    }

    public Optional<PosSale> find(String receiptNo) {
        return posSaleRepository.findByReceiptNo(receiptNo);
    }

    private PosSale deduct(PosSale sale) {
        Optional<PosSaleStatus> result = requestDeduction(sale);
        if (result.isEmpty()) {
            return sale;
        }
        posSaleRepository.changeStatus(sale.getId(), PosSaleStatus.PENDING, result.get());
        return posSaleRepository.findByReceiptNo(sale.getReceiptNo()).orElseThrow();
    }

    private Optional<PosSaleStatus> requestDeduction(PosSale sale) {
        try {
            inventoryCircuitBreaker.executeRunnable(() -> inventoryClient.deduct(toDeductionRequest(sale)));
            return Optional.of(PosSaleStatus.COMPLETED);
        } catch (HttpClientErrorException.Conflict exception) {
            return Optional.of(PosSaleStatus.REJECTED);
        } catch (CallNotPermittedException exception) {
            log.warn("판매 {}: inventory 서킷이 열려 있어 차감을 요청하지 않았습니다", sale.getReceiptNo());
            return Optional.empty();
        } catch (RestClientException exception) {
            log.warn("판매 {}의 차감 결과를 모릅니다. PENDING으로 두고 다시 시도합니다: {}",
                sale.getReceiptNo(), exception.toString());
            return Optional.empty();
        }
    }

    private PosDeductionRequest toDeductionRequest(PosSale sale) {
        List<PosDeductionRequest.Item> items = sale.getLines().stream()
            .map(line -> new PosDeductionRequest.Item(line.productId(), line.quantity()))
            .toList();
        return new PosDeductionRequest(REF_TYPE, sale.getReceiptNo(), sale.getLocationCode(), items);
    }
}
