import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createServer } from 'node:http';
import { createInterface } from 'node:readline';

const tools = [
  'launch_browser', 'navigate', 'click_element', 'type_text', 'get_page_text', 'find_elements',
  'take_screenshot', 'close_browser', 'find_interactive_elements', 'wait_for_element',
  'select_dropdown', 'scroll_page', 'hover_element', 'switch_frame', 'switch_window',
  'get_element_details', 'assert_element_visible', 'assert_element_text', 'assert_element_attribute',
  'assert_page_title', 'assert_current_url', 'assert_element_count', 'execute_test_scenario',
  'get_test_execution_results', 'list_test_executions', 'get_test_execution', 'export_test_report',
  'get_execution_evidence', 'decode_page_barcode', 'assert_barcode_value',
  'execute_data_driven_scenario', 'get_data_driven_results',
  'send_api_request', 'assert_api_status', 'assert_api_response', 'get_api_execution_results'
];
const jar = process.argv[2] ?? 'target/selenium-mcp-0.3.0.jar';
const unsupportedRuntime = process.argv[3] === '--unsupported-runtime';
let fixtureRequests = 0;
const fixture = createServer((request, response) => {
  fixtureRequests++;
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
// Try an unsafe override so the smoke test also verifies the packaged entry point clamps it.
const child = spawn('java', ['-Djdk.http.maxHeaderSize=0', '-jar', jar], {
  stdio: ['pipe', 'pipe', 'inherit'],
  windowsHide: true,
  env: {...process.env, SELENIUM_API_ALLOWED_ORIGINS: origin, SELENIUM_API_ALLOW_LOOPBACK: 'true'},
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
try {
  const init = await request(1, 'initialize', {protocolVersion:'2025-06-18', capabilities:{},
    clientInfo:{name:'release-smoke',version:'1'}});
  assert.equal(init.serverInfo.name, 'selenium-mcp');
  assert.equal(init.serverInfo.version, '0.3.0');
  send({method:'notifications/initialized'});
  const listed = await request(2, 'tools/list');
  assert.deepEqual(listed.tools.map(t => t.name).sort(), tools.sort());
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
  child.stdin.end();
  const [code] = await closed;
  assert.equal(code, 0);
  console.log(unsupportedRuntime
    ? 'Verified initialize, 36 named tools, browser dispatch, API runtime guard with no fixture request, and clean EOF.'
    : 'Verified initialize, 36 named tools, API round trip, JDK header rejection and clean EOF.');
} finally {
  clearTimeout(stop);
  if (child.exitCode === null && child.signalCode === null) child.kill();
  fixture.closeAllConnections();
  fixture.close();
}
