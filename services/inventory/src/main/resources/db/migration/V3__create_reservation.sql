-- 예약: 문서 1건이 잡아 둔 재고. 가용에서 빠져나와 예약으로 옮겨 간 수량이다
CREATE TABLE reservation
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    ref_type      VARCHAR(20) NOT NULL,
    ref_id        VARCHAR(50) NOT NULL,
    location_code VARCHAR(10) NOT NULL,
    product_id    BIGINT      NOT NULL,
    quantity      INT         NOT NULL,
    status        VARCHAR(20) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_reservation_location CHECK (location_code IN ('STORE', 'DC')),
    CONSTRAINT chk_reservation_quantity CHECK (quantity > 0),
    CONSTRAINT chk_reservation_status CHECK (status IN ('ACTIVE', 'CONSUMED', 'RELEASED'))
);

-- 문서 하나가 잡은 예약을 한 번에 찾는다 (9단계 운송, 10단계 취소)
CREATE INDEX idx_reservation_ref ON reservation (ref_type, ref_id);
