package com.stockmanager.wms;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.stockmanager.wms.application.TransferService;
import com.stockmanager.wms.domain.Transfer;
import com.stockmanager.wms.domain.TransferLine;
import com.stockmanager.wms.domain.TransferStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import({TestcontainersConfiguration.class, InventoryStubConfiguration.class})
@SpringBootTest
class TransferRequestTest {

    @Autowired
    TransferService transferService;

    @Autowired
    WireMockServer inventoryStub;

    @Autowired
    JdbcClient jdbcClient;

    @BeforeEach
    void clearAll() {
        // 이동에서 생긴 입고 문서가 이동을 가리키므로(외래 키) 입고부터 지운다
        jdbcClient.sql("DELETE FROM inbound_item").update();
        jdbcClient.sql("DELETE FROM inbound").update();
        jdbcClient.sql("DELETE FROM reputaway_item").update();
        jdbcClient.sql("DELETE FROM reputaway").update();
        jdbcClient.sql("DELETE FROM transfer_item").update();
        jdbcClient.sql("DELETE FROM transfer").update();
        inventoryStub.resetAll();
    }

    @Test
    @DisplayName("이동을 요청하면 출발 거점의 재고를 예약하고 REQUESTED가 된다")
    void requestsReservationAtSource() {
        inventoryStub.stubFor(post("/reservations").willReturn(okJson("{\"movementId\":1}")));

        Transfer transfer = transferService.request("DC", "STORE", List.of(new TransferLine(1, 3)));

        assertThat(transfer.status()).isEqualTo(TransferStatus.REQUESTED);

        inventoryStub.verify(1, postRequestedFor(urlEqualTo("/reservations"))
            .withRequestBody(equalToJson("""
                {"refType":"TRANSFER","refId":"%d","locationCode":"DC",
                 "items":[{"productId":1,"quantity":3}]}
                """.formatted(transfer.id())))
        );
    }

    @Test
    @DisplayName("출발 거점 재고가 모자라면 REJECTED가 된다")
    void rejectsWhenSourceIsShort() {
        inventoryStub.stubFor(post("/reservations").willReturn(aResponse().withStatus(409)));

        Transfer transfer = transferService.request("DC", "STORE", List.of(new TransferLine(1, 3)));

        assertThat(transfer.status()).isEqualTo(TransferStatus.REJECTED);
    }

    @Test
    @DisplayName("예약 결과를 모르면 PENDING으로 남는다")
    void staysPendingWhenResultIsUnknown() {
        inventoryStub.stubFor(post("/reservations").willReturn(aResponse().withStatus(500)));

        Transfer transfer = transferService.request("DC", "STORE", List.of(new TransferLine(1, 3)));

        assertThat(transfer.status()).isEqualTo(TransferStatus.PENDING);
    }

    @Test
    @DisplayName("재시도 잡이 PENDING 요청을 확정한다")
    void retryJobConfirmsPending() {
        inventoryStub.stubFor(post("/reservations").willReturn(aResponse().withStatus(500)));
        long transferId = transferService.request("DC", "STORE", List.of(new TransferLine(1, 3))).id();

        inventoryStub.resetAll();
        inventoryStub.stubFor(post("/reservations").willReturn(okJson("{\"movementId\":1}")));

        transferService.confirmPending(Instant.now().plus(Duration.ofSeconds(1)));

        assertThat(transferService.find(transferId).status()).isEqualTo(TransferStatus.REQUESTED);
    }

    @Test
    @DisplayName("같은 거점끼리는 이동할 수 없다")
    void rejectsSameLocation() {
        assertThatThrownBy(() -> transferService.request("DC", "DC", List.of(new TransferLine(1, 3))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("같은 거점으로는 이동할 수 없습니다");
        assertThat(transferCount()).isZero();
        inventoryStub.verify(0, postRequestedFor(urlEqualTo("/reservations")));
    }

    @Test
    @DisplayName("같은 상품을 두 줄로 요청하면 받지 않는다")
    void rejectsDuplicateProduct() {
        assertThatThrownBy(() -> transferService.request("DC", "STORE",
            List.of(new TransferLine(1, 2), new TransferLine(1, 3))))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("같은 상품이 두 줄로 들어 있습니다: 상품 1");

        assertThat(transferCount()).isZero();
        inventoryStub.verify(0, postRequestedFor(urlEqualTo("/reservations")));
    }

    @Test
    @DisplayName("품목을 저장하다 실패하면 이동을 남기지 않는다")
    void leavesNothingWhenItemInsertFails() {
        assertThatThrownBy(() -> transferService.request("DC", "STORE",
            List.of(new TransferLine(1, 3), new TransferLine(2, 0))))
            .isInstanceOf(DataAccessException.class);

        assertThat(transferCount()).isZero();
        inventoryStub.verify(0, postRequestedFor(urlEqualTo("/reservations")));
    }

    private int transferCount() {
        return jdbcClient.sql("SELECT COUNT(*) FROM transfer")
            .query(Integer.class)
            .single();
    }

    @Test
    @DisplayName("없는 이동 요청은 조회할 수 없다")
    void rejectsUnknownTransfer() {
        assertThatThrownBy(() -> transferService.find(404))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("이동 요청이 없습니다");
    }


}
