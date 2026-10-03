package com.stockmanager.sales.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Entity
@Table(name = "pos_sale")
public class PosSale {

    private static final String STORE_LOCATION = "STORE";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String receiptNo;

    private String locationCode;

    @Enumerated(EnumType.STRING)
    private PosSaleStatus status;

    private Instant createdAt;

    @ElementCollection
    @CollectionTable(name = "pos_sale_item", joinColumns = @JoinColumn(name = "pos_sale_id"))
    private List<PosSaleLine> lines = new ArrayList<>();

    protected PosSale() {
    }

    private PosSale(String receiptNo, List<PosSaleLine> lines) {
        this.receiptNo = receiptNo;
        this.locationCode = STORE_LOCATION;
        this.status = PosSaleStatus.PENDING;
        this.createdAt = Instant.now();
        this.lines = new ArrayList<>(lines);
    }

    public static PosSale sell(String receiptNo, List<PosSaleLine> lines) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("판매할 상품이 없습니다");
        }
        Set<Long> productIds = new HashSet<>();
        for (PosSaleLine line : lines) {
            if (!productIds.add(line.productId())) {
                throw new IllegalArgumentException("같은 상품이 두 줄에 있습니다: 상품 " + line.productId());
            }
        }
        return new PosSale(receiptNo, lines);
    }

    public Long getId() {
        return id;
    }

    public String getReceiptNo() {
        return receiptNo;
    }

    public String getLocationCode() {
        return locationCode;
    }

    public PosSaleStatus getStatus() {
        return status;
    }

    public List<PosSaleLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

}
