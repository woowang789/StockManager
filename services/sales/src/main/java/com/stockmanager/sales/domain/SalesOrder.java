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
@Table(name = "sales_order")
public class SalesOrder {

    private static final String ONLINE_LOCATION = "DC";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String orderNo;

    private String locationCode;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private Instant createdAt;

    @ElementCollection
    @CollectionTable(name = "sales_order_item", joinColumns = @JoinColumn(name = "order_id"))
    private List<OrderLine> lines = new ArrayList<>();

    protected SalesOrder(){}

    private SalesOrder(String orderNo, List<OrderLine> lines) {
        this.orderNo = orderNo;
        this.locationCode = ONLINE_LOCATION;
        this.status = OrderStatus.PENDING;
        this.createdAt = Instant.now();
        this.lines = new ArrayList<>(lines);
    }

    public static SalesOrder place(String orderNo, List<OrderLine> lines) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("주문할 상품이 없습니다");
        }
        Set<Long> productIds = new HashSet<>();
        for (OrderLine line : lines) {
            if (!productIds.add(line.productId())) {
                throw new IllegalArgumentException("같은 상품이 두 줄에 있습니다: 상품 " + line.productId());
            }
        }
        return new SalesOrder(orderNo, lines);
    }

    public Long getId() {
        return id;
    }

    public String getOrderNo() {
        return orderNo;
    }

    public String getLocationCode() {
        return locationCode;
    }

    public OrderStatus getStatus() {
        return status;
    }

    public List<OrderLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

}
