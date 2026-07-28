# 기여 가이드

설계 근거는 [docs/design.md](docs/design.md), 구현 순서와 완료 기준은
[docs/roadmap.md](docs/roadmap.md), 규칙과 불변 조건은 [CLAUDE.md](CLAUDE.md).

## 커밋 — Conventional Commits

```
<type>(<scope>): <subject>
```

`type`:

| type | 용도 |
|---|---|
| `feat` | 기능 추가 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변경 없는 구조 개선 |
| `docs` | 문서만 변경 |
| `test` | 테스트 추가/수정 |
| `build` | Gradle, 의존성, 툴체인 |
| `chore` | 그 외 잡무 |

`scope`는 모듈명을 그대로 쓴다: `contract`, `agent-core`, `agent-mcp`, `bot-core`,
`bot-via`, `orchestrator`, `testkit`, `mcp-server`, `build-logic`.

```
feat(agent-core): add ring buffer with drop counter
fix(bot-core): keep forwarding handshake UUID stable across reconnects
build(build-logic): enforce dependency direction in check task
```

호환성이 깨지는 변경은 `feat(contract)!:`처럼 `!`를 붙이고 본문에 `BREAKING CHANGE:`를 적는다.

## 브랜치

`<type>/<short-description>` — 예: `feat/event-capture`, `fix/ring-buffer-overflow`.

## 작업 전 확인

- 새 모듈 추가나 의존 방향 변경은 임의로 하지 말고 먼저 제안할 것 (CLAUDE.md)
- 단계를 건너뛰지 말 것. 각 단계는 roadmap의 DoD를 만족해야 다음으로 넘어간다

## 빌드

```bash
./gradlew build
```

`build`에는 아키텍처 검사가 붙어 있다:

- `checkModuleDependencies` — 모듈이 허용되지 않은 모듈에 의존하면 실패.
  허용 간선은 `build-logic/src/main/kotlin/vitaminmcp.module-rules.gradle.kts`에 있다
- `checkContractIsDependencyFree` — `:contract`에 외부 의존성이 생기면 실패

두 검사는 CLAUDE.md의 불변 조건 1·2를 코드로 못박은 것이다. 통과시키려고 규칙을 고치기 전에
설계를 먼저 의심할 것.
