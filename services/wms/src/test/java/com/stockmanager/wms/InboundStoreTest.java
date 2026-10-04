package com.stockmanager.wms;

import com.stockmanager.wms.application.InboundService;
import com.stockmanager.wms.application.ProductBinService;
import com.stockmanager.wms.domain.Inbound;
import com.stockmanager.wms.domain.InboundLine;
import com.stockmanager.wms.domain.InboundStatus;
import com.stockmanager.wms.domain.InspectionLine;
import com.stockmanager.wms.domain.PutawayLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InboundStoreTest {

    @Autowired
    InboundService inboundService;

    @Autowired
    ProductBinService productBinService;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void clearAll() {
        jdbcClient.sql("DELETE FROM inbound_item").update();
        jdbcClient.sql("DELETE FROM inbound").update();
        jdbcClient.sql("DELETE FROM product_bin").update();
        jdbcClient.sql("DELETE FROM outbox").update();

        jdbcClient.sql("DELETE FROM product").update();
        jdbcClient.sql("INSERT INTO product (id, sku, name) VALUES (1, 'SKU-1', '상품 1')").update();
    }

    @Test
    @DisplayName("적치 안내는 양품 수량과 칸을 알려 준다")
    void guidesWhereToPut() {
        productBinService.assign(1, "A-01-03");
        long inboundId = inspected(9, 1);

        assertThat(inboundService.putawayGuide(inboundId))
            .containsExactly(new PutawayLine(1, 9, "A-01-03"));
    }

    @Test
    @DisplayName("적치하면 STORED가 되고 InboundStored가 남는다")
    void storesAndRecordsEvent() {
        productBinService.assign(1, "A-01-03");
        long inboundId = inspected(9, 1);

        Inbound stored = inboundService.store(inboundId);

        assertThat(stored.status()).isEqualTo(InboundStatus.STORED);
        assertThat(eventTypeOf(inboundId)).isEqualTo("InboundStored");
        assertThat(payloadOf(inboundId)).contains("\"quantity\":9").doesNotContain("\"quantity\":1");
    }

    @Test
    @DisplayName("칸이 지정되지 않은 상품이 있으면 적치할 수 없다")
    void rejectsStoreWithoutBin() {
        long inboundId = inspected(9, 1);

        assertThatThrownBy(() -> inboundService.store(inboundId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("칸이 지정되지 않은 상품이 있습니다");
        assertThat(statusOf(inboundId)).isEqualTo(InboundStatus.INSPECTED.name());
        assertThat(outboxCount(inboundId)).isEqualTo(1);
    }

    @Test
    @DisplayName("검수하지 않은 입고는 적치할 수 없다")
    void rejectsStoreBeforeInspection() {
        productBinService.assign(1, "A-01-03");
        long inboundId = inboundService.arrive("DC", List.of(InboundLine.expected(1, 10))).id();

        assertThatThrownBy(() -> inboundService.store(inboundId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("적치할 수 없는 상태입니다");
    }

    @Test
    @DisplayName("두 번 적치할 수 없다")
    void rejectsSecondStore() {
        productBinService.assign(1, "A-01-03");
        long inboundId = inspected(9, 1);
        inboundService.store(inboundId);

        assertThatThrownBy(() -> inboundService.store(inboundId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("적치할 수 없는 상태입니다");

        assertThat(outboxCount(inboundId)).isEqualTo(2);
    }

    @Test
    @DisplayName("매장 입고는 검수로 끝나서 적치할 것이 없다")
    void rejectsStoreAtStore() {
        long inboundId = inboundService.arrive("STORE", List.of(InboundLine.expected(1, 5))).id();
        inboundService.inspect(inboundId, List.of(new InspectionLine(1, 5, 0)));

        assertThatThrownBy(() -> inboundService.store(inboundId))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("적치할 수 없는 상태입니다");
    }


    private long inspected(int good, int defective) {
        long inboundId = inboundService.arrive("DC", List.of(InboundLine.expected(1, good + defective))).id();
        inboundService.inspect(inboundId, List.of(new InspectionLine(1, good, defective)));
        return inboundId;
    }

    private String statusOf(long inboundId) {
        return jdbcClient.sql("SELECT status FROM inbound WHERE id = :id")
            .param("id", inboundId).query(String.class).single();
    }

    private int outboxCount(long inboundId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE message_key = :k")
            .param("k", String.valueOf(inboundId)).query(Integer.class).single();
    }

    private String eventTypeOf(long inboundId) {
        return jdbcClient.sql("""
                        SELECT event_type FROM outbox
                        WHERE message_key = :k AND event_type = 'InboundStored'
                        """)
            .param("k", String.valueOf(inboundId)).query(String.class).single();
    }

    private String payloadOf(long inboundId) {
        return jdbcClient.sql("""
                        SELECT payload FROM outbox
                        WHERE message_key = :k AND event_type = 'InboundStored'
                        """)
            .param("k", String.valueOf(inboundId)).query(String.class).single().replace(" ", "");
    }
}
