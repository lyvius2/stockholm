-- V2.4: 주문 요청 기록. 멱등 키마다 한 행이며 증권사에 보내기 전에 남김.
-- 접수 뒤 기록이 실패하거나 데몬이 다시 떠도 SENDING·UNKNOWN 행으로 결과 확인을 이어 감.
-- 같은 키의 두 번째 요청은 PK 충돌로 막혀 새 주문이 되지 않음.
-- broker_order 처럼 사용자 등록부와 물리 FK 는 두지 않음(주문 기록은 증권사 사실과 짝을 이루는 캐시 성격).

CREATE TABLE order_submission (
    client_order_id       TEXT       NOT NULL PRIMARY KEY,
    user_id               ${id}      NOT NULL,
    device_id             ${id}      NOT NULL,
    market                TEXT       NOT NULL,
    code                  TEXT       NOT NULL,
    side                  TEXT       NOT NULL,
    kind                  TEXT       NOT NULL,
    time_in_force         TEXT       NOT NULL,
    limit_price_amount    ${decimal},
    limit_price_currency  TEXT,
    quantity              ${decimal},
    order_amount_amount   ${decimal},
    order_amount_currency TEXT,
    origin                TEXT       NOT NULL,
    trigger_type          TEXT       NOT NULL,
    trigger_json          ${json},
    intended_at           ${instant} NOT NULL,
    high_value_confirmed  ${bool}    NOT NULL,
    state                 TEXT       NOT NULL,
    broker_order_id       TEXT,
    reason                TEXT,
    sent_at               ${instant} NOT NULL,
    updated_at            ${instant} NOT NULL
);
CREATE INDEX idx_order_submission_user_state ON order_submission (user_id, state);
CREATE INDEX idx_order_submission_user_broker ON order_submission (user_id, broker_order_id);
