# DART 어댑터 설계 — 국내 공시 감시 · 재무 · 배당 · 기업 정보

> 문서 지도: [docs/README.md](README.md) · 기준 문서: [PROJECT.md](../PROJECT.md) · 작업 규칙: [CLAUDE.md](../CLAUDE.md) · 개발 순서: [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)

작성: 2026-09-25 / 상태: **[제안]** (API 이용 자격·그룹·한도·상태 코드는 [확인함], 엔드포인트명·파라미터는 개발가이드와 대조 필요 [확인 필요]) / 관련: [`docs/EXTERNAL_APIS.md`](EXTERNAL_APIS.md) 1.3, [`docs/STOCK_INFO_DESIGN.md`](STOCK_INFO_DESIGN.md)(F15), [`docs/RAG_DESIGN.md`](RAG_DESIGN.md) 4.3·5장, [`docs/CORE_DOMAIN.md`](CORE_DOMAIN.md) 7·10장, [`docs/EDGAR_DESIGN.md`](EDGAR_DESIGN.md)(미국 대응)

## 1. 결론 요약

- DART는 국내 종목의 **사실 자료 1순위**다. 하나의 키(admin 공유, 필수)로 네 가지를 한다: **공시 감시**(1~2분 폴링, 새 접수번호 → 이벤트), **재무제표**(F15 재무제표 탭·F5 스크리닝), **배당**(F15 배당 탭), **기업 정보**(F15 산업군의 업종 코드·F13 매핑 보조).
- 모든 요청은 `GET https://opendart.fss.or.kr/api/{API명}.json?crtfc_key=…&…`, 응답 `status` `000`이 정상, `013`은 "결과 없음"(오류 아님). 하루 20,000건.
- 공시 시각이 날짜뿐이므로 **`firstSeenAt`을 반드시 저장**한다(기준 시점 원칙). 정정 공시는 덮지 않고 버전으로 쌓는다.
- 악재성 공시 → 자동 매수 제외·알림은 **`core`의 규칙 표**(공시 유형 → 조치)로 하고 LLM에 맡기지 않는다.

## 2. 공통 규칙 [확인함, 엔드포인트명은 대조 필요]

| 항목 | 값 |
|---|---|
| 호스트 | `https://opendart.fss.or.kr/api/` |
| 인증 | 모든 요청에 `crtfc_key`(40자) 쿼리. 키는 Keychain `stockholm/shared/dart` |
| 형식 | `.json`(기본) 또는 `.xml`. `document.xml`·`corpCode.xml`은 zip |
| 상태 코드 | `000` 정상 · `010` 미등록 키 · `011` 사용 불가 키 · `012` 접근 불가 IP · `013` 조회 데이터 없음(**예외 아님, 빈 결과**) · `020` 요청 제한 초과 · `100` 부적절한 값 · `800` 점검 중 · `900` 정의되지 않은 오류 |
| 한도 | 하루 20,000건(초과 `020`). 가족 4명이 각자 키를 쓰지 않고 **admin 키 하나**를 쓰므로 한 설치의 예산이다. 어댑터 rate limiter 초당 3회 |
| 종목 식별 | DART는 `corp_code`(8자리 고유번호)를 쓴다. `corpCode.xml`(zip, 전체 회사 목록·`stock_code`)을 **주 1회** 내려받아 `stock_code ↔ corp_code` 표 갱신. 상장사만 `stock_code`가 있다 |
| 보고서 구분 | `reprt_code` 11013 1분기 · 11012 반기 · 11014 3분기 · 11011 사업보고서. `fs_div` CFS(연결)/OFS(별도). 재무 API는 2015년 이후 |

## 3. 용도별 API [엔드포인트명은 개발가이드와 대조 후 확정]

| 용도 | API(그룹) | 주요 파라미터 | 우리 쪽 결과 |
|---|---|---|---|
| 공시 감시 | 공시검색 `list`(DS001) | `bgn_de`·`end_de`(당일), `page_count` 100, `corp_code` 없음(전체) | 새 `rcept_no` → `Disclosure` + 이벤트. `corp_code` 없이 조회 시 기간 3개월 제한 |
| 공시 원문 | 공시서류 원본 `document`(DS001) | `rcept_no` | zip 안 XML → 텍스트 추출 → RAG(사업보고서는 "사업의 내용"·"위험 요소"·"경영진단" 절만) |
| 기업 정보 | 기업개황 `company`(DS001) | `corp_code` | `corp_name, corp_name_eng, stock_code, ceo_nm, corp_cls(Y·K·N·E), induty_code(표준산업분류), est_dt, acc_mt(결산월), hm_url` → **F15 산업군의 업종 코드**, F13 매핑의 영문명 보조, 결산월 |
| 재무제표 | 단일회사 전체 재무제표 `fnlttSinglAcntAll`(DS003) | `corp_code, bsns_year, reprt_code, fs_div` | `account_id`(IFRS 태그)·`account_nm`·`thstrm_amount`(당기)·`frmtrm_amount`(전기)·`bfefrmtrm_amount`(전전기) → F15 재무제표 표 3개년 |
| 재무제표(다건) | 다중회사 주요계정 `fnlttMultiAcnt`(DS003) | `corp_code` 여러 개(쉼표), `bsns_year, reprt_code` | F5 스크리닝 재무 항목을 종목 다건으로 → 호출 수 절약 |
| 재무 지표 | 주요 재무지표 `fnlttSinglIndx`(DS003) [확인 필요] | `corp_code, bsns_year, reprt_code, idx_cl_code` | 검증용 보조 |
| 배당 이력 | 배당에 관한 사항 `alotMatter`(DS002) | `corp_code, bsns_year, reprt_code` | 연간 주당배당금·배당성향·배당수익률(사업보고서 기재) → F15 배당 탭 검증 |
| 배당 결정 | 주요사항보고서 현금·현물배당 결정(DS005) [API명 확인 필요] | `corp_code, bgn_de, end_de` | 배당 기준일·지급일·주당배당금 → F15 배당 탭의 내역 표·다음 배당 |
| 지분 | 대량보유 `majorstock`, 임원·주요주주 `elestock`(DS004) | `corp_code` | F15 추가 후보 탭(지분), 토론 자료 |
| 주요사항 | DS005 계열(유상증자·CB·BW·감자·자기주식·합병·분할·영업정지·회생·부도·소송) | `corp_code, bgn_de, end_de` | 가드레일 이벤트(5장) |

## 4. 공시 감시

1. 매 1~2분(장중 1분, 장외 5분) `list`를 당일 범위·`corp_code` 없이 페이지 순회. 하루 1,000건 안팎이라 페이지 10~15회.
2. 저장에 없는 `rcept_no`를 새 공시로 등록: `(rcept_no, corp_code, stock_code, corp_cls, report_nm, flr_nm, rcept_dt, rm, firstSeenAt=now)`.
3. `report_nm`을 규칙 표로 분류(5장). 보유·관심·자동 매수 후보 종목의 공시만 이벤트로 발행(그 밖은 저장만).
4. 정정(`report_nm`이 "[정정]"으로 시작)은 새 문서로 넣고 원본과 `supersedes`로 연결(같은 `corp_code`·보고서명).
5. 원문 수집·RAG 색인은 별도 큐. 색인 완료를 기다리지 않는다.
6. 감시 시작 전 과거 공시는 보수적으로 "다음 거래일 장 시작 전 공개"로 취급(기준 시점 원칙).

예산: 감시 15회/2분 ≈ 하루 5,000회(장중 1분이면 더) + 재무·배당 재적재 수십 회 + 원문 수십 회 → 20,000건 안. 감시 주기는 설정으로 늘릴 수만 있다.

## 5. 공시 유형 → 조치 (`core.guardrail`의 규칙 표)

| 분류 | 보고서명 패턴(예) | 칩 | 조치 |
|---|---|---|---|
| 악재 | 유상증자결정, 전환사채권발행결정, 신주인수권부사채권발행결정, 교환사채권발행결정, 감자결정, 영업정지, 회생절차개시신청, 부도발생, 관리종목지정, 상장폐지, 불성실공시법인지정, 소송등의제기 | 악재 | **자동 매수 제외 목록에 즉시 추가**, 보유 시 Slack 긴급 알림, 자동 매도 허용 종목이면 매도 판단 앞당김 |
| 호재 | 자기주식취득결정, 현금·현물배당결정, 무상증자결정, 단일판매·공급계약체결(매출 대비 큰 것) | 호재 | 토론 자료, 관심 종목이면 빠른 토론 자동 실행(설정) |
| 재무 | 사업보고서, 반기보고서, 분기보고서, 감사보고서 | 재무 | 재무제표·배당 캐시 재적재(F15), RAG 색인 |
| 안내 | 기업설명회(IR)개최, 기타경영사항, 조회공시요구·답변 | 안내 | 표시만 |
| 미분류 | 그 밖 | — | 표시만, 주 1회 미분류 목록 검토 |

- 표는 `core`에 두고 LLM은 관여하지 않는다. 패턴은 `report_nm` 정규식. 새 패턴 추가는 코드 변경(테스트 포함).
- "자동 매수 제외" 해제는 사람만(설정 화면), 기본 유지 기간 30일 [제안].

## 6. 재무제표 추출 (F15·F5)

- `fnlttSinglAcntAll`의 행에서 표준 항목을 뽑는다. 1순위 `account_id`(IFRS 태그: `ifrs-full_Revenue`, `dart_OperatingIncomeLoss`, `ifrs-full_ProfitLoss`, `ifrs-full_Equity`, `ifrs-full_Liabilities`, `ifrs-full_BasicEarningsLossPerShare` 등), 2순위 `account_nm` 표준 명칭 표(매출액·영업이익·당기순이익·자본총계·부채총계). 표는 어댑터 안, **제조·금융 샘플 학습 테스트**로 고정.
- 비율(영업이익률·부채비율·ROE)은 `core.FinancialRatios`(순수 함수, `RoundingMode.HALF_EVEN`).
- 연간은 사업보고서(11011)의 당기·전기·전전기 → 3개년 한 번에. 분기는 11013/11012/11014를 분기별 호출(누적치 → 분기 값은 차감, [`docs/EDGAR_DESIGN.md`](EDGAR_DESIGN.md) 5장과 같은 주의).
- 금융업(`corp_cls`·업종 코드 64~66)은 항목 표를 바꾼다(영업수익·순이자이익·BIS 등).
- 정정 사업보고서가 오면 새 접수번호 버전으로 저장, 화면은 최신본.

## 7. 배당 (F15)

- 내역 표·다음 배당: DS005 **현금·현물배당 결정** 공시(배당 기준일·지급 예정일·1주당 배당금·배당 종류). 공시 감시가 감지하는 즉시 반영.
- 연간 검증: `alotMatter`(주당 배당금·배당성향·수익률, 사업보고서 기재).
- 수익률 계산은 [`docs/STOCK_INFO_DESIGN.md`](STOCK_INFO_DESIGN.md) 3.2의 정의를 따르고 종가는 토스 일봉(백필은 KRX).

## 8. 기업 정보 (F15 산업군 · F13)

- `company`의 `induty_code`(한국표준산업분류) → `core`의 대응 표(대분류·중분류 → 우리 업종명, 대응 KRX 업종 지수명). 토스 종목 정보에 업종이 있으면 그것이 1순위, 없으면 이것 [확인 필요: 토스 필드].
- `corp_name_eng`는 F13 연간 자료(회사명만)의 매핑 보조로 쓰지 않는다(그쪽은 미국 회사). 국내 종목의 영문명은 KRX 종목기본정보가 있다.
- `acc_mt`(결산월)로 회계연도 표기.

## 9. 저장

| 표 | 열 |
|---|---|
| `dart_corp` | `corp_code, stock_code, corp_name, corp_name_eng, corp_cls, induty_code, acc_mt, est_dt, updated_at` |
| `kr_disclosure` | `rcept_no(PK), corp_code, stock_code, report_nm, flr_nm, rcept_dt, rm, category(악재/호재/재무/안내/미분류), supersedes_rcept_no, first_seen_at, indexed_at` |
| ~~`kr_financial_statement`~~ | → `financial_fact(source=DART, entity_key=corp_code, tag, unit, start_date, end_date, fy, fp, filing_ref=rcept_no, value, first_seen_at)` 원본 + `financial_statement`(F15 공용 표현, 접수번호 단위 버전)로 통합 [확정 2026-09-25, [`docs/DB_SCHEMA.md`](DB_SCHEMA.md) 9장] |
| `dividend_payment`·`dividend_yield_weekly` | [`docs/STOCK_INFO_DESIGN.md`](STOCK_INFO_DESIGN.md) 6장 |
| `auto_buy_exclusion` | `symbol, reason(공시 유형), rcept_no, since, until` |

## 10. 오류와 fail-safe

| 상황 | 동작 |
|---|---|
| `010/011/012` | 키·IP 문제 → admin 알림, 감시 중단(주문 경로 무관) |
| `020` 한도 초과 | 감시 중단 후 자정 재개, admin 알림. 예산 계산 재점검 |
| `013` | 빈 결과. 오류 아님 |
| `800` 점검 | 30분 뒤 재시도 |
| 원문 zip 파싱 실패 | 메타데이터만 저장, 원문 링크로 대체 |
| 분류 표에 없는 보고서명 | 미분류로 저장, 주 1회 목록 |

## 11. 테스트

- 상태 코드 처리(`013`은 예외 아님), `020` 시 중단.
- 공시 감시: 같은 `rcept_no` 재수신 0건, 새 건 1건, `[정정]` 연결, `firstSeenAt` 기록.
- 분류 표: 악재·호재·재무·안내 패턴 전체 + 미분류.
- 재무 추출: 제조업·금융업 샘플, 분기 차감, 정정 버전.
- corpCode 갱신: 상장사만 `stock_code`, 상폐 종목 처리.

## 12. 열린 항목

1. 엔드포인트명·파라미터 대조(개발가이드, 구현 시 키로 학습 테스트). 특히 DS005 배당 결정·주요사항 API의 정확한 이름.
2. 토스 종목 정보의 업종 필드 유무(8장 1순위 결정).
3. 자동 매수 제외의 기본 유지 기간(제안 30일)과 해제 규칙.
4. 감시 주기(장중 1분/장외 5분) 확정.
