# Journal

One entry per round. The friction, not the diagnosis — and what it changed.

A round with no entry here did not happen, and an entry that changed nothing is a round to be
suspicious of: either the tools are in better shape than the roadmap thinks, or the round was too
easy to have measured anything.

Newest first.

---

## 2026-08-24 — `unenforced-permission`

**Diagnosis:** right. The player was not op and `state_query kind='player' permissions=["dogfood.shop"]`
reported `granted: false`, but `/shop` still opened a `Shop` menu with diamond and emerald slots.
The manifest declares `dogfood.shop` with `default: op`, while the command only checks that node in
the separate `silent-refusal` branch.

**Calls, in order:**

1. `session_start` — Paper 1.21.8 build 60, both VitaminMCP and DogfoodFixture enabled.
2. `server_info` — clean capture, no exceptions, no players online.
3. `state_query kind='plugin' target='DogfoodFixture'` — the live config and the declared
   `dogfood.shop` permission were visible; the `shop` command's own `permission` field was null.
4. `bot_spawn RoundPermission` — creative, online, non-op.
5. `state_query kind='player' permissions=["dogfood.shop"]` — explicitly confirmed the node was
   denied.
6. `bot_run_scenario` with `/shop` then `wait_for inventory_open` — both steps passed.
7. `bot_inspect` — the client had the `Shop` menu and its diamond/emerald contents.

**Friction:** small but real — a reader who looked only at the command's null `permission` field
could conclude it was intentionally ungated. The separate `permissions` list and the player test
were what settled it. The existing description now says to read both.

**Worked:** `state_query` supplied exactly the two halves that matter: what the plugin declares and
what this player is allowed to do. `bot_inspect` closed the loop from a successful command to what
the player actually saw.

**Changed** — no product schema change; the scenario and its answer were added so this declared-but-
unenforced permission remains a deliberate regression probe.

---

## 2026-08-24 — `decorated-config`

**Diagnosis:** right. The live config held `shop.enabled: false`, and the plugin logged that the
shop was disabled, but `/shop` still opened. The key is decoration in this scenario: the command
does not read it.

**Calls, in order:**

1. `session_start` — Paper 1.21.8 build 60, with DogfoodFixture enabled.
2. `server_info` — capture was healthy and the fixture was present.
3. `state_query kind='plugin' target='DogfoodFixture'` — live config showed `shop.enabled: false`;
   the `shop` command had no direct permission field.
4. `bot_spawn RoundConfig` — the bot connected in creative and was non-op.
5. `logs_query pattern="shop\\.enabled"` — empty, despite the startup line; the agent had attached
   after the line was emitted.
6. `bot_run_scenario` with `/shop` then `wait_for inventory_open` — both steps passed.
7. `bot_inspect` — the client had the `Shop` menu and its diamond/emerald contents.

**Friction:** the runtime tools could show the loaded value and the repeated claim, but not whether
the plugin's code wired the key to behavior. More concretely, the startup search was empty because
that log line predated the agent capture buffer, and the logs description did not say that could
happen.

**Worked:** `state_query` made the live value visible, and `bot_inspect` proved the behavior instead
of trusting the config or the command's empty synchronous output.

**Changed** — `agent-mcp`: `logs_query` now says startup lines before agent attachment may be absent;
`state_query kind='plugin'` now says a loaded config value is not proof that the plugin reads that
key. The fixture scenario and its answer are now planted deliberately.

---

## 2026-08-23 — `none` (the control)

**Result: correct.** It said plainly that nothing is wrong, having checked, and ruled out all five
fault paths one at a time rather than asserting it. It did not invent a fault — which is what this
scenario exists to test.

It also volunteered the one real oddity in a clean fixture: `plugin.yml` declares `dogfood.shop`
default `op`, and outside the `silent-refusal` branch nothing checks it, so `/shop` opens for
anyone. A declared-but-unenforced permission node is exactly the sort of thing that makes an owner
say "something's off" without being able to name it. Left as it is — it is realistic, and now
documented rather than accidental.

**A caveat that governs how rounds 2 to 6 should be read.** These rounds drove the *installed*
VitaminMCP 2.1.1 through its MCP server, and only the agent jar was redeployed between them. So
every agent-side fix was live for the rounds after it — and several were quoted back approvingly —
but the **bot-side fixes from round 1 were not**: `break_block`'s acknowledgement and `bot_spawn`'s
readiness wait live in the runner, which the installed package supplies. When this round says
`break_block` returns `"sent"` and cannot confirm the dig landed, it is describing 2.1.1, and the
fix for it is already committed. The same goes for `bot_inspect`'s `items: []`, which it flagged
as a near-misread — fixed in round 4, not yet released.

**Friction**

- **A bot that cannot break a block, and a plugin that cancels the break, are the same
  observation.** Three scenarios were spent on this. The round eventually distinguished them by
  reasoning that a *cancelled* BlockBreakEvent is still a *captured* one, so zero events meant the
  bot rather than the plugin. That inference is correct and nothing points anyone at it. **A less
  careful run reports "join-lockout confirmed" here and is wrong.** Round 1's acknowledgement fix
  answers this directly; this round is independent confirmation that it was the right thing to
  build.

- **`assert_reachable` answers a different question than the one being asked.** Used as "can the
  bot hit this block", it returned false for a block two metres away — it means "can the pathfinder
  walk there", and the block was floating. The failure text restated the assertion instead of
  saying what the pathfinder objected to.

- **`wait_for`'s timeout evidence is bounded but not relevant.** Forty events of ambient mob churn
  to find the two that mattered, unscoped to the player being waited for. The same complaint round
  1 made about a scenario's evidence, which was fixed there and not here.

- **Fourth mention of `session_start`'s payload**, now listing eight stale sessions from earlier
  rounds, several still holding bots.

**Worked**

- `state_query kind='plugin'` — "the single best tool here", live config and permissions in one
  call, corroborating the startup log independently. Added in round 3, decisive in rounds 4, 5 and
  6.
- `wait_for inventory_open` did exactly what it said.
- `bot_inspect` for messages, again: `command_exec` returned identical empty output for all three
  commands and the messages were the only thing separating working from broken.

**Changed** — `fix(agent-core, testkit)`:

- `wait_for`'s timeout snapshot is now filtered to the player the condition names, looking over a
  wider window to find them.
- `assert_reachable`'s failure says what reachable means — walkable, not within arm's reach — and
  what to do instead.

---

## 2026-08-23 — `disabled-feature`

**Diagnosis:** right, in 12 tool calls. And it caught the harness lying, which no round before it
had done.

**The finding of the whole exercise so far: the tools pointed confidently at the wrong fix.**
`state_query kind='plugin'` reported `kit.enabled: "false"`, and the startup log said
`kit.enabled is false in config.yml; /kit will not hand anything out.` Both named `kit.enabled`.
**The fixture's code never read that key** — it switched on `scenario` instead. An operator
working from VitaminMCP alone would set `kit.enabled: true`, restart, watch `/kit` still do
nothing, and have no next move. Nothing in the toolset can answer "is this config key wired to
anything"; only the source can, and the round only got it right because it had the repository.

That was the harness's fault, not the product's — an accident in the fixture, caught by the thing
built to catch accidents. It is fixed: `/kit` now reads `kit.enabled`, so the log line is true.
The bug class it exposed by accident — **a config key that is decoration, plus a log line that
repeats the lie** — is realistic and nastier than anything deliberately planted here, and is worth
a scenario of its own.

**Friction**

- **`bot_inspect.messages` has no cursor and no timestamps**, while `logs_query` and
  `events_query` both page. "The plugin sent nothing" rested on eyeballing a one-element array and
  trusting both that it was complete and that nothing had arrived before the round looked. With
  twenty accumulated messages there would be no way to say which landed after the command.

- **`bot_spawn` did not report gamemode.** The bot was in creative, learned incidentally from the
  `view` field of an inventory query — a field documented as being about menus. Gamemode is
  load-bearing for any inventory assertion.

- **`state_query kind='plugin'` was sold for the wrong job.** Its description opens with "start
  here for 'it works for admins but not for players'". What this round needed was the live config
  as opposed to the repository's, and it found that capability by reading the raw schema dump
  rather than because the description matched the problem.

- **Third mention: `session_start`'s payload**, now also listing eight stale sessions from earlier
  rounds, several still holding bots.

**Worked**

- `command_exec`'s description, quoted back approvingly: it warned that `as` + `dispatched: true`
  + empty output cannot distinguish success from silent refusal and said to read `bot_inspect`
  first. The round did exactly that. That text was written in response to round 3.
- `exceptions_recent`'s new "not since boot" warning, likewise — written in response to round 4,
  used correctly in round 5.

**Changed** — `fix(dogfood, mcp-server, agent-mcp)`:

- The fixture reads `kit.enabled`, so the key it blames is the key it obeys.
- `bot_spawn` reports `gameMode` and `op` alongside the position.
- `state_query kind='plugin'` leads with the live config, since that is what it is best at.

**Left alone**

- Cursors and timestamps on `bot_inspect.messages`. The right fix and the most invasive one left:
  it changes the runner-to-Java line protocol, which CONTRIBUTING calls load-bearing. Design is
  known — messages become records with a timestamp field, the way menu items already are — and it
  wants doing deliberately rather than at the end of a long session. **Raised in some form in
  every round from 2 onward; this is the top open item.**
- `session_start`'s payload. Raised in rounds 1, 3 and 5. Every round that complained also praised
  it for meaning nothing had to be guessed. Trimming the stale session list is the cheap half.

---

## 2026-08-23 — `silent-listener`

**Diagnosis:** right, in 10 tool calls. The source gave up the suspect on the first read — an NPE
above `giveKit` in the join listener — so the round was about confirming *who* it happens to, and
that is where the tools were exercised.

**`state_query kind='plugin'`, added by the previous round, was the call that turned "the code can
NPE" into "it NPEs for everyone except Tester1".** The roster is one entry, and nothing else could
have shown that. First time an apparatus fix paid off inside the apparatus.

**Friction**

- **`bot_inspect`'s `items` nearly inverted the round's conclusion.** It returned `[]` for the
  control bot while that player was holding a full kit, because `items` means *the open menu's*
  contents and there was no menu. Nothing in the name says so. Read as "Tester1 has nothing" — the
  exact opposite of the truth, on the one subject that was supposed to be the control. Only a
  cross-check against `state_query kind='inventory' which='player'` caught it.

- **Bots accumulate state across rounds, and nothing warns you.** UUIDs are name-derived, so
  Tester1 came up carrying six bread and two wooden swords banked by earlier rounds. "Tester1 has
  the kit" was therefore far weaker evidence than it looked — equally consistent with a kit
  granted three rounds ago. The round recovered only because `exceptions_recent` gave `count: 1`
  with a `firstSeen` it could pin to the join it had just caused. There is no "give me a bot that
  has never played here".

- **"Has this been happening, and to whom?" has no answer.** The report is historical; the server
  had restarted 64 seconds earlier, so every real occurrence was gone and `count: 1` was the
  round's own bot. The bug had to be *reproduced* to be seen at all. An empty `exceptions_recent`
  reads as "this never happens" when it means "not since boot".

**Worked**

- `exceptions_recent` — called the best tool in the set. Collapsing to distinct exceptions with
  `count`/`firstSeen`, and putting the stack behind a second `hash` call, meant one small answer
  said *what* and a second said *where*, with no wading.
- `command_exec`'s new bluntness about `as` + `dispatched: true` telling you nothing. The round
  cited it and went to `state_query` for the real answer, exactly as the description now says to.
- `state_query kind='plugin'`, above.

**Changed** — `fix(mcp-server, agent-mcp)`:

- `bot_inspect` returns `items: null` with a note when no menu is open, instead of an empty array,
  and its description says in as many words that `items` is the open menu and never the player's
  inventory.
- `bot_spawn`'s description spells out the consequence of stable UUIDs: the server remembers these
  players, so a reused bot is not a fresh one, and says how to get a clean slate.
- `exceptions_recent`'s description says it covers the current server run only, so an empty answer
  is not evidence of absence.

**Left alone**

- Correlating a join with a listener that threw partway through. Worth having and not cheap —
  it wants the exception record to carry the event and player it happened inside, which is a
  change to how exceptions are captured rather than to how they are read.
- Wiping a bot's player data. A tool that deletes playerdata on a server this can be pointed at
  is a worse idea than the problem it solves; using an unused name costs nothing.

---

## 2026-08-23 — `silent-refusal`

**Diagnosis:** right, in 14 tool calls, and fast — `bot_inspect`'s description sent it straight
there on the first try.

The sharpest round so far, because it produced a controlled comparison nothing else had: the
**failing** call and the **succeeding** call returned byte-identical JSON.

```
/shop as a non-op   ->  dispatched: true, reason: null, output: []
/shop as an op      ->  dispatched: true, reason: null, output: []
```

One opened a menu. The other refused on the action bar. `command_exec` cannot tell them apart,
and its description was actively steering the wrong way: after the careful guarantee about the
`false` branch it reassured the caller that empty output is normal "even when it worked; the reply
reached the client" — which trains you to read this exact response as success.

**Friction**

- **`command_exec` reports a silent refusal as a clean success, and said so soothingly.** A plugin
  that refuses and returns `true` is the commonest shape of "the command does nothing"; this is
  the tool people point at it.

- **No tool reported a plugin's effective config.** `server_info` gives name, version, enabled.
  The round only recovered the live scenario because the fixture logs it at startup — a real
  plugin does not. The harness's own warning comment in `config.yml` was correctly called out as
  papering over a tooling gap rather than fixing one.

- **No way to discover which permission gates a command.** `state_query`'s `permissions` can be
  tested but never listed, so the node had to be known already — it came from reading plugin.yml.
  The server holds all of it in memory. For "works for admins only", the most common report a
  server owner writes down, that was the missing piece.

- **A flake it got away with.** It read `bot_inspect` immediately after `command_exec` and the
  action bar was already there — luck on an idle server. Round 2's fix put the wait in a
  scenario's `assert_message`; the step-by-step path still has none, so a busy server would have
  returned empty `messages` and the round would have concluded nothing was sent.

**Worked**

- `bot_inspect`'s description, called "the best-written thing in this toolset" — it states that
  refusals live in `messages`, that they never reach the console, and that a declined command and
  one that did nothing look identical from the agent's side. That sentence *is* this bug.
- The `[action bar]` prefix. Without it the round would have assumed chat and told the player to
  check their chat settings — the wrong fix.

**Changed** — `feat(contract, agent-core, agent-mcp)`:

- `state_query` gains `kind='plugin'`: a plugin's declared commands with the node gating each,
  the permissions it declares with their defaults, and its live config. Verified against the
  fixture — it reports `scenario: silent-refusal`, which is exactly what no tool could answer.
- Config values whose key looks like a secret come back `(redacted)`, with the key still shown.
  A plugin's config is where database passwords live and this tool is readable by anything
  holding the agent's token, so the pattern errs wide and has its own test.
- `command_exec`'s description now says outright that with `as` it CANNOT tell whether the
  command worked, and names the three things that produce an identical answer.

**Left alone**

- A wait primitive for messages outside a scenario. Same reason as round 2: the agent cannot see
  what a client was sent. Noted twice now; if a third round trips on it, `bot_inspect` should
  probably grow a `waitFor` argument.
- `session_start`'s payload size. Raised in rounds 1 and 3 both. Real, but it buys the caller
  every agent-side parameter without a second call, and both rounds praised that in the same
  breath as complaining about the size.

---

## 2026-08-23 — `async-reply`

**Diagnosis:** right, in 15 tool calls. It also pushed back on the report — measured, the reply
*always* arrives, so "it does nothing" is the player giving up inside a second rather than a
message that never comes. That correction is worth more than the diagnosis.

**Friction**

- **`wait_for` cannot wait for the single most common thing a plugin does: answer a player.**
  Its conditions cover blocks, events, players, inventories and logs. Chat is not among them, and
  neither is the action bar or a title. `log_matches` does not help, because a reply to a player
  never touches the log. So the round was forced into `wait_for ticks 20` — a fixed sleep, which
  this tool's own description calls "a guess about timing that is right on an idle server and
  wrong on a busy one". Every async plugin reply lands in this hole.

- **`assert_message` was a race by construction.** A bare one-shot check with no wait, while its
  neighbour `assert_inventory` is explicitly paired with `wait_for inventory_open` in the docs.
  The natural scenario — send a command, assert the reply — failed against any plugin that does
  not answer within the same tick, and reported `nothing said to RealPlayer contained 'points'`,
  which reads as a verdict when the truth was "not yet".

- **`command_exec` warns about the wrong half.** Its description explains at length that
  `dispatched: false` always carries a reason "so it never has to be read as a command that ran
  and did nothing". The trap the round actually hit is the inverse: `dispatched: true, output: [],
  20ms` is *exactly* what a late async reply looks like. The one sentence that would have saved
  it — "a declined command and one that did nothing look identical from the agent's side" — is in
  `bot_inspect`'s description, not where a caller is standing when they need it.

- **`command_exec`'s `as` is not what a player does, and nothing said so.** It dispatches
  directly and never fires `PlayerCommandPreprocessEvent`; the round only noticed because
  `events_summary` showed zero of them after three runs. A scenario's `command` step does go
  through the real path. Two tools with the same apparent job and materially different fidelity,
  with no note on either — on a server with a listener that cancels or rewrites commands, `as`
  would silently exercise a different code path than the bug report.

- **`events_summary` was 95% one event.** 8,295 `EndermanAttackPlayerEvent` out of 8,709, from a
  single enderman. The counts that mattered sat 37 rows down.

- **`logs_query` returned mostly the caller's own reflection.** Around twenty of twenty-five hits
  were the agent logging its own `tools/call` requests and full JSON echoes of results the round
  had just read — one of them 1,707 characters. There was no way to exclude it.

- **Minor:** captured log messages carry raw ANSI colour codes, in a JSON field nothing renders.

**Worked**

- `bot_inspect` again. Without its `messages` array this scenario is unsolvable, and its
  description advertises exactly that.
- `session_start` returning the full agent tool schema inline — nothing had to be guessed.
- `exceptions_recent` ruling out a whole branch in one empty answer.

**Changed** — all in `fix(agent-core, agent-mcp, testkit)`:

- `assert_message` now waits, with `timeoutMillis`, instead of checking once.
- `wait_for`'s description says what it cannot see — messages go to a client, not to the agent —
  and points at the bot side.
- `command_exec`'s description carries the inverse trap, and says `as` skips
  `PlayerCommandPreprocessEvent`.
- `EndermanAttackPlayerEvent` joins the high-frequency exclusions.
- The agent's own `MCP …` activity lines stay on the console but are kept out of the searchable
  buffer, and ANSI escapes are stripped from captured messages.

**Left alone**

- A `message_matches` condition on `wait_for`. It cannot be built where `wait_for` lives: the
  agent runs inside the server and never sees what a client was sent. Waiting for a message has
  to happen on the bot side, which is what `assert_message` now does.

---

## 2026-08-23 — `join-lockout`

**Diagnosis:** right, in 23 tool calls. It also found a second fault nobody had reported — under
this scenario `onJoin` returns before `giveKit`, so affected players get no kit either.

The round did not fail the way ANSWERS.md predicted. The decoy worked, but not as a decoy: the
debugger did reach "no `BlockBreakEvent` fired", and concluded from it that **VitaminMCP was
dropping cancelled events from capture** rather than that Paper's own join window was to blame. It
spent two reproductions on that theory. The theory was wrong and the reasoning was sound, which is
the worst combination and exactly what this apparatus is for.

**Friction**

- **`break_block` is fire-and-forget, and nothing closes the loop.** It answers `detail: "sent"`.
  No tool answers *did the server receive and process this bot's dig?*, so "the plugin cancelled
  it silently" and "the dig never arrived" are the same observation. Steps 7 through 11 of the
  round exist only to tell those apart, and they got there by luck — moving the attempt later made
  the cancelled event appear.

- **`bot_spawn` reads as "ready to act" and is not.** Its description says it waits until the bot
  is standing in the world, and it returns coordinates, which is the same shape an answer to
  "ready" would have. A dig issued immediately after it silently no-ops at the protocol level.
  **For a bug about the first seconds after a join, the harness's own startup artifact is
  indistinguishable from the bug under investigation.** There is no `wait_for` condition for "this
  bot's client has loaded and can interact"; `PlayerClientLoadedWorldEvent` is in the event stream
  but is not a bot-scoped condition.

- **`bot_run_scenario`'s `evidence` is scoped to the wrong thing.** The description promises what
  the server was doing at the moment of failure. What comes back is an event summary over the
  whole retention window, as one unwrapped JSON string, sorted by count, led by
  `EndermanAttackPlayerEvent: 2016`. `BlockBreakEvent` sat 25th, with a count that silently
  aggregated the round's own earlier *successful* breaks — so on a run where the break failed, the
  evidence read as though a break had happened. The debugger stopped reading it and re-queried
  `events_query` by hand every time.

- **`state_query kind=block` echoes the world it was given rather than the one it read.** Omit
  `world` and the response says `"world": null`, so it cannot confirm which world answered.
  Verified in the source: `AgentTools` puts the caller's argument back, while `blockAt` resolves
  the default internally. Small, but it is a read tool that will not say what it read.

- **`session_start` costs ~4k tokens of tool schemas** that had already been loaded another way,
  and it silently reported a pre-existing session against the same server — which made passing
  `session` explicitly mandatory from then on without saying so.

- **The fixture's checked-in `config.yml` says `scenario: none`** and the live server was running
  something else. The debugger nearly trusted it. That is the harness's own defect, not the
  product's.

**Worked, and worth not breaking**

- `bot_inspect` closed the case. The report said "nothing in chat", and chat alone would not have
  settled it — `silent-refusal` declines on the action bar — so covering action bar and title is
  what made the absence of a message mean something.
- `logs_query` against the plugin name is what stopped the round debugging the wrong scenario.
- `wait_for ticks` inside a scenario made bisecting the window exact: locked at 180, free at 220.

**Changed**

- `dogfood/fixture-plugin/src/main/resources/config.yml` — the default no longer reads like a
  statement about the running server.
- `dogfood/README.md` — a round now starts by asking the server which scenario is live, rather
  than reading the file.

**Not changed yet** — these are product changes and want deciding on, not patching in passing:
the `break_block` acknowledgement, a bot-readiness `wait_for`, the `evidence` scoping, and
`state_query`'s echoed world.
