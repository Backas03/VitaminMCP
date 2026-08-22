# Journal

One entry per round. The friction, not the diagnosis — and what it changed.

A round with no entry here did not happen, and an entry that changed nothing is a round to be
suspicious of: either the tools are in better shape than the roadmap thinks, or the round was too
easy to have measured anything.

Newest first.

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
