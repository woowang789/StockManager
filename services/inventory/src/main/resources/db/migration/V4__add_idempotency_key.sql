-- 멱등 키: 문서에 딸린 전표는 문서·단계마다 하나만 남는다 (예: ORDER:ORD-1:RESERVE)
-- 같은 요청이 다시 와도 두 번째 INSERT가 여기서 막힌다.
-- 조정처럼 문서가 없는 전표는 NULL이고, NULL끼리는 UNIQUE에 걸리지 않는다
ALTER TABLE stock_movement
    ADD COLUMN idempotency_key VARCHAR(100) NULL AFTER ref_id,
    ADD CONSTRAINT uk_stock_movement_idempotency_key UNIQUE (idempotency_key);
