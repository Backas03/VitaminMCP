#!/usr/bin/env node
/**
 * The bot runner, on mineflayer.
 *
 * Speaks the VitaminMCP stdio line protocol and is launched as the runner child process:
 *
 *   node runner.mjs <host> <port> [protocol]
 *
 * Without a protocol it pings the server. VITAMINMCP_SERVER_VERSION, when set, is the Minecraft
 * version the agent reports, and it wins over a ping that disagrees (see `selectProtocol`). It is
 * an environment variable rather than an argument so a runner and an MCP server of different
 * releases still understand each other's command line.
 *
 * Everything it prints on stdout is protocol. Diagnostics go to stderr, which the MCP server
 * inherits — a stray `console.log` here is a desynchronised session, not a stray log line.
 */
import { createInterface } from 'node:readline';

import { BotRegistry } from './src/bots.mjs';
import { Dispatch } from './src/dispatch.mjs';
import * as protocol from './src/protocol.mjs';
import { pingProtocol, selectProtocol, versionForProtocol } from './src/version.mjs';

// stdout is the protocol channel; a dependency's console.log (mineflayer did this on 26.1, with a
// stack trace) desynchronises every later reply. `write` below is the only path to stdout.
for (const level of ['log', 'info', 'debug']) {
  console[level] = (...messages) => process.stderr.write(`${messages.map(String).join(' ')}\n`);
}

async function main() {
  const argv = process.argv.slice(2);
  if (argv.length < 2) {
    process.stderr.write('usage: runner.mjs <host> <port> [protocol]\n');
    process.exit(2);
  }

  const host = argv[0];
  const port = Number(argv[1]);

  const serverVersion = process.env.VITAMINMCP_SERVER_VERSION || undefined;

  let bots;
  let negotiated;
  let pinged;
  try {
    if (argv.length > 2) {
      negotiated = Number(argv[2]);
    } else {
      ({ protocol: negotiated, pinged } = selectProtocol(await pingProtocol(host, port), serverVersion));
    }
    if (pinged !== undefined) {
      process.stderr.write(
        `The server-list ping answered protocol ${pinged}, but the agent reports Minecraft `
          + `${serverVersion} (protocol ${negotiated}). Something in front of the server answered `
          + `the ping, a proxy or ViaVersion; using ${negotiated}.\n`,
      );
    }
    bots = new BotRegistry(host, port, versionForProtocol(negotiated));
  } catch (error) {
    // The Java side reads this line and reports it as the reason the runner did not start, so it
    // has to arrive on stdout in protocol form rather than as a stack trace on stderr.
    write(protocol.encode(protocol.ERROR, 'startup', `${error?.name ?? 'Error'}: ${error?.message ?? error}`));
    process.stderr.write(`${error?.stack ?? error}\n`);
    process.exit(1);
  }

  // A third field only when the ping disagreed, so the line a caller already parses is unchanged.
  write(pinged === undefined
    ? protocol.encode(protocol.READY, String(negotiated))
    : protocol.encode(protocol.READY, String(negotiated), String(pinged)));

  const dispatch = new Dispatch(bots);
  const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });

  for await (const line of lines) {
    const reply = await dispatch.handle(line);
    if (reply === null) {
      break;
    }
    if (reply !== '') {
      write(reply);
    }
  }

  bots.shutdown();
  process.exit(0);
}

main().catch((error) => {
  process.stderr.write(`${error?.stack ?? error}\n`);
  process.exit(1);
});

function write(line) {
  process.stdout.write(`${line}\n`);
}
