package com.stockmanager.wms.application;

import com.stockmanager.common.web.ConcurrentUpdateException;
import com.stockmanager.wms.domain.PutawayLine;
import com.stockmanager.wms.domain.Reputaway;
import com.stockmanager.wms.domain.ReputawayLine;
import com.stockmanager.wms.domain.ReputawayOrigin;
import com.stockmanager.wms.domain.ReputawayStatus;
import com.stockmanager.wms.infrastructure.ReputawayEventRecorder;
import com.stockmanager.wms.infrastructure.ReputawayRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ReputawayService {

    private final ReputawayRepository reputawayRepository;
    private final ProductBinService productBinService;
    private final ReputawayEventRecorder reputawayEventRecorder;
    private final TransactionTemplate transactionTemplate;

    ReputawayService(ReputawayRepository reputawayRepository, ProductBinService productBinService,
                     ReputawayEventRecorder reputawayEventRecorder, TransactionTemplate transactionTemplate) {
        this.reputawayRepository = reputawayRepository;
        this.productBinService = productBinService;
        this.reputawayEventRecorder = reputawayEventRecorder;
        this.transactionTemplate = transactionTemplate;
    }

    public void open(String locationCode, ReputawayOrigin origin, List<ReputawayLine> lines,
                     List<ReputawayLine> shortages) {
        Map<Long, Integer> missing = shortages.stream()
            .collect(Collectors.toMap(ReputawayLine::productId, ReputawayLine::quantity));
        List<ReputawayLine> found = lines.stream()
            .map(line -> new ReputawayLine(
                line.productId(), line.quantity() - missing.getOrDefault(line.productId(), 0)))
            .filter(line -> line.quantity() > 0)
            .toList();
        if (!found.isEmpty()) {
            reputawayRepository.insert(locationCode, origin, found);
        }
    }

    public List<Reputaway> findReady() {
        return reputawayRepository.findReady();
    }

    public List<PutawayLine> putawayGuide(long reputawayId) {
        Reputaway reputaway = find(reputawayId);
        return productBinService.guide(reputaway.locationCode(), reputaway.lines(),
            ReputawayLine::productId, ReputawayLine::quantity);
    }

    public Reputaway store(long reputawayId) {
        return transactionTemplate.execute(status -> {
            Reputaway reputaway = find(reputawayId);
            if (reputaway.status() != ReputawayStatus.READY) {
                throw new IllegalArgumentException(
                    "적치할 수 없는 상태입니다: " + reputawayId + " (" + reputaway.status() + ")");
            }
            List<PutawayLine> guided = putawayGuide(reputawayId);
            productBinService.requireEveryBinAssigned(guided);
            if (reputawayRepository.changeStatus(reputawayId, ReputawayStatus.READY, ReputawayStatus.STORED) == 0) {
                throw new ConcurrentUpdateException(
                    "적치하는 사이에 상태가 바뀌었습니다. 다시 시도해 주세요: " + reputawayId);
            }
            reputawayEventRecorder.reputawayStored(reputaway, guided);
            return find(reputawayId);
        });
    }

    public Reputaway find(long reputawayId) {
        return reputawayRepository.find(reputawayId)
            .orElseThrow(() -> new IllegalArgumentException("재적치 작업이 없습니다: " + reputawayId));
    }
}
