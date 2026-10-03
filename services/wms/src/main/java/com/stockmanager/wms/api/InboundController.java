package com.stockmanager.wms.api;

import com.stockmanager.wms.application.InboundService;
import com.stockmanager.wms.domain.Inbound;
import com.stockmanager.wms.domain.InboundLine;
import com.stockmanager.wms.domain.InspectionLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
class InboundController {

    private final InboundService inboundService;

    InboundController(InboundService inboundService) {
        this.inboundService = inboundService;
    }

    @PostMapping("/inbounds")
    InboundResponse arrive(@Valid @RequestBody ArriveRequest request) {
        List<InboundLine> lines = request.items().stream()
            .map(item -> InboundLine.expected(item.productId(), item.quantity()))
            .toList();
        return InboundResponse.from(inboundService.arrive(request.locationCode(), lines));
    }

    @PostMapping("/inbounds/{inboundId}/inspect")
    InboundResponse inspect(@PathVariable long inboundId, @Valid @RequestBody InspectRequest request) {
        List<InspectionLine> results = request.items().stream()
            .map(item -> new InspectionLine(item.productId(), item.goodQuantity(), item.defectiveQuantity()))
            .toList();
        return InboundResponse.from(inboundService.inspect(inboundId, results));
    }

    @GetMapping("/inbounds/{inboundId}/putaway")
    List<PutawayResponse> putawayGuide(@PathVariable long inboundId) {
        return inboundService.putawayGuide(inboundId).stream().map(PutawayResponse::from).toList();
    }

    @PostMapping("/inbounds/{inboundId}/store")
    InboundResponse store(@PathVariable long inboundId) {
        return InboundResponse.from(inboundService.store(inboundId));
    }

    @GetMapping("/inbounds/{inboundId}")
    InboundResponse find(@PathVariable long inboundId) {
        return InboundResponse.from(inboundService.find(inboundId));
    }

    record ArriveRequest(@NotBlank String locationCode, @NotEmpty @Valid List<Item> items){
        record Item(@NotNull Long productId, @NotNull @Positive Integer quantity) {
        }
    }

    record InspectRequest(@NotEmpty @Valid List<Item> items){
        record Item(@NotNull Long productId, @NotNull @PositiveOrZero Integer goodQuantity,
                    @NotNull @PositiveOrZero Integer defectiveQuantity) {
        }
    }

    record InboundResponse(long inboundId, String locationCode, Long transferId ,String status, List<Item> items){
        record Item(long productId, int expectedQuantity, Integer goodQuantity, Integer defectiveQuantity) {
        }

        static InboundResponse from(Inbound inbound) {
            return new InboundResponse(inbound.id(), inbound.locationCode(), inbound.origin().transferIdOrNull(),
                inbound.status().name(),
                inbound.lines().stream()
                    .map(line -> new Item(line.productId(), line.expectedQuantity(),
                        line.goodQuantity(), line.defectiveQuantity()))
                    .toList());
        }
    }



}
