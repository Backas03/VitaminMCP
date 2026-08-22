# Scenarios

What a player reported, in the words a player would use. This file is safe to show the debugger —
it is all they get.

Each is one value of `scenario:` in the fixture's `config.yml`. Set one, restart the server, run a
round. What is actually wrong is in [`ANSWERS.md`](ANSWERS.md), which the debugger must not read.

The tools each scenario is *expected* to lean on are in ANSWERS.md too, not here. Naming them here
would hand over the diagnosis: "check the action bar" is most of the answer to one of these.

---

## `none`

> Nothing to report.

The control. A round against this should end with the debugger saying it found nothing wrong,
having checked. If it invents a fault, that is worth knowing about the tools too.

---

## `async-reply`

> `/top` does nothing. I run it and there is no leaderboard, no error, nothing. Other commands
> work fine.

---

## `silent-refusal`

> Normal players say `/shop` doesn't open. There's no error message, it just doesn't do anything.
> It works for me, but I'm an admin.

---

## `join-lockout`

> Sometimes when you first join you can't break blocks. You hit a block and it just doesn't
> break. If you wait a bit it starts working. No message, nothing in chat.

---

## `silent-listener`

> Most people get the starter kit when they join, but some people don't get anything. It's the
> same people every time. The server doesn't crash or anything.

---

## `disabled-feature`

> `/kit` doesn't work. It doesn't say anything at all, doesn't give the items, no error. It just
> silently does nothing.

---

## Worth planting, not yet planted

**A config key that is decoration.** The fixture had one by accident: `kit.enabled` sat in the
config and in a startup log line, and the code switched on something else entirely. A round
followed the tooling straight to the wrong fix, and said so — an operator would have flipped the
key, restarted, and been out of moves. Nothing in a runtime toolset can answer "is this key wired
to anything". It is fixed here, but as a deliberate scenario it is nastier and more realistic than
anything on the list above.

**A permission declared and never checked.** The control round volunteered this one: `dogfood.shop`
is declared `default: op` in plugin.yml and nothing enforces it outside one branch, so the command
opens for anyone. Left in place — it is realistic, and now recorded rather than accidental.
