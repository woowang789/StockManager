-- 고칠 때마다 1씩 오른다. 관리자가 읽은 version이 그대로일 때만 고친다(낙관적 락)
ALTER TABLE product ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
