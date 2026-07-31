# VitaminMCP

**Minecraft automation testing MCP server for AI agents.**

Drive a real Minecraft server and real players through MCP tools, and run end-to-end plugin tests
without opening the game.

- Spawn and control test players — real protocol clients, not mock `Player` objects
- Execute commands as the console or as a player
- Open, read, click and assert on inventories and plugin GUIs
- Right-click NPCs and villagers, the way a shop or quest giver is actually triggered
- Move players, break and use blocks, chat
- Wait for events and conditions instead of sleeping
- Assert on blocks, players, events, inventories and the messages a player received
- Read the player's whole screen: menus, chat, action bar, titles, boss bars, scoreboard
- Read live server state: events, logs, exceptions, permissions
- Paper / Purpur 1.21+

Full usage is in [docs/usage.md](docs/usage.md), design rationale in
[docs/design.md](docs/design.md), contribution rules in [CONTRIBUTING.md](CONTRIBUTING.md).

---

## Why

**Without VitaminMCP**, verifying a plugin change means:

- Launch Minecraft, join the server
- Click through the GUI by hand
- Read the chat and eyeball whether it did the right thing
- Repeat for every permission level, every edge case, every version

**With VitaminMCP**, you type this to your agent:

> **Prompt:** Spawn a bot, op it, open the `/shop` GUI, check slot 11 is a diamond sword listed at
> 100 coins, buy it, confirm the sword is in the bot's inventory, then deop.

and it drives the server, verifies each step, and tells you which one failed and what the server was
doing at that moment.

The difference that matters for an AI agent is not the automation — it is that **failures are
attributable.** A scenario stops at the first failing step and returns the events and log lines from
that instant, so there is no second round-trip to find out why.

---

## What a test looks like

Every action below is a real step. Type the prompt and let the agent build it, or hand
`bot_run_scenario` the array yourself.

### Buying from a shop GUI

> **Prompt:** Spawn a bot called `Tester1` and op it. Open the `/shop` GUI and check slot 11 holds a
> diamond sword named "Diamond Sword" with "100 coins" in its lore. Buy it, then confirm the sword
> ended up in the bot's own inventory. Deop when you are done.

```json
[
  {"action": "spawn",         "bot": "Tester1"},
  {"action": "console",       "command": "op Tester1"},
  {"action": "assert_player", "bot": "Tester1", "op": true},

  {"action": "command",       "bot": "Tester1", "command": "shop"},
  {"action": "wait_for",      "condition": "inventory_open", "name": "Tester1", "title": "Shop"},
  {"action": "assert_inventory", "bot": "Tester1", "size": 27, "slots": [
      {"slot": 11, "material": "DIAMOND_SWORD", "name": "Diamond Sword", "lore": "100 coins"}
  ]},

  {"action": "click_slot",    "bot": "Tester1", "slot": 11},
  {"action": "assert_event",  "eventType": "InventoryClickEvent", "player": "Tester1"},
  {"action": "wait_for",      "condition": "inventory_contains",
                              "name": "Tester1", "material": "DIAMOND_SWORD", "which": "player"},

  {"action": "close_menu",    "bot": "Tester1"},
  {"action": "console",       "command": "deop Tester1"}
]
```

### A login reward, and its cooldown

> **Prompt:** Test the daily reward plugin. Join as `Newcomer`, wait for the reward menu, check slot
> 13 is the claim button, click it and confirm the bot was told it claimed something. Then rejoin as
> the same player, click again, and confirm it is refused this time because the cooldown is still
> running.

The second half tests the refusal, which is the part that usually goes unverified: a cooldown
rejection is often one chat message with nothing behind it — no exception, no log line, no event.

```json
[
  {"action": "spawn",    "bot": "Newcomer"},
  {"action": "wait_for", "condition": "inventory_open", "name": "Newcomer", "title": "Daily Reward"},
  {"action": "assert_inventory", "bot": "Newcomer", "slots": [
      {"slot": 13, "material": "CHEST", "name": "Claim"}
  ]},
  {"action": "click_slot",     "bot": "Newcomer", "slot": 13},
  {"action": "assert_message", "bot": "Newcomer", "contains": "claimed"},

  {"action": "despawn", "bot": "Newcomer"},
  {"action": "spawn",   "bot": "Newcomer"},
  {"action": "wait_for","condition": "inventory_open", "name": "Newcomer"},
  {"action": "click_slot",     "bot": "Newcomer", "slot": 13},
  {"action": "assert_message", "bot": "Newcomer", "contains": "already"}
]
```

That second run works because **a bot's UUID is derived from its name.** `Newcomer` is the same
player across runs, so anything keyed on identity — permissions, cooldowns, stored data —
reproduces instead of drifting.

A failure comes back naming the step, the reason, and the evidence:

```jsonc
{"step": 5, "action": "assert_inventory", "passed": false,
 "detail": "slot 11 expected DIAMOND_SWORD but held AIR",
 "evidence": "events=[...] logs=[...]"}
```

---

## Tools

Two groups. **Session tools** live in `mcp-server` and are always present. **Agent tools** are
proxied from the plugin, so which ones exist is decided by the server you connected to —
`session_start` returns their real definitions in `agentTools`.

### Connection

| | |
|---|---|
| `session_start` | Connect to a server and its agent. Every other tool needs it |
| `session_reset` | Disconnect every bot, keeping the connection. Use between independent tests. World state is **not** rolled back |

### Players

| | |
|---|---|
| `bot_spawn` | Connect a bot and wait until it is standing in the world. UUID derives from the name |
| `bot_inspect` | What the bot's client was actually sent: menu contents, messages (chat, action bar, title, subtitle), boss bars and the sidebar scoreboard |
| `bot_run_scenario` | Run a whole scenario. Stops at the first failure with evidence attached |

### Server

| | |
|---|---|
| `server_info` | Version, TPS, players online, installed plugins, capture statistics |
| `command_exec` | Run a command as the console or as a player. **Changes the server** — absent entirely unless `read-only: false` |

### World and state

| | |
|---|---|
| `state_query` `kind="player"` | Position, gamemode, op, IP, and any permission nodes you name |
| `state_query` `kind="block"` | The block at a coordinate |
| `state_query` `kind="inventory"` | The menu a player has open — the only place a plugin GUI's contents exist |

### Events and logs

| | |
|---|---|
| `events_summary` | Counts by event type. Call this before `events_query` — it stays small however busy the server is |
| `events_query` | Individual events, filtered by type and player, paged by cursor |
| `logs_query` | Logs by minimum severity and regular expression |
| `exceptions_recent` | Distinct exceptions with occurrence counts and first-seen times. Pass `hash` for a stack trace |

### Waiting

`wait_for` blocks until a condition holds, checked every tick inside the server.

| Condition | |
|---|---|
| `inventory_open` | a menu opened, optionally matching a title |
| `inventory_contains` | an item reached a slot — for GUIs filled after they open |
| `event` | an event fired, optionally for one player |
| `player_online` / `player_offline` | a player joined or left |
| `player_state` | `online` / `gameMode` / `op` reached a value |
| `player_near` | a player came within a radius |
| `block_is` / `block_is_not` | a block became, or stopped being, a material |
| `log_matches` | a log line matched a regex — for async work that changes nothing observable |
| `ticks` | the server advanced N ticks |

**There is no sleep, and there will not be one.** A fixed wait is a guess about timing that is right
on an idle server and wrong on a busy one — that is the entire mechanism by which flaky tests are
made. On timeout, `wait_for` returns the events and logs from that moment.

### Actions — scenario steps

Available inside `bot_run_scenario`.

| | |
|---|---|
| `spawn` / `despawn` | connect or disconnect a bot |
| `move_to` | move to coordinates |
| `break_block` / `use_block` | break, or right-click a block — `use_block` is how you open a chest |
| `use_entity` | right-click an NPC, villager or armour stand, named by the coordinates it stands at |
| `click_slot` | click a slot: `left`, `right`, `shift_left`, `shift_right` |
| `close_menu` | close the open menu |
| `chat` / `command` | say something, or run a command as the bot |
| `console` | run a command as the console |
| `wait_for` | any condition above |

### Assertions — scenario steps

Verification is the point, so this is where the surface is widest.

| | Checks |
|---|---|
| `assert_inventory` | per slot: `material`, `name`, `amount`, `lore`, `customModelData`, `modelDataString`, `empty` — plus the menu's `title` and `size` |
| `assert_player` | `online`, `gameMode`, `op`. Waits rather than reads, because `/op` resolves asynchronously |
| `assert_block` | the material at a coordinate |
| `assert_event` | an event fired, optionally for one player, since the scenario began |
| `assert_message` | the server told this bot something containing a string |

Two of these exist because the server alone cannot answer the question:

- **`assert_message`** — a plugin's refusal is usually one message and nothing else. No exception,
  no console line, no event. Without it, "denied for lack of permission" and "silently did nothing"
  are indistinguishable. It matches action bar and title text too, since a plugin is as likely to
  refuse above the hotbar as in chat.
- **`assert_inventory` with `customModelData`** — with a resource pack, two buttons of the same
  material and name can be entirely different icons. Checking material and name alone misses icon
  bugs.

**Permissions** are tested through `state_query` with `permissions: [...]` rather than a dedicated
assertion — permission nodes can be tested but not enumerated, so you have to name the ones you care
about. **A scoreboard or boss bar value** is read straight off the player's screen with
`bot_inspect`, which is usually where a server draws money, region and quest progress. Anything
still plugin-specific after that is reached through `command_exec` and its output.

Two notes on calling them:

- **Pass proxied parameters flat, at the top level** — `{"kind": "player", "target": "Tester1"}`,
  not wrapped in an `arguments` object.
- The usual GUI loop is `command_exec` → `wait_for inventory_open` → `state_query kind="inventory"`,
  falling back to `bot_inspect` when the menu reads empty because the plugin draws it with packets.

Full parameters and the complete step reference are in [docs/usage.md](docs/usage.md).

---

## Requirements

| | |
|---|---|
| Minecraft server | **Paper 1.21.8 or later** (Purpur and other Paper forks work). Anything below will not load the agent at all ([design.md §5](docs/design.md)) |
| Java | 21. Needed to run it; only needed to build it if you are not using the [prebuilt jars](https://github.com/Backas03/VitaminMCP/releases/latest) |

### Version support

| Versions | Protocol | Status |
|---|---|---|
| 1.18 – 1.20.6 | 757 – 766 | Planned. Below the agent floor; needs it lowered, and backends across MCProtocolLib's package rename |
| **1.21, 1.21.1** | **767** | **Supported** |
| **1.21.2, 1.21.3** | **768** | **Supported** |
| **1.21.4** | **769** | **Supported** |
| **1.21.5** | **770** | **Supported** |
| **1.21.6** | **771** | **Supported** |
| **1.21.7, 1.21.8** | **772** | **Supported** |
| 1.21.9 – 26.2 | 773 – 776 | Planned. Needs a `bot/backends/backend-<protocol>` directory and a coordinate |

**1.21 through 1.21.8 are supported today**, and every one of them runs in the matrix
([versions.yaml](versions.yaml)). The other rows are on the roadmap without a date attached.

**You install one runner whatever the version.** It carries a backend per protocol and picks the
right one by asking the server what it speaks, so there is no version to choose and none to get
wrong.

Outside the supported range, things fail clearly rather than misbehaving: an older server declines
to load the agent, and a server whose protocol has no backend is named as such at startup — which
protocols the runner carries, and which one the server asked for.

Agent support and bot support can also differ. The agent needs a compatible Paper API; bots need a
backend for the server's protocol. So a server may be readable by the agent before bots can join
it — inspection, logs and events all still work without them.

---

## Install

### Three artifacts

**Download them from [Releases](https://github.com/Backas03/VitaminMCP/releases/latest)** — all
three are attached to every release, so nothing has to be built to try this.

To build them yourself instead:

```bash
./gradlew dist
```

Either way you end up with the same three files, and **each goes somewhere different.**

| File | Where | What |
|---|---|---|
| `VitaminMCP.jar` | the server's `plugins/` | the agent plugin |
| `mcp-server.jar` | anywhere (remember the path) | your MCP client launches it |
| `bot-runner.jar` | anywhere (remember the path) | `mcp-server` launches it as a child process |

**One runner, every supported version.** It carries a backend per protocol inside it and chooses
one by pinging the server before any bot connects, so the same file works on 1.21 and on 1.21.8.

### 1. Install the agent

Drop `VitaminMCP.jar` into the server's `plugins/` and start it once. **The first startup fails** —
that is intended.

```
[VitaminMCP] No auth token is configured. The MCP endpoint grants access to server
             internals, so it will not start unauthenticated. ...
[VitaminMCP] Suggested token (paste into config.yml): kQ8s...
```

An endpoint that comes up without a token hands the server console to anyone who can reach it, so
this is a **refusal to start**, not a warning ([design.md §14](docs/design.md)). Paste the token
from the log into `auth-token` in `plugins/VitaminMCP/config.yml` and start again.

```
[VitaminMCP] MCP endpoint listening on http://127.0.0.1:25585/mcp
```

That is the minimum install. Every other setting is documented in
[config.yml](agent/agent-mcp/src/main/resources/config.yml), alongside why each default is what it
is. Two defaults to know before you change anything:

- **`read-only: true` is the default.** State-changing tools like `command_exec` are not exposed at
  all — a default install cannot alter the server even with a valid token. Turn it off only when
  you need to.
- **Moving `bind-address` off loopback makes TLS mandatory.** The token grants console access, and
  over plain HTTP it crosses the network in the clear where anything on the path can read it. So
  that combination is a refusal to start, not a warning. Satisfy it with either `tls.enabled` (the
  agent serves HTTPS itself) or `tls.terminated-upstream` (a proxy in front terminates it). The
  agent will not generate a self-signed certificate for you — convenient, but it would teach every
  client to skip verification.

### 2. Server setup, if you want bots

Skip this section if you only need the agent.

Bots do not authenticate with Mojang. They imitate a proxy forwarding handshake to inject an
arbitrary UUID, so the backend has to be told to trust it
([design.md §3.1](docs/design.md)):

```properties
# server.properties
online-mode=false
```
```yaml
# spigot.yml
settings:
  bungeecord: true
```

> **Never expose a server in this configuration to the internet.** Anyone who can open a socket can
> impersonate anyone. This is a test-harness configuration, not a production one.

Recommended alongside those:

```properties
# server.properties
allow-flight=true
```

**The bot has no physics engine.** `move_to` sends one position packet at the destination and calls
itself on the ground; nothing simulates gravity, acceleration, or the path in between. That is
deliberate — a bot that reimplemented client movement would be testing our physics rather than your
plugin — but it means the server's flight check sees a player crossing distance no walking player
could, and kicks it with `Flying is not enabled on this server`. The bot vanishes mid-scenario and
the next step fails somewhere unrelated to the real cause.

With `allow-flight=true` the check is off and `move_to` behaves like a teleport. It costs nothing on
a test server. Leave it alone on a real one — and note this is another reason not to point bots at
production.

### 3. Connect an MCP client

For Claude Code:

```bash
claude mcp add vitaminmcp -- java -jar /absolute/path/mcp-server.jar
```

Or directly in `.mcp.json`:

```json
{
  "mcpServers": {
    "vitaminmcp": {
      "command": "java",
      "args": ["-jar", "/absolute/path/mcp-server.jar"]
    }
  }
}
```

`mcp-server` speaks stdio. It has no port and no token — it is a child process of the client, so
the trust relationship already exists. Only the agent side crosses a network, which is why only the
agent side authenticates.

### 4. Connect

Call `session_start` first. Every other tool depends on it.

```json
{
  "host": "127.0.0.1",
  "port": 25565,
  "mcpPort": 25585,
  "token": "auth-token from config.yml",
  "runnerJar": "/absolute/path/bot-runner.jar"
}
```

`runnerJar` can be omitted — it looks for the runner next to `mcp-server.jar`. Since `dist` puts
all three in one folder, you rarely need to write it.

A successful connection returns the server version, TPS and plugin list.

#### Or just ask

You do not have to write that JSON. Type the same facts to your agent instead — these are prompts,
copy one and fill in your own values.

**A server on this machine**

> **Prompt:** Connect to the Minecraft server on this machine. Minecraft is on port 25565, the
> VitaminMCP agent on 25585, and the token is `kQ8s…` from `plugins/VitaminMCP/config.yml`. Once you
> are in, tell me the server version and which plugins are loaded.

**Behind an SSH tunnel** — say which local ports the tunnel forwards

> **Prompt:** The test server is tunnelled to this machine — Minecraft on localhost:10000, the agent
> on localhost:25685. Token is `kQ8s…`. Connect and confirm it is alive.

**Remote, over TLS** — paste the block the agent printed at startup

> **Prompt:** Connect using this: host 203.0.113.10, mcpPort 25585, tls true, token `YLwNyFij…`,
> fingerprint `sha256:ffb61d8f…f163`. Minecraft is on 25565.

**Include the port numbers and the token.** Without them the agent has to guess at defaults, and a
wrong guess surfaces as a rejected token rather than a wrong address — the same failure whichever
detail was missing.

---

## A server on another machine

Two ways: forward the ports over SSH, or expose the agent with TLS. If you already have SSH to the
box, the tunnel is less work and exposes nothing.

### Over an SSH tunnel

Leave the agent on its loopback default and forward both ports:

```bash
ssh -L 25585:127.0.0.1:25585 -L 25565:127.0.0.1:25565 user@your-server
```

Then connect as if everything were local — `host: "127.0.0.1"`, no `tls`, no `tlsFingerprint`. The
agent sees a loopback connection because, from its side, that is what it is. Nothing on the server
is published to the network, and the token never crosses it in the clear: SSH is the transport
security that TLS would otherwise have to provide.

Forward **both** ports. `mcpPort` is how tools reach the agent, and `port` is where bots connect —
forwarding only the first gives you a working `server_info` and a `bot_spawn` that cannot connect.

> **Pick local ports that are actually free.** `ssh -L` binds the local side, and if something on
> your machine already holds that port, the tunnel does not take it — your requests reach the other
> program instead. The failure that produces is misleading: a different VitaminMCP agent answering
> on 25585 rejects your token, so it reads as a wrong token rather than a wrong destination. When
> in doubt map to a distinct local port (`-L 25685:127.0.0.1:25585`) and pass that as `mcpPort`.

### Exposing the agent with TLS

Once `bind-address` leaves loopback the agent will not start without TLS. Set up a certificate and
start it, and **the agent prints everything needed to connect**:

```
[VitaminMCP] MCP endpoint listening on https://203.0.113.10:25585/mcp
[VitaminMCP] Connect with session_start:
  "host": "203.0.113.10", "mcpPort": 25585, "tls": "true",
  "token": "YLwNyFij...",
  "tlsFingerprint": "sha256:ffb61d8f...f163"
```

Paste it and you are done. **A self-signed certificate still requires installing nothing on the
client** — `tlsFingerprint` pins that one certificate. No exporting, no copying, no truststore.

With a real certificate (Let's Encrypt and friends), drop `tlsFingerprint` and verification
proceeds normally.

---

## Running against several versions

The version matrix is [versions.yaml](versions.yaml), not code — adding one is a single block.
Server jars are downloaded from the PaperMC API and started natively (no Docker, no ViaProxy;
[design.md §15.1](docs/design.md)).

Every version needs a runner that speaks its protocol. Without one the server rejects the bot with
an honest `Outdated client!`.

---

## License

MIT — see [LICENSE](LICENSE).

The distributed jars bundle third-party code, relocated so it cannot collide with the server or
other plugins:

| | Bundled in | License |
|---|---|---|
| Jackson | `VitaminMCP.jar`, `mcp-server.jar` | Apache-2.0 |
| ClassGraph | `VitaminMCP.jar` | MIT |
| MCProtocolLib, and with it Netty, Gson, JJWT | `bot-runner.jar` (one build per protocol) | MIT / Apache-2.0 |

Their license and notice files travel inside the jars under `META-INF/` — relocating a package
renames it, it does not lift the obligation to carry the notice.

`paper-api`, `log4j-core` and the JetBrains annotations are compile-only and are not distributed.
The agent compiles against Paper's API, which is LGPL-3.0; the jar does not contain it, and the
server already provides it. Nothing here touches `paper-server` (GPL-3.0) — the agent uses the
Bukkit/Paper API only, never NMS.
