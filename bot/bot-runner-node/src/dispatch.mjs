import * as actions from './actions.mjs';
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

      case protocol.BREAK: {
        actions.breakBlock(
          this.#bots.require(command[1]),
          command[1],
          Number(command[2]),
          Number(command[3]),
          Number(command[4]),
        );
        return ok(verb);
      }

      case protocol.COMMAND: {
        actions.command(this.#bots.require(command[1]), command[1], command[2]);
        return ok(verb);
      }

      case protocol.CHAT: {
        actions.chat(this.#bots.require(command[1]), command[1], command[2]);
        return ok(verb);
      }

      case protocol.USE: {
        actions.useBlock(
          this.#bots.require(command[1]),
          command[1],
          Number(command[2]),
          Number(command[3]),
          Number(command[4]),
          command.length > 5 ? command[5] : '',
        );
        return ok(verb);
      }

      case protocol.USE_ENTITY: {
        const radius = command.length > 5 && command[5].trim() !== '' ? Number(command[5]) : 2.0;
        const type = command.length > 6 ? command[6] : null;
        const entityId = actions.useEntity(
          this.#bots.require(command[1]),
          command[1],
          Number(command[2]),
          Number(command[3]),
          Number(command[4]),
          radius,
          type,
        );
        return protocol.encode(protocol.OK, verb, String(entityId));
      }

      case protocol.CLICK: {
        await actions.clickSlot(
          this.#bots.require(command[1]),
          command[1],
          Number(command[2]),
          command.length > 3 ? command[3] : 'left',
        );
        return ok(verb);
      }

      case protocol.CLOSE_MENU: {
        actions.closeMenu(this.#bots.require(command[1]), command[1]);
        return ok(verb);
      }

      case protocol.MENU: {
        const open = actions.menu(this.#bots.require(command[1]), command[1]);
        return protocol.encode(
          protocol.OK,
          verb,
          String(open === null ? -1 : open.containerId),
          open === null ? '' : protocol.sanitize(open.title),
        );
      }

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
