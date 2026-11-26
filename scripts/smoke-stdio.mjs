import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { closeSync, fstatSync, openSync, readFileSync, writeFileSync } from 'node:fs';
import { createServer } from 'node:http';
import { resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { respond as exampleFixture } from '../examples/fixture.mjs';
import * as examples from '../examples/workflows.mjs';

const tools = [
  'launch_browser', 'navigate', 'click_element', 'type_text', 'get_page_text', 'find_elements',
  'take_screenshot', 'close_browser', 'find_interactive_elements', 'wait_for_element',
  'select_dropdown', 'scroll_page', 'hover_element', 'switch_frame', 'switch_window',
  'get_element_details', 'assert_element_visible', 'assert_element_text', 'assert_element_attribute',
  'assert_page_title', 'assert_current_url', 'assert_element_count', 'execute_test_scenario',
  'get_test_execution_results', 'list_test_executions', 'get_test_execution', 'export_test_report',
  'get_execution_evidence', 'decode_page_barcode', 'assert_barcode_value',
  'execute_data_driven_scenario', 'get_data_driven_results',
  'send_api_request', 'assert_api_status', 'assert_api_response', 'get_api_execution_results',
  'execute_api_collection', 'get_api_collection_results', 'list_api_collection_executions', 'export_api_collection_report',
  'list_api_environments', 'save_api_collection', 'list_saved_api_collections', 'get_saved_api_collection',
  'execute_saved_api_collection'
];
const jar = process.argv[2] ?? 'target/selenium-mcp-0.6.0.jar';
const flags = process.argv.slice(3);
const unsupportedRuntime = flags.includes('--unsupported-runtime');
const savedFixture = flags.includes('--saved-fixture');
const checkExamples = flags.includes('--examples');
const browserExample = flags.includes('--browser-example');
const catalogOnly = flags.includes('--catalog');
const version = catalogOnly && flags.includes('--baseline-v050') ? '0.5.0' : '0.6.0';
let fixtureRequests = 0;
const fixture = createServer((request, response) => {
  fixtureRequests++;
  if (request.url === '/' || request.url.startsWith('/api/')) {
    exampleFixture(request, response);
    return;
  }
  if (request.url === '/check' && request.headers.authorization !== 'Bearer fixture-secret') {
    response.writeHead(401);
    response.end();
    return;
  }
  if (request.url === '/single-header') response.setHeader('X-Large', 'a'.repeat(17 * 1024));
  if (request.url === '/combined-headers') {
    response.setHeader('X-First', 'a'.repeat(9 * 1024));
    response.setHeader('X-Second', 'b'.repeat(9 * 1024));
  }
  response.writeHead(200, {'Content-Type': 'application/json'});
  response.end('{"healthy":true,"nothing":null,"access_token":"fixture-secret"}');
});
fixture.listen(0, '127.0.0.1');
await once(fixture, 'listening');
const origin = `http://127.0.0.1:${fixture.address().port}`;
const env = {...process.env, SELENIUM_ALLOWED_ORIGINS: origin,
  SELENIUM_API_ALLOWED_ORIGINS: origin, SELENIUM_API_ALLOW_LOOPBACK: 'true'};
if (savedFixture) {
  assert.ok(env.SELENIUM_API_ENVIRONMENTS_FILE && env.SELENIUM_SAVED_COLLECTION_DIR);
  env.PHASE15_SMOKE_AUTH = 'Bearer fixture-secret';
  const config = JSON.stringify({
    schemaVersion: 1,
    profiles: {dev: {enabled: true, origin, routes: {...examples.profiles(origin).profiles.dev.routes,
      check: {method: 'GET', path: '/check', secretHeaders: {Authorization: 'PHASE15_SMOKE_AUTH'}},
      blocked: {method: 'POST', path: '/check'},
    }}, staging: {origin, routes: {check: {method: 'GET', path: '/check'}}}},
  });
  const descriptor = openSync(env.SELENIUM_API_ENVIRONMENTS_FILE, 'r+');
  try {
    const stat = fstatSync(descriptor);
    assert.ok(stat.isFile() && stat.size === 0, 'Provision a new empty smoke fixture configuration file');
    writeFileSync(descriptor, config);
  } finally {
    closeSync(descriptor);
  }
} else {
  delete env.SELENIUM_API_ENVIRONMENTS_FILE;
  delete env.SELENIUM_SAVED_COLLECTION_DIR;
}
// Try an unsafe override so the smoke test also verifies the packaged entry point clamps it.
const child = spawn('java', ['-Djdk.http.maxHeaderSize=0', '-jar', jar], {
  stdio: ['pipe', 'pipe', 'inherit'],
  windowsHide: true,
  env,
});
const closed = once(child, 'close');
const stop = setTimeout(() => child.kill(), 30000);
const lines = createInterface({input: child.stdout})[Symbol.asyncIterator]();
function send(message) {
  child.stdin.write(JSON.stringify({ jsonrpc: '2.0', ...message }) + '\n');
}
async function request(id, method, params = {}) {
  send({id, method, params});
  while (true) {
    const {value, done} = await lines.next();
    assert.ok(!done, 'Server closed before responding');
    const response = JSON.parse(value);
    assert.equal(response.jsonrpc, '2.0');
    if (response.id === undefined) continue;
    assert.equal(response.id, id);
    assert.ok(!response.error, 'JSON-RPC request failed');
    return response.result;
  }
}
function canonical(_key, value) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) return value;
  return Object.fromEntries(Object.keys(value).sort().map(name => [name, value[name]]));
}
try {
  const init = await request(1, 'initialize', {protocolVersion:'2025-06-18', capabilities:{},
    clientInfo:{name:'release-smoke',version:'1'}});
  assert.equal(init.serverInfo.name, 'selenium-mcp');
  assert.equal(init.serverInfo.version, version);
  send({method:'notifications/initialized'});
  const listed = await request(2, 'tools/list');
  assert.deepEqual(listed.tools.map(t => t.name).sort(), tools.sort());
  if (catalogOnly) {
    console.log(JSON.stringify(listed.tools.sort((a, b) => a.name.localeCompare(b.name)), canonical));
  } else {
    await exercise();
  }
  child.stdin.end();
  const [code] = await closed;
  assert.equal(code, 0);
  if (!catalogOnly) {
    console.log(unsupportedRuntime
      ? 'Verified initialize, 45 named tools, browser dispatch, API runtime guards, and clean EOF.'
      : 'Verified initialize, 45 named tools, API and collection round trips, JDK header rejection and clean EOF.');
  }
} finally {
  clearTimeout(stop);
  if (child.exitCode === null && child.signalCode === null) child.kill();
  fixture.closeAllConnections();
  fixture.close();
}

async function exercise() {
  const result = await request(3, 'tools/call', {name:'get_page_text',arguments:{}});
  assert.equal(result.isError, true);
  assert.equal(result.structuredContent.code, 'NO_SESSION');
  const sent = await request(4, 'tools/call', {
    name:'send_api_request', arguments:{method:'GET', url:`${origin}/health?token=fixture-query`},
  });
  if (unsupportedRuntime) {
    assert.equal(sent.isError, true);
    assert.equal(sent.structuredContent.code, 'API_RUNTIME_UNSUPPORTED');
    assert.equal(fixtureRequests, 0);
  } else {
    assert.equal(sent.structuredContent.statusCode, 200);
    const requestId = sent.structuredContent.requestId;
    const checked = await request(5, 'tools/call', {
      name:'assert_api_status', arguments:{requestId, expectedStatus:200},
    });
    assert.equal(checked.structuredContent.status, 'PASS');
    const json = await request(6, 'tools/call', {
      name:'assert_api_response', arguments:{requestId, jsonPath:'$.nothing', assertionType:'equals', expected:null},
    });
    assert.equal(json.structuredContent.status, 'PASS');
    const history = await request(7, 'tools/call', {
      name:'get_api_execution_results', arguments:{requestId},
    });
    assert.equal(history.structuredContent.assertions.length, 2);
    assert.doesNotMatch(JSON.stringify([sent, history]), /fixture-secret|fixture-query|access_token/);
    for (const [id, path] of [[8, '/single-header'], [9, '/combined-headers']]) {
      const rejected = await request(id, 'tools/call', {
        name:'send_api_request', arguments:{method:'GET', url:origin + path},
      });
      assert.equal(rejected.isError, true);
      assert.equal(rejected.structuredContent.code, 'API_TRANSPORT_ERROR');
      assert.ok(!Object.hasOwn(rejected.structuredContent, 'statusCode'), 'JDK must reject headers before dispatch');
    }
    assert.equal(fixtureRequests, 3);
  }
  const collection = await request(10, 'tools/call', {
    name: 'execute_api_collection', arguments: {
      collectionName: 'Fixture chain',
      requests: [
        {id: 'login', name: 'Login', method: 'GET', url: origin + '/login', extract: {token: '$.access_token'}},
        {
          id: 'check', name: 'Check', method: 'GET', url: origin + '/check',
          headers: {Authorization: 'Bearer {{token}}'}, assertions: [{type: 'status', expectedStatus: 200}],
        },
      ],
    },
  });
  const results = await request(11, 'tools/call', {
    name: 'get_api_collection_results', arguments: {executionId: collection.structuredContent.executionId},
  });
  if (unsupportedRuntime) {
    assert.equal(results.structuredContent.requests[0].code, 'API_RUNTIME_UNSUPPORTED');
    assert.equal(fixtureRequests, 0);
  } else {
    assert.equal(results.structuredContent.passed, 2);
    assert.equal(fixtureRequests, 5);
  }
  assert.doesNotMatch(JSON.stringify(results), /fixture-secret|access_token|Authorization/);
  const environments = await request(12, 'tools/call', {name: 'list_api_environments', arguments: {}});
  if (savedFixture) {
    assert.deepEqual(environments.structuredContent, {environments: ['dev']});
    const saved = await request(13, 'tools/call', {name: 'save_api_collection', arguments: {
      name: 'Saved fixture', requests: [{id: 'check', name: 'Check', route: 'check', expectedStatus: 200}],
    }});
    const {collection} = saved.structuredContent;
    const listedSaved = await request(14, 'tools/call', {name: 'list_saved_api_collections', arguments: {}});
    assert.equal(listedSaved.structuredContent.collections[0].collectionId, collection.collectionId);
    const get = await request(15, 'tools/call', {name: 'get_saved_api_collection',
      arguments: {collectionId: collection.collectionId}});
    assert.deepEqual(get.structuredContent.collection, collection);
    const beforeDenial = fixtureRequests;
    const staging = await request(18, 'tools/call', {name: 'execute_saved_api_collection',
      arguments: {collectionId: collection.collectionId, environmentId: 'staging'}});
    assert.equal(staging.isError, true);
    assert.equal(staging.structuredContent.code, 'ENVIRONMENT_DISABLED');
    assert.equal(fixtureRequests, beforeDenial);
    const run = await request(16, 'tools/call', {name: 'execute_saved_api_collection',
      arguments: {collectionId: collection.collectionId, environmentId: 'dev'}});
    if (unsupportedRuntime) {
      assert.equal(run.structuredContent.code, 'API_RUNTIME_UNSUPPORTED');
      assert.equal(fixtureRequests, 0);
    } else {
      assert.equal(run.structuredContent.passed, 1);
      assert.equal(fixtureRequests, 6);
      const details = await request(17, 'tools/call', {name: 'get_api_collection_results',
        arguments: {executionId: run.structuredContent.executionId}});
      assert.doesNotMatch(JSON.stringify([get, run, details]), /fixture-secret|PHASE15_SMOKE_AUTH|access_token/);
      const blocked = await request(19, 'tools/call', {name: 'save_api_collection', arguments: {
        name: 'Blocked mutation', requests: [{id: 'blocked', name: 'Blocked', route: 'blocked', expectedStatus: 200}],
      }});
      const denied = await request(20, 'tools/call', {name: 'execute_saved_api_collection',
        arguments: {collectionId: blocked.structuredContent.collection.collectionId, environmentId: 'dev'}});
      assert.equal(denied.isError, true);
      assert.equal(denied.structuredContent.code, 'ROUTE_NOT_ALLOWED');
      assert.equal(fixtureRequests, 6);
    }
  } else {
    assert.equal(environments.isError, true);
    assert.equal(environments.structuredContent.code, 'STORE_UNAVAILABLE');
  }
  let nextId = 100;
  async function exampleCall(name, args) {
    const result = await request(nextId++, 'tools/call', {name, arguments: args});
    assert.ok(!result.isError, name + ' failed: ' + result.structuredContent?.code);
    return result;
  }
  if (checkExamples) {
    const sent = (await exampleCall('send_api_request', examples.send(origin))).structuredContent;
    for (const [name, args] of examples.assertions(sent.requestId)) {
      assert.equal((await exampleCall(name, args)).structuredContent.status, 'PASS');
    }
    const history = await exampleCall('get_api_execution_results', {requestId: sent.requestId});
    assert.equal(history.structuredContent.assertions.length, 2);
    const chain = (await exampleCall('execute_api_collection', examples.collection(origin))).structuredContent;
    assert.equal(chain.passed, 2);
    const details = await exampleCall('get_api_collection_results', {executionId: chain.executionId});
    assert.equal(details.structuredContent.passed, 2);
    const report = await exampleCall('export_api_collection_report', {executionId: chain.executionId, format: 'json'});
    assert.match(report.structuredContent.path, /\.json$/);
    const exported = readFileSync(resolve(env.SELENIUM_ARTIFACT_DIR ?? 'selenium-artifacts',
      report.structuredContent.path), 'utf8');
    assert.equal(JSON.parse(exported).executionId, chain.executionId);
    assert.doesNotMatch(exported, /fixture-1|Fixture item/);
    if (savedFixture) {
      const definition = (await exampleCall('save_api_collection', examples.saved)).structuredContent.collection;
      const run = (await exampleCall('execute_saved_api_collection',
        {collectionId: definition.collectionId, environmentId: 'dev'})).structuredContent;
      assert.equal(run.passed, 1);
      await exampleCall('get_api_collection_results', {executionId: run.executionId});
    }
    console.log(savedFixture ? 'Verified API, collection export and saved examples.' : 'Verified API and collection export examples.');
  }
  if (browserExample) {
    for (const [name, args] of examples.browser(origin)) {
      const result = await exampleCall(name, args);
      if (name === 'find_elements') assert.equal(result.structuredContent.total, 1);
      if (name === 'assert_element_text') assert.equal(result.structuredContent.status, 'PASS');
      if (name === 'take_screenshot') assert.ok(result.content.some(item => item.mimeType === 'image/png'));
    }
    console.log('Verified Chrome fixture and PNG evidence.');
  }
}
