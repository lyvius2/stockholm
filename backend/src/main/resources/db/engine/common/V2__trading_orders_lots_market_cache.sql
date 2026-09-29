-- V2: 주문·보유·알림(7장)과 종목·시세 캐시 나머지(6장). 2단계(토스 연동·F1~F4·F14·F18·F20·F23) 범위.
-- 자리표시자: ${id} ${decimal} ${instant} ${date} ${bool} ${json} ${text}. 값은 application-{profile}.yml 에서 벤더별로 넣음.
-- 물리 FK 는 lot → app_user, lot_disposal → lot 둘뿐임. 증권사 캐시(broker_order)·알림·시세 캐시는 부모보다 먼저 도착하거나 부모보다 오래 살아야 해서 FK 를 두지 않음.

-- 6장 종목·시세 캐시 (설치 공용, 동기화 안 함)

-- 토스가 주는 1분봉·일봉만 저장함. 3·5·10·30·60분·주·월·년은 조회 때 집계함. is_final=0 은 진행 중인 봉.
CREATE TABLE candle (
    market     TEXT       NOT NULL,
    code       TEXT       NOT NULL,
    interval   TEXT       NOT NULL,
    open_time  ${instant} NOT NULL,
    open       ${decimal} NOT NULL,
    high       ${decimal} NOT NULL,
    low        ${decimal} NOT NULL,
    close      ${decimal} NOT NULL,
    currency   TEXT       NOT NULL,
    volume     ${decimal} NOT NULL,
    adjusted   ${bool}    NOT NULL DEFAULT 1,
    source     TEXT       NOT NULL,
    is_final   ${bool}    NOT NULL DEFAULT 1,
    fetched_at ${instant} NOT NULL,
    PRIMARY KEY (market, code, interval, open_time)
);

-- F23 지수 티커의 마지막 값. 이력은 두지 않음(일별 종가 이력은 krx_index_daily).
CREATE TABLE market_index_quote (
    index_code    TEXT       NOT NULL PRIMARY KEY,
    value         ${decimal} NOT NULL,
    change_amount ${decimal},
    change_ratio  ${decimal},
    as_of         ${instant},
    closed        ${bool}    NOT NULL DEFAULT 0,
    source        TEXT       NOT NULL,
    fetched_at    ${instant} NOT NULL
);

-- KRX Open API 원본(국내만, 원화). 결측 "-" 는 NULL. 수정주가 아님.
CREATE TABLE krx_daily_price (
    code       TEXT       NOT NULL,
    bas_dd     ${date}    NOT NULL,
    open       ${decimal},
    high       ${decimal},
    low        ${decimal},
    close      ${decimal},
    volume     ${decimal},
    value      ${decimal},
    mktcap     ${decimal},
    list_shrs  ${decimal},
    fetched_at ${instant} NOT NULL,
    PRIMARY KEY (code, bas_dd)
);
CREATE INDEX idx_krx_daily_price_date ON krx_daily_price (bas_dd);

CREATE TABLE krx_etf_daily (
    code           TEXT       NOT NULL,
    bas_dd         ${date}    NOT NULL,
    close          ${decimal},
    nav            ${decimal},
    net_assets     ${decimal},
    index_name     TEXT,
    index_close    ${decimal},
    index_chg_rate ${decimal},
    fetched_at     ${instant} NOT NULL,
    PRIMARY KEY (code, bas_dd)
);

CREATE TABLE krx_index_daily (
    idx_class  TEXT       NOT NULL,
    idx_name   TEXT       NOT NULL,
    bas_dd     ${date}    NOT NULL,
    open       ${decimal},
    high       ${decimal},
    low        ${decimal},
    close      ${decimal},
    volume     ${decimal},
    value      ${decimal},
    mktcap     ${decimal},
    fetched_at ${instant} NOT NULL,
    PRIMARY KEY (idx_class, idx_name, bas_dd)
);

-- DART 고유번호 목록. 국내 종목 코드 → corp_code 대응.
CREATE TABLE dart_corp (
    corp_code     TEXT       NOT NULL PRIMARY KEY,
    stock_code    TEXT,
    corp_name     TEXT       NOT NULL,
    corp_name_eng TEXT,
    corp_cls      TEXT,
    induty_code   TEXT,
    acc_mt        TEXT,
    est_dt        ${date},
    updated_at    ${instant} NOT NULL
);
CREATE INDEX idx_dart_corp_stock_code ON dart_corp (stock_code);

-- SEC EDGAR 회사 식별. 미국 티커 → CIK 대응.
CREATE TABLE edgar_entity (
    cik             TEXT       NOT NULL PRIMARY KEY,
    code            TEXT,
    name            TEXT       NOT NULL,
    sic             TEXT,
    fiscal_year_end TEXT,
    updated_at      ${instant} NOT NULL
);
CREATE INDEX idx_edgar_entity_code ON edgar_entity (code);

-- Massive 미국 종목 참조(SIC·CIK·발행주식수·상장일).
CREATE TABLE us_ticker_ref (
    code               TEXT       NOT NULL PRIMARY KEY,
    name               TEXT       NOT NULL,
    cik                TEXT,
    composite_figi     TEXT,
    sic_code           TEXT,
    sic_description    TEXT,
    market_cap         ${decimal},
    shares_outstanding ${decimal},
    list_date          ${date},
    primary_exchange   TEXT,
    fetched_at         ${instant} NOT NULL
);

-- 7장 주문·보유·알림 (사용자 소유, 모든 인덱스의 첫 열은 user_id)

-- 토스 주문 캐시. 토스는 건별 체결을 주지 않아 주문 1건 = 체결 1건으로 이 표에 둠.
-- client_order_id 는 정정·취소로 생긴 주문에 원 키가 이어지는지 확인 전이라 NULL 허용.
CREATE TABLE broker_order (
    broker_order_id          TEXT       NOT NULL PRIMARY KEY,
    client_order_id          TEXT,
    replaces_broker_order_id TEXT,
    user_id                  ${id}      NOT NULL,
    market                   TEXT       NOT NULL,
    code                     TEXT       NOT NULL,
    side                     TEXT       NOT NULL,
    kind                     TEXT       NOT NULL,
    time_in_force            TEXT       NOT NULL,
    limit_price_amount       ${decimal},
    limit_price_currency     TEXT,
    quantity                 ${decimal},
    order_amount_amount      ${decimal},
    order_amount_currency    TEXT,
    status                   TEXT       NOT NULL,
    filled_quantity          ${decimal} NOT NULL,
    avg_price_amount         ${decimal},
    avg_price_currency       TEXT,
    fee_amount               ${decimal},
    tax_amount               ${decimal},
    filled_at                ${instant},
    canceled_at              ${instant},
    reject_reason            TEXT,
    origin                   TEXT       NOT NULL,
    trigger_type             TEXT       NOT NULL,
    trigger_json             ${json},
    remote                   ${bool}    NOT NULL DEFAULT 0,
    high_value_confirmed     ${bool}    NOT NULL DEFAULT 0,
    ordered_at               ${instant} NOT NULL,
    updated_at               ${instant} NOT NULL,
    fetched_at               ${instant} NOT NULL
);
CREATE INDEX idx_broker_order_user_status ON broker_order (user_id, status);
CREATE INDEX idx_broker_order_user_symbol_time ON broker_order (user_id, market, code, ordered_at);
CREATE INDEX idx_broker_order_user_client_order ON broker_order (user_id, client_order_id);
CREATE INDEX idx_broker_order_replaces ON broker_order (replaces_broker_order_id);

-- LotOpened·LotReduced·LotClosed·LotAgedOutOfAutoBuy 의 projection. 청산 lot 도 남김. 해외 lot 의 fx_* 는 값 객체가 필수로 검증함.
CREATE TABLE lot (
    lot_id             ${id}      NOT NULL PRIMARY KEY,
    user_id            ${id}      NOT NULL REFERENCES app_user (user_id) ON DELETE CASCADE,
    market             TEXT       NOT NULL,
    code               TEXT       NOT NULL,
    bought_quantity    ${decimal} NOT NULL,
    remaining_quantity ${decimal} NOT NULL,
    unit_cost_amount   ${decimal} NOT NULL,
    unit_cost_currency TEXT       NOT NULL,
    fx_from            TEXT,
    fx_to              TEXT,
    fx_rate            ${decimal},
    fx_as_of           ${instant},
    bought_at          ${instant} NOT NULL,
    origin             TEXT       NOT NULL,
    broker_order_id    TEXT,
    recommendation_id  ${id},
    aged_out_at        ${instant},
    closed_at          ${instant},
    created_at         ${instant} NOT NULL,
    updated_at         ${instant} NOT NULL
);
CREATE INDEX idx_lot_user_symbol_closed ON lot (user_id, market, code, closed_at);
CREATE INDEX idx_lot_user_origin_time ON lot (user_id, origin, bought_at);

-- 매도 체결이 lot 을 선입선출로 소진한 기록. 원화 실현손익 realized_krw = 매매손익 + fx_pnl_krw.
CREATE TABLE lot_disposal (
    disposal_id            ${id}      NOT NULL PRIMARY KEY,
    user_id                ${id}      NOT NULL,
    lot_id                 ${id}      NOT NULL REFERENCES lot (lot_id) ON DELETE CASCADE,
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
    created_at             ${instant} NOT NULL
);
CREATE INDEX idx_lot_disposal_user_time ON lot_disposal (user_id, disposed_at);
CREATE INDEX idx_lot_disposal_user_symbol_time ON lot_disposal (user_id, market, code, disposed_at);
CREATE INDEX idx_lot_disposal_lot ON lot_disposal (lot_id);

-- 마지막 계좌 스냅샷(패널의 지연·실패 표시용). snapshot_json 에 계좌번호는 없음(끝 4자리도 넣지 않음).
CREATE TABLE portfolio_cache (
    user_id       ${id}      NOT NULL,
    market        TEXT       NOT NULL,
    snapshot_json ${json}    NOT NULL,
    as_of         ${instant} NOT NULL,
    stale         ${bool}    NOT NULL DEFAULT 0,
    error         TEXT,
    PRIMARY KEY (user_id, market)
);

-- 알림 센터의 원천. 같은 (사용자, 종류, 중복 키) 는 한 번만 알림. 보존 180일.
CREATE TABLE notification (
    notification_id ${id}      NOT NULL PRIMARY KEY,
    user_id         ${id}      NOT NULL,
    kind            TEXT       NOT NULL,
    dedupe_key      TEXT       NOT NULL,
    title           TEXT       NOT NULL,
    body            ${text},
    link_json       ${json},
    created_at      ${instant} NOT NULL,
    read_at         ${instant},
    acked_at        ${instant},
    slack_sent_at   ${instant}
);
CREATE UNIQUE INDEX uq_notification_user_kind_dedupe ON notification (user_id, kind, dedupe_key);
CREATE INDEX idx_notification_user_acked ON notification (user_id, acked_at);
