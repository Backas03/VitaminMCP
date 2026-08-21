import assert from 'node:assert/strict';
import test from 'node:test';

import { offlineUuid } from '../src/identity.mjs';
import { decode, encode, fields, javaDouble, records, sanitize } from '../src/protocol.mjs';

/**
 * Every expectation here was produced by a JDK 21 run, not by reading the specification. The Java
 * runner is the other end of this protocol for as long as both exist, so "what Java prints" is the
 * only definition that matters.
 */

test('javaDouble spells doubles the way Double.toString does', () => {
  const fromJava = [
    [79.0, '79.0'],
    [-100.5, '-100.5'],
    [0.0, '0.0'],
    [-0.0, '-0.0'],
    [1.0, '1.0'],
    [0.5, '0.5'],
    [123.456, '123.456'],
    [64.0, '64.0'],
    [255.9375, '255.9375'],
    [100.0, '100.0'],
    [0.1, '0.1'],
    [1 / 3, '0.3333333333333333'],
    [1234567.875, '1234567.875'],
    [9999999.0, '9999999.0'],
    // The boundaries where Java switches to scientific notation, and the world border, which sits
    // the wrong side of the upper one.
    [1.0e-3, '0.001'],
    [1.0e-2, '0.01'],
    [9.99e-4, '9.99E-4'],
    [1.0e-4, '1.0E-4'],
    [2.5e-4, '2.5E-4'],
    [-1.0e-9, '-1.0E-9'],
    [1.0e7, '1.0E7'],
    [3.0e7, '3.0E7'],
    [-3.0e7, '-3.0E7'],
    [30000000.5, '3.00000005E7'],
    [1.0e21, '1.0E21'],
  ];

  for (const [value, expected] of fromJava) {
    assert.equal(javaDouble(value), expected, `javaDouble(${value})`);
  }
});

test('offlineUuid matches UUID.nameUUIDFromBytes', () => {
  const fromJava = {
    SpikeBot: 'fd30f2e7-89a9-309a-abc1-6fbacd8e0513',
    Tester1: '5b30590c-7361-31a2-a5a0-ee3cb0717365',
    a: '52428a0e-1e30-3cb1-976c-e728b2614047',
    ZZZZZZZZZZZZZZZZ: '51cf0f72-ddb1-3195-bff5-153f3d1ec8c5',
    한글봇: '20498745-f62b-3f5d-a516-b4a0396a46d9',
  };

  for (const [name, expected] of Object.entries(fromJava)) {
    assert.equal(offlineUuid(name), expected, name);
  }
});

test('encode and decode round-trip, keeping empty trailing fields', () => {
  assert.equal(encode('ok', 'menu', '-1', ''), 'ok\tmenu\t-1\t');
  assert.deepEqual(decode('ok\tmenu\t-1\t'), ['ok', 'menu', '-1', '']);
});

test('records and fields split on the control characters, not on anything visible', () => {
  const record = ['0', '1', '2'].join('');
  const field = [record, record].join('');

  assert.deepEqual(records(field), [record, record]);
  assert.deepEqual(fields(record), ['0', '1', '2']);
  assert.deepEqual(records(''), []);
});

test('sanitize strips every character the protocol gives meaning to', () => {
  assert.equal(sanitize('a\tb\nc\rd'), 'a b c d');
  assert.equal(sanitize(null), '');
});
