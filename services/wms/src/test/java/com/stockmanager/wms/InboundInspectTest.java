package com.stockmanager.wms;

import com.stockmanager.wms.application.InboundService;
import com.stockmanager.wms.domain.Inbound;
import com.stockmanager.wms.domain.InboundLine;
import com.stockmanager.wms.domain.InboundStatus;
import com.stockmanager.wms.domain.InspectionLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class InboundInspectTest {

    @Autowired
    InboundService inboundService;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void clearAll() {
        jdbcClient.sql("DELETE FROM inbound_item").update();
        jdbcClient.sql("DELETE FROM inbound").update();
        jdbcClient.sql("DELETE FROM outbox").update();
    }

    @Test
    @DisplayName("도착 등록은 예정 수량만 적고 이벤트를 내지 않는다")
    void arriveRecordsExpectedOnly() {
        Inbound inbound = inboundService.arrive("DC", List.of(InboundLine.expected(1, 10)));

        assertThat(inbound.status()).isEqualTo(InboundStatus.ARRIVED);
        assertThat(inbound.lines()).containsExactly(new InboundLine(1, 10, null, null));
        assertThat(outboxCount(inbound.id())).isZero();
    }

    @Test
    @DisplayName("같은 상품을 두 줄로 등록하면 받지 않는다")
    void rejectsDuplicateProductOnArrival() {
        assertThatThrownBy(() -> inboundService.arrive("DC",
            List.of(InboundLine.expected(1, 10), InboundLine.expected(1, 5))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("같은 상품이 두 줄로 들어 있습니다: 상품 1");
        assertThat(inboundCount()).isZero();
    }

    @Test
    @DisplayName("품목을 저장하다 실패하면 입고 문서를 남기지 않는다")
    void leavesNothingWhenItemInsertFails() {
        assertThatThrownBy(() -> inboundService.arrive("DC",
            List.of(InboundLine.expected(1, 10), InboundLine.expected(2, 0))))
            .isInstanceOf(DataAccessException.class);

        assertThat(inboundCount()).isZero();
    }

    @Test
    @DisplayName("센터에서 검수하면 INSPECTED가 되고 InboundInspected가 남는다")
    void inspectsAtCenter() {
        long inboundId = inboundService.arrive("DC", List.of(InboundLine.expected(1, 10))).id();

        Inbound inspected = inboundService.inspect(inboundId, List.of(new InspectionLine(1, 9, 1)));

        assertThat(inspected.status()).isEqualTo(InboundStatus.INSPECTED);
        assertThat(inspected.lines()).containsExactly(new InboundLine(1, 10, 9, 1));
        assertThat(outboxCount(inboundId)).isEqualTo(1);
        assertThat(eventTypeOf(inboundId)).isEqualTo("InboundInspected");
    }

    @Test
    @DisplayName("매장에서 검수하면 적치 단계 없이 STORED로 끝난다")
    void inspectsAtStore() {
        long inboundId = inboundService.arrive("STORE", List.of(InboundLine.expected(1, 5))).id();

        assertThat(inboundService.inspect(inboundId, List.of(new InspectionLine(1, 5, 0))).status())
            .isEqualTo(InboundStatus.STORED);
        assertThat(outboxCount(inboundId)).isEqualTo(1);
    }

    @Test
    @DisplayName("예정과 다르게 세도 센 수량으로 확정한다")
    void confirmsCountQuantity() {
        long inboundId = inboundService.arrive("DC", List.of(InboundLine.expected(1, 10))).id();

        Inbound inspected = inboundService.inspect(inboundId, List.of(new InspectionLine(1, 7, 0)));

        assertThat(inspected.lines()).containsExactly(new InboundLine(1, 10, 7, 0));
        assertThat(inspected.status()).isEqualTo(InboundStatus.INSPECTED);
    }

    @Test
    @DisplayName("두 번 검수할 수 없다")
    void rejectsSecondInspection() {
        long inboundId = inboundService.arrive("DC", List.of(InboundLine.expected(1, 10))).id();
        inboundService.inspect(inboundId, List.of(new InspectionLine(1, 10, 0)));

        assertThatThrownBy(() -> inboundService.inspect(inboundId, List.of(new InspectionLine(1, 3, 0))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("검수할 수 없는 상태입니다");
        assertThat(outboxCount(inboundId)).isEqualTo(1);
    }

    @Test
    @DisplayName("예정 품목을 빠뜨린 검수는 받지 않는다")
    void rejectsPartialInspection() {
        long inboundId = inboundService
            .arrive("DC", List.of(InboundLine.expected(1, 10), InboundLine.expected(2, 5))).id();

        assertThatThrownBy(() -> inboundService.inspect(inboundId, List.of(new InspectionLine(1, 10, 0))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("예정 품목을 모두 검수해야 합니다");
        assertThat(statusOf(inboundId)).isEqualTo(InboundStatus.ARRIVED.name());
        assertThat(outboxCount(inboundId)).isZero();
    }

    @Test
    @DisplayName("같은 상품을 두 줄로 검수하면 받지 않는다")
    void rejectsDuplicateProductOnInspection() {
        long inboundId = inboundService.arrive("DC", List.of(InboundLine.expected(1, 10))).id();

        assertThatThrownBy(() -> inboundService.inspect(inboundId,
            List.of(new InspectionLine(1, 3, 0), new InspectionLine(1, 4, 0))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("같은 상품이 두 줄로 들어 있습니다: 상품 1");
        assertThat(statusOf(inboundId)).isEqualTo(InboundStatus.ARRIVED.name());
        assertThat(outboxCount(inboundId)).isZero();

    }

    @Test
    @DisplayName("없는 입고 문서는 검수할 수 없다")
    void rejectsUnknownInbound() {
        assertThatThrownBy(() -> inboundService.inspect(404, List.of(new InspectionLine(1, 1, 0))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("입고 문서가 없습니다");
    }


    private int outboxCount(long inboundId) {
        return jdbcClient.sql("SELECT COUNT(*) FROM outbox WHERE message_key = :messageKey")
            .param("messageKey", String.valueOf(inboundId))
            .query(Integer.class)
            .single();
    }

    private String eventTypeOf(long inboundId) {
        return jdbcClient.sql("SELECT event_type FROM outbox WHERE message_key = :messageKey")
            .param("messageKey", String.valueOf(inboundId))
            .query(String.class)
            .single();
    }

    private int inboundCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM inbound").query(Integer.class).single();
    }

    private String statusOf(long inboundId) {
        return jdbcClient.sql("SELECT status FROM inbound WHERE id = :inboundId")
            .param("inboundId", inboundId)
            .query(String.class)
            .single();
    }



}
