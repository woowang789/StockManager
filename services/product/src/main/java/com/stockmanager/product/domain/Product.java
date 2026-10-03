package com.stockmanager.product.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String sku;

    private String name;

    private Instant createdAt;

    protected Product() {
    }

    private Product(String sku, String name) {
        this.sku = sku;
        this.name = name;
        this.createdAt = Instant.now();
    }

    public static Product register(String sku, String name) {
        return new Product(sku, name);
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }
}
