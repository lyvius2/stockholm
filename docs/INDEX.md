# INDEX.md — 문서 지도 (Stockholm 설계 문서)

갱신: 2026-09-25. 이 저장소의 설계 문서가 어디에 무엇이 있고 어떤 순서로 읽는지 정리한 색인이다. **문서를 새로 만들거나 이름을 바꾸면 이 파일을 함께 고친다.**

## 1. 문서의 층

```
AGENTS.md          다른 코딩 에이전트용 입구. 규칙은 없고 CLAUDE.md를 가리킴
CLAUDE.md          작업 규칙 (절대 규칙 · 코딩 규칙 · Git · 명령어)        ← 모든 작업의 출발점
PROJECT.md         기준 문서 (목적 · 결정 D1~D21 · 기능 F1~F19 · 자동화 규칙 · 화면 · 개발 단계)
docs/HANDOFF.md    세션 인수인계 (지금 상태 · 다음 작업 · 미결)             ← 세션 시작·종료 때
docs/DEVELOPMENT_PLAN.md   단계별 개발 순서와 완료 기준
docs/*_DESIGN.md 등        주제별 설계 (아래 3장)
docs/screens/              화면 설계서 HTML (아티팩트 사본)
```

결정의 우선순위는 **CLAUDE.md > PROJECT.md > 각 설계 문서**다. 설계 문서가 PROJECT.md와 다르면 PROJECT.md를 먼저 고치고 설계 문서를 맞춘다.

## 2. 읽는 순서

**처음 합류할 때** (반나절)

1. [CLAUDE.md](../CLAUDE.md) — 절대 규칙 8개는 외운다.
2. [PROJECT.md](../PROJECT.md) 1~7장 — 무엇을 왜 만드는지, 결정 표(D1~D21).
3. [HANDOFF.md](HANDOFF.md) — 지금 어디까지 왔는지.
4. [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md) — 지금 단계에서 무엇을 어떤 순서로 하는지.
5. [CORE_DOMAIN.md](CORE_DOMAIN.md) — 값 객체·이벤트·포트·가드레일. 코드를 쓰기 전 필수.
6. [DB_SCHEMA.md](DB_SCHEMA.md) — 표와 형식 규칙.
7. 나머지는 맡은 기능의 문서만(3장 표).

**세션을 시작할 때**: HANDOFF → DEVELOPMENT_PLAN의 현재 단계 → 그 단계의 "읽을 문서". **끝낼 때**: HANDOFF 갱신.

## 3. 주제별 문서

| 문서 | 다루는 것 | 상태 | 언제 읽는가 |
|---|---|---|---|
| [CORE_DOMAIN.md](CORE_DOMAIN.md) | 값 객체(`Money`·`Quantity`·`Symbol`…), 주문·lot·노출액, 가드레일 규칙, 이벤트 타입, 포트 시그니처, 예외, 필수 테스트 | 확정 | `core` 코드 작업 전 |
| [DB_SCHEMA.md](DB_SCHEMA.md) | 표 93개(성격 ①②③④), 형식 자리표시자, FK 정책, 인덱스, Flyway 버전, relay DB | 확정 2026-09-25 | 엔티티·마이그레이션 작업 전 |
| [KEY_MANAGEMENT.md](KEY_MANAGEMENT.md) | 키 분류(공유/개인), 변경 규칙, Keychain 저장, 다른 디바이스로 전달 | 확정 | 인증·설정·비밀값 작업 전 |
| [FIRST_RUN_DESIGN.md](FIRST_RUN_DESIGN.md) | 최초 구동 마법사 4단계, `SetupState`, 키 검증 호출, 조회 제한 모드, 로그인 모달, 시작 종목 규칙(F19) | 확정 2026-09-25 | 1~2단계 |
| [EXTERNAL_APIS.md](EXTERNAL_APIS.md) | 외부 API 카탈로그(토스·금융결제원·DART·EDGAR·KRX·Massive·네이버·NPS·Slack), 한도·제약·약관 | 갱신 중 | 어댑터 작업 전. 구현 기준은 항상 공식 문서 |
| [ORDER_MANAGEMENT_DESIGN.md](ORDER_MANAGEMENT_DESIGN.md) | 체결 현황·미체결 정정·취소, 상태 매핑, 정정 체인, 한도 초과 확인 창(F14) | 확정 | 2단계 주문 |
| [PORTFOLIO_PANEL_DESIGN.md](PORTFOLIO_PANEL_DESIGN.md) | 보유주식 평가금액 패널(F18) | 확정 | 2단계 |
| [TRADE_HISTORY_DESIGN.md](TRADE_HISTORY_DESIGN.md) | 거래내역 패널(F20): 손익(F2)·체결내역(F14 주문 내역)·매매내역, 기간 단위, 선입선출 실현손익 계산, `lot_disposal` | 확정 2026-09-25 | 2단계 |
| [ACCOUNT_SETTINGS_DESIGN.md](ACCOUNT_SETTINGS_DESIGN.md) | 사용자 메뉴 세 화면(F21): 회원정보 변경(기본·로그인 수단·연결 키·보안), 알림 설정(채널×항목·방해 금지), 회원 관리·공유 키(admin, step-up) | 제안 2026-09-25 | 1~2단계 |
| [MARKET_INDEX_TICKER_DESIGN.md](MARKET_INDEX_TICKER_DESIGN.md) | 상단 바 지수 티커(F23): 장별 지수 3개, 포맷, 세트 선택 규칙, 5분 갱신·슬라이딩, 출처 | 제안 2026-09-25 | 2단계 |
| [LLM_ROUTE_SETTINGS_DESIGN.md](LLM_ROUTE_SETTINGS_DESIGN.md) | LLM 경로 설정 모달(F22): 카테고리 5개(토론·리포트·요약·거래 판단·추천·번역)마다 주·대체 LLM, 등록 제공자만 선택, 프리셋, 고급에서 목적별, 검증, 이력 | 제안 2026-09-25 | 4단계 |
| [ASSET_DESIGN.md](ASSET_DESIGN.md) | 사용자 메뉴, 금융결제원 자산 조회 모달, 동의 흐름(F16) | 확정 | 2단계 |
| [RAG_DESIGN.md](RAG_DESIGN.md) | Lucene 하이브리드, 기준 시점 `asOf`, 코퍼스 구분, 보관 규칙 | 제안 | 3단계 |
| [DART_DESIGN.md](DART_DESIGN.md) | 공시 감시·재무·배당·기업개황, 공시 유형→조치 표 | 확정 | 3단계 |
| [EDGAR_DESIGN.md](EDGAR_DESIGN.md) | SEC submissions·companyfacts·frames, 서식→조치 | 확정 | 3단계 |
| [KRX_DESIGN.md](KRX_DESIGN.md) | KRX Open API 8종, 배치, 백필 | 확정 | 2~3단계 |
| [NPS_HOLDINGS_DESIGN.md](NPS_HOLDINGS_DESIGN.md) | 국민연금 해외투자 현황(13F + 공공데이터포털), 감지·푸시·매핑(F13) | 확정 | 3단계 |
| [STOCK_INFO_DESIGN.md](STOCK_INFO_DESIGN.md) | 종목 정보 서랍 5탭 + ETF 구성종목(보류)(F15) | 확정(ETF 보류) | 3단계 |
| [LLM_ROUTING.md](LLM_ROUTING.md) | 목적별 다중 LLM, 프리셋, 폴백, 개인정보 등급, 예산 | 확정 | LLM 호출 작업 전(4단계) |
| [DEBATE_DESIGN.md](DEBATE_DESIGN.md) | 토론 세 테마, 개요·전망, DebateSession 저장·재개, 메신저 UI, 실시간 시세 파이프라인 | 확정 | 4단계 |
| [MIROFISH_EXPERIMENT_GUIDE.md](MIROFISH_EXPERIMENT_GUIDE.md) | 미러피시 사전 실험 절차 | 안내 | 4·9단계 전 실험 |
| [screens/MAIN_SCREEN_DESIGN.html](screens/MAIN_SCREEN_DESIGN.html) | 메인 화면·서랍·모달·마법사 목업(아티팩트 사본). 원본은 claude.ai 아티팩트, Figma 반영은 `figma-plugin/`(gitignored) | v34 | 화면 작업 전 |

## 4. 기능 → 문서

| 기능 | 주 문서 | 보조 |
|---|---|---|
| F1 매수·매도 | PROJECT 9장 F1, [CORE_DOMAIN](CORE_DOMAIN.md) 4·7장 | [ORDER_MANAGEMENT](ORDER_MANAGEMENT_DESIGN.md) |
| F2·F17 손익·매매 통계 | F2는 [TRADE_HISTORY_DESIGN](TRADE_HISTORY_DESIGN.md) 손익 탭, F17은 화면 설계 후속 | [DB_SCHEMA](DB_SCHEMA.md) 7장 `broker_order`·`lot`·`lot_disposal` |
| F3 차트 | PROJECT F3, [DEBATE_DESIGN](DEBATE_DESIGN.md) 8장(실시간) | [DB_SCHEMA](DB_SCHEMA.md) 6장 `candle` |
| F4 종목 조회·관심종목 | PROJECT F4·11.3 | [DB_SCHEMA](DB_SCHEMA.md) 5·6장 |
| F5 추천 | PROJECT F5 | [RAG_DESIGN](RAG_DESIGN.md), [LLM_ROUTING](LLM_ROUTING.md) |
| F6 토론 | [DEBATE_DESIGN](DEBATE_DESIGN.md) | [RAG_DESIGN](RAG_DESIGN.md), [LLM_ROUTING](LLM_ROUTING.md), [MIROFISH](MIROFISH_EXPERIMENT_GUIDE.md) |
| F7·F8 자동 매수·매도 | PROJECT 10장, [CORE_DOMAIN](CORE_DOMAIN.md) 6·7장 | [DB_SCHEMA](DB_SCHEMA.md) 8장 |
| F9 시장 리포트 | PROJECT F9 | [LLM_ROUTING](LLM_ROUTING.md) |
| F10 학습 | PROJECT F10, [DEBATE_DESIGN](DEBATE_DESIGN.md) 6장 | [RAG_DESIGN](RAG_DESIGN.md) 4.1 |
| F11 실시간 급등락 | PROJECT F11·8.1 | — |
| F12 커뮤니티 | PROJECT F12, [EXTERNAL_APIS](EXTERNAL_APIS.md) 2.7·3장 | [RAG_DESIGN](RAG_DESIGN.md) 4.3 |
| F13 연기금종목 | [NPS_HOLDINGS_DESIGN](NPS_HOLDINGS_DESIGN.md) | [EDGAR_DESIGN](EDGAR_DESIGN.md) |
| F14 주문 관리 | [ORDER_MANAGEMENT_DESIGN](ORDER_MANAGEMENT_DESIGN.md) | [CORE_DOMAIN](CORE_DOMAIN.md) 4장 |
| F15 종목 정보 | [STOCK_INFO_DESIGN](STOCK_INFO_DESIGN.md) | [DART](DART_DESIGN.md), [EDGAR](EDGAR_DESIGN.md), [KRX](KRX_DESIGN.md) |
| F16 자산 조회 | [ASSET_DESIGN](ASSET_DESIGN.md) | [KEY_MANAGEMENT](KEY_MANAGEMENT.md) |
| F18 평가금액 패널 | [PORTFOLIO_PANEL_DESIGN](PORTFOLIO_PANEL_DESIGN.md) | — |
| F20 거래내역 패널 | [TRADE_HISTORY_DESIGN](TRADE_HISTORY_DESIGN.md) | [ORDER_MANAGEMENT](ORDER_MANAGEMENT_DESIGN.md) |
| F21 계정·알림·회원 관리 | [ACCOUNT_SETTINGS_DESIGN](ACCOUNT_SETTINGS_DESIGN.md) | [KEY_MANAGEMENT](KEY_MANAGEMENT.md), [LLM_ROUTING](LLM_ROUTING.md) 8장 |
| F22 LLM 경로 설정 | [LLM_ROUTE_SETTINGS_DESIGN](LLM_ROUTE_SETTINGS_DESIGN.md) | [LLM_ROUTING](LLM_ROUTING.md) 4·6·10장 |
| F23 지수 티커 | [MARKET_INDEX_TICKER_DESIGN](MARKET_INDEX_TICKER_DESIGN.md) | [EXTERNAL_APIS](EXTERNAL_APIS.md) 1.1·2.3 |
| F19 최초 구동·로그인·시작 종목 | [FIRST_RUN_DESIGN](FIRST_RUN_DESIGN.md) | [KEY_MANAGEMENT](KEY_MANAGEMENT.md) |

## 5. 단계 → 문서

[DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)의 각 단계에 "읽을 문서"가 있다. 요약:

| 단계 | 읽을 문서 |
|---|---|
| 1 리포 골격 | CLAUDE, CORE_DOMAIN, DB_SCHEMA 3·4·5장, FIRST_RUN, KEY_MANAGEMENT, ACCOUNT_SETTINGS 3.2 |
| 2 토스·F1~F4·F14·F16·F18·F19·F20·F21 | EXTERNAL_APIS 1.1·1.2, ORDER_MANAGEMENT, PORTFOLIO_PANEL, TRADE_HISTORY, ASSET, ACCOUNT_SETTINGS, FIRST_RUN, KRX |
| 3 수집·RAG·F12·F13·F15 | RAG, DART, EDGAR, KRX, NPS, STOCK_INFO, EXTERNAL_APIS 2·3장 |
| 4 토론·추천·리포트 | LLM_ROUTING, LLM_ROUTE_SETTINGS, DEBATE, MIROFISH 실험 |
| 5 학습 | PROJECT F10, DEBATE 6장 |
| 6 자동화 | PROJECT 10장, CORE_DOMAIN 6·7장, DB_SCHEMA 8장 |
| 7 relay | PROJECT 3·5·7장, DB_SCHEMA 11장 |
| 8 동기화·lease | PROJECT 6장, CORE_DOMAIN 9·10장 |
| 9 미러피시·완전 자동 | DEBATE 7장, MIROFISH |

## 6. 문서 규약

- 머리말은 `작성: 날짜 / 상태: [확정|제안|확인 필요] — 한 줄 요약 / 관련: 링크`. 상태 표기는 PROJECT.md와 같다.
- 다른 문서는 **상대 경로 링크**로 가리킨다(`[CORE_DOMAIN.md](CORE_DOMAIN.md)`). 절 번호는 바뀌므로 링크 뒤에 "N장"을 붙이되 본문 근거는 그 자리에 요약한다(CLAUDE.md 주석 규칙과 같은 이유).
- 결정이 바뀌면 PROJECT.md → 설계 문서 → HANDOFF 순서로 고친다. 설계 문서끼리 어긋나면 이 색인의 "주 문서"가 이긴다.
- 저장소 루트의 `README.md`는 프로젝트 소개용이고, 이 파일(`docs/INDEX.md`)이 설계 문서 색인이다.
- 문서를 추가하면 이 파일의 3~5장, CLAUDE.md의 "문서" 절, HANDOFF에 한 줄씩 넣는다.
