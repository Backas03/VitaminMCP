import * as protocol from './protocol.mjs';

/**
 * The line protocol, in the one place it is spoken.
 *
 * A port of `RunnerDispatch.java`. Commands arrive one per line and are answered one per line, in
 * order — the Java side reads a single reply per command and would desynchronise permanently if
 * two overlapped, so this awaits each one rather than dispatching concurrently.
 */
export class Dispatch {
  #bots;

  constructor(bots) {
    this.#bots = bots;
  }

  /** Handles one command line. Returns the reply, or null for `shutdown`. */
  async handle(line) {
    const command = protocol.decode(line);
    if (command.length === 0 || command[0] === '') {
      return '';
    }
    if (command[0] === protocol.SHUTDOWN) {
      return null;
    }

    const verb = command[0];
    try {
      return await this.#run(verb, command);
    } catch (error) {
      return protocol.encode(protocol.ERROR, verb, String(error?.message ?? error));
    }
  }

  async #run(verb, command) {
    switch (verb) {
      case protocol.SPAWN: {
        const clientIp = command.length > 2 ? command[2] : '';
        return positionLine(verb, await this.#bots.spawn(command[1], clientIp));
      }

      case protocol.DESPAWN: {
        this.#bots.despawn(command[1]);
        return ok(verb);
      }

      case protocol.POSITION:
        return positionLine(verb, this.#bots.position(command[1]));

      default:
        return protocol.encode(protocol.ERROR, verb, `unknown command '${verb}'`);
    }
  }
}

function ok(verb) {
  return protocol.encode(protocol.OK, verb);
}

function positionLine(verb, at) {
  return protocol.encode(
    protocol.OK,
    verb,
    protocol.javaDouble(at.x),
    protocol.javaDouble(at.y),
    protocol.javaDouble(at.z),
  );
}
