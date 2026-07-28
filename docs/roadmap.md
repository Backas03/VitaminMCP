# VitaminMCP 구현 로드맵

각 단계는 **완료 기준(DoD)을 만족해야 다음으로 넘어간다.** 앞 단계를 건너뛰고 뒤를 먼저 만들지 말 것.

설계 근거는 `design.md`, 규칙과 불변 조건은 `../CLAUDE.md`.

난이도 배분: **1~3단계가 전체의 대부분**이다. 4단계는 그 위의 얇은 껍데기이고, 5단계는 확장이다.

---

## Stage 0 — 스캐폴딩

Gradle 멀티모듈 골격을 세운다.

**작업**

- [ ] `settings.gradle.kts` — 전 모듈 등록
- [ ] `build-logic/` convention plugin — Java 21 툴체인, 공통 컴파일 옵션, shadow+relocate 설정
- [ ] 각 모듈 `build.gradle.kts` 스텁
- [ ] `contract/` 모듈 — 빈 상태로 생성, 외부 의존성 0 확인
- [ ] `.gitignore`, Conventional Commits 안내

**DoD**

- `./gradlew build`가 전 모듈에서 통과
- `contract`의 의존성 트리에 외부 라이브러리가 없음 (`./gradlew :contract:dependencies`로 확인)
- 의존 방향 위반 시 빌드가 실패하도록 강제 (convention plugin에서 검사)

---

## Stage 1 — 이벤트 캡처 + MCP 노출 ★ 핵심

**이 단계가 살아있으면 나머지는 위에 얹는 수준이다.** 가장 공들일 곳.

### 1a. contract 스키마

- [ ] 이벤트 레코드 DTO (타입, 타임스탬프, 플레이어, 취소 여부, 페이로드)
- [ ] 로그 레코드 DTO (레벨, 로거명, 메시지, throwable 참조)
- [ ] 커서 타입 — **처음부터 넣는다.** 나중에 추가하면 전부 뜯어야 함
- [ ] 페이지네이션 응답 래퍼 (`items`, `nextCursor`, `truncated`, `dropped`)

### 1b. agent-core 캡처 엔진

- [ ] ClassGraph로 `org.bukkit.event.Event` 서브클래스 스캔 → 동적 `registerEvent`
- [ ] `EventPriority.MONITOR` + `ignoreCancelled = false`로 등록
- [ ] lock-free 링 버퍼 (고정 크기, 기본 100k) + 드롭 카운터
- [ ] 별도 스레드에서 직렬화 — **메인 스레드 직렬화 금지**
- [ ] 고빈도 이벤트 기본 제외 목록 (`PlayerMoveEvent`, `BlockPhysicsEvent`, `ChunkLoadEvent`, 엔티티 이동 계열)
- [ ] Log4j2 커스텀 Appender 부착 — 파일 tail 금지
- [ ] 예외 그룹핑 (스택 해시 기준, 카운트 + 최초 발생 시각)

### 1c. agent-mcp

- [ ] JDK 내장 `com.sun.net.httpserver` 기반 MCP streamable HTTP 서버
- [ ] 툴 구현: `server_info`, `events_summary`, `events_query`, `logs_query`, `exceptions_recent`
- [ ] 응답 예산 하드 리밋 (200건 / 50KB) + `truncated` 플래그
- [ ] 토큰 인증 — **미설정 시 기동 거부**
- [ ] 기본 bind `127.0.0.1`
- [ ] read-only 모드 기본값 (`command_exec` 미노출)
- [ ] shadow jar relocate 검증 (Netty·Jackson·Guava)

**DoD**

- Paper 1.13 / 1.20 서버 양쪽에 플러그인 설치 후 정상 기동
- Claude Code에서 이 MCP에 직접 연결해 `events_summary` → `events_query` 흐름이 동작
- 플레이어 접속/블록파괴/채팅이 이벤트로 잡힘
- **부하 검증**: 플레이어 이동이 활발한 상태에서 TPS 저하가 측정 수준 이내
- 링 버퍼 오버플로 시 `dropped` 카운터가 응답에 반영됨
- 토큰 없이 기동하면 거부됨
- 다른 플러그인이 설치된 서버에서 클래스 충돌 없음

> Stage 1 완료 시점에서 **이미 단독 제품으로 쓸 수 있다.** 여기서 한 번 실사용해보고 툴 스키마를 다듬은 뒤 Stage 2로 갈 것.

---

## Stage 2 — 봇 접속

### 2a. bot-core

- [ ] MCProtocolLib 래퍼, 봇 세션 생명주기 관리
- [ ] **포워딩 핸드셰이크 주입** — server address 필드에 `host\0clientIP\0uuid\0properties-json`
- [ ] 고정 UUID 발급 정책 (이름 → UUID 결정적 매핑, 퍼미션 재현성)
- [ ] 다중 봇 인스턴스 관리 + 리소스 상한
- [ ] 기본 액션: 이동, 블록 상호작용, 인벤토리 클릭, 채팅, 커맨드

**DoD**

- `online-mode=false` + `bungeecord: true` 백엔드에 봇이 접속
- 주입한 UUID가 서버에서 그대로 관측됨 (agent의 `state_query`로 확인)
- 봇 3개 이상 동시 접속 유지
- 봇이 블록을 부수면 Stage 1의 `events_query`에 `BlockBreakEvent`로 잡힘 ← **두 계층이 처음 연결되는 지점**

---

## Stage 3 — testkit: 결정성 ★ 두 번째 난관

플래키 테스트를 만들지 않는 것이 전부다.

- [ ] agent 측 **틱 배리어** — "N틱 진행 후 응답" API
- [ ] `wait_for(predicate, timeout)` — 봇/서버 양쪽 상태에 대한 술어
- [ ] 선언적 JSON 액션 DSL 실행기 (`design.md` §11)
- [ ] 스텝 단위 실패 리포트 — 어느 스텝에서 왜 죽었는지
- [ ] assertion 헬퍼 (인벤토리, 위치, 스코어보드, 퍼미션)

**금지 사항**

- [ ] 시나리오 DSL에 **고정 대기(sleep)를 제공하지 않는다.** 제공하면 반드시 쓰이고, 플래키의 원인이 된다

**DoD**

- 동일 시나리오 50회 연속 실행에서 실패 0회 (플래키 없음)
- 의도적으로 실패하는 시나리오가 정확한 스텝을 지목
- 타임아웃 시 그 시점의 이벤트/로그 스냅샷이 함께 반환됨

---

## Stage 4 — mcp-server 조립

Stage 1~3 위의 얇은 계층. 여기서 새 로직을 만들지 말 것.

- [ ] MCP Java SDK로 툴 노출 (5~6개)
- [ ] `session_start` / `session_reset`
- [ ] `bot_spawn` / `bot_run_scenario`
- [ ] agent MCP 프록시 — 매트릭스 모드에서 서버별 툴 네임스페이스 부여
- [ ] 실패 시 이벤트/로그 자동 첨부

**DoD**

- Claude Code에서 "봇 2개 띄우고 X 플러그인의 Y 기능 테스트해줘"가 자연어로 동작
- 실패 시 원인 파악에 필요한 컨텍스트가 추가 툴 호출 없이 제공됨

---

## Stage 5 — 버전 매트릭스

- [ ] orchestrator: Docker(`itzg/minecraft-server`) 기동/정지/월드 템플릿 복원
- [ ] `versions.yaml` 스키마 + 로더 (`design.md` §15)
- [ ] bot-via: ViaProxy 임베드, 프로토콜 브리지
- [ ] 매트릭스 병렬 실행 + 버전별 결과 집계
- [ ] `native: true` 버전은 네이티브 프로토콜로 교차 검증

**DoD**

- 1.13 / 1.16 / 1.20 / 최신 4개 버전에서 동일 시나리오 실행
- 버전 추가가 `versions.yaml` 한 블록 추가로 끝남
- 특정 버전만 실패할 때 Via 경유/네이티브 결과를 대조할 수 있음

---

## 작업 순서 요약

```
Stage 0  스캐폴딩        ─ 가볍게
Stage 1  캡처 + MCP      ─ ★ 여기에 시간 대부분
Stage 2  봇 접속         ─ 핸드셰이크 주입이 관건
Stage 3  결정성          ─ ★ 두 번째 난관
Stage 4  조립            ─ 얇게
Stage 5  버전 매트릭스   ─ 확장
```

Stage 1 끝난 시점에 한 번 멈추고 실사용해볼 것. 툴 스키마는 실제로 써봐야 다듬어진다.

---

## 나중에 (현 범위 밖)

- 자체 Yggdrasil (authlib-injector + Drasl) — `online-mode=true` 자체 검증이 필요해질 때
- Groovy/GraalJS 스크립트 엔진 — 선언 DSL로 안 되는 사례가 쌓인 뒤
- `agent-legacy` 모듈 — 1.12 이하 요구가 실제로 생길 때
- Bedrock (Via 경유 가능하나 검증 범위 별도 정의 필요)
