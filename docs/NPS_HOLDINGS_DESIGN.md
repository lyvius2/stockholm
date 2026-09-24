# 국민연금 해외투자 현황 (F13) — 모달과 갱신 푸시 설계

작성: 2026-09-24 / 상태: **[확정 2026-09-24]** — 버튼 이름·위치, 캐시, 매핑, 알림 기본값, 토론 투입은 사용자 결정으로 확정(11장). 외부 사실은 [확인함]/[확인 필요]로 표기 / 관련: `PROJECT.md` 9장 F13·11.3, `docs/EXTERNAL_APIS.md` 2.8, `docs/CORE_DOMAIN.md` 10장, `docs/DEBATE_DESIGN.md` 3.2, 화면 설계 아티팩트 v16 "국민연금 해외투자 현황 모달"

## 1. 결론 요약

- 국민연금의 해외주식 종목별 보유를 **두 출처**로 데몬이 내려받아 저장하고, 상단 바 **🏛️ 연기금종목** 버튼(☰ 관심종목과 ⚡ 실시간 급등락 사이, ⌘N)으로 여는 **정보 모달** 하나에서 탭으로 나눠 보여 준다. 버튼 이름은 앞으로 국내 연기금 수급 등으로 넓힐 여지를 둔 것이고, 모달 제목은 자료의 실제 범위대로 "국민연금 해외주식 투자 현황"이다.
  - **SEC EDGAR 13F-HR** — 분기, 미국 상장분(ADR 포함), 주식 수·평가액(USD), 분기 말 후 35~45일 제출. **가장 촘촘한 공식 공개.**
  - **공공데이터포털 국민연금공단 해외주식 투자정보** — 연 1회, 전 지역, 연말 기준 평가액·비중·지분율. 미국 밖 보유분(유럽·일본·홍콩·신흥국)을 보는 유일한 자료.
- **종목 단위 실시간·일간·주간 공개는 어느 나라에도 없다.** 연기금은 매매를 실시간으로 공개할 의무가 없고, 5% 이상 보유 공시(미국 13G 등)는 국민연금의 미국 종목 지분율(0.2~0.4%대)에 걸리지 않는다. 그래서 "모니터링"은 **하루 한 번 + 앱 기동 시 + 수동**의 "새 보고서가 올라왔는가" 확인이다.
- 변화는 13F는 **접수번호**, 연간은 **(데이터셋 id, 내용 해시)** 로 식별해 같은 변화를 두 번 알리지 않는다. 설치 단위로 한 번 감지하고 **사용자별로 전달·확인 처리**한다.
- 푸시 경로는 네 가지: 앱 안 토스트 + 버튼 배지 + 알림 센터(로컬 WebSocket), 창이 닫혀 있을 때 macOS 알림(Electron), 사용자별 옵션 Slack DM(`NotifierPort`), 원격 모드의 relay 중계(7단계).
- 이 데이터는 **정보 표시와 토론 근거**일 뿐이다. 어떤 자동 주문의 트리거도 아니고 가드레일 입력도 아니다.
- 이용 허가: 둘 다 무료 공개 자료이고 별도 계약이 없다. 공공데이터포털은 이미 발급된 서비스 키(공유 키, admin), EDGAR는 키 없이 **User-Agent 연락처 + 초당 10회** 조건만 지킨다(12장).

## 2. 데이터 소스 확인 결과 [확인함 2026-09-24]

### 2.1 SEC EDGAR 13F-HR — 분기, 미국 상장분

- 제출자: **National Pension Service, CIK 0001608046**(주소 전주시 기지로 180). 13F-HR 48건, 13F-HR/A(정정) 2건, N-PX(의결권 행사) 3건.
- 제출 시점: 분기 말 후 35~45일. 최근 제출 2026-08-13(2Q), 05-12(1Q), 02-10(4Q25), 2025-11-04(3Q25), 08-08, 05-13, 02-05, 2024-10-30.
- 제출 목록: `GET https://data.sec.gov/submissions/CIK0001608046.json` → `filings.recent`의 `form`, `filingDate`, `reportDate`, `accessionNumber`, `primaryDocument`(예: `xslForm13F_X02/primary_doc.xml`). 이 JSON 하나로 새 보고서와 정정을 감지한다.
- 정보표: 접수별 디렉터리 `https://www.sec.gov/Archives/edgar/data/1608046/{접수번호(하이픈 제거)}/` 안의 XML. 각 `infoTable`에 `nameOfIssuer`, `titleOfClass`, **`cusip`**, `value`(**2023년부터 달러 단위**, 그 전은 천 달러), `shrsOrPrnAmt/sshPrnamt`(주식 수), `sshPrnamtType`(SH/PRN), `investmentDiscretion`, `votingAuthority`. 파일명(`infotable.xml` 관행)은 접수별 `index.json`으로 확인한다 [확인 필요].
- **접근 조건**: 회원가입·키 없음. `User-Agent`에 **이름과 연락처 이메일**을 넣어야 하고(없으면 "Request Rate Threshold Exceeded"로 차단됨 — 설계 확인 중 실제로 차단됨), **초당 10회 이하**, 대량 내려받기는 미국 야간 권장. 연락처 이메일은 admin이 넣는 공유 설정 항목으로 둔다(비밀값이 아니므로 Keychain이 아니라 설정 표).
- 한계: 미국 상장 주식·ADR의 **롱 포지션만**. 공매도·옵션·미국 밖 상장 종목은 없다. 지분율이 없어 발행주식수(EDGAR company facts `dei:EntityCommonStockSharesOutstanding`)로 계산한다. 45일 지연. 정정(/A) 시 해당 분기 스냅샷 교체.
- 종목 식별자가 **CUSIP**이라 연간 자료보다 매핑이 쉽다(8장).

### 2.2 공공데이터포털 — 국민연금공단 해외주식 투자정보 — 연간, 전 지역

Swagger: `https://infuser.odcloud.kr/oas/docs?namespace=3070517/v1` (Swagger 2.0, host `api.odcloud.kr`, basePath `/api`). 데이터셋 페이지: `https://www.data.go.kr/data/3070517/fileData.do`.

**성격.** "파일데이터"를 API로 노출한 것이라 연도마다 **엔드포인트(uddi)가 따로** 있다. 2026-09-24 기준 8개.

| 경로 (`GET /3070517/v1/…`) | 제목 | 기준 시점 해석 |
|---|---|---|
| `uddi:b9470910-…_201812140934` | 종목별 투자 현황(2017년 말 기준) | 2017-12-31 |
| `uddi:414d47dd-…` | 종목별 투자 현황(2018년 말 기준)_20190926 | 2018-12-31 |
| `uddi:df8671d8-…` | 해외주식투자정보_20201006 | 2019-12-31로 추정 [확인 필요] |
| `uddi:5565cf0b-…` | 해외주식 투자정보_20201231 | 2020-12-31 |
| `uddi:2e06ff94-…` | 해외주식 투자정보_20211231 | 2021-12-31 |
| `uddi:a5b517c1-…` | 해외주식 투자정보_20221231 | 2022-12-31 |
| `uddi:cbf4387f-…` | 해외주식 투자정보_20231231 | 2023-12-31 |
| `uddi:dce2f590-…` | 해외주식 투자정보_20241231 | 2024-12-31 (등록·수정 2025-12-10) |

- **갱신 주기 연간, 차기 갱신 예정 2026-09-30**, 이용허락 제한 없음(무료). 원화 10억원 미만 종목은 제외. 2024년 말 파일은 3,259행.
- 파라미터: `page`(기본 1), `perPage`(기본 10, **상한은 문서에 없음** [확인 필요]), `returnType`(JSON/XML). 필터(`cond[...]`)는 이 네임스페이스 문서에 없다.
- 인증: `serviceKey` 쿼리 또는 `Authorization` 헤더(`Infuser ` 접두 여부 [확인 필요]). **Swagger 문서 자체는 키 없이 읽힌다** — 새 연도 엔드포인트가 생겼는지는 키 없이도 알 수 있다.
- 응답: `{ page, perPage, totalCount, currentCount, matchCount, data: [...] }`.
- **컬럼명이 연도마다 다르다.** 어댑터가 정규화해야 한다.

| 연도 | 번호 | 종목명 | 평가액 | 비중 | 지분율 |
|---|---|---|---|---|---|
| 2017 | string | string | `평가액(억원)` string | `자산군 내 비중` string | `지분율(%)` string |
| 2018 | integer | string | `평가액(억원)` string | `자산군 내 비중` string | `지분율(%)` string |
| 2019·2020 | string / integer | string | `평가액(억원)` string | `자산군 내 비중(%)` string | `지분율(%)` string |
| 2021~2024 | integer | string | `평가액(억 원)` **integer** | `자산군 내 비중(퍼센트)` string | `지분율(퍼센트)` string |

- 종목 식별자는 **영문 회사명뿐**이다(티커·ISIN 없음).
- 한도: 공공데이터포털의 일반적인 일일 트래픽 한도(개발 계정 1,000~10,000) 중 이 API의 값은 [확인 필요]. 한 번 확인에 필요한 호출은 5회 안팎이라 어느 값이든 여유가 있다.

### 2.3 살펴봤지만 뺀 것

| 출처 | 주기 | 뺀 이유 |
|---|---|---|
| 기금운용본부 월간 운용현황(fund.nps.or.kr) | 월간, 약 2개월 지연 | 자산군 합계(해외주식 평가액·비중·수익률)만 있고 종목별이 없다. API 없음, 페이지 수집은 약관 확인이 먼저. 필요해지면 "해외주식 총액 추이" 카드로 [보류] |
| 미국 13G/13D(5% 보유 공시) | 사건 발생 시 | 국민연금의 미국 종목 지분율은 0.2~0.4%대라 사실상 발생하지 않는다 |
| 유럽·일본·홍콩의 대량보유 공시(3~5%) | 사건 발생 시 | 위와 같은 이유로 걸리지 않는다 |
| KRX 투자자별 매매동향 "연기금등" | **일간**, 종목별 | **국내 주식**만이다. 국민연금 단독이 아니라 연기금 합산. F12 수급 카드에 얹을 후보이지 F13 범위는 아니다 [제안] |
| 13F 재가공 사이트(WhaleWisdom, Fintel 등) | 분기 | 같은 13F를 재가공한 것. 원본(EDGAR)을 직접 읽는다 |

## 3. 변화 감지

| 출처 | 신호 | 방법 | 변화 종류 |
|---|---|---|---|
| 13F | 새 접수번호 | 제출 목록 JSON에서 `form ∈ {13F-HR, 13F-HR/A}`이고 저장에 없는 `accessionNumber` | `NEW_FILING`(13F-HR), `AMENDED`(13F-HR/A — 같은 `reportDate`의 스냅샷 교체) |
| 연간 | 새 uddi | Swagger `paths`의 uddi 집합과 저장된 집합 비교(키 불필요) | `NEW_DATASET` |
| 연간 | 내용 해시 | 최신 데이터셋만 전 페이지 내려받아 정규화·정렬한 행의 SHA-256 | `CONTENT_CHANGED` |

- **diff(의미 있는 차이)**: 알림 문구와 모달의 변화 탭을 위해 직전 스냅샷과 비교한다. 13F는 CUSIP 기준으로 신규 편입·전량 매도·주식 수 변동률(상위 N)·총 평가액 변화·사용자 관심·보유 종목 중 변동 건수. 연간은 정규화한 회사명 기준으로 신규·제외·비중 변동(pp)·총 평가액 변화.
- **멱등성.** 알림 키 = 13F `accessionNumber` / 연간 `(datasetId, contentHash)`. "앱 시작 시 push"는 새로 감지하는 것이 아니라 **아직 확인(ack)하지 않은 알림을 내려 주는 것**이다.
- **기준 시점(`asOf`) 원칙.** 토론 자료로 쓸 때는 보고 기준일이 아니라 **우리가 처음 본 시각(`firstSeenAt`)** 을 `asOf` 필터에 쓴다. 2026-06-30 기준 13F를 08-13에 공개했으므로 08-01 기준 토론에서는 보이면 안 된다.
- 과거 분기·연도 데이터셋은 첫 실행에만 내려받고 이후에는 다시 보지 않는다(비교 기준선일 뿐). 단 13F 정정은 예외로 해당 분기를 교체한다.

## 4. 확인 주기와 실행 조건

| 시점 | 조건 | 비고 |
|---|---|---|
| 데몬 기동 | 마지막 성공 확인이 24시간 이전이면 즉시 | 기동 직후 토스 연결·RAG 워밍업과 겹치지 않게 지연 30초 |
| 매일 06:30 KST | 고정 | 미국 동부 17:30(EDGAR 당일 접수분 반영 후), 장 전, F9 일간 리포트 생성 전 |
| 수동 | 모달의 "지금 확인" | 결과를 모달 제목 바에 즉시 표시. 연타 방지 최소 간격 1분 |
| 실패 재시도 | 1시간 뒤, 최대 3회 | 그 뒤는 다음 날 |

**캐시 — cache-aside, TTL 24시간 [확정].** 화면(모달·개요 행·토론 자료)은 출처를 직접 부르지 않고 항상 데몬의 SQLite 스냅샷을 읽는다. 데몬은 마지막 성공 확인이 24시간을 넘겼을 때만 출처를 다시 부른다(기동 시 검사와 매일 06:30 검사가 그 시점이다). "지금 확인"만 TTL을 무시하되 1분 간격으로 제한한다. 연간 API는 `perPage` 상한이 문서에 없으므로 **500씩 요청해 `currentCount`가 요청보다 적거나 `page × perPage ≥ totalCount`가 될 때까지** 넘긴다. 서버가 상한을 낮춰 잘라 주더라도 같은 규칙으로 끝까지 받는다(학습 테스트로 실제 상한을 기록한다).

- 한 번 확인의 호출: EDGAR 제출 목록 1회(+ 새 13F가 있으면 `index.json` 1회 + 정보표 1회), Swagger 카탈로그 1회(+ 새 데이터셋이 있으면 3~4회). 평소에는 2회.
- lease와 무관하다(주문이 아니다). 사용자와도 무관하다(공용 데이터). **한 설치에서 한 번** 확인하고 결과를 사용자들에게 나눠 준다.
- relay 동기화 대상이 아니다. 각 설치가 직접 조회한다. 확인(ack) 상태도 동기화하지 않는다 [제안].
- 시간은 `Clock` 주입. 스케줄은 `engine`의 스케줄러가 `Asia/Seoul`로 계산한다.

## 5. 푸시 경로

```
PensionHoldingsWatcher(engine) ──감지──▶ PensionHoldingsChanged(Spring 이벤트, 설치 단위)
   │
   ├─▶ NotificationInbox: 사용자마다 항목 생성 (kind=DATA_UPDATED, key=접수번호 또는 (datasetId, hash), 미확인)
   │      └─▶ 로컬 WebSocket EVENT ─▶ Electron
   │             ├─ 창 열림: 토스트 + 상단 바 배지 + 알림 센터
   │             └─ 창 닫힘(트레이): macOS 알림 → 클릭 시 창 열고 모달
   ├─▶ NotifierPort(Slack): "데이터 갱신 알림"을 켠 사용자에게만 DM (요약, 야간 방해 금지 적용, 긴급 아님)
   └─▶ (원격 모드) relay EVENT 중계 → 같은 토스트·배지
```

- **앱 기동 시**: 화면이 데몬에 붙으면 `GET /notifications?unacked=true`로 미확인 항목을 받아 토스트·배지를 그린다. 감지 자체는 4장의 규칙대로 데몬이 한다.
- **확인 처리**: 모달을 열면(또는 알림 센터에서 읽으면) 그 사용자의 항목을 ack. 배지가 사라진다. 다른 구성원의 배지는 그대로다.
- **기본값 [확정]**: 앱 안 알림 켬, Slack 끔. 사용자별 설정에서 바꾼다. 13F 정정 알림도 같은 설정을 따른다.
- **내용**: 공용 데이터라 Slack `NOTIFY`에 평문 요약을 넣어도 된다. 다만 "관심종목 중 N개 변동"은 개인 관심종목에서 나온 수치이므로 **Slack 문구에는 넣지 않는다**(앱 안 토스트에만).
- 알림 종류 `DATA_UPDATED`는 다른 데이터 갱신(예: 종목 마스터 대규모 변경)에도 재사용한다. 13F 정정은 문구를 "정정 보고서"로 바꿔 보낸다.

## 6. 저장

Flyway 마이그레이션으로 다음 표를 둔다. 이벤트 로그(동기화 대상)가 아니라 **engine의 캐시 표**다(잔고·체결 원본과 같은 부류).

| 표 | 열 | 비고 |
|---|---|---|
| `pension_dataset` | `id`, `source`(EDGAR_13F / ODCLOUD_ANNUAL), `dataset_id`(접수번호 또는 uddi), `as_of`(분기 말·연말), `title`, `published_at`(제출일·등록일), `first_seen_at` | 카탈로그 |
| `pension_holdings_snapshot` | `id`, `dataset_id`, `content_hash`, `fetched_at`, `row_count`, `total_valuation`, `total_currency` | 13F는 USD, 연간은 KRW. 같은 dataset의 해시가 바뀌면 새 행 |
| `pension_holding` | `snapshot_id`, `rank`, `company_name`, `company_key`(정규화), `cusip`(null 가능), `shares`(null 가능, BigDecimal), `valuation`(BigDecimal), `currency`, `weight_ratio`(null 가능), `stake_ratio`(null 가능) | 억원 → 원으로 환산해 `Money(KRW)`. 비율은 `Percent`(0.0362) |
| `pension_holdings_change` | `id`, `prev_snapshot_id`(null 가능), `new_snapshot_id`, `kind`(NEW_FILING/AMENDED/NEW_DATASET/CONTENT_CHANGED), `detected_at`, `summary_json` | 알림의 원천 |
| `pension_holdings_change_ack` | `change_id`, `user_id`, `acked_at` | 사용자별 확인 |
| `pension_symbol_alias` | `company_key` 또는 `cusip`, `symbol_market`, `symbol_code`, `source`(ISIN_MATCH/NAME_MATCH/USER), `updated_at` | 종목 매핑(8장) |

크기: 13F 분기 600~700행 × 48분기 + 연간 3천 행 × 8년 ≈ 6만 행. 무시할 수준.

## 7. 도메인·포트·패키지

```java
// core.port
public interface PensionHoldingsPort {
  PensionSource source();                                  // EDGAR_13F / ODCLOUD_ANNUAL
  List<PensionDataset> catalog();                          // 13F: 제출 목록 JSON / 연간: Swagger. 실패 시 MarketDataUnavailable
  PensionHoldingsSnapshot fetch(PensionDataset dataset);   // 정보표 XML 또는 전 페이지 수집·정규화. 연간은 키 없으면 SecretMissing
}

// core.pension  [제안 — CORE_DOMAIN 2장 패키지 목록에 추가]
public enum PensionSource { EDGAR_13F, ODCLOUD_ANNUAL }
public record PensionDataset(PensionSource source, String datasetId, LocalDate asOf, LocalDate publishedAt, String title) {}
public record PensionHolding(int rank, String companyName, Optional<Cusip> cusip, Optional<Quantity> shares,
                             Money valuation, Optional<Percent> weight, Optional<Percent> stake) {}
public record PensionHoldingsSnapshot(PensionDataset dataset, List<PensionHolding> holdings, String contentHash, Instant fetchedAt) {
  public static String hashOf(List<PensionHolding> holdings)   // 정렬·정규화 후 SHA-256. 순수 함수
}
public record PensionHoldingsDiff(List<PensionHolding> added, List<PensionHolding> removed,
                                  List<HoldingChange> topMovers, Money totalBefore, Money totalAfter) {
  public static PensionHoldingsDiff between(PensionHoldingsSnapshot prev, PensionHoldingsSnapshot next)   // 순수 함수. 13F는 CUSIP, 연간은 회사명 키
}
public sealed interface PensionHoldingsChange permits NewFiling, Amended, NewDataset, ContentChanged {}
```

- **core에 두는 것**: 값 객체, 해시, diff, 회사명 정규화(`CompanyNameKey`), CUSIP·ISIN 값 객체(체크 디지트 검증). 전부 순수 함수라 TDD 대상.
- **engine.research.pension**: `EdgarThirteenFAdapter`(제출 목록 JSON, index.json, 정보표 XML 파싱, User-Agent 조립, 초당 10회 rate limiter), `OdcloudPensionHoldingsAdapter`(HTTP, 컬럼 정규화, 페이지네이션), `PensionHoldingsWatcher`(스케줄·감지·저장·이벤트 발행 — 포트 목록을 돌며 출처마다 같은 절차), `PensionSymbolMatcher`(8장), JPA 엔티티·리포지토리, `localapi` 핸들러(모달 데이터, 지금 확인, ack).
- 두 어댑터는 서드파티 예외를 `MarketDataUnavailable`로 감싼다. 응답 원문은 로그에 남기지 않는다(공통 규칙).
- 연간 컬럼 정규화 규칙은 어댑터 안의 표 하나로 두고 **연도별 학습 테스트**(WireMock에 각 연도 응답 샘플)로 고정한다. 13F도 정보표 XML 샘플(2022년 이전 천 달러 단위 포함)로 학습 테스트를 둔다.
- `DomainEvent`(sealed, 이벤트 로그)에는 넣지 않는다. 사용자 범위 동기화 데이터가 아니기 때문이다. engine 내부 Spring 이벤트로 흘린다.

## 8. 화면 사용처

1. **모달** (상세는 화면 설계 아티팩트 "국민연금 해외투자 현황 모달"): 제목 바(연간·13F 기준일 · 최근 보고서 칩 · 마지막 확인 · 지금 확인) → 요약 4칸(연간 종목 수, 연간 총 평가액, 13F 미국 상장분, 내 종목) → 탭 넷 **분기 13F(기본)** · 연간 전체 · 전년 대비 변화 · 내 종목 + 검색·정렬·기준 시점 → 표 → 바닥 줄(두 출처·공개 주기·과거 시점 경고·링크아웃). 주문 모달과 같은 틀. 폭 760, 서랍 위 z-order.
   - 13F 표: 종목명·티커·주식 수·평가액(USD)·전분기 대비 주식 수·비고(신규/추가 매수/일부 매도/전량 매도). 원화 환산은 분기 말 환율로 병기.
   - 연간 표: 순위·종목명·티커·평가액(억원)·비중·지분율·전년 대비 pp. 미국 밖 보유분은 여기서만 보인다.
2. **토론 개요 탭의 "국민연금 보유" 행**(해외 종목만): 13F가 있으면 "13F 2Q26 24.9M주(전분기 ▲6.2%) · 연간 비중 3.54% · 지분율 0.28%", 없으면 연간 값만. "현황"을 누르면 모달이 그 종목을 강조한 채 열린다. 국내 종목에는 "해외 종목만 제공".
3. **토론 자료** [확정]: `docs/DEBATE_DESIGN.md` 3.2의 수치 스냅샷 표에 "국민연금 보유(13F 최근 분기 주식 수·전분기 대비, 연간 비중·지분율)" 한 줄. 등급은 `[S]`(공공기관 사실 자료)이되 **기준일이 오래됐음을 표에 명시**한다. `firstSeenAt <= asOf` 필터.
4. **F5 스크리닝 보조 신호** [보류]: 분기 데이터라도 45일 지연이라 스크리닝 신호로는 약하다. 넣지 않는다.

**종목 매핑 [확정].**
- 13F(CUSIP): (a) 토스 미국 종목 마스터가 ISIN을 주면 ISIN의 3~11자리가 CUSIP이므로 정확 일치 [확인 필요: ISIN 제공 여부], (b) 없으면 OpenFIGI(무료, 키 선택, 분당 제한)로 CUSIP→티커를 한 번 받아 `pension_symbol_alias`에 저장 [제안], (c) 그래도 없으면 미매칭.
- 연간(회사명): (a) 13F에서 이미 매핑된 종목의 회사명과 정규화 키로 일치(13F가 사실상 연간 자료의 티커 사전이 된다), (b) 토스 종목 마스터 영문명 정확 일치, (c) 사용자 편집 별칭, (d) 미매칭("티커 미확인" 칩, 행 클릭 이동 없음).
- LLM 추정 매핑은 쓰지 않는다(틀리면 다른 종목으로 이동하는 사고가 난다).

## 9. 상태와 예외

| 상황 | 동작 |
|---|---|
| 공공데이터포털 키 없음 | 연간 탭 비활성 + "admin이 설정에서 공공데이터포털 키를 등록해야 합니다". 13F는 키가 필요 없으므로 계속 동작 |
| SEC 연락처 이메일 없음 | 13F 확인 건너뜀 + 분기 탭에 "admin이 설정에서 SEC 연락처 이메일을 등록해야 합니다"(EDGAR가 연락처 없는 요청을 차단하기 때문) |
| 첫 실행 | 13F 최근 8분기 + 연간 카탈로그 전체를 내려받아 기준선 저장. **알림 없음** |
| 확인 실패(네트워크·5xx·401·403) | 알림 없음, 캐시 표시, 모달 제목 바에 "마지막 확인 실패 09/24 06:30". 3일 연속 실패면 admin에게 한 번 알림. 401(공공데이터)·403(EDGAR 차단)은 키·설정 문제이므로 즉시 admin 알림 |
| 변화 없음 | 배지·토스트 없음. "마지막 확인" 시각만 갱신 |
| 13F 정정(/A) | 해당 분기 스냅샷 교체, diff 재계산, "정정 보고서" 알림 |
| 컬럼·XML 스키마 변형 | 저장하지 않음, admin 알림, 학습 테스트 갱신 필요 |

돈이 걸린 경로가 아니므로 fail-safe의 뜻은 "확실하지 않으면 알리지 않는다"이다.

## 10. 테스트

- `PensionHoldingsSnapshot.hashOf`: 행 순서·공백·대소문자가 달라도 같은 해시. 값 하나가 다르면 다른 해시.
- `PensionHoldingsDiff.between`: 13F는 CUSIP 키로 신규·전량 매도·주식 수 변동률 상위 N, 연간은 회사명 키로 신규·제외·비중 변동. 회사명 정규화 경계(`Alphabet Inc Class A` vs `ALPHABET INC-CL A`는 **다른 키**로 남긴다 — 과잉 정규화 금지).
- `Cusip`·`Isin` 값 객체: 체크 디지트, ISIN→CUSIP 추출(`US0378331005` → `037833100`).
- `Percent`/`Money` 변환: `"3.62"` → `0.0362`, 억원 정수 `152300` → `15,230,000,000,000원`, 13F `value` 달러 단위(2023~)와 천 달러 단위(~2022) 구분.
- 어댑터 학습 테스트: EDGAR 제출 목록 JSON·index.json·정보표 XML 샘플, 403 차단 응답; 연간 컬럼명 변형 4종, 페이지네이션, 401·500.
- Watcher: 고정 `Clock`으로 24시간 경계(정확히 24시간은 확인함), 06:30 KST 스케줄, 같은 접수번호·같은 `(datasetId, hash)` 재감지 시 알림 0건, 정정(/A) 시 교체 + 1건, 사용자 3명일 때 항목 3개·ack는 각자.

## 11. 열린 항목

**결정됨 (2026-09-24, 사용자)**
1. 버튼 이름 **"연기금종목"**, 위치 **☰ 관심종목과 ⚡ 실시간 급등락 사이**, 단축키 ⌘N. `PROJECT.md` 11.3 상단 바 순서 [확정] 개정.
2. 캐시는 **cache-aside, TTL 24시간**(4장). `perPage` 상한은 정의를 찾지 못했으므로 500씩 끝까지 페이지를 넘긴다.
3. 회사명→티커 매핑: 종목 마스터 영문명 정확 일치 + 사용자 편집 별칭 표, 미매칭은 칩 없이 표시(8장).
4. 알림 기본값 앱 안 켬 · Slack 끔. 토론 수치 스냅샷 표에 "국민연금 보유" 줄 추가(`docs/DEBATE_DESIGN.md` 3.2).
5. 13F: 정보표 파일명은 접수별 `index.json`으로 확인, CUSIP 매핑은 ISIN 3~11자리, SEC 연락처 이메일은 admin 공유 설정.

**외부 확인**
1. EDGAR: 접수별 정보표 XML 파일명(`index.json`), 2022년 이전 `value` 단위 전환 시점.
2. 공공데이터포털: `perPage` 상한과 일일 트래픽 한도, `Authorization` 헤더의 `Infuser ` 접두 여부, `uddi:df8671d8…_20201006`의 기준 시점(2019년 말 추정).
3. 토스 미국 종목 마스터의 영문 회사명 형식과 ISIN 제공 여부.

## 12. 데이터 출처와 이용 허가 정리

| 출처 | 무엇 | 주기·지연 | 허가·비용 | 지켜야 할 조건 |
|---|---|---|---|---|
| SEC EDGAR 13F-HR (CIK 0001608046) | 미국 상장분 종목별 주식 수·평가액(USD), CUSIP | 분기, 분기 말 후 35~45일 | **회원가입·키 없음, 무료, 공공 데이터.** 별도 이용 신청 없음 | `User-Agent`에 이름·연락처 이메일(없으면 차단), 초당 10회 이하, 대량 내려받기는 미국 야간. 공식 안내 https://www.sec.gov/search-filings/edgar-application-programming-interfaces |
| 공공데이터포털 국민연금공단_해외주식 투자정보 | 전 지역 종목별 평가액(억원)·비중·지분율, 회사명만 | 연 1회, 연말 기준, 이듬해 가을~겨울 공개(2024년 말분은 2025-12-10) | **활용신청으로 발급된 서비스 키 사용(이미 확보).** 이용허락범위 제한 없음, 무료 | 키는 공유 키(admin, Keychain). 일일 트래픽 한도(계정 종류에 따름 [확인 필요]). 한 번 확인 5회 안팎이라 여유 |
| 기금운용본부 월간 운용현황 | 자산군 합계만 | 월간, 약 2개월 지연 | 공개 페이지, API 없음 | 이번 설계에서 제외. 쓰려면 페이지 수집이라 약관 확인 선행 |
| KRX 투자자별 매매동향 "연기금등" | 국내 종목별 일간 순매수(연기금 합산) | 일간 | KRX Open API 승인 절차(`docs/EXTERNAL_APIS.md` 2.3) 또는 토스 수급 API | F13 범위 밖. F12 수급 카드 후보 |

두 채택 출처 모두 **별도 계약이나 승인 절차가 새로 필요하지 않다.** 공공데이터포털 키는 이미 있고, EDGAR는 설정에 연락처 이메일을 넣는 것으로 끝난다.
