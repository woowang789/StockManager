-- 재적치 작업. 피킹한 뒤 취소된 물건을 칸에 다시 넣는다. 출하에서 왔든 이동에서 왔든 같은 작업이다
CREATE TABLE reputaway
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    location_code VARCHAR(10) NOT NULL,
    -- 어느 취소에서 왔는지. 출하면 주문번호, 이동이면 이동 번호 하나만 채운다
    order_no      VARCHAR(50) NULL,
    transfer_id   BIGINT      NULL,
    status        VARCHAR(20) NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    -- 한 문서는 한 번만 취소되므로 재적치도 문서마다 하나다
    CONSTRAINT uk_reputaway_order UNIQUE (order_no),
    CONSTRAINT uk_reputaway_transfer UNIQUE (transfer_id),
    CONSTRAINT fk_reputaway_shipment FOREIGN KEY (order_no) REFERENCES shipment (order_no),
    CONSTRAINT fk_reputaway_transfer FOREIGN KEY (transfer_id) REFERENCES transfer (id),
    CONSTRAINT chk_reputaway_origin CHECK ((order_no IS NULL) <> (transfer_id IS NULL)),
    CONSTRAINT chk_reputaway_location CHECK (location_code IN ('STORE', 'DC')),
    CONSTRAINT chk_reputaway_status CHECK (status IN ('READY', 'STORED'))
);

-- 다시 넣을 수량. 찾지 못한 수량(결품)은 꺼낸 것이 아니라 들어 있지 않다
CREATE TABLE reputaway_item
(
    reputaway_id BIGINT NOT NULL,
    product_id   BIGINT NOT NULL,
    quantity     INT    NOT NULL,
    PRIMARY KEY (reputaway_id, product_id),
    CONSTRAINT fk_reputaway_item_reputaway FOREIGN KEY (reputaway_id) REFERENCES reputaway (id),
    CONSTRAINT chk_reputaway_item_quantity CHECK (quantity > 0)
);
