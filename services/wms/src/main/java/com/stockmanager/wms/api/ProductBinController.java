package com.stockmanager.wms.api;

import com.stockmanager.wms.application.InboundService;
import com.stockmanager.wms.application.ProductBinService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProductBinController {

    private final ProductBinService productBinService;

    ProductBinController(ProductBinService productBinService) {
        this.productBinService = productBinService;
    }

    @PutMapping("/bins/{productId}")
    void assign(@PathVariable long productId, @Valid @RequestBody AssignRequest request) {
        productBinService.assign(productId, request.binCode());
    }

    record AssignRequest(@NotBlank String binCode) {
    }

}
