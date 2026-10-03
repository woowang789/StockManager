-- 상품 기준정보. SKU는 사람이 상품을 알아보는 코드라 겹치면 안 된다
CREATE TABLE product
(
    id         BIGINT       NOT NULL AUTO_INCREMENT,
    sku        VARCHAR(50)  NOT NULL,
    name       VARCHAR(100) NOT NULL,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_product_sku UNIQUE (sku)
);
