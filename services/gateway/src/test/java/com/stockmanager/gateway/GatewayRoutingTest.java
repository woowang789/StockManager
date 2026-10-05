package com.stockmanager.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(ServiceStubConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class GatewayRoutingTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    WireMockServer inventoryStub;

    @Autowired
    WireMockServer salesStub;

    @Autowired
    WireMockServer wmsStub;

    @Autowired
    WireMockServer productStub;

    @BeforeEach
    void resetStubs() {
        List.of(inventoryStub, salesStub, wmsStub, productStub).forEach(WireMockServer::resetAll);
    }

    @Test
    @DisplayName("경로를 보고 그 API를 가진 서비스로 보낸다")
    void routesByPath() throws Exception {
        productStub.stubFor(WireMock.get("/products/1").willReturn(okJson("{\"from\":\"product\"}")));
        salesStub.stubFor(WireMock.get("/orders/ORD-1").willReturn(okJson("{\"from\":\"sales\"}")));
        wmsStub.stubFor(WireMock.get("/inbounds/1").willReturn(okJson("{\"from\":\"wms\"}")));
        inventoryStub.stubFor(WireMock.get("/stocks/DC/1").willReturn(okJson("{\"from\":\"inventory\"}")));

        mockMvc.perform(get("/products/1")).andExpect(jsonPath("$.from").value("product"));
        mockMvc.perform(get("/orders/ORD-1")).andExpect(jsonPath("$.from").value("sales"));
        mockMvc.perform(get("/inbounds/1")).andExpect(jsonPath("$.from").value("wms"));
        mockMvc.perform(get("/stocks/DC/1")).andExpect(jsonPath("$.from").value("inventory"));
    }

    @Test
    @DisplayName("사용자 ID 헤더와 본문을 그대로 넘기고, 서비스의 응답을 그대로 돌려준다")
    void passesRequestAndResponseThrough() throws Exception {
        String body = """
                {"locationCode":"DC","productId":1,"state":"AVAILABLE","delta":-1,"reason":"LOST"}
                """;
        inventoryStub.stubFor(WireMock.post("/adjustments").willReturn(okJson("{\"movementId\":7}")));

        mockMvc.perform(post("/adjustments")
                .header("X-User-Id", "admin-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.movementId").value(7));

        // inventory는 이 헤더를 원장의 처리자로 남긴다
        inventoryStub.verify(postRequestedFor(urlEqualTo("/adjustments"))
            .withHeader("X-User-Id", equalTo("admin-1"))
            .withRequestBody(equalToJson(body)));
    }

    @Test
    @DisplayName("요청마다 trace를 열어 서비스로 넘기고, 사용자 ID는 한 번만 넘긴다")
    void startsTraceForService() throws Exception {
        inventoryStub.stubFor(WireMock.get("/stocks/DC/1").willReturn(okJson("{}")));

        mockMvc.perform(get("/stocks/DC/1").header("X-User-Id", "admin-1"))
            .andExpect(status().isOk());

        // 클라이언트는 trace를 보내지 않았다. 게이트웨이가 연 trace가 W3C traceparent로 넘어간다
        inventoryStub.verify(getRequestedFor(urlEqualTo("/stocks/DC/1"))
            .withHeader("traceparent", matching("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}"))
            .withHeader("X-User-Id", havingExactly("admin-1")));
    }

    @Test
    @DisplayName("서비스끼리만 부르는 API는 열지 않는다")
    void doesNotExposeInternalApis() throws Exception {
        mockMvc.perform(post("/reservations").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());
        mockMvc.perform(post("/pos-deductions").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound());

        inventoryStub.verify(0, anyRequestedFor(anyUrl()));
    }


}
