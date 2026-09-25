-- 출하 작업. 주문 하나에 하나씩 만들고, 같은 주문으로 두 번 만들지 않는다
CREATE TABLE shipment
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    order_no      VARCHAR(50) NOT NULL,
    location_code VARCHAR(10) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_shipment_order_no UNIQUE (order_no),
    CONSTRAINT chk_shipment_status CHECK (status IN ('READY', 'PICKED', 'PACKED', 'SHIPPED', 'CANCELED'))
);

CREATE TABLE shipment_item
(
    shipment_id BIGINT NOT NULL,
    product_id  BIGINT NOT NULL,
    quantity    INT    NOT NULL,
    PRIMARY KEY (shipment_id, product_id),
    CONSTRAINT fk_shipment_item_shipment FOREIGN KEY (shipment_id) REFERENCES shipment (id),
    CONSTRAINT chk_shipment_item_quantity CHECK (quantity > 0)
);

-- 이미 처리한 이벤트. 같은 이벤트가 두 번 와도 한 번만 반영한다
CREATE TABLE processed_event
(
    event_id     VARCHAR(64) NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id)
);
