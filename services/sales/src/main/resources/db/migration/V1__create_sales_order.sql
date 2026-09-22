-- 온라인 주문. 주문번호는 온라인몰이 정해서 보내고, 같은 번호로 다시 오면 새로 만들지 않는다
CREATE TABLE sales_order
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    order_no      VARCHAR(50) NOT NULL,
    location_code VARCHAR(10) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sales_order_order_no UNIQUE (order_no),
    -- 온라인 주문은 물류센터 재고로만 판다
    CONSTRAINT chk_sales_order_location CHECK (location_code = 'DC'),
    CONSTRAINT chk_sales_order_status CHECK (status IN ('PENDING', 'RESERVED', 'REJECTED', 'SHIPPED', 'CANCEL_REQUESTED', 'CANCELED'))
);

-- 주문 줄. 한 주문에 같은 상품은 한 줄만 있다
CREATE TABLE sales_order_item
(
    order_id   BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity   INT    NOT NULL,
    PRIMARY KEY (order_id, product_id),
    CONSTRAINT fk_sales_order_item_order FOREIGN KEY (order_id) REFERENCES sales_order (id),
    CONSTRAINT chk_sales_order_item_quantity CHECK (quantity > 0)
);
