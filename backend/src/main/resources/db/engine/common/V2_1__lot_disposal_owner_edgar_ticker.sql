-- V2.1: V2 코드 리뷰 반영. V2 는 이미 적용됐을 수 있어 고치지 않고 이 소버전에서 표를 다시 만듦.
-- 1) lot_disposal 의 사용자와 참조하는 lot 의 사용자가 같아야 함. (lot_id, user_id) 복합 FK 로 DB 가 강제함.
-- 2) 한 CIK 에 티커가 여럿인 회사(보통주·우선주 등)를 담기 위해 티커 → CIK 대응을 edgar_ticker 로 분리함.

CREATE UNIQUE INDEX uq_lot_id_user ON lot (lot_id, user_id);

CREATE TABLE lot_disposal_owned (
    disposal_id            ${id}      NOT NULL PRIMARY KEY,
    user_id                ${id}      NOT NULL,
    lot_id                 ${id}      NOT NULL,
    broker_order_id        TEXT       NOT NULL,
    market                 TEXT       NOT NULL,
    code                   TEXT       NOT NULL,
    quantity               ${decimal} NOT NULL,
    sell_price_amount      ${decimal} NOT NULL,
    sell_price_currency    TEXT       NOT NULL,
    buy_unit_cost_amount   ${decimal} NOT NULL,
    buy_unit_cost_currency TEXT       NOT NULL,
    fee_amount             ${decimal} NOT NULL,
    tax_amount             ${decimal} NOT NULL,
    fx_from                TEXT,
    fx_to                  TEXT,
    fx_rate                ${decimal},
    fx_as_of               ${instant},
    realized_amount        ${decimal} NOT NULL,
    realized_currency      TEXT       NOT NULL,
    realized_krw           ${decimal},
    fx_pnl_krw             ${decimal},
    holding_days           INTEGER    NOT NULL,
    lot_origin             TEXT       NOT NULL,
    disposed_at            ${instant} NOT NULL,
    created_at             ${instant} NOT NULL,
    FOREIGN KEY (lot_id, user_id) REFERENCES lot (lot_id, user_id) ON DELETE CASCADE
);
INSERT INTO lot_disposal_owned SELECT * FROM lot_disposal;
DROP TABLE lot_disposal;
ALTER TABLE lot_disposal_owned RENAME TO lot_disposal;
CREATE INDEX idx_lot_disposal_user_time ON lot_disposal (user_id, disposed_at);
CREATE INDEX idx_lot_disposal_user_symbol_time ON lot_disposal (user_id, market, code, disposed_at);
CREATE INDEX idx_lot_disposal_lot ON lot_disposal (lot_id);

-- SEC company_tickers.json 의 티커 한 줄 = 한 행. 같은 CIK 가 여러 행에 나옴.
CREATE TABLE edgar_ticker (
    code       TEXT       NOT NULL PRIMARY KEY,
    cik        TEXT       NOT NULL,
    exchange   TEXT,
    updated_at ${instant} NOT NULL
);
CREATE INDEX idx_edgar_ticker_cik ON edgar_ticker (cik);
INSERT INTO edgar_ticker (code, cik, exchange, updated_at)
    SELECT code, cik, NULL, updated_at FROM edgar_entity WHERE code IS NOT NULL;

CREATE TABLE edgar_entity_v2 (
    cik             TEXT       NOT NULL PRIMARY KEY,
    name            TEXT       NOT NULL,
    sic             TEXT,
    fiscal_year_end TEXT,
    updated_at      ${instant} NOT NULL
);
INSERT INTO edgar_entity_v2 (cik, name, sic, fiscal_year_end, updated_at)
    SELECT cik, name, sic, fiscal_year_end, updated_at FROM edgar_entity;
DROP TABLE edgar_entity;
ALTER TABLE edgar_entity_v2 RENAME TO edgar_entity;
