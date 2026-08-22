---
name: verifying-a-minecraft-plugin
description: How to check that a Minecraft plugin actually works on a running Paper/Purpur server using VitaminMCP - connecting a session, reading logs, events and exceptions, spawning bots, opening plugin GUIs, running commands, and waiting for conditions. Use after building or deploying a server plugin, or whenever someone asks whether something works on the server, why the server died, what a plugin logged, or wants a GUI, command, permission or NPC tested. A build passing says nothing about whether the server boots, so this is the verification step for any Bukkit/Spigot/Paper plugin work.
---

# Verifying a Minecraft plugin with VitaminMCP

Real bots connect over the Minecraft protocol while an agent inside the server reports what
happened. This is the only thing that answers "does it actually work" — **a build passing says
nothing about whether the server boots**, and a plugin that boots can still refuse silently.

## Order of work

1. `session_start` — every other tool depends on it
2. **Observe before acting**: `logs_query`, `exceptions_recent`, `events_summary`, `state_query`
3. Spawn bots and drive them only once you know the server's current state
4. Wait with `wait_for`, never by sleeping

## 1. Connecting

**On the same machine, `session_start` takes no arguments.** The agent writes its host, both ports
and its token to `~/.vitaminmcp/agents/<port>.properties` while it runs, and this reads them. The
response reports `resolvedFrom`, so a session that connected to something unexpected says so.

Pass only what differs:

- `mcpPort` — which agent, when several run on this machine. A proxied network is several servers,
  one agent each, and with more than one running an omitted `mcpPort` is an error that lists them
  rather than a guess
- `port` — the Minecraft port bots connect to, which on a proxied network is the proxy's
- `host` and `token` — **required together for a server on another machine.** A token minted
  locally says nothing about a remote server and is deliberately not sent there. The token is the
  `auth-token` in that server's `plugins/VitaminMCP/config.yml`; it is a credential, so ask the
  user for it rather than hunting for it
- `tls: "true"` when the remote agent serves HTTPS, plus `tlsFingerprint` if its certificate is
  self-signed. The agent prints both at startup

**If nothing answers, the server has not got the plugin.** The `setup` prompt — in Claude Code,
`/mcp__vitaminmcp__setup` — installs it.

Call `server_info` right after connecting. A wrong host, port or token surfaces here instead of
inside some unrelated tool three steps later. The response also carries `agentTools`, the real
parameters of the proxied tools, which depend on the agent: a read-only install exposes no
`command_exec` at all.

### A session does not outlive the conversation reliably

`No session named 'x'. Open: []` means it is gone. Call `session_start` again; nothing is lost.
Do not assume a session is still open several turns later.

### A remote server usually needs an SSH tunnel

The agent port is not something to expose to the internet, so it is normally firewalled or bound
to loopback. The symptom is `Could not reach the agent at https://<host>:25585/mcp` — that is not
a token problem.

**Forward the Minecraft port too.** `session_start` takes one host and uses it for both the agent
and the bots.

```bash
ssh -p <ssh-port> -N -L 25585:127.0.0.1:25585 -L 25565:127.0.0.1:25565 <user>@<host>
```

Connect to `127.0.0.1` through the tunnel, and do not pass `tls` — the hop beyond the tunnel is
plain HTTP. The user has to start the tunnel, so write the command out and wait.

### After a restart, two different things have to come up

The agent port and the Minecraft port open at different times. An agent that answers (HTTP 401
means alive) does not mean the world has finished loading, and `session_start` will fail in the bot
runner with `SocketTimeoutException: Read timed out`. Wait for the agent first, then for the
Minecraft port to accept a server-list ping.

## 2. Observing

### `logs_query` matches the message, not the logger

The pattern is applied to the log message body. To find `"Discord webhook enabled"` logged by
`com.example.hook.DiscordWebhook`, search for `Discord webhook` — the class name will not match.

**There is no "last N lines" tool, by design.** Decide what you are looking for and search for it:
the line a plugin prints when it finishes enabling, or the line the feature under test produces.
`level` raises the severity floor, which is how to find out whether your own plugin is warning
about something.

### `command_exec` returns only what the command answered synchronously

A plugin that replies from an async callback returns `output: []`. **That is not a failure.** The
reply reaches the console a moment later, so find it with `logs_query`.

```
command_exec("myplugin top")   →  output: []
logs_query(pattern="rank|points")  →  "1. Tester 10 points"
```

Console output carries colour codes, so search for plain text and leave them out of the pattern.

### `as` runs the command as a real player, permissions and all

Vanilla commands work through `as` — `/list`, `/tp`, `/gamemode` — because the server matches them
with its own dispatcher against that player's permissions. **They are op-only by default**, so a
bot that is not op is refused, which is exactly what you want when the permission is the thing
under test. `op` the bot when it is not.

**A command run as a player answers that player, not the console.** `output` is usually empty even
when the command worked; the reply went to the client, so read it in `bot_inspect`'s `messages`.

```
command_exec("list", as="Tester1")  →  dispatched: true, output: []
bot_inspect("Tester1")              →  "There are 1 of a max of 20 players online: Tester1"
```

### `dispatched: false` says why

Nothing ran — but the reason separates the two causes, which the server itself does not. Paper
prunes a command a player may not use during parsing and then reports the same "no" it reports for
a command that does not exist, telling the player nothing, not even "unknown command":

| `reason` says | What happened |
|---|---|
| `was refused before it executed, because ... does not have <node>` | The command exists and the sender may not use it. Grant the node, or op the bot |
| `this server has no command named '...'` | Nothing by that name. Check the spelling, or whether the plugin registered it |
| `nothing on this server answers to '...'` | Asked by a bot that is not op, where the two cannot be told apart. Run it as the console to settle it |

### `exceptions_recent` — do not adopt another plugin's exception

The default output has no stack traces; pass a `hash` for one. **Startup produces exceptions from
every other plugin on the server.** Open the stack and see which jar it came from before reporting
it as yours. A `NullPointerException: Name is null` looks like your code and is usually
`Enum.valueOf(null)` somewhere else entirely.

### `events_summary` before `events_query`

The summary is small however busy the server is. Read the counts per type, then dig into the one
type worth reading. High-frequency events — `PlayerMoveEvent`, `BlockPhysicsEvent`, chunk and
entity movement — are excluded unless a query names them.

**If the plugin fires a custom event, assert on the event rather than on a log line.** Log wording
changes under refactoring; an event type rarely does.

### `state_query` — read it instead of inferring it

`kind="player"` also answers permission questions: put nodes in `permissions` and it reports
whether each resolves. It cannot list them.

`kind="inventory"` is **the only way to see a plugin GUI.** A menu drawn with packets does not
exist as a server-side inventory, so nothing else can show it.

## 3. Driving bots

`bot_spawn` returns once the bot is standing in the world. **A bot's UUID is derived from its
name**, so the same name is the same player every run and permission-dependent behaviour is
reproducible.

### The first ~2 seconds after joining are locked out

The server is loading the player's data, and everything is refused in that window. A bot that
spawns and acts immediately **looks like it acted and was ignored.** Commands are usually told why;
entity interaction — right-clicking an NPC — says nothing at all.

Waiting on a data-load log line is **not enough**: the lockout is its own timer, not a consequence
of loading. This is close to the only case where waiting a fixed number of `ticks` is honest rather
than a guess.

### When nothing happens, read the bot's messages first

`bot_inspect` reports what the client was actually sent: chat, action bar, title, subtitle, boss
bars, scoreboard. **A refusal is normally one message with no event, no exception and no console
line behind it** — so from the server's side, "refused for lack of permission" and "silently did
nothing" look identical. Look here before forming a theory.

Live state a server shows a player — timers, balances, region names, quest progress — is usually
drawn in the scoreboard or a boss bar and visible nowhere else.

### Permissions

If the plugin's source is available, read which node the action needs and grant that. If it is not,
granting op, testing, and taking op away again is an acceptable substitute. **Always take it away.**

## 4. Waiting

**Never sleep.** Use `wait_for`. A fixed wait is a guess that is right on an idle server and wrong
on a busy one. Conditions: `ticks`, `block_is`, `block_is_not`, `event`, `player_online`,
`player_offline`, `player_near`, `inventory_open`, `inventory_contains`, `log_matches`.

- **Wait for `inventory_open` before reading a GUI.** A menu opening is not synchronous with the
  command that opened it
- Work with no visible effect — an async data load — is waited for with `log_matches`
- A timeout returns the events and log lines from that moment, which is usually where the cause is

## Cautions

- **`command_exec` changes the server.** Get the user's confirmation for anything hard to reverse:
  payouts, resets, wipes. If the permission system blocks a call, tell the user rather than looking
  for a way around it
- **Restarting and deploying are outside VitaminMCP.** `/stop` works through `command_exec`, but
  starting the server again belongs to whatever script or tooling the server has
- **Do not substitute a plugin reload for a restart.** A plugin with static singletons or a module
  framework does not re-initialise on reload, and you get something alive but half-assembled
- **Overwriting the jar of a running server breaks classes that have not been loaded yet.** New
  code applies from the next restart
- Bots need `online-mode=false` and `bungeecord: true` on the server, which is a test-harness
  configuration. **Never point them at a server reachable from the internet** — anyone who can open
  a socket to it can impersonate anyone
