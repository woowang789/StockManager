-- 거점 간 이동 요청. 출발 거점의 재고를 잡아 두고 도착 거점으로 보낸다
CREATE TABLE transfer
(
    id                 BIGINT      NOT NULL AUTO_INCREMENT,
    from_location_code VARCHAR(10) NOT NULL,
    to_location_code   VARCHAR(10) NOT NULL,
    status             VARCHAR(20) NOT NULL,
    created_at         DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT chk_transfer_from CHECK (from_location_code IN ('STORE', 'DC')),
    CONSTRAINT chk_transfer_to CHECK (to_location_code IN ('STORE', 'DC')),
    -- 같은 거점 안에서 옮기는 것은 이동이 아니다
    CONSTRAINT chk_transfer_locations CHECK (from_location_code <> to_location_code),
    CONSTRAINT chk_transfer_status CHECK (status IN
        ('PENDING', 'REQUESTED', 'REJECTED', 'PICKED', 'IN_TRANSIT', 'RECEIVED', 'CANCELED'))
);

CREATE TABLE transfer_item
(
    transfer_id BIGINT NOT NULL,
    product_id  BIGINT NOT NULL,
    quantity    INT    NOT NULL,
    PRIMARY KEY (transfer_id, product_id),
    CONSTRAINT fk_transfer_item_transfer FOREIGN KEY (transfer_id) REFERENCES transfer (id),
    CONSTRAINT chk_transfer_item_quantity CHECK (quantity > 0)
);

-- 결과를 모른 채 PENDING으로 남은 요청을 재시도 잡이 찾는다
CREATE INDEX idx_transfer_pending ON transfer (status, created_at);
