-- product 서비스가 알린 상품의 복제본. 번호는 product가 매긴 것을 그대로 쓴다
-- SKU의 중복은 product가 막는다. 복제본은 받은 대로 적기만 해서 SKU에 UNIQUE를 걸지 않는다
CREATE TABLE product
(
    id   BIGINT       NOT NULL,
    sku  VARCHAR(50)  NOT NULL,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id)
);
