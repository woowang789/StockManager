package com.stockmanager.sales.application;

import com.stockmanager.sales.domain.OrderLine;
import com.stockmanager.sales.domain.OrderStatus;
import com.stockmanager.sales.domain.SalesOrder;
import com.stockmanager.sales.infrastructure.InventoryClient;
import com.stockmanager.sales.infrastructure.InventoryClient.ReservationRequest;
import com.stockmanager.sales.infrastructure.SalesOrderRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final String REF_TYPE = "ORDER";

    private final SalesOrderRepository salesOrderRepository;
    private final InventoryClient inventoryClient;
    private final CircuitBreaker inventoryCircuitBreaker;

    OrderService(SalesOrderRepository salesOrderRepository,InventoryClient inventoryClient,
                 CircuitBreaker inventoryCircuitBreaker) {
        this.salesOrderRepository = salesOrderRepository;
        this.inventoryClient = inventoryClient;
        this.inventoryCircuitBreaker = inventoryCircuitBreaker;
    }

    public SalesOrder place(String orderNo, List<OrderLine> lines) {
        Optional<SalesOrder> placed = salesOrderRepository.findByOrderNo(orderNo);
        if (placed.isPresent()) {
            return placed.get();
        }
        SalesOrder order;
        try {
            order = salesOrderRepository.save(SalesOrder.place(orderNo, lines));
        } catch (DataIntegrityViolationException exception) {
            return salesOrderRepository.findByOrderNo(orderNo).orElseThrow(() -> exception);
        }
        return reserve(order);
    }

    public void confirmPending(Instant createdBefore) {
        for (SalesOrder order : salesOrderRepository.findByStatusAndCreatedAtBefore(OrderStatus.PENDING, createdBefore)) {
            reserve(order);
        }
    }

    public Optional<SalesOrder> find(String orderNo) {
        return salesOrderRepository.findByOrderNo(orderNo);
    }

    private SalesOrder reserve(SalesOrder order) {
        OrderStatus result;
        try {
            inventoryCircuitBreaker.executeRunnable(() -> inventoryClient.reserve(toReservationRequest(order)));
            result = OrderStatus.RESERVED;
        } catch (HttpClientErrorException.Conflict exception) {
            result = OrderStatus.REJECTED;
        } catch (CallNotPermittedException exception) {
            log.warn("주문 {}: inventory 서킷이 열려 있어 예약을 요청하지 않습니다", order.getOrderNo());
            return order;
        } catch (RestClientException exception) {
            log.warn("주문 {}의 예약 결과를 모릅니다. PENDING으로 두고 다시 시도합니다: {}", order.getOrderNo(), exception.toString());
            return order;
        }
        salesOrderRepository.changeStatus(order.getId(), OrderStatus.PENDING, result);
        return salesOrderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();
    }


    private ReservationRequest toReservationRequest(SalesOrder order) {
        List<ReservationRequest.Item> items = order.getLines().stream()
            .map(line -> new ReservationRequest.Item(line.productId(), line.quantity()))
            .toList();
        return new ReservationRequest(REF_TYPE, order.getOrderNo(), order.getLocationCode(), items);
    }

}
