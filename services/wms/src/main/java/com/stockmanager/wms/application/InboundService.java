package com.stockmanager.wms.application;

import com.stockmanager.common.web.ConcurrentUpdateException;
import com.stockmanager.wms.domain.Inbound;
import com.stockmanager.wms.domain.InboundLine;
import com.stockmanager.wms.domain.InboundOrigin;
import com.stockmanager.wms.domain.InboundStatus;
import com.stockmanager.wms.domain.InspectionLine;
import com.stockmanager.wms.domain.Locations;
import com.stockmanager.wms.domain.ProductLines;
import com.stockmanager.wms.domain.PutawayLine;
import com.stockmanager.wms.infrastructure.InboundEventRecorder;
import com.stockmanager.wms.infrastructure.InboundRepository;
import com.stockmanager.wms.infrastructure.ProductBinRepository;
import com.stockmanager.wms.infrastructure.ProductRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class InboundService {

    private final InboundRepository inboundRepository;
    private final ProductRepository productRepository;
    private final ProductBinService productBinService;
    private final TransferService transferService;
    private final InboundEventRecorder inboundEventRecorder;
    private final TransactionTemplate transactionTemplate;

    InboundService(InboundRepository inboundRepository,ProductRepository productRepository,
                   ProductBinService productBinService, TransferService transferService,
                   InboundEventRecorder inboundEventRecorder, TransactionTemplate transactionTemplate) {
        this.inboundRepository = inboundRepository;
        this.productRepository = productRepository;
        this.productBinService = productBinService;
        this.transferService = transferService;
        this.inboundEventRecorder = inboundEventRecorder;
        this.transactionTemplate = transactionTemplate;
    }

    public Inbound arrive(String locationCode, List<InboundLine> lines) {
        ProductLines.requireDistinct(lines, InboundLine::productId);
        requireRegistered(lines);
        return transactionTemplate.execute(status -> {
            long inboundId = inboundRepository.insert(locationCode, new InboundOrigin.Supplier(), lines);
            return inboundRepository.find(inboundId).orElseThrow();
        });
    }

    public Inbound inspect(long inboundId, List<InspectionLine> results) {
        ProductLines.requireDistinct(results, InspectionLine::productId);
        return transactionTemplate.execute(status -> {
            Inbound inbound = find(inboundId);
            requireAllItems(inbound, results);
            if (inbound.origin() instanceof InboundOrigin.Transfer) {
                requireNoMoreThanSent(inbound, results);
            }
            boolean putawayPending = Locations.managesBins(inbound.locationCode());
            InboundStatus to = putawayPending ? InboundStatus.INSPECTED : InboundStatus.STORED;
            int changed = inboundRepository.changeStatus(inboundId, InboundStatus.ARRIVED, to);
            if (changed == 0) {
                throw new IllegalArgumentException(
                    "검수할 수 없는 상태입니다: " + inboundId + " (" + inbound.status() + ")");
            }
            inboundRepository.recordInspection(inboundId, results);
            switch (inbound.origin()) {
                case InboundOrigin.Supplier() ->
                    inboundEventRecorder.inboundInspected(inboundId, inbound.locationCode(), results, putawayPending);
                case InboundOrigin.Transfer(long transferId) ->
                    transferService.receive(transferId, results, putawayPending);
            }
            return inboundRepository.find(inboundId).orElseThrow();
        });
    }

    public List<PutawayLine> putawayGuide(long inboundId) {
        Inbound inbound = find(inboundId);

        List<InboundLine> goods = inbound.lines().stream()
            .filter(line -> line.goodQuantity() != null && line.goodQuantity() > 0)
            .toList();
        return productBinService.guide(inbound.locationCode(), goods,
            InboundLine::productId, InboundLine::goodQuantity);
    }

    public Inbound store(long inboundId) {
        return transactionTemplate.execute(status -> {
            Inbound inbound = find(inboundId);
            if (inbound.status() != InboundStatus.INSPECTED) {
                throw new IllegalArgumentException(
                    "적치할 수 없는 상태입니다: " + inboundId + " (" + inbound.status() + ")");
            }
            List<PutawayLine> guided = putawayGuide(inboundId);
            productBinService.requireEveryBinAssigned(guided);
            if (inboundRepository.changeStatus(inboundId, InboundStatus.INSPECTED, InboundStatus.STORED) == 0) {
                throw new ConcurrentUpdateException(
                    "적치하는 사이에 상태가 바뀌었습니다. 다시 시도해 주세요: " + inboundId);
            }
            inboundEventRecorder.inboundStored(inbound, guided);
            return inboundRepository.find(inboundId).orElseThrow();
        });
    }

    public Inbound find(long inboundId) {
        return inboundRepository.find(inboundId)
            .orElseThrow(() -> new IllegalArgumentException("입고 문서가 없습니다: " + inboundId));
    }

    private void requireRegistered(List<InboundLine> lines) {
        List<Long> unregistered = productRepository.findUnregistered(
            lines.stream().map(InboundLine::productId).toList());
        if (!unregistered.isEmpty()) {
            throw new IllegalArgumentException("등록되지 않은 상품이 있습니다: 상품 " + unregistered);
        }
    }

    private void requireNoMoreThanSent(Inbound inbound, List<InspectionLine> results) {
        Map<Long, Integer> sent = inbound.lines().stream()
            .collect(Collectors.toMap(InboundLine::productId, InboundLine::expectedQuantity));
        results.forEach(result -> {
            int counted = result.goodQuantity() + result.defectiveQuantity();
            if (counted > sent.get(result.productId())) {
                throw new IllegalArgumentException("이동 입고는 보낸 수량보다 많이 받을 수 없습니다: "
                    + inbound.id() + ", 상품 " + result.productId()
                    + " (보냄 " + sent.get(result.productId()) + ", 검수 " + counted + ")");
            }
        });
    }

    private void requireAllItems(Inbound inbound, List<InspectionLine> results) {
        Set<Long> expected = inbound.lines().stream().map(InboundLine::productId).collect(Collectors.toSet());
        Set<Long> inspected = results.stream().map(InspectionLine::productId).collect(Collectors.toSet());
        if(!expected.equals(inspected)){
            throw new IllegalArgumentException("예정 품목을 모두 검수해야 합니다: " + inbound.id());
        }
    }
}
