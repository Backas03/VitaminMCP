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

## Requirements

| | |
|---|---|
| Minecraft server | **Paper 1.21.8 or later**. Anything below will not load the agent at all ([design.md §5](docs/design.md)) |
| Java | 21, for both building and running |

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

A successful connection returns the server version, TPS and plugin list. From there:

- `events_summary` → `events_query` — what happened
- `logs_query`, `exceptions_recent` — what broke
- `bot_spawn` → `bot_run_scenario` — attach a bot and actually try it

Tool parameters and the full scenario step reference are in [docs/usage.md](docs/usage.md).

## Running against several versions

The version matrix is [versions.yaml](versions.yaml), not code — adding one is a single block.
Server jars are downloaded from the PaperMC API and started natively (no Docker, no ViaProxy;
[design.md §15.1](docs/design.md)).

Every version needs a runner that speaks its protocol. Without one the server rejects the bot with
an honest `Outdated client!`.
