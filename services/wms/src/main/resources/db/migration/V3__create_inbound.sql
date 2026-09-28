-- 입고 문서. 물건이 도착하면 예정 수량으로 먼저 만들고, 검수에서 실제 수량을 확정한다
CREATE TABLE inbound
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    location_code VARCHAR(10) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    created_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_inbound_location CHECK (location_code IN ('STORE', 'DC')),
    CONSTRAINT chk_inbound_status CHECK (status IN ('ARRIVED', 'INSPECTED', 'STORED'))
);

-- 예정 수량과 검수 결과. 검수 전에는 양품·불량이 비어 있다
CREATE TABLE inbound_item
(
    inbound_id         BIGINT NOT NULL,
    product_id         BIGINT NOT NULL,
    expected_quantity  INT    NOT NULL,
    good_quantity      INT    NULL,
    defective_quantity INT    NULL,
    PRIMARY KEY (inbound_id, product_id),
    CONSTRAINT fk_inbound_item_inbound FOREIGN KEY (inbound_id) REFERENCES inbound (id),
    CONSTRAINT chk_inbound_item_expected CHECK (expected_quantity > 0),
    CONSTRAINT chk_inbound_item_good CHECK (good_quantity IS NULL OR good_quantity >= 0),
    CONSTRAINT chk_inbound_item_defective CHECK (defective_quantity IS NULL OR defective_quantity >= 0)
);
