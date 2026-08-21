# Where the Node runner does not match the Java runner

Stage 5 of `docs/mineflayer-roadmap.md` accepts or rejects the migration on this list, so it is
written as the differences are made rather than reconstructed at the end. Every entry needs a
reason that is not "mineflayer does it that way".

`test/parity.live.mjs` drives both runners through the same script and diffs their replies byte for
byte. Each entry below is allowed there by name, so a difference nobody decided on still fails.

## Deliberate

### `use` with an unknown face

| | |
|---|---|
| Java | `No enum constant org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction.SIDEWAYS` |
| Node | `Unknown face 'sideways'. Use down, up, north, south, west, east.` |

The Java text is not a message, it is an unhandled `IllegalArgumentException` from `Direction
.valueOf` reaching the caller intact. It names a class the caller cannot see and does not say what
a valid face would be. The scenario runner shows this string to whoever wrote the step, so it is
worth being a sentence.

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
