# Contributing

**Thank you for being here.** Every contribution is appreciated — a pull request, a bug report, a
question that shows a document was unclear, or a note that something did not work on your server.
Small ones count: a typo fix, a sentence that reads better, a version this was never tried on.

Much of what follows is strict, because a tool that can run console commands on a production server
has to be. None of it is aimed at you. If a rule here blocks something reasonable, say so — that is
worth knowing, and the rule can be wrong.

Design rationale is in [docs/design.md](docs/design.md), implementation order and definition of
done in [docs/roadmap.md](docs/roadmap.md), and the rules and invariants in [CLAUDE.md](CLAUDE.md).
When a design intent is unclear, read design.md rather than guessing — most of what looks arbitrary
here is written down there with its reason.

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

The full list is in [CLAUDE.md](CLAUDE.md). The ones easiest to break by accident:

- **No NMS in `agent-core`.** Bukkit and Paper API only. If NMS looks necessary, suspect the design
  before reaching for it.
- **`agent-*` shadow jars relocate every dependency** — Netty, Jackson and Guava especially. They
  collide with the server itself.
- **Event capture does not serialize on the main thread.** MONITOR listeners build a lightweight
  record and push it into a ring buffer; serialization happens on another thread.
- **Every query tool takes a cursor and has a cap.** Do not add a tool that can return an unbounded
  response.
- **The version matrix is [versions.yaml](versions.yaml), not code.** Adding a version must stay a
  configuration change.
- **`agent-*` and `contract` compile with `--release 21`** via `vitaminmcp.server-jvm-target`. This
  currently matches the toolchain, but it means something different: the toolchain is what we
  compile with, this is *what the server can load*. It is the safety net when the floor moves or the
  toolchain is raised.

### Adding an MCP tool

- **Aggregate first.** A detail tool needs its summary counterpart to exist first
  (`events_summary` before `events_query`).
- **Budget every response.** 200 records / 50KB by default. If output was cut, say so with
  `truncated` and the drop counters.
- **High-frequency events stay out by default** — `PlayerMoveEvent`, `BlockPhysicsEvent`,
  `ChunkLoadEvent`, entity movement — and appear only when a query names them.
- **Resist growing the tool count.** Extend an existing tool with a parameter before adding a new
  one, and never add a "last N lines" tool: searching for a pattern beats it every time.

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

These two are CLAUDE.md invariants 1 and 2 nailed down in code. **Before editing a rule to make a
build pass, suspect the change.**

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
just editing the file. Every version is started natively.

## Conventions

- Package root: `moe.vitamin.minecraft.mcp`
- Nullability: prefer an explicit `@Nullable` over returning `Optional`
- Logging: `java.util.logging`, the Bukkit standard. The agent must not depend on Log4j2 directly —
  the appender-attaching code is the sole exception
- **Write version-specific code when versions actually diverge, not before.** Do not abstract in
  advance (design.md §4.2)
