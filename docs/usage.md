# Usage

Installation is in [../README.md](../README.md). This document picks up after it.

There are two ways to use this, and they need different things installed.

| | Needs | Can do |
|---|---|---|
| **Investigate only** | the agent plugin | ask a running server what happened |
| **Test as well** | + mcp-server + a runner | attach bots, make them act, verify the result |

The investigate-only side is the read-only default as shipped. It is the only one that belongs on a
production server.

---

# A. Investigating

## Always start with `server_info`

It returns the version, TPS, player count, installed plugins, and **capture status**. That last one
matters — a non-zero `eventsDropped` means the buffer overflowed, and every query after that is
reading data with holes in it.

The response also carries `latestEventCursor` / `latestLogCursor`. Start from those when you only
want "what happens from now on" and you will not re-read the past.

## Events — summary first, detail second

```
events_summary  →  events_query
```

**Do not reverse the order.** `events_summary` stays small however busy the server is, and it tells
you which types are worth looking at in detail. Skip it and call `events_query` first, and you spend
the response budget on events you do not care about.

| Tool | Parameters |
|---|---|
| `events_summary` | `from`, `to` (epoch ms, both optional) |
| `events_query` | `types[]`, `player`, `cursor`, `limit` |

**High-frequency events only appear when named in `types`.** `PlayerMoveEvent`,
`BlockPhysicsEvent`, `ChunkLoadEvent` and entity movement are the ones. They are not even captured
by default, so if you genuinely need them you also have to enable `capture-high-frequency` in
`config.yml`.

## Logs — found by pattern

```
logs_query(level="WARN", pattern="Timer|lag")
```

`level` is a **minimum** severity. `pattern` is a Java regular expression matched against the
message.

**There is no `logs_tail`.** "The last N lines" spends the whole budget on join messages on a busy
server. Search for what you are looking for.

## Exceptions — read the groups, then dig into one

```
exceptions_recent(limit=10)        # no stacks; occurrence count + first-seen time
exceptions_recent(hash="...")      # that one, with its full stack
```

The same exception ten thousand times is one line. That is why counting and reading are separated.

## Asking for the current state — `state_query`

Ask the server instead of inferring from events. **Confusing test results are almost always a
disagreement about state.**

```jsonc
{"kind": "player", "target": "Tester1", "permissions": ["essentials.fly"]}
{"kind": "block",  "world": "world", "x": 10, "y": 63, "z": 20}
```

A `kind="player"` response carries `name`, `uuid`, `online`, `address`, `gameMode`, `op`, `world`,
`x`/`y`/`z`, `permissions`.

- `uuid` — what permissions key on. A bot's is derived from its name, so it is the same every time
- `address` — the IP the server attributed to this connection. For a bot that is the injected value,
  which makes this **the only place to confirm the injection worked**
- `permissions` — answers only what you asked. Permissions cannot be enumerated, only tested

## Reading a menu GUI — `state_query kind="inventory"`

**This is the only way to read the contents of a menu a plugin opened.** Those items exist only in
the virtual inventory held by the open view — not in the player's NBT, not in an event payload.
`/data get entity` will not show them either.

```jsonc
{"kind": "inventory", "target": "Tester1"}                     // the open menu
{"kind": "inventory", "target": "Tester1", "which": "player"}  // their own inventory
```

```jsonc
{
  "view": "CHEST",          // CRAFTING / CREATIVE / PLAYER = no menu open
  "title": "§aShop",
  "size": 27,
  "occupiedSlots": 2,
  "items": [
    {"slot": 11, "material": "EMERALD", "amount": 1,
     "displayName": "§aBuy", "lore": ["§7Costs 10"], "enchanted": false,
     "customModelData": 1,
     "modelData": {"floats": [1.0], "flags": [], "strings": ["icon_a"], "colors": ["#FF8800"]}}
  ],
  "truncated": false
}
```

### CustomModelData — two forms

A resource-pack menu distinguishes its icons with this. Same material and same name can still be a
completely different button, so **checking only material and name misses icon bugs.**

| Field | What |
|---|---|
| `customModelData` | The integer form. What `setCustomModelData(1)` put there |
| `modelData` | The component added in 1.21.4 — `floats` / `flags` / `strings` / `colors` |

**They are two views of the same thing.** On 1.21.8, `setCustomModelData(1)` actually writes
`floats: [1.0]`.

**`customModelData` is lossy.** It truncates the component's first float to an integer, so `2.5`
arrives as `2` — meaning a `2.0` button and a `2.5` button are indistinguishable through it. And
**string keys are invisible to it entirely.** String keys are what modern packs mostly use, and the
integer view makes them look absent.

Check `customModelData` if you set an integer, and `modelDataString` if you use string keys:

```json
{"slot": 7, "material": "PAPER", "customModelData": 1}
{"slot": 8, "material": "PAPER", "modelDataString": "icon_a"}
```

- **Empty slots are omitted.** A 54-slot menu is mostly air, and listing it all only eats budget.
  `size` and `occupiedSlots` describe the whole thing, so "slot 22 is empty" is still knowable — if
  it is not in the list, it is empty
- **Colour codes are preserved in `§` form.** Whether a menu rendered correctly includes its
  colours. Ignore them if you want to; strip them and you cannot get them back
- A `view` of `CRAFTING` / `CREATIVE` / `PLAYER` **means no menu is open.** Creative shows
  `CREATIVE` — all three mean "their own screen"

## What the server cannot see — `bot_inspect`

`state_query` reads the **server-side** Bukkit inventory. But a plugin drawing its GUI with
ProtocolLib or packetevents leaves the server inventory empty and **sends item packets to the client
only.** Then:

```
state_query  →  occupiedSlots: 0   ← the server believes it is an empty chest
the real player →  a full menu      ← only the client received it
```

`bot_inspect` returns **what the bot's client was actually sent**:

```jsonc
{
  "menu": {"containerId": 1, "title": "Shop"},
  "items": [
    {"slot": 7, "itemId": 983, "amount": 1, "name": "Test",
     "customModelData": "1.0", "lore": "line one | line two"}
  ],
  "messages": ["multiplayer.player.joined"]
}
```

**Items come back as numeric ids.** The protocol does not carry names and MCProtocolLib has no
lookup table. Use `state_query` when you need material names — but only when the server really holds
that inventory. Name, lore and CustomModelData arrive as components, so both sides show them.

## What the server told the player — `messages`

**A plugin's refusal is usually one message and nothing else.** No exception, no console log, no
event. So from the agent's side alone, *"refused for lack of permission"* and *"silently did
nothing"* look identical.

Check `bot_inspect`'s `messages`, or use the scenario step:

```json
{"action": "assert_message", "bot": "Tester1", "contains": "permission"}
```

## Response budget

Every query tool has a cap (200 records / 50KB by default). The exact value is stated in each tool's
description. When output is cut, the response says so:

- `truncated: true` — cut for budget
- `nextCursor` — pass this as `cursor` to continue
- `dropped` — records **gone for good** from the ring buffer. Continuing will not recover these

---

# B. Testing with bots

## `session_start` — always first

```jsonc
{
  "host": "127.0.0.1",
  "port": 25565,          // Minecraft port
  "mcpPort": 25585,       // agent port
  "token": "auth-token from config.yml"
}
```

Omit `runnerJar` and it looks for a `bot-runner-*.jar` next to `mcp-server.jar`. With several, it
asks which one to use — picking a runner whose protocol does not match gets the bot rejected with
`Outdated client!`, a failure far enough from its cause that guessing is not worth it.

**For a server on another machine** the agent prints a block to paste, in its startup log:

```jsonc
{
  "host": "203.0.113.10", "mcpPort": 25585, "tls": "true",
  "token": "YLwNyFij...",
  "tlsFingerprint": "sha256:ffb61d8f...f163",   // when self-signed
  "port": 25565
}
```

`tlsFingerprint` pins trust to **that one certificate**. Nothing has to be installed on the client,
and it is not the same as turning verification off — it is narrower than CA verification (a CA
vouches for everything it signs; a fingerprint vouches for one certificate). Omit it for a real
certificate.

Call `server_info` once right here. If the host, port or token is wrong, this is where it says so,
instead of blowing up inside some unrelated tool later.

The response also carries `agentTools` — **the real parameters of the proxied tools.** mcp-server
publishes its tool list at startup, when no agent is attached yet, so it cannot state their
parameters there. Even *which* tools exist depends on the agent (read-only means no `command_exec`
at all). So the definitions come from the side that has the implementation.

When calling a proxied tool, **pass parameters flat, at the top level.** Do not wrap them:

```jsonc
{"kind": "player", "target": "Tester1"}                     // ✓
{"arguments": "{\"kind\":\"player\"}"}                       // ✗
```

`session_reset` disconnects every bot while keeping the connection. **Call it between independent
tests** or one inherits the previous test's players.

> It does not roll back world state. A scenario that depends on the world has to create that state
> itself.

## Driving it step by step

```
bot_spawn {"name": "Tester1"}
```

The name is the identity. The UUID derives from it, so `Tester1` is the same player today as
yesterday and permission-dependent behaviour reproduces. The response is the UUID and where it
landed.

Pass `clientIp` and the server records the connection as coming from that address. Use it only for
things **keyed on the address** — IP bans, per-IP connection limits, geo logic. Leave it out and the
real address is sent; a made-up address is a lie every later step has to carry.

After that, use the proxied agent tools directly. `wait_for`, `state_query` and `events_query` all
go to the server you called `session_start` on.

## All at once — `bot_run_scenario`

A scenario is a JSON array of steps. **It stops at the first failure** — running later steps from a
state the scenario never described makes those failures meaningless.

```json
[
  {"action": "spawn",        "bot": "Tester1"},
  {"action": "console",      "command": "op Tester1"},
  {"action": "assert_player","bot": "Tester1", "op": true},
  {"action": "break_block",  "bot": "Tester1", "x": 10, "y": 63, "z": 20},
  {"action": "wait_for",     "condition": "block_is", "x": 10, "y": 63, "z": 20,
                             "material": "AIR"},
  {"action": "assert_event", "eventType": "BlockBreakEvent", "player": "Tester1"}
]
```

### Step reference

| action | Required | Optional |
|---|---|---|
| `spawn` | `bot` | `clientIp` |
| `despawn` | `bot` | |
| `move_to` | `bot`, `x`, `y`, `z` | |
| `break_block` | `bot`, `x`, `y`, `z` | |
| `use_block` | `bot`, `x`, `y`, `z` | `face` (default `UP`). Right-click — opens chests and menus |
| `command` | `bot`, `command` | a command typed by the bot |
| `chat` | `bot`, `message` | |
| `console` | `command` | a command typed by the console (via `command_exec`) |
| `click_slot` | `bot`, `slot` | `click`: `left` (default) / `right` / `shift_left` / `shift_right` |
| `close_menu` | `bot` | |
| `wait_for` | `condition` | per-condition parameters, `timeoutMillis` |
| `assert_block` | `x`, `y`, `z`, `material` | `world` (default `"world"`) |
| `assert_player` | `bot` | `online`, `gameMode`, `op`, `timeoutMillis` |
| `assert_event` | `eventType` | `player`, `sinceSequence`, `timeoutMillis` |
| `assert_inventory` | `bot` | `title`, `size`, `which`, `slots[]` (below) |
| `assert_message` | `bot`, `contains` | whether what the server told that bot contains this string |

Entries in `assert_inventory`'s `slots[]`:

| Field | Meaning |
|---|---|
| `slot` | which slot to check (required) |
| `material` | expected item. Case-insensitive |
| `name` | whether the display name contains this string. **Compared with colour codes ignored**, so `"Buy"` matches `§aBuy` |
| `amount` | count |
| `lore` | whether this string appears somewhere in the lore |
| `customModelData` | integer CustomModelData |
| `modelDataString` | whether the component's `strings` contains this value (for string-key packs) |
| `empty` | `true` requires the slot to be empty |

A few things to know:

- **`console` calls `command_exec`.** With the agent on `read-only: true`, this step fails.
- **`assert_player` waits rather than reads.** Values like `op` change asynchronously — `/op` takes
  effect only after the name resolves to a UUID, so reading immediately races the previous command
  and fails for the wrong reason.
- **`assert_event`'s reference point is the start of the scenario.** Leave out `sinceSequence` and
  that is automatic. The thing you are verifying usually happened in the previous step, not after
  this one.
- The default wait is 15 seconds; the `wait_for` tool's own default is 10.

---

# B-2. Testing a menu GUI

The flow is: type a command, a menu opens, check it was drawn correctly.

```json
[
  {"action": "spawn",    "bot": "Tester1"},
  {"action": "command",  "bot": "Tester1", "command": "shop"},
  {"action": "wait_for", "condition": "inventory_open", "name": "Tester1", "title": "Shop"},
  {"action": "assert_inventory", "bot": "Tester1", "size": 27, "slots": [
      {"slot": 11, "material": "EMERALD", "name": "Buy",   "lore": "Costs 10"},
      {"slot": 15, "material": "BARRIER", "name": "Close", "amount": 3},
      {"slot": 13, "empty": true}
  ]},
  {"action": "click_slot", "bot": "Tester1", "slot": 11},
  {"action": "assert_event", "eventType": "InventoryClickEvent", "player": "Tester1"},
  {"action": "close_menu", "bot": "Tester1"}
]
```

**Do not leave out the `wait_for`.** A menu does not open synchronously with the command — the
plugin may take a tick, or wait on a database. Read immediately and you read the player's own
screen and report "the menu is empty", which has the same symptoms as a menu that failed to fill.

If the plugin **opens the menu first and fills it later**, `inventory_open` is not enough. Wait for
the button itself:

```json
{"action": "wait_for", "condition": "inventory_contains",
 "name": "Tester1", "material": "EMERALD", "slot": 11}
```

On failure you get what was actually there:

```
slot 11 expected DIAMOND but held EMERALD
slot 11 is empty, expected DIAMOND
expected the title to contain 'Shop' but it was '§cError'
no menu is open for Tester1 — the view is CREATIVE. If a command should have opened one,
wait_for inventory_open first.
```

## Opening a chest directly

To open a container GUI without a plugin, right-click it with `use_block`.

```json
{"action": "console",   "command": "setblock 10 64 20 chest"},
{"action": "use_block", "bot": "Tester1", "x": 10, "y": 64, "z": 20}
```

> **A chest will not open with an opaque block directly above it.** That is a game rule and not a
> problem with this harness, but the symptom looks like "the menu code is broken". Clear the block
> above to `air` first.

---

# C. `wait_for` — why not to sleep

**There is no `sleep` step, and there will not be one.** Have it and it will get used — it is the
shortest way past a timing problem — and every scenario that used it is calibrated to the machine of
whoever wrote it. Right on an idle server, wrong on a busy one. That is the entire mechanism by
which flaky tests are made.

Instead, **name the thing you are waiting for.** The agent checks every tick inside the server and
answers the moment it becomes true. One request, and nothing can slip through between two polls.

| condition | Parameters |
|---|---|
| `ticks` | `count` |
| `block_is` / `block_is_not` | `material`, `x`, `y`, `z`, `world` |
| `event` | `eventType`, `player`, `sinceSequence` |
| `player_online` / `player_offline` | `name` |
| `player_near` | `name`, `x`, `y`, `z`, `distance` |
| `player_state` | `name`, plus whichever of `online` / `gameMode` / `op` to check |
| `inventory_open` | `name`, `title` (substring, colour ignored) |
| `inventory_contains` | `name`, `material`, `slot`, `which` |

`timeoutMillis` defaults to 10000, capped at 60000.

**A timeout does not come back empty-handed.** A snapshot of the events and logs from that moment
rides along, and the reason is usually in there.

---

# D. Changing the server — `command_exec`

It has to be `read-only: false` in `config.yml` before it **appears in the tool list at all.** It is
not restricted; it is absent.

```jsonc
{"command": "give Tester1 diamond 1", "as": "Tester1"}   // omit `as` for the console
```

`dispatched` in the response means "a handler accepted it", not that it succeeded. **The real answer
is in `output`** — plenty of commands succeed formally while reporting failure in their output.

---

# E. Reading a failure

`bot_run_scenario` tells you which step died and why.

```jsonc
{
  "passed": false,
  "steps": [
    {"step": 1, "action": "spawn", "passed": true,  "detail": "Tester1 joined at ..."},
    {"step": 2, "action": "break_block", "passed": false,
     "detail": "...", "evidence": "events=[...] logs=[...]"}
  ]
}
```

`evidence` is the events and logs **from the moment of failure**. Ask separately later and the
server state has already moved on, so it is attached rather than left for another tool call.

When the cause is not visible there, dig in this order:

1. `state_query` — do the bot and the server agree about position, gamemode and permissions
2. `exceptions_recent` — is a plugin failing quietly
3. `server_info`'s `eventsDropped` — was it simply never seen

---

# F. Common mistakes

| Symptom | Cause |
|---|---|
| Bot connection refused with `did you forget to enable BungeeCord in spigot.yml?` | The server is not `online-mode=false` + `bungeecord: true` ([README](../README.md) §2) |
| `Outdated client!` | Wrong runner for the server's protocol. The number in the filename is a protocol number, not an MC version |
| Events are not captured | The type is on the high-frequency list. Name it in `types`, and enable `capture-high-frequency` if needed |
| `command_exec` is missing | `read-only: true` (the default). `session_start`'s `agentTools` lists the tools that actually exist |
| A proxied tool refuses with something like `... needs 'kind'` | Parameters were wrapped. Pass them flat, at the top level |
| `presented a certificate that no trusted authority signed` | Self-signed server. Pass the `tlsFingerprint` from the startup log |
| `did not present the pinned certificate` | The certificate was regenerated. Take the new fingerprint from the log |
| The menu reads empty | No `wait_for inventory_open`. Or check `view` — `CREATIVE`/`CRAFTING` means it never opened |
| The menu opened but `occupiedSlots: 0` | It may be a packet-drawn GUI. Use `bot_inspect` to see what the client received |
| A command silently does nothing | Check `bot_inspect`'s `messages` — the refusal is in there |
| A chest will not open | An opaque block sits directly above it (a game rule) |
| `click_slot` fails with `has no menu open` | Clicked before it opened. `wait_for inventory_open` first |
| The bot connected but nothing works | It has not landed. `bot_spawn` waits for that, but when driving manually the ground under it may still be air |
| It breaks from the second run onward | State from the previous run survived. Use `session_reset`, and if the scenario depends on the world, have it create that state |

---

Design rationale is in [design.md](design.md), remaining work in [roadmap.md](roadmap.md).
