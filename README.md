# VitaminMCP

Expose what happens inside a Minecraft server over MCP, and test plugins with real protocol bots.

- The **agent** is a plugin that runs inside the server. It captures events, logs and exceptions,
  and answers MCP tool calls. It is useful on its own — install only this and you can ask a live
  server what just happened.
- The **MCP server** is a process your client (Claude Code, etc.) launches. It attaches bots and
  runs scenarios.

Once installed, [docs/usage.md](docs/usage.md) covers actually using it.
Design rationale is in [docs/design.md](docs/design.md), contribution rules in
[CONTRIBUTING.md](CONTRIBUTING.md).

## What you can do with it

**Ask a running server what happened.** Events by type over a window, logs by regex, exceptions
collapsed by occurrence, the current state of a player or a block. No restart, no debug build, no
`println` added and redeployed. This works on a production server — the read-only default cannot
change anything.

**Reproduce a bug with a real player.** `bot_spawn` connects an actual protocol client, not a fake
`Player` object. It has an inventory, a position, a gamemode, permissions and an IP. Its UUID is
derived from its name, so `Tester1` is the same player today as yesterday and permission-dependent
behaviour repeats instead of drifting.

**Test a plugin GUI, including how it looks.** Open a menu, read every slot's material, display
name, lore and CustomModelData, click a slot, assert on what came back. CustomModelData is the part
that usually goes untested — with a resource pack, two buttons of the same material and name can be
completely different icons, so checking material and name alone misses icon bugs.

**See what the server cannot tell you.** A plugin drawing its GUI with ProtocolLib or packetevents
leaves the server-side inventory empty while the player sees a full menu; `bot_inspect` reads what
the client was actually sent. It also returns the messages the server sent that bot — which is
where a refusal like "you lack permission" lives. Those never reach the console, so from the
server's side a declined command and one that silently did nothing look identical.

**Run the same scenario across versions.** The matrix is [versions.yaml](versions.yaml), and each
server is started natively from a PaperMC build.

A concrete session looks like: spawn a bot, op it, run the command that opens your menu, wait for
the menu to open, read the slots, assert the buttons are what you shipped, deop, and you have the
whole trace of what the server did in between.

## Requirements

| | |
|---|---|
| Minecraft server | **Paper 1.21.8 or later**. Anything below will not load the agent at all ([design.md §5](docs/design.md)) |
| Java | 21, for both building and running |

### Version support

| Versions | Status | |
|---|---|---|
| 1.18 – 1.21.6 | Planned | Below the current agent floor. Needs the floor lowered and a runner per protocol |
| **1.21.7, 1.21.8** | **Supported** | Protocol 772 — one runner covers both. Both run in the matrix ([versions.yaml](versions.yaml)) |
| 1.21.9 – 26.2 | Planned | Changed protocol. Needs a sibling `bot-runner-<protocol>` built against the matching MCProtocolLib release |

Only the middle row is tested today; the other two are intent, not a promise about a date. Pointing
the harness at a version outside it fails honestly rather than quietly — an unsupported server
refuses to load the agent, and a bot without a runner for its protocol is rejected with
`Outdated client!`.

The two halves can move independently. The agent's floor is what Paper API it compiles against; the
bot's reach is which protocol runners exist. A version can be readable by the agent before any bot
can connect to it, and that is a useful state — investigation works without bots.

## Three artifacts

```bash
./gradlew dist
```

Three files land in `build/dist/`. **Each goes somewhere different.**

| File | Where | What |
|---|---|---|
| `VitaminMCP.jar` | the server's `plugins/` | the agent plugin |
| `mcp-server.jar` | anywhere (remember the path) | your MCP client launches it |
| `bot-runner-772.jar` | anywhere (remember the path) | `mcp-server` launches it as a child process |

The number in the runner's filename is a **protocol number**, not a Minecraft version. The single
772 runner covers both 1.21.7 and 1.21.8. A server speaking a different protocol needs its own
runner (see [versions.yaml](versions.yaml)).

---

## 1. Install the agent

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
is. Two worth knowing up front:

- **`read-only: true` is the default.** State-changing tools like `command_exec` are not exposed at
  all — a default install cannot alter the server even with a valid token. Turn it off only when
  you need to.
- **Moving `bind-address` off loopback makes TLS mandatory.** The token grants console access, and
  over plain HTTP it crosses the network in the clear where anything on the path can read it. So
  that combination is a refusal to start, not a warning. Satisfy it with either `tls.enabled` (the
  agent serves HTTPS itself) or `tls.terminated-upstream` (a proxy in front terminates it). The
  agent will not generate a self-signed certificate for you — convenient, but it would teach every
  client to skip verification.

## 2. Server setup, if you want bots

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

## 3. Connect an MCP client

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

## 4. Connect

Call `session_start` first. Every other tool depends on it.

```json
{
  "host": "127.0.0.1",
  "port": 25565,
  "mcpPort": 25585,
  "token": "auth-token from config.yml",
  "runnerJar": "/absolute/path/bot-runner-772.jar"
}
```

`runnerJar` can be omitted — it looks for a `bot-runner-*.jar` next to `mcp-server.jar`. Since
`dist` puts all three in one folder, you rarely need to write it.

### A server on another machine

Two ways: forward the ports over SSH, or expose the agent with TLS. If you already have SSH to the
box, the tunnel is less work and exposes nothing.

#### Over an SSH tunnel

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

#### Exposing the agent with TLS

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

A successful connection returns the server version, TPS and plugin list.

## Tools

Two groups, and the difference matters. **Session tools** live in `mcp-server` and are always
present. **Agent tools** are proxied from the plugin — which ones exist is decided by the server
you connected to, so `session_start` returns their real definitions in `agentTools` rather than
`mcp-server` guessing at startup.

### Session tools — bots and the connection

| Tool | What it does |
|---|---|
| `session_start` | Connect to a server and its agent. Every other tool needs it. `host`, `port`, `mcpPort`, `token`, `runnerJar`, `tls`, `tlsFingerprint` |
| `session_reset` | Disconnect every bot, keeping the connection. Use between independent tests so one does not inherit the other's players. World state is **not** rolled back |
| `bot_spawn` | Connect a bot and wait until it is standing in the world. The UUID is derived from the name, so the same name is the same player every run. `name`, `clientIp` |
| `bot_inspect` | What the bot's client was actually told. Use when the server-side menu reads empty but a player would see a full one, and to read the messages the server sent that bot — a refusal like "you lack permission" appears nowhere else |
| `bot_run_scenario` | Run a declarative scenario. Stops at the first failure and reports which step failed, why, and what the server was doing at that moment |

### Agent tools — reading the server

| Tool | What it does |
|---|---|
| `server_info` | Implementation, version, TPS, online players, installed plugins, capture statistics. Start here when you do not know what you are looking at |
| `events_summary` | Counts captured events by type over a window. **Always call this before `events_query`** — it stays small however busy the server is, and it tells you which types are worth asking for |
| `events_query` | Individual captured events. High-frequency types are excluded unless you name them in `types`. Page with `cursor` |
| `logs_query` | Search logs by minimum severity and regular expression. There is no "last N lines" tool — search for what you are looking for |
| `exceptions_recent` | Distinct exceptions, most recent first, collapsed with occurrence count and first-seen time. Pass `hash` for one full stack trace |
| `state_query` | Read current state instead of inferring it: `kind="player"` (position, gamemode, op, and permission nodes you name), `kind="block"`, `kind="inventory"` (the menu a player has open — the only place a plugin GUI's contents exist) |
| `wait_for` | Block until something becomes true. Conditions: `ticks`, `block_is` / `block_is_not`, `event`, `player_online` / `player_offline`, `player_near`, `inventory_open`, `inventory_contains`. On timeout the response carries the events and logs from that moment |
| `command_exec` | Run a command, as the console by default. **Changes the server**, so it is absent entirely unless `read-only: false` |

Two things that catch people out:

- **Pass proxied parameters flat, at the top level** — `{"kind": "player", "target": "Tester1"}`,
  not wrapped in an `arguments` object.
- **`wait_for` exists because there is no sleep step, and there will not be one.** A fixed wait is
  a guess about timing that is right on an idle server and wrong on a busy one. Name the thing you
  are waiting for and the agent checks every tick inside the server.

The usual GUI-testing loop is `command_exec` → `wait_for inventory_open` → `state_query` with
`kind="inventory"`, falling back to `bot_inspect` if the menu reads empty because the plugin draws
it with packets.

Full parameters and the scenario step reference are in [docs/usage.md](docs/usage.md).

## Running against several versions

The version matrix is [versions.yaml](versions.yaml), not code — adding one is a single block.
Server jars are downloaded from the PaperMC API and started natively (no Docker, no ViaProxy;
[design.md §15.1](docs/design.md)).

Every version needs a runner that speaks its protocol. Without one the server rejects the bot with
an honest `Outdated client!`.
