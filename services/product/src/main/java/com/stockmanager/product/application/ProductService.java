package com.stockmanager.product.application;

import com.stockmanager.product.domain.Product;
import com.stockmanager.product.infrastructure.ProductRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductRepository productRepository;

    ProductService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    public Product register(String sku, String name) {
        try {
            return productRepository.save(Product.register(sku, name));
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("이미 등록된 SKU입니다: "+ sku);
        }
    }

    public Optional<Product> find(long productId) {
        return productRepository.findById(productId);
    }
}
