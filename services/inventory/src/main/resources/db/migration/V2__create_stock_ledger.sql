-- 전표: 재고를 바꾼 업무 1건
CREATE TABLE stock_movement
(
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    type        VARCHAR(20) NOT NULL,
    ref_type    VARCHAR(20),
    ref_id      VARCHAR(50),
    reason      VARCHAR(30),
    actor       VARCHAR(50) NOT NULL,
    -- 업무가 일어난 시각과 원장에 적은 시각. 이벤트는 늦게 도착할 수 있어서 둘을 나눈다
    occurred_at DATETIME(6) NOT NULL,
    recorded_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id)
);

-- 전표 줄: 계정(거점×상품×재고 유형)별 증감과 이동 후 잔액
CREATE TABLE stock_entry
(
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    movement_id   BIGINT      NOT NULL,
    location_code VARCHAR(10) NOT NULL,
    product_id    BIGINT      NOT NULL,
    state         VARCHAR(20) NOT NULL,
    delta         INT         NOT NULL,
    balance_after INT         NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_stock_entry_movement FOREIGN KEY (movement_id) REFERENCES stock_movement (id),
    CONSTRAINT chk_stock_entry_state CHECK (state IN ('IN_TRANSIT', 'PUTAWAY_WAIT', 'AVAILABLE', 'RESERVED', 'DEFECTIVE')),
    CONSTRAINT chk_stock_entry_delta CHECK (delta <> 0),
    CONSTRAINT chk_stock_entry_balance CHECK (balance_after >= 0)
);

-- 계정별로 시간순으로 읽는 일이 많다 (통장 조회, 잔액 체인 검사)
CREATE INDEX idx_stock_entry_account ON stock_entry (location_code, product_id, state, id);
