# AGENTS.md — Stockholm

이 저장소에서 일하는 모든 코딩 에이전트(Codex, Cursor, Copilot, Claude Code 등)의 규칙은 **[CLAUDE.md](CLAUDE.md)** 한 곳에 있다. 이 파일은 그 문서를 가리키는 입구일 뿐이며, 여기에 규칙을 따로 쓰지 않는다. 두 파일이 어긋나면 CLAUDE.md가 맞다.

## 읽는 순서

1. [CLAUDE.md](CLAUDE.md) — 작업 규칙. 절대 규칙 8개(실제 주문 API 호출 금지, 비밀값 금지, 가드레일 우회 금지, LLM 출력을 주문에 직접 쓰지 않음, `BigDecimal`, 토스 어댑터만 주문, 로그에 금융정보 금지, `userId` 경계)는 어떤 편의를 위해서도 어기지 않는다.
2. [PROJECT.md](PROJECT.md) — 기준 문서. CLAUDE.md는 첫 줄에서 `@PROJECT.md`로 이 문서를 끌어들이는데, 그 문법을 지원하지 않는 도구는 **PROJECT.md를 직접 읽는다.**
3. [docs/README.md](docs/README.md) — 문서 지도. 어떤 설계 문서를 봐야 할지 여기서 찾는다.
4. [docs/HANDOFF.md](docs/HANDOFF.md) — 지금 상태와 다음 작업. 세션을 시작할 때 읽고 끝날 때 갱신한다.
5. [docs/DEVELOPMENT_PLAN.md](docs/DEVELOPMENT_PLAN.md) — 단계별 착수 순서와 완료 기준.

## 한 줄 요약

- 한국어로 소통한다. 사용자는 시니어 Java/Spring 개발자다.
- 이 프로그램은 실제 돈을 움직인다. 테스트는 fake `TradingPort`로만, 키 값은 Keychain·환경 변수로만, 커밋·푸시는 사용자가 요청할 때만.
- 빌드·테스트 명령과 코딩 규칙(Clean Code 기반, 한국어 개조식 주석, TDD 대상)은 CLAUDE.md에 있다.
