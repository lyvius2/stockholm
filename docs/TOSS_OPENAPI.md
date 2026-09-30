# TOSS_OPENAPI.md — 토스증권 Open API 정리

> 문서 지도: [docs/INDEX.md](INDEX.md) · 기준 문서: [PROJECT.md](../PROJECT.md) · 외부 API 카탈로그: [EXTERNAL_APIS.md](EXTERNAL_APIS.md) 1.1

토스증권 Open API의 규격·운영 규칙·자주 묻는 사실을 한곳에 모은 요약이다. **구현 기준(source of truth)은 언제나 원문 OpenAPI·AsyncAPI JSON**이며, 이 문서는 그것을 빠르게 찾기 위한 색인이다. 기억이 흐리거나 규격이 의심되면 아래 0장의 원문을 다시 받아 대조한다.

기준 버전: REST OpenAPI **v1.2.19**, WebSocket AsyncAPI **v1.2.2** (2026-09-29 수집).

## 0. 원문과 갱신 방법

| 자료 | 주소 | 용도 |
|---|---|---|
| 개발자 문서(브라우저) | https://developers.tossinvest.com/docs#description/introduction | 사람이 보는 API 문서(해시 라우팅 SPA라 도구로 긁기 어려움) |
| LLM 안내 | https://developers.tossinvest.com/llms.txt | 아래 원문들의 목록 |
| 소개·가이드(마크다운) | https://openapi.tossinvest.com/openapi-docs/overview.md | 카테고리·시작하기·호출 한도·오류 모델·웹소켓 연동 가이드 |
| FAQ(마크다운) | https://openapi.tossinvest.com/openapi-docs/faq.md | 인증·시세·주문·조회·수수료·장 운영 FAQ |
| REST 규격 | https://openapi.tossinvest.com/openapi-docs/latest/openapi.json | **정본.** 엔드포인트·스키마·예시·오류·한도 그룹 |
| REST 레퍼런스(마크다운) | https://openapi.tossinvest.com/openapi-docs/latest/api-reference/README.md | 엔드포인트·모델 목록(모델별 md 링크) |
| WebSocket 규격 | https://openapi.tossinvest.com/openapi-docs/latest/asyncapi.json | **정본.** 채널·구독 선언·메시지·한도 |

- 로컬 사본: `docs/external/toss/`(gitignore) — `openapi.json`, `asyncapi.json`, `overview.md`, `faq.md`, `llms.txt`.
- 갱신: 위 주소를 `curl -sSL`로 받아 사본을 덮어쓰고, 이전 사본과 JSON 차이를 비교해 이 문서와 어댑터에 반영한다. 버전이 바뀌면 이 문서 머리의 기준 버전과 Changes를 고친다.
- 1.2.17 → 1.2.19 차이: 토큰 발급 400 오류 예시와 설명(`OAuth 2.0 Parameter: {name}`, `Malformed client authentication`)과 조건주문 수정 설명뿐. 엔드포인트 변화 없음.

## 1. 개요

| 항목 | 내용 |
|---|---|
| REST 서버 | `https://openapi.tossinvest.com` |
| WebSocket 서버 | `wss://openapi-ws.tossinvest.com/ws/v1` |
| 인증 | OAuth 2.0 Client Credentials. 모든 요청에 `Authorization: Bearer {access_token}` |
| 계좌 헤더 | 계좌·자산·주문·조건주문 카테고리는 `X-Tossinvest-Account: {accountSeq}` 도 필수(없으면 400 `account-header-required`) |
| 대상 | 국내(KRX+NXT 통합) 주식, 미국 주식 |
| 카테고리 | 인증 / 시세·종목 정보(Market Data·Stock Info·Market Info·Market Indicators·Ranking) / 계좌·자산 / 주문(Order·Order History·Order Info) / 조건주문 / 웹소켓 |

시세·종목·환율·장 운영·랭킹·지수는 계좌와 무관해 토큰만으로 부른다.

## 2. 시작하기

1. **클라이언트 등록**: 토스증권 WTS 로그인 → 설정 → Open API에서 `client_id`·`client_secret` 발급.
2. **허용 IP 등록**: 같은 화면의 "허용 IP 관리". 목록에 없는 IP에서의 호출은 403(REST·WebSocket 모두).
3. **토큰 발급**: `POST /oauth2/token`.
4. **호출**: Bearer 토큰, 필요한 카테고리는 계좌 헤더를 함께.

## 3. 인증

- `POST /oauth2/token`, 본문 `application/x-www-form-urlencoded`: `grant_type=client_credentials`, `client_id`, `client_secret`.
- 응답은 공통 봉투가 아닌 OAuth2 표준: `access_token`(JWT), `token_type=Bearer`, `expires_in`(초, 예시 86400).
- **refresh token 없음.** 만료되면 같은 엔드포인트로 다시 발급.
- **클라이언트당 유효 토큰은 1개.** 새로 발급하면 직전 토큰은 즉시 무효(`401 token-revoked`). **국내·해외를 다른 프로세스로 돌려도 토큰 하나를 공유해야 한다.** 프로세스마다 발급하면 서로를 무효화한다.
- 오류(OAuth2 형식, `error` 필드로 식별):
  - `401 invalid_client`: `client_id`·`client_secret` 값 오류. `error_description`에 어느 쪽이 틀렸는지 나옴(예: `Client authentication failed: client_secret`). 허용 IP와 무관.
  - `400 invalid_request`: 요청 형식 오류. `OAuth 2.0 Parameter: client_secret`(누락·빈 값), `OAuth 2.0 Parameter: grant_type`(누락 또는 form이 아닌 본문), `Malformed client authentication`(Authorization 헤더 형식·파라미터 중복).
- 그 밖의 401(API 호출 시): `invalid-token`, `expired-token`, `token-revoked`, `login-user-not-found`, `edge-blocked`(Authorization 헤더 누락).

## 4. 공통 규칙

- **성공 봉투** `{"result": ...}`, **오류 봉투** `{"error": {"requestId", "code", "message", "data"}}`. 둘은 동시에 나오지 않는다. `requestId`는 응답 헤더 `X-Request-Id`와 같다(문의 시 첨부, 없으면 `referenceId`·`x-amz-cf-id`).
- `data`는 오류 해결 힌트이며 코드마다 구조가 다르다(예: 호가 단위 오류의 `tickSize`·`nearestPrices`, 금액 한도의 `limits`, 시간 제한의 `retryAfterAt`).
- **숫자는 모두 문자열**(금액·수량·비율). 비율은 소수(`0.1077` = 10.77%).
- **시각은 ISO 8601 KST(+09:00)**. 날짜 파라미터는 `YYYY-MM-DD`.
- **enum은 모르는 값을 허용하도록 구현**하라고 명시(새 값 추가 가능).
- 요청 본문은 `application/json`(아니면 415 `unsupported-content-type`). 토큰 발급만 form.
- 종목 심볼: 국내 6자리(숫자 또는 영문·숫자 혼합, 예 `005930`·`0101N0`), 미국 티커(영문 대소문자·숫자·`.`·`-`). 다건 조회는 콤마 구분 최대 200.
- 주문 식별자 `orderId`는 서버가 발급하는 불투명 문자열.

## 5. 호출 한도

**클라이언트 × 그룹** 단위 초당 요청 수. 수치는 예고 없이 바뀔 수 있으며 응답 헤더 `X-RateLimit-Limit`가 현재 값이다.

| 그룹 | 초당 | 09:00~09:10 KST | 대상 |
|---|---|---|---|
| `AUTH` | 5 | — | 토큰 발급 |
| `ACCOUNT` | 1 | — | 계좌 목록 |
| `ASSET` | 5 | — | 보유 주식 |
| `STOCK` | 5 | — | 종목 정보·매수 유의사항 |
| `STOCK_ALL` | 1 | — | 마켓별 전체 종목 |
| `STOCK_TRADING_TREND` | 10 | — | 국내 수급(투자자별·프로그램·공매도·신용·대차) |
| `MARKET_INFO` | 3 | — | 환율·장 운영 |
| `MARKET_DATA` | 15 | — | 호가·현재가·최근 체결·상하한가 |
| `MARKET_DATA_CHART` | 20 | — | 캔들 |
| `RANKING` | 5 | — | 랭킹 |
| `MARKET_INDICATOR_PRICE` | 10 | — | (표에만 있음) |
| `MARKET_INDICATOR` | 10 | — | 지표 현재가·투자자별 매매대금 |
| `MARKET_INDICATOR_CHART` | 5 | — | 지표 캔들 |
| `ORDER` | 10 | 10 | 주문 생성·정정·취소(매수·매도 합산) |
| `ORDER_HISTORY` | 5 | — | 주문 목록·**주문 상세** |
| `ORDER_INFO` | 6 | **3** | 매수 가능 금액·판매 가능 수량·수수료 |
| `CONDITIONAL_ORDER` | 5 | — | 조건주문 등록·수정·취소 |
| `CONDITIONAL_ORDER_HISTORY` | 10 | — | 조건주문 조회 |

- 응답 헤더(성공·429 모두): `X-RateLimit-Limit`(버스트 용량), `X-RateLimit-Remaining`(남은 토큰, 429면 0), `X-RateLimit-Reset`(토큰 1개 재충전 예상 초). 429에만 `Retry-After`.
- 429 대응 권장: `Retry-After`만큼 대기, 지수 백오프 + jitter, `Remaining`이 낮으면 선제 감속. 429 코드는 `rate-limit-exceeded` 또는 `edge-rate-limit-exceeded`.
- **어뷰징 제한**: 짧은 시간에 대량 주문(예: 소액 주문을 수십 분 내 수백 건)하면 일정 시간 매매가 제한되고, 반복되면 기간이 늘어난다. 매수·매도 건수 한도는 따로 없다.

## 6. 엔드포인트 목록

### 6.1 시세 (Market Data)

| 엔드포인트 | 그룹 | 요점 |
|---|---|---|
| `GET /api/v1/orderbook?symbol=` | MARKET_DATA | 매도 호가(낮은 가격순)·매수 호가(높은 가격순) 전체 스냅샷 |
| `GET /api/v1/prices?symbols=` | MARKET_DATA | 현재가 최대 200건. `symbol`·`timestamp`(마지막 체결)·`lastPrice`·`currency`만. 전일 종가·거래량 없음 |
| `GET /api/v1/trades?symbol=&count=` | MARKET_DATA | 당일 최근 체결 최대 50건. 과거 방향 페이지 없음 |
| `GET /api/v1/price-limits?symbol=` | MARKET_DATA | 당일 상·하한가(미국은 null) |
| `GET /api/v1/candles?symbol=&interval=&count=&before=&adjusted=` | MARKET_DATA_CHART | `interval` `1m`·`1d`, `count` 최대 200(기본 100), 최신순. `before`(포함, ISO 8601, `+`는 `%2B`) + 응답 `nextBefore`로 과거 페이지. `adjusted` 기본 true |

### 6.2 종목 정보 (Stock Info)

| 엔드포인트 | 그룹 | 요점 |
|---|---|---|
| `GET /api/v1/stocks?symbols=` | STOCK | 최대 200건. `name`·`englishName`·`isinCode`·`market`(KOSPI·KOSDAQ·NYSE·NASDAQ·AMEX·KR_ETC·US_ETC)·`securityType`·`isCommonShare`·`status`(SCHEDULED·ACTIVE·DELISTED)·`currency`·`listDate`·`delistDate`·`sharesOutstanding`·`leverageFactor`·`koreanMarketDetail`(정리매매·NXT 지원·KRX/NXT 거래정지). **업종 필드 없음** |
| `GET /api/v1/stocks/all?market=&status=&securityType=&commonShare=` | STOCK_ALL | 마켓별 거래 가능 종목 전량(페이지 없음, `symbol` 오름차순, NASDAQ 약 2,800건). 일 배치 갱신이라 하루 1회 캐시 권장 |
| `GET /api/v1/stocks/{symbol}/warnings` | STOCK | 활성 매수 유의사항(`startDate ≤ 오늘 ≤ endDate` 또는 `endDate` null). 종류: 정리매매·단기과열·투자경고·투자위험·VI(정적/동적/혼합)·신주인수권. **VI는 수 초 내 반영**, 나머지 지정은 일배치. 종목 없으면 404, 유의사항 없으면 빈 배열 |
| `GET /api/v1/stocks/{symbol}/investor-trading?count=&until=` | STOCK_TRADING_TREND | 국내만. 개인·외국인·기관(7개 세부)·기타법인 매수·매도·순매수 **거래량**(금액 없음), 외국인 보유·CFD 잔고. 당일은 장중 잠정치(일부 null), 확정치는 저녁, CFD는 T+1. `nextUntil`로 페이지 |
| `GET /api/v1/stocks/{symbol}/program-trades` | STOCK_TRADING_TREND | 국내만, 차익·비차익 거래량, **KRX만 집계(NXT 제외)** |
| `GET /api/v1/stocks/{symbol}/short-selling` | STOCK_TRADING_TREND | 국내만, 공매도 거래량·대금·비중(분모는 시간외 포함 누적). 확정치는 저녁 |
| `GET /api/v1/stocks/{symbol}/credit-trades` | STOCK_TRADING_TREND | 국내만, 신용융자·신용대주 신규·상환·잔고·공여율. T+1(최신은 전 영업일) |
| `GET /api/v1/stocks/{symbol}/securities-lending` | STOCK_TRADING_TREND | 국내만, 기관 간 대차 체결·상환·잔고(신용대주와 다른 데이터) |

기본 정보·유의사항은 영업일 단위 갱신이라 짧은 주기로 폴링하지 말고 화면·세션 진입 때 캐시하라고 권장한다. 수급 5종은 모두 `count` 최대 100(기본 10), `until`(포함) + `nextUntil` 페이지, 국내 외 종목은 400 `unsupported-market`.

### 6.3 시장 정보 (Market Info)

| 엔드포인트 | 그룹 | 요점 |
|---|---|---|
| `GET /api/v1/exchange-rate?baseCurrency=&quoteCurrency=&dateTime=` | MARKET_INFO | KRW↔USD. **1분 주기 갱신 참고용 표시 환율**(실제 주문 적용 환율과 다를 수 있음). `validFrom`~`validUntil`(보통 1분), `rate`·`midRate`·`basisPoint`·`rateChangeType`. `dateTime`으로 과거 시점 조회 |
| `GET /api/v1/market-calendar/KR?date=` | MARKET_INFO | 통합(KRX+NXT) 기준, 장전·장후 시간외종가 제외. `previousBusinessDay`·`today`·`nextBusinessDay` 3영업일. 휴장일은 `integrated: null`, 세션별 휴장은 그 세션만 null. 세션: `preMarket`(동시호가 시작 시각 포함)·`regularMarket`(종가 단일가 시작)·`afterMarket`(단일가 종료). 정규·애프터 시간은 **KRX와 NXT의 합집합** |
| `GET /api/v1/market-calendar/US?date=` | MARKET_INFO | `date`는 **미국 현지 날짜**. 세션 4개 `dayMarket`·`preMarket`·`regularMarket`·`afterMarket` 각각 nullable, 휴장이면 모두 null. 시각은 KST |

실측(2026-09-28): 국내 프리 08:00~09:00(동시호가 08:50~), 정규 09:00~15:30(종가 단일가 15:20~), 애프터 15:30~20:00(단일가 ~15:40). 미국 데이마켓 09:00~17:00, 프리 17:00~22:30, 정규 22:30~05:00, 애프터 05:00~08:50(KST, 서머타임 기간).

### 6.4 랭킹 (Ranking)

`GET /api/v1/rankings?type=&marketCountry=&duration=&excludeInvestmentCaution=&count=` (RANKING)

- `type`: `MARKET_TRADING_AMOUNT`·`MARKET_TRADING_VOLUME`·`TOP_GAINERS`·`TOP_LOSERS`(시장 전체 기준), `TOSS_SECURITIES_TRADING_AMOUNT`·`TOSS_SECURITIES_TRADING_VOLUME`(토스증권 체결 기준).
- `duration`: `realtime`·`1d`·`1w`·`1mo`·`3mo`·`6mo`·`1y`(거래일 기준). **`TOP_GAINERS`·`TOP_LOSERS`는 `realtime` 불가**(400 `unsupported-ranking-duration`).
- 상위 100위까지. 응답 항목은 `count`보다 적을 수 있음(시세 조회 실패 종목 제외). 집계 안 된 조합은 빈 배열 + `rankedAt: null`.
- `price.basePrice`·`changeRate`: `TOP_*`는 기간 시작 기준가·기간 등락률, 나머지는 전일 기준.

### 6.5 시장 지표 (Market Indicators)

심볼 카탈로그 8종만 지원(그 외 400 `unsupported-symbol`):

| 심볼 | 명칭 | 유형 | 단위 |
|---|---|---|---|
| `KOSPI`·`KOSDAQ` | 코스피·코스닥 | INDEX | 포인트(투자자별 매매대금 지원) |
| `KR_BOND_2Y`·`3Y`·`5Y`·`10Y`·`20Y`·`30Y` | 한국 국채 | BOND | % 수익률(`3.25` = 3.25%) |

| 엔드포인트 | 그룹 | 요점 |
|---|---|---|
| `GET /api/v1/market-indicators/prices?symbols=` | MARKET_INDICATOR | `symbol`·`timestamp`(장 밖이면 null)·`lastPrice`. 등락은 없음(캔들로 계산) |
| `GET /api/v1/market-indicators/{symbol}/candles` | MARKET_INDICATOR_CHART | 분봉은 지수만, 국채는 일봉만(분봉 요청 시 400) |
| `GET /api/v1/market-indicators/{symbol}/investor-trading?interval=&count=&until=` | MARKET_INDICATOR | KOSPI·KOSDAQ만. 투자자 4분류(기관 7개 세부) 매수·매도 **거래대금**(원화 정수). `interval` `1d`·`1w`·`1mo`·`1y`. 외국인은 등록·미등록 합계(종목별 매매동향의 등록외국인 기준과 다름) |

**해외 지수(다우·나스닥·S&P·닛케이)는 없다.**

### 6.6 계좌·자산

| 엔드포인트 | 그룹 | 요점 |
|---|---|---|
| `GET /api/v1/accounts` | ACCOUNT | 정상 상태 계좌만, **현재는 종합매매(`BROKERAGE`)만 반환**. 자녀계좌 불가. `accountNo`·`accountSeq`·`accountType`. `accountSeq`가 계좌 헤더 값 |
| `GET /api/v1/holdings?symbol=` | ASSET | 국내·미국 주식만(해외 옵션·채권 제외). 합산 요약(`totalPurchaseAmount`·`marketValue`·`profitLoss`·`dailyProfitLoss`, 금액은 `krw`/`usd` 칸) + 종목별 `quantity`·`lastPrice`·`averagePurchasePrice`·`marketValue{purchaseAmount, amount, amountAfterCost}`·`profitLoss{amount, amountAfterCost, rate, rateAfterCost}`·`dailyProfitLoss`·`cost{commission, tax}`. 종목별 금액은 거래 통화, 손익률은 원화 환산 기준. 보유 없으면 0과 빈 배열 |

### 6.7 주문 정보 (Order Info)

| 엔드포인트 | 그룹 | 요점 |
|---|---|---|
| `GET /api/v1/buying-power?currency=` | ORDER_INFO | 미수 제외 **현금 기반** 매수 가능 금액. `currency`·`cashBuyingPower`만(D+1·D+2·담보비율 없음) |
| `GET /api/v1/sellable-quantity?symbol=` | ORDER_INFO | 판매 가능 수량 |
| `GET /api/v1/commissions` | ORDER_INFO | 시장별 `commissionRate`(소수 비율, `0.00015` = 0.015%)·적용 기간. KRX·NXT 구분 없음 |

주문 직전 호출을 권장한다.

## 7. 주문

### 7.1 생성 `POST /api/v1/orders` (ORDER)

본문은 두 형태 중 하나(수량 기반 / 금액 기반):

| 필드 | 수량 기반 | 금액 기반 |
|---|---|---|
| `clientOrderId` | 선택. 최대 36자 `[a-zA-Z0-9_-]` | 같음 |
| `symbol`·`side`(BUY·SELL) | 필수 | 필수 |
| `orderType` | `LIMIT`·`MARKET` | `MARKET`만 |
| `timeInForce` | `DAY`(기본)·`CLS`(미국 종가 지정가, LIMIT만)·`OPG`(국내 시가 단일가) | 없음 |
| `quantity` | 필수, 문자열(`^\d+(\.\d+)?$`) | 없음 |
| `price` | LIMIT이면 필수 | 없음 |
| `orderAmount` | 없음 | 필수(달러) |
| `confirmHighValueOrder` | 1억원 이상이면 `true` 필수 | 같음 |

- `quantity`와 `orderAmount` 중 정확히 하나.
- **소수점 수량은 미국 시장가 매도에만**, 그 외 정수만. **금액 주문은 미국 시장가 전용.**
- 소수점·금액 주문 접수 시간은 **정규장 시작 ~ 정규장 종료 1시간 전**. 밖이면 422 `fractional-quantity-outside-regular-hours` / `amount-order-outside-regular-hours`.
- 1억원 이상인데 확인이 없으면 400 `confirm-high-value-required`. 30억원 초과는 422 `max-order-amount-exceeded`.
- 응답: `orderId`·`clientOrderId`**만**(상태·체결 없음). 상태는 상세 조회나 `personal:order`로 확인.
- 멱등성: 같은 `clientOrderId`는 중복 접수되지 않는다. **유효 10분**, 지나면 같은 키도 새 주문. 같은 키에 다른 내용이면 422 `idempotency-key-conflict`. 같은 키가 처리 중이면 409 `request-in-progress`.
- 같은 종목 반대 방향 체결 대기 주문이 있으면 가격이 겹치지 않아도 409 `opposite-pending-order-exists`.
- 주문 가능 시간은 앱과 같음(애프터마켓 포함). 불가 시간은 422 `order-hours-closed`(`data.retryAfterAt`).
- 그 밖의 422: `insufficient-buying-power`, `insufficient-sellable-quantity`, `stock-restricted`, `price-out-of-range`, `order-type-not-allowed`, `prerequisite-required`(레버리지·인버스 ETF 등 사전 요건 — 앱에서 등록 필요), `market-not-supported-for-stock`, `investor-exchange-not-integrated`(투자자지시 거래소 설정이 SOR 통합이 아님), `account-restricted`, `order-limit-exceeded`.
- 500: `internal-error`, `maintenance`(`data.retryAfterSeconds`).
- 주문은 **통합(SOR) 모드만** 지원. 거래소 지정 주문 불가.

### 7.2 정정 `POST /api/v1/orders/{orderId}/modify` (ORDER)

- 본문: `orderType`(필수), `price`, `quantity`, `confirmHighValueOrder`.
- **국내: `quantity` 필수, 양의 정수. 미국: `quantity` 불가**(400 `us-modify-quantity-not-supported`), 가격만.
- 호가 단위 불일치: 400 `invalid-request` + `data.tickSize`·`data.nearestPrices`.
- 응답 `orderId`는 **새로 발급된 주문 번호**. 원주문은 `REPLACED`로 닫힌다.
- 409: `already-filled`·`already-canceled`·`already-modified`·`already-rejected`·`already-processing`(`retryAfterSeconds`). 422: `modify-restricted`·`order-hours-closed`·`investor-exchange-not-integrated`·`prerequisite-required`·`account-restricted`·`max-order-amount-exceeded`. 404: `order-not-found`·`account-not-found`.

### 7.3 취소 `POST /api/v1/orders/{orderId}/cancel` (ORDER)

- 본문 빈 객체. 이미 체결된 주문은 취소 불가.
- 응답 `orderId`는 **취소 요청 레코드의 새 번호**. 원주문은 `CANCELED`.
- 409는 정정과 같은 목록, 422 `cancel-restricted`·`order-hours-closed`.
- 정정·취소 거부는 별도 레코드(`REPLACE_REJECTED`·`CANCEL_REJECTED`)로 생기고 원주문은 이전 상태로 돌아간다.

### 7.4 조회 (ORDER_HISTORY)

- `GET /api/v1/orders?status=OPEN|CLOSED&symbol=&from=&to=&cursor=&limit=`
  - `OPEN`(PENDING·PARTIAL_FILLED·PENDING_CANCEL·PENDING_REPLACE): **전량 반환**, `cursor`·`limit` 무시, `from`/`to`만 적용.
  - `CLOSED`(FILLED·CANCELED·REJECTED·REPLACED·CANCEL_REJECTED·REPLACE_REJECTED): 커서 페이지, `limit` 기본 20·최대 100. 응답 `nextCursor`·`hasNext`.
  - `from`/`to`: 주문 생성일(`orderedAt`, KST) 포함 범위. 지정하지 않으면 계좌 개설 이후 전체.
- `GET /api/v1/orders/{orderId}`: 모든 상태.
- 주문 필드: `orderId`·`symbol`·`side`·`orderType`·`timeInForce`·`status`·`price`·`quantity`·`orderAmount`·`currency`·`orderedAt`·`canceledAt`·`execution{filledQuantity, averageFilledPrice, filledAmount, commission, tax, filledAt, settlementDate}`.
- **체결은 건별이 아니라 주문 1건 = 1행**(평균가·총량, 체결 시각은 마지막 체결). 건별 체결 이벤트는 웹소켓 `personal:order`로만.
- 앱·WTS로 낸 주문, 소수점·예약 주문도 조회된다. **Open API가 지원하지 않는 호가 유형(장전·장후 시간외종가 등)의 주문은 목록·상세 모두에 없다.** 부분 체결 뒤 취소한 주문은 체결 수량을 유지한 채 `CLOSED`에 남는다.

### 7.5 주문 상태

`PENDING`, `PARTIAL_FILLED`, `PENDING_CANCEL`, `PENDING_REPLACE`(진행 중) / `FILLED`, `CANCELED`, `REJECTED`, `REPLACED`, `CANCEL_REJECTED`, `REPLACE_REJECTED`(종료). 모르는 값을 허용할 것.

## 8. 조건주문

- `POST /api/v1/conditional-orders`(CONDITIONAL_ORDER). 필수 `symbol`·`type`·`quantity`·`orderType`·`expireDate`·`first`, 선택 `second`·`clientOrderId`·`confirmHighValueOrder`. 조건(`first`/`second`)은 `orderSide`·`triggerPrice`(감시가)·`orderPrice`(LIMIT일 때 주문 가격).
- 타입: `SINGLE`(한 조건), `OCO`(두 조건 동시 감시, 하나 충족 시 나머지 자동 취소. **둘 다 매도**, `first` 감시가 > 현재가 > `second` 감시가, 지정가만), `OTO`(`first` 체결 후 `second` 감시 시작. `first` 매수·`second` 매도, 지정가만).
- 발동 세션: **국내는 KRX 정규장에서만**, 미국은 거래 가능한 모든 시간대. 감시 장(거래소)은 고를 수 없음(앱의 "장 상관없이").
- OCO·OTO는 **종목당 1개**(422 `duplicate-conditional-order`), SINGLE은 제한 없음. 이미 충족된 가격이면 422 `condition-already-met`.
- 수정 `POST .../{conditionalOrderId}/modify`: 전체 재설정(`type`·`quantity`·`orderType`·`expireDate`·`first`[·`second`]), 타입 전환 가능. **기존을 취소하고 새로 만드는 방식이라 새 `conditionalOrderId`가 발급되고 옛 ID는 무효.**
- 취소 `DELETE .../{conditionalOrderId}`. 조회 `GET /api/v1/conditional-orders?status=OPEN|CLOSED`(OPEN = WATCHING·PAUSED·ORDERING·ORDERED, 커서 페이지 최대 100), 상세 `GET .../{id}`(CONDITIONAL_ORDER_HISTORY). **다른 채널(앱)에서 등록한 조건주문도 함께 반환.**
- 조건주문은 토스 서버에서 감시하므로 클라이언트가 꺼져 있어도 작동한다.
- 조회 응답(`ConditionalOrderDetailResponse`)에 `clientOrderId` 가 없고, 감시 조건(`first`/`second`)에는 **매매 방향이 없다**(`type` STOP/PROFIT_RATE, `status`, `triggerPrice`, `targetProfitRate`, `orderPrice`, `triggeredOrderId`). 조건 상태에는 그룹에 없는 `HOLDING`(OTO 둘째 대기)·`CANCELED`(OCO 반대편 자동 취소)가 있다. `createdAt` 은 KST 오프셋 ISO.
- 수정 본문에는 `symbol`·`clientOrderId` 가 없다(수정은 멱등 키 없음). 수정·등록 응답은 `conditionalOrderId`, 취소는 204 본문 없음. 목록은 `hasNext`·`nextCursor`.

## 9. 시세 데이터의 성질 (FAQ)

- 국내 캔들·체결·호가는 **KRX + NXT 통합 시세**. 거래소 지정 조회·구분 필드 없음. NXT 비거래 종목은 KRX 시세.
- **1분봉 `timestamp`는 봉 종료 시각**: `09:01` 봉 = 09:00:00.000~09:00:59.999 체결. 09:02:00 정각 체결은 `09:03` 봉. **일봉은 그 거래일 0시.**
- 국내 시가 단일가 체결(09:00)은 `09:01` 봉, 종가 단일가(15:30)는 `15:31` 봉. 종가 단일가 접수 구간(15:20~15:30)은 체결이 없어 **직전가로 채운 거래량 0 봉**이 나온다(VI·서킷브레이커·거래정지 때도 같음).
- 국내 1분봉 제공 구간 08:01~20:00.
- **1분봉 합계 ≠ 일봉 거래량**(일봉은 시간외종가·대량·바스켓 매매 등 전부 합산). 1분봉으로 일봉을 재구성할 수 없다.
- 진행 중인 봉은 완성 뒤 값이 달라질 수 있다.
- 수정주가: 가격은 × 수정비율 후 반올림, 거래량은 ÷ 수정비율 후 버림. 원 가격이 필요하면 `adjusted=false`(단, 제공 시작 이전 과거는 이미 수정된 값만 보유).
- 과거 데이터: **국내 2022-11-23 이후, 미국 2021-11-30 이후.** 과거 캔들의 사후 정정은 없다(기업행위 수정주가만). **상장폐지 종목은 과거 시세 조회 불가(404).**
- **미국 시세는 일부 거래소 기준이며 NBBO가 아니다.** 세션에 따라 원천이 다를 수 있다. 1분봉 이상값(장외·특수 체결)을 걸러내지 않으며 정정 여부 필드도 없다. **미국 일봉 OHLC는 정규장 공식 값**(시간외 제외), 거래량은 종합 거래량.
- 국내 호가는 KRX·NXT 가격별 합산 통합 호가라 **매도 1호가 ≤ 매수 1호가로 교차돼 보일 수 있다.** 실시간 호가는 **매번 전체 스냅샷**(증분 없음).
- 최근 체결 최대 50건, 당일 전체 체결 조회 불가.
- 거래정지는 종목 정보의 `krxTradingSuspended`·`nxtTradingSuspended`(현재값만, 이력 없음).

## 10. 오류 코드 요약

| HTTP | 코드 |
|---|---|
| 400 | `invalid-request`(`data`에 `field`·`allowedValues`·`tickSize`·`nearestPrices` 등), `confirm-high-value-required`, `account-header-required`, `unsupported-ranking-duration`, `unsupported-symbol`, `unsupported-market`, `us-modify-quantity-not-supported` |
| 401 | `invalid-token`, `edge-blocked`(헤더 없음), `expired-token`, `token-revoked`, `login-user-not-found` |
| 403 | `edge-blocked`(허용 IP 등), `forbidden` |
| 404 | `edge-blocked`(경로 없음), `stock-not-found`, `exchange-rate-not-found`, `account-not-found`, `order-not-found`, `conditional-order-not-found` |
| 409 | `request-in-progress`, `already-filled`, `already-canceled`, `already-modified`, `already-rejected`, `already-processing`, `opposite-pending-order-exists` |
| 414 | `edge-blocked`(URI 길이) |
| 415 | `unsupported-content-type` |
| 422 | `insufficient-buying-power`, `order-hours-closed`, `stock-restricted`, `price-out-of-range`, `order-type-not-allowed`, `prerequisite-required`, `market-not-supported-for-stock`, `investor-exchange-not-integrated`, `amount-order-outside-regular-hours`, `fractional-quantity-outside-regular-hours`, `modify-restricted`, `cancel-restricted`, `insufficient-sellable-quantity`, `order-limit-exceeded`, `duplicate-conditional-order`, `condition-already-met`, `idempotency-key-conflict`, `account-restricted`, `max-order-amount-exceeded` |
| 429 | `edge-rate-limit-exceeded`, `rate-limit-exceeded` |
| 500 | `internal-error`, `maintenance` |

## 11. 웹소켓

### 11.1 연결과 구독

- `wss://openapi-ws.tossinvest.com/ws/v1`, 연결 시 `Authorization: Bearer {토큰}` 헤더(REST와 같은 토큰). TLS 필수. 허용 IP 목록 동일 적용.
- 연결 실패는 HTTP로: 401(토큰 없음·무효·만료), 403(허용 IP 미등록), 503(서버 오류, 백오프 후 재시도).
- **선언형 구독**: JSON **배열 하나**가 현재 구독 전체(**full-replace**). 새 배열이 기존 구독을 모두 대체하고, 빠진 항목은 해제, `[]`은 전체 해제. 선택 요소 `{"id": "req-1"}`를 넣으면 응답에 되돌려 준다.
- 구독 종류(`type`): `trade:kr`·`trade:us`(체결 틱), `orderbook:kr`·`orderbook:us`(호가), `personal:order`(본인 주문, `codes`에 `accountSeq` 문자열). 국내는 통합 시세만.
- 수신 `topic` = `type` + `:` + 코드. 예 `trade:us:AAPL`, `personal:order:3`.

### 11.2 프레임

| 프레임 | 모양 | 의미 |
|---|---|---|
| `subscriptions` | `{"type","id","subscribed":[...],"rejected":[{"target","code","message"}]}` | 선언 결과. 데이터보다 먼저 옴 |
| `message` | `{"type":"message","topic","data"}` | 체결 `data{price, volume, timestamp, currency}` / 호가 `data{timestamp, currency, asks[], bids[]}` / 주문 `data{event, accountSeq, order{...}}` |
| `error` | `{"type":"error","error":{"code","message"},"id"}` | 선언 전체 실패(기존 구독 유지) 또는 `server-shutdown` |
| `pong` | `{"type":"pong"}` | 텍스트 `PING`의 응답 |

- 주문 이벤트 `event`: `PENDING`, `PARTIAL_FILL`, `FILL`, `CANCELING`, `CANCELED`, `REPLACING`, `REPLACED`, `REJECTED`, `CANCEL_REJECTED`, `REPLACE_REJECTED`. `order`는 주문 상세와 같은 모양(`execution.filledAt` 제외).

### 11.3 연결 유지와 한도

- **서버는 클라이언트로부터 180초간 아무것도 받지 못하면 연결을 끊는다.** 서버가 보내는 데이터는 이 타이머를 리셋하지 않으므로 **60초 간격으로 텍스트 `PING`**(대문자 4글자, JSON 아님)을 보낸다. 표준 ping/pong 프레임도 지원.
- 동시 연결 **계정당 2개**. 초과하면 새 연결이 수락되고 **가장 오래된 연결이 끊긴다.**
- 구독 수 **연결당 100건**(`codes` 합산, 채널×종목 조합 기준, `accountSeq` 포함). 초과 `too-many-topics`.
- 선언 빈도 **초당 5회**. 초과 `rate-limit-exceeded`(1초 대기 후 재선언, `Retry-After` 없음). 한도를 넘은 선언은 거부되고 기존 구독은 유지.

### 11.4 전달 보장과 재동기

| 채널 | 보장 | 대응 |
|---|---|---|
| `trade`·`orderbook` | **LOSSY** — 밀리면 중간 프레임 유실, 최신 우선, 순번 없음 | 유실을 전제로 화면은 최신값만 씀 |
| `personal:order` | **LOSSLESS**(세션 안에서만). 수신이 2초 이상 막히면 서버가 연결을 끊음 | 소비를 막지 말 것. **끊긴 구간은 재전달되지 않으므로 재연결 후 `GET /api/v1/orders`로 재동기** |

### 11.5 선언 오류

- `error.code`: `wrong-format`, `no-type`, `invalid-type`, `no-codes`, `too-many-topics`, `too-many`, `rate-limit-exceeded`, `internal-error`, `server-shutdown`(배포 시, 프레임 직후 연결 종료 → 재연결·재선언).
- `rejected[].code`: `stock-not-found`, `symbol-market-mismatch`, `account-not-found`(본인 계좌가 아니거나 부적격). 거부 항목은 고치기 전까지 재선언해도 다시 거부되므로 목록에서 뺀다. 일부 거부여도 나머지는 정상 구독.

## 12. 데이터 이용 정책

API로 받은 정보는 **투자자 본인의 매매 목적으로만** 쓴다. 상업적 이용은 물론, 비상업 용도라도 **제3자 배포는 금지**다. (Stockholm은 가족 각자가 본인 키로 본인 계좌만 매매 — PROJECT D10.)

## 13. Stockholm 적용 메모

| 원문 사실 | Stockholm 반영 |
|---|---|
| 토큰은 클라이언트당 1개, 프로세스 간에도 공유 | `TossTokenCache`가 사용자별 잠금으로 발급을 직렬화. 한 데몬 안에서만 발급 |
| 401 `token-revoked`·`expired-token` | `TossTokenAuthenticator`가 한 번만 갱신·재시도, 두 번째 401은 `BrokerAccessDeniedException` |
| 그룹별 초당 한도 | Resilience4j RateLimiter 인스턴스(`application-engine.yml`): market-data 15·chart 20·market-info 3·order 10·order-history 5·order-info 3(피크 기준)·asset 5·account 1 |
| 주문 접수 응답에 상태 없음, 출처를 모름 | `TradingPort`는 `OrderReceipt`·`BrokerOrderRecord`만 반환(CORE_DOMAIN Changes 2026-09-29) |
| `clientOrderId` 36자·10분·같은 키 다른 내용 422, 같은 키 재요청은 이전 결과를 그대로 돌려줌. **주문 조회·상세(`Order`)에는 `clientOrderId` 가 없음** | `ClientOrderId`(26자 결정적/ULID, 수동은 화면이 줌), 409 `request-in-progress`는 결과 모름. 결과 모름은 다시 보내지 않음(첫 요청이 안 닿았으면 새 주문이 됨) — `ManualOrderService` 가 미체결·종료 목록을 읽어 속성으로 대조(`SubmissionMatcher`) |
| 시장가 범위·소수점·금액 주문 시간 | `OrderIntent` 형태 불변식 + 가드레일 `MarketOrderScope` |
| 1억 확인·30억 한도 | 가드레일 `HighValueOrder`(노트·거부), `OrderSubmission.isHighValueConfirmed` |
| 반대 방향 미체결 409 | 가드레일 `OppositeSideOpenOrder`로 선제 차단 |
| 정정: 국내 가격+수량, 미국 가격만, 새 orderId. 정정·취소 요청에는 멱등 키가 없고(이미 정정·취소된 주문에는 409), **주문 응답에 원주문 연결 정보가 없음** | `OrderAmendRequest` 검증, `placeAmendment`. `AmendOrderService`: 원주문을 상세 조회로 확인 → 가드레일 → `order_submission`(원주문 번호) → 정정. 국내 정정 수량은 잔량(잔량 이하라 해석과 무관하게 초과 매매 없음). 결과 모름은 원주문 상태로만 판정하고 새 주문 번호는 사람 확인. `CancelOrderService`: 결과 모름은 실시간 채널·재동기에 맡김 |
| 조건주문: 조회에 멱등 키·매매 방향 없음, 수정은 새 번호(멱등 키 없음), 취소 204, 국내는 KRX 정규장에서만 발동 | `ConditionalOrderPort`(`TossConditionalOrderAdapter`, 엔드포인트는 `TossOrderClient`). 지정가만. `ConditionalOrderService`: 가드레일(`evaluateConditional`) → `conditional_submission` → 등록/수정. 결과 모름은 `PendingConditionalOrderResolver` 가 목록 속성 대조(등록)·기존 조건주문 상태(수정)로 확인하고 다시 보내지 않음. 발동된 주문은 일반 주문 채널로 들어와 외부(수동) 출처 |
| 판매 가능 수량(미국은 소수), 수수료율(소수 비율, 시장별·적용 기간), 상하한가(미국 null), 랭킹(급등·급락은 realtime 없음, 빈 조합은 빈 목록), 시장 지표 현재가(등락 없음, 시각 null 가능) | `TradingPort.sellableQuantity`·`commissionRates`, `MarketDataPort.priceLimits` → `OrderTicketService`(주문 모달용, 상하한가·수수료는 못 받아도 나머지 반환, 수수료 기간은 KST 날짜). `RankingPort`·`MarketIndicatorPort`(`TossMarketBoardAdapter`, admin 키) — F11·F23 서비스는 뒤 단계. 호출 한도 `toss-ranking` 5·`toss-market-indicator` 10 |
| 캔들 1분봉 = 종료 시각 | 어댑터가 `openTime = timestamp − 1분` |
| 현재가에 전일 종가 없음 | `Quote(symbol, last, asOf)`, 등락은 일봉으로 |
| 해외 지수 없음 | F23 지수 티커는 ETF 프록시 + FRED(MARKET_INDEX_TICKER_DESIGN) |
| 업종 필드 없음 | F15 산업군은 KRX·SIC 등 다른 출처 |
| 휴장일 `integrated: null` | `TossMarketCalendarAdapter`가 빈 세션 `TradingDay` |
| 계좌는 현재 BROKERAGE만 | `TossAccountLookup`이 종합매매 계좌 하나를 선택(여럿이면 선택 필요 — 마법사 ③ TODO) |
| 웹소켓 180초 무수신 종료, 60초 PING, 연결 2개·구독 100건·선언 5회/초, `personal:order` 재연결 뒤 재동기 | `TossRealtimeFeed`: 키 주인별 연결 1개, OkHttp 표준 ping 60초, 구독 전체를 배열 하나로 선언(변경은 250ms 묶음, 비면 `[]`만), 100건 초과 거부. **연결 성공과 구독 확정을 구분**: 선언 `id` 에 맞는 `subscriptions` 승인을 받아야 `FeedState(CONNECTED, accepted, rejected)` 를 알림, 승인이 10초 안에 없거나 `error` 프레임이면 끊고 재연결(`rate-limit-exceeded` 만 1초 뒤 재선언). 재연결 지수 백오프(1초→60초)와 재선언, 재승인 시 `recovered = true` 로 재동기 신호. `personal:order` 는 토픽 코드와 `data.accountSeq` 가 선택 계좌 순번과 같을 때만 받음. 수신 대기열 `TossFeedInbox`: 시세는 종목별 최신값으로 합치고, 내 주문 이벤트는 순서 보장·상한 1000건, 넘치면 끊고 재연결·재동기(읽기 스레드를 막지 않아 2초 막힘 종료도 피함) |
| `personal:order` 는 세션 안에서만 무손실, 끊긴 구간은 재전달 없음 | `OrderStreamService`: 주문 채널 스트림이 새로 살아날 때(기동 뒤 첫 연결 포함)와 이벤트 반영에 실패했을 때 재동기 — `GET /orders?status=OPEN` → `status=CLOSED`(마지막으로 끝까지 읽은 날부터, 처음이면 30일) → 로컬에만 열린 주문은 상세. 구독은 `FeedSubscriptionService` 가 기능별 대상을 주인 연결 하나로 합쳐 선언(주인별 전체 교체 규격) |
| `/stocks/all` 은 코드·이름·종류·보통주 여부·ISIN 만, 시장별 전량(실측: KOSPI 2,477 · KOSDAQ 1,825 · KR_ETC 0 · NYSE 2,304 · NASDAQ 4,440 · AMEX 3,897 · US_ETC 455). 신주인수권은 8자리 코드(`2109801G`) | `StockMasterSyncService`: 시장별 목록 → `/stocks` 200건씩 → `stock_master` upsert(07:00 KST 이후 첫 확인, 모든 시장 성공 시에만 동기화 시각 기록). 코드 형식이 맞지 않는 행(신주인수권)은 건너뜀. 빈 목록이면 상장폐지 표시를 하지 않음. RateLimiter stock 5 · stock-all 1 |
| 매수 유의사항에 관리종목·투자주의 없음, 거래정지는 `/stocks` 의 `koreanMarketDetail`(국내만) | `StockFlags`: 유의사항 + KRX·NXT 거래정지(어느 쪽이든 정지면 정지). `nxtTradingSuspended` 의 null 은 규격상 NXT 미지원 종목이라 정지 아님, NXT 지원 종목인데 null 이면 모름. 모르는 유의사항 종류는 `hasUnknownWarning`. 관리종목·미국 거래정지는 null(모름). 캐시 TTL 10초(`stock_warning`) |
| 호가는 매번 전체 스냅샷, 국내는 KRX·NXT 합산이라 교차돼 보일 수 있음 | `MarketDataPort.orderBook`(MARKET_DATA 그룹), 통화 불일치는 조회 실패 |
| 환율 `dateTime` 으로 과거 시점 조회, 주문 `execution` 에 환율 없음, 실시간 주문 이벤트에 `execution.filledAt` 없음 | `MarketDataPort.exchangeRateAt`: 해외 체결은 체결 시각 환율로 lot·실현손익 원화 환산. 실시간 채널 체결은 받은 시각을 체결 시각으로 씀. 시각 파라미터(`dateTime`·캔들 `before`)는 초를 늘 넣은 ISO-8601 KST(`OffsetDateTime.toString()` 은 0초를 생략함) |
| 어뷰징 제한(단시간 대량 주문) | 가드레일 분당 자동 주문 상한(PROJECT 8.1, 기본 5회/분) |
| 1분봉 합 ≠ 일봉 | 3·5·10·30·60분·주·월·년 집계는 1분봉으로, 일봉은 토스 일봉 그대로(재구성하지 않음) |
| 캔들 `before` 는 봉 시각 이하(포함), 1분봉 시각은 종료 시각 | 포트는 시작 시각으로 주고받고 `TossMarketDataAdapter` 가 1분봉만 1분을 더하고 뺌. `ChartService` 는 묶음이 요청 수 + 1 이 될 때까지 200개씩 최대 10쪽 받고, 과거가 남았으면 덜 찬 가장 오래된 묶음을 버린 뒤 그 앞에서 이어 받음 |
| 미국 시세 NBBO 아님 | 가격 표시·가드레일은 참고 시세로 다루고 체결가는 주문 상세 기준 |

## Changes

| 날짜 | 변경 |
|---|---|
| 2026-09-29 | 처음 작성. 원문 overview·FAQ·OpenAPI v1.2.19·AsyncAPI v1.2.2를 분석해 정리하고 로컬 사본을 최신으로 갱신 |
| 2026-09-29 | 13장 웹소켓 적용 메모를 구현 내용으로 갱신 |
| 2026-09-29 | 13장 웹소켓 메모에 구독 승인 확인·`[]` 해제·계좌 대조·수신 대기열 상한 반영 |
| 2026-09-29 | 13장에 종목 마스터·매수 유의사항·호가 적용 메모와 시장별 종목 수 실측 추가 |
| 2026-09-29 | 13장 경고 플래그 메모에 NXT null 의 뜻과 모르는 유의사항 처리 추가 |
| 2026-09-29 | 13장: 멱등 키 재요청의 의미, 주문 조회에 멱등 키가 없다는 사실, 결과 모름은 읽기 전용 대조로 확인 |
| 2026-09-29 | 13장: 주문 채널 재동기 시점과 구독 합치기 |
| 2026-09-29 | 13장: 정정·취소 적용 메모 |
| 2026-09-29 | 13장: 재동기에 종료 주문 포함, 이벤트 반영 실패 복구 |
| 2026-09-29 | 13장: 정정 결과 확인 방식(원주문 상태), 국내 정정 수량의 근거 |
| 2026-09-29 | 13장: 체결 시각 환율, 시각 파라미터 형식 |
| 2026-09-30 | 8장: 조건주문 조회 응답에 멱등 키·매매 방향 없음, 수정 본문·응답 형태. 13장: 조건주문 적용 메모 |
| 2026-09-30 | 13장: 판매 가능 수량·수수료·상하한가·랭킹·시장 지표 적용 메모 |
| 2026-09-30 | 13장: 캔들 이어 받기 위치와 차트 봉 묶기 |
