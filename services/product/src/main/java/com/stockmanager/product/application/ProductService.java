package com.stockmanager.product.application;

import com.stockmanager.product.domain.Product;
import com.stockmanager.product.infrastructure.ProductEventRecorder;
import com.stockmanager.product.infrastructure.ProductRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final ProductEventRecorder productEventRecorder;
    private final TransactionTemplate transactionTemplate;

    ProductService(ProductRepository productRepository, ProductEventRecorder productEventRecorder, TransactionTemplate transactionTemplate) {
        this.productRepository = productRepository;
        this.productEventRecorder = productEventRecorder;
        this.transactionTemplate = transactionTemplate;
    }

    public Product register(String sku, String name) {
        try {
            return transactionTemplate.execute(status -> {
                Product product = productRepository.save(Product.register(sku, name));
                productEventRecorder.productRegistered(product);
                return product;
            });
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalArgumentException("이미 등록된 SKU입니다: "+ sku);
        }
    }

    public Optional<Product> find(long productId) {
        return productRepository.findById(productId);
    }
}
