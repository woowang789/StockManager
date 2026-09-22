package com.stockmanager.sales.application;

import com.stockmanager.sales.domain.OrderLine;
import com.stockmanager.sales.domain.SalesOrder;
import com.stockmanager.sales.infrastructure.SalesOrderRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Optional;

@Service
public class OrderService {

    private final SalesOrderRepository salesOrderRepository;

    OrderService(SalesOrderRepository salesOrderRepository) {
        this.salesOrderRepository = salesOrderRepository;
    }

    public SalesOrder place(String orderNo, List<OrderLine> lines) {
        Optional<SalesOrder> placed = salesOrderRepository.findByOrderNo(orderNo);
        if (placed.isPresent()) {
            return placed.get();
        }

        try {
            return salesOrderRepository.save(SalesOrder.place(orderNo, lines));
        } catch (DataIntegrityViolationException exception) {
            return salesOrderRepository.findByOrderNo(orderNo).orElseThrow(() -> exception);
        }
    }

    public Optional<SalesOrder> find(String orderNo) {
        return salesOrderRepository.findByOrderNo(orderNo);
    }
}
