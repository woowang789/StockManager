package com.stockmanager.sales.infrastructure;

import com.stockmanager.sales.domain.PosSale;
import com.stockmanager.sales.domain.PosSaleStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PosSaleRepository extends JpaRepository<PosSale, Long> {

    @EntityGraph(attributePaths = "lines")
    Optional<PosSale> findByReceiptNo(String receiptNo);

    @EntityGraph(attributePaths = "lines")
    List<PosSale> findByStatusAndCreatedAtBefore(PosSaleStatus status, Instant createdBefore);

    @Transactional
    @Modifying
    @Query("UPDATE PosSale s SET s.status = :to WHERE s.id = :id AND s.status = :from")
    int changeStatus(long id, PosSaleStatus from, PosSaleStatus to);
}
