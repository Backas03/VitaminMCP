# Historical parity record

The Java runner used during Stage 5 has been retired in Stage 9. The entries below are retained as
the historical record of the parity gate and are not current runtime behavior.

# Where the Node runner did not match the retired Java runner

Stage 5 of `docs/mineflayer-roadmap.md` accepts or rejects the migration on this list, so it is
written as the differences are made rather than reconstructed at the end. Every entry needs a
reason that is not "mineflayer does it that way".

`test/parity.live.mjs` drives both runners through the same script and diffs their replies byte for
byte. Each entry below is allowed there by name, so a difference nobody decided on still fails.

## Deliberate

### Stage 6 verbs exist only in the Node runner

| | |
|---|---|
| Java | Returns `unknown command` for `attack_entity`, inventory/movement controls, placement and `assert_reachable` |
| Node | Implements the new verbs through mineflayer and pathfinder |

These are capabilities the Java runner never exposed, rather than replacements for an existing
contract. The Java runner remains selectable as the Stage 5 oracle, while new scenarios opt into
the Node implementation and the deletion of the old runner remains deferred to Stage 9.

### `bot_view` is Node-only and optional

The Java runner has no equivalent world viewer. Node starts the MIT-licensed prismarine-viewer
asset only when `bot_view` is called, binds it to `127.0.0.1`, and uses a small local inventory
page for `what: inventory`. This keeps the 269MB texture asset out of normal runner installs; the
optional asset path is supplied by the Stage 8 fetcher.

### `move` without an explicit mode

| | |
|---|---|
| Java | Sends one position packet and returns immediately; it is a teleport |
| Node | Walks to the destination with `mineflayer-pathfinder` and waits for arrival |

Stage 4 needs movement that can fire events along the route, including pressure plates. The Java
runner remains the stage-5 oracle and cannot simulate a route, so its legacy behaviour stays intact
while the Node runner gives `move_to` the new default. `mode: teleport` remains available for setup
steps that only need a bot at a coordinate.

### Movement failure categories

The Node runner reports `No path exists`, `Pathfinding timed out`, and `did not arrive ... within
<n>ms` separately. The Java runner ignores the optional mode and timeout fields because its move is
the legacy packet send. This distinction is necessary for a scenario author to know whether a
destination is sealed or merely too far for the chosen deadline; it is not a wrapper around an
opaque library error.

### `use` with an unknown face

| | |
|---|---|
| Java | `No enum constant org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction.SIDEWAYS` |
| Node | `Unknown face 'sideways'. Use down, up, north, south, west, east.` |

The Java text is not a message, it is an unhandled `IllegalArgumentException` from `Direction
.valueOf` reaching the caller intact. It names a class the caller cannot see and does not say what
a valid face would be. The scenario runner shows this string to whoever wrote the step, so it is
worth being a sentence.

### Vanilla translatable messages arrive as text, not as a translation key

| | |
|---|---|
| Java | `multiplayer.player.joined` |
| Node | `Tester1 joined the game` |

A component like the join message is *translatable*: the server sends a key and the arguments, and
the client looks the text up. Adventure's plain-text serializer has no translations registered, so
the Java runner reports the key. mineflayer bundles the language file and resolves it.

The resolved text is kept, because `assert_message` is written by a person, and nobody writes
`multiplayer.player.joined` when they mean "joined the game". A plugin's own messages are literal
components and read the same either way, so this only affects vanilla text.

**Left inconsistent on purpose, for now.** The same resolution is *not* applied to a menu title,
which still reports `container.chest` rather than `Chest`, because that half does match the Java
runner. Resolving it too would be better for `wait_for inventory_open`, whose title argument a
person also writes by hand — but it is a second contract change and belongs to a decision made
deliberately at stage 5, not to a fix made in passing at stage 3.

## Not differences, but worth knowing

### The join lockout is longer than the documentation says

Block interactions in the first seconds after a bot joins are dropped with **no event, no log line
and no refusal**, so a dig inside the window cannot be told apart from one the server rejected.
Measured on Paper 1.21.8 by digging at 1500 / 2500 / 3500 / 4500ms after spawn: the first two do
nothing, the last two break the block. The window is closer to three seconds than to the two the
README and the skill both quote.

This is not new in this runner — it is the server's behaviour and the Java runner has always had
it — but it cost an afternoon here, because the first symptom was "`break` returns ok and the block
is still there", which reads like a broken packet.

### Coordinates cannot be compared between runs

The server picks a spawn point inside a radius, so two bots do not stand in the same place and a
`spawn` or `position` reply cannot be diffed by value. The parity test compares the field count and
checks each coordinate is spelled the way `Double.toString` spells it, which is the part that has
actually been wrong.

### Tick timing was measured, not assumed

On Paper 1.21.8 at about 20 TPS, a Node path command reached its client-side goal and the next
server-side `wait_for player_near` matched after one observed tick. The same run produced three
server `PlayerInteractEvent` records with `action=PHYSICAL` while crossing the pressure-plate
fixture. No fixed wait was needed. The runner still uses a wall-clock arrival timeout because the
server cannot cancel a child process's local physics loop; the server predicate remains the final
assertion of where the player actually is.
