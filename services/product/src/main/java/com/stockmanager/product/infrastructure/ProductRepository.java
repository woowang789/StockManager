package com.stockmanager.product.infrastructure;

import com.stockmanager.product.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product,Long> {
}
