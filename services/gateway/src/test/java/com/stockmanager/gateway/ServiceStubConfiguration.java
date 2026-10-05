package com.stockmanager.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;

@TestConfiguration(proxyBeanMethods = false)
class ServiceStubConfiguration {

    @Bean(destroyMethod = "stop")
    WireMockServer inventoryStub() {
        return start();
    }

    @Bean(destroyMethod = "stop")
    WireMockServer salesStub() {
        return start();
    }

    @Bean(destroyMethod = "stop")
    WireMockServer wmsStub() {
        return start();
    }

    @Bean(destroyMethod = "stop")
    WireMockServer productStub() {
        return start();
    }

    @Bean
    DynamicPropertyRegistrar serviceUrls(WireMockServer inventoryStub, WireMockServer salesStub,
                                         WireMockServer wmsStub, WireMockServer productStub) {
        return registry -> {
            registry.add("services.inventory.url", inventoryStub::baseUrl);
            registry.add("services.sales.url", salesStub::baseUrl);
            registry.add("services.wms.url", wmsStub::baseUrl);
            registry.add("services.product.url", productStub::baseUrl);
        };
    }


    private static WireMockServer start() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options()
            .dynamicPort()
            .http2PlainDisabled(true));
        server.start();
        return server;
    }
}
