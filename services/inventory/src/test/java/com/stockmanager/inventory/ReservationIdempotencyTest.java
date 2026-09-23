package com.stockmanager.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.stockmanager.inventory.application.ReservationService;
import com.stockmanager.inventory.application.StockAdjustmentService;
import com.stockmanager.inventory.domain.AdjustmentReason;
import com.stockmanager.inventory.domain.InsufficientStockException;
import com.stockmanager.inventory.domain.ReserveCommand;
import com.stockmanager.inventory.domain.StockChange;
import com.stockmanager.inventory.domain.StockState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReservationIdempotencyTest {

    private static final int RETRIES = 10;
    private static final int ROUNDS = 10;

    @Autowired
    ReservationService reservationService;

    @Autowired
    StockAdjustmentService stockAdjustmentService;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void clearAll() {
        jdbcClient.sql("DELETE FROM reservation").update();
        jdbcClient.sql("DELETE FROM stock_entry").update();
        jdbcClient.sql("DELETE FROM stock_movement").update();
        jdbcClient.sql("DELETE FROM stock").update();
    }

    @Test
    @DisplayName("같은 예약을 다시 보내면 처음 전표 번호를 돌려주고 아무것도 바뀌지 않는다")
    void returnsFirstResultOnRetry() {
        given(3);

        long first = reservationService.reserve(orderOf("ORD-1", 3), "sales");
        long retried = reservationService.reserve(orderOf("ORD-1", 3), "sales");

        assertThat(retried).isEqualTo(first);
        assertThat(quantityOf("available")).isZero();
        assertThat(quantityOf("reserved")).isEqualTo(3);
        assertThat(reservationRowCount()).isEqualTo(1);
        assertThat(reserveMovementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 예약이 동시에 여러 번 들어와도 한 번만 반영된다")
    void reservesOnceUnderConcurrentRetries() throws Exception{
        given(10);
        List<Long> movementIds = new ArrayList<>();
        for(Future<Long> result : reserveConcurrently(orderOf("ORD-1", 3))){
            movementIds.add(result.get());
        }

        assertThat(movementIds).hasSize(RETRIES).containsOnly(movementIds.getFirst());
        assertThat(quantityOf("available")).isEqualTo(7);
        assertThat(quantityOf("reserved")).isEqualTo(3);
        assertThat(reservationRowCount()).isEqualTo(1);
        assertThat(reserveMovementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("재고가 모자란 같은 예약이 동시에 여러 번 와도 모두 재고 부족으로 끝난다")
    void rejectsAllConcurrentRetriesWhenStockIsShort() throws Exception {
        given(1);

        for (int round = 1; round <= ROUNDS; round++) {
            for (Future<Long> result : reserveConcurrently(orderOf("ORD-" + round, 3))) {
                assertThatThrownBy(result::get).hasCauseInstanceOf(InsufficientStockException.class);
            }
        }
        assertThat(quantityOf("available")).isEqualTo(1);
        assertThat(reservationRowCount()).isZero();
        assertThat(reserveMovementCount()).isZero();
    }


    private List<Future<Long>> reserveConcurrently(ReserveCommand command) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(RETRIES)) {
            for (int i = 0; i < RETRIES; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return reservationService.reserve(command, "sales");
                }));
            }
            start.countDown();
        }

        return results;
    }

    private void given(int quantity) {
        stockAdjustmentService.adjust(
            new StockChange("DC", 1, StockState.AVAILABLE, quantity), AdjustmentReason.COUNT_DIFF, "test");
    }

    private ReserveCommand orderOf(String refId, int quantity) {
        return new ReserveCommand("ORDER", refId, "DC", List.of(new ReserveCommand.Item(1, quantity)));
    }

    private int quantityOf(String column) {
        return jdbcClient.sql("SELECT %s FROM stock WHERE location_code = 'DC' AND product_id = 1".formatted(column))
            .query(Integer.class)
            .single();
    }

    private int reservationRowCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM reservation")
            .query(Integer.class)
            .single();
    }

    private int reserveMovementCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM stock_movement WHERE type = 'RESERVE'")
            .query(Integer.class)
            .single();
    }
}
