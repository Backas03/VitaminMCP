# The launch prompt

Run this as a **subagent**, so its context starts empty. An agent that watched the scenario being
set up is not blind, and there is no way to un-see it.

Substitute the symptom from [`SCENARIOS.md`](SCENARIOS.md) and nothing else from this directory.

---

```
A player on our Paper server reported this:

  <the symptom, verbatim from SCENARIOS.md>

The plugin is DogfoodFixture. Its source is at dogfood/fixture-plugin/ in this repository and you
may read it.

The server is running with VitaminMCP, so you have tools that can watch it: use session_start
first, then whatever you need — bots, events, logs, exceptions, commands, server state.

Find out what is actually happening. Then report:

  1. The cause, in a sentence.
  2. The calls you made, in order, and what each told you.
  3. Where you got stuck: a question you had that no tool would answer, a tool you reached for
     that turned out to be the wrong one, a description that pointed you somewhere unhelpful, or
     output you had to work to read. Be specific and be blunt — this is the part that matters, and
     "it was fine" is a useful answer when it is true.

Do not read dogfood/ANSWERS.md. It has the answer in it and reading it wastes the round.
```

---

## Reading the result back

The diagnosis is the least interesting part. What to pull out for [`JOURNAL.md`](JOURNAL.md):

- **The first tool it reached for.** If that is the wrong one, the descriptions are steering badly
  — the first call is made almost entirely on the tool list.
- **Calls that told it nothing.** A tool that returns an empty result where the answer was
  available somewhere else is a routing failure, not a user error.
- **Anything it had to guess.** A regex it had to invent, a field it had to know existed, a
  convention it could only have learned from the source.
- **Where it stopped believing a tool.** If it checked something twice by different means, the
  first means did not convince it.
- **What it never tried.** Sometimes the right tool exists and the description never sold it.
