import { createServer } from 'node:http';
import { once } from 'node:events';
import { pathToFileURL } from 'node:url';
import { inspect } from './clientview.mjs';

const viewers = new Map();

/** Starts or reuses one localhost viewer for a bot. */
export async function view(bot, name, what = 'world', mode = 'third_person') {
  const existing = viewers.get(bot);
  if (existing) {
    return existing.url;
  }

  const port = await freePort();
  const selectedWhat = String(what || 'world').trim().toLowerCase();
  const selectedMode = String(mode || 'third_person').trim().toLowerCase();
  let close;

  if (selectedWhat === 'world') {
    close = await startWorldViewer(bot, port, selectedMode);
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

async function startWorldViewer(bot, port, mode) {
  const configuredPath = process.env.VITAMINMCP_VIEWER_PATH || 'prismarine-viewer';
  const packagePath = /^[A-Za-z]:[\\/]/.test(configuredPath)
    ? pathToFileURL(configuredPath).href
    : configuredPath;
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
  net.Server.prototype.listen = function listen(...args) {
    if (typeof args[0] === 'number') {
      args.splice(1, 0, '127.0.0.1');
    }
    return originalListen.apply(this, args);
  };
  try {
    // mineflayer can expose the negotiated registry version before it fills bot.version. The
    // viewer sends bot.version to the browser, where an empty value becomes "null is not
    // supported" instead of a useful viewer. Keep the runner's negotiated version as the source
    // of truth and populate the convenience field before prismarine-viewer connects.
    const version = bot.version
      ?? bot.registry?.version?.minecraftVersion
      ?? bot._client?.version;
    if (version != null && bot.version == null) {
      bot.version = version;
    }
    api.mineflayer(bot, {
      port,
      firstPerson: mode === 'first_person',
      viewDistance: 6,
    });
  } finally {
    net.Server.prototype.listen = originalListen;
  }
  if (typeof bot.viewer?.close !== 'function') {
    throw new Error('The optional prismarine-viewer did not expose a close hook.');
  }
  return () => bot.viewer.close();
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
