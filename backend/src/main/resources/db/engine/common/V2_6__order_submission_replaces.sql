-- V2.6: 정정 요청도 주문 요청 기록에 둠. 결과를 모를 때 새 주문을 원주문과 이어 찾기 위해 원주문 번호를 남김.

ALTER TABLE order_submission ADD COLUMN replaces_broker_order_id TEXT;
