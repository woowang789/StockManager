package com.stockmanager.wms;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stockmanager.common.event.OrderCancelRequested;
import com.stockmanager.common.event.ReputawayStored;
import com.stockmanager.wms.application.ProductBinService;
import com.stockmanager.wms.application.ReputawayService;
import com.stockmanager.wms.application.ShipmentService;
import com.stockmanager.wms.application.TransferService;
import com.stockmanager.wms.domain.PutawayLine;
import com.stockmanager.wms.domain.Reputaway;
import com.stockmanager.wms.domain.ReputawayOrigin;
import com.stockmanager.wms.domain.ReputawayStatus;
import com.stockmanager.wms.domain.ShipmentLine;
import com.stockmanager.wms.domain.TransferLine;
import com.stockmanager.wms.infrastructure.ShipmentRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

@Import({TestcontainersConfiguration.class, InventoryStubConfiguration.class})
@SpringBootTest
class ReputawayStoreTest {

    @Autowired
    ReputawayService reputawayService;

    @Autowired
    ProductBinService productBinService;

    @Autowired
    ShipmentService shipmentService;

    @Autowired
    ShipmentRepository shipmentRepository;

    @Autowired
    TransferService transferService;

    @Autowired
    WireMockServer inventoryStub;

    @Autowired
    JdbcClient jdbcClient;

    @Autowired
    ObjectMapper objectMapper;

    @BeforeEach
    void clearAll() {
        jdbcClient.sql("DELETE FROM reputaway_item").update();
        jdbcClient.sql("DELETE FROM reputaway").update();
        jdbcClient.sql("DELETE FROM shipment_item").update();
        jdbcClient.sql("DELETE FROM shipment").update();
        jdbcClient.sql("DELETE FROM processed_event").update();
        jdbcClient.sql("DELETE FROM inbound_item").update();
        jdbcClient.sql("DELETE FROM inbound").update();
        jdbcClient.sql("DELETE FROM transfer_item").update();
        jdbcClient.sql("DELETE FROM transfer").update();
        jdbcClient.sql("DELETE FROM product_bin").update();
        jdbcClient.sql("DELETE FROM outbox").update();
        inventoryStub.resetAll();
        inventoryStub.stubFor(post("/reservations").willReturn(okJson("{\"movementId\":1}")));
    }

    @Test
    @DisplayName("출하에서 왔든 이동에서 왔든 한 목록에 있다")
    void listsReadyFromBothOrigins() {
        long fromShipment = canceledAfterPick("ORD-1");
        long fromTransfer = transferCanceledAfterPick();

        assertThat(reputawayService.findReady())
                .extracting(Reputaway::id, Reputaway::origin)
                .containsExactly(
                        tuple(fromShipment, new ReputawayOrigin.Shipment("ORD-1")),
                        tuple(fromTransfer, new ReputawayOrigin.Transfer(transferIdOf(fromTransfer))));
    }

    @Test
    @DisplayName("적치 안내는 다시 넣을 수량과 칸을 알려 준다")
    void guidesWhereToPutBack() {
        productBinService.assign(1, "A-01-03");
        long reputawayId = canceledAfterPick("ORD-2");

        assertThat(reputawayService.putawayGuide(reputawayId))
                .containsExactly(new PutawayLine(1, 3, "A-01-03"));
    }

    @Test
    @DisplayName("적치하면 STORED가 되어 목록에서 빠지고, 취소된 출하와 같은 토픽·키로 알린다")
    void storesAndReportsOnShipmentFlow() {
        productBinService.assign(1, "A-01-03");
        long reputawayId = canceledAfterPick("ORD-3");

        Reputaway stored = reputawayService.store(reputawayId);

        assertThat(stored.status()).isEqualTo(ReputawayStatus.STORED);
        assertThat(reputawayService.findReady()).isEmpty();
        // 적치대기를 만든 것은 출하 취소다. 같은 토픽·키라야 취소보다 먼저 처리되지 않는다
        ReputawayStored event = storedEventOf("wms.shipment", "ORD-3");
        assertThat(event.reputawayId()).isEqualTo(reputawayId);
        assertThat(event.locationCode()).isEqualTo("DC");
        assertThat(event.items()).containsExactly(new ReputawayStored.Item(1, 3));
    }

    @Test
    @DisplayName("이동에서 온 재적치는 취소된 이동과 같은 토픽·키로 알린다")
    void reportsOnTransferFlow() {
        productBinService.assign(1, "A-01-03");
        long reputawayId = transferCanceledAfterPick();

        reputawayService.store(reputawayId);

        ReputawayStored event = storedEventOf("wms.transfer", String.valueOf(transferIdOf(reputawayId)));
        assertThat(event.reputawayId()).isEqualTo(reputawayId);
        assertThat(event.items()).containsExactly(new ReputawayStored.Item(1, 2));
    }

    @Test
    @DisplayName("칸이 지정되지 않은 상품이 있으면 적치할 수 없다")
    void rejectsStoreWithoutBin() {
        long reputawayId = canceledAfterPick("ORD-4");

        assertThatThrownBy(() -> reputawayService.store(reputawayId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("칸이 지정되지 않은 상품이 있습니다");
        assertThat(reputawayService.find(reputawayId).status()).isEqualTo(ReputawayStatus.READY);
        assertThat(storedCount()).isZero();
    }

    @Test
    @DisplayName("두 번 적치할 수 없다")
    void rejectsSecondStore() {
        productBinService.assign(1, "A-01-03");
        long reputawayId = canceledAfterPick("ORD-5");
        reputawayService.store(reputawayId);

        assertThatThrownBy(() -> reputawayService.store(reputawayId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("적치할 수 없는 상태입니다");
        // 두 번 알리면 같은 물건이 적치대기에서 두 번 빠진다
        assertThat(storedCount()).isEqualTo(1);
    }

    // 센터에서 3개를 피킹한 출하를 고객이 취소했다
    private long canceledAfterPick(String orderNo) {
        shipmentRepository.insert(orderNo, "DC", List.of(new ShipmentLine(1, 3)));
        shipmentService.pick(orderNo);
        shipmentService.cancel(new OrderCancelRequested("evt-" + orderNo, orderNo, Instant.now()));
        return jdbcClient.sql("SELECT id FROM reputaway WHERE order_no = :orderNo")
                .param("orderNo", orderNo)
                .query(Long.class)
                .single();
    }

    // 센터에서 2개를 피킹한 이동을 취소했다
    private long transferCanceledAfterPick() {
        long transferId = transferService.request("DC", "STORE", List.of(new TransferLine(1, 2))).id();
        transferService.pick(transferId);
        transferService.cancel(transferId);
        return jdbcClient.sql("SELECT id FROM reputaway WHERE transfer_id = :transferId")
                .param("transferId", transferId)
                .query(Long.class)
                .single();
    }

    private long transferIdOf(long reputawayId) {
        return reputawayService.find(reputawayId).origin().transferIdOrNull();
    }

    private ReputawayStored storedEventOf(String topic, String key) {
        String payload = jdbcClient.sql("""
                        SELECT payload FROM outbox
                        WHERE topic = :topic AND message_key = :key AND event_type = 'ReputawayStored'
                        """)
                .param("topic", topic)
                .param("key", key)
                .query(String.class)
                .single();
        return objectMapper.readValue(payload, ReputawayStored.class);
    }

    private int storedCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE event_type = 'ReputawayStored'")
                .query(Integer.class)
                .single();
    }
}
