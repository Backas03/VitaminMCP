# Dogfooding harness

[roadmap.md](../docs/roadmap.md) ends Stage 1 with an instruction that was never carried out:

> At the end of Stage 1 **this is already usable as a standalone product.** Use it for real once
> here, refine the tool schemas, then go to Stage 2.

Every tool in this project has been driven by a test that already knew the answer. This is the
apparatus for the other thing: someone who does **not** know the answer, trying to find one, and
being watched while they fail to.

## What is being measured

**Not whether the bug gets found.** A capable agent will get there eventually by reading the
plugin's source, and a round where it does that proves nothing.

What is being measured is **friction**: the question the debugger had that no tool would answer,
the tool it reached for first and was wrong to, the description that pointed it the wrong way, the
output it had to squint at. Each of those is a defect in the tool surface, and the tool surface is
what this project ships.

A round that ends "found it in four calls, no complaints" is a finding too — it says that path is
in good shape.

## The pieces

| | |
|---|---|
| [`fixture-plugin/`](fixture-plugin) | an ordinary-looking Paper plugin with exactly one thing wrong with it, chosen by `scenario` in its config |
| [`SCENARIOS.md`](SCENARIOS.md) | what a player reported. **Safe to show the debugger** — it is all they get |
| [`ANSWERS.md`](ANSWERS.md) | what is actually wrong, and the path a healthy tool surface would make obvious. **Withheld from the debugger** |
| [`JOURNAL.md`](JOURNAL.md) | what each round cost, and which schema change it bought |

The fixture depends on nothing of ours. It is a plugin like any other plugin someone would point
VitaminMCP at, and giving it a privileged hook would make the exercise easier than the real thing.

## Running a round

**1. Pick a scenario and put it on the server.**

```bash
./gradlew :dogfood:jar
```

Copy `dogfood/fixture-plugin/build/libs/DogfoodFixture.jar` into the scratch server's `plugins/`,
set `scenario:` in `plugins/DogfoodFixture/config.yml`, and restart the server. The scenario is
read once at enable, deliberately: a plugin that re-reads its config on demand is a plugin with a
debug backdoor, and no real one has that.

The checked-in `config.yml` is the shipped default and says nothing about what a given server is
running. The plugin logs the scenario it enabled, so a round that wants to know should ask the
server:

```
logs_query(pattern="DogfoodFixture enabled")
```

**2. Give the debugger the symptom and nothing else.**

The launch prompt is in [`ROUND.md`](ROUND.md). It names one scenario's symptom, points at the
fixture source, and says what to report back. Run it as a subagent so its context starts empty —
an agent that watched the scenario being set up is not blind.

**3. Write the round up in `JOURNAL.md`** — the friction, not the diagnosis.

**4. Change something.** A round that produces no change to a tool description, schema, or
document was a round spent for nothing. If the friction turns out to be unfixable, write down why;
that is also a result.

## Rules that keep a round honest

- **The debugger never reads `ANSWERS.md`.** Nothing enforces this but the prompt and whoever
  reads the transcript afterwards.
- **The debugger may read the fixture's source.** A real developer has their own source. Hiding it
  would make this a black-box puzzle and flatter the tools, since every question would have to go
  through them.
- **One scenario per round.** With four faults live at once the debugger finds the loudest and
  stops, and the quiet ones — which are the interesting ones — are never reached.
- **Do not fix the friction during the round.** Finish it, write it down, then decide. Changing a
  tool description halfway through means the round measured two different products.
