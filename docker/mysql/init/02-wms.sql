-- 서비스마다 데이터베이스와 계정을 따로 둔다. 계정이 다르면 다른 서비스의 테이블을 아예 읽을 수 없다
CREATE DATABASE wms;
CREATE USER 'wms'@'%' IDENTIFIED BY 'wms';
GRANT ALL PRIVILEGES ON wms.* TO 'wms'@'%';
