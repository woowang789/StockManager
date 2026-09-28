package com.stockmanager.wms.application;

import com.stockmanager.wms.domain.Inbound;
import com.stockmanager.wms.domain.InboundLine;
import com.stockmanager.wms.domain.InboundStatus;
import com.stockmanager.wms.domain.InspectionLine;
import com.stockmanager.wms.infrastructure.InboundEventRecorder;
import com.stockmanager.wms.infrastructure.InboundRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class InboundService {

    private static final String STORE = "STORE";

    private final InboundRepository inboundRepository;
    private final InboundEventRecorder inboundEventRecorder;
    private final TransactionTemplate transactionTemplate;

    InboundService(InboundRepository inboundRepository, InboundEventRecorder inboundEventRecorder, TransactionTemplate transactionTemplate) {
        this.inboundRepository = inboundRepository;
        this.inboundEventRecorder = inboundEventRecorder;
        this.transactionTemplate = transactionTemplate;
    }

    public Inbound arrive(String locationCode, List<InboundLine> lines) {
        long inboundId = inboundRepository.insert(locationCode, lines);
        return inboundRepository.find(inboundId).orElseThrow();
    }

    public Inbound inspect(long inboundId, List<InspectionLine> results) {
        return transactionTemplate.execute(status -> {
            Inbound inbound = inboundRepository.find(inboundId)
                .orElseThrow(() -> new IllegalArgumentException("입고 문서가 없습니다: " + inboundId));
            requireAllItems(inbound, results);
            InboundStatus to = STORE.equals(inbound.locationCode())
                ? InboundStatus.STORED
                : InboundStatus.INSPECTED;
            int changed = inboundRepository.changeStatus(inboundId, InboundStatus.ARRIVED, to);
            if (changed == 0) {
                throw new IllegalArgumentException(
                    "검수할 수 없는 상태입니다: " + inboundId + " (" + inbound.status() + ")");
            }
            inboundRepository.recordInspection(inboundId, results);
            inboundEventRecorder.inboundInspected(inboundId, inbound.locationCode(), results);
            return inboundRepository.find(inboundId).orElseThrow();
        });
    }

    public Inbound find(long inboundId) {
        return inboundRepository.find(inboundId)
            .orElseThrow(() -> new IllegalArgumentException("입고 문서가 없습니다: " + inboundId));
    }

    private void requireAllItems(Inbound inbound, List<InspectionLine> results) {
        Set<Long> expected = inbound.lines().stream().map(InboundLine::productId).collect(Collectors.toSet());
        Set<Long> inspected = results.stream().map(InspectionLine::productId).collect(Collectors.toSet());
        if(!expected.equals(inspected)){
            throw new IllegalArgumentException("예정 품목을 모두 검수해야 합니다: " + inbound.id());
        }
    }
}
