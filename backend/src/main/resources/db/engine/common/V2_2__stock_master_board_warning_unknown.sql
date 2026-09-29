-- V2.2: 종목 마스터 동기화(토스) 준비.
-- 1) 상장 시장(KOSPI·KOSDAQ·NYSE…)을 담을 listing_board 를 더함. 시장별 상장폐지 표시가 이 열로 범위를 정함.
-- 2) 토스는 관리종목을 주지 않고 미국 종목의 거래정지도 주지 않아, 두 열을 "모름(NULL)"이 되게 다시 만듦.
--    stock_warning 은 짧은 TTL 캐시라 지우고 다시 만들어도 잃는 것이 없음.

ALTER TABLE stock_master ADD COLUMN listing_board TEXT;
CREATE INDEX idx_stock_master_board ON stock_master (listing_board);

DROP TABLE stock_warning;

CREATE TABLE stock_warning (
    market             TEXT       NOT NULL,
    code               TEXT       NOT NULL,
    investment_warning ${bool}    NOT NULL,
    investment_risk    ${bool}    NOT NULL,
    administrative     ${bool},
    trading_halted     ${bool},
    vi_static          ${bool}    NOT NULL,
    vi_dynamic         ${bool}    NOT NULL,
    overheated         ${bool}    NOT NULL,
    liquidation        ${bool}    NOT NULL,
    fetched_at         ${instant} NOT NULL,
    PRIMARY KEY (market, code)
);
