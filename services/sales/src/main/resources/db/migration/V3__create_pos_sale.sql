-- 매장 POS 판매. 영수증 번호는 POS가 정해서 보내고, 같은 번호로 다시 오면 새로 만들지 않는다
CREATE TABLE pos_sale
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    receipt_no    VARCHAR(50) NOT NULL,
    location_code VARCHAR(10) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_pos_sale_receipt_no UNIQUE (receipt_no),
    -- 매장 판매는 매장 재고로만 한다
    CONSTRAINT chk_pos_sale_location CHECK (location_code = 'STORE'),
    CONSTRAINT chk_pos_sale_status CHECK (status IN ('PENDING', 'COMPLETED', 'REJECTED'))
);

-- 판매 줄. 한 판매에 같은 상품은 한 줄만 있다
CREATE TABLE pos_sale_item
(
    pos_sale_id BIGINT NOT NULL,
    product_id  BIGINT NOT NULL,
    quantity    INT    NOT NULL,
    PRIMARY KEY (pos_sale_id, product_id),
    CONSTRAINT fk_pos_sale_item_pos_sale FOREIGN KEY (pos_sale_id) REFERENCES pos_sale (id),
    CONSTRAINT chk_pos_sale_item_quantity CHECK (quantity > 0)
);
