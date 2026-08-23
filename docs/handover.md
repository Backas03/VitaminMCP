# Handover — the mineflayer migration

> **Start with [next.md](next.md) instead.** This file is the migration's handover and its "where
> things stand" section is a snapshot from 2026-08-22, before 2.1.0, 2.1.1, 2.2.0 and the
> dogfooding work. Section 2 onward — the tools, the commands, the traps — is still accurate and
> is why this file is kept.

Written 2026-08-22 for whoever picks this up next. It assumes you have the repository and nothing
else. Read `mineflayer-roadmap.md` for *what* to build and in what order; this is *how to work
here* — the tools, the commands, and the traps that have already cost time.

---

## 1. Where things stand

Branch: **`feat/mineflayer-runner`**.

| Stage | | |
|---|---|---|
| 0 | the identity spike | ✅ done |
| 1 | process and lifecycle | ✅ done |
| 2 | the packet verbs | ✅ done |
| 3 | `inspect` and the client view | ✅ done |
| 4 | movement and pathfinding | ✅ done |
| 5 | the parity gate | ✅ done |
| 6 | new verbs | ✅ done |
| 7 | live bot view | ✅ done |
| 8 | distribution | ✅ Windows scope done; Linux/macOS planned |
| 9 | deletion and docs | ✅ done |

Stages 0–9 have passed their implementation gates on the Node runner. The Java runner was retired
in Stage 9 after the parity gate; the historical parity record remains in
`bot/bot-runner-node/DIFFERENCES.md`.

### The shape of it

The MCP server (Java) launches a **bot runner** as a child process and talks to it over stdio with
a tab-separated line protocol. That seam is the whole migration:

```
MCP client ──stdio MCP──> mcp-server.jar ──tab-separated stdio──> bot runner ──Minecraft protocol──> server
                                │                                                                      │
                                └────────────────HTTP MCP──────────> VitaminMCP.jar (agent) ───────────┘
```

- `bot/bot-runner-node/` — the new runner. `runner.mjs` is the entry point.
- `bot/bot-core/.../BotRunner.java` — launches the Node script or native runner executable.
- `docs/mineflayer-roadmap.md` — the plan.
- `bot/bot-runner-node/DIFFERENCES.md` — **every place the two runners disagree, with reasons.**
  Stage 5 accepts or rejects the migration on this list. Add to it as you make differences.

---

## 2. Driving the Minecraft server: VitaminMCP

This is the project's own tool, and it is how you find out whether anything actually works.
**A build passing says nothing about whether the server boots**, and a plugin that boots can still
refuse silently.

### Connecting

`session_start` with **no arguments** on this machine. The agent writes its host, ports and token
to `~/.vitaminmcp/agents/<port>.properties` while it runs, and the tool reads them. The response
reports `resolvedFrom`, so a session that connected somewhere unexpected says so.

If nothing answers, the server does not have the plugin. There is a `setup` prompt that installs
it.

**A session does not reliably outlive the conversation.** `No session named 'x'` means it is gone —
call `session_start` again, nothing is lost.

Which MCP server: use the **npx one** (`plugin:vitaminmcp:vitaminmcp`, `npx -y vitaminmcp`). The
old `java -jar C:/vitaminmcp/mcp-server.jar` registration was removed on 2026-08-22 and
`C:\vitaminmcp\` is now dead weight.

### The tools, and what each is actually for

| tool | use it for |
|---|---|
| `server_info` | call right after connecting. A wrong host/port/token surfaces here instead of three steps later. Also lists `agentTools` — the real parameters, as the agent defines them |
| `logs_query` | matched against the **message**, not the logger. To find something logged by `com.example.DiscordWebhook`, search for the message text; the class name will not match. There is no "last N lines" tool by design |
| `events_summary` → `events_query` | always the summary first; it is small however busy the server is. High-frequency types (`PlayerMoveEvent`, `BlockPhysicsEvent`, chunk/entity movement) are excluded unless you name them in `types` |
| `exceptions_recent` | startup produces exceptions from every other plugin. Open the stack and see which jar it came from before believing it is yours |
| `state_query` | `kind="player"` also answers permission questions via `permissions`. `kind="block"` for what a block *is now*. `kind="inventory"` is the **only** way to see a plugin GUI |
| `command_exec` | runs a command as the console, or as a player with `as`. **This changes the server.** Returns only what the command answered synchronously — a plugin replying from an async callback returns `output: []`, which is not a failure; find the reply with `logs_query`. `dispatched: false` always carries a `reason` separating "no such command" from "the sender was not permitted"; vanilla commands are op-only, and a command run `as` a player answers that player, so read the reply with `bot_inspect` |
| `wait_for` | never sleep. `ticks`, `block_is`, `block_is_not`, `event`, `player_online`, `player_offline`, `player_near`, `inventory_open`, `inventory_contains`, `log_matches` |
| `bot_spawn` / `bot_inspect` / `bot_run_scenario` | the bot side |

Console output carries colour codes. Search for plain text and leave them out of the pattern.

### Starting and stopping the Minecraft server

**The server runs in one visible cmd window, and you type into that same window.** Do not start it
as a background task and do not open a second window.

Start one only if no server window exists (check for a `cmd.exe` with a `java.exe` child):

```powershell
Start-Process cmd.exe -ArgumentList '/k', 'title Paper 1.21.8 (VitaminMCP scratch) && cd /d C:\server\1.21.8 && "C:\Program Files\Java\jdk-21\bin\java.exe" -Xms2G -Xmx2G -jar paper-1.21.8-60.jar nogui'
```

Type into the existing one:

```powershell
$w = New-Object -ComObject WScript.Shell
if ($w.AppActivate('Paper 1.21.8')) { Start-Sleep -Milliseconds 500; $w.SendKeys("stop{ENTER}") }
```

Two traps here, both already hit:

- `AppActivate` needs the **window title, not the PID**. A console window is owned by `conhost.exe`,
  so the cmd process reports `MainWindowHandle: 0` and activating by PID returns false.
- `SendKeys` reports success for a window that never received anything. Verify by reading
  `C:\server\1.21.8\logs\latest.log`.

**Never `Stop-Process` the server.** On Windows that is `TerminateProcess` — no shutdown hook, so
Paper never saves. Stop it with `command_exec("stop")` through the agent, or by typing `stop`.

The scratch server is `C:\server\1.21.8`: Paper 1.21.8 build 60, `online-mode=false`, default
BungeeCord parsing, `gamemode=creative`, `spawn-protection=0`, agent at `read-only: false`.
Its token is in `plugins/VitaminMCP/config.yml`.

---

## 3. Running the tests

### Java

`JAVA_HOME` on this machine points at a path that does not exist, so **every** Gradle command needs
an override:

```bash
JAVA_HOME="C:/Program Files/Java/jdk-21" ./gradlew build
```

In PowerShell, quote `-D` arguments — unquoted, PowerShell splits at the first `.` and Gradle
reports a missing task.

Live tests are gated. Point `vitaminmcp.runnerJar` at whichever runner you want to exercise — it
takes a `.mjs` as happily as a jar, which is the whole point of the launch change:

```bash
JAVA_HOME="C:/Program Files/Java/jdk-21" ./gradlew :mcp-server:test --tests '*SessionLiveTest*' \
  -Dvitaminmcp.liveServer=true \
  -Dvitaminmcp.token=<token from config.yml> \
  -Dvitaminmcp.runnerJar="$PWD/bot/bot-runner-node/runner.mjs"
```

**Every property must be listed in that module's `tasks.test` passthrough block** or it is silently
ignored. This has already produced a false result once: a "50 run" finished in 12 seconds having
actually run 5, because `vitaminmcp.repeat` was missing from the list.

### Node

```bash
cd bot/bot-runner-node
npm test                                                    # pure, no server needed
node test/lifecycle.live.mjs                                # spawn/despawn/position
node test/actions.live.mjs                                  # the packet verbs
node test/inspect.live.mjs 127.0.0.1 25565 SomeBot 50       # the client view; two-phase, see below
```

**`parity.live.mjs` is the important one.** It drives both runners through the same script and diffs
their replies byte for byte. Differences you decided on are listed in it *by name*, so a difference
nobody chose still fails. Run it after every change to a verb.

`inspect.live.mjs` cannot build its own fixture — a chest, items, a boss bar and a scoreboard all
need the console. It spawns a bot, prints the exact commands to run and where, then waits. Run
those through `command_exec` while it waits.

There is also a `__dump` verb in the dispatch — not protocol, a development aid that prints the raw
shapes prismarine hands over. It exists because none of those shapes are documented anywhere
useful.

---

## 4. Traps, in the order they will bite you

### The join lockout is about three seconds, not two

Block interactions in the first seconds after a bot joins are dropped with **no event, no log line
and no refusal**. A dig inside that window is indistinguishable from one the server rejected —
`break` returns `ok` and the block is still there, which reads exactly like a broken packet.

Measured on Paper 1.21.8 by digging at 1500 / 2500 / 3500 / 4500ms after spawn: the first two do
nothing, the last two work. The README and the skill both say ~2 seconds; they are optimistic.

This cost an afternoon, and two confident explanations were wrong before measurement settled it:
spawn-region protection (it is `0` here, and would not care how long you wait) and out-of-range
interactions poisoning later ones (the experiment meant to confirm it produced the opposite result).

### Bots do not spawn in the same place twice

The server picks a spawn point inside a radius, so **a coordinate chosen in advance is out of reach
about as often as not** — and out of reach looks identical to refused. Live tests target the block
the bot is standing on (`floor(x), floor(y) - 1, floor(z)`), or place their fixture relative to the
position `spawn` just reported.

### Doubles and floats have to be spelled Java's way

Java writes `79.0` where JavaScript writes `79`, and switches to scientific notation at 1e7 — which
is not hypothetical, the world border is 3e7 and Java prints `3.0E7`. `javaDouble` and `javaFloat`
in `src/protocol.mjs` handle it; `javaFloat` needs `Math.fround` because `Float.toString` prints
the shortest decimal that round-trips through **32** bits (`0.35`, not `0.3499999940395355`).

Every expectation in those tests came out of a JDK run, not out of the specification. Keep doing
that.

### The separators are invisible

`RECORD_SEPARATOR` and `UNIT_SEPARATOR` are 0x1E and 0x1F. They do not survive being checked by
eye, in a diff, or in a code review. Compare protocol lines as bytes. When you write one into a
regex, write `\t`, not a literal tab — that mistake has already been made in this repo, in the file
whose entire purpose is warning about it.

### Everything prismarine hands over is NBT-tagged

`{ type: 'string', value: '…' }`, nested. An item's custom name is three layers at once: an NBT
string whose value is JSON describing a chat component. Reading only the outer layer returns `''`
and looks like a plugin that set no name. `src/text.mjs` unwraps all three — use `plainText` from
there and never write your own.

### mineflayer has real gaps

- **No action bar handler** on modern versions. It raises `actionBar` only for system chat at
  position 2, which is not the route `/title … actionbar` takes. Listen to the raw `action_bar`
  packet.
- **Scoreboard tracking came back empty.** Boss bars and the sidebar are read from raw packets
  instead — which is also what the Java runner does, so the two stay comparable.

When a mineflayer convenience method disagrees with the Java runner, prefer the packet. `bot.dig`
and `bot.activateEntity` look at the target and wait for the result, which is *better* than what
the Java runner does and therefore a behaviour change. Parity first; improvements deliberately, in
`DIFFERENCES.md`.

### An unhandled `error` event kills the process

Fatal in Node. A bot that outlives its spawn call needs a permanent `error` and `kicked` handler,
or one kicked bot takes the runner down and every other bot with it.

### A green build here proves nothing about a clean checkout

For 25 commits the build passed only because an untracked file happened to exist on this machine —
`.gitignore`'s `**/build/` had swallowed a Kotlin package named `build`. Check what git actually
tracks, not what compiles.

---

## 5. Conventions

- **Commits**: Conventional Commits. Explain *why*, and record what was tried and rejected — the
  reversal is usually the useful part. Look at the recent history on this branch for the register.
- **No Claude/AI attribution** in commit messages. No `Co-Authored-By` trailer.
- **Branches**: `feat/…`, `fix/…`. Never push a `claude/…` or hash-suffixed branch.
- Ask the *server* what is true rather than inferring it. "gameMode=CREATIVE, block before=
  GRASS_BLOCK after=GRASS_BLOCK" narrowed a week-shaped mystery to one cause in a single run. Every
  real bug in this migration was found that way and none were found by reading code.
- Assume exclusion and config lists are incomplete until measured under the condition that matters.

---

## 6. What stage 4 needs

Movement, and the reason for the migration.

- `move_to` gains `mode`: `"path"` (default) and `"teleport"`. **Keep teleport** — setup steps that
  only need a bot standing at a coordinate must stay fast and certain, and a walk that cannot reach
  its destination would break every scenario that never wanted to walk.
- `mineflayer-pathfinder` for `"path"`. **Verify its LICENSE before depending on it** — same rule as
  `prismarine-physics`. MIT is fine and needs attribution; GPL is not, and would relicense the whole
  published project.
- `timeout`, and failure that says *which* failure it was. "No path exists" and "did not arrive in
  time" are different answers, and a scenario that cannot tell them apart is a debugging dead end.
- Tick determinism against `design.md` §12: mineflayer runs its own physics timer while `wait_for`
  is evaluated inside the server. Establish whether the two can disagree. This is the top risk in
  the whole migration and the most likely cause of a flaky matrix at stage 5.

The Java runner's `move` is a single position packet at the destination — a teleport, which is why
The Node runner owns movement physics; teleport mode remains available for setup steps but does
not require `allow-flight=true` on the server.

Do not start stage 5 until stage 4's DoD is met, and do not delete anything before stage 9.
