-- 물류센터 상품별 고정 칸. 상품마다 칸 하나, 한 칸에는 한 상품만 둔다
CREATE TABLE product_bin
(
    location_code VARCHAR(10) NOT NULL,
    product_id    BIGINT      NOT NULL,
    bin_code      VARCHAR(20) NOT NULL,
    PRIMARY KEY (location_code, product_id),
    -- 한 칸을 두 상품이 나눠 쓰지 못하게 막는 것이 이 UNIQUE다
    CONSTRAINT uk_product_bin_code UNIQUE (location_code, bin_code),
    -- 매장은 로케이션을 관리하지 않는다
    CONSTRAINT chk_product_bin_location CHECK (location_code = 'DC')
);
