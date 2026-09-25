# 외부 API 카탈로그

> 문서 지도: [docs/INDEX.md](INDEX.md) · 기준 문서: [PROJECT.md](../PROJECT.md) · 작업 규칙: [CLAUDE.md](../CLAUDE.md) · 개발 순서: [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)

작성: 2026-09-20
관련: [`PROJECT.md`](../PROJECT.md) 8장(외부 연동 포트)

Stockholm이 사용하는 외부 API를 **필수 / 권장 / 있으면 좋음**으로 나누어 정의한다. 각 API는 `core.port`의 포트 인터페이스 뒤에 어댑터로 붙인다. 표기: **[확인함]** 공식 문서로 확인(2026-09-20 기준) / **[확인 필요]** 착수 전 문서·약관 확인이 필요.

API 규격은 바뀐다. 어댑터를 구현할 때는 이 문서가 아니라 **공식 문서를 기준**으로 하고, 이 문서와 다르면 이 문서를 고친다.

---

## 1. 필수

### 1.1 토스증권 Open API — 매매·시세·계좌 [확인함]

유일한 주문 경로이자 1차 시세 소스. 포트: `TradingPort`, `MarketDataPort`, `MarketCalendarPort`.

- 문서: https://developers.tossinvest.com/docs · AI용 색인 https://developers.tossinvest.com/llms.txt
- 정식 규격: `https://openapi.tossinvest.com/openapi-docs/latest/openapi.json`(REST), `…/asyncapi.json`(WebSocket) → **어댑터의 DTO는 이 규격에서 생성하거나 대조한다**
- 서버: `https://openapi.tossinvest.com` / `wss://openapi-ws.tossinvest.com/ws/v1`
- 인증: OAuth 2.0 Client Credentials(`POST /oauth2/token`). 계좌·주문 API는 `X-Tossinvest-Account: {accountSeq}` 헤더 추가

| 그룹 | 엔드포인트 | Stockholm에서의 용도 |
|---|---|---|
| 시세 | `GET /api/v1/prices`, `/orderbook`, `/trades` | F1 주문 화면, 급등 탐지, 자동 매도 판단 |
| 차트 | `GET /api/v1/candles` (1분봉·일봉, 1회 최대 200개) | F3 차트, 지표 계산, 스크리닝 |
| 종목 | `GET /api/v1/stocks`, `/stocks/{symbol}/warnings` | F4 종목 마스터, **F7 제외 필터(투자경고·위험·VI·정리매매 등)** |
| 시장 정보 | `GET /api/v1/exchange-rate`, `/market-calendar/*` | 해외 노출액 원화 환산, **KR/US 세션·휴장일 계산** |
| 랭킹 | `GET /api/v1/rankings` (거래대금·거래량·등락률) | **F7 급등 종목 탐지의 1차 입력**, F5 스크리닝 |
| 시장 지표 | `GET /api/v1/market-indicators/*` (지수, 국채) — **2026-09-25 openapi.json 확인: 심볼은 `KOSPI`·`KOSDAQ`와 국채 `KR_BOND_2Y~30Y`뿐, 해외 지수 없음.** 응답 `symbol·timestamp·value·change·changeRate`, MARKET_DATA 한도 그룹. 투자자별 매매대금(개인·외국인·기관)도 여기 | F9 시장 리포트, **F23 지수 티커의 KOSPI·KOSDAQ**. 해외 지수는 `docs/MARKET_INDEX_TICKER_DESIGN.md` 4장의 대체 출처 |
| 수급 | STOCK_TRADING_TREND 그룹 | F5 스크리닝, 토론 자료 |
| 계좌·자산 | `GET /api/v1/accounts`, `/holdings` | 잔고·보유 조회, F2 손익 |
| 주문 | `POST /api/v1/orders`, `…/{orderId}/modify`, `…/{orderId}/cancel` | F1, F7, F8 |
| 주문 조회 | `GET /api/v1/orders`, `/orders/{orderId}` | F2, **자동 주문 직전 중복 확인** |
| 주문 정보 | `GET /api/v1/buying-power`, `/sellable-quantity`, `/commissions` | **예수금 하한 검사**, 90% 한도 계산, 순손익 계산 |
| 가격 제한 | `GET /api/v1/price-limits` (상·하한가) | **F7 제외 필터 "상한가 근접"**, 주문 모달 가격 검증 |
| 종목 수급 상세 | `GET /api/v1/stocks/{symbol}/investor-trading`, `/program-trades`, `/credit-trades`, `/securities-lending`, `/short-selling` | F5 수급 스크리닝, 토론 자료, F12 대신 쓰는 "수급 심리" 지표 |
| 조건주문 | `POST /api/v1/conditional-orders` (SINGLE·OCO·OTO), 수정·삭제·조회 | 아래 "설계 영향 4" 참조 |
| 실시간 | WebSocket `trade:{kr\|us}`, `orderbook:{kr\|us}`, `personal:order` | 급등 탐지, 체결 알림 |

**커뮤니티 없음(2026-09-23 전체 엔드포인트 확인).** 게시글·댓글·소셜 관련 엔드포인트는 없다. 비공식 WTS 내부 엔드포인트는 약관 밖이며 주문 계정과 같은 계정을 쓰므로 절대 쓰지 않는다. 커뮤니티는 F12(링크아웃 + StockTwits 공개 API)로 간다.

**호출 제한(초당, 클라이언트×그룹).** ACCOUNT 1 · STOCK_ALL 1 · MARKET_INFO 3 · AUTH/ASSET/STOCK/RANKING/ORDER_HISTORY/CONDITIONAL_ORDER 5 · ORDER_INFO 6(09:00~09:10에는 3) · ORDER 10 · MARKET_DATA 15 · MARKET_DATA_CHART 20. 응답 헤더 `X-RateLimit-*`, 429 시 `Retry-After`. WebSocket은 계정당 동시 연결 2개, 연결당 구독 100건, 구독 선언 5회/초, 180초 무수신 시 종료(60초 PING 권장). 시세 채널은 유실 가능(LOSSY), `personal:order`는 세션 내 무손실이나 재연결 시 재동기 필요.

**제약.** 국내 시세는 KRX+NXT 통합(거래소 지정 불가). 캔들 과거 데이터는 국내 2022-11-23, 미국 2021-11-30 이후만. 미국 금액 주문은 정규장만. 미국 레버리지·인버스 ETF는 앱에서 사전 등록 필요. 1억원 이상 주문은 `confirmHighValueOrder: true`. 체결은 주문 1건 1행(건별 체결 미제공, 평균가). 모의투자 환경은 문서에서 확인되지 않음. **이용 목적은 본인 매매에 한정, 상업적 이용·제3자 배포 금지.** 단시간 대량 주문은 어뷰징으로 매매 제한될 수 있음.

#### 설계에 영향을 주는 확인 결과

1. **멱등성 확인됨.** 동일 `clientOrderId`는 중복 접수되지 않는다. → 자동 주문의 `clientOrderId`는 `(userId, 전략, 종목, 거래일, 회차)`에서 결정적으로 생성한다. lease·사전 조회와 함께 이중 주문 방어의 3중 장치가 된다.
2. **허용 IP 사전 등록 필수(미등록 IP는 403).** 고정 IP가 없는 환경은 차단된다. → "밖의 Mac이 독립 동작"(D5)과 충돌한다. 밖의 Mac은 접속 위치마다 공인 IP가 바뀌므로 그대로는 토스 API를 못 쓴다. 대안: (a) 고정 IP를 가진 VPS를 **토스 API 전용 송신 프록시**로 두고 그 IP를 등록, (b) 집 회선 경유(VPN) — 단 집 Mac/공유기가 꺼지면 불가, (c) 자주 쓰는 장소의 IP를 수동 등록. **결정 필요.** (a)를 택하면 relay 서버용 VPS가 이 역할을 겸할 수 있으나, 그 경우에도 VPS는 TLS 터널만 중계하고 토큰·키는 보지 못하게 설계한다.
3. **클라이언트당 유효 토큰 1개.** 새 토큰을 발급하면 직전 토큰이 즉시 무효화된다. → 같은 `client_id`를 두 디바이스가 쓰면 서로의 토큰을 계속 무효화한다. 대안: (a) 디바이스마다 별도 client를 발급(가능 여부 [확인 필요]), (b) 토큰을 사용자 동기화 키로 암호화해 디바이스 간 공유하고 발급은 lease 보유 디바이스만 수행. 단독 모드에는 영향 없음.
4. **조건주문(OCO/OTO)이 서버 측에서 동작한다.** 손절·익절을 토스에 걸어 두면 **Mac이 꺼져 있어도 출구가 작동**한다. 자동 매수 직후 OCO(손절+익절)를 함께 거는 옵션을 F7에 추가할 가치가 크다. 제약: OCO/OTO는 종목당 1개. [제안]
5. **랭킹·경고·시장 달력 API가 있다.** 급등 탐지, 제외 필터, 세션 계산을 크롤링 없이 공식 API로 구현할 수 있다.
6. **과거 캔들이 짧다.** 장기 백테스트(F10 스크리닝 튜닝)에는 2장의 보조 소스가 필요하다.
7. **모의투자 환경이 없다.** 우리의 "모의 실행" 단계와 fake `TradingPort`가 유일한 안전한 검증 수단이다.
8. **호출 제한 대응.** 어댑터에 그룹별 rate limiter(토큰 버킷)를 내장하고, 09:00~09:10 피크 제한을 반영한다. 어뷰징 제한을 피하기 위해 자동 주문에 **분당 주문 수 상한**을 가드레일로 추가한다. [제안]
9. **정정·취소는 새 주문을 만든다 (2026-09-24 확인, v1.2.17).** `modify`(KR 가격+수량 필수, US 가격만, `quantity` 주면 `400 us-modify-quantity-not-supported`)·`cancel` 응답의 `orderId`는 원주문과 다른 새 식별자. 원주문은 `REPLACED`/`CANCELED`, 거부는 `REPLACE_REJECTED`/`CANCEL_REJECTED` 별도 레코드 + 원주문 복귀. 이미 체결된 주문은 409. 상태 10종(`PENDING, PARTIAL_FILLED, PENDING_CANCEL, PENDING_REPLACE, FILLED, CANCELED, REJECTED, REPLACED, CANCEL_REJECTED, REPLACE_REJECTED`), 미지 코드 허용 필수. 목록은 `status=OPEN`(전량, 커서 무시)/`CLOSED`(커서·limit 최대 100), `ORDER_HISTORY` 그룹. 시간외 호가 유형으로 낸 주문은 목록·상세에 안 나온다. `personal:order`는 `accountSeq`로 구독, 세션 안 무손실, 재연결 시 `OPEN` 재동기, 수신 2초 이상 막히면 서버가 끊음. → [`docs/ORDER_MANAGEMENT_DESIGN.md`](ORDER_MANAGEMENT_DESIGN.md). [확인 필요: 부분 체결 뒤 정정 `quantity`의 기준, 새 주문의 `clientOrderId` 승계]

### 1.2 금융결제원 오픈API — 본인인증·자산 조회 [확인 필요]

포트: `IdentityPort`, `AssetPort`. 포털: https://openapi.kftc.or.kr · 개발자 사이트: https://developers.kftc.or.kr

- 용도: 본인인증, 본인 계좌 잔액·거래내역 조회(자산 현황 화면)
- 오픈뱅킹 계열은 OAuth 2.0 **Authorization Code**(사용자 동의 화면 → redirect) 방식이다. 데스크톱 앱에서는 로컬 데몬의 루프백 주소 또는 커스텀 URL 스킴으로 redirect를 받아야 하므로 등록 가능한 redirect URI 형식을 확인한다.
- 확인할 것: 확보한 키가 테스트베드용인지 운영용인지, 이용 가능한 API 범위(본인인증, 계좌 통합 조회, 잔액, 거래내역), 가족 4명이 각자 동의해 쓰는 구성이 약관상 가능한지, 토큰 유효기간과 갱신 방식

### 1.3 DART 오픈API (OpenDART) — 국내 공시·재무 [확인함: 이용 자격·그룹 구성·일일 한도 / 엔드포인트명은 구현 시 개발가이드와 대조]

포트: `DisclosurePort`(KR 구현체), `FundamentalsPort`. https://opendart.fss.or.kr · 개발가이드 https://opendart.fss.or.kr/guide/main.do

- 금융감독원 운영, 무료. **개인도 인증키 발급 가능**(개인·기업·기관 제한 없음). 인증키는 40자, 모든 요청에 `crtfc_key` 파라미터로 전달
- 기본 URL `https://opendart.fss.or.kr/api/{API명}.json|.xml`, GET. 응답의 `status`가 `000`이면 정상
- **한도: 일반적으로 하루 20,000건**(초과 시 `020`). 단시간 과다 호출은 차단될 수 있으므로 어댑터에 보수적인 rate limiter(초당 수 건 이하)를 둔다. 가족 4명이 각자 키를 쓰면 한도도 각자다
- 주요 상태 코드: `000` 정상 · `010` 미등록 키 · `011` 사용 불가 키 · `012` 접근 불가 IP · `013` 조회 데이터 없음 · `020` 요청 제한 초과 · `100` 부적절한 필드 값 · `800` 점검 중 · `900` 정의되지 않은 오류. `013`은 오류가 아니라 "결과 없음"이므로 예외로 던지지 않는다

| 그룹 | 주요 API | Stockholm에서의 용도 |
|---|---|---|
| DS001 공시정보 | 공시검색 `list`, 기업개황 `company`, 공시서류 원본 `document`(zip/XML), 고유번호 `corpCode`(zip/XML) | **신규 공시 감시**, 종목코드↔`corp_code` 매핑, RAG 원문 수집 |
| DS002 정기보고서 주요정보 | 배당, 증자·감자 현황, 자기주식 취득·처분, 최대주주·변동, 소액주주, 임원·직원 현황, 임원 보수, 타법인 출자, 주식 총수, 감사의견 등 약 30종 | F5 스크리닝(배당·지배구조), 토론 시드 자료 |
| DS003 정기보고서 재무정보 | 단일회사 주요계정 `fnlttSinglAcnt`, 다중회사 주요계정 `fnlttMultiAcnt`, 단일회사 전체 재무제표 `fnlttSinglAcntAll`, 주요 재무지표(단일·다중), XBRL 원본, 택소노미 | **F5 1단 정량 스크리닝의 재무·밸류에이션 입력**. 다중회사 API로 호출 수 절약 |
| DS004 지분공시 | 대량보유 상황보고 `majorstock`, 임원·주요주주 소유보고 `elestock` | 내부자·대주주 매매 신호(가치투자자·역발상 페르소나 자료) |
| DS005 주요사항보고서 | 유상·무상증자, 감자, 전환사채·신주인수권부사채 발행, 자기주식 취득·처분 결정, 합병·분할, 영업정지, 회생절차 개시, 부도 발생, 소송 등 | **자동화 가드레일의 이벤트 입력**(아래) |
| DS006 증권신고서 | 지분증권, 채무증권, 합병·분할 등 | 신규 상장·대규모 발행 파악(우선순위 낮음) |

#### Stockholm에서의 사용 방식

1. **신규 공시 감시(폴링).** DART에는 푸시가 없다. `list`를 `corp_code` 없이 당일 범위로 1~2분 간격 조회해 새 접수번호(`rcept_no`)를 감지한다(하루 1,000건 안팎이라 한도에 여유). 보유·관심·자동 매수 후보 종목의 공시만 걸러 이벤트로 발행한다. `corp_code` 없이 조회할 때는 검색 기간이 3개월로 제한된다.
2. **가드레일 연동 [제안].** DS005 계열의 악재성 공시(유상증자, CB·BW 발행, 감자, 영업정지, 회생절차, 부도, 관리종목 사유)가 감지된 종목은 **자동 매수 제외 목록에 즉시 추가**하고, 보유 중이면 Slack 긴급 알림 + 자동 매도 허용 종목이면 매도 판단을 앞당긴다. 공시 유형 → 조치 매핑은 `core`의 규칙 테이블로 둔다(LLM 판단에 맡기지 않는다).
3. **토론·리포트 자료.** 새 공시가 뜬 관심 종목은 빠른 토론(F6)을 자동 실행하도록 설정할 수 있다. 공시 요약은 LLM이 하되 원문 링크(`https://dart.fss.or.kr/dsaf001/main.do?rcpNo={rcept_no}`)를 항상 함께 저장한다.
4. **RAG 수집.** `document`는 zip 안에 DART 전용 XML로 온다. 텍스트 추출기를 어댑터에 두고, 사업보고서 전체가 아니라 "사업의 내용", "위험 요소", "재무에 관한 사항" 등 필요한 절만 색인한다.
5. **종목 매핑.** `corpCode` 전체 파일(zip)을 주 1회 내려받아 `stock_code ↔ corp_code` 테이블을 갱신한다. 상장사만 `stock_code`가 채워져 있다.

#### 주의점

- **공시 시각이 없다.** `list` 응답의 접수일자(`rcept_dt`)는 날짜(YYYYMMDD)뿐이다. 장중 공시인지 장 마감 후 공시인지 API만으로는 알 수 없으므로, **우리 시스템이 처음 감지한 시각(`firstSeenAt`)을 반드시 함께 저장**한다. 백테스트에서 미래 정보가 섞이는 것을 막는 핵심 장치다. 감시를 시작하기 전의 과거 공시는 보수적으로 "다음 거래일 장 시작 전 공개"로 취급한다.
- 재무정보 API(DS003)는 2015년 이후 보고서부터 제공된다. 분기·반기·사업보고서 구분은 `reprt_code`(11013 1분기, 11012 반기, 11014 3분기, 11011 사업보고서), 연결/별도는 `fs_div`(CFS/OFS)로 지정한다.
- 정정 공시가 잦다. 같은 보고서의 정정본이 오면 이전 값을 덮지 말고 버전으로 쌓는다(이벤트 로그 원칙과 동일).
- 금융업종은 계정 체계가 달라 일반 제조업용 스크리닝 지표를 그대로 적용하면 안 된다.
- 코스피·코스닥 상장사 대상. ETF·ETN은 DART 공시 대상이 아니므로 다른 소스가 필요하다.

### 1.4 LLM API — 목적별 다중 사용 [확정]

포트: `LlmPort`. 설계 상세는 [`docs/LLM_ROUTING.md`](LLM_ROUTING.md).

- 지원 제공자: **OpenAI, Claude(Anthropic), Ollama(로컬), DeepSeek.** 네 제공자 모두 Spring AI에 채팅 모델 구현이 있다(DeepSeek는 전용 스타터, OpenAI 호환 엔드포인트로도 가능). [확인함]
- 목적(`LlmPurpose`)별로 제공자·모델을 설정에서 매핑하고, 페르소나마다 다른 모델을 배정할 수 있다
- 키는 사용자가 클라이언트에 등록(Keychain). 실행 중 등록·교체되므로 Spring AI 자동 설정 대신 빌더로 `ChatModel`을 직접 구성
- 요구 기능: 구조화 출력(제공자별 방식 차이는 어댑터가 흡수, 검증은 우리 코드), 스트리밍, 토큰 사용량 집계, 프롬프트 캐싱
- 사용자별 일일 예산과 제공자별 "개인 데이터 전송 허용" 스위치를 둔다

### 1.5 임베딩 API — RAG

포트: `EmbeddingPort`. 한국어 품질이 핵심.

- 후보: OpenAI `text-embedding-3` 계열, Voyage, Cohere multilingual, 또는 로컬 Ollama(`bge-m3` 등 다국어 모델)
- 로컬 임베딩은 비용이 0이고 자료가 밖으로 나가지 않는 장점. Mac mini에서는 로컬, 노트북에서는 API 같은 선택을 설정으로 열어 둔다
- 주의: 모델을 바꾸면 인덱스를 전부 다시 만들어야 한다. 인덱스에 모델 식별자를 기록한다

### 1.6 Slack Web API — 알림

포트: `NotifierPort`. 봇 토큰 방식(Incoming Webhook은 사용자별 DM이 어려움).

- `chat.postMessage`(DM·채널), `conversations.open`(DM 채널 열기), `users.lookupByEmail`(회원 ↔ Slack 사용자 매핑)
- 필요한 스코프: `chat:write`, `im:write`, `users:read.email`
- 알림 전용. 인터랙티브 버튼(승인)은 쓰지 않는다(D12)

### 1.7 SMTP — 메일 인증번호 (relay 전용)

가입 확인·새 디바이스 등록·계정 복구용. Gmail 앱 비밀번호, AWS SES, Resend 등 표준 SMTP를 설정으로 받는다. Spring Mail.

---

## 2. 권장

### 2.1 한국투자증권 KIS Developers — 정보 수집 보조 [확인 필요: 약관]

포트: `MarketDataPort`(정보 전용, **주문 기능 구현 금지** — D8). https://apiportal.koreainvestment.com · 공식 샘플 https://github.com/koreainvestment/open-trading-api

- 개인 대상 REST·WebSocket API 중 가장 성숙하고 커뮤니티 자료가 많다. 토스 API에 없는 항목을 메우는 용도: 장기 과거 시세, 재무비율, 투자자별 매매동향 상세, 업종 지수, 해외 시세 상세
- 계좌 개설이 필요하다. 시세만 쓰더라도 약관상 이용 조건을 확인한다

### 2.2 신한투자증권 Open API [확인 필요]

프로젝트 개요에 있던 항목. 신한 그룹 포털(https://openapi.shinhan.com)이 있으나, 개인이 증권 시세 API를 쓸 수 있는지, REST인지(과거 "신한 i Indi"는 Windows COM 기반)를 확인해야 한다. macOS에서 쓸 수 없는 방식이면 제외하고 2.1로 대체한다.

### 2.3 KRX Open API [확인함 2026-09-25: 서비스 목록·이용 구조 + 8개 API 엔드포인트·필드(개발 명세서) / 확인 필요: 갱신 시각·한도·전송 방식]

포트: `MarketDataPort`(보조), `FundamentalsPort`의 산업군 보조. https://openapi.krx.co.kr — **인증키 확보됨(2026-09-25)**. 인증키는 요청 헤더 `AUTH_KEY`로 전달. **API마다 따로 이용 신청**(기간 1·3·6·12개월, 목적 선택)해야 하며, 상세 명세("개발 명세서" 다운로드, 출력값 표, 샘플 URL)는 로그인 + 인증키 발급 계정에서만 보인다. 데이터는 **2010-01-04부터, 일별(전일 확정치)**. 장중·실시간 없음.

**서비스 목록 31개 (2026-09-25 확인).** 지수 5: KRX 시리즈 · KOSPI 시리즈 · KOSDAQ 시리즈 일별시세정보, 채권지수 · 파생상품지수 시세정보. 주식 8: 유가증권 · 코스닥 · 코넥스 일별매매정보, 유가증권 · 코스닥 · 코넥스 종목기본정보, 신주인수권증권 · 신주인수권증서 일별매매정보. 증권상품 3: ETF · ETN · ELW 일별매매정보. 채권 3: 국채전문유통 · 일반채권 · 소액채권시장 일별매매정보. 파생 6: 선물·옵션 일별매매정보(주식선물·옵션은 유가/코스닥 구분). 일반상품 3: 석유 · 금 · 배출권 시장. ESG 3: ESG 증권상품 · 사회책임투자채권 · ESG 지수.

| 우리 용도 | 신청할 API | 쓰는 곳 |
|---|---|---|
| 전 종목 일별 OHLCV·시총·상장주식수 | 유가증권 일별매매정보, 코스닥 일별매매정보 | F5 1단 정량 스크리닝(전 종목), F10 백테스트 장기 데이터(토스 캔들은 2022-11-23 이후만) |
| 종목 마스터 보완(업종·상장주식수·액면가·상장일) | 유가증권 종목기본정보, 코스닥 종목기본정보 | F4 마스터 검증, **F15 산업군 탭의 KRX 업종**(토스 종목 정보에 업종이 없을 때 1차 출처) |
| ETF 일별(종가·NAV·순자산·추적지수명·괴리율) | ETF 일별매매정보 | **F15 ETF 탭 헤더**(추적지수·순자산), 괴리율 표시 [제안] |
| 업종·시장 지수 일별 | KRX · KOSPI · KOSDAQ 시리즈 일별시세정보 | F15 산업군 "업종 지수 대비 수익률", F9 시장 리포트 |
| 거시·참고 | 채권지수, 파생상품지수 | F9 (우선순위 낮음) |

- **ETF 구성종목(PDF)은 이 API에 없다.** ETF 일별매매정보는 시세·NAV·순자산까지다. F15 구성종목 탭의 국내 출처는 별도로 정한다(운용사 CSV, KRX 정보데이터시스템 화면은 로그인·약관 제약) [결정 필요].
- **규격 확인함(2026-09-25, 개발 명세서 8종)**: `https://data-dbg.krx.co.kr/svc/apis/{그룹}/{서비스}` — `sto/stk_bydd_trd`·`sto/ksq_bydd_trd`(일별매매), `sto/stk_isu_base_info`·`sto/ksq_isu_base_info`(종목기본정보: ISIN·단축코드·영문명·상장일·증권구분·주식종류·액면가·상장주식수, **업종 없음**), `etp/etf_bydd_trd`(ETF: NAV·순자산총액·기초지수명·기초지수 종가/대비/등락률), `idx/kospi_dd_trd`·`idx/kosdaq_dd_trd`·`idx/krx_dd_trd`(지수: 계열구분·지수명·OHLC·거래량·거래대금·상장시총). 요청은 `{"basDd":"YYYYMMDD"}` 하나, 값은 전부 문자열이고 결측은 `"-"`. 필드 전체·배치·매핑·오류 처리는 [`docs/KRX_DESIGN.md`](KRX_DESIGN.md). 갱신 시각·한도·전송 방식(JSON POST/GET) [확인 필요]. 명세서 원본은 `docs/external/krx/`(저장소 밖)
- 어댑터: 하루 1회 장 마감 후 배치(전 종목 2회 + 지수 3회 + ETF 1회 + 기본정보 주 1회). 호출이 적어 한도 위험은 낮다. 응답의 숫자는 문자열(쉼표 포함 가능)이므로 `BigDecimal` 파싱을 학습 테스트로 고정

### 2.4 공공데이터포털 — 금융위원회 주식시세·상장종목 정보 [확인 필요: 갱신 지연]

https://www.data.go.kr (주식시세정보 15094808, KRX상장종목정보 15094775). 무료, 장기 일별 시세. 갱신이 하루 이상 늦을 수 있어 백테스트·보조용.

### 2.5 한국은행 ECOS / 미국 FRED — 거시 지표

포트: `MacroIndicatorPort`. ECOS(https://ecos.bok.or.kr/api, 기준금리·환율·물가·통화량) / FRED(https://fred.stlouisfed.org/docs/api, 미국 금리·CPI·고용·장단기 금리차). 둘 다 무료, 키 발급. 용도: F9 리포트, 매크로 분석가 페르소나의 근거 자료.

### 2.6 SEC EDGAR — 미국 공시

포트: `DisclosurePort`(US 구현체). https://www.sec.gov/search-filings/edgar-application-programming-interfaces — 무료, 키 없음. `User-Agent`에 연락처 명시 필수, 초당 요청 제한 준수. 제출 서류 목록, XBRL 재무 데이터(company facts). 해외 자동화의 DART 대응.

미국 종목의 공시는 **전부 여기서** 온다(2026-09-24 정리, F15·공시 감시). **어댑터 규격(요청 규칙·JSON 형식·재무 추출 규칙·서식 분류·저장)은 [`docs/EDGAR_DESIGN.md`](EDGAR_DESIGN.md)** — submissions·companyfacts·frames는 실측했고, `www.sec.gov` 계열은 연락처 없는 UA가 403이라 재확인 대상.
- 종목 → CIK: `https://www.sec.gov/files/company_tickers.json`(티커·CIK·회사명, 일 1회 갱신). ADR을 포함한 미국 상장사는 CIK가 있다
- 종목별 공시 목록: `https://data.sec.gov/submissions/CIK{10자리}.json` — 서식(`form`), 제출일, 보고 기간, 접수번호, 주 문서. 최근 1,000건 + 이전 파일 링크. 관심·보유 종목의 CIK를 1~2분 간격으로 돌려 새 접수번호를 감지한다(DART 감시와 같은 방식, 초당 10회 한도 안에서 30종목이면 넉넉). 전체 시장의 최신 제출은 EDGAR 최신 제출 Atom 피드(`browse-edgar?action=getcurrent&type=8-K&output=atom`)로 보조 [확인 필요: 갱신 지연]
- 원문: `https://www.sec.gov/Archives/edgar/data/{CIK}/{접수번호}/` 아래 HTML·XBRL. 10-K/10-Q는 Item 단위(Business, Risk Factors, MD&A)로 RAG 색인([`docs/RAG_DESIGN.md`](RAG_DESIGN.md) 4.3)
- 재무: company facts `https://data.sec.gov/api/xbrl/companyfacts/CIK{10자리}.json`(us-gaap 태그별 분기·연간 값) → F15 재무제표 탭
- 서식과 우리 용도: **8-K**(주요 사건: 실적 발표 Item 2.02, 임원 변경, 인수합병, 유상증자, 상장폐지 통지 → 가드레일 이벤트·토론 자료), **10-K/10-Q**(연간·분기 보고서 → 재무·RAG), **20-F/6-K**(외국 기업·ADR의 연간·수시 보고), **Form 4**(내부자 매매 → 지분 신호), **13D/13G**(5% 이상 보유), **S-1/424B**(신규 상장·증권 발행), **DEF 14A**(주주총회·보수). 배당 선언은 8-K 또는 보도자료라 EDGAR만으로 기준일·지급일이 항상 잡히지는 않는다 [확인 필요]
- 시각: EDGAR는 접수 시각(`acceptanceDateTime`, 미국 동부)을 주므로 DART와 달리 장중·장후 구분이 된다. 그래도 `firstSeenAt`을 함께 저장한다(기준 시점 원칙)
- 한계: SEC에 등록된 발행사만. 거래소 공지(상장폐지 심사·거래정지)는 EDGAR가 아니라 거래소·토스 종목 경고 API에서 온다. 애널리스트 자료·컨센서스는 공시가 아니므로 별도 소스

### 2.7 뉴스

포트: `NewsPort`.

- **네이버 검색 API(뉴스·블로그·카페)** [확정 2026-09-23, 사용자 결정]: RSS에 더하는 **추가 소스**로, 이미 발급된 키로 사용한다. 개인 단독 사용 프로그램이라는 판단. 제목·요약(`description`)·링크·`pubDate`를 받아 국내 종목 뉴스 목록과 토론 근거로 쓴다. **약관 위험은 기록해 둔다**: 2026-09-07 시행 약관은 검색 결과의 AI 입력·학습·평가 이용, 저장·캐싱, 제3자 제공을 금지한다. 따라서 (a) 네이버 어댑터는 `NewsPort` 구현체 중 하나일 뿐이고 RSS 어댑터를 항상 함께 두어 **키가 막히거나 약관 판단이 바뀌면 설정만으로 뺄 수 있게** 한다, (b) 네이버 출처 문서는 RAG 코퍼스에 `source=naver` 태그를 달아 **한 번에 삭제·재색인 가능**하게 한다, (c) 운영이 개발자센터 → NAVER API HUB로 이관 중이므로 **기존 키 유예 종료(2027-06-30) 전에 HUB 이관과 유료화 여부를 재확인**한다(신규 신청은 2026-07-31 종료). 블로그·카페 검색은 국내 종목의 개인 투자자 글을 잡는 보조 소스로 같은 조건에서 쓴다
- **국내 뉴스의 1차 소스는 RSS**: 언론사 공식 RSS(경제지·통신사)와 Google News RSS(한국어, 종목명 검색 피드). 제목·링크·발행 시각은 저장하고, 본문은 각 사이트 robots.txt·약관이 허용하는 범위에서만 수집·색인한다. 빅카인즈(한국언론진흥재단) 뉴스 API는 비상업 이용 조건 확인 후 후보 [확인 필요]
- 언론사·Google News RSS: 키 불필요. 발행 시각 확보가 쉽다
- 본문 수집은 각 사이트의 robots.txt와 약관을 따른다. RAG에는 요약과 링크·발행 시각을 저장하고, 본문 전문 저장은 허용된 소스에 한한다

### 2.8 국민연금 해외주식 보유 — SEC EDGAR 13F-HR + 공공데이터포털 [확인함 2026-09-24]

포트: `PensionHoldingsPort`(F13). 두 구현체. 종목 단위 실시간·일간·주간 공개는 어느 나라에도 없다.

**(a) SEC EDGAR 13F-HR — 분기, 미국 상장분** [확인함: CIK·제출 이력 / 확인 필요: 정보표 XML 파일명]

- National Pension Service, **CIK 0001608046**. 13F-HR 48건 + 13F-HR/A 2건(2026-09-24 기준). 제출은 분기 말 후 35~45일(최근 2026-08-13, 05-12, 02-10, 2025-11-04). N-PX(의결권)도 낸다
- 제출 목록: `https://data.sec.gov/submissions/CIK0001608046.json`(접수번호·제출일·보고기간·primary_doc). 정보표: 접수별 `Archives/edgar/data/1608046/{접수번호}/` 안의 XML(`infoTable`: nameOfIssuer, titleOfClass, **cusip**, value(2023년부터 달러 단위), sshPrnamt, sshPrnamtType, investmentDiscretion, votingAuthority). 파일명은 접수별 `index.json`으로 확인 [확인 필요]
- **무료, 회원가입·키 없음.** 조건: `User-Agent`에 이름과 연락처 이메일(없으면 차단됨 — 설계 확인 중 실제로 차단됨), **초당 10회 이하**, 대량 내려받기는 미국 야간 권장. 연락처 이메일은 admin 공유 설정 항목으로 둔다
- 한계: 미국 상장분(ADR 포함) 롱 포지션만, 지분율 없음(company facts의 발행주식수로 계산), 45일 지연, 정정(/A) 재수집 필요
- CUSIP → `Symbol`: 토스 미국 종목 마스터가 ISIN을 주면 ISIN 3~11자리가 CUSIP [확인 필요]. 없으면 OpenFIGI(무료, 키 선택)를 후보로

**(b) 공공데이터포털 — 국민연금공단 해외주식 투자정보 — 연간, 전 지역** [확인함: 엔드포인트·컬럼·갱신 주기 / 확인 필요: perPage 상한·일일 한도·헤더 접두]
 Swagger `https://infuser.odcloud.kr/oas/docs?namespace=3070517/v1`, 데이터셋 `https://www.data.go.kr/data/3070517/fileData.do`. 무료, 이용허락 제한 없음, 키 확보됨(공유 키, admin).

- 파일데이터 API라 **연도마다 엔드포인트(uddi)가 따로** 있다(2017년 말~2024년 말, 8개). `GET https://api.odcloud.kr/api/3070517/v1/uddi:…?page=&perPage=&returnType=JSON`. 인증은 `serviceKey` 쿼리 또는 `Authorization` 헤더. **Swagger 문서는 키 없이 읽히므로** 새 연도 데이터셋의 등장을 키 없이 감지할 수 있다
- 컬럼: 번호 · 종목명(영문 회사명, **티커·ISIN 없음**) · 평가액(억원) · 자산군 내 비중(%) · 지분율(%). **컬럼명과 타입이 연도마다 조금씩 다르다**(`평가액(억원)`/`평가액(억 원)`, `비중`/`비중(%)`/`비중(퍼센트)`, string/integer) → 어댑터가 정규화하고 연도별 학습 테스트로 고정
- 갱신 **연 1회**(연말 기준, 다음 해 가을~겨울 등록. 2024년 말 파일은 2025-12-10 등록, 차기 예정 2026-09-30). 10억원 미만 종목 제외, 2024년 말 3,259행
- 용도: F13 모달, 토론 개요 "국민연금 보유" 행, 토론 수치 스냅샷(제안). **자동 주문 트리거 아님.** 한 번 확인에 호출 5회 안팎(카탈로그 1 + 최신 데이터셋 페이지 3~4), 하루 1회 + 기동 시 + 수동
- 뺀 것: 기금운용본부 월간 운용현황(자산군 합계만, 종목별 없음, API 없음). 상세 [`docs/NPS_HOLDINGS_DESIGN.md`](NPS_HOLDINGS_DESIGN.md)

### 2.9 Massive (구 Polygon.io) — 미국 종목 참조·배당 캘린더·뉴스·공매도 [확인함 2026-09-25: 무료 등급 범위·엔드포인트·필드 / 확인 필요: 약관의 저장·표시 조건]

포트: `FundamentalsPort`(미국 배당·산업), `NewsPort`(미국 뉴스), `MarketDataPort`(보조). https://massive.com/docs (전체 색인 https://massive.com/docs/llms.txt). 기본 URL `https://api.massive.com`, 인증은 `?apiKey=` 또는 `Authorization: Bearer`. **키 확보됨(2026-09-25, 공유 키·admin)**.

- **무료 등급 "Stocks Basic"**: 미국 전 종목, **분당 5회**, **종가 기준(EOD, 실시간·지연 시세 없음)**, **과거 2년**. 유료(Starter $29 ~ Advanced $199, 개인용)는 무제한 호출·5~20년·15분 지연·실시간. 재무제표·비율은 별도 확장($29)이라 무료에 없다.
- 무료 등급에서 쓸 수 있는 것(엔드포인트 문서의 요금제 표시 기준):

| 엔드포인트 | 우리 용도 |
|---|---|
| `GET /v3/reference/dividends?ticker=` — `cash_amount, currency, declaration_date, ex_dividend_date, record_date, pay_date, frequency(0·1·2·4·12), distribution_type` | **F15 배당 탭(미국)의 유일한 결손이던 배당락일·기준일·지급일·주기** → 해결. EDGAR company facts는 선언액 검증용 |
| `GET /v3/reference/tickers/{ticker}` — `name, cik, composite_figi, sic_code, sic_description, market_cap, share_class_shares_outstanding, weighted_shares_outstanding, list_date, primary_exchange, description, homepage_url, branding` | 미국 종목 마스터 보완, **SIC 업종 → F15 산업군(미국)**, CIK 매핑(EDGAR 티커 파일이 막힐 때 대안), 상장일(신규 상장 필터), 발행주식수(13F 지분율 계산) |
| 관련 종목(`related companies`, 개요 페이지에 존재, 경로 [확인 필요]) | F15 관련 종목 탭(미국)·F6 동종 종목 후보 |
| `GET /v2/reference/news?ticker=&published_utc.gte=` — `title, description, article_url, publisher, published_utc, tickers, keywords, insights(종목별 sentiment·reasoning)` | **미국 `NewsPort` 구현체**(RSS 보완). `publishedAt` 확보, RAG `NEWS` 문서. 본문 전문은 없고 요약·링크뿐 |
| `GET /v2/aggs/ticker/{t}/range/1/day/{from}/{to}` (`adjusted`) | 미국 일봉 보조. 무료는 2년이라 토스(2021-11-30~)가 우선. 수정주가 `adjusted=true` 검증용 |
| splits, IPOs | 수정주가 조정, 신규 상장 필터(미국) |
| short interest(FINRA, 격주), short volume(일별), free float | **미국 수급 심리 카드**(F12 국내 수급 카드의 미국판, 토론 `[S]` 자료) |
| market holidays / status | `MarketCalendarPort` 미국 보조(1차는 토스 시장 달력) |
| SEC 13F holdings, 8-K 항목 파싱, 10-K 절 추출, Form 3/4 | EDGAR 직접 파싱의 편의 대체 후보 [보류]. 원천은 EDGAR |
| 기술 지표(SMA·EMA·MACD·RSI) | 안 씀(지표는 데몬이 계산) |

- **분당 5회**가 설계 제약이다: 관심·보유 30종목 기준 배당·개요 갱신은 하루 1회 배치(약 60회 = 12분), 뉴스는 시간당 종목별 1회(30회/시간). 어댑터에 분당 5회 토큰 버킷 + 429 백오프.
- 약관: 개인 요금제는 "Individual use only"(우리 조건과 맞음). 뉴스 항목의 저장·표시 조건(요약·링크만 저장할지)과 데이터 재배포 금지 범위 [확인 필요].

---

## 3. 있으면 좋음

| API | 용도 | 비고 |
|---|---|---|
| Finnhub / Alpha Vantage | 실적 일정, 애널리스트 추정치 | 미국 참조·배당·뉴스는 **Massive(2.9)로 확정**. 실적 캘린더·컨센서스만 남은 후보 |
| 실적·경제 캘린더 (Finnhub, FMP 등) | "판단을 뒤집을 이벤트" 자동 채우기, 실적 발표일 전후 자동 매수 제한 | 가드레일 입력으로도 유용 |
| KIND(한국거래소 공시) | 거래정지·관리종목 지정, 시장조치의 1차 출처 | 공식 API 없음 → 토스 `warnings` API로 충분한지 먼저 확인 |
| 네이버 데이터랩 / Google Trends | 종목·테마 검색량 → 개인 투자자 관심도 | 모멘텀 페르소나의 보조 신호. 데이터랩(Search Trend)도 API HUB로 이관 중 — 이관 시점·유료화 [확인 필요] |
| Reddit Data API | (피드 소스에서 제외) F12는 Reddit 검색 **링크아웃만** | [확정 2026-09-23] Responsible Builder Policy: 개인·비상업도 **사전 승인 필수**(예외 없음, 폼 신청), 승인 후 무료 한도 OAuth 클라이언트당 100회/분, 삭제된 글 동기 삭제 의무·48시간 내 저장 데이터 정리 권고·AI/ML 이용 금지. 원문 보관·RAG 투입이 불가하므로 승인받더라도 **표시 전용(원문 미저장, 캐시 48시간 이내)** 어댑터로만 [보류]. **Devvit 검토(2026-09-24, developers.reddit.com/docs)**: Devvit 앱은 Reddit 서버 안에서 서브레딧 설치 단위로 돌고 키 없이 Reddit API를 읽으며 스케줄러(cron)·Redis(5GB)가 있다. 그러나 (a) 데이터를 우리 데몬으로 보내려면 HTTP Fetch가 필요하고 이는 도메인 허용 목록 심사 + 앱 승인 + 자체 약관·개인정보처리방침이 요건인 "프리미엄 기능"이다(LLM 사용도 같음), (b) Devvit 규칙은 Data API 약관을 그대로 포함하고 삭제 의무가 더 엄격하다(PostDelete·CommentDelete 트리거로 외부 서비스에 보낸 데이터까지 삭제, 30일 안 자동 삭제 권고, 익명화해도 보관 위반), (c) 링크아웃·오프플랫폼 유도 금지. 결론: Devvit는 "Reddit 안에서 도는 앱"의 길이지 Reddit 데이터를 밖으로 가져오는 길이 아니며 제약이 줄지 않는다. **[확정 2026-09-25] StockTwits도 링크아웃만**(공개 API가 Cloudflare 챌린지로 Java에서 불가). 엔터프라이즈 개인 자격은 병행 문의 — 문안: "Hello, I am building a personal, non-commercial desktop application for my family (max 4 users) that shows StockTwits sentiment (bullish/bearish ratio) and recent messages for a handful of U.S. tickers. The public v2 endpoints are now behind a bot challenge, and your documented API requires Basic Auth credentials issued by enterprise-support. Is there an individual / non-commercial tier, and if so what are its rate limits, allowed uses (display only vs. caching), and pricing? Contact: (admin 이메일)". 자격이 생기면 `CommunityPort`에 Basic Auth 어댑터를 추가한다. Reddit 원문을 쓰려면 정식 경로는 Data API 이용 신청(비상업 무료)뿐이고, 승인 후에도 표시 전용이다. Reddit RSS(`/r/{sub}/.rss`)는 피드 리더용이라 프로그램 수집·저장은 Public Content Policy의 스크래핑 금지와 충돌하므로 쓰지 않는다 |
| StockTwits (공개 비인증 엔드포인트) | F12 미국 종목 심리 피드: 종목별 Bullish/Bearish 심리, 트렌딩 종목, 종목 메시지 스트림 | 포트 `CommunityPort`의 두 번째 구현체 [확정 2026-09-23]. 공식 문서 https://api-docs.stocktwits.com. **IP당 시간당 200회** → 커뮤니티 서랍을 연 종목만 조회, 응답 캐시 TTL 10분, 백그라운드 폴링 없음. 엔드포인트 규격은 공식 MCP 서버 소스(https://github.com/stocktwits/stocktwits-mcp, MIT, Node 18+, stdio)에서 확인한다. **제품에 MCP 서버를 넣지 않는다** — 어댑터가 같은 공개 엔드포인트를 직접 HTTP로 호출한다(Node 런타임·자식 프로세스 관리 회피, 기준 시점 `asOf` 원칙 유지). MCP 서버는 개발 중 데이터 품질 검증(사전 실험)에만 쓴다. 개인 비상업 용도이며 엔터프라이즈 API는 쓰지 않는다. **[확인 2026-09-24, 실측]** (1) `api-docs.stocktwits.com`이 지금 설명하는 것은 **엔터프라이즈 API**(`api-gw-prd.stocktwits.com/api-middleware/external/…`, Basic Auth, 자격은 enterprise-support@stocktwits.com 문의, 셀프 가입·가격·약관 없음)이고 엔드포인트는 뉴스 피드(JSON/RSS)·심리 상세·트렌딩·인기 메시지·스레드·최신 메시지 스트림. (2) 공개 v2 API(`api.stocktwits.com/api/2`)는 문서에서 사라졌지만 살아 있다: `streams/symbol/{SYM}.json`(limit≤30, 커서 `since`/`max`), `streams/trending.json`, `trending/symbols.json`, `streams/user/{user}.json`. `symbols/search.json`은 404. 메시지 필드 `id, body, created_at(UTC), user{…}, source, symbols[], entities{sentiment{basic: Bullish|Bearish}|null, media…}, likes{total}`. 한도 헤더 없음(README상 IP당 200회/시). 숫자 심볼은 내부 id로 해석돼 국내 종목은 못 씀. (3) **Cloudflare 봇 관리가 붙어 있다**: 같은 UA로도 curl·Node https·**Java 21 HttpClient는 `cf-mitigated: challenge` 403**, Node `fetch`(undici)만 통과. TLS 지문으로 거르는 것이라 **우리 Java 데몬은 그대로는 호출 불가**이고, 지문을 흉내 내는 우회는 하지 않는다(약관·안전 규칙). → F12 미국 피드 결정 재검토 필요([`PROJECT.md`](../PROJECT.md) 13장) |
| ETF 구성종목 — KRX 납부자산구성내역(PDF) · 운용사 CSV · SEC N-PORT | F15 구성종목 탭(원형 그래프·표·요약). 국내는 KRX 정보데이터시스템/Open API의 일별 PDF [확인 필요: Open API 제공 여부·갱신 시각], 대체로 운용사 사이트 CSV. 미국은 운용사 일일 공시 CSV(iShares·Vanguard 등)와 SEC N-PORT(월간, 분기 지연) | 포트 `EtfCompositionPort` [제안]. 자산종류 정규화(주식·채권·현금·파생·기타)는 어댑터 규칙 표. 상세 [`docs/STOCK_INFO_DESIGN.md`](STOCK_INFO_DESIGN.md) 3.6 |
| DeepL / Google Cloud Translation / Papago | F12 번역이 LLM으로 부족할 때의 전용 번역기 | `TranslationPort` [제안]. 무료 등급 월 50만 자 안팎. 기본은 LLM 번역 |
| 미러피시 | 심층 토론 | 선택적. [`docs/MIROFISH_EXPERIMENT_GUIDE.md`](MIROFISH_EXPERIMENT_GUIDE.md), 내부 API이므로 실험으로 규격 확인 |
| Ollama (로컬 LLM) | 비용 0의 요약·분류·임베딩, 급등 재료 확인 같은 저지연 작업 | Mac mini 32GB에서 8B~14B급 현실적 |
| 웹 검색 API (Tavily, Brave 등) | 토론 중 최신 정보 보강 | LLM 제공자의 내장 검색 도구로 대체 가능 |
| DDNS / Cloudflare API | 집 서버 주소 갱신, Tunnel 구성 | relay를 집에 둘 때 |
| Let's Encrypt (ACME) | relay TLS 인증서 | 리버스 프록시(Caddy)에 맡기면 코드 불필요 |
| Apple Push / Web Push | Slack 없이 브라우저·기기 알림 | Slack으로 충분하면 생략 |

---

## 4. 쓰지 않는 것

- **증권사 HTS/MTS 화면 자동화, 비공식·역공학 API**: 약관 위반과 계정 제한 위험. 토스 웹 전용 기능을 긁는 비공식 도구도 쓰지 않는다.
- **네이버 금융·증권사 리서치 페이지의 무단 대량 크롤링**: 공식 API와 RSS로 대체한다.
- **네이버 종목토론실·토스 커뮤니티의 프레임 삽입(iframe/webview) 또는 크롤링**: `X-Frame-Options`·약관에 걸리고, 헤더를 벗겨 띄우는 방식은 우리 창에 남의 스크립트를 들이는 것이라 하지 않는다. F12는 링크아웃만 한다.
- **타 증권사 API의 주문 기능**: D8.

---

## 5. 포트 정리

| 포트 | 구현체(어댑터) |
|---|---|
| `TradingPort` | Toss (유일) |
| `MarketDataPort` | Toss(주), KIS·KRX·공공데이터(보조) |
| `MarketCalendarPort` | Toss market-calendar (+ 뉴욕 시간대 계산 검증) |
| `RealtimeFeedPort` | Toss WebSocket |
| `IdentityPort`, `AssetPort` | 금융결제원 |
| `DisclosurePort` | DART(KR), SEC EDGAR(US) |
| `FundamentalsPort` | DART 재무정보·배당(KR), SEC EDGAR company facts(US 재무), **Massive(US 배당 캘린더·SIC 산업·관련 종목)**, KRX 지수(업종 지수), (KIS 재무비율 보조) |
| `NewsPort` | 네이버 검색(뉴스·블로그·카페), 언론사·Google News RSS, **Massive(미국)**, (빅카인즈) |
| `PensionHoldingsPort` | SEC EDGAR 13F-HR(분기), 공공데이터포털 국민연금 해외주식 투자정보(연간) [제안] |
| `MacroIndicatorPort` | ECOS, FRED, Toss market-indicators |
| `LlmPort`, `EmbeddingPort` | Spring AI 기반 다중 제공자, Ollama |
| `SimulationPort` | 미러피시 (선택) |
| `NotifierPort` | Slack (engine 직접 / relay 경유) |
| `MailPort` | SMTP (relay) |
| `SecretStorePort` | macOS Keychain |
| `Clock` | 시스템 시계 / 테스트용 고정 시계 |

모든 어댑터 공통 요건: 그룹별 rate limiter, 타임아웃, 재시도(지수 백오프+jitter, **주문은 재시도 전 반드시 조회로 확인**), 서킷 브레이커, 서드파티 예외의 도메인 예외 변환, 응답 원문 비로깅(비밀·개인정보), WireMock 기반 통합 테스트.

---

## 출처

- [토스증권 Open API 개요(overview.md)](https://openapi.tossinvest.com/openapi-docs/overview.md)
- [토스증권 Open API FAQ](https://openapi.tossinvest.com/openapi-docs/faq.md)
- [토스증권 개발자센터 llms.txt](https://developers.tossinvest.com/llms.txt)
- [OpenDART 소개](https://opendart.fss.or.kr/intro/main.do)
- [OpenDART 개발가이드 — 공시검색](https://opendart.fss.or.kr/guide/detail.do?apiGrpCd=DS001&apiId=2019001)
- [Spring AI Reference — DeepSeek Chat](https://docs.spring.io/spring-ai/reference/api/chat/deepseek-chat.html)
- [금융결제원 통합포털](https://openapi.kftc.or.kr/)
- [KIS Developers](https://apiportal.koreainvestment.com/intro)
- [신한 Open API](https://openapi.shinhan.com/)
- [KRX Open API](https://openapi.krx.co.kr/)
- [공공데이터포털 금융위원회 주식시세정보](https://www.data.go.kr/data/15094808/openapi.do)
- [Reddit Responsible Builder Policy](https://support.reddithelp.com/hc/en-us/articles/42728983564564-Responsible-Builder-Policy)
- [Reddit Developer Platform & Accessing Reddit Data](https://support.reddithelp.com/hc/en-us/articles/14945211791892-Developer-Platform-Accessing-Reddit-Data)
- [네이버 검색 API AI 활용 금지 보도(한국데이터경제신문, 2026-09)](https://www.dataeconomy.co.kr/news/articleView.html?idxno=42307)
- [네이버 검색 API → API HUB 이관 안내(와플보드)](https://waffleboard.io/blog/naver-search-api-hub-migration-guide)
- [StockTwits 공식 MCP 서버](https://github.com/stocktwits/stocktwits-mcp)
