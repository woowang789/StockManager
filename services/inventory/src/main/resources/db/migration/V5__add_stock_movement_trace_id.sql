-- 전표를 만든 요청의 trace_id. 같은 trace_id로 서비스들의 로그를 찾아 이어 본다
-- 이 컬럼 전에 적힌 전표는 비어 있다
ALTER TABLE stock_movement ADD COLUMN trace_id VARCHAR(32) NULL AFTER actor;
