# Contributing

**Thank you for being here.** Every contribution is appreciated — a pull request, a bug report, a
question that shows a document was unclear, or a note that something did not work on your server.
Small ones count: a typo fix, a sentence that reads better, a version this was never tried on.

Much of what follows is strict, because a tool that can run console commands on a production server
has to be. None of it is aimed at you. If a rule here blocks something reasonable, say so — that is
worth knowing, and the rule can be wrong.

**This file is the rules.** Design rationale is in [docs/design.md](docs/design.md) and
implementation order in [docs/roadmap.md](docs/roadmap.md); when an intent here is unclear, read
design.md rather than guessing — most of what looks arbitrary is written down there with its
reason.

## Stack

- Java 21, Gradle (Kotlin DSL), multi-module monorepo
- **Server plugin: Paper API 1.21+.** Changing the floor means updating design.md §5
  - One agent jar covers the whole range. It compiles against the *floor*, so API added later is
    not on the classpath and cannot be reached by accident. Where a newer field is worth reporting
    anyway it goes through reflection — `getTPS` and custom model data are the two
  - `agent-*` and `contract` compile with **`--release 21`** via `vitaminmcp.server-jvm-target`.
    That matches the toolchain today and **means something different**: the toolchain is what we
    compile with, this is *what a server can load*. It is the safety net when the floor moves or
    the toolchain is raised (design.md §5.1). Every other module runs on our JVM and is unaffected
- **Bots: MCProtocolLib. ViaProxy is not used** (design.md §4)
  - **One `bot-runner.jar` with a backend per protocol inside it.** At startup it pings the server,
    reads the protocol out of the status reply, and loads the matching backend in a parent-last
    class loader. Several MCProtocolLib builds cannot share a *class path* — every build occupies
    the same package names — but they can share a process (design.md §4.4)
  - A backend is named for the **protocol number**, not the Minecraft version: `backend-772` covers
    1.21.7 and 1.21.8
  - Backends share their source and override it **by file**. A version that genuinely differs drops
    its own copy at the same path; the seams that have earned their place are `PlayerSync`,
    `SessionFactory`, `EntitySync`, `ItemText` and `BlockUse`
- **MCP is implemented directly** — the agent over HTTP on the JDK's `HttpServer`, `mcp-server` over
  stdio. Why not the MCP Java SDK is in the mcp-server commit
- **Servers are started natively**: the jar is downloaded from the PaperMC API and run (design.md
  §15.1)

## Modules

Project paths are flat (`:contract`, `:agent-core`, …) so they match the names used in the docs
one-to-one, while the `agent/` and `bot/` grouping is kept on disk.

| Module | Role |
|---|---|
| `build-logic` | Convention plugins: compilation, shadow packaging, the architecture checks |
| `contract` | MCP tool schemas and DTOs. **Pure Java, zero external dependencies** |
| `agent-core` | Capture engine and state queries, on the Bukkit API |
| `agent-mcp` | The agent's MCP server, on the JDK's `HttpServer` |
| `bot-core` | Runner handle, line protocol, handshake injection, server ping, and the `bot.spi` contract. No protocol library |
| `bot-runner` | The runner jar: launcher, backend selection, dispatch. Runs as a child process |
| `backend-<n>` | One per protocol, under `bot/backends/`. A coordinate plus whatever actually differs; the rest comes from `bot/backends/shared` |
| `orchestrator` | Native server startup, world reset, version matrix |
| `testkit` | Scenario runner, `wait_for`, assertions |
| `mcp-server` | Tool exposure and assembly. The entry point |

### Dependency direction

Dependencies flow **one way only**:

```
mcp-server  → testkit → {bot-core, orchestrator, contract}
bot-runner  → bot-core → contract
backend-*   → bot-core → contract
agent-mcp   → agent-core → contract
```

Three boundaries are load-bearing, and each is there for a reason that is not obvious from the code:

- **`mcp-server` never compiles against `agent-*`.** The agent is injected at runtime as a jar, and
  the only thing joining the two sides is `contract`. Break this and per-version agents stop being
  separable.
- **Nothing above `testkit` compiles against a protocol library.** One JVM cannot speak two
  Minecraft protocols — every MCProtocolLib build occupies the same package names — so bots live in
  child processes. That is also what lets one matrix span versions whose protocols differ.
- **`contract` has no external dependencies.** It is the shared vocabulary of two artifacts that
  ship separately; anything it drags in, both sides inherit.

Adding a module or changing the direction is a **proposal first, not a patch**. The whitelist in
[vitaminmcp.module-rules.gradle.kts](build-logic/src/main/kotlin/vitaminmcp.module-rules.gradle.kts)
is exhaustive, so a new module with no entry fails the build — deliberately, to force the decision
about what it may depend on.

## Invariants

**Do not break these.** They are numbered, and `checkModuleDependencies` cites the numbers back at
you when it fails — "See invariant 2 in CONTRIBUTING.md" means the second entry here, so the
numbering is part of the contract.

1. **`mcp-server` does not compile against `agent-*`.** The agent is only injected as a jar at
   runtime, and the sole thing joining the two is `contract`. Break this and separating agents per
   version becomes impossible.
2. **`contract` takes no external dependencies.** Pure Java types only. It is the shared vocabulary
   of two artifacts that ship separately, so anything it drags in, both sides inherit.
3. **No NMS in `agent-core`.** Bukkit and Paper API only. If NMS looks necessary, suspect the design
   before reaching for it.
4. **`agent-*` shadow jars relocate every dependency** — Netty, Jackson and Guava especially. They
   collide with the server itself.
5. **Event capture does not serialize on the main thread.** The MONITOR listener builds a
   lightweight record and puts it in the ring buffer; serialization happens on another thread.
6. **Every query tool takes a cursor and has a cap.** Do not add a tool that can return an unbounded
   response.
7. **The version matrix is [versions.yaml](versions.yaml), not code.** Adding a version must stay a
   configuration change, and **the protocol number never appears in it** — the runner asks the
   server.
8. **`bot.spi` names no protocol library type.** It is the one package shared across the class
   loader boundary, so a signature mentioning MCProtocolLib would put that library on the launcher's
   own class path — the collision the whole bundle exists to prevent. A test asserts it.

### Adding an MCP tool

- **Aggregate first.** A detail tool needs its summary counterpart to exist first
  (`events_summary` before `events_query`).
- **Budget every response.** 200 records / 50KB by default. If output was cut, say so with
  `truncated` and the drop counters.
- **High-frequency events stay out by default** — `PlayerMoveEvent`, `BlockPhysicsEvent`,
  `ChunkLoadEvent`, entity movement — and appear only when a query names them.
- **Resist growing the tool count.** Extend an existing tool with a parameter before adding a new
  one. Dozens of fine-grained tools make an agent worse, not better.
- **Never add a "last N lines" tool** like `logs_tail`. Pattern search beats it every time and a
  tail only consumes context.

## Security

`command_exec` alone hands over op. Assume every change lands on a production server.

- Default bind is `127.0.0.1`; exposure is opt-in and explicit
- A token is required — no token means **refuse to start**, not warn and continue
- **read-only is the default mode.** State-changing tools are unexposed until config says otherwise
- **Never relax any of the above for the convenience of a test.** If a test needs a weaker default,
  the test is wrong.

## Commits — Conventional Commits

```
<type>(<scope>): <subject>
```

| type | For |
|---|---|
| `feat` | a new capability |
| `fix` | a bug fix |
| `refactor` | structural change with no behaviour change |
| `docs` | documentation only |
| `test` | tests added or changed |
| `build` | Gradle, dependencies, toolchain |
| `chore` | everything else |

`scope` is the module name: `contract`, `agent-core`, `agent-mcp`, `bot-core`, `bot-runner`,
`orchestrator`, `testkit`, `mcp-server`, `build-logic`. Use a comma for a change that genuinely
spans two.

```
feat(agent-core): add ring buffer with drop counter
fix(bot-core): keep forwarding handshake UUID stable across reconnects
build(build-logic): enforce dependency direction in check task
```

Breaking changes take a `!` — `feat(contract)!:` — and a `BREAKING CHANGE:` paragraph in the body.

**Write the body for whoever hits this commit in `git blame`.** The subject says what changed; the
body is for why, and for what you tried that did not work. A constraint discovered once and not
written down gets rediscovered the expensive way.

## Branches

`<type>/<short-description>` — `feat/event-capture`, `fix/ring-buffer-overflow`.

## Build

```bash
./gradlew build
```

`build` runs the architecture checks:

- `checkModuleDependencies` — fails when a module declares a dependency the direction does not allow
- `checkContractIsDependencyFree` — fails when `:contract` gains an external dependency, checked on
  the resolved classpath so transitive arrivals are caught too

These two are invariants 1 and 2 nailed down in code. **Before editing a rule to make a build pass,
suspect the change.**

To assemble the three distributable artifacts into `build/dist/`:

```bash
./gradlew dist
```

## Tests

JUnit 5. Agent logic is unit-testable without Bukkit — keep it that way by separating the interface
from the Bukkit-facing implementation.

Tests that need a real server are skipped unless asked for, so `./gradlew build` needs no server:

```bash
./gradlew :testkit:test -Dvitaminmcp.liveServer=true -Dvitaminmcp.token=...
```

Recognised properties: `vitaminmcp.liveServer`, `host`, `port`, `mcpPort`, `token`, `agentJar`,
`runnerJar`.

> **A property only reaches the test if the build script forwards it.** Gradle does not pass `-D`
> through to the test JVM on its own; each key is listed explicitly in the module's `build.gradle.kts`.
> Add a new property there as well, or the gate reads it as absent, the live test skips, and the run
> goes green **without having tested anything**. A silent skip looks exactly like a pass in the
> summary — check that the test actually ran.

Adding a version to `versions.yaml` means starting a server on that version and confirming it, not
just editing the file. `CompatibilityLiveTest` is the gate — seventeen features against a server it
starts itself — and it collects failures rather than stopping at the first, so one run tells you
which parts of a new version work.

### Bots and online mode

Test servers run `online-mode=false` with `bungeecord: true` in `spigot.yml`. Bots reproduce
arbitrary UUIDs and skins by injecting `host\0clientIP\0uuid\0properties-json` into the handshake's
server address field (design.md §3.1) — which is also why **a server configured this way must never
be reachable from the internet**.

The authlib-injector and Drasl path exists only for when `online-mode=true` is itself what needs
verifying, and real accounts are for a final smoke test only.

## Conventions

- Package root: `moe.vitamin.minecraft.mcp`
- Nullability: prefer an explicit `@Nullable` over returning `Optional`
- Logging: `java.util.logging`, the Bukkit standard. The agent must not depend on Log4j2 directly —
  the appender-attaching code is the sole exception
- **Write version-specific code when versions actually diverge, not before.** Do not abstract in
  advance (design.md §4.2). Inside a backend that means: let the compiler tell you which file
  differs, then override that file. Do not add a seam for a difference nobody has seen
- A `backend-*` directory is **not** a new module in the "propose it first" sense — it is the
  mechanism working as designed
