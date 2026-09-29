-- V2.9: 체결 대기열에 이미 넣은 누적 요약. 증권사 누적 체결(filled_*)과 따로 두어,
-- 체결 금액이 늦게 채워진 체결도 금액이 생긴 뒤 넣지 못한 수량 전체를 한 증분으로 넣을 수 있게 함.

ALTER TABLE broker_order ADD COLUMN queued_quantity ${decimal};
ALTER TABLE broker_order ADD COLUMN queued_amount ${decimal};
ALTER TABLE broker_order ADD COLUMN queued_fee ${decimal};
ALTER TABLE broker_order ADD COLUMN queued_tax ${decimal};
