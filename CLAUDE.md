# VitaminMCP

An MCP server plus protocol-bot test harness for Minecraft server and plugin automation.

Detailed design rationale is in `docs/design.md`, implementation order and definition of done in
`docs/roadmap.md`. When design intent is unclear, read `docs/design.md` rather than guessing.

## Stack

- Java 21, Gradle (Kotlin DSL), multi-module monorepo
- Server plugin: Paper API **1.21.8+** (the floor is settled; changing it requires updating
  design.md)
  - `agent-*` and `contract` state **`--release 21`** via `vitaminmcp.server-jvm-target`. It equals
    the toolchain value today but **means something different** — the toolchain is what we compile
    with, this is *what the server can load*. It is the safety net when the floor drops or the
    toolchain rises (design.md §5.1)
  - The remaining modules run on our JVM and are unaffected by this constraint
- Bots: MCProtocolLib. **ViaProxy is not used** (design.md §4)
  - Bots run in a **child process** (`bot-runner-<protocol>`). One JVM cannot speak two protocols —
    every MCProtocolLib build occupies the same package names, so they cannot share a classpath
  - A runner is named for the **protocol number**, not the MC version. The single 772 covers 1.21.7
    and 1.21.8
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
  bot-core/          MCProtocolLib wrapper, forwarding handshake injection
  bot-runner-772/    bot runner for protocol 772 (1.21.7/1.21.8). Runs as a child process
orchestrator/        native server startup / world reset / version matrix
testkit/             scenario runner, wait_for, assertions
mcp-server/          tool exposure + assembly (entry point)
```

Dependencies flow **one way only**:

```
mcp-server → testkit → {bot-core, orchestrator, contract}
bot-runner-* → bot-core → contract
agent-mcp  → agent-core → contract
```

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
   configuration change.

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

- Do not add a module or change the dependency direction unilaterally — propose it first
- When adding a version to `versions.yaml`, actually start a server on that version and confirm it.
  Every version is native
- Write version-specific code when versions actually diverge. Do not abstract in advance
  (design.md §4.2)
