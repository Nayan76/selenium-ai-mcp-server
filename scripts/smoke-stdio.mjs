import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import { createInterface } from 'node:readline';

const tools = [
  'launch_browser', 'navigate', 'click_element', 'type_text', 'get_page_text', 'find_elements',
  'take_screenshot', 'close_browser', 'find_interactive_elements', 'wait_for_element',
  'select_dropdown', 'scroll_page', 'hover_element', 'switch_frame', 'switch_window',
  'get_element_details', 'assert_element_visible', 'assert_element_text', 'assert_element_attribute',
  'assert_page_title', 'assert_current_url', 'assert_element_count', 'execute_test_scenario',
  'get_test_execution_results', 'list_test_executions', 'get_test_execution', 'export_test_report',
  'get_execution_evidence', 'decode_page_barcode', 'assert_barcode_value'
];
const jar = process.argv[2] ?? 'target/selenium-mcp-0.1.0.jar';
const child = spawn('java', ['-jar', jar], { stdio: ['pipe', 'pipe', 'inherit'], windowsHide: true });
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
  send({method:'notifications/initialized'});
  const listed = await request(2, 'tools/list');
  assert.deepEqual(listed.tools.map(t => t.name).sort(), tools.sort());
  const result = await request(3, 'tools/call', {name:'get_page_text',arguments:{}});
  assert.equal(result.isError, true);
  assert.equal(result.structuredContent.code, 'NO_SESSION');
  child.stdin.end();
  const [code] = await closed;
  assert.equal(code, 0);
  console.log('Verified initialize, 30 named tools, structured error and clean EOF.');
} finally {
  clearTimeout(stop);
  if (child.exitCode === null && child.signalCode === null) child.kill();
}
