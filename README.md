# VitaminMCP

마인크래프트 서버에서 벌어지는 일을 MCP로 노출하고, 진짜 프로토콜 봇으로 플러그인을 테스트한다.

- **에이전트**는 서버 안에 들어가는 플러그인이다. 이벤트·로그·예외를 잡아 MCP 툴로 답한다.
  이것만 설치해도 쓸모가 있다 — 돌고 있는 서버에 "무슨 일이 있었는지" 물어볼 수 있다.
- **MCP 서버**는 클라이언트(Claude Code 등)가 띄우는 프로세스다. 봇을 붙이고 시나리오를 돌린다.

설치가 끝난 뒤 실제로 쓰는 법은 [docs/usage.md](docs/usage.md).
설계 근거는 [docs/design.md](docs/design.md), 기여 규칙은 [CONTRIBUTING.md](CONTRIBUTING.md).

## 요구 사항

| | |
|---|---|
| 마인크래프트 서버 | **Paper 1.21.8 이상**. 그 아래는 에이전트를 아예 로드하지 못한다 ([design.md §5](docs/design.md)) |
| Java | 21 (빌드·실행 양쪽) |

## 설치물 세 개

```bash
./gradlew dist
```

`build/dist/`에 세 개가 떨어진다. **가는 곳이 각각 다르다.**

| 파일 | 어디로 | 무엇 |
|---|---|---|
| `VitaminMCP.jar` | 마인크래프트 서버의 `plugins/` | 에이전트 플러그인 |
| `mcp-server.jar` | 아무 데나 (경로만 기억) | MCP 클라이언트가 띄운다 |
| `bot-runner-772.jar` | 아무 데나 (경로만 기억) | `mcp-server`가 자식 프로세스로 띄운다 |

러너 파일명의 숫자는 마인크래프트 버전이 아니라 **프로토콜 번호**다. 772 하나가 1.21.7과
1.21.8을 덮는다. 서버가 다른 프로토콜을 쓰면 그 프로토콜용 러너가 따로 필요하다
([versions.yaml](versions.yaml) 참고).

---

## 1. 에이전트 설치

`VitaminMCP.jar`를 서버의 `plugins/`에 넣고 한 번 띄운다. **첫 기동은 실패한다** — 이게 정상이다.

```
[VitaminMCP] No auth token is configured. The MCP endpoint grants access to server
             internals, so it will not start unauthenticated. ...
[VitaminMCP] Suggested token (paste into config.yml): kQ8s...
```

토큰 없이 뜨는 엔드포인트는 서버 콘솔을 아무에게나 열어주는 것과 같아서, 경고가 아니라 **기동
거부**다 ([design.md §14](docs/design.md)). 로그에 찍힌 토큰을 그대로
`plugins/VitaminMCP/config.yml`의 `auth-token`에 붙이고 다시 띄운다.

```
[VitaminMCP] MCP endpoint listening on http://127.0.0.1:25585/mcp
```

여기까지가 최소 설치다. 나머지 설정은 [config.yml](agent/agent-mcp/src/main/resources/config.yml)
주석에 각 항목이 왜 그 기본값인지와 함께 적혀 있다. 특히:

- **`read-only: true`가 기본.** `command_exec` 같은 상태 변경 툴은 아예 노출되지 않는다.
  유효한 토큰을 쥐어도 기본 설치는 서버를 못 바꾼다. 필요할 때만 끈다.
- **`bind-address`를 루프백 밖으로 옮기면 TLS가 필수다.** 토큰은 콘솔 권한이고, 평문 HTTP로
  네트워크를 건너면 경로상의 무엇이든 읽을 수 있다. 그래서 이 조합은 경고가 아니라 기동 거부다.
  `tls.enabled`(에이전트가 직접 HTTPS) 또는 `tls.terminated-upstream`(앞단 프록시가 종단) 중
  하나를 만족해야 한다. 자체 서명 인증서를 대신 만들어주지는 않는다 — 편하지만 모든 클라이언트에
  검증 생략을 가르치게 된다.

## 2. 봇을 붙일 거라면 — 서버 설정

에이전트만 쓸 거면 이 절은 건너뛴다.

봇은 Mojang 인증을 하지 않는다. 대신 프록시 포워딩 핸드셰이크를 흉내내 임의의 UUID를 주입하므로,
백엔드가 그걸 신뢰하도록 두 가지를 맞춰야 한다 ([design.md §3.1](docs/design.md)).

```properties
# server.properties
online-mode=false
```
```yaml
# spigot.yml
settings:
  bungeecord: true
```

> **이 조합의 서버를 인터넷에 열어두지 말 것.** 소켓을 열 수 있는 사람이면 누구든 아무나로
> 행세할 수 있다. 테스트 하네스 설정이지 운영 설정이 아니다.

## 3. MCP 클라이언트 연결

Claude Code라면:

```bash
claude mcp add vitaminmcp -- java -jar /절대/경로/mcp-server.jar
```

또는 `.mcp.json`에 직접:

```json
{
  "mcpServers": {
    "vitaminmcp": {
      "command": "java",
      "args": ["-jar", "/절대/경로/mcp-server.jar"]
    }
  }
}
```

`mcp-server`는 stdio로 말한다. 포트도 토큰도 없다 — 클라이언트가 띄운 자식 프로세스이므로
이미 신뢰 관계가 있다. 네트워크를 건너는 것은 에이전트 쪽뿐이고, 그래서 인증이 붙는 것도 그쪽뿐이다.

## 4. 연결

`session_start`를 먼저 부른다. 나머지 툴은 전부 이게 있어야 동작한다.

```json
{
  "host": "127.0.0.1",
  "port": 25565,
  "mcpPort": 25585,
  "token": "config.yml의 auth-token",
  "runnerJar": "/절대/경로/bot-runner-772.jar"
}
```

`runnerJar`는 생략해도 된다 — `mcp-server.jar` 옆의 `bot-runner-*.jar`를 찾는다. `dist`가 셋을
한 폴더에 두므로 보통은 적을 일이 없다.

### 다른 기계의 서버라면

`bind-address`를 루프백 밖으로 옮기면 에이전트가 TLS 없이는 뜨지 않는다. 인증서를 준비하고
기동하면, **붙는 데 필요한 걸 에이전트가 통째로 찍어준다**:

```
[VitaminMCP] MCP endpoint listening on https://203.0.113.10:25585/mcp
[VitaminMCP] Connect with session_start:
  "host": "203.0.113.10", "mcpPort": 25585, "tls": "true",
  "token": "YLwNyFij...",
  "tlsFingerprint": "sha256:ffb61d8f...f163"
```

그대로 붙여넣으면 끝이다. **자체 서명 인증서라도 클라이언트에 아무것도 설치하지 않는다** —
`tlsFingerprint`가 그 인증서 하나만 신뢰하도록 고정하기 때문이다. 인증서를 내보내고 옮기고
truststore를 만들 필요가 없다.

정식 인증서(Let's Encrypt 등)를 쓴다면 `tlsFingerprint`를 빼면 평소대로 검증한다.

잘 붙었으면 서버 버전·TPS·플러그인 목록이 돌아온다. 그다음은:

- `events_summary` → `events_query` — 무슨 일이 있었나
- `logs_query`, `exceptions_recent` — 무엇이 터졌나
- `bot_spawn` → `bot_run_scenario` — 봇을 붙여 실제로 시켜보기

각 툴의 파라미터와 시나리오 스텝 목록은 [docs/usage.md](docs/usage.md)에 있다.

## 여러 버전에 돌리려면

버전 매트릭스는 코드가 아니라 [versions.yaml](versions.yaml)이다. 한 블록 추가로 끝난다.
서버 jar는 PaperMC API에서 직접 받아 네이티브로 띄운다 (Docker도 ViaProxy도 쓰지 않는다,
[design.md §15.1](docs/design.md)).

각 버전에는 그 프로토콜을 말하는 러너가 있어야 한다. 없으면 서버가 `Outdated client!`로
정직하게 거절한다.
