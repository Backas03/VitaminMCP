# VitaminMCP implementation roadmap

**Stages 0–5 are done and verified.** What follows is the plan they were built to, kept because the
definitions of done are still the standard anything new is held to — not as a description of work
outstanding. Where a decision was later reversed, the entry says so rather than being quietly
rewritten: the reversal is the useful part.

Each stage **must meet its definition of done before the next one starts.** Do not skip ahead and
build a later stage first.

Design rationale is in `design.md`, rules and invariants in `../CLAUDE.md`.

How the difficulty is distributed: **stages 1 through 3 are most of the work.** Stage 4 is a thin
shell over them, and stage 5 is expansion.

---

## Stage 0 — scaffolding

Stand up the Gradle multi-module skeleton.

**Work**

- [ ] `settings.gradle.kts` — register every module
- [ ] `build-logic/` convention plugin — Java 21 toolchain, shared compile options, shadow+relocate
      setup
- [ ] A `build.gradle.kts` stub per module
- [ ] `contract/` module — created empty, confirmed to have zero external dependencies
- [ ] `.gitignore`, a note on Conventional Commits

**DoD**

- `./gradlew build` passes across every module
- `contract`'s dependency tree contains no external library (check with
  `./gradlew :contract:dependencies`)
- A dependency-direction violation fails the build (checked in the convention plugin)

---

## Stage 1 — event capture + MCP exposure ★ the core

**If this stage is alive, the rest is a matter of stacking on top.** Where the effort goes.

### 1a. contract schemas

- [ ] Event record DTO (type, timestamp, player, cancelled, payload)
- [ ] Log record DTO (level, logger name, message, throwable reference)
- [ ] Cursor type — **in from the start.** Adding it later means tearing everything apart
- [ ] Pagination response wrapper (`items`, `nextCursor`, `truncated`, `dropped`)

### 1b. agent-core capture engine

- [ ] Scan `org.bukkit.event.Event` subclasses with ClassGraph → dynamic `registerEvent`
- [ ] Register with `EventPriority.MONITOR` and `ignoreCancelled = false`
- [ ] Lock-free ring buffer (fixed size, 100k default) plus a drop counter
- [ ] Serialize on a separate thread — **no serialization on the main thread**
- [ ] Default exclusion list for high-frequency events (`PlayerMoveEvent`, `BlockPhysicsEvent`,
      `ChunkLoadEvent`, entity movement)
- [ ] Attach a custom Log4j2 Appender — no file tailing
- [ ] Exception grouping (by stack hash, with count and first-seen time)

### 1c. agent-mcp

- [ ] MCP streamable HTTP server on the JDK's built-in `com.sun.net.httpserver`
- [ ] Implement the tools: `server_info`, `events_summary`, `events_query`, `logs_query`,
      `exceptions_recent`
- [ ] Hard response budget (200 records / 50KB) plus a `truncated` flag
- [ ] Token authentication — **refuse to start when unset**
- [ ] Default bind `127.0.0.1`
- [ ] read-only as the default mode (`command_exec` not exposed)
- [ ] Verify shadow jar relocation (Netty, Jackson, Guava)

**DoD**

- The plugin installs and starts cleanly on the supported range
  (**1.13 and 1.20 as written; the floor is now 1.21.8** — design.md §5)
- Connecting to this MCP directly from Claude Code, the `events_summary` → `events_query` flow works
- Player joins, block breaks and chat are captured as events
- **Load check**: with players moving actively, TPS degradation stays within the measurement floor
- On ring buffer overflow the `dropped` counter shows up in the response
- Starting without a token is refused
- No class conflicts on a server with other plugins installed

> At the end of Stage 1 **this is already usable as a standalone product.** Use it for real once
> here, refine the tool schemas, then go to Stage 2.

---

## Stage 2 — bots connecting

### 2a. bot-core

- [ ] MCProtocolLib wrapper, bot session lifecycle
- [ ] **Forwarding handshake injection** — `host\0clientIP\0uuid\0properties-json` in the server
      address field
- [ ] Fixed UUID policy (deterministic name → UUID mapping, for permission reproducibility)
- [ ] Managing multiple bot instances plus a resource ceiling
- [ ] Basic actions: movement, block interaction, inventory clicks, chat, commands

**DoD**

- A bot connects to an `online-mode=false` + `bungeecord: true` backend
- The injected UUID is observed unchanged by the server (confirmed via the agent's `state_query`)
- Three or more bots stay connected simultaneously
- A bot breaking a block shows up as `BlockBreakEvent` in Stage 1's `events_query` ← **the point
  where the two layers first connect**

---

## Stage 3 — testkit: determinism ★ the second hard part

Not producing flaky tests is the whole job.

- [ ] A **tick barrier** on the agent side — an "advance N ticks, then respond" API
- [ ] `wait_for(predicate, timeout)` — predicates over both bot and server state
- [ ] Runner for the declarative JSON action DSL (`design.md` §11)
- [ ] Step-level failure reporting — which step died, and why
- [ ] Assertion helpers (inventory, position, scoreboard, permissions)

**Prohibited**

- [ ] **Do not provide a fixed wait (sleep) in the scenario DSL.** Provide it and it will be used,
      and it will be the cause of flakiness

**DoD**

- The same scenario run 50 times consecutively fails zero times (no flakiness)
- A deliberately failing scenario points at the exact step
- On timeout, a snapshot of events and logs from that moment comes back with it

---

## Stage 4 — assembling mcp-server

A thin layer over stages 1–3. Do not create new logic here.

- [ ] Expose the tools (five or six). **Written against the MCP Java SDK; implemented without it** —
      see the mcp-server commit for why
- [ ] `session_start` / `session_reset`
- [ ] `bot_spawn` / `bot_run_scenario`
- [ ] Agent MCP proxy — per-server tool namespaces in matrix mode
- [ ] Automatic attachment of events and logs on failure

**DoD**

- "Spawn two bots and test feature Y of plugin X" works in natural language from Claude Code
- On failure, the context needed to find the cause arrives without an extra tool call

---

## Stage 5 — version matrix

> **Two entries here were reversed while building it, and both reversals are in design.md.**
> Docker was dropped for native startup (§15.1), and ViaProxy for per-protocol bot runners (§4).
> The DoD below is written against the plan, not against what shipped.

- [ ] orchestrator: start/stop and world template restore.
      **Written for Docker (`itzg/minecraft-server`); native instead** — design.md §15.1
- [ ] `versions.yaml` schema and loader (`design.md` §15)
- [ ] bot-via: embed ViaProxy, bridge protocols.
      **Dropped** — one runner per protocol in a child process instead, design.md §4
- [ ] Parallel matrix execution plus per-version result aggregation
- [ ] ~~Cross-check versions marked `native: true` against the native protocol~~ —
      moot once every version is native

**DoD**

- The same scenario runs across the matrix
  (**1.13/1.16/1.20/latest as written; 1.21.7 and 1.21.8 in practice**, since the floor moved)
- Adding a version is one block in `versions.yaml` and nothing more
- ~~When one version alone fails, the Via-routed and native results can be compared~~ —
  nothing is Via-routed any more

---

## Stage 6 — many protocols, one runner (done 2026-08-01)

Planned in `multi-version.md`, which holds the reasoning. Summarised here because the DoDs are what
anything new is held to.

- [x] `bot.spi.BotBackend` — the contract between the launcher and a backend. Library-free, and a
      test asserts it
- [x] One `bot-runner.jar`: backends embedded as resources, chosen by pinging the server, loaded in
      a parent-last class loader
- [x] The line protocol moves into the launcher, compiled once for every version there will ever be
- [x] `bot/backends/shared` compiled into each backend, overridden by file where a version differs
- [x] `vitaminmcp.bot-backend` convention plugin, directory-scanned includes, pattern whitelist —
      a protocol is a directory and a coordinate
- [x] Backends 767, 768, 769, 770, 771 beside the existing 772
- [x] Floor to 1.21, so the compiler enforces the range the matrix demonstrates (design.md §5.6)
- [x] `CompatibilityLiveTest` — seventeen features against a server it starts itself

**DoD — all met**

- `gradlew dist` produces exactly one runner jar, whatever versions are supported
- The compatibility check passes on 1.21.1, 1.21.3, 1.21.4, 1.21.5, 1.21.6 and 1.21.8, each against
  a real server of that version
- One matrix run covers all seven entries in `versions.yaml` and passes
- After the run, `exceptions_recent` holds no `IncompatibleClassChangeError`, `NoSuchMethodError`,
  `NoSuchFieldError`, `NoClassDefFoundError` or `AbstractMethodError` on any version
- Adding a protocol is a directory and a coordinate: no edit to `settings.gradle.kts`, the
  dependency whitelist, or `dist`
- The protocol number appears in the module directory, the generated descriptor and nowhere else —
  not in `versions.yaml`, not in a package name

---

## Order of work, in short

```
Stage 0  scaffolding      ─ lightly
Stage 1  capture + MCP    ─ ★ most of the time goes here
Stage 2  bots connecting  ─ handshake injection is the crux
Stage 3  determinism      ─ ★ the second hard part
Stage 4  assembly         ─ thin
Stage 5  version matrix   ─ expansion
```

Stop once at the end of Stage 1 and use it for real. Tool schemas only get refined by being used.

---

## Later (outside the current scope)

- Our own Yggdrasil (authlib-injector + Drasl) — when verifying `online-mode=true` itself becomes
  necessary
- A Groovy/GraalJS script engine — after cases the declarative DSL cannot handle accumulate
- An `agent-legacy` module — when a real requirement for 1.12 or below appears
- Bedrock (reachable via Via, but the scope of verification needs defining separately)
