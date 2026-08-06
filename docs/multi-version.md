# Multi-version support — plan

> **Status: §2 and §3's upward half are built and verified (2026-08-01).** One runner jar with a
> backend per protocol, backends 767 through 772, the floor at 1.21, and the whole matrix green.
> `CONTRIBUTING.md`, `design.md` §4.4 and §5.6, and `roadmap.md` Stage 6 now hold the settled version;
> this document keeps the reasoning that got there, including the parts that turned out wrong.
>
> **Still a proposal:** everything below 1.21 — the Java 17 floor, per-version JVMs in the matrix,
> and the MCProtocolLib package seam at 1.20.4. Nothing has asked for it yet.
>
> **What the build disagreed with**, kept because being wrong in a predictable way is the useful
> part:
> - §2.1 said "nothing typed crosses the class loader boundary" and that the launcher would hand
>   the backend a `String[]`. A typed SPI is better, and §2.1.1 says why. The dispatch is
>   version-independent, so compiling it once rather than once per backend was the whole win.
> - "Why not an interface that each version implements" argued inheritance could not compile.
>   It can. The reason to start with file override is cost, not impossibility — corrected in place.
> - The measured cost of the seam was five files, not the "one file that diverged" §2.1 guessed —
>   and 1.21.5 onward needs none of them.

Two asks drive it:

1. **The range widens both ways** — down to 1.18, up to whatever ships next. Today it is 1.21.7 and
   1.21.8, which is one protocol.
2. **One bot runner jar, not one per protocol.** Versions are selected inside the jar, not by
   picking a file.

---

## 1. Where the current structure stops

Measured against the code as it stands, not guessed.

| Obstacle | Where |
|---|---|
| Runner discovery **refuses to guess** when more than one `bot-runner-*.jar` is present. A second protocol makes every existing install ambiguous. | `SessionTools.java:356` |
| The runner *implementation* lives inside the protocol module — **1620 lines of main source**. A second protocol copies all of it. | `bot/bot-runner-772/src/main` |
| `MatrixRunner` takes **one** `runnerJar` and **one** `javaHome`. One runner is wrong the moment protocols differ; one JVM is wrong the moment the matrix spans 1.20.4 (Java 17) and 1.21.8 (Java 21). | `MatrixRunner.java:60` |
| The build names the runner in **three places**: the `include(...)` list, the dependency whitelist, and the `dist` copy. | `settings.gradle.kts`, `vitaminmcp.module-rules.gradle.kts`, root `build.gradle.kts` |
| `versions.yaml` says nothing about protocol or JVM. | — |

That last row is not an obstacle, it is the property to protect: adding a version must stay one
block (invariant 7). Every mechanism below is chosen so that neither the protocol number nor the
JVM has to be written down there.

### 1.1 What the floor drop actually costs

The floor is one constant (`SupportedVersions.FLOOR`), and `1.18` derives `release 17` on its own.
The real question is what stops compiling.

- **Syntax: nothing.** `agent-*` and `contract` contain no record patterns, no switch type patterns
  and no sealed types. The only `reversed()` calls are on `Comparator`, which is Java 8. The price
  design.md §5.2 quotes for going back to `release 17` is, today, zero.
- **API: not nothing.** `CaptureService` reads `ItemMeta.hasCustomModelDataComponent()` and
  `CustomModelDataComponent`, which are 1.21.4 API and absent from a 1.18 `paper-api`
  (`CaptureService.java:379`, `:394`). `getTPS()` in the same file is already reflective for exactly
  this reason (`CaptureService.java:198`) — that is the pattern to copy, not a new problem to solve.

So the floor drop is an **API audit**, and the compiler enumerates the list for us the moment
`FLOOR` changes. That is what §5.4 promised it would do.

---

## 2. Target shape

### 2.1 One runner jar, many backends

```
bot/
  bot-core/              unchanged — BotRunner, RunnerProtocol, handshake, identity
  bot-runner/            THE jar. Launcher, backend selection, isolating classloader.
                         Depends on no protocol library at all
  backends/
    shared/              runner source every backend of a library band compiles
    backend-772/         MCProtocolLib 1.21.7-1   → 1.21.7, 1.21.8
    backend-<n>/         one per protocol, each with its own MCProtocolLib
```

`bot-runner.jar` **embeds each backend's shaded jar as a resource** under `backends/<protocol>/`.
At startup it:

1. pings `host:port` — the status handshake, which needs no protocol library and has been stable
   since 1.7 — and reads `version.protocol` out of the reply;
2. extracts the matching backend to a cache directory;
3. loads it in a **parent-last `URLClassLoader`** that delegates `java.*` and the SPI package
   (§2.1.1) to the parent, and everything else child-first;
4. gets its `BotBackend` and drives it — the launcher owns the line protocol, the backend owns the
   packets.

The line protocol is untouched, so **`BotRunner` and everything above it do not change** — only the
path it is handed and the fact that the path is now always the same file.

#### 2.1.1 Two boundaries, two different natures

There are two seams in the chain, and they should not be built the same way.

```
Claude → mcp-server                bot_inspect / state_query
mcp-server → BotRunner             our JVM, bot-core
   │
   ├─ PROCESS BOUNDARY ─────────── text. RunnerProtocol over stdin/stdout
   │                               a separate JVM, killed to reset (Session.java:58)
   ▼
runner JVM: RunnerDispatch         parses the line, once, for every protocol ever
   │
   ├─ CLASSLOADER BOUNDARY ─────── typed. BotBackend, an interface in the SPI package
   │                               same JVM, so real types cross
   ▼
backend-772 (isolated loader)      MCProtocolLib 1.21.7-1, its own Vector3d field
```

**The process boundary stays text.** It has to be serialized whichever way it is expressed, and it
is also the crash and cleanup boundary — killing the process is what guarantees no protocol state
survives a `session_reset`, which is a property worth more than a nicer call syntax.

**The classloader boundary is a Java interface**, because there is no serialization to pay for and
a type contract buys three things text cannot:

- **The dispatch stops being per-version.** `RunnerMain`'s 226-line command switch is entirely
  version-independent — it parses strings and calls methods. Under a text hand-off it sits in
  `shared/` and is compiled into every backend, one more file that can fork. As `RunnerDispatch` in
  the launcher it is compiled **once**, and no backend can drift from it.
- **A backend that misses a command does not compile.** With text, a backend could quietly answer
  `unknown command` at runtime instead.
- **Encoding bugs go away at that seam.** `INSPECT` currently packs eight fields, two of them nested
  lists, into tab-separated text — hence `RunnerProtocol.sanitize` and
  `containerTitle().replace('\t', ' ')` (`RunnerMain.java:191`). Between launcher and backend those
  become records and the escaping question does not arise.

**The SPI must be library-free** — `String`, `double`, and our own records. That is not a
restriction to design around: the line protocol already carries every command as text, which is
proof that a library-free signature exists for each one. Where a protocol genuinely lacks a
capability, the interface says so with a `default` method that reports it, rather than every caller
guessing.

**This does not replace shared source.** The SPI is the boundary *above* `BotSession`; the 1094
lines below it are still shared by source within a band and overridden by file where a version
diverges (§2.1's example). The two mechanisms sit at different levels and both are needed.

**Why a classloader rather than a second process.** The runner is already a child process. A
launcher that spawns a grandchild JVM to isolate one library doubles the process count for nothing.
Only one backend is ever loaded per process (one runner per server), so the isolation needed is
narrow: package collision between MCProtocolLib builds, which is exactly what a classloader
separates.

**Why not one flat classpath with relocation.** Relocating MCProtocolLib per protocol drags its
netty and adventure copies along, and the runner source still has to be compiled once per protocol
because its imports are rewritten. Same work, plus stack traces nobody can read.

**The fallback, if a backend turns out not to load in-process.** Netty's native transports are the
candidate — a JNI library cannot be loaded twice in one JVM, and the first backend that ships one
will say so loudly. The launcher then execs the extracted jar as a child JVM instead: same bundle,
same layout, one branch in the launcher. **Decide that on evidence, not now.**

**Mismatch reporting.** The backend already knows what it speaks (`RunnerMain.PROTOCOL`). It goes in
the `READY` line, and the launcher asserts it against what the ping said. A wrong pairing becomes
`server speaks 765, this backend speaks 772` instead of `Outdated client!` arriving from three
layers away.

**Shared source.** `backends/shared/src` is added as a `srcDir` to each backend module; a backend
whose library diverged excludes that file and keeps its own copy. Sharing is **per library band, not
global** — expect a hard seam where MCProtocolLib was repackaged (`org.geysermc.mcprotocollib` vs
`com.github.steveice10.mc.protocol`); the exact release where that happened is in §5. Two copies
across that seam is the correct outcome, not a failure: design.md §4.2 says write version-specific
code where versions actually differ.

#### One version, end to end

"A runner per version" does not go away — it moves. It stops being a *file the caller picks* and
becomes a *module that is built, embedded and selected*. The per-version thing exists at four levels
and looks different at each.

**1. Source.** One module per protocol, and a shared tree they all compile:

```
bot/backends/
├── shared/src/main/java/…/bot/runner/     RunnerMain, session/, world/, menu/, hud/, action/
├── backend-772/
│   ├── build.gradle.kts                   org.geysermc.mcprotocollib:protocol:1.21.7-1
│   └── src/main/java/                     (empty — nothing about 772 differs)
└── backend-765/
    ├── build.gradle.kts                   the 1.20.4 coordinate
    └── src/main/java/…/bot/runner/session/Login.java    ← overrides the shared one
```

Override is **"drop a file at the same relative path"**, with no list to maintain: the convention
plugin adds the shared tree already filtered by what the backend carries locally. Implement that as
a filtered `FileTree` passed to `srcDir`, **not** as `java.exclude(...)` — an exclude pattern is
matched against every source directory and would drop both copies, leaving a missing-symbol error
that reads like anything but the cause.

Where a library band changes shape entirely (the MCProtocolLib repackage, §5), `shared/` gains a
sibling — `shared-legacy/` — and each backend takes the one its coordinate matches. Two bands, not
two copies per backend.

**2. Build.** Each backend shades to `backend-<protocol>.jar`. The bundle takes them through
`processResources` into `backends/<protocol>/`, so they end up as **ordinary resources** and never
touch shadow's merging, or the bundle's classpath.

**3. Bundle.** One `bot-runner.jar` ships. Inside it the per-version material is data.

**4. Runtime.**

```
BotRunner.launch(bot-runner.jar, host, port)
        └─ java -jar bot-runner.jar <host> <port>
             ├─ ServerPing(host, port)                 → version.protocol = 765
             ├─ BackendCatalog                         → backends/765/backend-765.jar
             ├─ extract to ~/.vitaminmcp/backends/765/ (keyed on the bundle's build id)
             ├─ URLClassLoader(parent-last, that jar only; SPI package parent-first)
             ├─ ServiceLoader.load(BotBackend.class, loader)  → Backend765
             ├─ backend.connect(host, port)                   → READY 765
             └─ RunnerDispatch: line in → backend.<method> → line out
```

The dispatch loop and the line protocol live in the launcher and are compiled once. What the
backend implements is `BotBackend` — `position(name)` returns a `Position` record, not a tab-joined
string.

**Why identical package names never collide.** They would collide on one classpath. There is never
one classpath: the launcher's own classes include no `…bot.runner` at all — only `bot.spi`, which
both sides share deliberately — and each backend jar is reachable only through the loader that owns
it. One backend is loaded per process, because a process serves one server. The isolation that
separate runner *processes* provide today is provided by a loader instead — the same guarantee, one
JVM cheaper, and one jar to install.

**So what does "add a version" cost?** A directory, a coordinate, and a `versions.yaml` block —
plus, only when the version actually diverges, the one file that diverged.

#### Override first, inheritance once it has earned it

**This is about the shared implementation, not the boundary.** At the boundary the answer is an
interface, and §2.1.1 is why. The question here is narrower: below `BotBackend`, should the 1600
lines of protocol code be shared by inheritance or by source override?

Inheritance is not ruled out there either, and it is worth being exact about why, because the loose
version of this argument ("shared code can't name library types") is wrong.

**`shared/` is not a module.** It has no build script and never compiles on its own; each backend
compiles it **as its own source**, against its own MCProtocolLib. So a base class in `shared/`
*can* name `ClientSession` or `ItemStack` — those resolve per backend. An abstract base with a
concrete subclass per backend compiles fine. The reason not to start there is cost, not
impossibility.

**Inheritance requires deciding the seams in advance.** An abstract method exists because someone
predicted a divergence. Predict wrong and there are two outcomes, both bad: an abstraction with one
implementation forever, or a real divergence at a point with no seam — where the answer is file
override anyway, now sitting next to an abstraction that did not help.

**And every seam has to be materialised in every backend.** Shared code cannot name
`backend-765`'s subclass, so instantiation needs a fixed name each backend supplies, or a
`ServiceLoader`. Either way, a backend that differs nowhere still writes an empty subclass per
seam — N seams × M backends of boilerplate. Under file override, a backend that differs nowhere is
an **empty directory**, which is exactly what `backend-772` should be.

**A base class also cannot cross a library band.** Where MCProtocolLib was repackaged, a signature
mentioning its types does not survive; only library-free signatures do, and those are the ones that
share nothing (§2.1). Inside a band, ordinary object orientation is unrestricted.

**What inheritance genuinely buys** is the one thing override lacks: a shared file that improves
later does not reach a backend that copied it. Overrides fork, and forks drift. So the promotion
rule is evidence, not taste:

> When **two or more backends have actually diverged at the same point**, that point has shown its
> shape. Promote it to a base class then, inside its band. Not before.

That is design.md §4.2 — write version-specific code where versions actually differ, do not abstract
in advance — applied one level down, and it is the second reason the `bot.runner` feature split in
§2.5 matters: small overrides fork little while waiting for the evidence.

**Override is still compiler-checked**, just not by a type contract. Every backend compiles the
*whole* shared tree, so a replacement whose signature drifts from what its shared callers expect
fails that backend's build.

**The version-free API already exists**: the line protocol, `RunnerProtocol` — text, and the real
boundary between the launcher's world and the backend's. A Java type hierarchy spanning bands would
be describing that same seam a second time.

#### Worked example: the bot's coordinates

The rule this establishes is more useful than the example, so the example comes first.

**Today, on 772** (`BotSession.java:780`, `BotActions.java:62`):

```java
private volatile org.cloudburstmc.math.vector.Vector3d position;   // :53

public void packet(ClientboundPlayerPositionPacket position) {     // :780
    BotSession.this.position = position.getPosition();             // Vector3d
    session.send(new ServerboundAcceptTeleportationPacket(position.getId()));
}

session.send(new ServerboundMovePlayerPosPacket(true, false, x, y, z));   // onGround, horizontalCollision
```

**On 765 the same three lines change shape.** The position packet was reworked in 1.21.2 to carry
delta movement, and movement packets gained the horizontal-collision flag in the same release, so
the older library has `getX()/getY()/getZ()` where this has a `Vector3d`, a differently named
teleport id, and a four-argument move packet. *Exact names are §5's "verify the coordinates" item —
the shape difference is the point, not the spelling.*

**What forks is only the ingest.** `x()`, `y()`, `z()`, `blockX()`, `describePosition()`,
`awaitGrounded`'s settle loop, every caller in `RunnerMain` — none of them care which packet shape
delivered the number. They fork on nothing.

So the split is:

```
shared/…/runner/session/Position.java     three doubles + x()/y()/z()/blockX()…   never overridden
shared/…/runner/session/PositionSync.java the packet handler and the tick sender   ← the seam
backend-765/…/runner/session/PositionSync.java   same name, same signature, older packets
```

**The rule: convert the library's type to ours at the point it arrives.** `position` is a
MCProtocolLib `Vector3d` today, and it reaches six places — the tick sender, the settle loop, the
accessors, the failure message. Every one of those is a place a fork could spread to. Store three
doubles at ingest and the fork cannot leave `PositionSync`.

That is the same instinct as `x()`'s existing comment ("callers outside this module have no business
resolving one", `BotSession.java:425`) applied one level further in: keep the protocol type inside
the *file* that speaks the protocol, not merely inside the module.

### 2.2 Build wiring, so a backend is a directory and a coordinate

- New convention plugin `vitaminmcp.bot-backend`: applies `executable-jar` + `module-rules`, wires
  the shared srcDir, derives `archiveBaseName` and `Main-Class`. A backend's build script becomes
  its MCProtocolLib coordinate and nothing else.
- `settings.gradle.kts` includes `bot/backends/backend-*` by directory scan.
- `module-rules` gets a pattern entry for `:backend-*` → `{:bot-core, :contract}`. The whitelist
  stays exhaustive: a module matching no pattern still fails the build.
- `dist` ships `bot-runner` only. Backends are embedded, never installed separately.
- The bundle pulls backends through a dedicated `backends` configuration — not `implementation` —
  so they stay off `bot-runner`'s compile classpath by construction rather than by discipline.

### 2.3 The matrix learns which JVM

Paper 1.18–1.20.4 needs Java 17 and will not run on 21; 1.20.5+ needs 21. One `javaHome` cannot
serve both.

- `MatrixRunner` splits the two JVMs it conflates today: **the server JVM is per version, the runner
  JVM is always ours.**
- `versions.yaml` gains a top-level `javaHomes:` block keyed by release. A version entry stays one
  block — the release it needs is derived from its id by the same table `SupportedVersions` already
  encodes, duplicated into orchestrator as plain Java with a pointer in both directions.
- A version whose JVM is not registered fails **before** the Paper download, naming the release it
  wanted.

### 2.4 The agent stays a single jar

`FLOOR = "1.18.2"` → `release 17`, `paper-api:1.18.2`, `api-version: 1.18`. One jar over
1.18–latest keeps design.md §5.3 intact, and splitting it is not on the table: the audit in §1.1 is
small enough that reflection covers it.

The §5.5 trap gets **wider**, not narrower — an API shape change anywhere between 1.18 and latest is
now inside the supported range, and the compiler catches none of it. The compat scenario in §4 is
what stands in for that.

### 2.5 Packages

The repository has one package per module and no subpackages today. Multi-version support is the
reason to change that — in one place for a concrete gain, elsewhere only if wanted.

```
moe.vitamin.minecraft.mcp
├── contract                      unchanged, flat. DTOs are one feature
├── agent
│   ├── core
│   │   └── compat        NEW     version-tolerant API access, one class per shim
│   └── mcp                       unchanged
├── bot
│   ├── core                      wire / process / identity split optional (see below)
│   │   └── ping          NEW     ServerPing — reads version.protocol off a status handshake
│   ├── spi               NEW     BotBackend + its records. The one package both sides share,
│   │                             library-free, parent-first in the loader
│   ├── launcher          NEW     RunnerLauncher, BackendCatalog, BackendLoader, RunnerDispatch
│   └── runner                    every backend, identical names in all of them
│       ├── (Backend)             implements bot.spi.BotBackend
│       ├── session       SPLIT   connect, login, tick, position
│       ├── world         SPLIT   entity tracking
│       ├── menu          SPLIT   container id, title, items, clicks
│       ├── hud           SPLIT   boss bars, scoreboard, teams, chat messages
│       └── action                BotActions
├── orchestrator
│   ├── paper             SPLIT   PaperDownloader
│   ├── server            SPLIT   ManagedServer + ServerProperties (version-conditional)
│   └── version           SPLIT   VersionMatrix + JavaReleases NEW + JavaHomes NEW
├── testkit
│   ├── agent / scenario / matrix SPLIT, cosmetic
│   └── compat            NEW     the cross-version compat scenario of §4
└── server                        unchanged
```

#### The one split that is not cosmetic: `bot.runner`

Backends share source and override it **by whole file** — that is what a shared `srcDir` plus an
exclusion can express. So **the file split decides the override granularity**, and today the file
split is one 1094-line `BotSession` carrying six concerns at once: login lifecycle, ticking and
position, entity tracking, container state, chat messages, and boss bars plus scoreboard.

A 1.20.2 backend that has to handle the configuration phase differently would, as things stand,
copy all six to change one. Split by feature and it overrides `session` and leaves `world`, `menu`
and `hud` shared. **The marginal cost of a version is set here, before any version is added.** Do it
in 6b, when the source moves into `backends/shared` anyway — moving it twice is the only way to make
this expensive.

The corresponding constraint: **the feature packages must be identical in every backend.** Override
by exclusion works only when the replacement has the same fully-qualified name, so a backend cannot
rename a package to suit its library.

#### The rest

`orchestrator` gains real material — `JavaHomes`, `JavaReleases`, version-conditional
`ServerProperties` — so a split there pays for itself. `agent.core.compat` earns its place in 6e:
the API audit produces a handful of reflective accessors, and `getTPS` proves they otherwise scatter
into whatever class needed them (`CaptureService.java:198`).

`bot.core` and `testkit` splits are presentation. Four files and five files respectively, no new
material arriving. Worth doing for consistency if the convention is changing anyway; not worth
sequencing any other work behind.

**Two things move with a package and break silently if they do not:** `plugin.yml`'s `main:` and
every jar's `Main-Class`. Neither is compiled against.

#### Where version knowledge lives

`SupportedVersions.requiredJavaRelease` already encodes the MC↔Java table in Kotlin, in
`build-logic`, where the build needs it. `orchestrator.version.JavaReleases` needs the same table at
runtime and **cannot import it** — build-logic is an included build, not a dependency. Putting it in
`contract` to have one copy does not fix that; build-logic still could not reach it, and `contract`
would be holding something that is not a DTO.

So: two copies, each pointing at the other, and a test in orchestrator pinning the boundary values
(1.17, 1.18, 1.20.5). Duplication that is visible and tested beats a single source that only one of
the two callers can actually use.

#### Backend package identity

**Every backend uses the same package and the same class names** —
`moe.vitamin.minecraft.mcp.bot.runner.{RunnerMain, BotSession, BotActions}`, once per protocol. That
is not a collision to fix, it is the point:

- shared source in `backends/shared` cannot carry a per-protocol package name, so if backends were
  named apart, nothing could be shared;
- a backend that diverges drops its own copy of one file at the same fully-qualified name and
  excludes the shared one — the override works precisely because the name is identical;
- isolation comes from the classloader, which is where it belongs. This is the concrete reason the
  bundle beats shading: relocation would force `…runner.p772.BotSession` and delete the sharing.

**The protocol number appears in the module directory, in the backend's `PROTOCOL` constant, and in
its `META-INF/services` entry. Nowhere else** — not in a package, not in a class name, not in
`versions.yaml`.

**Exactly one package crosses the classloader boundary: `bot.spi`.** It must be parent-first in the
loader, or the two sides would each hold their own `BotBackend` class and the cast would fail with
the least helpful message in Java. Everything else is child-first — the backend even carries its own
copy of `bot.core`, and the two copies never meet.

Jar layout:

```
bot-runner.jar
├── moe/vitamin/minecraft/mcp/bot/spi/*.class           the contract, shared with every backend
├── moe/vitamin/minecraft/mcp/bot/launcher/*.class      loader, catalog, RunnerDispatch
├── moe/vitamin/minecraft/mcp/bot/core/*.class          the launcher's copy
├── backends/772/backend-772.jar                        a resource, never on the classpath
├── backends/<n>/backend-<n>.jar
└── META-INF/MANIFEST.MF   Main-Class: …bot.launcher.RunnerLauncher
```

Backends extract to `~/.vitaminmcp/backends/<protocol>/`, mirroring `PaperDownloader`'s cache
(`PaperDownloader.java:80`) — outside any working directory, kept between runs, and overridable by
system property for a machine with no writable home.

### 2.6 Server setup differs by version

`ManagedServer` writes one fixed `server.properties`. It needs version-conditional entries:

- `enforce-secure-profile=false` for 1.19+ — without it, signed-chat versions reject a bot's chat
  and the failure looks like a scenario bug.
- Whatever else a live start turns up. Keep it one template with a conditional, not a file per
  version.

---

## 3. Stages

Each stage is independently shippable and has to meet its DoD before the next starts, same rule as
`roadmap.md`.

### 6a — Selection inside one jar (still only protocol 772)

Bundle layout, ping-based detection, isolating classloader, the `BotBackend` SPI and the dispatch
loop moving up into the launcher, protocol in `READY`. No new protocol yet, so nothing observable
changes.

**DoD**
- `gradlew dist` produces exactly one runner jar, named `bot-runner.jar`.
- `RunnerProtocol` is parsed in exactly one place — the launcher. No backend contains dispatch code.
- The SPI names no MCProtocolLib type. A test asserts it: every method signature resolves against
  `java.*` and our own packages only.
- The existing live scenario passes on 1.21.8 through the bundle.
- A backend deliberately mismatched against the server reports both numbers by name.
- `session_start` without `runnerJar` finds the bundle beside the server jar.

### 6b — Build templating

`vitaminmcp.bot-backend`, directory-scan includes, pattern whitelist, shared source dir.

**DoD**
- Adding a backend is a directory plus one coordinate; no edit to `settings.gradle.kts`,
  `module-rules` or `dist`.
- A backend declaring a forbidden dependency still fails `check`.
- 772 rebuilds byte-for-identically in behaviour — the live scenario re-run proves it.

### 6c — First upward backend

The protocol current at the time. Proves the seam works upward before anything harder is attempted.

**DoD**
- Live scenario passes on that version.
- Matrix green across 1.21.8 and the new version in one run.
- The `versions.yaml` change is one block.

### 6d — Per-version JVM

Server JVM split from runner JVM; `javaHomes:`; derived release.

**DoD**
- A matrix whose entries need different JVMs starts both.
- A missing JVM is named before the download, not as a startup timeout.

### 6e — Floor to 1.18.2

`FLOOR`, the API audit from §1.1, `enforce-secure-profile`, `api-version`.

**DoD**
- Compiles clean at `release 17`.
- The agent loads and captures on **1.18.2, 1.20.4 and 1.21.8** — started live, all three.
- After the compat scenario, `exceptions_recent` shows no `IncompatibleClassChangeError`,
  `NoSuchMethodError` or `NoClassDefFoundError` on any of them.

### 6f — Downward backends

One protocol at a time, each live-verified before the next. The MCProtocolLib package seam is
crossed here; the first backend on the far side is the one that shows what shared source really
costs.

**DoD (per backend)**
- Live scenario passes on a real server of that version.
- The matrix stays green on every version already in it.

### 6g — Compat scenario across the matrix, and the docs

**DoD**
- One scenario, run across the full matrix, green.
- `README.md` and `docs/usage.md` describe one runner jar, no protocol in any path.

---

## 4. The compat scenario

The stand-in for a compiler that cannot see version drift. One scenario, run on every entry, that
touches each place where the API has historically changed shape:

- join, chat, break a block — the capture path end to end;
- open a chest and a crafting table, click a slot, close — the `InventoryView` trap that §5.5 was
  written about, and the one place the measurement in §5.5 already exists to compare against;
- `state_query` for inventory, scoreboard and permissions;
- read the boss bar and sidebar, since those are the newest thing the bot inspects;
- `exceptions_recent` asserted empty of the three linkage errors above.

A version passing the scenario but throwing a linkage error into the log **fails**. That combination
is precisely what shipping a single agent jar across seven Minecraft versions risks.

---

## 5. To verify before building on it

None of these are blocking for 6a–6b; all of them are blocking for 6f.

- **MCProtocolLib coordinates and the package seam.** Which releases exist per protocol, and exactly
  where `com.github.steveice10.mc.protocol` became `org.geysermc.mcprotocollib`. The shared-source
  split follows from that line.
- **Protocol numbers.** Do not trust a table from memory — read them off a running server with the
  same ping the launcher uses.
- **Paper builds for old versions on `fill.papermc.io/v3`.** `PaperDownloader` targets v3; confirm it
  serves 1.18.2, 1.19.4 and 1.20.4 builds before assuming the matrix can reach them.
- **Netty in a child classloader**, and whether any backend ships a native transport.
- **The configuration phase (1.20.2+).** Login goes login → configuration → play from there; how much
  of `BotSession` that moves is unknown until a backend below it is written.
- **Bundle size.** Each backend carries MCProtocolLib plus its dependencies. Four or five of them is
  a jar in the tens of megabytes — acceptable, but it should be a decision rather than a surprise.

---

## 6. Explicitly out of scope

**Below 1.18.** `release 8` deletes every record in `contract`, which is a rewrite, not a port
(design.md §5.4). If a real 1.16.5 or 1.12.2 requirement appears it is a separate project — an
`agent-legacy` module, or Via re-examined — and this plan is not a step toward it.

---

## 7. Documents this revises, once approved

| Document | What changes |
|---|---|
| `CONTRIBUTING.md` | "One JVM cannot speak two protocols" → one *classloader* cannot. Module list gains `bot-runner` and `backends/`. Runner naming rule |
| `docs/design.md` §4.2 | Per-protocol runner *modules* become per-protocol *backends inside one jar*; add a dated revision note in the style of §4.1 |
| `docs/design.md` §5 | Floor 1.21.8 → 1.18.2, with the measured cost from §1.1 |
| `docs/design.md` §15 | `javaHomes:` in `versions.yaml`, and why the protocol number is deliberately not in it |
| `docs/roadmap.md` | Stage 6, from §3 above |
| `README.md`, `docs/usage.md` | One runner jar; no protocol number in any install path |

Nothing in this file is a decision yet. §7 is the list that makes it one.
