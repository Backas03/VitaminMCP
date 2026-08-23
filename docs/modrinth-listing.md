# The Modrinth listing

The text of the Modrinth project page, kept here so it can be reviewed and changed like anything
else rather than living only in a web form. Update it when the thing it describes changes, and
paste it over the page.

**What Modrinth gets is the plugin jar and nothing else.** `mcp-server.jar` and the runner assets
are client-side and belong on the GitHub release and npm. That is exactly why the page opens by
saying so: someone who installs only this half and expects something to happen has been misled by
the listing, not by the software.

---

## Project settings

| Field | Value |
|---|---|
| Name | VitaminMCP |
| Slug | `vitaminmcp` |
| Project type | Plugin |
| Loaders | Paper, Purpur |
| Game versions | 1.21 through 1.21.11 |
| Environment | Server: **required** · Client: **unsupported** |
| License | MIT |
| Categories | Utility, Management |
| Source | `https://github.com/Backas03/VitaminMCP-minecraft` |
| Issues | `https://github.com/Backas03/VitaminMCP-minecraft/issues` |
| Wiki / docs | `https://github.com/Backas03/VitaminMCP-minecraft#readme` |

Loaders are Paper and Purpur because those are what the compatibility matrix actually starts.
Spigot and Bukkit are not claimed: the plugin may well load, but nobody has run it there, and a
listing is not the place to guess.

---

## Summary

> The server half of an AI-assisted plugin testing harness. Lets an agent read your server's logs,
> events and exceptions, run commands, and drive real protocol bots — so "does it actually work"
> gets answered by the server instead of by a hopeful build.

---

## Description

### This plugin is one half of a pair

VitaminMCP lets an AI coding agent test a Minecraft plugin against a **running server** instead of
guessing from source. This page carries the server half — the plugin that watches events, taps the
log, and answers questions over an authenticated endpoint. The other half runs on your machine,
next to your editor, and is installed with one command:

```
npx -y vitaminmcp
```

Installing only this jar does nothing on its own, the way a database with no client does nothing.
Install both.

### What it answers

A build passing tells you the code compiled. It tells you nothing about whether the server booted,
whether the GUI opened, or whether the permission you declared is the one being checked. This
closes that gap:

- **The log, searchable** — by severity and regular expression, including the stack traces that
  scrolled past while you were reading something else.
- **Events and exceptions** — what actually fired, filtered by type and player, paged by cursor.
- **Server state** — a plugin's live config, the commands it declares, the permission node gating
  each one, and whether a given player actually has it. Regularly not what the config file in your
  repository says.
- **Real bots over the real protocol** — not fake players. They log in, receive packets, open your
  GUI, click slot 13, and report what the client was sent, down to the millisecond each message
  arrived. If a menu does not open for a player, this is what shows you that.
- **Whole scenarios** — a list of steps run in order, stopping at the first failure with the
  server's state at that moment attached.

### What it does not do

Nothing about gameplay. It adds no commands for players, no items, no recipes, and no behaviour a
player can notice. If you are looking for something to install on a server people play on, this is
not that — though it is safe there, which is the next section.

### The defaults assume your server matters

- **Read-only is the default.** State-changing tools are not exposed at all, so a default install
  cannot alter the server even with a valid token. You turn that off deliberately.
- **The endpoint never opens unauthenticated.** Leave `auth-token` empty and the plugin generates
  one, writes it to `config.yml`, and carries on. If it cannot write one it refuses to start rather
  than opening anyway.
- **Loopback by default, and moving off it makes TLS mandatory.** The token grants console access,
  and over plain HTTP it crosses the network in the clear. Binding to a real interface without TLS
  is a refusal to start, not a warning.
- **Nothing is copied by hand.** A client on the same machine reads the connection details from a
  handshake file the agent writes while it runs, readable only by the user the server runs as.

### Installing

1. Drop `VitaminMCP.jar` into `plugins/` and start the server. No server flags, no java agent, no
   dependencies. It prints the endpoint and the token it generated.
2. On your own machine, add the client half to your MCP client — for Claude Code that is
   `claude mcp add vitaminmcp -- npx -y vitaminmcp`.

That is the whole install. Everything else is documented in `config.yml`, next to the reason each
default is what it is.

**Bots are optional and need one server setting.** They log in for real, so the test server has to
be in offline mode (`online-mode=false`). That is a test-harness configuration and an offline-mode
server must never be reachable from the internet. Skip it entirely if you only want the log,
events, state and exceptions.

### Requirements

| | |
|---|---|
| Server | Paper or Purpur, 1.21 – 1.21.11 |
| Java | 21 |
| Your machine | Node 18.17+ for `npx`, and Java 21 to run the client half |

Versions above 1.21.11 are not listed yet. Each one is added only after a full compatibility run
against a real server of that version, because claiming support without having started it is how
plugins get one-star reviews.

---

## Notes for whoever updates this

- The version range comes from `SupportedVersions.FLOOR` and `versions.yaml`. If the matrix gains a
  version, this page and the Modrinth version list both need it.
- Modrinth reviews a new project before it is publicly listed. Nothing here can shorten that.
- Uploading a new version needs a token with `Create versions` scope, kept as a repository secret
  and never in the repository.
