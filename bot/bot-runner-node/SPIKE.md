# Spike: can a mineflayer bot carry an injected identity?

**Result: yes, on both counts.** Run 2026-08-22 against the scratch Paper 1.21.8 backend
(`online-mode=false`, `settings.bungeecord: true`).

`design.md` §3.1 has bots imitate a BungeeCord forwarding handshake, writing
`<host>\0<clientIP>\0<uuid>\0<properties>` into the handshake's server address field. That is
non-standard, so whether `minecraft-protocol` would let it be written verbatim was the one
unknown that could have ended the migration before it started.

It does: `createBot({ fakeHost })` replaces the address field wholesale
(`minecraft-protocol/src/client/setProtocol.js`), NUL separators included.

## What the server recorded

```
UUID of player SpikeBot is fd30f2e7-89a9-309a-abc1-6fbacd8e0513
SpikeBot[/203.0.113.7:62949] logged in with entity id 126 at ([world]-100.5, 79.0, -109.5)
```

Both claims landed. The UUID is the one derived from the name, and the address is the TEST-NET-3
one the spike asked for — the socket's real address was `127.0.0.1`. So `clientIp`, which rides on
the same mechanism, works too.

**Read that from the server, not from the bot.** `bot.player.uuid` only says the client believes
its own claim, which it would whether or not the server accepted it.

## The identity has to agree with Java byte for byte

`offlineUuid` is a version 3 (MD5) name-based UUID, matching `UUID.nameUUIDFromBytes` — the
version and variant nibbles are stamped by hand in `src/identity.mjs` because Node has no
equivalent. Verified against a JDK 21 run for `SpikeBot`, `Tester1`, `a`, a 16-character name and
a non-ASCII name; all five agree.

This is not cosmetic. The whole reason the UUID is derived from the name is that the same name is
the same player every run, so permission and LuckPerms tests stay reproducible. A runner that
computed it differently would silently hand every existing scenario a new player.

## Reproducing

```bash
node spike/join.mjs 127.0.0.1 25565 SpikeBot 203.0.113.7
```

Then read the server's log or ask the agent — the bot's own view proves nothing.

## Still unproven

Ranked by how much they can still hurt:

1. **Tick determinism.** mineflayer runs its own physics timer, against `design.md` §12.
2. **`ClientView` fidelity.** `MenuItem`, `Scoreboard` and the message list have to come back in a
   shape the MCP tools already promise, or every existing scenario's assertions move.
3. **Velocity modern forwarding.** §3.1 says a test environment holds the HMAC secret, so the
   same approach should work, but this spike only covers the BungeeCord form.
