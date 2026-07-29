# VitaminMCP

마인크래프트 서버/플러그인 자동화를 위한 MCP 서버 + 프로토콜 봇 테스트 하네스.

상세 설계 근거는 `docs/design.md`, 구현 순서와 완료 기준은 `docs/roadmap.md`.
설계 의도가 불분명하면 추측하지 말고 `docs/design.md`를 먼저 확인할 것.

## 기술 스택

- Java 21, Gradle (Kotlin DSL), 멀티모듈 모노레포
- 서버 플러그인: Paper API **1.21.8+** (하한 확정, 변경 시 design.md 갱신 필요)
  - `agent-*`와 `contract`는 `vitaminmcp.server-jvm-target`으로 **`--release 21`** 을 명시한다.
    지금은 툴체인과 같은 값이지만 **의미가 다르다** — 툴체인은 무엇으로 컴파일하는지, 이쪽은
    *서버가 무엇을 로드할 수 있는지*다. 하한을 내리거나 툴체인을 올릴 때 여기가 안전장치다
    (design.md §5.1)
  - 나머지 모듈은 우리 JVM에서 도므로 이 제약과 무관하다
- 봇: MCProtocolLib. **ViaProxy는 쓰지 않는다** (design.md §4)
  - 봇은 **자식 프로세스**(`bot-runner-<프로토콜>`)에서 돈다. 한 JVM은 두 프로토콜을 말할 수 없다 —
    MCProtocolLib 빌드마다 패키지명이 같아 한 클래스패스에 못 올린다
  - 러너 이름은 MC 버전이 아니라 **프로토콜 번호**다. 772 하나가 1.21.7과 1.21.8을 덮는다
  - `testkit` 이상은 프로토콜 라이브러리를 컴파일 의존하지 않는다
- MCP: 직접 구현. agent는 HTTP(JDK HttpServer), mcp-server는 stdio.
  MCP Java SDK를 쓰지 않은 이유는 mcp-server 커밋 참조
- 서버 기동: **네이티브** — PaperMC API에서 jar를 받아 직접 실행 (design.md §15.1)

## 모듈 구조와 의존 방향

```
build-logic/         convention plugin (공통 컴파일/shadow 설정)
contract/            MCP 툴 스키마 + DTO. 순수 Java, 외부 의존성 0
agent/
  agent-core/        캡처 엔진, 상태 조회 (Bukkit API)
  agent-mcp/         MCP 서버 (JDK HttpServer)
bot/
  bot-core/          MCProtocolLib 래퍼, 포워딩 핸드셰이크 주입
  bot-runner-772/    프로토콜 772(1.21.7/1.21.8) 봇 러너. 자식 프로세스로 실행
orchestrator/        네이티브 서버 기동/월드 리셋/버전 매트릭스
testkit/             시나리오 실행기, wait_for, assertion
mcp-server/          툴 노출 + 조립 (엔트리포인트)
```

의존은 **한 방향으로만** 흐른다:

```
mcp-server → testkit → {bot-core, orchestrator, contract}
bot-runner-* → bot-core → contract
agent-mcp  → agent-core → contract
```

## 불변 조건 (깨지 말 것)

1. **`mcp-server`는 `agent-*`를 컴파일 의존하지 않는다.** 에이전트는 런타임에 jar로 주입될 뿐이고, 둘을 잇는 것은 오직 `contract`다. 이걸 어기면 버전별 에이전트 분리가 불가능해진다.
2. **`contract`에 외부 의존성을 추가하지 않는다.** 순수 Java 타입만.
3. **`agent-core`에서 NMS를 쓰지 않는다.** Bukkit/Paper API만. NMS가 필요해 보이면 먼저 설계를 의심할 것.
4. **`agent-*` shadow jar는 모든 의존성을 relocate한다.** 특히 Netty·Jackson·Guava. 서버 본체와 충돌한다.
5. **이벤트 캡처는 메인 스레드에서 직렬화하지 않는다.** MONITOR 리스너는 경량 레코드만 만들어 링 버퍼에 넣고, 직렬화는 별도 스레드.
6. **모든 조회형 툴은 커서 + 상한을 갖는다.** 무제한 응답을 반환하는 툴을 추가하지 말 것.
7. **버전 매트릭스는 코드가 아니라 `versions.yaml`이다.** 버전 추가가 설정 한 줄로 끝나야 한다.

## MCP 툴 설계 규칙

- **집계 우선**: 상세 조회 툴은 반드시 대응하는 요약 툴이 먼저 있어야 한다 (`events_summary` → `events_query`)
- **응답 예산**: 툴 단위로 하드 리밋 (기본 200건 / 50KB). 잘렸으면 응답에 `truncated`와 드롭 카운터를 포함
- **고빈도 이벤트 기본 제외**: `PlayerMoveEvent`, `BlockPhysicsEvent`, `ChunkLoadEvent`, 엔티티 이동 계열은 명시 요청 시에만
- **툴 수를 늘리지 말 것**: 마이크로 툴 대신 파라미터로 해결. 새 툴 추가 전에 기존 툴 확장이 가능한지 먼저 검토
- `logs_tail` 같은 "마지막 N줄" 툴은 만들지 않는다. 패턴 검색이 항상 낫다

## 보안 (타협 금지)

`command_exec` 하나로 op 권한이 넘어간다. 운영 서버에 꽂힐 것을 전제로 한다.

- 기본 bind는 `127.0.0.1`. 외부 노출은 명시적 설정으로만
- 토큰 인증 필수. 토큰 미설정 시 **기동 거부** (경고 후 계속 X)
- **read-only가 기본 모드.** `command_exec` 및 상태 변경 계열은 config에서 명시적으로 켜야 동작
- 테스트 편의를 위해 위 기본값을 완화하지 말 것

## 온라인 모드 처리

테스트 서버는 `online-mode=false` + `spigot.yml`의 `bungeecord: true`로 두고, 핸드셰이크의 server address 필드에
`host\0clientIP\0uuid\0properties-json` 을 주입해 임의의 UUID/스킨을 재현한다.

`online-mode=true` 자체를 검증해야 하는 경우에만 authlib-injector + Drasl 경로를 쓴다. 실계정은 최종 스모크 전용.

## 코딩 컨벤션

- 패키지 루트: `moe.vitamin.minecraft.mcp`
- 널 처리: `Optional` 반환은 피하고 `@Nullable` 명시
- 로깅: `java.util.logging` (Bukkit 표준), 에이전트 내부에서 Log4j2 직접 의존 금지 — Appender 부착 코드만 예외
- 테스트: JUnit 5. 에이전트 로직은 Bukkit 없이 단위 테스트 가능하도록 인터페이스 분리
- 커밋: Conventional Commits (`feat:`, `fix:`, `refactor:`)

## 작업 시 주의

- 새 모듈 추가나 의존 방향 변경은 임의로 하지 말고 먼저 제안할 것
- `versions.yaml`에 버전을 추가하면 그 버전으로 실제 기동해 확인할 것. 전 버전이 네이티브다
- 버전별로 갈리는 코드는 실제로 갈릴 때 만든다. 미리 추상화하지 말 것 (design.md §4.2)
