# Answers

**A blind round must not read this file.** It holds what is actually wrong with each scenario, and
the path a healthy tool surface would make short.

The third column is the point. It is the claim each round tests: *if the tools are good, this is
how it should go.* A round that took a different path, or a much longer one, is a finding — and so
is a round that could not get there at all.

---

## `async-reply`

**Planted:** `/top` schedules an async task that sleeps 600ms and then messages the **player**.
Nothing is written to the console, and the reply happens after `command_exec` has already
returned.

**What it looks like:** `dispatched: true`, `output: []`. Formally fine, apparently dead.

**The short path:** `command_exec("top", as: "Tester1")` → empty output is expected for a
player-run command → `bot_inspect` messages, which has the leaderboard line.

**What it probes:** whether "dispatched, no output" reads as success. This one is documented in
the skill and in usage.md, so a round that still gets stuck here means the documentation is not
reaching the tool surface where it matters.

---

## `silent-refusal`

**Planted:** `/shop` needs `dogfood.shop` (default `op`). Without it the plugin sends the refusal
to the **action bar** and returns `true`, so Bukkit never prints its own permission message and
the console sees nothing.

**The short path:** `bot_inspect` messages, where action bar text appears prefixed with where it
appeared → `state_query kind=player permissions=["dogfood.shop"]` to confirm.

**What it probes:** whether a refusal delivered somewhere other than chat is discoverable. The
tool description for `bot_inspect` claims it is. This is the round that tests the claim.

---

## `join-lockout`

**Planted:** on join the player goes into a `loading` set for 200 ticks, and `BlockBreakEvent` is
cancelled at `HIGH` priority while they are in it. Nothing is sent to the player.

**The short path:** `events_query types=["BlockBreakEvent"]` → the event is there with
`cancelled: true` → the timing lines up with the join.

**It has a decoy, and that is the point.** Paper drops a joining player's block interactions for
about three seconds on its own while it loads their data — the compatibility harness has a fixed
tick barrier for exactly this. From the player's side the two are identical: you hit a block, it
does not break, no message. Ten seconds is long enough that "wait it out" separates them, but the
evidence that actually settles it is the event record: Paper's own window produces **no
BlockBreakEvent at all**, and the plugin's produces one with `cancelled: true`.

**What it probes:** whether `cancelled` is *findable*. The agent records it on every event, but
nothing in the tool descriptions says so, and a debugger who does not know it is there will look
for a missing event instead of a cancelled one — and Paper's own lockout is sitting right there
offering a plausible wrong answer. Watch for the round that concludes "no BlockBreakEvent fired,
so it is Paper's join window". That is the failure this scenario exists to catch.

---

## `silent-listener`

**Planted:** the join listener calls `getConfig().getString("teams." + name)` and then
`.toLowerCase(...)` on it. Any player not on the roster in config.yml gets an NPE inside the
listener, Bukkit logs it and carries on, and the `giveKit` call below it never runs. `Tester1` is
on the roster; anyone else is not — hence "the same people every time".

**The short path:** `exceptions_recent` → the NPE, grouped, with the fixture in the trace →
`hash` for the stack.

**What it probes:** whether an exception can be tied to the player it happened to.
`exceptions_recent` groups by shape and does not carry the player, so "which players" has to come
from somewhere else. Watch how the debugger crosses that gap, or fails to.

---

## `disabled-feature`

**Planted:** `/kit` returns `true` and does nothing. The reason was logged once, at `INFO`, at
startup: `kit.enabled is false in config.yml`.

**The short path:** `logs_query pattern="kit"` finds the startup line.

**What it probes:** whether a startup line is still reachable when the buffer has since filled,
and whether a debugger who does not know the wording can find it. `logs_query` takes a regex
against the message and there is no "show me startup" — deliberately. This scenario is the test of
whether that is the right call.

---

## `decorated-config`

**Planted:** `shop.enabled` is `false` in config.yml and the startup log repeats that value, but
the `DECORATED_CONFIG` branch never reads the key in `shop()`. The command opens the shop anyway.

**The short path:** `state_query kind='plugin'` → the live config says `shop.enabled: false` →
`logs_query pattern="shop.enabled"` repeats the same claim → `bot_inspect` after `/shop` shows the
menu. The source is required to answer the final question: whether a config key is wired to code.

**What it probes:** whether the tools can distinguish a live setting from a setting that is merely
decoration. They cannot prove that wiring from runtime state alone; the important friction is the
confidently wrong startup line.

---

## `unenforced-permission`

**Planted:** `plugin.yml` declares `dogfood.shop` with `default: op`, but `shop()` checks that
permission only in the `SILENT_REFUSAL` branch. In this scenario a non-op opens the menu.

**The short path:** `state_query kind='player'` with `permissions=["dogfood.shop"]` confirms the
bot is not allowed → `/shop` still opens → `bot_inspect` shows the menu. Comparing that result with
`plugin.yml` and the command source reveals the permission declaration was never enforced.

**What it probes:** whether a declared permission is discoverable as an effective runtime rule, and
whether a debugger checks the player state instead of trusting the manifest.
