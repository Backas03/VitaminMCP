import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import test from 'node:test';

import { runServiceAction, serviceAction } from '../lib/service.mjs';

test('recognises only complete service commands', () => {
  assert.equal(serviceAction(['service', 'install']), 'install');
  assert.equal(serviceAction(['--http', '25584']), null);
  assert.throws(() => serviceAction(['service']), /install, uninstall, or status/);
  assert.throws(() => serviceAction(['service', 'restart']), /install, uninstall, or status/);
});

test('passes stable runtime paths to the Windows installer', async () => {
  let invocation;
  const child = new EventEmitter();
  const result = runServiceAction('install', '3.1.1', {
    platform: 'win32',
    node: 'C:\\Node\\node.exe',
    java: 'java.exe',
    javaHome: 'C:\\Java\\jdk-21',
    npmCli: 'C:\\Node\\npm-cli.js',
    packageRoot: 'C:\\pkg',
    home: 'C:\\Users\\tester\\.vitaminmcp',
    spawnProcess: (command, args, options) => {
      invocation = { command, args, options };
      queueMicrotask(() => child.emit('exit', 0, null));
      return child;
    },
  });

  assert.equal(await result, 0);
  assert.equal(invocation.command, 'powershell.exe');
  assert.equal(invocation.options.stdio, 'inherit');
  assert.ok(invocation.args.includes('C:\\pkg'));
  assert.ok(invocation.args.includes('C:\\Users\\tester\\.vitaminmcp'));
  assert.equal(invocation.args[invocation.args.indexOf('-JavaHome') + 1], 'C:\\Java\\jdk-21');
});

test('refuses to install a service without a Java home', () => {
  assert.throws(
    () => runServiceAction('install', '3.1.1', {
      platform: 'win32',
      node: 'C:\\Node\\node.exe',
      java: 'java.exe',
      javaHome: null,
      npmCli: 'C:\\Node\\npm-cli.js',
      spawnProcess: () => assert.fail('the installer must not start'),
    }),
    /Java home/,
  );
});
