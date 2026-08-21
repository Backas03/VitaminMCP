# Publishing

Three things are published per release, in this order, and **none of them can be unpublished**:

1. the **GitHub release** — the three jars
2. the **npm package** `vitaminmcp` — the launcher, pinning the sha256 of those jars
3. the **MCP registry** entry — `server.json`, pointing at that npm version

The order is forced. A package cannot pin bytes that are not downloadable yet, and the registry
rejects a `server.json` naming an npm package that does not exist or does not claim this server
name. Why it is built this way at all is [design.md §16](design.md).

[`.github/workflows/release.yml`](../.github/workflows/release.yml) does all three when a version
tag is pushed. What follows is the one-time setup it needs, and the first release, which is worth
doing by hand.

---

## One-time setup

### npm

The package name `vitaminmcp` is claimed by whoever publishes it first, so publish before
announcing anything.

- An npm account, and `npm login` locally for the first release
- For CI: an **automation** access token from npmjs.com (Access Tokens → Generate → Automation),
  stored as the repository secret **`NPM_TOKEN`**. Granular tokens work too, scoped to this
  package with read/write

The workflow publishes with `--provenance`, which links the tarball to the workflow run that built
it. That needs `id-token: write`, which the workflow already declares.

### MCP registry

Nothing to register in advance. The namespace `io.github.backas03/*` is proved by GitHub
authentication — interactively with `mcp-publisher login github`, and from CI with
`mcp-publisher login github-oidc`, which is why the workflow needs `id-token: write` for that too.

The name in [`server.json`](../server.json) is **lowercase**: `io.github.backas03/vitaminmcp`. It
has to match the GitHub account the login proves, and lowercase is the form the registry stores.

### npm ownership of the name

The registry checks that `npm/package.json` carries

```json
"mcpName": "io.github.backas03/vitaminmcp"
```

which is what stops someone else's package from claiming this server name. Do not remove it.

---

## Cutting a release

### 1. Bump the version

The version lives in **one** place —
[`build-logic/src/main/kotlin/vitaminmcp.java-conventions.gradle.kts`](../build-logic/src/main/kotlin/vitaminmcp.java-conventions.gradle.kts).
Everything else copies it:

```bash
node npm/scripts/stamp-checksums.mjs --sync
```

That writes the version into `npm/package.json` and `server.json` (both places it appears there).
Commit all three files together. The release workflow refuses to build if any of them disagrees
with the tag, which is deliberate: a mismatch has to fail in the repository, not halfway through
publishing.

### 2. Tag it

```bash
git tag 1.5.0
git push origin 1.5.0
```

The workflow builds `dist`, creates the release with the three jars, stamps the checksums from the
jars it just built, publishes to npm, and publishes `server.json`. Watch it — the first two steps
are irreversible before the third runs.

---

## The first release, by hand

Worth doing once rather than debugging the workflow against an unpublished name.

**Creating a release with `gh` pushes the tag, so the workflow runs too.** That is fine: each of
its three publish steps checks whether it has already happened and carries on rather than failing,
so whichever of you gets there second does nothing. The same guard makes a rerun safe after a
failure halfway down, which matters because nothing above the failure can be undone.

From a clean checkout at the commit you want released:

```bash
./gradlew dist
```

```bash
gh release create 1.5.0 --title 1.5.0 --generate-notes \
  build/dist/VitaminMCP.jar build/dist/mcp-server.jar build/dist/bot-runner.jar
```

```bash
cd npm && node scripts/stamp-checksums.mjs --dist ../build/dist && npm publish --access public
```

Then install the publisher and claim the name. On Windows:

```powershell
Invoke-WebRequest -Uri "https://github.com/modelcontextprotocol/registry/releases/latest/download/mcp-publisher_windows_amd64.tar.gz" -OutFile mcp-publisher.tar.gz; tar xf mcp-publisher.tar.gz mcp-publisher.exe
```

```bash
mcp-publisher login github
```

```bash
mcp-publisher publish
```

`login github` opens a browser. `publish` reads `server.json` from the working directory; add
`--dry-run` first to validate without publishing.

Check it landed:

```bash
curl -s "https://registry.modelcontextprotocol.io/v0/servers?search=vitaminmcp"
```

---

## Checking the launcher without publishing

`npm/` can be exercised against a release that already exists, or against local jars:

```bash
node npm/bin/vitaminmcp.mjs --jars
```

Downloads what this version pins and prints where the jars went, without starting a server.
`VITAMINMCP_SERVER_JAR` and `VITAMINMCP_RUNNER_JAR` point it at jars you built instead, which is
how to run the launcher against unreleased changes:

```bash
VITAMINMCP_SERVER_JAR=$PWD/build/dist/mcp-server.jar \
VITAMINMCP_RUNNER_JAR=$PWD/build/dist/bot-runner.jar \
  node npm/bin/vitaminmcp.mjs
```

`npm pack --dry-run` lists exactly what would be published. It should be six files and nothing
else — no jars.

---

## What a user gets

| | |
|---|---|
| `claude mcp add vitaminmcp -- npx -y vitaminmcp` | from npm |
| the server's entry in a client's MCP catalogue | from the registry |
| `/mcp__vitaminmcp__setup` | from the server itself, once connected |
| `VitaminMCP.jar` for the Minecraft server | from the GitHub release, by hand or by that prompt |

The plugin stays a manual install. It goes on a machine none of the above can reach.
