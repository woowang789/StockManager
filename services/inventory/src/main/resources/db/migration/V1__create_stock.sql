-- 거점×상품마다 한 행. 재고 유형 5개를 컬럼으로 둔다
CREATE TABLE stock
(
    location_code VARCHAR(10) NOT NULL,
    product_id    BIGINT      NOT NULL,
    in_transit    INT         NOT NULL DEFAULT 0,
    putaway_wait  INT         NOT NULL DEFAULT 0,
    available     INT         NOT NULL DEFAULT 0,
    reserved      INT         NOT NULL DEFAULT 0,
    defective     INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (location_code, product_id),
    CONSTRAINT chk_stock_location CHECK (location_code IN ('STORE', 'DC')),
    -- 코드에 버그가 있어도 DB가 음수 재고를 막는 마지막 방어선
    CONSTRAINT chk_stock_in_transit CHECK (in_transit >= 0),
    CONSTRAINT chk_stock_putaway_wait CHECK (putaway_wait >= 0),
    CONSTRAINT chk_stock_available CHECK (available >= 0),
    CONSTRAINT chk_stock_reserved CHECK (reserved >= 0),
    CONSTRAINT chk_stock_defective CHECK (defective >= 0)
);
