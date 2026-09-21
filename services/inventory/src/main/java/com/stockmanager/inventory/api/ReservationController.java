package com.stockmanager.inventory.api;

import com.stockmanager.inventory.application.ReservationService;
import com.stockmanager.inventory.domain.ReserveCommand;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/reservations")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping
    public ReservationResponse reserve(
        @Valid @RequestBody ReservationRequest request,
        @RequestHeader(value = "X-User-Id", defaultValue = "unknown") String actor){

        List<ReserveCommand.Item> items = request.items().stream()
            .map(item -> new ReserveCommand.Item(item.productId(), item.quantity()))
            .toList();
        ReserveCommand command = new ReserveCommand(
            request.refType(), request.refId(), request.locationCode(), items);
        return new ReservationResponse(reservationService.reserve(command, actor));
    }

}
