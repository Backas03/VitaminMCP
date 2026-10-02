import assert from 'node:assert/strict';
import test from 'node:test';

import { javaHome, mcpServerArgs } from '../lib/java.mjs';

test('leaves the stdio server on the JVM defaults', () => {
  assert.deepEqual(mcpServerArgs('server.jar'), ['-jar', 'server.jar']);
});

test('bounds shared server memory and forwards the selected transport', () => {
  assert.deepEqual(mcpServerArgs('server.jar', ['--http', '25584']), [
    '-Xms16m',
    '-Xmx128m',
    '-XX:+UseSerialGC',
    '-jar',
    'server.jar',
    '--http',
    '25584',
  ]);
});

test('reads the Java home from the settings the JVM prints', () => {
  const settings = [
    'Property settings:',
    '    java.class.version = 65.0',
    '    java.home = C:\\Users\\tester\\.jdks\\temurin 21',
    '    java.io.tmpdir = C:\\Temp\\',
    'openjdk version "21.0.4" 2024-07-16 LTS',
  ].join('\r\n');

  assert.equal(
    javaHome('java.exe', () => ({ stderr: settings, stdout: '' })),
    'C:\\Users\\tester\\.jdks\\temurin 21',
  );
  assert.equal(javaHome('java.exe', () => ({ stderr: 'not a jvm', stdout: '' })), null);
});
