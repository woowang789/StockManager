package com.stockmanager.wms.api;

import com.stockmanager.wms.application.InboundService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ProductBinController {

    private final InboundService inboundService;

    ProductBinController(InboundService inboundService) {
        this.inboundService = inboundService;
    }

    @PutMapping("/bins/{productId}")
    void assign(@PathVariable long productId, @Valid @RequestBody AssignRequest request) {
        inboundService.assignBin(productId, request.binCode());
    }

    record AssignRequest(@NotBlank String binCode) {
    }

}
