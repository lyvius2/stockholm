# SEC EDGAR 어댑터 규격 — 미국 공시·재무 가져오기

작성: 2026-09-24 / 상태: **[제안]** (규격은 실측 [확인함] / [확인 필요] 표기) / 관련: `docs/EXTERNAL_APIS.md` 2.6, `docs/STOCK_INFO_DESIGN.md`(F15), `docs/NPS_HOLDINGS_DESIGN.md`(13F), `docs/RAG_DESIGN.md` 4.3, `docs/CORE_DOMAIN.md` 10장(`DisclosurePort`, `FundamentalsPort`)

## 1. 결론 요약

- 미국 종목의 공시·재무는 **SEC EDGAR 하나**에서 온다. 무료, 회원가입·키 없음, 공공 데이터라 저장·RAG 투입 제약이 없다.
- 호스트가 둘이고 정책이 다르다. **`data.sec.gov`(JSON API)** 는 연락처 없는 User-Agent도 받아 주었고, **`www.sec.gov`(파일·Archives·cgi-bin)** 는 연락처 없는 요청을 403으로 막는다(2026-09-24 실측). 그래서 User-Agent는 **항상 `앱이름/버전 (연락처 이메일)`** 형식으로 보내고, 이메일은 admin 공유 설정에서 읽는다(13F와 같은 항목).
- 공시 감시는 **종목별 제출 목록 JSON**을 1~2분 간격으로 돌려 새 접수번호를 찾는 폴링이다. 재무는 **company facts JSON**(XBRL 태그별 값)에서 뽑는다. 원문은 접수번호 디렉터리에서 받는다.
- 시각은 접수 시각(`acceptanceDateTime`, UTC)이 초 단위로 오므로 장중·장후 구분이 되지만, 기준 시점 원칙대로 `firstSeenAt`을 함께 저장한다.

## 2. 공통 규칙 [확인함]

| 항목 | 값 |
|---|---|
| 호스트 | `https://data.sec.gov`(submissions, xbrl API), `https://www.sec.gov`(company_tickers.json, Archives 원문·index.json, cgi-bin 피드) |
| 인증 | 없음 |
| User-Agent | **필수.** `Stockholm/{버전} ({admin 연락처 이메일})`. 없으면 `www.sec.gov`는 "Request Rate Threshold Exceeded" 403(본문 gzip HTML). 이메일이 설정에 없으면 EDGAR 호출을 건너뛰고 사유를 표시한다 |
| 헤더 | `Accept: application/json`, `Accept-Encoding: gzip`(응답은 gzip으로 온다), `Host` 명시 |
| 한도 | **초당 10회**(SEC 공정 접근 정책). 어댑터에 토큰 버킷(초당 8회로 보수적) + 429/403 시 지수 백오프. 대량 내려받기(첫 적재)는 미국 동부 야간(21:00~06:00 ET)에 |
| CIK | 10자리 0 채움(`CIK0000320193`). 접수번호는 `0001140361-26-037020`, Archives 경로에서는 하이픈 제거 |
| 응답 크기 | submissions 160KB 안팎(최근 1,000건), companyfacts 수 MB(Apple 3.8MB). 캐시 필수 |
| 시각 | `acceptanceDateTime`은 ISO-8601 UTC(`2026-09-17T22:30:24.000Z`). 제출일 `filingDate`는 미국 동부 날짜 |

## 3. 엔드포인트

| 용도 | 요청 | 상태 |
|---|---|---|
| 티커 → CIK | `GET https://www.sec.gov/files/company_tickers.json` → `{"0":{"cik_str":320193,"ticker":"AAPL","title":"Apple Inc."},…}`. 일 1회 내려받아 `symbol ↔ cik` 표 갱신 | 형식은 알려진 것, 이번엔 UA 정책으로 403 [확인 필요: 연락처 UA로 재확인] |
| 종목별 제출 목록 | `GET https://data.sec.gov/submissions/CIK{10자리}.json` | [확인함] 4장 |
| 이전 제출(1,000건 초과분) | 위 응답의 `filings.files[].name`(예: `CIK0000320193-submissions-001.json`)을 같은 호스트에서 | [확인함] 파일명만 |
| 재무 전체 | `GET https://data.sec.gov/api/xbrl/companyfacts/CIK{10자리}.json` | [확인함] 5장 |
| 재무 한 태그 | `GET https://data.sec.gov/api/xbrl/companyconcept/CIK{10자리}/us-gaap/{Tag}.json` | 알려진 형식, 미실측 |
| 전 종목 한 분기 값 | `GET https://data.sec.gov/api/xbrl/frames/us-gaap/{Tag}/USD/CY2025Q4.json` → `data[{accn,cik,entityName,loc,start,end,val}]` | [확인함] F5 미국 스크리닝 후보(379개 회사가 한 응답에) |
| 접수 문서 목록 | `GET https://www.sec.gov/Archives/edgar/data/{cik}/{접수번호 하이픈 제거}/index.json` → `directory.item[{name,type,size,last-modified}]` | 알려진 형식, UA 403 [확인 필요] |
| 원문 | `https://www.sec.gov/Archives/edgar/data/{cik}/{접수번호}/{primaryDocument}` (HTML·XML). `primaryDocument`는 제출 목록에 있음 | 동일 |
| 전체 시장 최신 제출 | `GET https://www.sec.gov/cgi-bin/browse-edgar?action=getcurrent&type=8-K&count=100&output=atom` | 보조용, UA 403 [확인 필요: 갱신 지연·중복] |

## 4. 제출 목록 JSON [확인함 2026-09-24, Apple CIK 320193]

```
{
  cik, entityType, sic:"3571", sicDescription, name:"Apple Inc.", tickers:["AAPL"], exchanges:["Nasdaq"],
  fiscalYearEnd:"0926", stateOfIncorporation, formerNames[], …,
  filings: {
    recent: {   // 열 단위 배열 1,000건, 최신순. i번째 값끼리 한 제출
      accessionNumber[], filingDate[], reportDate[], acceptanceDateTime[], act[], form[], fileNumber[], filmNumber[],
      items[],            // 8-K의 Item 번호 목록 "2.02,9.01" — 실적 발표·사건 분류의 열쇠
      core_type[], size[], isXBRL[], isInlineXBRL[], isXBRLNumeric[], primaryDocument[], primaryDocDescription[]
    },
    files: [{ name, filingCount, filingFrom, filingTo }]   // 1,000건 이전 이력 파일
  }
}
```

실측 예: `4 | 2026-09-17 | 2026-09-15 | 2026-09-17T22:30:24.000Z | 0001140361-26-037020 | xslF345X06/form4.xml | FORM 4`.

**감시 알고리즘.**
1. 관심·보유·자동 매수 후보 종목의 CIK 집합을 만든다(F4 종목 마스터 + `symbol ↔ cik`).
2. 1~2분마다 CIK별 submissions JSON을 받는다(30종목이면 분당 30회, 한도 안). `If-Modified-Since`/`ETag`가 오면 쓴다 [확인 필요].
3. `recent.accessionNumber[]`에서 저장에 없는 것을 새 공시로 등록: `(cik, accessionNumber, form, filingDate, reportDate, acceptanceDateTime, items, primaryDocument, firstSeenAt=now)`.
4. `form`과 `items`로 분류해 이벤트를 낸다(6장). 원문·RAG 색인은 별도 큐(색인 완료를 기다리지 않는다).
5. 같은 접수번호는 두 번 등록하지 않는다. 정정(`10-K/A`, `8-K/A`)은 새 접수번호이므로 새 문서로 넣고 원본과 `supersedes`로 연결(같은 `reportDate`·서식).

## 5. Company facts JSON [확인함]

```
{ cik, entityName, facts: { dei: {...}, "us-gaap": { <Tag>: { label, description, units: { USD | USD/shares | shares: [
    { start?, end, val, accn, fy, fp, form, filed, frame? } ] } } } } }
```

실측(Apple): us-gaap 태그 503개. `RevenueFromContractWithCustomerExcludingAssessedTax`의 최근 두 행 — `end 2026-06-27, fy 2026, fp Q3, form 10-Q, val 364,357,000,000`(frame 없음, **회계연도 누적 9개월**)과 `val 109,417,000,000, frame CY2026Q2`(**분기 값**). `StockholdersEquity`는 `frame CY2026Q2I`(끝에 `I` = 시점 값).

**F15 재무제표로 뽑는 규칙.**
- **분기 값은 `frame`으로 고른다.** `CYyyyyQn`이 붙은 행이 그 달력 분기의 값이다. `frame`이 없는 10-Q 행은 회계연도 누적치라 그대로 쓰면 틀린다. `frame`이 없을 때만 `start`~`end` 기간(약 90일)으로 판별한다.
- **연간 값은 `fp == FY`이고 `form == 10-K`인 행**. 회계연도 종료월은 submissions의 `fiscalYearEnd`(Apple `0926`)로 안다. 표에는 회계연도(FY2025)로 표기하고 달력 연도와 다름을 툴팁으로.
- **시점 값(재무상태표)**: `frame`이 `…I`로 끝나는 행. 부채비율은 `Liabilities / StockholdersEquity`.
- **태그 대체 순서**(회사마다 쓰는 태그가 다르다): 매출 `Revenues → RevenueFromContractWithCustomerExcludingAssessedTax → SalesRevenueNet`, 영업이익 `OperatingIncomeLoss`, 매출총이익 `GrossProfit`(없으면 매출 − `CostOfRevenue`), 순이익 `NetIncomeLoss`, EPS `EarningsPerShareDiluted`(단위 `USD/shares`), 자본 `StockholdersEquity`, 부채 `Liabilities`(없으면 `LiabilitiesAndStockholdersEquity − StockholdersEquity`). 대체 표는 어댑터 안에 두고 학습 테스트로 고정.
- **배당(F15 배당 탭)**: `CommonStockDividendsPerShareDeclared`(`USD/shares`, Apple 분기 0.27). 선언액과 분기만 알 수 있고 **기준일·배당락일·지급일은 없다** → 8-K Item 8.01 본문 또는 별도 소스 [확인 필요].
- 단위: `USD` 정수(달러). 화면은 백만 달러로 `BigDecimal` 나눗셈 후 `RoundingMode.HALF_EVEN`.
- 같은 `(tag, end, frame)`에 `accn`이 다른 행이 여럿이면(정정·재표시) **`filed`가 가장 늦은 행**을 최신본으로, 나머지는 판본으로 보관.

## 6. 서식 → 우리 용도 (분류 표는 `core`에 둔다)

| 서식 | 뜻 | 조치 |
|---|---|---|
| `8-K` | 수시 보고. `items`로 세분: `2.02` 실적 발표, `1.01` 중요 계약, `2.01` 인수·처분 완료, `3.01` 상장 기준 미달·상장폐지 통지, `3.02` 미등록 증권 발행(증자), `5.02` 임원·이사 변경, `8.01` 기타(배당 선언 등), `9.01` 첨부 | `3.01`·`3.02`·회생(`1.03`)은 **가드레일 이벤트**(자동 매수 제외, 보유 시 알림). `2.02`는 토론 자동 실행 후보. 전부 F15 공시 탭 |
| `10-K`, `10-Q`(`/A`) | 연간·분기 보고서 | company facts 재적재 트리거, Item 1·1A·7 RAG 색인 |
| `20-F`, `6-K` | 외국 발행사·ADR | 10-K/8-K에 준함. `6-K`는 `items` 없음 |
| `4`, `3`, `5` | 내부자 매매·보유 | 지분 신호(가치투자자·역발상 자료). 잦으므로 RAG 색인 안 함 |
| `SC 13D`, `SC 13G`(`/A`) | 5% 이상 보유 | 지분 신호 |
| `S-1`, `424B*` | 신규 상장·증권 발행 | 신규 상장 직후 제외 필터 근거 |
| `DEF 14A` | 주주총회 | 참고 |
| `144` | 제한 주식 매도 신고 | 참고(잦음) |

## 7. 저장

| 표 | 열 | 비고 |
|---|---|---|
| `edgar_entity` | `cik, symbol_code, name, sic, fiscal_year_end, updated_at` | 티커 파일 + submissions 머리 |
| `us_disclosure` | `accession_no(PK), cik, form, items, filing_date, report_date, accepted_at, primary_document, size, supersedes_accession, first_seen_at, indexed_at` | 공시 감시 결과. F15 공시 탭·가드레일 이벤트 원천 |
| `us_financial_fact` | `cik, tag, unit, end_date, start_date, frame, fy, fp, form, accession_no, filed, val` | company facts 정규화. `(cik, tag, unit, end_date, frame, accession_no)` 유일 |

F15의 `financial_statement`(`docs/STOCK_INFO_DESIGN.md` 6장)는 위 표에서 계산한 결과를 접수번호 단위 버전으로 담는다.

## 8. 오류와 fail-safe

| 상황 | 동작 |
|---|---|
| 403 + "Request Rate Threshold Exceeded" | UA 정책 위반 또는 초과. 10분 정지 후 재시도, 3회 연속이면 admin 알림(연락처 이메일 설정 확인 안내) |
| 404 | CIK 없음(비상장·등록 안 됨) → 종목에 "EDGAR 등록 없음" 표시, 감시 목록에서 제외 |
| 429 | `Retry-After` 준수 |
| 응답 크기 급증(companyfacts) | 스트리밍 파서로 필요한 태그만 뽑는다. 전체를 메모리에 올리지 않는다 |
| 태그 없음 | 해당 항목 빈칸("—"), 대체 태그 시도 기록 |
| 감시 실패 | 알림 없음, 상태줄 "EDGAR 마지막 확인 hh:mm" 갱신 안 됨. 주문 경로와 무관 |

## 9. 테스트

- 학습 테스트(WireMock에 실측 응답 저장): submissions 1건, companyfacts 축약본(Apple 태그 9개), frames 1건, 403 HTML(gzip), 404.
- 분기 값 선택: `frame` 있는 행 우선, 없으면 90일 기간 판별. Apple 2026 Q3 10-Q에서 분기 매출 109,417M을 고르고 누적 364,357M을 버리는지.
- 회계연도 표기: `fiscalYearEnd 0926`인 회사의 `fp FY` 행을 FY2025로.
- 정정 판본: 같은 `(tag,end,frame)`에 `filed`가 다른 두 행 → 최신본 선택, 이전 판본 보존.
- 감시: 같은 접수번호 재수신 시 0건, 새 접수번호 1건, `8-K items 3.01` → 가드레일 이벤트 1건.
- UA 조립: 이메일 없으면 호출하지 않고 `SecretMissing`이 아니라 설정 부재 예외(비밀값이 아님).

## 10. 열린 항목

1. `www.sec.gov` 계열(티커 파일, Archives index.json, 원문, Atom 피드)을 연락처 UA로 재확인. 이번 실측은 `data.sec.gov`만 통과했다.
2. submissions JSON의 `ETag`/`If-Modified-Since` 지원 여부(폴링 비용 절감).
3. ~~배당 기준일·지급일 출처~~ → Massive `/v3/reference/dividends`로 해결(2026-09-25, `docs/EXTERNAL_APIS.md` 2.9).
4. `6-K`(ADR)에는 `items`가 없어 분류가 어렵다 — 제목(`primaryDocDescription`) 키워드로 보조할지.
5. F5 미국 스크리닝에 frames API를 쓸지(한 호출로 전 종목 한 분기 값).
