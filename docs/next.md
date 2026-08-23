# What to do next

Written 2026-08-23, at 2.2.0, for an agent starting with the repository and nothing else. Read
this before either roadmap.

## Read this first, or you will redo finished work

**`roadmap.md` and `mineflayer-roadmap.md` have stale checkboxes.** `roadmap.md` shows 8 items
ticked and 44 open; nearly all 44 are done and shipped. The roadmaps were written as plans and
never maintained as trackers. Treat them as *design rationale* — which is what they are good for,
and why they are still worth reading — and treat this file as the list of what is actually left.

**`handover.md` describes the mineflayer migration and stops there.** Its "how to work here"
half — the tool table, the commands, the traps — is still accurate and worth reading. Its "where
things stand" half is a snapshot from 2026-08-22.

## Where things stand

3.0.0 is the version being published to GitHub, npm and the MCP registry; 2.2.0 was the one before
it. `publishing.md` is the release runbook;
a release is a version bump plus a tag push, and the version lives in exactly one file
(`build-logic/src/main/kotlin/vitaminmcp.java-conventions.gradle.kts`) with
`node npm/scripts/stamp-checksums.mjs --sync` copying it to the other four.

Everything in both roadmaps is implemented and has been run against real servers. The
compatibility matrix (`versions.yaml`, 1.21.1 through 1.21.11) passes end to end.

**The dogfooding apparatus in `dogfood/` is the most useful thing here for deciding what to build
next.** Six blind rounds ran on 2026-08-23: a fixture plugin with one planted fault, a symptom in
a player's words, and a fresh agent given the symptom and the tools and nothing else. What it
measures is friction — the question that had no tool behind it, the description that misdirected —
because a capable agent reaches the bug anyway by reading source. `dogfood/JOURNAL.md` is the
record, and several tool descriptions are now written in direct response to a specific round.
Before changing any tool description, read it. `dogfood/ANSWERS.md` must never be read by a round.

---

## 1. Timestamps and a cursor on `bot_inspect.messages`

**Completed 2026-08-24 in `e4416b5`.** Messages now carry arrival timestamps and per-connection
sequence cursors; stale streams are rejected and retention loss is reported.

**The top open item.** Raised in some form by every dogfooding round from the second onward.

`messages` is a bare array of strings. `logs_query` and `events_query` both page with cursors and
carry timestamps; messages carry neither. So "the plugin sent nothing" rests on eyeballing an
array and trusting both that it is complete and that nothing arrived before you looked. With a bot
that has accumulated twenty messages there is no way to say which landed after the command you
just ran — and "did the plugin answer" is the single most common question asked of this tool.

**The design is settled.** Messages become records carrying a timestamp, the way menu items
already carry slot and id: the line protocol already has a unit separator for fields within a
record (`bot/bot-runner-node/src/protocol.mjs`), and `items()` in `dispatch.mjs` is the pattern to
copy.

**Why it was deferred rather than done:** it changes the runner-to-Java line protocol, which
CONTRIBUTING calls load-bearing, and it touches `clientview.mjs`, `dispatch.mjs`, `ClientView` on
the Java side, `bot_inspect`'s response, and `assert_message`. That is a deliberate morning's work,
not something to bolt on at the end of a session.

**Done means:** `bot_inspect` reports when each message arrived; a caller can ask for messages
since a cursor; `assert_message` still passes; and a dogfooding round can say "the reply arrived
400ms after the command" instead of "there is a reply in the array".

## 2. Trim `prismarine-viewer`, the way `minecraft-data` was trimmed

**Completed 2026-08-24.** The Windows viewer sidecar is derived from the package's own supported
version and data references, reduced to a 31MB archive (179MB unpacked), and fetched lazily with a
pinned checksum only when `bot_view` requests a world view.

`mineflayer-roadmap.md` Stage 7 leaves this open with "decide with a number rather than a guess".
There is now a number and a proven technique.

The viewer unpacks to **269MB** because it carries textures for every Minecraft version ever
released. 2.1.1 did exactly this to `minecraft-data` and took the Windows runner asset from 564MB
to 134MB — see `scripts/slim-minecraft-data.mjs` and design.md §16.2. The lesson that transfers:
**derive the keep-set from what the package itself references, never from directory names.** The
naive prune of `minecraft-data` built cleanly, started cleanly, and would have died the first time
a bot asked for a recipe, because 1.21.x borrows files from 1.16.1, 1.20, 1.20.2, 1.20.3 and
1.20.5.

Stage 7's other open item — fetching the viewer on demand as an optional asset with a pinned
checksum — is the same work from the other end, and `npm/lib/jars.mjs` already has the fetcher.

**Done means:** `bot_view` still renders on every version in `versions.yaml`, and nobody downloads
269MB for a feature they never ask for.

## 3. Linux and macOS runner assets

**Implemented 2026-08-24.** The release matrix now builds real Node SEA binaries for Linux x64,
Linux arm64, macOS Intel and Apple Silicon, signs both macOS binaries ad hoc, uploads all five
runner assets, and stamps all five hashes. The workflow must run on GitHub-hosted target runners;
this Windows workstation intentionally does not fake those binaries.

Before this change, 2.2.0 shipped Windows x64 only. `scripts/build-sea.mjs` already has the targets defined and refuses
to fake a cross-built binary — it needs a real Node executable for the target platform, named by
an environment variable per `mineflayer-roadmap.md` Stage 8.

**The one real trap is written down there: macOS needs an ad-hoc code signature or the binary will
not run.** `npm/` and the README currently call these platforms planned.

## 4. Two more dogfooding scenarios, both found by accident

`dogfood/SCENARIOS.md` ends with "Worth planting, not yet planted". Both came out of a round
rather than being designed, and both are nastier than anything deliberately planted:

- **A config key that is decoration**, with a startup log line repeating the lie. The fixture had
  one by accident and a round followed the tooling straight to the wrong fix — an operator would
  have flipped the key, restarted, and been out of moves. Nothing in a runtime toolset can answer
  "is this key wired to anything", which is what makes it worth planting.
- **A permission declared in plugin.yml and never checked**, so the command opens for everyone.
  Already present in the fixture; it needs a symptom and an answers entry.

Adding a scenario is: a branch in `DogfoodPlugin`, a value in `Scenario`, a symptom in
SCENARIOS.md, an entry in ANSWERS.md. Then run a round and journal it. Rounds are cheap and they
have found something every single time.

## 5. The second machine

The install path has been walked end to end from an empty server directory, but only on this one.
The remote half is unexercised: TLS with a real keystore, and `session_start` with `tls: "true"`
and a pinned fingerprint. This is the last thing standing between "works here" and "works for
strangers", and strangers are who this is for.

## 6. Versions above 1.21.8

**Completed 2026-08-24.** Paper 1.21.11 build 132 was added as one matrix block; the runner selected
protocol 774 and the full 20-check compatibility gate passed with no linkage errors. The bundled
1.21.x data line already covered it, so no runner code change was needed.

Before this change, `versions.yaml` stopped there and required a compatibility run first. This is
a matrix run rather than new code — the runner asks the server its protocol and picks the matching
`minecraft-data` entry. **Note the interaction with item 2**: the runner now bundles only the floor's
version line,
so a version above it is refused with a clear message rather than half-working. Adding a version
means the compat run *and* checking the bundle still covers it.

## 7. Smaller, in rough order

- **`session_start`'s response — completed 2026-08-24.** The response still carries the full server
  details and real agent tool definitions, but it now removes sessions whose child runner has
  exited before reporting the roster. The same pruning runs before session resolution, so a dead
  session cannot make an unnamed call ambiguous or leave a stale name behind.
- **The `open a container` flake — completed 2026-08-24.** `use_block` now waits for the target
  block to be known by the client and lets a queued block update settle before sending the
  interaction. The async path is awaited by the line dispatcher. The 1.21.8 compatibility gate
  passed three cache-bypassing reruns, including the container check each time.
- **World template isolation — completed 2026-08-24.** The live orchestrator test now generates a
  real Paper world, uses it as a template, changes a block in a second server, restores the
  template, and verifies after the next boot that the changed block is gone.

---

## How to work here

- `CONTRIBUTING.md` is the rules, and the invariants are numbered because the build cites the
  numbers back at you.
- `handover.md` §2 onward for the commands and the traps that have already cost time.
- `dogfood/README.md` for running a round.
- Live tests need real servers and are skipped unless asked for; the properties are listed in
  CONTRIBUTING, and **a property only reaches the test if that module's `build.gradle.kts`
  forwards it** — a missing one makes the gate skip and the run go green having tested nothing.
- **A green build proves nothing about a clean checkout.** For 25 commits this build passed only
  because an untracked file happened to exist on one machine.
