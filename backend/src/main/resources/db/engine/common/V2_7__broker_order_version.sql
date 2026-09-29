-- V2.7: 주문 기록 행 버전. 실시간 이벤트와 재동기가 같은 주문을 동시에 고칠 때 먼저 읽은 진행으로 덮어쓰지 않도록 낙관적 잠금에 씀.
-- 지금은 SQLite 쓰기 연결이 하나라 겹치지 않지만, 연결 풀이나 DB 가 바뀌어도 규칙이 지켜지게 함.

ALTER TABLE broker_order ADD COLUMN version INTEGER NOT NULL DEFAULT 0;
