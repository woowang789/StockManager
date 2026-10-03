package com.stockmanager.sales.api;

import com.stockmanager.sales.application.PosSaleService;
import com.stockmanager.sales.domain.PosSale;
import com.stockmanager.sales.domain.PosSaleLine;
import com.stockmanager.sales.domain.PosSaleStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/pos-sales")
class PosSaleController {

    private final PosSaleService posSaleService;

    PosSaleController(PosSaleService posSaleService) {
        this.posSaleService = posSaleService;
    }

    @PostMapping
    PosSaleResponse sell(@Valid @RequestBody PosSaleRequest request) {
        List<PosSaleLine> lines = request.items().stream()
            .map(item -> new PosSaleLine(item.productId(), item.quantity()))
            .toList();
        return PosSaleResponse.from(posSaleService.sell(request.receiptNo(), lines));
    }

    @GetMapping("/{receiptNo}")
    ResponseEntity<PosSaleResponse> find(@PathVariable String receiptNo) {
        return posSaleService.find(receiptNo)
            .map(sale -> ResponseEntity.ok(PosSaleResponse.from(sale)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }


    record PosSaleRequest(@NotBlank @Size(max = 50) String receiptNo, @NotEmpty @Valid List<Item> items) {
        record Item(@NotNull Long productId, @NotNull @Positive Integer quantity) {
        }
    }

    record PosSaleResponse(String receiptNo, String locationCode, PosSaleStatus status, List<Item> items) {
        record Item(long productId, int quantity) {
        }

        static PosSaleResponse from(PosSale sale) {
            List<Item> items = sale.getLines().stream()
                .map(line -> new Item(line.productId(), line.quantity()))
                .toList();
            return new PosSaleResponse(sale.getReceiptNo(), sale.getLocationCode(), sale.getStatus(), items);
        }
    }

}

