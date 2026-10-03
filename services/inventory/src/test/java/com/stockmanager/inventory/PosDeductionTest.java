package com.stockmanager.inventory;

import com.stockmanager.inventory.application.PosDeductionService;
import com.stockmanager.inventory.application.ReservationService;
import com.stockmanager.inventory.application.StockAdjustmentService;
import com.stockmanager.inventory.domain.AdjustmentReason;
import com.stockmanager.inventory.domain.InsufficientStockException;
import com.stockmanager.inventory.domain.PosDeductionCommand;
import com.stockmanager.inventory.domain.ReserveCommand;
import com.stockmanager.inventory.domain.StockChange;
import com.stockmanager.inventory.domain.StockState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
public class PosDeductionTest {

    // 교착은 동시에 부딪히는 수가 많을 때 드러난다
    private static final int RETRIES = 20;
    private static final int ROUNDS = 10;

    @Autowired
    MockMvc mockMvc;

    @Autowired
    PosDeductionService posDeductionService;

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
    @DisplayName("POS 판매는 예약을 거치지 않고 가용에서 바로 빠진다")
    void deductsAvailableDirectly() throws Exception {
        given(1, 10);

        deduct("""
            {"refType":"POS","refId":"R-1","locationCode":"STORE",
             "items":[{"productId":1,"quantity":3}]}
            """)
            .andExpect(status().isOk());

        assertThat(quantityOf("available", 1)).isEqualTo(7);
        assertThat(quantityOf("reserved", 1)).isZero();
        // 예약과 달리 짝이 되는 줄이 없다. 3개는 회사 밖(고객)으로 나갔다
        assertThat(entriesOfLastMovement()).containsExactly("AVAILABLE:-3:7");
        assertThat(keyOfLastMovement()).isEqualTo("POS:R-1:POS_SALE");
    }

    @Test
    @DisplayName("항목이 하나라도 모자라면 409로 거절하고 한 개도 빼지 않는다")
    void deductsAllOrNothing() throws Exception {
        given(1, 10);
        given(2, 1);

        deduct("""
            {"refType":"POS","refId":"R-2","locationCode":"STORE",
             "items":[{"productId":1,"quantity":3},{"productId":2,"quantity":5}]}
            """)
            .andExpect(status().isConflict());

        assertThat(quantityOf("available", 1)).isEqualTo(10);
        assertThat(posSaleMovementCount()).isZero();
    }

    @Test
    @DisplayName("예약된 재고는 팔 수 없다")
    void cannotSellReservedStock() throws Exception {
        given(1, 3);
        // 센터로 보낼 2개가 이동 요청으로 잡혀 있다. 선반에는 3개가 있어도 팔 수 있는 것은 1개다
        reservationService.reserve(
            new ReserveCommand("TRANSFER", "1", "STORE", List.of(new ReserveCommand.Item(1, 2))), "wms");

        deduct("""
            {"refType":"POS","refId":"R-3","locationCode":"STORE",
             "items":[{"productId":1,"quantity":2}]}
            """)
            .andExpect(status().isConflict());

        assertThat(quantityOf("available", 1)).isEqualTo(1);
        assertThat(quantityOf("reserved", 1)).isEqualTo(2);
    }

    // 도메인이 대신 막아 줄 수 없는 경우라서, 목록에 @Valid가 빠지면 이 테스트만 깨진다
    @Test
    @DisplayName("상품 ID가 빠진 항목이 있으면 받지 않는다")
    void rejectsItemWithoutProductId() throws Exception {
        deduct("""
            {"refType":"POS","refId":"R-4","locationCode":"STORE",
             "items":[{"quantity":1}]}
            """)
            .andExpect(status().isBadRequest());

        assertThat(posSaleMovementCount()).isZero();
    }

    @Test
    @DisplayName("응답을 잃어 같은 영수증으로 다시 보내면 처음 전표 번호를 돌려주고 한 번만 뺀다")
    void returnsFirstResultOnRetry() {
        // 딱 한 번 팔 만큼만 둔다. 다시 실행되면 재고 부족이 난다
        given(1, 3);

        long first = posDeductionService.deduct(receiptOf("R-5", 3), "sales");
        long retried = posDeductionService.deduct(receiptOf("R-5", 3), "sales");

        assertThat(retried).isEqualTo(first);
        assertThat(quantityOf("available", 1)).isZero();
        assertThat(posSaleMovementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 영수증이 동시에 여러 번 와도 한 번만 뺀다")
    void deductsOnceUnderConcurrentRetries() throws Exception {
        given(1, 10);

        List<Long> movementIds = new ArrayList<>();
        for (Future<Long> result : deductConcurrently(receiptOf("R-6", 3))) {
            movementIds.add(result.get());
        }

        assertThat(movementIds).hasSize(RETRIES).containsOnly(movementIds.getFirst());
        assertThat(quantityOf("available", 1)).isEqualTo(7);
        assertThat(posSaleMovementCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("재고가 모자란 같은 영수증이 동시에 여러 번 와도 모두 재고 부족으로 끝난다")
    void rejectsAllConcurrentRetriesWhenStockIsShort() {
        given(1, 1);

        // 교착은 스레드가 정확히 겹칠 때만 나서, 한 번으로는 잡히지 않을 수 있다. 영수증을 바꿔 가며 여러 번 한다
        for (int round = 1; round <= ROUNDS; round++) {
            for (Future<Long> result : deductConcurrently(receiptOf("R-7-" + round, 3))) {
                assertThatThrownBy(result::get).hasCauseInstanceOf(InsufficientStockException.class);
            }
        }
        assertThat(quantityOf("available", 1)).isEqualTo(1);
        assertThat(posSaleMovementCount()).isZero();
    }

    private List<Future<Long>> deductConcurrently(PosDeductionCommand command) {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Long>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(RETRIES)) {
            for (int i = 0; i < RETRIES; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return posDeductionService.deduct(command, "sales");
                }));
            }
            start.countDown();
        }
        return results;
    }

    private PosDeductionCommand receiptOf(String receiptNo, int quantity) {
        return new PosDeductionCommand("POS", receiptNo, "STORE", List.of(new PosDeductionCommand.Item(1, quantity)));
    }

    private void given(long productId, int quantity) {
        stockAdjustmentService.adjust(
            new StockChange("STORE", productId, StockState.AVAILABLE, quantity),
            AdjustmentReason.COUNT_DIFF, "test");
    }

    private ResultActions deduct(String body) throws Exception {
        return mockMvc.perform(post("/pos-deductions")
            .header("X-User-Id", "sales")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body));
    }

    private int quantityOf(String column, long productId) {
        return jdbcClient.sql("SELECT %s FROM stock WHERE location_code = 'STORE' AND product_id = ?".formatted(column))
            .param(productId)
            .query(Integer.class)
            .optional()
            .orElse(0);
    }

    // 마지막 전표의 줄을 "재고유형:변화량:이동 후 잔액" 형태로 본다
    private List<String> entriesOfLastMovement() {
        return jdbcClient.sql("""
                        SELECT CONCAT(state, ':', delta, ':', balance_after)
                        FROM stock_entry
                        WHERE movement_id = (SELECT MAX(id) FROM stock_movement)
                        ORDER BY id
                        """)
            .query(String.class)
            .list();
    }

    private String keyOfLastMovement() {
        return jdbcClient.sql("SELECT idempotency_key FROM stock_movement ORDER BY id DESC LIMIT 1")
            .query(String.class)
            .single();
    }

    private int posSaleMovementCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM stock_movement WHERE type = 'POS_SALE'")
            .query(Integer.class)
            .single();
    }
}
