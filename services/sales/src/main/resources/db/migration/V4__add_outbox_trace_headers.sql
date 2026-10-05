-- 이벤트를 적은 요청의 trace와 baggage(traceparent, X-User-Id 등). 발행할 때 Kafka 헤더로 싣는다
-- 요청 밖에서 적은 이벤트와 이 컬럼 전에 적은 이벤트는 비어 있다
ALTER TABLE outbox ADD COLUMN trace_headers JSON NULL AFTER payload;
