package com.stockmanager.wms;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stockmanager.common.event.TransferDispatched;
import com.stockmanager.wms.application.InboundService;
import com.stockmanager.wms.application.TransferService;
import com.stockmanager.wms.domain.Inbound;
import com.stockmanager.wms.domain.InboundLine;
import com.stockmanager.wms.domain.InboundOrigin;
import com.stockmanager.wms.domain.InboundStatus;
import com.stockmanager.wms.domain.Transfer;
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
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({TestcontainersConfiguration.class, InventoryStubConfiguration.class})
@SpringBootTest
class TransferDispatchTest {

    @Autowired
    TransferService transferService;

    @Autowired
    InboundService inboundService;

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
        jdbcClient.sql("DELETE FROM outbox").update();

        jdbcClient.sql("DELETE FROM product").update();
        jdbcClient.sql("INSERT INTO product (id, sku, name) VALUES (1, 'SKU-1', '상품 1')").update();
        inventoryStub.resetAll();
        inventoryStub.stubFor(post("/reservations").willReturn(okJson("{\"movementId\":1}")));
    }

    @Test
    @DisplayName("피킹하면 꺼낸 상태가 된다")
    void picks() {
        long transferId = requested();

        assertThat(transferService.pick(transferId).status()).isEqualTo(TransferStatus.PICKED);
    }

    @Test
    @DisplayName("요청 상태가 아니면 피킹할 수 없다")
    void rejectsPickBeforeRequested() {
        long transferId = requested();
        transferService.pick(transferId);
        transferService.dispatch(transferId);

        assertThatThrownBy(() -> transferService.pick(transferId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("PICKED로 보낼 수 없는 상태입니다");
    }

    @Test
    @DisplayName("피킹하지 않았으면 상품이동할 수 없다")
    void rejectsDispatchBeforePicking() {
        long transferId = requested();

        assertThatThrownBy(() -> transferService.dispatch(transferId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("이동할 수 없는 상태입니다");
        assertThat(inboundOf(transferId)).isEmpty();
    }

    @Test
    @DisplayName("상품이동하면 도착 거점에 입고 문서가 예정 수량으로 만들어진다")
    void createsArrivalDocument() {
        long transferId = requested();
        transferService.pick(transferId);

        Transfer shipped = transferService.dispatch(transferId);

        assertThat(shipped.status()).isEqualTo(TransferStatus.IN_TRANSIT);
        Inbound inbound = inboundService.find(inboundOf(transferId).orElseThrow());

        assertThat(inbound.locationCode()).isEqualTo("STORE");
        assertThat(inbound.status()).isEqualTo(InboundStatus.ARRIVED);
        assertThat(inbound.origin()).isEqualTo(new InboundOrigin.Transfer(transferId));

        assertThat(inbound.lines()).containsExactly(new InboundLine(1, 3, null, null));
    }

    @Test
    @DisplayName("상품이동 이벤트에 두 거점이 함께 실린다")
    void publishesBothLocations() {
        long transferId = requested();
        transferService.pick(transferId);

        transferService.dispatch(transferId);

        TransferDispatched event = objectMapper.readValue(dispatchedPayloadOf(transferId).orElseThrow(),
            TransferDispatched.class);
        assertThat(event.transferId()).isEqualTo(transferId);
        assertThat(event.fromLocationCode()).isEqualTo("DC");
        assertThat(event.toLocationCode()).isEqualTo("STORE");
        assertThat(event.items()).containsExactly(new TransferDispatched.Item(1, 3));
    }

    @Test
    @DisplayName("이미 떠난 이동을 다시 보내도 입고 문서와 이벤트는 하나다")
    void dispatchesOnce() {
        long transferId = requested();
        transferService.pick(transferId);

        transferService.dispatch(transferId);
        Transfer again = transferService.dispatch(transferId);

        assertThat(again.status()).isEqualTo(TransferStatus.IN_TRANSIT);
        assertThat(inboundCountOf(transferId)).isEqualTo(1);
        assertThat(dispatchedCountOf(transferId)).isEqualTo(1);
    }

    @Test
    @DisplayName("공급사 입고 문서는 이동에서 오지 않는다")
    void supplierInboundHasNoTransfer() {
        Inbound inbound = inboundService.arrive("DC", List.of(InboundLine.expected(1, 10)));

        assertThat(inbound.origin()).isEqualTo(new InboundOrigin.Supplier());
    }

    private long requested() {
        return transferService.request("DC", "STORE", List.of(new TransferLine(1, 3))).id();
    }

    private Optional<Long> inboundOf(long transferId) {
        return jdbcClient.sql("SELECT id FROM inbound WHERE transfer_id = :transferId")
            .param("transferId", transferId)
            .query(Long.class)
            .optional();
    }

    private int inboundCountOf(long transferId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM inbound WHERE transfer_id = :transferId")
            .param("transferId", transferId)
            .query(Integer.class)
            .single();
    }

    private Optional<String> dispatchedPayloadOf(long transferId) {
        return jdbcClient.sql("""
                        SELECT payload FROM outbox
                        WHERE message_key = :transferId AND event_type = 'TransferDispatched'
                        """)
            .param("transferId", String.valueOf(transferId))
            .query(String.class)
            .optional();
    }

    private int dispatchedCountOf(long transferId) {
        return jdbcClient.sql("""
                        SELECT COUNT(*) FROM outbox
                        WHERE message_key = :transferId AND event_type = 'TransferDispatched'
                        """)
            .param("transferId", String.valueOf(transferId))
            .query(Integer.class)
            .single();
    }




}
