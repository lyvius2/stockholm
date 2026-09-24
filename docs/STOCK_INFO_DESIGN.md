# 종목 정보 서랍 (F15) — 재무제표 · 배당 · 산업군 · 관련 종목 · 공시

작성: 2026-09-24 / 상태: **[확정 2026-09-25]** — 탭·항목·마지막 탭 기억 승인. ETF 구성종목 탭은 화면·설계 유지, **구현 보류**(국내 데이터 제공자 확보 시 해제). 배당은 정보 표시만(F7 필터에 쓰지 않음) / 관련: `PROJECT.md` 9장 F15·11.3, `docs/EXTERNAL_APIS.md` 1.3(DART)·2.6(EDGAR), `docs/RAG_DESIGN.md` 4.3·5장, `docs/DEBATE_DESIGN.md` 3.1(동종 종목), 화면 설계 아티팩트 v22 "종목 정보 서랍"

## 1. 결론 요약

- 2번 영역(가격) 헤더의 **ⓘ 정보** 버튼(⌘I)으로 여는 **오른쪽 서랍**. 순서는 **|Main|종목 정보|급등락|** 이고 오른쪽 둘은 겹쳐 열린다. **좌우는 배타**: 오른쪽이 열리면 커뮤니티·관심종목이 슬라이딩하며 자동으로 닫히고, 왼쪽이 열리면 오른쪽 둘이 닫힌다 [확정 2026-09-24].
- 탭 다섯: **재무제표**(기본) · **배당** · 산업군 · 관련 종목 · 공시. 재무제표와 배당이 중심이다. **ETF는 재무제표 대신 구성종목 탭이 기본**이다(3.6, [확정 구성 2026-09-25]).
- 국내는 **DART**가 원천(재무제표·배당·공시), 토스 API가 업종·시세를 보탠다. 미국은 SEC EDGAR. 모두 구조화 데이터라 LLM을 거치지 않는다(산업군 요약만 RAG 요약).
- 숫자는 데몬이 `BigDecimal`로 계산해 문자열로 내려주고 프론트는 표시만 한다. 정정 공시는 값을 덮지 않고 버전으로 쌓는다. 배당·재무는 정보이지 어떤 자동 주문의 조건도 아니다.

## 2. 서랍 규칙

| 항목 | 값 |
|---|---|
| 여는 곳 | 2번 영역 헤더 "ⓘ 정보" 버튼, ⌘I. 상단 바에는 두지 않는다(종목 단위 정보라 종목이 보이는 자리에 둠) |
| 닫는 법 | ✕ · 같은 버튼 · Esc |
| 폭 | 기본 32%, 구분선으로 15~50%, 더블클릭 복원, 사용자 설정 저장·동기화 |
| 겹침 | 오른쪽: \|Main\|종목 정보\|급등락\|. 둘 다 열리면 네 영역은 두 폭의 합만큼 왼쪽으로 밀린다 |
| 좌우 배타 | 오른쪽 서랍(종목 정보·급등락)이 열리면 왼쪽(커뮤니티·관심종목) 자동 닫힘, 왼쪽이 열리면 오른쪽 자동 닫힘. 급등락에서 💬를 누르면 오른쪽이 닫히고 커뮤니티가 왼쪽에 열린다 |
| 종목 변경 | 검색·관심종목·급등락·관련 종목 어디서 골라도 서랍은 열린 채 그 종목으로 갱신. 탭은 유지 |
| 저장 | 열림 상태는 저장하지 않는다(재시작 시 닫힘). 마지막 탭은 사용자 설정으로 기억 [확정 2026-09-25] |

## 3. 탭별 내용과 출처

### 3.1 재무제표 (기본)

- 요약 3칸: 매출액(전년 대비), 영업이익(이익률), ROE(부채비율).
- 표: 매출액 · 매출총이익 · 영업이익 · 영업이익률 · 당기순이익 · EPS · BPS · 부채비율 · ROE, **최근 3개년**(연간) 또는 **최근 4~8분기**(분기 토글). 연결(CFS) 기본, 별도(OFS) 토글. 단위 억원(미국은 백만 달러).
- 최근 분기 한 줄(매출·영업이익·YoY·접수일)과 원문 링크(`https://dart.fss.or.kr/dsaf001/main.do?rcpNo={rcept_no}`).
- 출처(국내): DART **단일회사 전체 재무제표**(`fnlttSinglAcntAll`, `reprt_code` 11011/11012/11013/11014, `fs_div` CFS/OFS). 표준 항목은 어댑터가 계정 ID(`account_id`, IFRS 태그)로 뽑고, 태그가 없으면 `account_nm` 표준 명칭 표로 대응한다. 비율(이익률·부채비율·ROE)은 데몬이 계산. 주요 재무지표 API(`fnlttSinglIndx`)는 검증용 보조 [확인 필요: 엔드포인트명은 구현 시 개발가이드와 대조].
- 출처(미국): EDGAR **company facts**(XBRL `us-gaap` 태그: Revenues, GrossProfit, OperatingIncomeLoss, NetIncomeLoss, EarningsPerShareDiluted, StockholdersEquity, Liabilities…). 10-K/10-Q 단위.
- 금융업(은행·보험·증권)은 항목이 다르다: 영업수익·순이자이익·BIS/RBC 등 업종별 표를 따로 둔다 [제안].

### 3.2 배당

**구성 [확정 2026-09-24, 사용자 지정]** — 위에서 아래로:
1. **요약 3칸**: **배당 주기**(연 몇 회, 몇 월에 지급 — 최근 2년 지급월에서 추론, 예 "분기 · 4·5·8·11월") · **직전 1주당 배당액**(가장 최근 지급 회차의 주당 금액, 지급일·회차 표기) · **평균 연 배당수익률**(최근 5년, 각 연도 배당 합 ÷ 그 해 연말 종가의 평균).
2. **배당수익률 최근 1년 추이 그래프**: 주 단위 점으로, 각 시점의 값 = **최근 12개월 배당 합 ÷ 그 주 종가**(TTM 수익률). 오른쪽 끝이 현재 값. 데몬이 계산해 내려준 수열을 프론트는 그리기만 한다.
3. **배당 내역 표, 최신순**: `배당지급일 | 1주당 배당금 | 배당수익률`. 행의 수익률 = 지급일 기준 최근 12개월 배당 합 ÷ 지급일 종가.
4. **다음 배당**: 기준일 · 배당락일(D-n) · 결정 공시 예정 · 지급 예정. "기준일 전 매수 시 권리 발생, 배당락일 시가가 그만큼 낮게 시작"을 설명 문구로 둔다.

계산 정의(모두 데몬, `BigDecimal`, `RoundingMode.HALF_EVEN`, 소수 둘째 자리): 배당 주기 = 최근 24개월 지급 건수 ÷ 2를 반올림(1·2·4·12)과 지급월 집합. 수익률 분모의 종가는 토스 일봉(수정주가 아님, 액면분할이 있으면 배당금도 같은 비율로 조정 [제안]). 배당이 없는 종목은 "배당 이력 없음"만 보인다.
- 출처(국내): 주요사항보고서 **현금·현물배당 결정** 공시가 1차(지급일·기준일·주당배당금 — 표의 행과 "다음 배당"의 원천), 정기보고서 **배당에 관한 사항**(`alotMatter`)이 보조(연간 합계·배당성향 검증). 공시 감시가 배당 결정 공시를 감지하면 그 종목의 배당 캐시를 갱신한다. 지급일이 아직 없는 결정 공시는 "예정"으로 표시.
- 출처(미국): **Massive `/v3/reference/dividends`**(선언일·배당락일·기준일·지급일·주당 현금·주기·유형, 무료 등급 포함 — 2026-09-25 확인)가 1차. EDGAR company facts(`CommonStockDividendsPerShareDeclared`)는 검증용. 이로써 "다음 배당" 칸이 미국 종목에도 채워진다.
- 배당은 정보다. F7 제외 필터·F8 매도 판단의 입력이 아니며, 넣으려면 별도 결정.

### 3.3 산업군

- KRX 업종 · GICS 분류, 업종 내 시총 순위, 업종 지수 20일 수익률과 종목의 초과 수익률. **업종의 출처**: KRX Open API 종목기본정보에는 업종 필드가 없다(2026-09-25 명세 확인). 후보 순서 — (1) 토스 종목 정보의 업종·GICS [확인 필요], (2) DART 기업개황의 표준산업분류 코드(`induty_code`) → `core`의 분류 대응 표 [제안]. **미국 종목은 Massive 종목 개요의 `sic_code`·`sic_description`**(무료 등급)으로 채운다. 업종 지수는 KRX Open API 지수 시리즈(코스피·코스닥 업종 지수, `docs/KRX_DESIGN.md` 3.4)에서 받고, 종목→업종 지수 대응 표는 어댑터에 둔다.
- 업종 요약: DART "사업의 내용" + 업종 뉴스를 RAG로 모아 `DOCUMENT_DIGEST` 목적으로 주 1회 요약(F6 전망 요약과 같은 규칙, 출처 번호).
- "산업 동향 토론 열기" 버튼 → 4번 영역에서 산업 동향 테마 시작.

### 3.4 관련 종목

- F6 산업 동향의 **동종 종목 목록과 같은 데이터**(같은 산업 분류 시총 상위 10 + 보유·관심, 사용자 편집, 상한 14). 한 곳에서 편집하면 둘 다 바뀐다. 미국 종목의 기본 후보는 Massive 관련 종목(related companies) + SIC 동일 종목 [제안].
- 행: 종목명·코드·칩(보유·관심·토론 중·투자경고) · 현재가·등락률 · PER. 시세는 실시간 스트림(급등락 서랍과 같은 규칙).
- 행 클릭 → 네 영역이 그 종목으로, 서랍은 열린 채 갱신.

### 3.6 구성종목 (ETF·ETN만) [확정 구성 2026-09-25 · **구현 보류**: 국내 구성종목 데이터 제공자를 찾을 때까지. 그동안 ETF는 "구성종목 미제공" 안내 + 운용사 링크아웃]

ETF·ETN을 고르면 재무제표 탭이 숨고 이 탭이 기본으로 열린다. 위에서 아래로:
1. **구성 비율 원형 그래프**: 상위 10종목 + "기타 n종목"의 도넛, 가운데에 상위 10 합계. 범례에 종목·비율. 기준일 표기.
2. **구성종목 표**(기준일 표시): `순번 | 종목 | 자산종류 | 비율`. 자산종류는 **주식 · 채권 · 현금 · 파생(선물·스왑) · 기타(ETF·리츠·원자재)** 다섯 가지로 정규화하고, 주식은 시장(국내/미국/ADR)을 덧붙인다. 비율은 평가금액 ÷ 순자산, 합계 100%. 전체 구성은 스크롤.
3. **"어떤 ETF인가" 요약 문장**: 추적지수·운용사·총보수·순자산·환헤지 여부(구조화 값)와 함께, 어떤 산업·테마·지역으로 구성됐는지, 상위 비중·자산 구성(주식 %·현금 %)을 3~4문장으로. `DOCUMENT_DIGEST` 목적으로 **주 1회** 생성(투자설명서·운용사 상품 설명 + 구성종목의 업종 집계가 입력, 출처 번호 필수), 구성이 크게 바뀌면(상위 10 변화 20%p 이상) 즉시 재생성.

출처: 국내 ETF의 구성종목은 **KRX Open API에 없다**(2026-09-25 확인 — ETF 일별매매정보는 종가·NAV·순자산·기초지수까지). 후보는 운용사 사이트 일별 CSV(운용사별 형식) [결정 필요]. 헤더의 추적지수·순자산·괴리율은 KRX ETF 일별매매정보(`docs/KRX_DESIGN.md` 3.3)에서 받는다. 미국 ETF는 운용사 일일 공시(예: iShares·Vanguard CSV)와 **SEC N-PORT**(월간, 공개는 분기 지연). 배당 탭은 ETF에서는 **분배금**으로 같은 구성이다. 산업군 탭은 ETF의 추적지수 업종·테마 분류를 보인다.

### 3.5 공시

- 공시 감시(`docs/EXTERNAL_APIS.md` 1.3 사용 방식 1)가 모은 결과를 종목으로 걸러 최근순. **새 API 호출 없음**.
- 행: 제목(정정이면 `[정정]`), 유형 칩(재무 / 호재 / 악재 / 안내 — `core`의 공시 유형→분류 표), 접수일, **감지 시각(firstSeenAt)**, 정정 연결("정정됨 → 날짜"), 원문 링크아웃.
- 악재 유형은 가드레일 이벤트로도 흐른다는 사실을 바닥 줄에 밝힌다(표시일 뿐 여기서 판단하지 않는다).

## 4. 데이터 갱신과 캐시

| 데이터 | 갱신 시점 | 캐시 |
|---|---|---|
| 재무제표 | 공시 감시가 그 종목의 사업·분기·반기보고서(또는 정정) 접수를 감지했을 때. 그 밖에는 서랍을 처음 열 때 1회 | SQLite 표, 보고서 접수번호 단위 버전 |
| 배당 내역·주기·직전 배당액 | 배당 결정 공시 감지 시(사업보고서로 검증) | 동일 |
| 다음 배당 | 배당 결정 공시 감지 시 | 동일 |
| 평균 연 배당수익률·1년 추이 | 일 1회 장 마감 후 재계산(종가 확정 시) | 수열을 SQLite에 저장 |
| 산업군 분류·순위 | 일 1회(종목 마스터 동기화와 함께) | 동일 |
| 업종 요약 | 주 1회(관심·보유 종목은 주말 배치, 그 밖은 처음 열 때) | 1주 |
| 관련 종목 | F6과 공유 | F6 규칙 |
| 공시 목록 | 공시 감시 결과 | 공시 감시 저장소 |

DART 하루 20,000건 한도 안에서 이 서랍이 새로 쓰는 호출은 "재무제표·배당 최초 적재"와 "보고서 접수 시 재적재"뿐이다. 관심·보유 종목 30개 기준 하루 수십 건.

## 5. 도메인·포트

```java
// core.port
public interface FundamentalsPort {
  FinancialStatements statements(Symbol s, ReportPeriod period, ConsolidationBasis basis);  // 최근 N기
  DividendHistory dividends(Symbol s);                                                        // 이력 + 다음 배당(있으면)
  IndustryProfile industry(Symbol s);                                                          // 분류·순위
}
public interface EtfCompositionPort {                                                            // [제안] 3.6. KRX PDF(KR) / 운용사 CSV·N-PORT(US)
  EtfComposition composition(Symbol etf);                                                        // 기준일·구성종목·자산종류·비율·순자산·추적지수
}
public record EtfComposition(Symbol etf, LocalDate asOf, String indexName, String manager, Percent totalExpenseRatio, Money netAssets,
                             boolean currencyHedged, List<EtfHolding> holdings /* 비율 내림차순 */, Instant firstSeenAt) {}
public record EtfHolding(int rank, String name, Optional<Symbol> symbol, AssetClass assetClass, Percent weight) {}
public enum AssetClass { EQUITY, BOND, CASH, DERIVATIVE, OTHER }
// core.fundamentals [제안]
public record FinancialStatements(Symbol symbol, ConsolidationBasis basis, List<FiscalPeriodFigures> periods, String sourceReceiptNo, Instant firstSeenAt) {}
public record FiscalPeriodFigures(FiscalPeriod period, Money revenue, Money grossProfit, Money operatingIncome, Money netIncome,
                                  Money eps, Money bps, Percent debtRatio, Percent roe) {}
public record DividendHistory(Symbol symbol, DividendCycle cycle, List<DividendPayment> payments /* 최신순 */, Percent averageAnnualYield,
                              List<YieldPoint> trailingYearYield /* 주간 TTM 수익률 */, Optional<UpcomingDividend> next) {}
public record DividendCycle(int timesPerYear, List<Month> paymentMonths) {}
public record DividendPayment(LocalDate paymentDate, LocalDate recordDate, Money perShare, Percent yieldAtPayment /* TTM 합 ÷ 지급일 종가 */) {}
public record YieldPoint(LocalDate weekEnding, Percent trailingYield) {}
public record UpcomingDividend(LocalDate recordDate, LocalDate exDividendDate, Optional<LocalDate> paymentDate, Optional<Money> perShare, Instant firstSeenAt) {}
public enum ConsolidationBasis { CONSOLIDATED, SEPARATE }
public enum ReportPeriod { ANNUAL, QUARTERLY }
```

- 어댑터: `engine.research.dart.DartFundamentalsAdapter`(KR), `engine.research.edgar.EdgarFundamentalsAdapter`(US). 계정 매핑 표는 어댑터 안에 두고 **연도별·업종별 학습 테스트**로 고정한다.
- 비율 계산은 `core`의 순수 함수(`FinancialRatios`)로 두고 `RoundingMode`를 명시한다. `Percent`는 비율(0.109)로 저장.
- 정정: 같은 `(symbol, period)`에 새 접수번호가 오면 새 버전으로 저장하고 `supersedes`로 연결. 화면은 최신본, 기준 시점 조회는 그 시점 판본.

## 6. 저장

| 표 | 열 | 비고 |
|---|---|---|
| `financial_statement` | `id, symbol_market, symbol_code, basis, period_kind, fiscal_year, fiscal_quarter, receipt_no, supersedes_id, first_seen_at, figures_json` | 접수번호 단위 버전 |
| `dividend_payment` | `symbol, payment_date, record_date, per_share, yield_at_payment, receipt_no, first_seen_at` | 결정 공시 단위, 최신순 표의 원천 |
| `dividend_yield_weekly` | `symbol, week_ending, trailing_dps, close, trailing_yield` | 1년 추이 그래프·평균 수익률 계산 |
| `dividend_upcoming` | `symbol, record_date, ex_date, payment_date, per_share, receipt_no, first_seen_at` | 결정 공시 기준 |
| `industry_profile` | `symbol, krx_sector, gics, rank_in_sector, sector_count, updated_at` | 일 1회 |
| `related_symbols` | F6과 공유(사용자 편집 포함, 동기화 대상) | |
| `etf_composition` | `symbol, as_of, index_name, manager, ter, net_assets, hedged, first_seen_at, holdings_json` | 기준일 단위 버전. 요약 문장은 `etf_digest(symbol, generated_at, text, sources_json)` |

## 7. 화면 (상세는 화면 설계 아티팩트 "종목 정보 서랍")

헤더(ⓘ 종목 정보 · 종목명 · "DART · 확인 시각" · ✕) → 탭 칩 다섯(공시 탭은 건수) → 탭 본문 → 바닥 줄(출처 · 정정 규칙). 재무제표 탭은 요약 3칸 + 표 + 출처 줄, 배당 탭은 요약 3칸 + 5년 표 + "다음 배당" + 설명, 산업군은 정의 목록 + 요약 + 토론 버튼, 관련 종목은 목록, 공시는 목록. 2번 영역 헤더의 "ⓘ 정보" 버튼은 열린 동안 강조색.

## 8. 예외

| 상황 | 동작 |
|---|---|
| DART 키 없음 | 서랍은 열리되 재무·배당·공시 탭에 "admin이 DART 키를 등록해야 합니다"(D17에서 필수 키라 드묾) |
| ETF·ETN | 재무제표 탭 숨김, 구성종목 탭 기본(3.6). 배당 탭은 분배금. PDF를 못 받으면 "구성종목 미제공" + 운용사 링크아웃 |
| 신규 상장(보고서 없음) | "아직 정기보고서가 없습니다" + 증권신고서 링크 |
| 금융업 | 업종별 항목 표로 교체 |
| 조회 실패 | 캐시 표시 + 확인 시각에 "실패" 표기, 알림 없음 |
| 미국 종목 배당 캘린더 부재(Massive 응답 없음) | 이력만 보이고 "다음 배당" 칸은 숨김 |

## 9. 테스트

- `FinancialRatios`: 영업이익률·부채비율·ROE 계산과 반올림(정확히 0.5 경계), 분모 0이면 `Optional.empty`.
- 배당: TTM 합 계산(정확히 12개월 경계 — 366일 전 지급분은 제외), 주기 추론(분기 4건·반기 2건·연 1건·월 12건, 특별배당이 섞이면 반올림), 평균 연 수익률(5년 미만이면 있는 해만), 액면분할 조정.
- DART 어댑터 학습 테스트(WireMock): 전체 재무제표 응답에서 표준 항목 추출(제조업·금융업 샘플), 정정본 버전 처리, `013` 결과 없음, `020` 한도 초과.
- EDGAR company facts 태그 매핑 샘플.
- 서랍 규칙(프론트 테스트): 오른쪽 열림 시 왼쪽 닫힘, 둘 다 열렸을 때 밀림 폭 = 합, 종목 변경 시 탭 유지.

## 10. 열린 항목

**사용자 결정**
1. 탭 다섯과 각 탭의 항목(3장) 승인. 추가 후보: 지분(대량보유·임원 매매, DART DS004), 주주 구성, 실적 컨센서스(외부 소스 필요). ETF 구성종목 탭은 2026-09-25 구성 확정.
2. 마지막 탭 기억 여부.
3. 배당 정보를 F7 제외 필터(배당락 직전 매수 회피 등)에 쓸지 — 지금은 정보만.

**외부 확인**
1. DART 엔드포인트명·파라미터(구현 시 키로 학습 테스트). 배당 API의 분기 배당 표현 방식.
4. ETF 구성종목 출처: KRX Open API의 납부자산구성내역(PDF) 제공 여부와 갱신 시각, 운용사별 CSV 형식, 미국 ETF의 N-PORT 지연·운용사 공시 이용 조건.
2. 토스 종목 정보에 업종(KRX)·GICS 필드가 있는지.
3. ~~미국 배당 캘린더 출처~~ → Massive dividends로 해결(2026-09-25). 남은 것: Massive 약관의 저장·표시 조건.
