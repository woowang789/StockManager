package com.stockmanager.sales.infrastructure;

import com.stockmanager.sales.domain.SalesOrder;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {

    @EntityGraph(attributePaths = "lines")
    Optional<SalesOrder> findByOrderNo(String orderNo);
}
