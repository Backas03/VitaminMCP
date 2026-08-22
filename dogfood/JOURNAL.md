# Journal

One entry per round. The friction, not the diagnosis — and what it changed.

A round with no entry here did not happen, and an entry that changed nothing is a round to be
suspicious of: either the tools are in better shape than the roadmap thinks, or the round was too
easy to have measured anything.

Newest first.

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
