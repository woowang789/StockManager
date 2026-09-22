package com.stockmanager.sales.infrastructure;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.PostExchange;
import java.util.List;


public interface InventoryClient {

    @PostExchange("/reservations")
    void reserve(@RequestBody ReservationRequest request);

    record ReservationRequest(String refType, String refId, String locationCode, List<Item> items){

        public record Item(long productId, int quantity) {
        }
    }
}
