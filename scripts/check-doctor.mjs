import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

const jar = resolve(process.argv[2] ?? 'target/selenium-mcp-0.6.0.jar');
assert.match(jar, / /, 'Place the test JAR in an absolute path containing spaces');
const flags = process.argv.slice(3);
const unsupported = flags.includes('--unsupported-runtime');
const saved = flags.includes('--saved-fixture');
const env = {...process.env};
for (const key of ['SELENIUM_ALLOWED_ORIGINS', 'SELENIUM_API_ALLOWED_ORIGINS', 'SELENIUM_API_ALLOW_LOOPBACK',
  'SELENIUM_API_ENVIRONMENTS_FILE', 'SELENIUM_SAVED_COLLECTION_DIR', 'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS']) {
  delete env[key];
}
const canary = 'doctor-fixture-secret';
env.DOCTOR_TEST_TOKEN = canary;
function run(expected, overrides = {}, extra = [], vmArgs = []) {
  const result = spawnSync('java', [...vmArgs, '-jar', jar, '--doctor', ...extra], {
    env: {...env, ...overrides}, encoding: 'utf8', timeout: 15000, windowsHide: true,
  });
  assert.ifError(result.error);
  assert.equal(result.status, expected, result.stderr);
  assert.equal(result.stdout, '');
  assert.doesNotMatch(result.stderr, new RegExp(canary));
  return result.stderr;
}

const empty = run(0);
assert.match(empty, /PASS Java 21/);
assert.match(empty, unsupported ? /WARN API tools require Java/ : /PASS API runtime/);
assert.match(empty, /SKIP Saved environment/);
assert.match(run(0, {}, [], ['-Djava.vendor=' + canary]), /PASS Java 21/);
assert.match(run(1, {SELENIUM_API_ALLOWED_ORIGINS: 'https://127.0.0.1/'+canary}),
  /FAIL API origin list/);
assert.match(run(1, {SELENIUM_ALLOWED_ORIGINS: canary}), /FAIL Browser origin list/);
assert.match(run(1, {SELENIUM_API_ENVIRONMENTS_FILE: canary}), /FAIL Saved environment/);
assert.match(run(2, {}, ['extra']), /FAIL Usage/);
if (saved) {
  const config = process.env.SELENIUM_API_ENVIRONMENTS_FILE;
  const root = process.env.SELENIUM_SAVED_COLLECTION_DIR;
  assert.ok(config && root);
  const snapshots = () => new Map(readdirSync(root).map(name => [name, readFileSync(join(root, name))]));
  const before = snapshots();
  const bytes = readFileSync(config);
  const output = run(0, {SELENIUM_API_ENVIRONMENTS_FILE: config, SELENIUM_SAVED_COLLECTION_DIR: root});
  assert.match(output, /PASS Saved environment/);
  assert.ok(!output.includes(config) && !output.includes(root));
  assert.deepEqual(readFileSync(config), bytes);
  assert.deepEqual(snapshots(), before);
}
console.log('Verified doctor exits, redaction, configuration isolation and empty stdout from a path with spaces.');
