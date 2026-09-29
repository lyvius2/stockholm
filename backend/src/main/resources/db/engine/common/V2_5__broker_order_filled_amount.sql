-- V2.5: 체결 금액 합계. 토스는 주문 한 건에 누적 체결 요약만 주므로, 새로 체결된 몫의 단가는 금액 차이 ÷ 수량 차이로 구함.
-- 평균가에 수량을 곱해 되돌리면 반올림 오차가 생기므로 금액을 그대로 둠.

ALTER TABLE broker_order ADD COLUMN filled_amount_amount ${decimal};
ALTER TABLE broker_order ADD COLUMN filled_amount_currency TEXT;
