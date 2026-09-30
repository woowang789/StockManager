-- 이동으로 생긴 입고 문서는 어느 이동에서 왔는지 남긴다. 공급사 입고는 비어 있다.
-- 도착 검수에서 예정과 다른 수량을 이동 중 분실로 볼지 판단하려면 출처를 알아야 한다
ALTER TABLE inbound
    ADD COLUMN transfer_id BIGINT NULL,
    -- 한 이동이 만드는 입고 문서는 하나다. NULL끼리는 부딪히지 않아 공급사 입고는 제약을 받지 않는다
    ADD CONSTRAINT uk_inbound_transfer UNIQUE (transfer_id),
    ADD CONSTRAINT fk_inbound_transfer FOREIGN KEY (transfer_id) REFERENCES transfer (id);
