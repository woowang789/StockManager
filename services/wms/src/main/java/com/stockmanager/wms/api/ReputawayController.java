package com.stockmanager.wms.api;

import com.stockmanager.wms.application.ReputawayService;
import com.stockmanager.wms.domain.Reputaway;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
public class ReputawayController {

    private final ReputawayService reputawayService;

    ReputawayController(ReputawayService reputawayService) {
        this.reputawayService = reputawayService;
    }

    @GetMapping("/reputaways")
    List<ReputawayResponse> findReady() {
        return reputawayService.findReady().stream().map(ReputawayResponse::from).toList();
    }

    @GetMapping("/reputaways/{reputawayId}/putaway")
    List<PutawayResponse> putawayGuide(@PathVariable long reputawayId) {
        return reputawayService.putawayGuide(reputawayId).stream().map(PutawayResponse::from).toList();
    }

    @PostMapping("/reputaways/{reputawayId}/store")
    ReputawayResponse store(@PathVariable long reputawayId) {
        return ReputawayResponse.from(reputawayService.store(reputawayId));
    }

    record ReputawayResponse(long reputawayId, String locationCode, String orderNo, Long transferId,
                             String status, List<Item> items) {

        record Item(long productId, int quantity) {
        }

        static ReputawayResponse from(Reputaway reputaway) {
            return new ReputawayResponse(reputaway.id(), reputaway.locationCode(),
                reputaway.origin().orderNoOrNull(), reputaway.origin().transferIdOrNull(),
                reputaway.status().name(),
                reputaway.lines().stream().map(line -> new Item(line.productId(), line.quantity())).toList());
        }

    }


}
