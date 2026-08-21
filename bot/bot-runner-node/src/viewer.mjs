import { createServer } from 'node:http';
import { once } from 'node:events';
import { pathToFileURL } from 'node:url';
import { inspect } from './clientview.mjs';

const viewers = new Map();

/** Starts or reuses one localhost viewer for a bot. */
export async function view(bot, name, what = 'world', mode = 'third_person', version) {
  const existing = viewers.get(bot);
  if (existing) {
    return existing.url;
  }

  const port = await freePort();
  const selectedWhat = String(what || 'world').trim().toLowerCase();
  const selectedMode = String(mode || 'third_person').trim().toLowerCase();
  let close;

  if (selectedWhat === 'world') {
    close = await startWorldViewer(bot, port, selectedMode, version);
  } else if (selectedWhat === 'inventory') {
    close = await startInventoryViewer(bot, name, port);
  } else {
    throw new Error(`Unknown view '${what}'. Use world or inventory.`);
  }

  const entry = { url: `http://127.0.0.1:${port}/`, close };
  viewers.set(bot, entry);
  return entry.url;
}

/** Stops the viewer for one bot, if one exists. */
export function stopView(bot) {
  const entry = viewers.get(bot);
  if (!entry) {
    return;
  }
  viewers.delete(bot);
  try {
    entry.close();
  } catch {
    // A bot that was already kicked may have closed its socket before the HTTP server did.
  }
}

/** Stops all viewers. */
export function stopAllViews() {
  for (const bot of [...viewers.keys()]) {
    stopView(bot);
  }
}

async function startWorldViewer(bot, port, mode, version) {
  const packagePath = viewerPackageSpecifier(process.env.VITAMINMCP_VIEWER_PATH);
  let viewerPackage;
  try {
    viewerPackage = await import(packagePath);
  } catch (error) {
    throw new Error(
      `World viewer is not installed. Set VITAMINMCP_VIEWER_PATH to the optional `
        + `prismarine-viewer asset: ${error?.message ?? error}`,
    );
  }

  const api = viewerPackage.default ?? viewerPackage;
  if (typeof api.mineflayer !== 'function') {
    throw new Error('The optional prismarine-viewer asset has no mineflayer viewer API.');
  }

  // prismarine-viewer 1.33.0 does not expose a host option and otherwise listens on all
  // interfaces. Its listen call is synchronous, so narrowing the one call is safe and keeps the
  // viewer from becoming an unauthenticated LAN service.
  const net = await import('node:net');
  const originalListen = net.Server.prototype.listen;
  let resolveListening;
  let rejectListening;
  const listening = new Promise((resolve, reject) => {
    resolveListening = resolve;
    rejectListening = reject;
  });
  net.Server.prototype.listen = function listen(...args) {
    if (typeof args[0] === 'number') {
      args.splice(1, 0, '127.0.0.1');
    }
    const callbackIndex = args.findLastIndex((argument) => typeof argument === 'function');
    const callback = callbackIndex >= 0 ? args[callbackIndex] : null;
    const onListening = function onListening(...callbackArgs) {
      // prismarine-viewer logs its port with console.log from this callback. stdout belongs to the
      // runner protocol, so even one such line makes the next Java-side read consume a log instead
      // of a reply. Preserve the diagnostic on stderr and do not return until the port is ready.
      const originalLog = console.log;
      console.log = (...messages) => process.stderr.write(`${messages.map(String).join(' ')}\n`);
      try {
        callback?.apply(this, callbackArgs);
      } finally {
        console.log = originalLog;
        resolveListening();
      }
    };
    if (callbackIndex >= 0) {
      args[callbackIndex] = onListening;
    } else {
      args.push(onListening);
    }
    this.once('error', rejectListening);
    return originalListen.apply(this, args);
  };
  try {
    const selectedVersion = resolveViewerVersion(version, api.supportedVersions);
    api.mineflayer(viewerBot(bot, selectedVersion), {
      port,
      firstPerson: mode === 'first_person',
      viewDistance: 6,
    });
    await listening;
  } finally {
    net.Server.prototype.listen = originalListen;
  }
  if (typeof bot.viewer?.close !== 'function') {
    throw new Error('The optional prismarine-viewer did not expose a close hook.');
  }
  return () => bot.viewer.close();
}

/** Resolves only the declared viewer sidecar, never an unrelated ancestor node_modules. */
export function viewerPackageSpecifier(configuredPath) {
  if (configuredPath && configuredPath.trim()) {
    return /^[A-Za-z]:[\\/]/.test(configuredPath)
      ? pathToFileURL(configuredPath).href
      : configuredPath;
  }
  return new URL(
    '../../bot-runner-viewer/node_modules/prismarine-viewer/index.js',
    import.meta.url,
  ).href;
}

/** Maps a server patch version to the newest viewer data from the same major release. */
export function resolveViewerVersion(negotiatedVersion, supportedVersions) {
  if (negotiatedVersion == null) {
    throw new Error('The bot runner did not provide a Minecraft version for the world viewer.');
  }
  const requested = String(negotiatedVersion);
  const supported = Array.isArray(supportedVersions) ? supportedVersions : [];
  if (supported.includes(requested)) {
    return requested;
  }
  const major = requested.split('.').slice(0, 2).join('.');
  const compatible = supported.filter(
    (candidate) => String(candidate).split('.').slice(0, 2).join('.') === major,
  );
  if (compatible.length === 0) {
    throw new Error(
      `World viewer does not support Minecraft ${requested}; supported versions: `
        + supported.join(', '),
    );
  }
  return compatible.at(-1);
}

/** Overrides only the version prismarine-viewer sees, preserving the live mineflayer bot. */
function viewerBot(bot, version) {
  return new Proxy(bot, {
    get(target, property) {
      if (property === 'version') {
        return version;
      }
      const value = Reflect.get(target, property, target);
      return typeof value === 'function' ? value.bind(target) : value;
    },
    set(target, property, value) {
      return Reflect.set(target, property, value, target);
    },
  });
}

async function startInventoryViewer(bot, name, port) {
  const server = createServer((request, response) => {
    if (request.url === '/state') {
      const current = inspect(bot, name);
      response.writeHead(200, {
        'content-type': 'application/json; charset=utf-8',
        'cache-control': 'no-store',
      });
      response.end(JSON.stringify(current));
      return;
    }
    response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
    response.end(INVENTORY_PAGE);
  });
  server.listen(port, '127.0.0.1');
  await once(server, 'listening');
  return () => server.close();
}

function freePort() {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const port = server.address().port;
      server.close(() => resolve(port));
    });
  });
}

const INVENTORY_PAGE = `<!doctype html>
<meta charset="utf-8"><title>VitaminMCP inventory view</title>
<style>body{font:16px system-ui;background:#202124;color:#eee;margin:2rem}table{border-collapse:collapse}td,th{border:1px solid #555;padding:.45rem}.empty{opacity:.45}</style>
<h1>VitaminMCP inventory</h1><p id="title"></p><table><thead><tr><th>Slot</th><th>Item</th><th>Amount</th><th>Name</th><th>Lore</th></tr></thead><tbody id="items"></tbody></table>
<script>
async function refresh(){const v=await fetch('/state').then(r=>r.json());document.querySelector('#title').textContent=v.menu?v.menu.title:'No menu open';document.querySelector('#items').innerHTML=v.items.map(i=>'<tr><td>'+i.slot+'</td><td>'+i.itemId+'</td><td>'+i.amount+'</td><td>'+i.name+'</td><td>'+i.lore+'</td></tr>').join('')||'<tr class="empty"><td colspan="5">No items received</td></tr>'} refresh();setInterval(refresh,500)
</script>`;
