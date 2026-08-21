# mineflayer migration roadmap

Replacing the Java bot runner with one built on [mineflayer](https://github.com/PrismarineJS/mineflayer).
The decision, and the options rejected on the way to it, belong in `design.md` §2's revision note;
this is the order the work happens in.

Each stage **must meet its definition of done before the next one starts.** They are ordered so
that the thing most likely to kill the migration is proven first, and the thing hardest to undo —
deleting the Java runner — happens last.

Three rules hold across every stage.

- **The Java runner stays the oracle until Stage 5 passes.** It is the only reference for what the
  MCP tools currently return, so it is not deleted, not deprecated, and stays selectable. Every
  parity claim is a diff against a live run of it, never against a reading of its source.
- **The wire protocol does not change.** `RunnerProtocol`'s verbs, field order and separators stay
  as they are. Watch the separators: `RECORD_SEPARATOR` and `UNIT_SEPARATOR` are 0x1E and 0x1F and
  are **invisible in a diff**.
- **Node stops being optional the moment this ships.** Stage 8 exists to make that untrue again,
  and no release goes out between Stage 5 and Stage 8.

---

## Stage 0 — the spike ✅ done 2026-08-22

Prove a mineflayer bot can carry an injected identity, because nothing else matters if it cannot.

**Result: passes.** `createBot({ fakeHost })` writes the handshake's server address field verbatim,
NUL separators included, so `design.md` §3.1's forwarding handshake works unchanged. The server
recorded both the injected UUID and the spoofed `clientIp`, and `src/identity.mjs`'s `offlineUuid`
matches Java's `UUID.nameUUIDFromBytes` byte for byte.

Detail and the reproduction command are in `../bot/bot-runner-node/SPIKE.md`.

---

## Stage 1 — the process and its lifecycle ★ the seam

Get a Node process speaking the existing line protocol, and the Java side launching it.

**Work**

- [ ] `bot/bot-runner-node/src/protocol.mjs` — port `RunnerProtocol`: separators, `encode`,
      `decode`, `records`, `fields`, `sanitize`
- [ ] The stdio loop — read a line, dispatch, write `ok`/`err`, and print `ready` once connected.
      **`ready` carries the protocol number**, which `BotRunner.launch` parses
- [ ] Lifecycle verbs only: `spawn`, `despawn`, `position`, `shutdown`
- [ ] Negotiate the version by pinging the server, the way `BackendCatalog` does, rather than
      trusting `minecraft-data`'s auto-detection to arrive at the same answer
- [ ] `BotRunner.launch` takes a command line rather than a jar path — the `ProcessBuilder` at
      `bot-core/.../BotRunner.java:45` becomes `node <runner.mjs>` when handed a `.mjs`
- [ ] Keep the jar path working. One environment variable or `runnerJar` value picks the runner, so
      both can be driven against the same server in the same afternoon

**DoD**

- `session_start` then `bot_spawn` against the Node runner returns a bot standing in the world
- `state_query kind="player"` reports the **same UUID and the same address** the Java runner
  produces for the same bot name — read from the server, never from the bot
- `bot_spawn` with `clientIp` set is attributed to that address in `PlayerLoginEvent`
- Killing the MCP server leaves no orphaned Node process
- The Java runner still passes everything it passed before

---

## Stage 2 — the packet verbs

The eight verbs that are one packet each. Mechanical, and the cheapest part of the migration.

**Work**

- [ ] `break`, `command`, `chat`
- [ ] `use` (right-click a block, with the face argument) and `use_entity` (nearest entity to a
      point, within a radius, optionally filtered by type — **returns the entity id**)
- [ ] `click` (slot, with `left` / `right` / `shift_*`), `close_menu`, `menu`
- [ ] Error text: an unknown bot, a closed menu, an out-of-range slot must produce the *same* `err`
      shape the Java runner produces. `ScenarioRunner` surfaces these to the user verbatim

**DoD**

- Every `SessionLiveTest` scenario that does not move a bot passes on the Node runner
- For each verb, a run against both runners produces the same protocol line, **compared as bytes** —
  the separators do not survive being checked by eye
- `use_entity` against a villager returns an entity id the agent can confirm with `state_query`

---

## Stage 3 — `inspect` and the `ClientView` contract

Everything the client was told. The stage that **deliberately breaks a contract**.

**Work**

- [ ] `inspect` — menu, items, messages, boss bars, scoreboard, in `RunnerDispatch`'s field order
- [ ] Messages keep covering action bar, title and subtitle, each prefixed with where it appeared.
      A plugin refuses above the hotbar as readily as in chat
- [ ] **`MenuItem.itemId` becomes a name** — `minecraft:diamond_sword`, not `898`. Decided
      2026-08-22: it is not used enough yet to be worth a transition period, so it is replaced
      rather than joined by a second field
- [ ] Update `assert_inventory`, and every scenario and test that names an item numerically
- [ ] `bot_inspect`'s description loses the sentence "Item ids are numeric here — the protocol
      carries no names", which stops being true
- [ ] A breaking-change note in `README.md` and the release notes. A published scenario asserting
      on a numeric id will now fail rather than silently pass

**DoD**

- A plugin GUI drawn entirely with packets reads back through `bot_inspect` with names, and its
  slot count, titles, lore and `customModelData` match what the Java runner reported for that menu
- `state_query kind="inventory"` and `bot_inspect` agree on item names for a menu the server also
  holds — the agent already speaks in `Material` names, so the two now have to line up
- Boss bars and scoreboard survive a round trip with colour codes intact

---

## Stage 4 — movement, and the reason for all of this

**Work**

- [x] `move_to` gains `mode`: `"path"` (default) and `"teleport"`. **Teleport is not deleted** —
      putting a bot at a coordinate during setup has to stay fast and certain, and a walk that
      cannot reach its destination would break every scenario that only wanted a bot standing
      somewhere
- [x] `mineflayer-pathfinder` for `"path"`. Verify its LICENSE before depending on it, the same
      rule that applies to `prismarine-physics`
- [x] `mode: "teleport"` has to coexist with mineflayer's own physics timer, which will fight a
      position it did not compute. Expect this to be the fiddly one
- [x] `timeout`, and **failure that says which failure it was.** "No path exists" and "did not
      arrive in time" are different answers, and a scenario that cannot tell them apart is a
      debugging dead end
- [x] Tick determinism against `design.md` §12 — mineflayer runs its own timer while `wait_for` is
      evaluated server-side. Establish whether the two can disagree

**DoD**

- [x] A bot walks around a wall to a destination it cannot reach in a straight line
- [x] Walking across a pressure plate fires the plugin listening for it — the concrete thing
  teleporting never did
- [x] A destination sealed behind a barrier returns "no path", not a timeout
- [x] `mode: "teleport"` still works, so existing scenarios are unaffected
- [x] `wait_for player_near` resolves from the walk, with no fixed wait anywhere in the scenario

**Verified 2026-08-22 on Paper 1.21.8:** the Node bot walked around a two-block wall, crossed a
pressure-plate row producing `PlayerInteractEvent(PHYSICAL)`, and reached the destination confirmed
by `state_query` plus `wait_for player_near` after one observed tick. A sealed room returned `No
path exists`; a one-millisecond route returned `did not arrive ... within 1ms`; teleport moved the
server-side player to its target despite mineflayer's physics timer. The first teleport attempt
exposed that timer overwriting the raw packet, so the runner now synchronises local position and
velocity and briefly suspends physics before restoring it.

---

## Stage 5 — the parity gate ★ the stage that decides it

Nothing new is built here. This is where the migration is accepted or sent back.

**Work**

- [ ] `SessionLiveTest` green on the Node runner
- [ ] `CompatibilityLiveTest` green — it starts its own server and collects failures rather than
      stopping at the first, which is exactly what is wanted here
- [ ] `MatrixRunnerLiveTest` green across every entry in `versions.yaml`
- [ ] Run the matrix under `vitaminmcp.repeat` for flakiness. mineflayer's physics timer is a new
      source of it, and a suite that passes four times in five is a failure
- [ ] Write down every place the output legitimately differs from the Java runner, and why

**DoD**

- All three suites green, on the Node runner, across every supported version
- The list of intentional differences is short, written down, and no entry's reason is "mineflayer
  does it that way"
- **Check what git tracks, not what runs here.** A green run on this machine has already been
  wrong once, for 25 commits

---

## Stage 6 — the verbs the Java runner never had

The payoff beyond physics, and cheap once the runner exists.

**Work**

- [ ] `attack_entity` — left-click. `use_entity` only ever right-clicked, so combat plugins were
      untestable
- [ ] `hold_item`, `drop_item` — the hotbar was not reachable at all
- [ ] `place_block` — placing from the hand, as distinct from `use` right-clicking a block
- [ ] `jump`, `sneak`, `sprint`
- [ ] `look_at` — yaw and pitch directly, instead of implying them through `use`'s face argument
- [ ] `assert_reachable` — can a player get from A to B? Region seals, barriers and maze-shaped
      builds become one assertion. **It needs no bot**: ask for the path and throw it away
- [ ] `bot_inspect` gains health, food, experience and active potion effects
- [ ] Every new verb lands in `bot_run_scenario`'s step list *and* its tool description

**DoD**

- A scenario kills a mob with `attack_entity` and asserts the death event
- A scenario equips an item, places it, and asserts the block with `assert_block`
- `assert_reachable` reports false for a sealed region and true once the barrier is removed

---

## Stage 7 — a live view of what the bot sees

Ask for it and a web page opens showing the bot's world. `prismarine-viewer` (MIT, 1.33.0) renders
the bot's loaded chunks in a browser; `mineflayer-web-inventory` (MIT) does the same for its
inventory. Neither is possible on the Java runner at any price, and both close the gap the roadmap
has always called dogfooding: **every tool here has been driven by tests, never by someone
watching.**

**The size is the design constraint.** `prismarine-viewer` unpacks to **269MB** — it carries
textures for every Minecraft version ever released. It cannot go inside the Stage 8 runner
binaries, and it must not be a hard dependency of the runner.

**Work**

- [ ] `bot_view` — a new MCP tool, not a scenario step. Starts a viewer for one bot and returns
      its URL. `what`: `"world"` (default) or `"inventory"`; `mode`: `"first_person"` or
      `"third_person"`; `stop: true` to close one
- [ ] **Bind `127.0.0.1` and nothing else.** This is an unauthenticated HTTP server showing a live
      game view; it follows the agent's own default rather than inventing a laxer one
- [ ] Allocate a free port and report it. Never fail because a hardcoded port was taken
- [ ] Lifecycle: a viewer dies with its bot, and `session_reset` closes every one. A leaked
      viewer holding a port across runs is the obvious failure here
- [ ] Fetch on demand as an optional asset, reusing Stage 8's fetcher — pinned checksum, cached
      per version. Nobody pays 269MB for a feature they never ask for
- [ ] Consider trimming the shipped textures to the versions in `versions.yaml`. Real saving,
      real maintenance cost; decide with a number rather than a guess

**DoD**

- `bot_view` returns a URL that renders the bot's surroundings, and the bot moves in it when a
  `move_to` runs
- `what: "inventory"` shows a plugin GUI the bot has open
- A second call for the same bot returns the same URL rather than starting a second server
- Closing a session leaves no listening port behind
- An install that never calls `bot_view` downloads nothing extra

---

## Stage 8 — distribution, so Node stops being a prerequisite

**No release ships between Stage 5 and this stage.** Today the jar-only install path needs Java
alone (`README.md` §Requirements); without this stage it would quietly start needing Node.

**Work**

- [ ] Hybrid launch, mirroring `findJava()` in `npm/lib/java.mjs`: use a Node on `PATH` or in
      `VITAMINMCP_NODE` when there is one, and fall back to a downloaded runner binary
- [ ] Generalise `npm/lib/jars.mjs` from jars to assets. It already does the hard part — pinned
      SHA-256, per-version cache, and a `checksums.json` whose stamped version is verified — and
      **none of that is deleted.** It gets pointed at a different file
- [ ] Per-platform runner binaries via Node SEA: win-x64, linux-x64, linux-arm64, darwin-x64,
      darwin-arm64. Blob injection cross-builds them from one Linux runner
- [ ] **macOS needs an ad-hoc code signature** or the binary will not run. The one real trap here
- [ ] `checksums.json` grows a platform key, and `stamp-checksums.mjs` follows
- [ ] `session_start`'s `runnerJar` is renamed and redescribed — it names a runner, not a jar
- [ ] `docs/publishing.md` gains the new assets and whatever errors this path turns out to produce

**DoD**

- A machine with no Node installs from the jars and connects bots
- A machine that has Node downloads **nothing** — the 93MB `bot-runner.jar` fetch is gone and
  nothing takes its place
- A tampered asset is refused, with a message that says so rather than reporting a missing package
- Release assets are five runners plus `VitaminMCP.jar` and `mcp-server.jar`, all checksummed

---

## Stage 9 — deletion, and the documentation debt

Last, and only once Stage 8 is done. Everything here is irreversible in practice.

**Work**

- [ ] Delete `bot/backends/backend-*` and `bot/backends/shared`
- [ ] Delete `bot/bot-runner` (the launcher) and the `BotBackend` SPI
- [ ] Delete the `.backend` embedding, the extracted-backend cache and the Shadow workarounds they
      needed. `docs/multi-version.md`'s three traps stop existing
- [ ] `README.md`: drop `allow-flight=true` from the server setup, and the "bot has no physics
      engine" paragraph
- [ ] `versions.yaml`: the protocol reasoning goes. `minecraft-data` already covers **1.21.9
      through 26.2** — every version the support table lists as Planned above the floor — so adding
      a version becomes one entry in the matrix
- [ ] `design.md` §2 says "Being a Java stack, MCProtocolLib is the bot implementation". It gets a
      **revision note, not a quiet edit** — the reversal is the useful part
- [ ] `docs/multi-version.md` is largely about a design that no longer exists. Decide whether it is
      rewritten or retired
- [ ] Retire the `vitaminmcp-backend-per-protocol` memory

**DoD**

- `./gradlew build` green with the backend modules gone
- No reference to MCProtocolLib outside a historical note
- A fresh clone builds and runs — verified from `git archive`, not from this working tree
- The README's requirements table is honest about Node

---

## Risks, worst first

1. **Tick determinism.** mineflayer runs its own physics timer while `wait_for` is evaluated inside
   the server; `design.md` §12 assumed one clock. Surfaces in Stage 4, judged in Stage 5, and is
   the most likely cause of a flaky matrix.
2. **`ClientView` fidelity.** Stage 3 changes `MenuItem` on purpose; the risk is changing anything
   *else* by accident, because the MCP tools' output is the product.
3. **Packaging.** Stage 8 is five platforms, a signing requirement and a checksum format change,
   all inside a release path whose failures are already documented as confusing.
4. **Upstream cadence.** mineflayer 4.37.1, last published 2026-05-03 — active, not fast. A
   protocol this project needs could land later than the server it belongs to.
5. **Velocity modern forwarding.** The spike covered only the BungeeCord form. `design.md` §3.1
   says a test environment holds the HMAC secret, so it should work — unproven.
