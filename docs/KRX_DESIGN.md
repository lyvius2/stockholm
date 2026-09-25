# KRX Open API 어댑터 규격 — 국내 일별 시세·종목 기본정보·지수·ETF

> 문서 지도: [docs/INDEX.md](INDEX.md) · 기준 문서: [PROJECT.md](../PROJECT.md) · 작업 규칙: [CLAUDE.md](../CLAUDE.md) · 개발 순서: [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)

작성: 2026-09-25 / 상태: **[확인함]** — 8개 API의 엔드포인트·요청·응답 필드는 KRX가 배포한 개발 명세서(docx, `docs/external/krx/`, 저장소 밖)로 확인. 갱신 시각·호출 한도·값 형식은 [확인 필요] / 관련: [`docs/EXTERNAL_APIS.md`](EXTERNAL_APIS.md) 2.3, [`docs/STOCK_INFO_DESIGN.md`](STOCK_INFO_DESIGN.md) 3.3·3.6, [`PROJECT.md`](../PROJECT.md) F5·F10·F15

## 1. 결론 요약

- 인증키 확보됨. 모든 API가 같은 모양이다: `POST https://data-dbg.krx.co.kr/svc/apis/{그룹}/{서비스}`, 요청 본문 `{"basDd":"YYYYMMDD"}`, 헤더 `AUTH_KEY: {키}`, 응답 `{"OutBlock_1":[…]}`. 값은 **전부 문자열**이고 없는 값은 `"-"`.
- **일별(전일 확정치)** 데이터, 2010-01-04부터. 장중 값은 없다. 한 호출이 그날의 **전 종목**(또는 전 지수)을 돌려주므로 하루 몇 번 호출로 끝난다.
- 우리 용도: 전 종목 일별 OHLCV·시총·상장주식수(F5 스크리닝, F10 백테스트), 종목 마스터 검증(상장일·액면가·주식종류·영문명), ETF의 NAV·순자산·기초지수(F15 ETF 탭 헤더·괴리율), KOSPI·KOSDAQ·KRX 지수 시리즈(F15 산업군의 업종 지수, F9 리포트).
- **주의: 종목기본정보에 업종(산업 분류) 필드가 없다.** 소속부·증권구분·주식종류뿐이다. F15 산업군 탭의 KRX 업종은 이 API로 못 채운다(6장).
- 키는 공유 키(admin, Keychain). API마다 이용 신청(기간제)이 필요하므로 만료 전 재신청 알림을 둔다.

## 2. 공통 규칙

| 항목 | 값 | 상태 |
|---|---|---|
| 호스트 | `https://data-dbg.krx.co.kr` | 확인함 |
| 인증 | 요청 헤더 `AUTH_KEY: {인증키}` | 확인함(서비스 페이지 안내) |
| 요청 | `InBlock_1` = `{"basDd":"YYYYMMDD"}` 하나. 서비스 페이지의 샘플은 json/xml 선택이 있으므로 JSON 본문 POST로 구현하고, GET 쿼리(`?basDd=`)도 되는지 학습 테스트로 확인 | 필드는 확인함, 전송 방식 [확인 필요] |
| 응답 | `{"OutBlock_1":[{…}]}`. 모든 값 `string`. 결측 `"-"`. 숫자에 천 단위 쉼표가 섞이는지 [확인 필요] → 파서는 쉼표 제거 후 `BigDecimal`, `"-"`는 `Optional.empty` | 형식 확인함 |
| 기준일 | `basDd`는 영업일. 휴장일을 넣으면 빈 배열로 추정 [확인 필요]. 전일 데이터가 열리는 시각 [확인 필요: 예상 다음 영업일 새벽~오전] | |
| 한도 | 문서에 없음 [확인 필요]. 하루 십수 회 호출이라 위험 낮음. 어댑터는 초당 1회 + 429·5xx 지수 백오프 | |
| 이용 신청 | API별 1·3·6·12개월. 만료 30일 전 admin 알림(설정에 만료일 기록) | 확인함 |

## 3. 엔드포인트와 필드 [확인함]

### 3.1 주식 일별매매정보 — `sto/stk_bydd_trd`(유가증권), `sto/ksq_bydd_trd`(코스닥)

`BAS_DD` 기준일자 · `ISU_CD` 종목코드 · `ISU_NM` 종목명 · `MKT_NM` 시장구분 · `SECT_TP_NM` 소속부 · `TDD_CLSPRC` 종가 · `CMPPREVDD_PRC` 대비 · `FLUC_RT` 등락률 · `TDD_OPNPRC` 시가 · `TDD_HGPRC` 고가 · `TDD_LWPRC` 저가 · `ACC_TRDVOL` 거래량 · `ACC_TRDVAL` 거래대금 · `MKTCAP` 시가총액 · `LIST_SHRS` 상장주식수

→ `Candle`(일봉, `Symbol(KR, ISU_CD)`) + `DailyMarketStat(시총·상장주식수·거래대금)`. `ISU_CD`가 6자리 단축코드인지 표준코드(ISIN)인지 [확인 필요: 종목기본정보는 둘을 구분하므로 여기서는 단축코드로 추정].

### 3.2 종목기본정보 — `sto/stk_isu_base_info`, `sto/ksq_isu_base_info`

`ISU_CD` 표준코드(ISIN, `KR7…`) · `ISU_SRT_CD` 단축코드(6자리) · `ISU_NM` 한글 종목명 · `ISU_ABBRV` 한글 종목약명 · `ISU_ENG_NM` 영문 종목명 · `LIST_DD` 상장일 · `MKT_TP_NM` 시장구분 · `SECUGRP_NM` 증권구분(주권·ETF·ETN·리츠 등) · `SECT_TP_NM` 소속부 · `KIND_STKCERT_TP_NM` 주식종류(보통주·우선주) · `PARVAL` 액면가 · `LIST_SHRS` 상장주식수

→ 종목 마스터 검증·보완: 상장일(F7 "신규 상장 직후" 제외 필터의 근거), 우선주 여부, ISIN, 영문명, 증권구분(ETF·ETN 판별 → F15 구성종목 탭 전환). **업종 없음.**

### 3.3 ETF 일별매매정보 — `etp/etf_bydd_trd`

`BAS_DD` · `ISU_CD` · `ISU_NM` · `TDD_CLSPRC` 종가 · `CMPPREVDD_PRC` · `FLUC_RT` · `NAV` 순자산가치 · `TDD_OPNPRC` · `TDD_HGPRC` · `TDD_LWPRC` · `ACC_TRDVOL` · `ACC_TRDVAL` · `MKTCAP` · `INVSTASST_NETASST_TOTAMT` 순자산총액 · `LIST_SHRS` 상장좌수 · `IDX_IND_NM` 기초지수명 · `OBJ_STKPRC_IDX` 기초지수 종가 · `CMPPREVDD_IDX` 기초지수 대비 · `FLUC_RT_IDX` 기초지수 등락률

→ F15 ETF 탭 헤더의 추적지수·순자산, **괴리율 = (종가 − NAV) ÷ NAV**, 기초지수 등락률과 ETF 등락률 차이(추적 오차 참고). 구성종목·운용사·총보수는 **없다**(3.6 출처 별도).

### 3.4 지수 일별시세정보 — `idx/kospi_dd_trd`, `idx/kosdaq_dd_trd`, `idx/krx_dd_trd`

`BAS_DD` · `IDX_CLSS` 계열구분 · `IDX_NM` 지수명 · `CLSPRC_IDX` 종가 · `CMPPREVDD_IDX` 대비 · `FLUC_RT` 등락률 · `OPNPRC_IDX` 시가 · `HGPRC_IDX` 고가 · `LWPRC_IDX` 저가 · `ACC_TRDVOL` · `ACC_TRDVAL` · `MKTCAP` 상장시가총액

→ 시장 지수(코스피·코스닥·KRX 300 등)와 **업종 지수**(코스피 전기·전자, 코스닥 반도체 등 — `IDX_CLSS`로 계열 구분)를 한 호출에 받는다. F15 산업군 "업종 지수 대비 20일 수익률", F9 리포트의 업종 흐름. 종목이 어느 업종 지수에 속하는지는 이 API가 말해 주지 않는다(6장).

## 4. 수집 배치

| 배치 | 호출 | 시점 | 저장 |
|---|---|---|---|
| 일별 시세 | 유가증권 1 + 코스닥 1 | 매 영업일 07:00 KST(전일분, 열리는 시각 확인 후 조정) 실패 시 1시간 간격 재시도 | `krx_daily_price(symbol, bas_dd, open, high, low, close, volume, value, mktcap, list_shrs)` |
| ETF 일별 | 1 | 같은 시각 | `krx_etf_daily(symbol, bas_dd, close, nav, net_assets, index_name, index_close, index_chg_rate)` |
| 지수 | KOSPI 1 + KOSDAQ 1 + KRX 1 | 같은 시각 | `krx_index_daily(idx_class, idx_name, bas_dd, open, high, low, close, volume, value, mktcap)` |
| 종목기본정보 | 유가증권 1 + 코스닥 1 | 주 1회(일요일) + 신규 상장 감지 시 | `krx_issue(isin, short_code, name, abbrev, eng_name, list_dd, market, secu_group, sect, stock_kind, par_value, list_shrs, as_of)` |
| 백필 | 위 호출 × 영업일 수 | 첫 설치 시 2010-01-04부터. 약 3,900영업일 × 6 ≈ 23,000회 → 초당 1회면 6~7시간, 야간에 나눠 실행 | 동일 |

- 하루 정상 호출 6회. 한도가 낮아도 견딘다.
- 토스 일봉과 겹치는 구간(2022-11-23 이후)은 토스를 1차로 쓰고 KRX는 검증·보정용. 그 이전은 KRX만.
- 수정주가는 없다(액면분할·감자 시 과거 가격 불연속). 종목기본정보의 액면가·상장주식수 변화로 이벤트를 감지해 조정 계수를 계산한다 [제안].

## 5. 도메인 매핑

- `MarketDataPort.candles(Symbol, Duration.ofDays(1), from, to)`의 KR 구현체에 **KRX 백필 + 토스 최신** 합성 어댑터(`CompositeKrCandleAdapter`)를 둔다. 경계일 중복은 토스 값 우선.
- 새 포트 없이 `MarketDataPort`(시세·지수)와 `FundamentalsPort.industry`(지수 대비 수익률)로 흡수. ETF 필드는 `EtfCompositionPort`가 아니라 `MarketDataPort.etfDaily(Symbol)` [제안]로 두어 구성종목 출처와 분리한다.
- 값 객체: 가격 `Money(KRW)`, 수량·상장주식수 `Quantity`, 등락률 `Percent`(문자열 "1.23" → 0.0123), 시총·거래대금 `Money`.

## 6. 설계에 주는 영향 — 업종 정보의 출처

종목기본정보에 업종 필드가 없으므로 F15 산업군 탭의 "KRX 업종"은 다른 출처가 필요하다. 후보 순서:
1. **토스 종목 정보**에 업종·GICS가 있으면 그것 [확인 필요: 2단계 학습 테스트].
2. **DART 기업개황**(`company` API)의 `induty_code`(업종 코드, 한국표준산업분류) — 키가 있고 종목당 1회면 된다. 표준산업분류 → 우리 업종 분류 표는 `core`에 둔다 [제안].
3. 지수 구성종목은 KRX Open API에 없으므로 "업종 지수 대비 수익률"은 종목의 업종을 1·2로 정한 뒤 이름이 대응하는 업종 지수(`IDX_NM`)를 고른다. 대응 표는 어댑터 안.

이 결정은 [`docs/STOCK_INFO_DESIGN.md`](STOCK_INFO_DESIGN.md) 3.3에 반영한다.

## 7. 오류와 fail-safe

| 상황 | 동작 |
|---|---|
| 401/403 | 키·이용 신청 문제 → admin 알림, 배치 중단 |
| 빈 배열 | 휴장일 또는 미반영 → 다음 시각 재시도(하루 3회까지), 이후 "미수집" 표시 |
| 값 `"-"` | `Optional.empty`. 스크리닝에서 결측 종목은 제외 |
| 필드 누락·이름 변경 | 학습 테스트 실패로 감지. 저장하지 않고 admin 알림 |
| 호출 초과(429) | `Retry-After` 없으면 10분 대기 |

주문 경로와 무관하다. 시세 백필이 늦어도 자동 매매는 토스 시세로 돈다.

## 8. 테스트

- 학습 테스트(WireMock): 8개 응답 샘플(명세서 sample 형식 + 실제 1일치), `"-"` 결측, 쉼표 숫자, 휴장일 빈 배열, 401.
- 파서: 문자열 → `BigDecimal`/`Percent` 변환, 등락률 부호, 상장주식수 정수.
- 합성 캔들 어댑터: 경계일(2022-11-23) 앞뒤에서 토스 우선 규칙, 빠진 날 없음.
- 배치: 고정 `Clock`으로 07:00 KST 실행, 재시도 3회, 백필 야간 창.

## 9. 열린 항목

1. 요청 전송 방식(JSON POST vs GET 쿼리), 값의 쉼표 여부, `ISU_CD`가 단축코드인지 — 첫 학습 테스트에서 확인(키는 환경 변수로만).
2. 전일 데이터가 열리는 시각과 호출 한도.
3. 업종 출처 결정(6장): 토스 종목 정보 → DART 기업개황 순으로 확인.
4. ETF 구성종목 국내 출처(별도 결정, [`docs/STOCK_INFO_DESIGN.md`](STOCK_INFO_DESIGN.md) 3.6).
5. 이용 신청 만료 관리(설정 항목·알림).
