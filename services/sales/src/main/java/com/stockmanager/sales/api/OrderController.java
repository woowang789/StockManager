package com.stockmanager.sales.api;

import com.stockmanager.sales.application.OrderService;
import com.stockmanager.sales.domain.OrderLine;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderService orderService;

    OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public OrderResponse place(@Valid @RequestBody PlaceOrderRequest request) {
        List<OrderLine> lines = request.items().stream()
            .map(item -> new OrderLine(item.productId(), item.quantity()))
            .toList();
        return OrderResponse.from(orderService.place(request.orderNo(), lines));
    }

    @GetMapping("/{orderNo}")
    public ResponseEntity<OrderResponse> get(@PathVariable String orderNo) {
        return orderService.find(orderNo)
            .map(order -> ResponseEntity.ok(OrderResponse.from(order)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
