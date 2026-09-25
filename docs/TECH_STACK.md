# 기술 스택 (기술 설계 2)

> 문서 지도: [docs/INDEX.md](INDEX.md) · 기준 문서: [PROJECT.md](../PROJECT.md) · 작업 규칙: [CLAUDE.md](../CLAUDE.md) · 개발 순서: [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md)

작성: 2026-09-25 / 상태: **[제안]** — PROJECT 11.1(스택 한 줄)·11.2(빌드·네이티브 방침)·CLAUDE.md 기술 기준(OpenJDK 25, Kotlin, preview 금지, AGPL/GPL 금지)을 항목별로 내린 것. **버전은 2026-09 기준 최신 안정판을 적었고 1단계 착수 때 재확인한다.** 새 의존성을 더할 때는 이 표에 이유·라이선스·네이티브 호환을 적는다(CLAUDE.md 작업 방식) / 관련: [DIRECTORY_STRUCTURE.md](DIRECTORY_STRUCTURE.md), [DB_SCHEMA.md](DB_SCHEMA.md) 3장, [RAG_DESIGN.md](RAG_DESIGN.md), [LLM_ROUTING.md](LLM_ROUTING.md)

## 0. 사용자 확정 스펙 (2026-09-26)

| 항목 | 결정 |
|---|---|
| Spring Boot | **4.x** (Spring Framework 7, Jakarta EE 11). Modulith·Spring AI·Resilience4j는 4.x 짝 버전 [착수 시 확인] |
| 언어 | **Kotlin**(2.x, JDK 25 타깃). **코루틴 금지**, 비동기·병렬은 **가상 스레드**, 동시 실행 수 제어는 **`Semaphore`**(Kotlin `@Configuration`으로 빈 등록) |
| 영속성 | **JPA + jOOQ(코드 생성 없음)**. jOOQ는 **Join 쿼리와 Bulk INSERT/UPDATE에만**, 나머지는 JPA. 리포지터리 메서드 이름이 **16자를 넘으면 JPQL `@Query`** |
| HTTP 클라이언트 | **Retrofit2**(OkHttp) [확정 2026-09-26, RestClient에서 변경]. 엔드포인트(URL) 그룹별로 인터페이스 하나 + **빈 하나**(FeignClient 방식). JDK 25라 OkHttp의 `synchronized` pinning 문제 없음 |
| 회복성 | **Resilience4j** 서킷 브레이커(+ RateLimiter·Bulkhead·Retry·TimeLimiter). **fallback 메서드 필수**(ArchUnit로 강제). 더 맞는 것이 있으면 제안 → 2장 비고 |
| 포트 | 데몬 로컬 API **2609**(`127.0.0.1`만) |
| 캐시 | 마법사 또는 admin이 **Valkey/Redis 접속 주소를 등록하고 연결에 성공하면 그것**, 아니면 **로컬 메모리 캐시**(Caffeine). 런타임 전환 |
| 아키텍처 | **Hexagonal, 계층별 패키지**. 루트 패키지 **`banghak.stock`**: `core`(domain · usecase · port) / `engine`·`relay`(application · adapter/in · adapter/out · config) / `shared`. 도메인별 패키지 분할은 **취소**(2026-09-26) ([DIRECTORY_STRUCTURE.md](DIRECTORY_STRUCTURE.md)) |

## 1. 원칙

- **표준 라이브러리와 Spring이 주는 것을 먼저 쓴다.** HTTP는 JDK `HttpClient`, JSON은 Jackson, 스케줄은 Spring, 동시성은 가상 스레드. 외부 라이브러리는 "직접 쓰면 수백 줄 이상"일 때만.
- 라이선스는 Apache-2.0 · MIT · BSD · EPL만. AGPL/GPL은 별도 프로세스로만(미러피시).
- GraalVM 네이티브는 relay 단독 빌드에서만 검토하므로(PROJECT 11.2), 데몬 쪽은 JIT 전제. 다만 리플렉션·동적 프록시 남용 라이브러리는 피한다.
- 프론트는 금액을 `number`로 계산하지 않으므로 decimal 라이브러리를 두지 않는다(표시 포맷만).

## 2. 런타임·빌드

| 영역 | 선택 | 버전(기준) | 라이선스 | 비고 |
|---|---|---|---|---|
| JDK | **GraalVM for JDK 25 (LTS)** [확정 2026-09-26] | 25.0.x | **배포판 결정 필요**: GraalVM **Community**(GPLv2+CE, OpenJDK 기반, dmg 재배포 자유) vs **Oracle GraalVM**(GFTC — 무료이지만 재배포 조건 확인 필요) → jlink JRE를 dmg에 넣으므로 **Community 제안** | JIT는 Graal 컴파일러(C2 대비 피크 성능 유리한 경우 있음), 가상 스레드 pinning 없음(JEP 491). 네이티브 이미지 도구는 함께 오지만 데몬은 JVM 모드(PROJECT 11.2), relay 단독 빌드에서만 검토. preview 금지. 1단계 첫날 Kotlin `jvmTarget=25`·Gradle·jlink 동작 확인, 안 맞으면 21로 임시 후퇴 |
| 빌드 | **Gradle Kotlin DSL** + 버전 카탈로그 | 9.x(JDK 25 실행 지원 버전) | Apache-2.0 | toolchain으로 GraalVM JDK 25 고정(`vendor = GRAAL_VM`). 단일 모듈(D3) |
| 포맷·정적 검사 | **Spotless + ktfmt(kotlinlang 스타일)**, **detekt**(선택) | — | Apache-2.0 | 형식 논쟁 없음(CLAUDE.md). ktfmt는 Kotlin용 자동 포맷터(google-java-format의 Kotlin판)로 스타일 프리셋이 둘: `kotlinlang`(공식 컨벤션, 4칸) / `google`(2칸). **kotlinlang 채택** [확정 2026-09-26] |
| Node | **Node 22 LTS** | 22.x | MIT | Electron·Vite·생성 스크립트 |
| 패키지 관리 | npm(lockfile 커밋) | — | — | pnpm 전환 여지 |
| 배포 | **jlink JRE + bootJar + electron-builder → dmg** | — | MIT(electron-builder) | `scripts/dist.sh`. 서명·공증은 배포 시점 결정 |
| CI | GitHub Actions(macOS 러너) | — | — | `./gradlew build`(learning 태그 제외) + `npm test` + dmg 산출(태그 시) |

## 3. 백엔드 (`backend/`)

| 영역 | 선택 | 버전(기준) | 라이선스 | 이유·비고 |
|---|---|---|---|---|
| 프레임워크 | **Spring Boot 4.x** [확정 2026-09-26] | 4.x 최신 패치 | Apache-2.0 | 프로필 `engine`/`relay`, 생성자 주입만. Spring Framework 7 |
| 언어 | **Kotlin** [확정 2026-09-26] | 2.x(`jvmTarget=25`, 지원 버전 확인) | Apache-2.0 | `kotlin-spring`·`kotlin-jpa`(allopen·noarg) 플러그인, `jackson-module-kotlin`. data class·value class·sealed interface. 코루틴 없음 |
| 모듈 경계 | **Spring Modulith** + **ArchUnit** | Modulith 2.x, ArchUnit 1.4.x | Apache-2.0 | 패키지 경계·프로필 빈 검증 테스트 |
| 웹 | **Spring MVC**(servlet) + 가상 스레드(`spring.threads.virtual.enabled=true`) | Boot 내장 Tomcat | Apache-2.0 | WebFlux 불필요. `127.0.0.1:2609` 바인딩 [포트 확정 2026-09-26] |
| 병렬·동시성 | 가상 스레드 `Executor` + **`java.util.concurrent.Semaphore`**(도메인별 상한 빈, Kotlin Config) | JDK 25 | — | 예: 토스 MARKET_DATA 15, LLM 병렬 4, 수집 배치 8. 코루틴 금지 |
| 로컬 WebSocket | **Spring WebSocket**(서버) | Boot 내장 | Apache-2.0 | 시세·체결·토론·알림 스트림 |
| 외부 HTTP | **Retrofit2 + OkHttp** [확정 2026-09-26] | Retrofit 3.x / OkHttp 5.x(또는 4.12) | Apache-2.0 | 인터페이스(`@GET`·`@POST`…) + `converter-jackson`. 엔드포인트 그룹별 인터페이스·빈(`engine/config/*HttpConfig`). 공통 `OkHttpClient` 하나(타임아웃·연결 풀·마스킹 로깅 인터셉터·User-Agent). 동기 `execute()`를 가상 스레드에서 호출(Call 어댑터 불필요). Spring 관측은 서비스 계층에서 |
| 회복성 | **Resilience4j** [확정 2026-09-26] | 2.3.x+ (Boot 4 짝 확인) | Apache-2.0 | `@CircuitBreaker(fallbackMethod)` 필수 · `@RateLimiter`(토스 그룹별 초당 한도) · `@Bulkhead`(SEMAPHORE) · `@Retry`(주문 제외) · `@TimeLimiter`. 대안 검토: Failsafe(경량이나 Spring 통합 없음), Spring Cloud CircuitBreaker(r4j 래퍼, 층만 늘어남) → **Resilience4j 유지 제안** |
| 외부 WebSocket(토스) | **OkHttp WebSocket**(같은 클라이언트) | OkHttp | Apache-2.0 | 재연결·하트비트·`OPEN` 재동기는 우리 코드 |
| JSON | **Jackson** | Boot 내장 | Apache-2.0 | `BigDecimal` 문자열 직렬화 설정(`shared/json`). core에는 애너테이션 금지 |
| 영속성 | **Spring Data JPA + Hibernate** [확정] | Boot 내장(Hibernate 7.x) | Apache-2.0(Hibernate 7) | 엔티티는 `engine`/`relay`의 `adapter/out/persistence`에만. 메서드 이름 16자 초과 → JPQL `@Query` |
| 질의 | **jOOQ(OSS, 코드 생성 없음)** [확정 2026-09-26] | 3.20.x | Apache-2.0(OSS 판, SQLite·MySQL 지원) | `DSL.table/field`로 동적 DSL. **Join 쿼리·Bulk INSERT/UPDATE에만**. JPA와 같은 DataSource·트랜잭션(`DataSourceConnectionProvider`) |
| SQLite JDBC | **xerial sqlite-jdbc** | 3.5x | Apache-2.0 | 네이티브 라이브러리 내장(arm64 포함). WAL·`foreign_keys` PRAGMA는 연결 초기화 |
| SQLite 방언 | **hibernate-community-dialects**(SQLiteDialect) | Hibernate와 동일 | Apache-2.0 | `${decimal}`은 TEXT라 방언 의존 최소 |
| 마이그레이션 | **Flyway**(community) | 11.x | Apache-2.0 | 자리표시자로 벤더 분기(DB_SCHEMA 3.2·3.3). relay는 별도 DataSource·이력 |
| MySQL(relay 옵션) | MySQL Connector/J | 9.x | GPLv2 + FOSS exception [확인 필요] → 대안 **MariaDB Connector/J**(LGPL) | 7단계에서 결정. 기본은 SQLite |
| 검색 | **Apache Lucene** + **lucene-analysis-nori** | 10.x | Apache-2.0 | BM25(Nori) + HNSW(int8). 인덱스는 파생물 |
| 임베딩 | **Ollama** `bge-m3`(로컬, HTTP) | — | MIT(Ollama) / bge-m3 MIT | 별도 프로세스, 키 없음 |
| LLM 클라이언트 | **Spring AI** (OpenAI · Anthropic · Ollama 모듈, DeepSeek은 OpenAI 호환 엔드포인트) | 1.x/2.x [확인 필요: Boot 버전과 짝] | Apache-2.0 | `LlmPort` 뒤에 격리. 제공자 이름은 `engine/llm` 안에만. 스트리밍 지원 |
| 비밀번호 해시 | **Spring Security Crypto `Argon2PasswordEncoder`**(Bouncy Castle) | Boot 내장 | Apache-2.0 / MIT(BC) | Spring Security 전체가 아니라 `spring-security-crypto`만 |
| TOTP | **직접 구현**(`javax.crypto` HMAC-SHA1, RFC 6238) | — | — | 100줄 안팎, 테스트 벡터로 검증. 라이브러리 불필요 |
| QR 생성 | **ZXing core** [확정 2026-09-26] | 3.5.x | Apache-2.0 | TOTP 등록 QR을 데몬이 PNG로. 순수 Java, 리플렉션 없음 |
| Keychain | **macOS `security` CLI 자식 프로세스** | — | — | 의존성 없음. 값은 stdin으로 전달, 로그 금지. 대안 JNA(Apache-2.0)는 필요해질 때 |
| ULID | 직접 구현(`java.security.SecureRandom`, 26자 Crockford base32) | — | — | 50줄. 라이브러리 불필요 |
| RSS | **Rome** | 2.x | Apache-2.0 | 언론사·Google News RSS 파싱 |
| HTML 파싱 | **jsoup** | 1.18.x | MIT | DART 원문·허용된 뉴스 본문 추출 |
| 스케줄링 | Spring `@Scheduled` + 주입 `Clock` | Boot 내장 | Apache-2.0 | cron은 KST 명시. 장 캘린더 판단은 우리 코드 |
| 캐시 | **Spring Cache** + **Caffeine**(로컬) / **Valkey·Redis**(Spring Data Redis + Lettuce) [확정 2026-09-26] | Caffeine 3.x / Lettuce 6.x | Apache-2.0 | `CacheConfig`가 등록된 주소로 PING 성공 시 RedisCacheManager, 아니면 CaffeineCacheManager(런타임 전환, 실패 시 로컬로 강등 + 상태줄 표시). 주소·비밀번호는 자격 종류 `CACHE`(마법사 ②·회원 관리 공유 키). 캐시 대상 [확정 2026-09-26]: 종목 검색·현재가 스냅샷·환율·지수·RAG 검색 결과·요약만. **비밀·계좌·주문·lot은 캐시하지 않음** |
| 검증 | Jakarta Bean Validation(localapi DTO만) | Boot 내장 | EPL/Apache | core는 값 객체 생성자가 검증 |
| 로깅 | SLF4J + Logback(Boot 기본) | Boot 내장 | MIT/EPL | 마스킹 필터로 키·계좌번호 차단(테스트에서 표식 검색) |
| 테스트 | **JUnit 5 · AssertJ**[확정 2026-09-26] · ArchUnit · WireMock · Testcontainers(MySQL·Valkey, relay·캐시) · jqwik(선택), mock은 손 fake 우선(필요 시 MockK) | 5.11 / 3.26 / 1.4 / 3.x / 1.20 / 1.9 | EPL-2.0 / Apache-2.0 / Apache-2.0 / Apache-2.0 / MIT / EPL-2.0 | 단위 테스트는 Spring 컨텍스트 없이. 학습 테스트는 `@Tag("learning")` |
| 관측 | Spring Boot Actuator(health·metrics, `127.0.0.1`만) | Boot 내장 | Apache-2.0 | 상태줄·메모리 실측 |

## 4. 데스크톱 (`desktop/`)

| 영역 | 선택 | 버전(기준) | 라이선스 | 이유·비고 |
|---|---|---|---|---|
| 셸 | **Electron** | 3x 최신 안정판(Chromium 최신) | MIT | `contextIsolation` 켬, `nodeIntegration` 끔, preload 최소 API. 트레이 상주, `nativeTheme` |
| 빌드 | **Vite + electron-vite** | Vite 6.x | MIT | main/preload/renderer 세 타깃 + 웹 타깃(원격 모드) |
| UI | **React** | 19.x | MIT | 함수 컴포넌트·훅. 표현과 데이터 접근 분리 |
| 언어 | **TypeScript** `strict` | 5.x | Apache-2.0 | `any` 금지, `!` 지양 |
| 상태·데이터 | **TanStack Query**(서버 상태·캐시·재조회) + **Zustand**(UI 상태: 선택 종목·서랍·패널·모달) | 5.x / 5.x | MIT | 데이터 접근은 `renderer/data` 한 겹만 |
| 실시간 | 브라우저 `WebSocket` + 초당 4회 묶음(우리 코드) | — | — | 라이브러리 불필요 |
| 차트 | **TradingView Lightweight Charts** | 5.x | Apache-2.0 | 간단 선 차트·캔들·MA·거래량. 지표 계산은 데몬 |
| 스타일 | **CSS(설계서 토큰) + CSS Modules** | — | — | 토큰 파일이 화면 설계서 `:root`와 동일. 유틸리티 프레임워크 없음 |
| 아이콘 | 이모지 + 최소 SVG(직접) | — | — | 설계서와 동일 표기 |
| 날짜 | JS `Intl`/`Temporal` 폴리필 없이 `Intl.DateTimeFormat`(KST·ET 표기) | — | — | 계산은 데몬. 표시만 |
| 폼·검증 | 직접(작은 폼) | — | — | react-hook-form은 마법사·설정이 커지면 검토 |
| 린트·포맷 | **ESLint 9(flat) + typescript-eslint + Prettier** | — | MIT | `parseFloat`/`Number()` 금지 규칙(금액), import 경계 규칙(features → data만) |
| 테스트 | **Vitest + Testing Library + jsdom** | 2.x / 16.x | MIT | 서랍 배타·패널 40:60·모달 단일·시작 종목 토스트 |
| E2E | Playwright(Electron) [2단계 끝 도입 확정 2026-09-26] | 1.4x | Apache-2.0 | 2단계 끝 수동 확인 항목을 자동화 |

## 5. 프로토콜 (`protocol/`)

| 영역 | 선택 | 라이선스 | 비고 |
|---|---|---|---|
| 스키마 | **JSON Schema draft 2020-12** | — | 금액 문자열, 시각 ISO-8601 UTC, enum = Java enum 이름 |
| Kotlin·TS 생성 | **quicktype**(npm CLI, JSON Schema → Kotlin data class + TypeScript 타입을 한 도구로) [확정 2026-09-26] | Apache-2.0 | `protocol/generate.sh`가 두 언어를 함께 생성. 대안: jsonschema2pojo(Java) + json-schema-to-typescript 조합 |
| 검증 | 양쪽 라운드트립 테스트(직렬화 → 역직렬화 동일) | — | protocol 변경 시 CI에서 |

## 6. 외부 서비스 (키·약관은 [EXTERNAL_APIS.md](EXTERNAL_APIS.md))

| 용도 | 서비스 | 접근 방식 |
|---|---|---|
| 주문·시세·계좌·지수(국내)·환율·달력 | 토스증권 OpenAPI(REST + WebSocket) | JDK HttpClient, 규격 `openapi.json` 대조 |
| 국내 일별 시세·지수·ETF | KRX Open API | REST, `AUTH_KEY` |
| 국내 공시·재무·배당 | DART OpenAPI | REST |
| 미국 공시·재무·13F | SEC EDGAR | REST(JSON), 연락처 UA |
| 미국 참조·배당·뉴스·공매도 | Massive(구 Polygon) 무료 | REST, 분당 5회 |
| 미국·일본 지수 종가, 거시 | FRED | REST, 키 확보 |
| 국내 거시 | 한국은행 ECOS | REST |
| 국민연금 연간 | 공공데이터포털 odcloud | REST |
| 뉴스 | 언론사 RSS · Google News RSS · 네이버 검색 API | Rome / REST |
| 본인인증·자산 | 금융결제원 | OAuth(브라우저) + REST |
| 알림 | Slack Web API `chat.postMessage` | REST(SDK 없이) |
| LLM | OpenAI · Anthropic · DeepSeek · Ollama | Spring AI |
| 심층 토론 | 미러피시(AGPL, 별도 프로세스) | `uv` 관리형 설치, HTTP |
| relay 메시지 브로커(7·8단계) | **NATS JetStream**(Apache-2.0, 단일 바이너리) [제안] · 대안 Valkey Streams(BSD) | Java 클라이언트 `jnats`(Apache-2.0). relay 프로세스만 접속, 외부 비노출. 디바이스별 durable consumer·ack·보존 정책. 사용자 결정(2026-09-25): 브로커 도입 |

## 7. 쓰지 않기로 한 것과 이유

| 후보 | 결론 | 이유 |
|---|---|---|
| Spring WebFlux / Reactor | 안 씀 | 가상 스레드로 충분. 코드 단순성 |
| Spring `RestClient` HTTP 인터페이스(`@HttpExchange`) | 안 씀(2026-09-26 Retrofit2로 대체) | Boot 내장이라 의존성은 적지만 사용자 결정으로 Retrofit2 채택. JDK 25라 OkHttp pinning 우려 없음 |
| JDK 24(비LTS) | 안 씀 | LTS가 아니라 25 출시와 함께 지원 종료. 25 LTS 채택 |
| Spring Security(전체) | 안 씀(crypto만) | 로컬 토큰·세션·step-up이 단순해 필터 두 개로 충분. 원격(relay)은 7단계에서 재검토 |
| sqlite-vec / sqlite-vss | 안 씀 | 네이티브 확장 로드 문제(RAG_DESIGN) |
| Lombok | 안 씀 | record로 대체. 애너테이션 프로세서 최소화 |
| MapStruct | 안 씀 | 변환은 손으로(경계가 곧 문서). 양이 커지면 재검토 |
| Redux / MobX | 안 씀 | Zustand + TanStack Query로 충분 |
| Tailwind / MUI | 안 씀 | 설계서 토큰 CSS를 그대로 쓰는 편이 일치가 쉽다 |
| Slack·네이버 등의 **SDK(클라이언트 라이브러리)** | 안 씀 | **API 자체는 쓴다**(네이버 검색 API는 F12 뉴스·블로그·카페 소스, Slack은 알림). 호출이 몇 개뿐이라 JDK HttpClient로 직접 부르고 SDK 의존성은 넣지 않는다 |
| StockTwits MCP 서버 | 제품에 안 넣음 | PROJECT F12 |
| Redis Pub/Sub · 순수 MQTT QoS0 | 안 씀 | 오프라인 구독자에게 유실 → store-and-forward 불가. 브로커는 내구 스트림 방식으로(아래 6장 relay 행) |

## 8. 결정 대기

1. ~~Spring Boot 3.5 vs 4.x~~ → 4.x 확정(2026-09-26). Spring AI·Modulith·Resilience4j 짝 버전은 착수 시 확인.
2. MySQL 드라이버(GPL+FOSS exception) vs MariaDB Connector/J(LGPL) — relay 7단계.
2-1. relay 브로커 제품: NATS JetStream(제안) vs Valkey Streams — **7단계(relay 서버) 착수 전까지 보류** [사용자 결정 2026-09-25]. relay 포트·WS 경로도 같이 연기.
3. ~~protocol 생성 도구·ktfmt 스타일~~ → quicktype · kotlinlang [확정 2026-09-26].
4. ~~ZXing~~ → 채택(2026-09-26).
5. ~~Playwright 도입 시점~~ → 2단계 끝(실주문 검증 전 수동 확인 자동화) (2026-09-26).
6. GraalVM 배포판: Community(제안, GPLv2+CE로 dmg 재배포 명확) vs Oracle GraalVM(GFTC 재배포 조건 확인).
