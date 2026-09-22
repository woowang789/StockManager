package com.stockmanager.sales;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

@TestConfiguration(proxyBeanMethods = false)
class InventoryStubConfiguration {

    @Bean(destroyMethod = "stop")
    WireMockServer inventoryStub() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options()
            .dynamicPort()
            .http2PlainDisabled(true));
        server.start();
        return server;
    }

    @Bean
    DynamicPropertyRegistrar inventoryBaseUrl(WireMockServer inventoryStub) {
        return registry -> registry.add("spring.http.serviceclient.inventory.base-url", inventoryStub::baseUrl);
    }
}
