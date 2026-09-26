package com.stockmanager.sales.application;

import com.stockmanager.sales.domain.OrderLine;
import com.stockmanager.sales.domain.OrderStatus;
import com.stockmanager.sales.domain.SalesOrder;
import com.stockmanager.sales.infrastructure.InventoryClient;
import com.stockmanager.sales.infrastructure.InventoryClient.ReservationRequest;
import com.stockmanager.sales.infrastructure.OrderEventRecorder;
import com.stockmanager.sales.infrastructure.SalesOrderRepository;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
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
    private final OrderEventRecorder orderEventRecorder;
    private final TransactionTemplate transactionTemplate;

    OrderService(SalesOrderRepository salesOrderRepository,InventoryClient inventoryClient,
                 CircuitBreaker inventoryCircuitBreaker,OrderEventRecorder orderEventRecorder,
                 TransactionTemplate transactionTemplate) {
        this.salesOrderRepository = salesOrderRepository;
        this.inventoryClient = inventoryClient;
        this.inventoryCircuitBreaker = inventoryCircuitBreaker;
        this.orderEventRecorder = orderEventRecorder;
        this.transactionTemplate = transactionTemplate;
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

    public SalesOrder requestCancel(String orderNo) {
        SalesOrder order = salesOrderRepository.findByOrderNo(orderNo)
            .orElseThrow(() -> new IllegalArgumentException("주문이 없습니다: " + orderNo));
        return transactionTemplate.execute(status -> {
            int changed = salesOrderRepository.changeStatus(
                order.getId(), OrderStatus.RESERVED, OrderStatus.CANCEL_REQUESTED);
            SalesOrder current = salesOrderRepository.findByOrderNo(orderNo).orElseThrow();
            if (changed == 1) {
                orderEventRecorder.orderCancelRequested(orderNo);
            } else if (current.getStatus() != OrderStatus.CANCEL_REQUESTED) {
                throw new IllegalArgumentException(
                    "취소할 수 없는 상태입니다: " + orderNo + " (" + current.getStatus() + ")");
            }
            return current;
        });
    }

    public void markShipped(String orderNo) {
        SalesOrder order = salesOrderRepository.findByOrderNo(orderNo)
            .orElseThrow(() -> new IllegalArgumentException("주문이 없습니다: " + orderNo));
        int changed = salesOrderRepository.changeStatus(order.getId(), OrderStatus.RESERVED, OrderStatus.SHIPPED);
        if (changed == 0) {
            changed = salesOrderRepository.changeStatus(
                order.getId(), OrderStatus.CANCEL_REQUESTED, OrderStatus.SHIPPED);
        }
        if (changed == 0) {
            log.info("이미 끝난 주문입니다: {} ({})", orderNo, order.getStatus());
        }
    }

    public Optional<SalesOrder> find(String orderNo) {
        return salesOrderRepository.findByOrderNo(orderNo);
    }

    private SalesOrder reserve(SalesOrder order) {
        Optional<OrderStatus> result = requestReservation(order);
        if (result.isEmpty()) {
            return order;
        }
        return confirm(order, result.get());
    }

    private Optional<OrderStatus> requestReservation(SalesOrder order) {
        try {
            inventoryCircuitBreaker.executeRunnable(() -> inventoryClient.reserve(toReservationRequest(order)));
            return Optional.of(OrderStatus.RESERVED);
        } catch (HttpClientErrorException.Conflict exception) {
            return Optional.of(OrderStatus.REJECTED);
        } catch (CallNotPermittedException exception) {
            log.warn("주문 {}: inventory 서킷이 열려 있어 예약을 요청하지 않습니다", order.getOrderNo());
            return Optional.empty();
        } catch (RestClientException exception) {
            log.warn("주문 {}의 예약 결과를 모릅니다. PENDING으로 두고 다시 시도합니다: {}", order.getOrderNo(), exception.toString());
            return Optional.empty();
        }
    }

    private SalesOrder confirm(SalesOrder order, OrderStatus result) {
        return transactionTemplate.execute(status -> {
            int changed = salesOrderRepository.changeStatus(order.getId(), OrderStatus.PENDING, result);
            SalesOrder confirmed = salesOrderRepository.findByOrderNo(order.getOrderNo()).orElseThrow();

            if (changed == 1 && result == OrderStatus.RESERVED) {
                orderEventRecorder.orderReserved(confirmed);
            }
            return confirmed;
        });
    }


    private ReservationRequest toReservationRequest(SalesOrder order) {
        List<ReservationRequest.Item> items = order.getLines().stream()
            .map(line -> new ReservationRequest.Item(line.productId(), line.quantity()))
            .toList();
        return new ReservationRequest(REF_TYPE, order.getOrderNo(), order.getLocationCode(), items);
    }

}
