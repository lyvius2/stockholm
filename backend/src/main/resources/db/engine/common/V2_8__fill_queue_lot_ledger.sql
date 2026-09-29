-- V2.8: 체결 → lot 반영.
-- 1) fill_queue: 주문 상태 반영과 같은 트랜잭션에서 넣는 체결 증분(아웃박스). lot 처리가 실패해도 체결을 잃지 않음.
-- 2) lot_ledger: 사용자별 원장 시작 시각. 이 시각의 증권사 보유로 기초 lot 을 만들고 이전 체결은 반영하지 않음.
-- 3) lot.opening: 기초 lot 표시(실제 매수 시각·환율을 몰라 보유 기간을 보이지 않음).

CREATE TABLE fill_queue (
    fill_id         ${id}      NOT NULL PRIMARY KEY,
    user_id         ${id}      NOT NULL,
    broker_order_id TEXT       NOT NULL,
    market          TEXT       NOT NULL,
    code            TEXT       NOT NULL,
    side            TEXT       NOT NULL,
    quantity        ${decimal} NOT NULL,
    amount          ${decimal} NOT NULL,
    fee             ${decimal} NOT NULL,
    tax             ${decimal} NOT NULL,
    currency        TEXT       NOT NULL,
    order_origin    TEXT       NOT NULL,
    executed_at     ${instant} NOT NULL,
    state           TEXT       NOT NULL,
    reason          TEXT,
    created_at      ${instant} NOT NULL,
    updated_at      ${instant} NOT NULL
);
CREATE INDEX idx_fill_queue_user_state_time ON fill_queue (user_id, state, executed_at);

CREATE TABLE lot_ledger (
    user_id    ${id}      NOT NULL PRIMARY KEY,
    started_at ${instant} NOT NULL,
    created_at ${instant} NOT NULL
);

ALTER TABLE lot ADD COLUMN opening ${bool} NOT NULL DEFAULT 0;
