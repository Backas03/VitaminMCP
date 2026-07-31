# VitaminMCP

An MCP server plus protocol-bot test harness for Minecraft server and plugin automation.

Detailed design rationale is in `docs/design.md`, implementation order and definition of done in
`docs/roadmap.md`. When design intent is unclear, read `docs/design.md` rather than guessing.

## Stack

- Java 21, Gradle (Kotlin DSL), multi-module monorepo
- Server plugin: Paper API **1.21+** (changing the floor requires updating design.md)
  - One agent jar covers the whole range. It compiles against the *floor*, so API added later is
    not on the classpath and cannot be reached by accident; where a newer field is worth reporting
    anyway, it goes through reflection (`getTPS`, custom model data)
  - `agent-*` and `contract` state **`--release 21`** via `vitaminmcp.server-jvm-target`. It equals
    the toolchain value today but **means something different** — the toolchain is what we compile
    with, this is *what the server can load*. It is the safety net when the floor drops or the
    toolchain rises (design.md §5.1)
  - The remaining modules run on our JVM and are unaffected by this constraint
- Bots: MCProtocolLib. **ViaProxy is not used** (design.md §4)
  - **One `bot-runner.jar`, a backend per protocol inside it.** At startup it pings the server,
    reads the protocol out of the status reply and loads the matching backend in a parent-last
    class loader. Several MCProtocolLib builds cannot share a *class path* — every build occupies
    the same package names — but they can share a process (design.md §4.2)
  - A backend is named for the **protocol number**, not the MC version: `backend-772` covers 1.21.7
    and 1.21.8
  - Backends share their source and override it **by file**. A version that genuinely differs drops
    its own copy of the file that differs, at the same path; the seams are `PlayerSync`,
    `SessionFactory`, `EntitySync`, `ItemText`, `BlockUse`
  - `bot.spi` is the one package that crosses the class loader, and **no signature in it may name a
    protocol library type**. A test asserts this
  - Nothing at `testkit` or above compiles against a protocol library
- MCP: implemented directly. The agent over HTTP (the JDK's HttpServer), mcp-server over stdio.
  For why the MCP Java SDK was not used, see the mcp-server commit
- Server startup: **native** — the jar is downloaded from the PaperMC API and run directly
  (design.md §15.1)

## Modules and dependency direction

```
build-logic/         convention plugins (shared compile/shadow setup)
contract/            MCP tool schemas + DTOs. Pure Java, zero external dependencies
agent/
  agent-core/        capture engine, state queries (Bukkit API)
  agent-mcp/         MCP server (JDK HttpServer)
bot/
  bot-core/          runner process handle, line protocol, handshake injection, server ping,
                     and `bot.spi` — the BotBackend contract. No protocol library
  bot-runner/        THE runner jar. Launcher, backend selection, isolating class loader,
                     and the line protocol's one implementation
  backends/
    shared/          the backend source every protocol compiles. Not a module: it has no build
                     of its own and is compiled *into* each backend
    backend-767/     1.21, 1.21.1     ─┐
    backend-768/     1.21.2, 1.21.3    │ a coordinate each, plus only the files that
    backend-769/     1.21.4            │ actually differ
    backend-770/     1.21.5            │
    backend-771/     1.21.6            │
    backend-772/     1.21.7, 1.21.8   ─┘
orchestrator/        native server startup / world reset / version matrix
testkit/             scenario runner, wait_for, assertions
mcp-server/          tool exposure + assembly (entry point)
```

Dependencies flow **one way only**:

```
mcp-server → testkit → {bot-core, orchestrator, contract}
bot-runner  → bot-core → contract
backend-*   → bot-core → contract
agent-mcp   → agent-core → contract
```

Adding a protocol is a `bot/backends/backend-<n>` directory and one coordinate: the settings file
finds it, the convention plugin derives everything else from its name, and it is embedded in the
runner automatically.

## Invariants (do not break these)

1. **`mcp-server` does not compile against `agent-*`.** The agent is only injected as a jar at
   runtime, and the sole thing joining the two is `contract`. Break this and separating agents per
   version becomes impossible.
2. **Do not add external dependencies to `contract`.** Pure Java types only.
3. **Do not use NMS in `agent-core`.** Bukkit/Paper API only. If NMS looks necessary, suspect the
   design first.
4. **`agent-*` shadow jars relocate every dependency.** Netty, Jackson and Guava especially. They
   collide with the server itself.
5. **Event capture does not serialize on the main thread.** The MONITOR listener builds only a
   lightweight record and puts it in the ring buffer; serialization happens on a separate thread.
6. **Every query tool has a cursor and a cap.** Do not add a tool that returns an unbounded
   response.
7. **The version matrix is `versions.yaml`, not code.** Adding a version must be a one-line
   configuration change. **The protocol number never appears in it** — the runner asks the server.
8. **`bot.spi` names no protocol library type.** It is the one package shared across the class
   loader boundary, so a signature mentioning MCProtocolLib would put that library on the
   launcher's own class path — the collision the whole bundle exists to prevent.

## MCP tool design rules

- **Aggregate first**: a detail tool requires its corresponding summary tool to exist first
  (`events_summary` → `events_query`)
- **Response budget**: a hard limit per tool (200 records / 50KB by default). If output was cut,
  include `truncated` and the drop counters in the response
- **High-frequency events excluded by default**: `PlayerMoveEvent`, `BlockPhysicsEvent`,
  `ChunkLoadEvent` and entity movement appear only on explicit request
- **Do not grow the tool count**: solve it with a parameter instead of a micro tool. Before adding
  one, check whether an existing tool can be extended
- Never build a "last N lines" tool like `logs_tail`. Pattern search is always better

## Security (not negotiable)

`command_exec` alone hands over op. Assume this lands on a production server.

- Default bind is `127.0.0.1`. External exposure only through explicit configuration
- Token authentication is mandatory. With no token configured, **refuse to start** (never warn and
  continue)
- **read-only is the default mode.** `command_exec` and other state-changing tools work only when
  explicitly enabled in config
- Do not relax any of these defaults for the convenience of a test

## Handling online mode

Test servers run `online-mode=false` with `bungeecord: true` in `spigot.yml`, and reproduce
arbitrary UUIDs and skins by injecting `host\0clientIP\0uuid\0properties-json` into the handshake's
server address field.

The authlib-injector + Drasl path is used only when `online-mode=true` itself is what needs
verifying. Real accounts are for the final smoke test only.

## Coding conventions

- Package root: `moe.vitamin.minecraft.mcp`
- Nullability: avoid returning `Optional`; state `@Nullable` explicitly
- Logging: `java.util.logging` (the Bukkit standard). No direct Log4j2 dependency inside the agent —
  the appender-attaching code is the sole exception
- Tests: JUnit 5. Keep agent logic unit-testable without Bukkit by separating the interface
- Commits: Conventional Commits (`feat:`, `fix:`, `refactor:`)

## While working

- Do not add a module or change the dependency direction unilaterally — propose it first. A
  `backend-*` directory is not a new module in this sense; it is the mechanism working
- When adding a version to `versions.yaml`, actually start a server on that version and confirm it.
  `CompatibilityLiveTest` is the gate: seventeen features against a server it starts itself
- Write version-specific code when versions actually diverge. Do not abstract in advance
  (design.md §4.2). Inside a backend that means: let the compiler tell you which file differs, then
  override that file — do not add a seam for a difference nobody has seen
