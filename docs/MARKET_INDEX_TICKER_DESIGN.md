# 상단 바 지수 티커 (F23) — 거래소 지수 간략 표시

> 문서 지도: [docs/INDEX.md](INDEX.md) · 기준 문서: [PROJECT.md](../PROJECT.md) · 작업 규칙: [CLAUDE.md](../CLAUDE.md) · 개발 순서: [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)

작성: 2026-09-25 / 상태: **[제안]** — 표시 위치·구성(한국 장 KOSPI·KOSDAQ·Nikkei, 미국 장 DJIA·NASDAQ·S&P 500)·포맷·5분 갱신·왼쪽에서 오른쪽으로 슬라이딩(2026-09-25 아래→위에서 변경)은 사용자 지정. **데이터 출처는 무료 구성으로 확정(2026-09-25)**: 국내 토스 시장 지표, 미국 장중 토스 ETF 프록시(DIA·QQQ·SPY), 미국·일본 종가 FRED(Massive Basic은 포함 지수 확인 뒤 종가 출처로 교체 가능). 장 밖 표시 규칙은 제안 / 관련: [PROJECT.md](../PROJECT.md) 11.3(상단 바)·F9·8.1(장 캘린더), [EXTERNAL_APIS.md](EXTERNAL_APIS.md) 1.1(토스 시장 지표)·2.3(KRX 지수), [DB_SCHEMA.md](DB_SCHEMA.md) 6장, 화면 설계 아티팩트 "상단 바 지수 티커"

## 1. 결론 요약

- 상단 바 **1행의 "Stockholm" 바로 오른쪽**에 지수 세 개를 한 줄로 보인다. 형식은 **`거래소 이름(영어) | 지수 | 등락폭`** 이며 등락폭은 상승이면 빨간 ▲와 상승 수치·백분율, 하락이면 파란 ▼와 하락 수치·백분율이다.
  - 예: `KOSPI | 3,412.85 | ▲ 12.30 (+0.36%)` · `NASDAQ | 18,204.11 | ▼ 96.40 (−0.53%)`
- **어떤 세트를 보이는가**: 한국 정규장(09:00~15:30 KST)에는 **KOSPI · KOSDAQ · NIKKEI 225**, 미국 정규장(현지 09:30~16:00, `America/New_York`, 서머타임 자동)에는 **DJIA · NASDAQ · S&P 500**. 두 장이 모두 닫혀 있으면 **가장 최근에 닫힌 시장의 종가 세트**를 "종가" 칩과 함께 보이고, 다음 장 개장 30분 전부터 그 시장 세트로 바꾼다(미국은 프리마켓 값) [제안].
- **5분마다 갱신**한다. 값이 바뀌면 각 항목이 **왼쪽에서 오른쪽으로 슬라이딩**하며 교체된다: 새 값이 왼쪽에서 들어오고 옛 값은 오른쪽으로 밀려 나간다(0.35초, 항목 폭 안에서만, 상승·하락 색이 바뀌면 함께). 값이 같으면 움직이지 않는다.
- **국내(KOSPI·KOSDAQ)는 토스 OpenAPI 시장 지표**(`/market-indicators/*`, 2026-09-25 규격 확인)에서 온다. **토스에는 해외 지수가 없다**(심볼이 KOSPI·KOSDAQ·국채뿐). 미국 지수는 장중에는 토스 미국 ETF 현재가를 프록시로(SPY·QQQ·DIA), 종가는 Massive Indices Basic(무료·EOD) 또는 FRED로, Nikkei 225는 FRED 일별 종가로 받는다(4장, [제안]).
- 정보 표시일 뿐이다. 어떤 자동 주문의 입력도 아니며, F9 리포트의 "지수·업종" 입력과 같은 캐시를 공유한다.

## 2. 화면

| 항목 | 규칙 |
|---|---|
| 위치 | 1행: Stockholm · **지수 티커** · (빈 공간) · ☰ 관심종목 · 🏛️ 연기금종목 · ⚡ 실시간 급등락 · 전체 정지 |
| 항목 형식 | `NAME` (영문, 회색) · `\|` · 지수(tabular 숫자) · `\|` · `▲ 12.30 (+0.36%)`(빨강) 또는 `▼ 96.40 (−0.53%)`(파랑). 보합은 회색 `— 0.00 (0.00%)` |
| 항목 수 | 항상 3개. 좁은 창(1100px 미만)에서는 등락폭의 백분율만 남긴다 |
| 갱신 | 5분(설정으로 늘릴 수만 있음). 갱신 시 값이 바뀐 항목만 왼쪽→오른쪽 슬라이딩(항목 폭 안에서 클리핑). `prefers-reduced-motion`이면 즉시 교체 |
| 상태 칩 | 장중이면 없음. 장 밖이면 "종가"(회색). 갱신 실패가 10분 넘으면 "지연"(노랑). 미국 프리마켓 값이면 "프리" |
| 툴팁 | 마지막 갱신 시각 · 출처 · 기준 시간대 |
| 클릭 | 없음 [제안]. (후속: 산업 동향 토론이나 F9 리포트로 연결 가능) |
| 조회 제한 모드 | 토스 키 없음 → 티커 자리에 "지수 —" 회색 |

## 3. 세트 선택 규칙 (데몬, `Clock` + 장 캘린더)

```
if KR 정규장 중            → KR 세트 (KOSPI, KOSDAQ, NIKKEI 225)
else if US 정규장 중        → US 세트 (DJIA, NASDAQ, S&P 500)
else if US 개장 30분 전~    → US 세트 + "프리"
else if KR 개장 30분 전~    → KR 세트 + "종가"(전일)
else                        → 가장 최근에 닫힌 시장의 세트 + "종가"
```

- KR·US 정규장은 토스 장 캘린더(휴장일 포함)로 판단한다. Nikkei는 KR 세트에 묶어 KR 장 시간에 보이며 일본 휴장일이면 전일 종가 + "휴장".
- KR 정규장과 US 정규장은 겹치지 않는다(KST 09:00~15:30 vs 22:30~05:00/23:30~06:00).
- 세트가 바뀔 때도 슬라이딩으로 교체한다.

## 4. 데이터

| 지수 | 장중(5분 갱신) | 종가·보완 | 확인 상태 |
|---|---|---|---|
| KOSPI · KOSDAQ | **토스 시장 지표 API** `market-indicators/prices`(심볼 `KOSPI`·`KOSDAQ`, 응답은 `lastPrice`·`timestamp`뿐 → 등락은 `market-indicators/{symbol}/candles`의 전일 종가로 우리가 계산, 2026-09-26 확인) | KRX Open API 지수 일별시세 | **확인됨 2026-09-25**(openapi.json). 갱신 주기·지연 여부는 학습 테스트 |
| DJIA · NASDAQ · S&P 500 | **토스 미국 현재가 API로 ETF 프록시**: DIA(다우) · QQQ(나스닥 100) · SPY(S&P 500). 이미 쓰는 API라 추가 키·한도 없음. 화면에는 `S&P 500 (SPY)`처럼 프록시임을 표기하고 값은 ETF 가격, 등락률은 지수와 거의 같음 [제안] | **Massive Indices Basic**(무료, 분당 5회, 종가 EOD, 지수 종목 제한 — 포함 지수 확인 필요) 또는 **FRED**(`SP500`·`DJIA`·`NASDAQCOM`, 일별 종가, 무료 API 키, 1영업일 지연) | 토스에 지수 없음 확인됨. Massive Basic의 포함 지수는 [확인 필요]. 정확한 지수값이 장중에 꼭 필요하면 Massive Indices Starter($49/월, 15분 지연)가 유일한 유료 후보 |
| NIKKEI 225 | 장중 출처 없음 → **전일 종가 + "종가" 칩**으로 표시 [제안]. (토스 미국 ETF EWJ는 MSCI Japan이라 Nikkei 프록시로 부적합) | **FRED `NIKKEI225`**(일별 종가, 무료 API 키, 1영업일 지연. 출처 Nikkei Industry Research Institute, 재배포 금지 → 개인 화면 표시만) | FRED 시리즈 확인됨(2026-09-25, 최근값 09/25 66,364.20) |

- **확정(2026-09-25, 무료 구성)**: 국내 = 토스 / 미국 장중 = 토스 ETF 프록시(표기) / 미국·일본 종가 = FRED(무료 API 키, `MacroIndicatorPort`). Massive Indices Basic은 포함 지수를 확인해 되면 종가 출처로 FRED보다 우선(당일 EOD가 더 빠름). 유료(Massive Indices Starter)는 쓰지 않는다. FRED 키는 공유 키(admin, 선택)로 `credential_meta`에 `FRED` 종류를 추가한다.
- 프록시 표기 규칙: 이름 뒤에 `(SPY)` 같은 티커, 툴팁에 "ETF 가격, 지수와 등락률이 거의 같음". 미국 종가 세트로 바뀌면 FRED/Massive 지수값으로 교체된다.

- 포트: `MarketDataPort.indexQuotes(List<IndexCode>)` → `IndexQuote(IndexCode code, BigDecimal value, BigDecimal change, Percent changeRatio, Instant asOf, boolean closed)`. `IndexCode { KOSPI, KOSDAQ, NIKKEI225, DJIA, NASDAQ, SP500 }`.
- 갱신: 데몬 스케줄러가 5분마다 현재 세트의 3개를 조회해 메모리 캐시 + `market_index_quote` 표(마지막 값, 재시작·리포트용)에 저장하고 로컬 WebSocket으로 화면에 보낸다. 실패 시 마지막 값 유지 + "지연".
- 표 `market_index_quote(index_code PK, value, change_amount, change_ratio, as_of, closed, source, fetched_at)` → [DB_SCHEMA.md](DB_SCHEMA.md) 6장(③ 캐시, V2). 이력은 두지 않는다(리포트용 일별 종가는 `krx_index_daily`가 담당).
- 호출량: 5분 × 3지수 = 시간당 36회. 토스 MARKET_DATA 한도 안.

## 5. 예외

| 상황 | 동작 |
|---|---|
| 출처 실패 | 마지막 값 + "지연" 칩. 10분 넘게 실패하면 admin 알림 없음(정보 표시) |
| 해외 지수 출처 없음 | 그 자리에 "NIKKEI 225 | — | —" 회색, 툴팁에 "출처 미설정" |
| 휴장일 | "휴장" 칩 + 직전 종가 |
| 토스 키 없음 | 티커 전체 회색 "지수 —" |

## 6. 테스트

- 세트 선택: KST 09:00·15:30·22:00·22:30·05:00 경계와 서머타임 전환일, 휴장일(고정 `Clock` + fake 캘린더).
- 5분 스케줄이 실패 시 마지막 값을 유지하고 "지연"으로 바뀌는지, 값이 같으면 갱신 이벤트를 내지 않는지.
- 화면: 값 변경 시 왼쪽→오른쪽 슬라이딩 1회(옛 값이 항목 폭 밖으로 새지 않음), 색 전환, `prefers-reduced-motion`이면 즉시, 좁은 창 축약.

## 7. 결정 (2026-09-26 확정)

1. 장 밖 표시 규칙 = 최근 닫힌 시장의 종가 + "종가" 칩, 다음 장 개장 30분 전부터 그 시장 세트(3장).
2. ~~해외 지수 출처~~ → **무료 구성으로 확정(2026-09-25)**: 미국 장중 ETF 프록시(SPY·QQQ·DIA), 종가 FRED(또는 Massive Basic), Nikkei FRED 전일 종가. 유료 안은 쓰지 않음.
3. 티커 클릭 동작 없음.
