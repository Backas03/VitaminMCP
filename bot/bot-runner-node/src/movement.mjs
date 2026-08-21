import pathfinderPackage from 'mineflayer-pathfinder';

const { pathfinder, Movements, goals } = pathfinderPackage;

/** A movement command is allowed to spend this long walking unless it says otherwise. */
export const DEFAULT_MOVE_TIMEOUT_MILLIS = 30_000;

/** Installs the pathfinder plugin before the bot joins the server. */
export function loadPathfinder(bot) {
  bot.loadPlugin(pathfinder);
}

/**
 * Configures movement as walking rather than construction.
 *
 * A test bot must not make a wall disappear merely because it was asked to walk to the other
 * side. Disabling digging, placing and parkour also makes a path mean what a normal player can
 * walk, which is the behaviour the movement scenarios are trying to observe.
 */
export function configurePathfinder(bot) {
  const movements = new Movements(bot);
  movements.canDig = false;
  movements.allow1by1towers = false;
  movements.allowParkour = false;
  movements.allowSprinting = false;
  bot.pathfinder.setMovements(movements);
}

/** Moves a bot by walking or by the legacy one-packet teleport. */
export async function moveTo(bot, name, x, y, z, mode = 'path', timeoutMillis) {
  requireInWorld(bot, name);
  requireCoordinate(x, 'x');
  requireCoordinate(y, 'y');
  requireCoordinate(z, 'z');

  const selected = mode == null || String(mode).trim() === ''
    ? 'path'
    : String(mode).trim().toLowerCase();
  if (selected !== 'path' && selected !== 'teleport') {
    throw new Error(`Unknown movement mode '${mode}'. Use path or teleport.`);
  }

  const limit = timeoutMillis == null || String(timeoutMillis).trim() === ''
    ? DEFAULT_MOVE_TIMEOUT_MILLIS
    : Number(timeoutMillis);
  if (!Number.isFinite(limit) || limit <= 0) {
    throw new Error(`Movement timeout must be a positive number of milliseconds, got '${timeoutMillis}'.`);
  }

  if (selected === 'teleport') {
    teleport(bot, x, y, z);
    return;
  }

  await walk(bot, name, x, y, z, limit);
}

/** Asks pathfinder for a route without changing the bot's goal or control state. */
export function reachable(bot, x, y, z, timeoutMillis = DEFAULT_MOVE_TIMEOUT_MILLIS) {
  const goal = new goals.GoalNear(x, y, z, 0.75);
  const result = bot.pathfinder.getPathTo(bot.pathfinder.movements, goal, Number(timeoutMillis));
  return {
    // A partial path is only the best frontier found before the search budget expired. It is not
    // evidence that the destination can be reached; sealed regions commonly return one.
    reachable: result.status === 'success',
    status: result.status,
  };
}

/** Sends the same position packet as the Java runner, while stopping any old path first. */
function teleport(bot, x, y, z) {
  // Calling stop with no active goal leaves pathfinder's stopPathing flag armed until its next
  // movement. That would make the next, unrelated path command report path_stop immediately.
  if (bot.pathfinder?.goal || bot.pathfinder?.isMoving?.()) {
    bot.pathfinder.stop();
  }
  bot.clearControlStates();

  // Mineflayer's physics loop keeps its own local entity position. If only the raw packet is
  // changed, the next physics tick sends the old position back and the server quite correctly
  // leaves the player where it was. Update the local state first and briefly suspend simulation
  // so the packet and the next client tick agree about the teleport.
  const physicsWasEnabled = bot.physicsEnabled;
  bot.physicsEnabled = false;
  if (bot.entity?.position) {
    bot.entity.position.x = x;
    bot.entity.position.y = y;
    bot.entity.position.z = z;
  }
  if (bot.entity?.velocity) {
    bot.entity.velocity.x = 0;
    bot.entity.velocity.y = 0;
    bot.entity.velocity.z = 0;
  }
  if (bot.entity) {
    bot.entity.onGround = true;
  }
  bot._client.write('position', {
    x,
    y,
    z,
    onGround: true,
    flags: { onGround: true, hasHorizontalCollision: false },
  });
  setTimeout(() => {
    if (bot._client?.socket?.writable) {
      bot.physicsEnabled = physicsWasEnabled;
    }
  }, 100);
}

/**
 * Walks to the target and reports path planning failures separately from arrival timeout.
 *
 * The pathfinder's convenience `goto` helper currently treats an empty no-path result as a
 * successful completion. Listening to the raw path_update result is intentional: the status is
 * the only reliable distinction between no route and a route that has not finished yet.
 */
function walk(bot, name, x, y, z, timeoutMillis) {
  return new Promise((resolve, reject) => {
    const goal = new goals.GoalNear(x, y, z, 0.75);
    let finished = false;

    const timer = setTimeout(() => {
      finish(new Error(
        `Bot ${name} did not arrive at ${x}, ${y}, ${z} within ${timeoutMillis}ms.`,
      ));
      bot.pathfinder.stop();
    }, timeoutMillis);

    const onGoalReached = () => finish();
    const onPathUpdate = (result) => {
      if (result?.status === 'noPath') {
        finish(new Error(`No path exists to ${x}, ${y}, ${z}.`));
        bot.pathfinder.setGoal(null);
      } else if (result?.status === 'timeout') {
        finish(new Error(
          `Pathfinding timed out before finding a route to ${x}, ${y}, ${z}.`,
        ));
        bot.pathfinder.setGoal(null);
      }
    };
    const onGoalChanged = (changed) => {
      if (changed !== goal) {
        finish(new Error(`The path to ${x}, ${y}, ${z} was replaced before arrival.`));
      }
    };
    const onPathStopped = () => finish(new Error(
      `The path to ${x}, ${y}, ${z} stopped before arrival.`,
    ));
    const onKicked = (reason) => finish(new Error(
      `Bot ${name} was kicked while walking: ${describe(reason)}`,
    ));
    const onEnd = () => finish(new Error(`Bot ${name} disconnected while walking.`));
    const onError = (error) => finish(new Error(
      `Bot ${name} failed while walking: ${error?.message ?? error}`,
    ));

    bot.on('goal_reached', onGoalReached);
    bot.on('path_update', onPathUpdate);
    bot.on('goal_updated', onGoalChanged);
    bot.on('path_stop', onPathStopped);
    bot.on('kicked', onKicked);
    bot.on('end', onEnd);
    bot.on('error', onError);

    // `goto` is deliberately not used; see the comment above walk(). The listeners are attached
    // before setGoal so an already-complete goal cannot win a race with this promise.
    bot.pathfinder.setGoal(goal);

    function finish(error) {
      if (finished) {
        return;
      }
      finished = true;
      clearTimeout(timer);
      bot.removeListener('goal_reached', onGoalReached);
      bot.removeListener('path_update', onPathUpdate);
      bot.removeListener('goal_updated', onGoalChanged);
      bot.removeListener('path_stop', onPathStopped);
      bot.removeListener('kicked', onKicked);
      bot.removeListener('end', onEnd);
      bot.removeListener('error', onError);
      if (error) {
        reject(error);
      } else {
        resolve();
      }
    }
  });
}

function requireInWorld(bot, name) {
  if (!bot.entity || !bot._client?.socket?.writable) {
    throw new Error(`Bot ${name} is not in the world`);
  }
}

function requireCoordinate(value, label) {
  if (!Number.isFinite(value)) {
    throw new Error(`Movement coordinate ${label} must be finite, got '${value}'.`);
  }
}

function describe(reason) {
  if (typeof reason === 'string') {
    return reason;
  }
  try {
    return JSON.stringify(reason);
  } catch {
    return String(reason);
  }
}
