package com.stockmanager.sales.infrastructure;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "inventory", types = InventoryClient.class)
class InventoryClientConfig {
}
