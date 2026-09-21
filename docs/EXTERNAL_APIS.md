# 외부 API 카탈로그

작성: 2026-09-20
관련: `PROJECT.md` 8장(외부 연동 포트)

wave-stock이 사용하는 외부 API를 **필수 / 권장 / 있으면 좋음**으로 나누어 정의한다. 각 API는 `core.port`의 포트 인터페이스 뒤에 어댑터로 붙인다. 표기: **[확인함]** 공식 문서로 확인(2026-09-20 기준) / **[확인 필요]** 착수 전 문서·약관 확인이 필요.

API 규격은 바뀐다. 어댑터를 구현할 때는 이 문서가 아니라 **공식 문서를 기준**으로 하고, 이 문서와 다르면 이 문서를 고친다.

---

## 1. 필수

### 1.1 토스증권 Open API — 매매·시세·계좌 [확인함]

유일한 주문 경로이자 1차 시세 소스. 포트: `TradingPort`, `MarketDataPort`, `MarketCalendarPort`.

- 문서: https://developers.tossinvest.com/docs · AI용 색인 https://developers.tossinvest.com/llms.txt
- 정식 규격: `https://openapi.tossinvest.com/openapi-docs/latest/openapi.json`(REST), `…/asyncapi.json`(WebSocket) → **어댑터의 DTO는 이 규격에서 생성하거나 대조한다**
- 서버: `https://openapi.tossinvest.com` / `wss://openapi-ws.tossinvest.com/ws/v1`
- 인증: OAuth 2.0 Client Credentials(`POST /oauth2/token`). 계좌·주문 API는 `X-Tossinvest-Account: {accountSeq}` 헤더 추가

| 그룹 | 엔드포인트 | wave-stock에서의 용도 |
|---|---|---|
| 시세 | `GET /api/v1/prices`, `/orderbook`, `/trades` | F1 주문 화면, 급등 탐지, 자동 매도 판단 |
| 차트 | `GET /api/v1/candles` (1분봉·일봉, 1회 최대 200개) | F3 차트, 지표 계산, 스크리닝 |
| 종목 | `GET /api/v1/stocks`, `/stocks/{symbol}/warnings` | F4 종목 마스터, **F7 제외 필터(투자경고·위험·VI·정리매매 등)** |
| 시장 정보 | `GET /api/v1/exchange-rate`, `/market-calendar/*` | 해외 노출액 원화 환산, **KR/US 세션·휴장일 계산** |
| 랭킹 | `GET /api/v1/rankings` (거래대금·거래량·등락률) | **F7 급등 종목 탐지의 1차 입력**, F5 스크리닝 |
| 시장 지표 | `GET /api/v1/market-indicators/*` (지수, 국채) | F9 시장 리포트 |
| 수급 | STOCK_TRADING_TREND 그룹 | F5 스크리닝, 토론 자료 |
| 계좌·자산 | `GET /api/v1/accounts`, `/holdings` | 잔고·보유 조회, F2 손익 |
| 주문 | `POST /api/v1/orders`, `…/{orderId}/modify`, `…/{orderId}/cancel` | F1, F7, F8 |
| 주문 조회 | `GET /api/v1/orders`, `/orders/{orderId}` | F2, **자동 주문 직전 중복 확인** |
| 주문 정보 | `GET /api/v1/buying-power`, `/sellable-quantity`, `/commissions` | **예수금 하한 검사**, 90% 한도 계산, 순손익 계산 |
| 조건주문 | `POST /api/v1/conditional-orders` (SINGLE·OCO·OTO), 수정·삭제·조회 | 아래 "설계 영향 4" 참조 |
| 실시간 | WebSocket `trade:{kr\|us}`, `orderbook:{kr\|us}`, `personal:order` | 급등 탐지, 체결 알림 |

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

| 그룹 | 주요 API | wave-stock에서의 용도 |
|---|---|---|
| DS001 공시정보 | 공시검색 `list`, 기업개황 `company`, 공시서류 원본 `document`(zip/XML), 고유번호 `corpCode`(zip/XML) | **신규 공시 감시**, 종목코드↔`corp_code` 매핑, RAG 원문 수집 |
| DS002 정기보고서 주요정보 | 배당, 증자·감자 현황, 자기주식 취득·처분, 최대주주·변동, 소액주주, 임원·직원 현황, 임원 보수, 타법인 출자, 주식 총수, 감사의견 등 약 30종 | F5 스크리닝(배당·지배구조), 토론 시드 자료 |
| DS003 정기보고서 재무정보 | 단일회사 주요계정 `fnlttSinglAcnt`, 다중회사 주요계정 `fnlttMultiAcnt`, 단일회사 전체 재무제표 `fnlttSinglAcntAll`, 주요 재무지표(단일·다중), XBRL 원본, 택소노미 | **F5 1단 정량 스크리닝의 재무·밸류에이션 입력**. 다중회사 API로 호출 수 절약 |
| DS004 지분공시 | 대량보유 상황보고 `majorstock`, 임원·주요주주 소유보고 `elestock` | 내부자·대주주 매매 신호(가치투자자·역발상 페르소나 자료) |
| DS005 주요사항보고서 | 유상·무상증자, 감자, 전환사채·신주인수권부사채 발행, 자기주식 취득·처분 결정, 합병·분할, 영업정지, 회생절차 개시, 부도 발생, 소송 등 | **자동화 가드레일의 이벤트 입력**(아래) |
| DS006 증권신고서 | 지분증권, 채무증권, 합병·분할 등 | 신규 상장·대규모 발행 파악(우선순위 낮음) |

#### wave-stock에서의 사용 방식

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

포트: `LlmPort`. 설계 상세는 `docs/LLM_ROUTING.md`.

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

### 2.3 KRX 정보데이터시스템 Open API [확인 필요: 승인 절차·한도]

포트: `MarketDataPort`. https://openapi.krx.co.kr — 회원가입 후 서비스별 이용 신청.

- 일별 매매정보(전 종목 시세), 종목 기본정보, 지수 일별 시세
- 용도: **전 종목 일별 데이터의 공식 소스**. F5 1단 스크리닝과 F10 백테스트용 장기 데이터(토스 캔들의 과거 한계를 보완)

### 2.4 공공데이터포털 — 금융위원회 주식시세·상장종목 정보 [확인 필요: 갱신 지연]

https://www.data.go.kr (주식시세정보 15094808, KRX상장종목정보 15094775). 무료, 장기 일별 시세. 갱신이 하루 이상 늦을 수 있어 백테스트·보조용.

### 2.5 한국은행 ECOS / 미국 FRED — 거시 지표

포트: `MacroIndicatorPort`. ECOS(https://ecos.bok.or.kr/api, 기준금리·환율·물가·통화량) / FRED(https://fred.stlouisfed.org/docs/api, 미국 금리·CPI·고용·장단기 금리차). 둘 다 무료, 키 발급. 용도: F9 리포트, 매크로 분석가 페르소나의 근거 자료.

### 2.6 SEC EDGAR — 미국 공시

포트: `DisclosurePort`(US 구현체). https://www.sec.gov/search-filings/edgar-application-programming-interfaces — 무료, 키 없음. `User-Agent`에 연락처 명시 필수, 초당 요청 제한 준수. 제출 서류 목록, XBRL 재무 데이터(company facts). 해외 자동화의 DART 대응.

### 2.7 뉴스

포트: `NewsPort`.

- 네이버 검색 API(뉴스): https://developers.naver.com — 무료, 일일 한도. 제목·요약·링크만 제공(본문 없음). 종목명 키워드 수집에 적합
- 언론사·Google News RSS: 키 불필요. 발행 시각 확보가 쉽다
- 본문 수집은 각 사이트의 robots.txt와 약관을 따른다. RAG에는 요약과 링크·발행 시각을 저장하고, 본문 전문 저장은 허용된 소스에 한한다

---

## 3. 있으면 좋음

| API | 용도 | 비고 |
|---|---|---|
| Finnhub / Polygon / Alpha Vantage | 미국 종목 뉴스, 실적 일정, 애널리스트 추정치, 기업 기본정보 | 무료 등급에 분당 제한. 해외 자동화를 본격화할 때 도입 |
| 실적·경제 캘린더 (Finnhub, FMP 등) | "판단을 뒤집을 이벤트" 자동 채우기, 실적 발표일 전후 자동 매수 제한 | 가드레일 입력으로도 유용 |
| KIND(한국거래소 공시) | 거래정지·관리종목 지정, 시장조치의 1차 출처 | 공식 API 없음 → 토스 `warnings` API로 충분한지 먼저 확인 |
| 네이버 데이터랩 / Google Trends | 종목·테마 검색량 → 개인 투자자 관심도 | 모멘텀 페르소나의 보조 신호 |
| 미러피시 | 심층 토론 | 선택적. `docs/MIROFISH_EXPERIMENT_GUIDE.md`, 내부 API이므로 실험으로 규격 확인 |
| Ollama (로컬 LLM) | 비용 0의 요약·분류·임베딩, 급등 재료 확인 같은 저지연 작업 | Mac mini 32GB에서 8B~14B급 현실적 |
| 웹 검색 API (Tavily, Brave 등) | 토론 중 최신 정보 보강 | LLM 제공자의 내장 검색 도구로 대체 가능 |
| DDNS / Cloudflare API | 집 서버 주소 갱신, Tunnel 구성 | relay를 집에 둘 때 |
| Let's Encrypt (ACME) | relay TLS 인증서 | 리버스 프록시(Caddy)에 맡기면 코드 불필요 |
| Apple Push / Web Push | Slack 없이 브라우저·기기 알림 | Slack으로 충분하면 생략 |

---

## 4. 쓰지 않는 것

- **증권사 HTS/MTS 화면 자동화, 비공식·역공학 API**: 약관 위반과 계정 제한 위험. 토스 웹 전용 기능을 긁는 비공식 도구도 쓰지 않는다.
- **네이버 금융·증권사 리서치 페이지의 무단 대량 크롤링**: 공식 API와 RSS로 대체한다.
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
| `FundamentalsPort` | DART 재무정보(KR), SEC EDGAR company facts(US), (KIS 재무비율 보조) |
| `NewsPort` | 네이버 검색, RSS, (Finnhub) |
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
