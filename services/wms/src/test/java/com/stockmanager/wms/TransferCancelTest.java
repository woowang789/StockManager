package com.stockmanager.wms;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stockmanager.common.event.TransferCanceled;
import com.stockmanager.common.web.ConcurrentUpdateException;
import com.stockmanager.wms.application.TransferService;
import com.stockmanager.wms.domain.TransferLine;
import com.stockmanager.wms.domain.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({TestcontainersConfiguration.class, InventoryStubConfiguration.class})
@SpringBootTest
class TransferCancelTest {

    @Autowired
    TransferService transferService;

    @Autowired
    WireMockServer inventoryStub;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    TransactionTemplate transactionTemplate;

    @BeforeEach
    void clearAll() {
        jdbcClient.sql("DELETE FROM inbound_item").update();
        jdbcClient.sql("DELETE FROM inbound").update();
        jdbcClient.sql("DELETE FROM transfer_item").update();
        jdbcClient.sql("DELETE FROM transfer").update();
        jdbcClient.sql("DELETE FROM outbox").update();
        inventoryStub.resetAll();
        inventoryStub.stubFor(post("/reservations").willReturn(okJson("{\"movementId\":1}")));
    }

    @Test
    @DisplayName("피킹 전에 취소하면 적치가 남지 않았다고 알린다")
    void cancelsBeforePick() {
        long transferId = requested();

        assertThat(transferService.cancel(transferId).status()).isEqualTo(TransferStatus.CANCELED);

        TransferCanceled event = canceledEventOf(transferId);
        assertThat(event.fromLocationCode()).isEqualTo("DC");
        assertThat(event.items()).containsExactly(new TransferCanceled.Item(1, 3));
        assertThat(event.shortages()).isEmpty();
        assertThat(event.putawayPending()).isFalse();
    }

    @Test
    @DisplayName("센터에서 피킹한 뒤에 취소하면 적치가 남았다고 알린다")
    void cancelsAfterPick() {
        long transferId = requested();
        transferService.pick(transferId);

        transferService.cancel(transferId);

        assertThat(canceledEventOf(transferId).putawayPending()).isTrue();
    }

    @Test
    @DisplayName("매장에서 피킹한 뒤에 취소하면 적치가 남지 않았다고 알린다")
    void cancelsAfterPickAtStore() {
        long transferId = transferService.request("STORE", "DC", List.of(new TransferLine(1, 2))).id();
        transferService.pick(transferId);

        transferService.cancel(transferId);

        assertThat(canceledEventOf(transferId).putawayPending()).isFalse();

    }

    @Test
    @DisplayName("두 번 취소해도 이벤트는 하나다")
    void cancelsOnce() {
        long transferId = requested();
        transferService.cancel(transferId);

        assertThat(transferService.cancel(transferId).status()).isEqualTo(TransferStatus.CANCELED);
        assertThat(canceledCountOf(transferId)).isEqualTo(1);
    }

    @Test
    @DisplayName("상품이동한 뒤에는 취소할 수 없다")
    void rejectsCancelAfterDispatch() {
        long transferId = requested();
        transferService.pick(transferId);
        transferService.dispatch(transferId);

        assertThatThrownBy(() -> transferService.cancel(transferId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("취소할 수 없는 상태입니다");
        assertThat(transferService.find(transferId).status()).isEqualTo(TransferStatus.IN_TRANSIT);
        assertThat(canceledCountOf(transferId)).isZero();
    }

    @Test
    @DisplayName("예약 결과를 모르는 이동은 취소할 수 없다")
    void rejectsCancelWhilePending() {
        inventoryStub.resetAll();
        inventoryStub.stubFor(post("/reservations").willReturn(aResponse().withStatus(500)));
        long transferId = requested();

        assertThatThrownBy(() -> transferService.cancel(transferId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("취소할 수 없는 상태입니다");
        assertThat(transferService.find(transferId).status()).isEqualTo(TransferStatus.PENDING);
        assertThat(canceledCountOf(transferId)).isZero();
    }

    @Test
    @DisplayName("취소한 이동은 출발할 수 없다")
    void rejectsDispatchAfterCancel() {
        long transferId = requested();
        transferService.pick(transferId);
        transferService.cancel(transferId);

        assertThatThrownBy(() -> transferService.dispatch(transferId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("이동할 수 없는 상태입니다");
        assertThat(eventCountOf(transferId, "TransferDispatched")).isZero();
        assertThat(inboundCountOf(transferId)).isZero();
    }

    @Test
    @DisplayName("결품으로 취소하면 찾지 못한 수량이 이벤트에 실린다")
    void cancelsForShortage() {
        long transferId = requested();
        transferService.pick(transferId);

        transferService.cancelForShortage(transferId, List.of(new TransferLine(1, 2)));

        assertThat(transferService.find(transferId).status()).isEqualTo(TransferStatus.CANCELED);
        TransferCanceled event = canceledEventOf(transferId);
        assertThat(event.items()).containsExactly(new TransferCanceled.Item(1, 3));
        assertThat(event.shortages()).containsExactly(new TransferCanceled.Item(1, 2));
        assertThat(event.putawayPending()).isTrue();
    }

    @Test
    @DisplayName("이동에 없는 상품은 결품으로 보고할 수 없다")
    void rejectsShortageOfUnknownProduct() {
        long transferId = requested();

        assertThatThrownBy(() -> transferService.cancelForShortage(transferId, List.of(new TransferLine(99, 1))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("이동에 없는 상품은 결품으로 보고할 수 없습니다");

        assertThat(transferService.find(transferId).status()).isEqualTo(TransferStatus.REQUESTED);
        assertThat(canceledCountOf(transferId)).isZero();
    }

    @Test
    @DisplayName("결품이 이동 수량보다 많을 수 없다")
    void rejectsShortageBeyondQuantity() {
        long transferId = requested();

        assertThatThrownBy(() -> transferService.cancelForShortage(transferId, List.of(new TransferLine(1, 4))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("결품이 이동 수량보다 많습니다");
        assertThat(transferService.find(transferId).status()).isEqualTo(TransferStatus.REQUESTED);
        assertThat(canceledCountOf(transferId)).isZero();
    }

    @Test
    @DisplayName("같은 상품을 두 줄로 보고하면 받지 않는다")
    void rejectsDuplicateShortage() {
        long transferId = requested();

        assertThatThrownBy(() -> transferService.cancelForShortage(transferId,
            List.of(new TransferLine(1, 1), new TransferLine(1, 2))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("같은 상품이 두 줄로 들어 있습니다: 상품 1");
        assertThat(transferService.find(transferId).status()).isEqualTo(TransferStatus.REQUESTED);
        assertThat(canceledCountOf(transferId)).isZero();
    }

    @Test
    @DisplayName("취소하는 사이에 출발하면 취소 이벤트를 내지 않는다")
    void doesNotPublishWhenDispatchedUnderneath() {
        long transferId = requested();
        transferService.pick(transferId);

        Future<?> canceling = cancelWhile(transferId, TransferStatus.IN_TRANSIT,
            () -> transferService.cancel(transferId));

        assertThatThrownBy(canceling::get).hasRootCauseInstanceOf(ConcurrentUpdateException.class);

        assertThat(transferService.find(transferId).status()).isEqualTo(TransferStatus.IN_TRANSIT);
        assertThat(canceledCountOf(transferId)).isZero();
    }


    private long requested() {
        return transferService.request("DC", "STORE", List.of(new TransferLine(1, 3))).id();
    }

    private Future<?> cancelWhile(long transferId, TransferStatus to, Runnable cancel) {
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            List<Future<?>> started = new ArrayList<>();
            transactionTemplate.executeWithoutResult(status -> {
                jdbcClient.sql("UPDATE transfer SET status = :to WHERE id = :transferId")
                    .param("to", to.name())
                    .param("transferId", transferId)
                    .update();
                started.add(pool.submit(cancel));
                sleep(Duration.ofMillis(500));
            });
            return started.getFirst();
        }
    }

    private TransferCanceled canceledEventOf(long transferId) {
        return objectMapper.readValue(canceledPayloadOf(transferId).orElseThrow(), TransferCanceled.class);
    }

    // 이동의 다른 이벤트와 같은 토픽·키로 나간다
    private Optional<String> canceledPayloadOf(long transferId) {
        return jdbcClient.sql("""
                        SELECT payload FROM outbox
                        WHERE topic = 'wms.transfer' AND message_key = :transferId
                          AND event_type = 'TransferCanceled'
                        """)
            .param("transferId", String.valueOf(transferId))
            .query(String.class)
            .optional();
    }

    private int canceledCountOf(long transferId) {
        return eventCountOf(transferId, "TransferCanceled");
    }

    private int eventCountOf(long transferId, String eventType) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM outbox WHERE message_key = :transferId AND event_type = :eventType
                        """)
            .param("transferId", String.valueOf(transferId))
            .param("eventType", eventType)
            .query(Integer.class)
            .single();
    }

    private int inboundCountOf(long transferId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM inbound WHERE transfer_id = :transferId")
            .param("transferId", transferId)
            .query(Integer.class)
            .single();
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

}
