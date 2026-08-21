# VitaminMCP

**Minecraft automation testing MCP server plugin for AI agents.**

VitaminMCP is a **Paper/Purpur server plugin.** Drop `VitaminMCP.jar` into `plugins/`, start the
server, and it opens an MCP endpoint from inside the running server — so an AI agent can drive that
server and read back what happened, while real bot clients connect to it over the Minecraft
protocol.

**Nothing about the plugin you are testing changes.** No test framework to adopt, no source to
instrument, no harness to compile against, no mock server standing in for a real one: the plugin
under test runs on a real server through its real lifecycle, and VitaminMCP watches it from the next
plugin slot over. Which also means it works on plugins you did not write — anything already
installed is testable.

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
- Drive **several servers at once** — one session per backend of a BungeeCord network, bots staying
  connected across all of them
- Paper / Purpur **1.21 through 1.21.8**, from one install — the runner works out which protocol the
  server speaks and adapts

Full usage is in [docs/usage.md](docs/usage.md), design rationale in
[docs/design.md](docs/design.md), contribution rules in [CONTRIBUTING.md](CONTRIBUTING.md),
release steps in [docs/publishing.md](docs/publishing.md).

---

## How it fits together

Three jars, in three different places. Only the first is a Minecraft plugin.

```text
  your MCP client (Claude Code, ...)
        |
        |  stdio
        v
  mcp-server.jar ---- HTTP(S) + token ---->  VitaminMCP.jar  <- the plugin, inside your server
        |                                    sees events, logs, exceptions, live state
        |  spawns
        v
  bot-runner.jar ---- Minecraft protocol ->  the same server, on :25565
                                             sees what a player's client was actually sent
```

| | Runs | Role |
|---|---|---|
| `VitaminMCP.jar` | **in the server, as a plugin** | Listens to every event, taps the log, and serves an authenticated MCP endpoint. The only piece with a view of server internals |
| `mcp-server.jar` | on your machine, as a child of your MCP client | Speaks stdio to the client and HTTP to the plugin, and owns the bots |
| `bot-runner.jar` | on your machine, as a child of `mcp-server` | Connects real clients over the real protocol — login, packets, GUIs and all |

**Why the agent has to be a plugin.** Half of what a test needs to assert on has no protocol
representation. Whether a `PlayerJoinEvent` fired, a stack trace on the console, whether `/op`
actually resolved, what a permission node evaluates to — none of that reaches a connected client.
Only code running inside the server can see it. Hence the split: the plugin reports what the
*server* did, bots observe what a *player* was shown, and a single assertion can draw on both.

**The plugin is worth installing on its own.** With nothing else set up, it turns "find out why the
server died last night" into a question you can ask — events, logs, exceptions, plugin list, live
state, all over MCP ([design.md §1](docs/design.md)). Bots are opt-in, and so is the server
configuration they need ([Server setup](#2-server-setup-if-you-want-bots)); read-only is the
default, so a plugin-only install cannot alter the server at all.

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
| `session_start` | Connect to a server and its agent. Every other tool needs it. Several sessions can be open at once — one per backend of a proxied network |
| `session_reset` | Disconnect every bot, keeping the connection. Use between independent tests. World state is **not** rolled back. `close: true` ends the session instead |

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
| `move_to` | walk to coordinates by default; use `mode: "teleport"` for fast setup placement. Optional `timeoutMillis` distinguishes a sealed route from a walk that did not arrive in time |
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
| Minecraft server | **Paper 1.21 or later** (Purpur and other Paper forks work). Anything below will not load the agent at all ([design.md §5](docs/design.md)) |
| Java | 21, on the server and on the machine running your MCP client. Needed to build it too, if you are not using the [prebuilt jars](https://github.com/Backas03/VitaminMCP-minecraft/releases/latest) |
| Node | 18.17 or later, for `npx`. Only to install the client side the easy way — [the jars need none](#installing-from-the-jars-instead) |

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

Two halves, and neither is useful alone: an **MCP server on your machine**, which your client
launches, and the **agent plugin on the Minecraft server**, which is where everything worth asking
about happens.

### 1. Connect your MCP client

**In Claude Code**, install the plugin — it brings the MCP server and the working knowledge of how
to drive it, as a skill that loads itself when a question calls for it:

```bash
/plugin marketplace add Backas03/VitaminMCP-minecraft
```

```bash
/plugin install vitaminmcp@vitaminmcp
```

**Any other MCP client**, or Claude Code without the skill:

```bash
claude mcp add vitaminmcp -- npx -y vitaminmcp
```

Or directly in `.mcp.json`:

```json
{
  "mcpServers": {
    "vitaminmcp": {
      "command": "npx",
      "args": ["-y", "vitaminmcp"]
    }
  }
}
```

That is the whole client side. Nothing to download by hand and no path to get right: the
[`vitaminmcp`](https://www.npmjs.com/package/vitaminmcp) package fetches the jars it needs on first
run, into `~/.vitaminmcp/jars/<version>/`, each checked against a SHA-256 pinned into the package
when it was published.

`mcp-server.jar` is two megabytes and is waited for. `bot-runner.jar` is ninety, so it arrives in
the background — a session that never spawns a bot never waits for it, and one that does waits
inside the call rather than inside your client's startup timeout.

`mcp-server` speaks stdio. It has no port and no token: it is a child process of the client, so the
trust relationship already exists. Only the agent side crosses a network, which is why only the
agent side authenticates.

> Needs **Node 18.17+** for `npx`, and **Java 21** to run the jars. No npm, or nothing to download
> with? [Install from the jars](#installing-from-the-jars-instead).

### 2. Install the plugin on the server

Ask, and the agent does it — this is a command your client offers once step 1 is done:

```text
/mcp__vitaminmcp__setup
```

It checks the server is Paper 1.21+, puts the jar in `plugins/`, restarts, and connects. By hand
instead:

**Download `VitaminMCP.jar`** from
[Releases](https://github.com/Backas03/VitaminMCP-minecraft/releases/latest) into the server's
`plugins/` — an ordinary Bukkit/Paper plugin, no server flags and no java agent to attach — and
start the server.

```
[VitaminMCP] No auth token was configured, so one was generated and written to config.yml: kQ8s...
[VitaminMCP] MCP endpoint listening on http://127.0.0.1:25585/mcp
```

**You do not need to copy that token.** A client on the same machine reads it from the agent's own
handshake. Copy it only for a client somewhere else.

That is the minimum install. Every other setting is documented in
[config.yml](agent/agent-mcp/src/main/resources/config.yml), alongside why each default is what it
is. Three defaults to know before you change anything:

- **`read-only: true` is the default.** State-changing tools like `command_exec` are not exposed at
  all — a default install cannot alter the server even with a valid token. Turn it off only when
  you need to.
- **The endpoint never opens unauthenticated.** An empty `auth-token` is filled in with a generated
  one rather than waved through, and if it cannot be written the plugin still refuses to start
  ([design.md §14](docs/design.md)). What was never negotiable is that a token exists; making you
  fetch one out of a crash log was not part of it.
- **Moving `bind-address` off loopback makes TLS mandatory.** The token grants console access, and
  over plain HTTP it crosses the network in the clear where anything on the path can read it. So
  that combination is a refusal to start, not a warning. Satisfy it with either `tls.enabled` (the
  agent serves HTTPS itself) or `tls.terminated-upstream` (a proxy in front terminates it). The
  agent will not generate a self-signed certificate for you — convenient, but it would teach every
  client to skip verification.

### 3. Server setup, if you want bots

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

`move_to` walks to its destination by default, using the same client-side physics loop that sends
the movement packets between the two points. That means plugins listening for pressure plates and
movement events observe the route. A path that cannot be found fails with `No path exists`; a path
that does not arrive before `timeoutMillis` fails with `did not arrive ... within ...`.

For setup steps that only need a bot at a coordinate, use `"mode":"teleport"`. This retains the
legacy one-position-packet behaviour and is still fast, but it does not fire the events that a
walking player would have caused.

Walking does not dig through or place blocks. The pathfinder is intentionally configured for
ordinary traversal so a test wall remains a test wall.

`allow-flight=true` is still useful only for legacy Java-runner scenarios that use the packet
teleport, and for an explicit `mode: "teleport"` step. Leave it alone on a real one — and note this
is another reason not to point bots at production.

### 4. Connect

```text
session_start
```

No arguments. The agent writes its host, both ports and its token to
`~/.vitaminmcp/agents/<port>.properties` while it runs, and `session_start` reads them — so for a
server on this machine there is nothing to pass and nothing to look up. A successful connection
returns the server version, TPS and plugin list.

Pass what differs, and only that. A server somewhere else needs `host` and `token`, because a token
minted on this machine says nothing about a server on another one and is not sent there:

```json
{
  "host": "203.0.113.10",
  "token": "auth-token from config.yml",
  "tls": "true",
  "tlsFingerprint": "sha256:ffb61d8f...f163"
}
```

**A proxied network is several servers.** Open one session per backend — they coexist, and starting
one never disturbs another, which matters because closing a session disconnects its bots. `port` is
the proxy's in every session; what tells them apart is `mcpPort`, the agent inside each backend.
With more than one agent running locally that is also what picks between them, and omitting it is
an error naming them rather than a guess.

```jsonc
session_start {"session": "lobby",    "mcpPort": 25585, "port": 25577}
session_start {"session": "survival", "mcpPort": 25586, "port": 25577}
bot_spawn     {"session": "lobby", "name": "Tester1"}
```

Every other tool takes `session`. Omit it and it resolves only while one session is open; with
several it is an error naming them, rather than a guess about which server you meant. The full
walkthrough is in [docs/usage.md](docs/usage.md).

#### Or just ask

These are prompts — copy one and fill in your own values.

**A server on this machine**

> **Prompt:** Connect to the Minecraft server on this machine, then tell me the server version and
> which plugins are loaded.

**Behind an SSH tunnel** — say which local ports the tunnel forwards

> **Prompt:** The test server is tunnelled to this machine — Minecraft on localhost:10000, the agent
> on localhost:25685. Token is `kQ8s…`. Connect and confirm it is alive.

**Remote, over TLS** — paste the block the agent printed at startup

> **Prompt:** Connect using this: host 203.0.113.10, mcpPort 25585, tls true, token `YLwNyFij…`,
> fingerprint `sha256:ffb61d8f…f163`. Minecraft is on 25565.

**For anything not on this machine, include the port numbers and the token.** Without them the
agent has to guess at defaults, and a wrong guess surfaces as a rejected token rather than a wrong
address — the same failure whichever detail was missing.

### Installing from the jars instead

`npx` is a convenience, not a requirement. **Three artifacts**, all attached to every
[release](https://github.com/Backas03/VitaminMCP-minecraft/releases/latest), and **each goes
somewhere different:**

| File | Where | What |
|---|---|---|
| `VitaminMCP.jar` | the server's `plugins/` | the agent — an ordinary Bukkit/Paper plugin |
| `mcp-server.jar` | anywhere (remember the path) | your MCP client launches it |
| `bot-runner.jar` | beside `mcp-server.jar` | `mcp-server` launches it as a child process |

To build them yourself instead:

```bash
./gradlew dist
```

Either way, point the client at the jar rather than at the package:

```bash
claude mcp add vitaminmcp -- java -jar /absolute/path/mcp-server.jar
```

`bot-runner.jar` is found beside `mcp-server.jar`, which is where `dist` puts it.
`VITAMINMCP_RUNNER_JAR`, or `session_start`'s `runnerJar`, names it anywhere else.

**One runner, every supported version.** It carries a backend per protocol inside it and chooses
one by pinging the server before any bot connects, so the same file works on 1.21 and on 1.21.8.

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

The same scenario can be run across every supported version in one pass. The matrix is
[versions.yaml](versions.yaml), not code — adding a version is a single block. Server jars are
downloaded from the PaperMC API and started natively (no Docker, no ViaProxy;
[design.md §15.1](docs/design.md)).

**The protocol is deliberately not in that file.** The runner asks each server what it speaks and
loads the matching backend, so a version needs nothing there beyond the build to download.

A *new protocol* — 1.21.9 and later — is a different matter: it needs a
`bot/backends/backend-<protocol>` directory with the matching MCProtocolLib coordinate, and only
the files that genuinely differ. Across 1.21 to 1.21.8 that came to five small files, and 1.21.5
onward needed none. Until one exists, a server on that protocol is refused at startup by name —
which protocols the runner carries, and which one the server asked for — rather than by an
`Outdated client!` arriving from the server later.

See [docs/design.md §4.4](docs/design.md) for why it is built this way, and
[docs/multi-version.md](docs/multi-version.md) for the reasoning that got there.

---

## License

MIT — see [LICENSE](LICENSE).

The distributed jars bundle third-party code, relocated so it cannot collide with the server or
other plugins:

| | Bundled in | License |
|---|---|---|
| Jackson | `VitaminMCP.jar`, `mcp-server.jar` | Apache-2.0 |
| ClassGraph | `VitaminMCP.jar` | MIT |
| MCProtocolLib, and with it Netty, Gson, JJWT | `bot-runner.jar` — one build of it per supported protocol, each in its own embedded jar | MIT / Apache-2.0 |

Their license and notice files travel inside the jars under `META-INF/` — relocating a package
renames it, it does not lift the obligation to carry the notice.

`paper-api`, `log4j-core` and the JetBrains annotations are compile-only and are not distributed.
The agent compiles against Paper's API, which is LGPL-3.0; the jar does not contain it, and the
server already provides it. Nothing here touches `paper-server` (GPL-3.0) — the agent uses the
Bukkit/Paper API only, never NMS.
