package com.stockmanager.sales.infrastructure;

import com.stockmanager.sales.domain.OrderStatus;
import com.stockmanager.sales.domain.SalesOrder;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;

public interface SalesOrderRepository extends JpaRepository<SalesOrder, Long> {

    @EntityGraph(attributePaths = "lines")
    Optional<SalesOrder> findByOrderNo(String orderNo);

    @Transactional
    @Modifying
    @Query("UPDATE SalesOrder o set o.status = :to WHERE o.id = :id AND o.status = :from")
    int changeStatus(long id, OrderStatus from, OrderStatus to);
}
