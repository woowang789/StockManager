package com.stockmanager.sales.api;

import com.stockmanager.sales.domain.OrderStatus;
import com.stockmanager.sales.domain.SalesOrder;
import java.util.List;

public record OrderResponse(String orderNo, String locationCode, OrderStatus status, List<Item> items) {

    public record Item(long productId, int quantity) {
    }

    static OrderResponse from(SalesOrder order) {
        List<Item> items = order.getLines().stream()
            .map(line -> new Item(line.productId(), line.quantity()))
            .toList();
        return new OrderResponse(order.getOrderNo(), order.getLocationCode(), order.getStatus(), items);
    }
}
