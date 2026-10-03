package com.stockmanager.wms;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stockmanager.common.event.InboundInspected;
import com.stockmanager.common.event.InboundStored;
import com.stockmanager.common.event.TransferReceived;
import com.stockmanager.wms.application.InboundService;
import com.stockmanager.wms.application.ProductBinService;
import com.stockmanager.wms.application.TransferService;
import com.stockmanager.wms.domain.InboundLine;
import com.stockmanager.wms.domain.InboundStatus;
import com.stockmanager.wms.domain.InspectionLine;
import com.stockmanager.wms.domain.TransferLine;
import com.stockmanager.wms.domain.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({TestcontainersConfiguration.class, InventoryStubConfiguration.class})
@SpringBootTest
class TransferReceiveTest {

    @Autowired
    TransferService transferService;

    @Autowired
    InboundService inboundService;

    @Autowired
    ProductBinService productBinService;

    @Autowired
    WireMockServer inventoryStub;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void clearAll() {
        jdbcClient.sql("DELETE FROM inbound_item").update();
        jdbcClient.sql("DELETE FROM inbound").update();
        jdbcClient.sql("DELETE FROM reputaway_item").update();
        jdbcClient.sql("DELETE FROM reputaway").update();
        jdbcClient.sql("DELETE FROM transfer_item").update();
        jdbcClient.sql("DELETE FROM transfer").update();
        jdbcClient.sql("DELETE FROM product_bin").update();
        jdbcClient.sql("DELETE FROM outbox").update();
        inventoryStub.resetAll();
        inventoryStub.stubFor(post("/reservations").willReturn(okJson("{\"movementId\":1}")));
    }

    @Test
    @DisplayName("도착 거점에서 검수하면 이동이 끝난다")
    void completesTransfer() {
        long transferId = dispatched("DC", "STORE", 3);

        inboundService.inspect(inboundOf(transferId), List.of(new InspectionLine(1, 3, 0)));

        assertThat(transferService.find(transferId).status()).isEqualTo(TransferStatus.RECEIVED);

        assertThat(inboundService.find(inboundOf(transferId)).status()).isEqualTo(InboundStatus.STORED);
    }

    @Test
    @DisplayName("이동 입고를 검수하면 입고 검수가 아니라 이동의 도착을 상품이동과 같은 토픽/키로 알린다")
    void publishesTransferReceived() {
        long transferId = dispatched("DC", "STORE", 3);

        inboundService.inspect(inboundOf(transferId), List.of(new InspectionLine(1, 2, 0)));

        TransferReceived event = objectMapper.readValue(
            payloadOf("wms.transfer", String.valueOf(transferId), "TransferReceived"), TransferReceived.class);
        assertThat(event.transferId()).isEqualTo(transferId);
        assertThat(event.toLocationCode()).isEqualTo("STORE");
        assertThat(event.items()).containsExactly(new TransferReceived.Item(1, 2, 0));

        assertThat(event.putawayPending()).isFalse();

        assertThat(countOf("InboundInspected")).isZero();
    }

    @Test
    @DisplayName("공급사 입고의 검수 이벤트는 그대로 입고 토픽으로 간다")
    void supplierInboundKeepsItsTopic() {
        long inboundId = inboundService.arrive("DC", List.of(InboundLine.expected(1, 10))).id();

        inboundService.inspect(inboundId, List.of(new InspectionLine(1, 10, 0)));

        InboundInspected event = objectMapper.readValue(
            payloadOf("wms.inbound", String.valueOf(inboundId), "InboundInspected"), InboundInspected.class);
        assertThat(event.inboundId()).isEqualTo(inboundId);
        assertThat(countOf("TransferReceived")).isZero();
    }

    @Test
    @DisplayName("보낸 것보다 많이 받을 수 없다")
    void rejectsMoreThanSent() {
        long transferId = dispatched("DC", "STORE", 3);
        long inboundId = inboundOf(transferId);

        assertThatThrownBy(() -> inboundService.inspect(inboundId, List.of(new InspectionLine(1, 3, 1))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("이동 입고는 보낸 수량보다 많이 받을 수 없습니다");

        assertThat(inboundService.find(inboundId).status()).isEqualTo(InboundStatus.ARRIVED);
        assertThat(transferService.find(transferId).status()).isEqualTo(TransferStatus.IN_TRANSIT);
        assertThat(countOf("TransferReceived")).isZero();
    }

    @Test
    @DisplayName("센터로 온 이동 입고는 적치 이벤트도 상품이동과 같은 토픽으로 간다")
    void storedAtCenterGoesToTransferTopic() {
        long transferId = dispatched("STORE", "DC", 3);
        long inboundId = inboundOf(transferId);
        inboundService.inspect(inboundId, List.of(new InspectionLine(1, 3, 0)));
        productBinService.assign(1, "A-01-01");

        inboundService.store(inboundId);

        InboundStored event = objectMapper.readValue(
            payloadOf("wms.transfer", String.valueOf(transferId), "InboundStored"), InboundStored.class);
        assertThat(event.inboundId()).isEqualTo(inboundId);
        assertThat(event.items()).containsExactly(new InboundStored.Item(1, 3));
    }

    @Test
    @DisplayName("센터에 도착해 검수하면 적치가 남았다고 알린다")
    void reportsPutawayPendingAtCenter() {
        long transferId = dispatched("STORE", "DC", 3);

        inboundService.inspect(inboundOf(transferId), List.of(new InspectionLine(1, 3, 0)));

        TransferReceived event = objectMapper.readValue(
            payloadOf("wms.transfer", String.valueOf(transferId), "TransferReceived"), TransferReceived.class);
        assertThat(event.putawayPending()).isTrue();
    }

    @Test
    @DisplayName("이동이 이동 중이 아니면 검수를 되돌린다")
    void rollsBackWhenTransferIsNotInTransit() {
        long transferId = dispatched("DC", "STORE", 3);
        long inboundId = inboundOf(transferId);
        jdbcClient.sql("UPDATE transfer SET status = 'RECEIVED' WHERE id = :id")
            .param("id", transferId).update();

        assertThatThrownBy(() -> inboundService.inspect(inboundId, List.of(new InspectionLine(1, 3, 0))))
            .isInstanceOf(IllegalStateException.class);
        assertThat(inboundService.find(inboundId).status()).isEqualTo(InboundStatus.ARRIVED);
        assertThat(countOf("TransferReceived")).isZero();
    }

    private long dispatched(String from, String to, int quantity) {
        long transferId = transferService.request(from, to, List.of(new TransferLine(1, quantity))).id();
        transferService.pick(transferId);
        transferService.dispatch(transferId);
        return transferId;
    }

    private long inboundOf(long transferId) {
        return jdbcClient.sql("SELECT id FROM inbound WHERE transfer_id = :transferId")
            .param("transferId", transferId)
            .query(Long.class)
            .single();
    }

    private String payloadOf(String topic, String key, String eventType) {
        return jdbcClient.sql("""
                    SELECT payload FROM outbox
                    WHERE topic = :topic AND message_key = :key AND event_type = :eventType
                """)
            .param("topic", topic)
            .param("key", key)
            .param("eventType", eventType)
            .query(String.class)
            .single();
    }

    private int countOf(String eventType){
        return jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE event_type = :eventType")
            .param("eventType", eventType)
            .query(Integer.class)
            .single();
    }

}
