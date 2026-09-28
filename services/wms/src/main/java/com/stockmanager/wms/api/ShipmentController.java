package com.stockmanager.wms.api;

import com.stockmanager.wms.application.ShipmentService;
import com.stockmanager.wms.domain.Shipment;
import com.stockmanager.wms.domain.ShipmentLine;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
class ShipmentController {
    private final ShipmentService shipmentService;

    ShipmentController(ShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @PostMapping("/shipments/{orderNo}/shortage")
    ShipmentResponse cancelForShortage(@PathVariable String orderNo, @Valid @RequestBody ShortageRequest request) {
        List<ShipmentLine> shortages = request.items().stream()
            .map(item -> new ShipmentLine(item.productId(), item.quantity()))
            .toList();
        return ShipmentResponse.from(shipmentService.cancelForShortage(orderNo, shortages));
    }

    @PostMapping("/shipments/{orderNo}/pick")
    ShipmentResponse pick(@PathVariable String orderNo) {
        return ShipmentResponse.from(shipmentService.pick(orderNo));
    }

    @PostMapping("/shipments/{orderNo}/pack")
    ShipmentResponse pack(@PathVariable String orderNo) {
        return ShipmentResponse.from(shipmentService.pack(orderNo));
    }

    @PostMapping("/shipments/{orderNo}/ship")
    ShipmentResponse ship(@PathVariable String orderNo) {
        return ShipmentResponse.from(shipmentService.ship(orderNo));
    }

    @GetMapping("/shipments/{orderNo}")
    ShipmentResponse find(@PathVariable String orderNo) {
        return ShipmentResponse.from(shipmentService.find(orderNo));
    }

    record ShortageRequest(@NotEmpty @Valid List<Item> items){

        record Item(@NotNull Long productId, @NotNull @Positive Integer quantity) {
        }
    }


    record ShipmentResponse(String orderNo, String locationCode, String status, List<Item> items) {

        record Item(long productId, int quantity) {
        }

        static ShipmentResponse from(Shipment shipment) {
            return new ShipmentResponse(shipment.orderNo(), shipment.locationCode(), shipment.status().name(),
                shipment.lines().stream().map(line -> new Item(line.productId(), line.quantity())).toList());
        }
    }

}
