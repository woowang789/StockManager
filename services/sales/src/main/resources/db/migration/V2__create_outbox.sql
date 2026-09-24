-- 발행할 이벤트를 적어 두는 곳. 상태 변경과 같은 트랜잭션으로 적고, 발행기가 읽어서 보낸다
CREATE TABLE outbox
(
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    topic        VARCHAR(100) NOT NULL,
    message_key  VARCHAR(100) NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    payload      JSON         NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    -- 발행하고 브로커의 확인을 들은 시각. NULL이면 아직 보내지 않았다
    published_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    -- 발행기는 "아직 안 보낸 것을 id 순으로" 찾는다
    KEY idx_outbox_unpublished (published_at, id)
);
