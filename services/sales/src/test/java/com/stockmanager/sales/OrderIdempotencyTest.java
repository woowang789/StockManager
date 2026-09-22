package com.stockmanager.sales;

import static org.assertj.core.api.Assertions.assertThat;

import com.stockmanager.sales.application.OrderService;
import com.stockmanager.sales.domain.OrderLine;
import com.stockmanager.sales.domain.SalesOrder;
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
class OrderIdempotencyTest {

    private static final int RETRIES = 10;

    @Autowired
    OrderService orderService;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void clearOrders() {
        jdbcClient.sql("DELETE FROM sales_order_item").update();
        jdbcClient.sql("DELETE FROM sales_order").update();
    }

    @Test
    @DisplayName("같은 주문번호로 다시 보내면 새로 만들지 않고 처음 주문을 돌려준다")
    void returnsFirstOrderOnRetry() {
        SalesOrder first = orderService.place("ORD-1", List.of(new OrderLine(1, 3)));
        SalesOrder retried = orderService.place("ORD-1", List.of(new OrderLine(2, 5)));

        assertThat(retried.getId()).isEqualTo(first.getId());
        assertThat(retried.getLines()).containsExactly(new OrderLine(1, 3));
        assertThat(orderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 주문번호가 동시에 여러 번 와도 주문은 하나만 생긴다")
    void placesOnceUnderConcurrentRetries() throws Exception{
        CountDownLatch start = new CountDownLatch(1);
        List<Future<SalesOrder>> results = new ArrayList<>();

        try (ExecutorService pool = Executors.newFixedThreadPool(RETRIES)) {
            for (int i = 0; i < RETRIES; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return orderService.place("ORD-1", List.of(new OrderLine(1, 3)));
                }));
            }
            start.countDown();
        }

        List<Long> orderIds = new ArrayList<>();
        for (Future<SalesOrder> result : results) {
            orderIds.add(result.get().getId());
        }
        assertThat(orderIds).hasSize(RETRIES).containsOnly(orderIds.getFirst());
        assertThat(orderCount()).isEqualTo(1);
    }

    private int orderCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM sales_order")
            .query(Integer.class)
            .single();
    }
}
