-- V2.11: 저장한 봉이 빠짐없이 이어지는 구간. 종목·봉 단위마다 한 행.
-- 장이 닫힌 시간에는 봉이 없어 candle 표만으로는 빠진 구간을 알 수 없으므로 받은 범위를 따로 적음.
-- covered_from 이상 covered_to 이하의 봉은 증권사에 있는 것을 모두 갖고 있음.
-- reached_start = 1 이면 covered_from 보다 과거의 봉은 증권사에도 없음.

CREATE TABLE candle_coverage (
    market        TEXT       NOT NULL,
    code          TEXT       NOT NULL,
    interval      TEXT       NOT NULL,
    covered_from  ${instant} NOT NULL,
    covered_to    ${instant} NOT NULL,
    reached_start ${bool}    NOT NULL DEFAULT 0,
    updated_at    ${instant} NOT NULL,
    PRIMARY KEY (market, code, interval)
);
