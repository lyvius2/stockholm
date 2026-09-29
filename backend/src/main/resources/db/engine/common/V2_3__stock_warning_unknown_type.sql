-- V2.3: 증권사가 새로 더한 매수 유의사항 종류를 "경고 없음"으로 오인하지 않도록 unknown_warning 을 더함.
-- stock_warning 은 짧은 TTL 캐시라 비우고 열을 더해도 잃는 것이 없음(기본값 0 이 안전으로 읽히지 않게 먼저 비움).

DELETE FROM stock_warning;
ALTER TABLE stock_warning ADD COLUMN unknown_warning ${bool} NOT NULL DEFAULT 0;
