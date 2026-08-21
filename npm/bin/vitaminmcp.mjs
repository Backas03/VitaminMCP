#!/usr/bin/env node

import { spawn } from 'node:child_process';
import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

import { BOT_RUNNER_JAR, MCP_SERVER_JAR, ensureJar, jarPath } from '../lib/jars.mjs';
import { checkJava, findJava } from '../lib/java.mjs';

const HERE = path.dirname(fileURLToPath(import.meta.url));

/**
 * stdout is the MCP channel and carries nothing but JSON-RPC. Everything this launcher has to say
 * goes to stderr, where a client shows it as server output rather than trying to parse it.
 */
function say(message) {
  process.stderr.write(`[vitaminmcp] ${message}\n`);
}

function die(message) {
  process.stderr.write(`[vitaminmcp] ${message}\n`);
  process.exit(1);
}

async function version() {
  const manifest = await fs.readFile(path.join(HERE, '..', 'package.json'), 'utf8');
  return JSON.parse(manifest).version;
}

const HELP = `vitaminmcp — MCP server for testing Minecraft plugins

  Launched by an MCP client, not usually by hand. To connect it to Claude Code:

    claude mcp add vitaminmcp -- npx -y vitaminmcp

  The agent plugin still has to be installed on the Minecraft server itself. Once this server is
  connected, the /mcp__vitaminmcp__setup command walks through it.

Options
  --help        this text
  --version     the version this launcher will run
  --jars        download the jars and print where they are, without starting anything

Environment
  JAVA_HOME               the JDK to run the jars with; Java 21 or later
  VITAMINMCP_HOME         where jars and agent handshakes are kept (default ~/.vitaminmcp)
  VITAMINMCP_TOKEN        an agent token, for a server that leaves no local handshake
  VITAMINMCP_SERVER_JAR   run this mcp-server jar instead of a downloaded one
  VITAMINMCP_RUNNER_JAR   use this bot runner instead of a downloaded one
`;

async function main() {
  const argv = process.argv.slice(2);
  const release = await version();

  if (argv.includes('--help') || argv.includes('-h')) {
    process.stderr.write(HELP);
    return 0;
  }
  if (argv.includes('--version') || argv.includes('-v')) {
    process.stderr.write(`${release}\n`);
    return 0;
  }

  const java = findJava();
  const usable = checkJava(java);
  if (!usable.ok) {
    die(usable.message);
  }

  // The server jar is small and nothing works without it, so it is waited for. The runner is
  // ninety megabytes and only bots need it, so it is fetched alongside: a client that never
  // spawns a bot never waits for it, and one that does waits inside a tool call rather than
  // inside a startup timeout.
  let server = process.env.VITAMINMCP_SERVER_JAR;
  if (!server) {
    try {
      server = await ensureJar(release, MCP_SERVER_JAR, { log: say });
    } catch (error) {
      die(String(error.message ?? error));
    }
  }

  const runner = process.env.VITAMINMCP_RUNNER_JAR ?? jarPath(release, BOT_RUNNER_JAR);
  const runnerReady = process.env.VITAMINMCP_RUNNER_JAR
    ? Promise.resolve(runner)
    : ensureJar(release, BOT_RUNNER_JAR, { log: say }).catch((error) => {
        say(`the bot runner could not be downloaded: ${error.message ?? error}`);
        say('Everything except bots still works. Retry by restarting this server.');
      });

  if (argv.includes('--jars')) {
    await runnerReady;
    process.stderr.write(`${server}\n${runner}\n`);
    return 0;
  }

  return await run(java, server, runner);
}

/**
 * Runs the server jar, and lives exactly as long as it does.
 *
 * A download still in flight is abandoned when the server exits rather than held on to: the
 * `.part` file it leaves is claimed again by the next start, and a client waiting on a process
 * that no longer serves anything is worse than a jar fetched twice.
 */
function run(java, server, runner) {
  const child = spawn(java, ['-jar', server], {
    stdio: 'inherit',
    env: { ...process.env, VITAMINMCP_RUNNER_JAR: runner },
  });

  return new Promise((resolve) => {
    for (const signal of ['SIGINT', 'SIGTERM']) {
      process.on(signal, () => child.kill(signal));
    }

    child.on('error', (error) => {
      say(`could not start java: ${error.message}`);
      resolve(1);
    });

    child.on('exit', (code, signal) => {
      resolve(signal ? 1 : (code ?? 0));
    });
  });
}

main().then(
  (code) => process.exit(code),
  (error) => die(String(error?.stack ?? error)),
);
