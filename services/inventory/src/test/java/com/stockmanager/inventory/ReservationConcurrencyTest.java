package com.stockmanager.inventory;

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

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class ReservationConcurrencyTest {

    private static final int STOCK = 60;
    private static final int ATTEMPTS = 100;
    private static final int THREADS = 16;

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
    @DisplayName("동시에 100건을 예약해도 가용 수량만큼만 성공한다")
    void reservesOnlyUpToAvailable() throws Exception {
        stockAdjustmentService.adjust(
            new StockChange("DC", 1, StockState.AVAILABLE, STOCK), AdjustmentReason.COUNT_DIFF, "test");

        int succeeded = reserveConcurrently();

        assertThat(succeeded).isEqualTo(STOCK);
        assertThat(quantityOf("available")).isZero();
        assertThat(quantityOf("reserved")).isEqualTo(STOCK);
        assertThat(activeReservationQuantity()).isEqualTo(STOCK);

        // 원장 합계가 현재고와 같다. 조정으로 넣은 +60과 예약으로 뺀 -60이 상쇄된다
        assertThat(ledgerSumOf("AVAILABLE")).isZero();
        assertThat(ledgerSumOf("RESERVED")).isEqualTo(STOCK);

        // 두 예약이 같은 잔액을 읽고 지나갔다면 같은 값이 두 번 적힌다
        assertThat(distinctBalancesOf("AVAILABLE")).isEqualTo(STOCK);
    }

    private int reserveConcurrently() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
            for (int i = 0; i < ATTEMPTS; i++) {
                String refId = "ORD-" + i;
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        reservationService.reserve(commandFor(refId), "test");
                        return true;
                    } catch (InsufficientStockException exception) {
                        return false;
                    }
                }));
            }
            // 스레드를 다 만든 뒤에 한꺼번에 출발시켜야 실제로 겹친다
            start.countDown();
        }

        int succeeded = 0;
        for (Future<Boolean> result : results) {
            if (result.get()) {
                succeeded++;
            }
        }
        return succeeded;
    }

    private ReserveCommand commandFor(String refId) {
        return new ReserveCommand("ORDER", refId, "DC", List.of(new ReserveCommand.Item(1, 1)));
    }

    private int quantityOf(String column) {
        return jdbcClient.sql("SELECT %s FROM stock WHERE location_code = 'DC' AND product_id = 1".formatted(column))
            .query(Integer.class)
            .single();
    }

    private int activeReservationQuantity() {
        return jdbcClient.sql("SELECT COALESCE(SUM(quantity), 0) FROM reservation WHERE status = 'ACTIVE'")
            .query(Integer.class)
            .single();
    }

    private int ledgerSumOf(String state) {
        return jdbcClient.sql("SELECT COALESCE(SUM(delta), 0) FROM stock_entry WHERE state = ?")
            .param(state)
            .query(Integer.class)
            .single();
    }

    private int distinctBalancesOf(String state) {
        return jdbcClient.sql("SELECT COUNT(DISTINCT balance_after) FROM stock_entry WHERE state = ? AND delta < 0")
            .param(state)
            .query(Integer.class)
            .single();
    }

}
