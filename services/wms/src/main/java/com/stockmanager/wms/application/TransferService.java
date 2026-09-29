package com.stockmanager.wms.application;

import com.stockmanager.wms.domain.Transfer;
import com.stockmanager.wms.domain.TransferLine;
import com.stockmanager.wms.domain.TransferStatus;
import com.stockmanager.wms.infrastructure.InventoryClient;
import com.stockmanager.wms.infrastructure.TransferRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    private static final String REF_TYPE = "TRANSFER";

    private final TransferRepository transferRepository;
    private final InventoryClient inventoryClient;
    private final TransactionTemplate transactionTemplate;

    TransferService(TransferRepository transferRepository, InventoryClient inventoryClient, TransactionTemplate transactionTemplate) {
        this.transferRepository = transferRepository;
        this.inventoryClient = inventoryClient;
        this.transactionTemplate = transactionTemplate;
    }

    public Transfer request(String fromLocationCode, String toLocationCode, List<TransferLine> lines) {
        if (fromLocationCode.equals(toLocationCode)) {
            throw new IllegalArgumentException("같은 거점으로는 이동할 수 없습니다: " + fromLocationCode);
        }
        long transferId = transferRepository.insert(fromLocationCode, toLocationCode, lines);
        return reserve(find(transferId));
    }

    public void confirmPending(Instant createdBefore) {
        for (long transferId : transferRepository.findPendingBefore(createdBefore)) {
            reserve(find(transferId));
        }
    }

    public Transfer find(long transferId) {
        return transferRepository.find(transferId)
            .orElseThrow(() -> new IllegalArgumentException("이동 요청이 없습니다: " + transferId));
    }

    private Transfer reserve(Transfer transfer) {
        Optional<TransferStatus> result = requestReservation(transfer);
        if (result.isEmpty()) {
            return transfer;
        }
        return confirm(transfer, result.get());
    }

    private Optional<TransferStatus> requestReservation(Transfer transfer) {
        try {
            inventoryClient.reserve(toReservationRequest(transfer));
            return Optional.of(TransferStatus.REQUESTED);
        } catch (HttpClientErrorException.Conflict exception) {
            return Optional.of(TransferStatus.REJECTED);
        } catch (RestClientException exception) {
            log.warn("이동 {}의 예약 결과를 모릅니다. PENDING으로 두고 다시 시도합니다: {}",
                transfer.id(), exception.toString());
            return Optional.empty();
        }
    }

    private Transfer confirm(Transfer transfer, TransferStatus result) {
        return transactionTemplate.execute(status -> {
            transferRepository.changeStatus(transfer.id(), TransferStatus.PENDING, result);
            return find(transfer.id());
        });
    }

    private InventoryClient.ReservationRequest toReservationRequest(Transfer transfer) {
        List<InventoryClient.ReservationRequest.Item> items = transfer.lines().stream()
            .map(line -> new InventoryClient.ReservationRequest.Item(line.productId(), line.quantity()))
            .toList();
        return new InventoryClient.ReservationRequest(
            REF_TYPE, String.valueOf(transfer.id()), transfer.fromLocationCode(), items);
    }
}
