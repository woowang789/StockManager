package com.stockmanager.product.api;

import com.stockmanager.product.application.ProductService;
import com.stockmanager.product.domain.Product;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/products")
class ProductController {

    private final ProductService productService;

    ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    ProductResponse register(@Valid @RequestBody ProductRequest request) {
        return ProductResponse.from(productService.register(request.sku(), request.name()));
    }

    @GetMapping("/{productId}")
    ResponseEntity<ProductResponse> find(@PathVariable long productId) {
        return productService.find(productId)
            .map(product -> ResponseEntity.ok(ProductResponse.from(product)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    record ProductRequest(@NotBlank @Size(max = 50) String sku, @NotBlank @Size(max = 100) String name) {
    }

    record ProductResponse(long productId, String sku, String name) {

        static ProductResponse from(Product product) {
            return new ProductResponse(product.getId(), product.getSku(), product.getName());
        }
    }

}
