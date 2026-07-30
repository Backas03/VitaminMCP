# 사용법

설치는 [../README.md](../README.md). 이 문서는 설치가 끝난 다음부터다.

쓰는 방식은 두 가지고, 필요한 설치물이 다르다.

| | 필요한 것 | 할 수 있는 것 |
|---|---|---|
| **조사만** | 에이전트 플러그인 | 돌고 있는 서버에 무슨 일이 있었는지 묻기 |
| **테스트까지** | + mcp-server + 러너 | 봇을 붙여 실제로 시켜보고 결과 검증 |

조사만 하는 쪽이 read-only 기본값 그대로다. 운영 서버에 꽂아도 되는 건 이쪽뿐이다.

---

# A. 조사하기

## 항상 `server_info`부터

버전, TPS, 접속자 수, 설치된 플러그인, 그리고 **캡처 상태**가 돌아온다. 마지막 게 중요하다 —
`eventsDropped`가 0이 아니면 버퍼가 넘쳤다는 뜻이고, 그 뒤의 모든 조회는 구멍 뚫린 데이터다.

응답에 `latestEventCursor` / `latestLogCursor`가 같이 온다. "지금 이후에 벌어지는 일"만 보고
싶을 때 이 값을 들고 시작하면 과거를 다시 읽지 않는다.

## 이벤트 — 요약을 먼저, 상세는 그다음

```
events_summary  →  events_query
```

**순서를 바꾸지 말 것.** `events_summary`는 서버가 아무리 바빠도 응답이 작고, 어떤 타입이
상세히 볼 가치가 있는지 알려준다. 그걸 건너뛰고 `events_query`부터 부르면 응답 예산을
관심 없는 이벤트로 채우게 된다.

| 툴 | 파라미터 |
|---|---|
| `events_summary` | `from`, `to` (epoch ms, 둘 다 생략 가능) |
| `events_query` | `types[]`, `player`, `cursor`, `limit` |

**고빈도 이벤트는 `types`에 이름을 적어야만 나온다.** `PlayerMoveEvent`, `BlockPhysicsEvent`,
`ChunkLoadEvent`, 엔티티 이동 계열이 그렇다. 애초에 캡처조차 안 하는 게 기본이라, 정말 필요하면
`config.yml`의 `capture-high-frequency`도 켜야 한다.

## 로그 — 패턴으로 찾는다

```
logs_query(level="WARN", pattern="Timer|lag")
```

`level`은 **최소** 심각도다. `pattern`은 자바 정규식이고 메시지에 매칭된다.

**`logs_tail` 같은 건 없다.** "마지막 N줄"은 바쁜 서버에서 접속 메시지로 예산을 다 쓴다.
찾는 걸 찾아라.

## 예외 — 그룹을 보고, 하나를 판다

```
exceptions_recent(limit=10)        # 스택 없이, 발생 횟수 + 최초 발생 시각
exceptions_recent(hash="...")      # 그 하나만 전체 스택과 함께
```

같은 예외가 만 번 나도 한 줄이다. 세는 것과 읽는 것을 나눠 놓은 이유다.

## 지금 상태를 직접 묻기 — `state_query`

이벤트에서 추론하지 말고 서버에 물어라. **혼란스러운 테스트 결과는 거의 전부 상태에 대한
의견 차이다.**

```jsonc
{"kind": "player", "target": "Tester1", "permissions": ["essentials.fly"]}
{"kind": "block",  "world": "world", "x": 10, "y": 63, "z": 20}
```

`kind="player"` 응답: `name`, `uuid`, `online`, `address`, `gameMode`, `op`, `world`,
`x`/`y`/`z`, `permissions`.

- `uuid` — 퍼미션이 키로 쓰는 값. 봇은 이름에서 결정적으로 파생되므로 매번 같다.
- `address` — 서버가 이 접속에 부여한 IP. 봇이면 핸드셰이크로 주입된 값이라, **주입이 먹혔는지
  확인할 수 있는 유일한 곳**이다.
- `permissions` — 물어본 것만 답한다. 퍼미션은 열거가 불가능하고 테스트만 가능하다.

## 메뉴 GUI 읽기 — `state_query kind="inventory"`

**플러그인이 띄운 메뉴의 내용은 여기서만 읽을 수 있다.** 그 아이템들은 열린 뷰가 들고 있는 가상
인벤토리에만 존재한다 — 플레이어 NBT에도, 이벤트 페이로드에도 없다. `/data get entity`로도 안
나온다.

```jsonc
{"kind": "inventory", "target": "Tester1"}              // 열린 메뉴
{"kind": "inventory", "target": "Tester1", "which": "player"}  // 본인 인벤토리
```

```jsonc
{
  "view": "CHEST",          // CRAFTING / CREATIVE / PLAYER = 열린 메뉴 없음
  "title": "§aShop",
  "size": 27,
  "occupiedSlots": 2,
  "items": [
    {"slot": 11, "material": "EMERALD", "amount": 1,
     "displayName": "§aBuy", "lore": ["§7Costs 10"], "enchanted": false}
  ],
  "truncated": false
}
```

- **빈 슬롯은 빠진다.** 54칸 메뉴는 대부분 공기라 다 넣으면 예산만 먹는다. `size`와
  `occupiedSlots`가 전체를 말해주므로 "슬롯 22는 비었다"도 여전히 알 수 있다 — 목록에 없으면 빈
  것이다.
- **색 코드는 `§` 형태로 보존된다.** 메뉴가 제대로 렌더됐는지에는 색도 포함되니까. 색을 무시하고
  싶으면 무시하면 되지만, 지웠으면 되돌릴 수 없다.
- `view`가 `CRAFTING`/`CREATIVE`/`PLAYER`면 **열린 메뉴가 없다는 뜻**이다. 크리에이티브에서는
  `CREATIVE`가 나온다 — 셋 다 "본인 화면"이다.

## 응답 예산

모든 조회형 툴은 상한이 있다 (기본 200건 / 50KB). 정확한 값은 각 툴 설명에 박혀 있다.
잘리면 응답이 그렇게 말한다:

- `truncated: true` — 예산 때문에 잘림
- `nextCursor` — 이걸 `cursor`로 넘겨 이어서 읽기
- `dropped` — 링 버퍼에서 **영영 사라진** 건수. 이건 이어 읽어도 못 가져온다

---

# B. 봇으로 테스트하기

## `session_start` — 무조건 먼저

```jsonc
{
  "host": "127.0.0.1",
  "port": 25565,          // 마인크래프트 포트
  "mcpPort": 25585,       // 에이전트 포트
  "token": "config.yml의 auth-token",
  "runnerJar": "/절대/경로/bot-runner-772.jar",
  "tls": "true"           // 다른 기계의 서버일 때만
}
```

여기서 바로 `server_info`를 한 번 부른다. 호스트·포트·토큰이 틀렸으면 나중에 엉뚱한 툴 안에서
터지는 대신 여기서 그 이유를 말하고 끝난다.

응답에는 `agentTools`도 실려 온다 — **프록시된 툴들의 진짜 파라미터**다. mcp-server의 툴 목록은
서버가 뜰 때 발행되는데 그때는 아직 붙은 에이전트가 없으므로, 파라미터를 거기 적을 수가 없다.
어떤 툴이 있는지조차 에이전트에 달려 있다 (read-only면 `command_exec`이 아예 없다). 그래서
정의는 구현이 있는 쪽에서 그대로 가져온다.

프록시 툴을 부를 때 **파라미터는 최상위에 평평하게 넣는다.** 감싸지 않는다:

```jsonc
{"kind": "player", "target": "Tester1"}                     // ✓
{"arguments": "{\"kind\":\"player\"}"}                       // ✗
```

`session_reset`은 봇만 전부 끊고 연결은 유지한다. **독립적인 테스트 사이에 부르라** — 안 그러면
앞 테스트의 플레이어를 물려받는다.

> 월드 상태는 되돌리지 않는다. 월드에 의존하는 시나리오는 스스로 상태를 만들어야 한다.

## 한 스텝씩 몰기

```
bot_spawn {"name": "Tester1"}
```

이름이 곧 정체성이다. UUID가 이름에서 파생되므로 "Tester1"은 어제도 오늘도 같은 플레이어고,
퍼미션에 의존하는 동작이 재현된다. 응답은 UUID와 착지 좌표.

`clientIp`를 넣으면 서버가 그 주소에서 접속한 것으로 기록한다. IP 밴, IP당 접속 제한, 지역
판정처럼 **주소를 키로 삼는 것**을 테스트할 때만 쓴다. 안 넣으면 실제 주소가 간다 — 지어낸
주소는 이후 모든 스텝이 떠안는 거짓말이다.

그다음은 프록시된 에이전트 툴을 그대로 쓰면 된다. `wait_for`, `state_query`, `events_query` 전부
`session_start`한 서버로 간다.

## 통째로 — `bot_run_scenario`

시나리오는 스텝의 JSON 배열이다. **첫 실패에서 멈춘다** — 시나리오가 기술한 적 없는 상태에서
뒷 스텝을 계속 돌려봐야 그 실패들은 아무 의미가 없다.

```json
[
  {"action": "spawn",        "bot": "Tester1"},
  {"action": "console",      "command": "op Tester1"},
  {"action": "assert_player","bot": "Tester1", "op": true},
  {"action": "break_block",  "bot": "Tester1", "x": 10, "y": 63, "z": 20},
  {"action": "wait_for",     "condition": "block_is", "x": 10, "y": 63, "z": 20,
                             "material": "AIR"},
  {"action": "assert_event", "eventType": "BlockBreakEvent", "player": "Tester1"}
]
```

### 스텝 레퍼런스

| action | 필수 | 선택 |
|---|---|---|
| `spawn` | `bot` | `clientIp` |
| `despawn` | `bot` | |
| `move_to` | `bot`, `x`, `y`, `z` | |
| `break_block` | `bot`, `x`, `y`, `z` | |
| `use_block` | `bot`, `x`, `y`, `z` | `face` (기본 `UP`). 우클릭 — 상자·메뉴를 여는 동작 |
| `command` | `bot`, `command` | 봇이 친 커맨드 |
| `chat` | `bot`, `message` | |
| `console` | `command` | 콘솔이 친 커맨드 (`command_exec` 경유) |
| `click_slot` | `bot`, `slot` | `click`: `left`(기본)/`right`/`shift_left`/`shift_right` |
| `close_menu` | `bot` | |
| `wait_for` | `condition` | 조건별 파라미터, `timeoutMillis` |
| `assert_block` | `x`, `y`, `z`, `material` | `world` (기본 `"world"`) |
| `assert_player` | `bot` | `online`, `gameMode`, `op`, `timeoutMillis` |
| `assert_event` | `eventType` | `player`, `sinceSequence`, `timeoutMillis` |
| `assert_inventory` | `bot` | `title`, `size`, `which`, `slots[]` (아래) |

`assert_inventory`의 `slots[]` 항목:

| 필드 | 뜻 |
|---|---|
| `slot` | 검사할 슬롯 (필수) |
| `material` | 기대 아이템. 대소문자 무관 |
| `name` | 표시 이름에 이 문자열이 포함되는지. **색 코드는 무시하고 비교**하므로 `"Buy"`로 `§aBuy`가 맞는다 |
| `amount` | 개수 |
| `lore` | lore 어딘가에 이 문자열이 포함되는지 |
| `empty` | `true`면 그 슬롯이 비어 있어야 한다 |

몇 가지 알아둘 것:

- **`console`은 `command_exec`을 부른다.** 에이전트가 `read-only: true`면 이 스텝은 실패한다.
- **`assert_player`는 읽지 않고 기다린다.** `op` 같은 값은 비동기로 바뀐다 — `/op`는 이름을
  UUID로 해석한 뒤에야 반영되므로, 바로 읽으면 앞 커맨드와 경합해서 엉뚱한 이유로 실패한다.
- **`assert_event`의 기준점은 시나리오 시작이다.** `sinceSequence`를 안 주면 자동으로 그렇게
  된다. 검증하려는 사건은 보통 직전 스텝에서 일어났지, 이 스텝 이후가 아니다.
- 기본 대기는 15초, `wait_for` 툴 자체의 기본은 10초다.

---

# B-2. 메뉴 GUI 테스트

명령어를 치면 메뉴가 열리고, 그 메뉴가 제대로 그려졌는지 보는 흐름이다.

```json
[
  {"action": "spawn",    "bot": "Tester1"},
  {"action": "command",  "bot": "Tester1", "command": "shop"},
  {"action": "wait_for", "condition": "inventory_open", "name": "Tester1", "title": "Shop"},
  {"action": "assert_inventory", "bot": "Tester1", "size": 27, "slots": [
      {"slot": 11, "material": "EMERALD", "name": "Buy",   "lore": "Costs 10"},
      {"slot": 15, "material": "BARRIER", "name": "Close", "amount": 3},
      {"slot": 13, "empty": true}
  ]},
  {"action": "click_slot", "bot": "Tester1", "slot": 11},
  {"action": "assert_event", "eventType": "InventoryClickEvent", "player": "Tester1"},
  {"action": "close_menu", "bot": "Tester1"}
]
```

**`wait_for`를 빼지 말 것.** 메뉴는 명령어와 동기적으로 열리지 않는다 — 플러그인이 한 틱 쉬거나
DB를 기다릴 수 있다. 바로 읽으면 플레이어 본인 화면을 읽고 "메뉴가 비었다"고 보고하는데, 이건
메뉴가 안 채워진 경우와 증상이 똑같다.

플러그인이 **메뉴를 먼저 열고 나중에 채우는** 경우라면 `inventory_open`만으로는 부족하다. 버튼
자체를 기다려라:

```json
{"action": "wait_for", "condition": "inventory_contains",
 "name": "Tester1", "material": "EMERALD", "slot": 11}
```

실패하면 무엇이 실제로 있었는지가 그대로 나온다:

```
슬롯 11 expected DIAMOND but held EMERALD
슬롯 11 is empty, expected DIAMOND
expected the title to contain 'Shop' but it was '§cError'
no menu is open for Tester1 — the view is CREATIVE. If a command should have opened one,
wait_for inventory_open first.
```

## 상자를 직접 열어보려면

플러그인 없이 컨테이너 GUI를 열려면 `use_block`으로 우클릭한다.

```json
{"action": "console",   "command": "setblock 10 64 20 chest"},
{"action": "use_block", "bot": "Tester1", "x": 10, "y": 64, "z": 20}
```

> 상자 **바로 위에 불투명 블록이 있으면 열리지 않는다.** 게임 규칙이지 이 하네스의 문제가
> 아닌데, 증상은 "메뉴 코드가 고장났다"처럼 보인다. 위칸을 `air`로 비워두고 시작할 것.

---

# C. `wait_for` — 자면 안 되는 이유

**`sleep` 스텝은 없고, 앞으로도 없다.** 있으면 반드시 쓰이고 — 타이밍 문제를 넘어가는 가장 짧은
길이니까 — 쓰인 모든 시나리오는 그걸 쓴 사람의 기계에 맞춰진다. 한가한 서버에서 맞고 바쁜
서버에서 틀린다. 그게 플래키 테스트가 만들어지는 메커니즘 전부다.

대신 **기다리는 대상의 이름을 대라.** 에이전트가 서버 안에서 틱마다 검사하고, 참이 되는 순간
답한다. 요청 한 번이면 되고, 두 폴링 사이에 벌어진 일을 놓칠 수도 없다.

| condition | 파라미터 |
|---|---|
| `ticks` | `count` |
| `block_is` / `block_is_not` | `material`, `x`, `y`, `z`, `world` |
| `event` | `eventType`, `player`, `sinceSequence` |
| `player_online` / `player_offline` | `name` |
| `player_near` | `name`, `x`, `y`, `z`, `distance` |
| `player_state` | `name`, 그리고 `online` / `gameMode` / `op` 중 검사할 것 |
| `inventory_open` | `name`, `title`(부분 일치, 색 무시) |
| `inventory_contains` | `name`, `material`, `slot`, `which` |

`timeoutMillis`는 기본 10000, 상한 60000.

**타임아웃은 빈손으로 오지 않는다.** 그 시점의 이벤트와 로그 스냅샷이 응답에 실려 온다. 대개
거기에 이유가 있다.

---

# D. 서버 바꾸기 — `command_exec`

`config.yml`에서 `read-only: false`로 해야 **툴 목록에 나타난다.** 제한되는 게 아니라 아예 없다.

```jsonc
{"command": "give Tester1 diamond 1", "as": "Tester1"}   // as 생략 시 콘솔
```

응답의 `dispatched`는 "핸들러가 받았다"는 뜻이지 성공이 아니다. **진짜 답은 `output`에 있다** —
형식적으로는 성공하면서 출력으로 실패를 알리는 커맨드가 많다.

---

# E. 실패를 읽는 법

`bot_run_scenario`는 어느 스텝에서 왜 죽었는지 말한다.

```jsonc
{
  "passed": false,
  "steps": [
    {"step": 1, "action": "spawn", "passed": true,  "detail": "Tester1 joined at ..."},
    {"step": 2, "action": "break_block", "passed": false,
     "detail": "...", "evidence": "events=[...] logs=[...]"}
  ]
}
```

`evidence`는 **실패 시점의** 이벤트·로그다. 나중에 따로 물으면 서버 상태는 이미 지나가 있으므로,
추가 툴 호출 없이 붙여서 준다.

여기서 원인이 안 보이면 다음 순서로 판다:

1. `state_query` — 봇과 서버가 위치·게임모드·퍼미션에 대해 같은 생각을 하고 있나
2. `exceptions_recent` — 플러그인이 조용히 터지고 있나
3. `server_info`의 `eventsDropped` — 애초에 못 본 것 아닌가

---

# F. 자주 하는 실수

| 증상 | 원인 |
|---|---|
| 봇 접속이 `did you forget to enable BungeeCord in spigot.yml?`로 거절 | 서버가 `online-mode=false` + `bungeecord: true`가 아니다 ([README](../README.md) 3절) |
| `Outdated client!` | 서버 프로토콜에 맞는 러너가 아니다. 파일명 숫자는 MC 버전이 아니라 프로토콜 번호 |
| 이벤트가 안 잡힌다 | 고빈도 목록에 있는 타입이다. `types`에 명시하고, 필요하면 `capture-high-frequency`도 켤 것 |
| `command_exec`이 없다 | `read-only: true` (기본값). `session_start`의 `agentTools`에 실제로 있는 툴이 나온다 |
| 프록시 툴이 `... needs 'kind'` 같은 걸로 거절 | 파라미터를 감쌌다. 최상위에 평평하게 넣을 것 |
| 메뉴가 비었다고 나온다 | `wait_for inventory_open`을 안 했다. 아니면 `view`를 볼 것 — `CREATIVE`/`CRAFTING`이면 애초에 안 열린 것이다 |
| 상자가 안 열린다 | 바로 위에 불투명 블록이 있다 (게임 규칙) |
| `click_slot`이 `has no menu open`으로 실패 | 열리기 전에 클릭했다. `wait_for inventory_open` 먼저 |
| 봇은 붙었는데 아무것도 안 먹는다 | 착지 전이다. `bot_spawn`은 착지까지 기다리지만, 직접 몰 때는 발밑이 아직 공기일 수 있다 |
| 두 번째 실행부터 깨진다 | 앞 실행의 상태가 남았다. `session_reset`을 쓰고, 월드에 의존하면 시나리오가 직접 상태를 만들 것 |

---

설계 근거는 [design.md](design.md), 남은 작업은 [roadmap.md](roadmap.md).
