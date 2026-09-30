-- V2.10: 조건주문 요청 기록. 멱등 키마다 한 행이며 토스에 보내기 전에 남김.
-- 조건주문 자체는 토스가 원본이라 저장하지 않고, 결과를 모르는 등록·수정을 이어서 확인하는 데만 씀.
-- 같은 키의 두 번째 요청은 PK 충돌로 막혀 새 조건주문이 되지 않음.
-- 수정이면 replaces_conditional_order_id 가 원래 조건주문임(토스가 새 번호를 발급함).

CREATE TABLE conditional_submission (
    client_order_id               TEXT       NOT NULL PRIMARY KEY,
    user_id                       ${id}      NOT NULL,
    device_id                     ${id}      NOT NULL,
    market                        TEXT       NOT NULL,
    code                          TEXT       NOT NULL,
    type                          TEXT       NOT NULL,
    quantity                      ${decimal} NOT NULL,
    currency                      TEXT       NOT NULL,
    first_side                    TEXT       NOT NULL,
    first_trigger_price           ${decimal} NOT NULL,
    first_order_price             ${decimal} NOT NULL,
    second_side                   TEXT,
    second_trigger_price          ${decimal},
    second_order_price            ${decimal},
    expire_date                   ${date}    NOT NULL,
    intended_at                   ${instant} NOT NULL,
    high_value_confirmed          ${bool}    NOT NULL,
    replaces_conditional_order_id TEXT,
    state                         TEXT       NOT NULL,
    conditional_order_id          TEXT,
    reason                        TEXT,
    sent_at                       ${instant} NOT NULL,
    updated_at                    ${instant} NOT NULL
);
CREATE INDEX idx_conditional_submission_user_state ON conditional_submission (user_id, state);
