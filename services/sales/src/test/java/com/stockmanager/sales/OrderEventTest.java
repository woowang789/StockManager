package com.stockmanager.sales;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stockmanager.common.event.EventHeaders;
import com.stockmanager.common.event.OrderReserved;
import com.stockmanager.sales.application.OrderService;
import com.stockmanager.sales.domain.OrderLine;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.ObjectMapper;

@Import({TestcontainersConfiguration.class, InventoryStubConfiguration.class})
@SpringBootTest
@AutoConfigureMockMvc
class OrderEventTest {

    private static final String TOPIC = "sales.order";

    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    OrderService orderService;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    WireMockServer inventoryStub;

    @Autowired
    CircuitBreaker inventoryCircuitBreaker;

    @Autowired
    KafkaContainer kafkaContainer;

    @Autowired
    ObjectMapper objectMapper;

    private KafkaConsumer<String, String> consumer;

    @BeforeEach
    void clearAndSubscribe() {
        jdbcClient.sql("DELETE FROM sales_order_item").update();
        jdbcClient.sql("DELETE FROM sales_order").update();
        inventoryStub.resetAll();
        inventoryCircuitBreaker.reset();

        consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(List.of(TOPIC));
    }

    @AfterEach
    void closeConsumer() {
        consumer.close();
    }

    @Test
    @DisplayName("주문이 RESERVED가 되면 OrderReserved가 주문번호를 키로 발행된다")
    void publishesOrderReserved() throws Exception {
        inventoryStub.stubFor(post("/reservations").willReturn(okJson("{\"movementId\":1}")));

        orderService.place("EVT-1", List.of(new OrderLine(1, 3)));

        List<ConsumerRecord<String, String>> records = recordsOf("EVT-1", Duration.ofSeconds(10));
        assertThat(records).hasSize(1);

        OrderReserved event = objectMapper.readValue(records.getFirst().value(), OrderReserved.class);
        assertThat(event.orderNo()).isEqualTo("EVT-1");
        assertThat(event.locationCode()).isEqualTo("DC");
        assertThat(event.items()).containsExactly(new OrderReserved.Item(1, 3));
        assertThat(event.eventId()).isNotBlank();
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    @DisplayName("재고가 모자라 REJECTED가 되면 아무것도 발행하지 않는다")
    void publishesNothingWhenRejected() {
        inventoryStub.stubFor(post("/reservations").willReturn(aResponse().withStatus(409)));

        orderService.place("EVT-2", List.of(new OrderLine(1, 3)));

        assertThat(recordsOf("EVT-2", Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    @DisplayName("두 번 확정하려 해도 이벤트는 한 번만 나간다")
    void publishesOnceWhenConfirmedTwice() throws Exception {
        inventoryStub.stubFor(post("/reservations").willReturn(aResponse().withStatus(500)));
        orderService.place("EVT-3", List.of(new OrderLine(1, 3)));

        inventoryStub.resetAll();
        inventoryStub.stubFor(post("/reservations").willReturn(okJson("{\"movementId\":1}")));
        inventoryCircuitBreaker.reset();

        confirmPendingConcurrently();

        assertThat(recordsOf("EVT-3", Duration.ofSeconds(10))).hasSize(1);
    }

    @Test
    @DisplayName("주문한 요청의 trace와 사용자 ID가 이벤트 헤더에 실려 나간다")
    void publishesTraceOfRequest() throws Exception {
        inventoryStub.stubFor(post("/reservations").willReturn(okJson("{\"movementId\":1}")));

        mockMvc.perform(MockMvcRequestBuilders.post("/orders")
                .header("traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01")
                .header("X-User-Id", "customer-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                                {"orderNo":"EVT-4","items":[{"productId":1,"quantity":3}]}
                                """))
            .andExpect(status().isOk());

        // 발행기가 요청이 끝난 뒤 따로 보냈는데도, 주문한 요청의 trace와 사용자 ID가 그대로 실렸다
        ConsumerRecord<String, String> record = recordsOf("EVT-4", Duration.ofSeconds(10)).getFirst();
        assertThat(headerOf(record, "traceparent")).contains(TRACE_ID);
        assertThat(headerOf(record, "X-User-Id")).isEqualTo("customer-1");
    }

    private void confirmPendingConcurrently() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            for (int worker = 0; worker < 2; worker++) {
                results.add(pool.submit(() -> {
                    start.await();
                    orderService.confirmPending(Instant.now());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get();
            }
        }
    }

    private List<ConsumerRecord<String, String>> recordsOf(String orderNo, Duration timeout) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline && found.isEmpty()) {
            collectInto(found, orderNo, Duration.ofMillis(500));
        }
        collectInto(found, orderNo, Duration.ofSeconds(1));
        return found;
    }

    private void collectInto(List<ConsumerRecord<String, String>> found, String orderNo, Duration poll) {
        ConsumerRecords<String, String> polled = consumer.poll(poll);
        polled.records(TOPIC).forEach(record -> {
            if (orderNo.equals(record.key()) && isOrderReserved(record)) {
                found.add(record);
            }
        });
    }

    private String headerOf(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private boolean isOrderReserved(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(EventHeaders.EVENT_TYPE);
        return header != null
            && OrderReserved.class.getSimpleName().equals(new String(header.value(), StandardCharsets.UTF_8));
    }


}
