package com.stockmanager.product.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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

    @Version
    private long version;

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

    public boolean change(String sku, String name) {
        if (this.sku.equals(sku) && this.name.equals(name)) {
            return false;
        }
        this.sku = sku;
        this.name = name;
        return true;
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

    public long getVersion() {
        return version;
    }
}
