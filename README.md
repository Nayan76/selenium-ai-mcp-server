# Selenium MCP Server

Control one Chrome, Firefox, or Edge session from an MCP client over stdio. Version 0.3.0 exposes 36 tools, including 32 browser and scenario tools and four REST API testing tools. The browser tools share a fresh profile and run headlessly by default.

## Build and run

Install JDK 21, Maven 3.9+, and the browser you intend to use. Selenium Manager resolves compatible drivers on first launch, which may require internet access and a writable cache. In offline environments, provision a compatible driver on PATH. Inspector 2.16.0 declares Node.js ^22.23.4, ^24.22.1 or >=26.12.1.

From the project directory, run:

```powershell
mvn clean verify
$env:SELENIUM_ALLOWED_ORIGINS = 'https://example.com'
java -jar target/selenium-mcp.jar
```

The process waits for MCP messages on stdin. Configure your client to launch the Java command directly and pass the origin setting in its environment. Use an absolute JAR path when starting from another directory. Closing stdin ends the server. Logs go to stderr.

## Verify with MCP Inspector

The included configuration starts the packaged server and permits the example origin. Run from the repository root:

```powershell
npx --yes @modelcontextprotocol/inspector@2.16.0 --cli --config inspector.json --server selenium --method tools/list
npx --yes @modelcontextprotocol/inspector@2.16.0 --cli --config inspector.json --server selenium --method tools/call --tool-name get_page_text
```

Expect 36 tools from the first command and a structured `NO_SESSION` error from the second. Each CLI invocation launches a new process. For a sequence of browser or API operations, start the interactive Inspector and keep the connection open:

```powershell
npx --yes @modelcontextprotocol/inspector@2.16.0 --web --config inspector.json --server selenium
```

Open the authenticated local URL printed by Inspector, connect to the stdio server, and list tools. Call `launch_browser` with `{"browser":"chrome"}`, then `navigate` with `{"url":"https://example.com"}`. Call `get_page_text` or `take_screenshot`, then `close_browser`.

## Tool contracts

| Tool | Arguments | Result |
| --- | --- | --- |
| `launch_browser` | Required `browser` (`chrome`, `firefox`, `edge`), optional boolean `headless` | Opens one session. A second launch reports `SESSION_ACTIVE`. |
| `navigate` | `url` | Loads an allowed HTTP(S) page with a 20-second page-load timeout. |
| `click_element` | CSS `selector` | Waits for the first match to be visible and enabled, then clicks it. |
| `type_text` | CSS `selector`, `text` | Appends up to 8,192 printable characters to a text input or textarea. |
| `get_page_text` | None | Visible body text, limited to 32,000 characters. |
| `find_elements` | CSS `selector` | Total matches and the first 50 tag/text summaries. Returns an empty list after the wait expires. |
| `take_screenshot` | None | An inline PNG image, limited to 4 MiB. |
| `close_browser` | None | Quits all windows in the session. Safe to repeat. |

Element waits default to five seconds. Tool failures set `isError` and carry a stable `code` and human-readable `message` in `structuredContent`.

## Advanced tools

Use these JSON objects as tool arguments on the same connection after opening a page. Selectors use CSS by default. Advanced tools also accept `"strategy":"id"` and `"strategy":"name"` for literal attribute matches. Each locator resolves in the current window and frame. A single-element operation uses the first match.

| Tool | Example arguments | Behavior |
| --- | --- | --- |
| `find_interactive_elements` | `{}` | Returns a candidate count and up to 50 summaries in document order. Includes native controls, links, editable elements, common ARIA roles and tab stops. Check the visibility and enabled flags before acting. |
| `wait_for_element` | `{"selector":"save","strategy":"id","condition":"clickability","timeoutSeconds":8}` | Accepts `presence`, `visibility`, `clickability`, `invisibility` (no displayed matches), and `absence` (zero matches). Detached elements are looked up again on the next poll. Timeouts are integers from 0 through 30 seconds. Zero checks once. Clickability means displayed and enabled. |
| `select_dropdown` | `{"selector":"#country","visibleText":"India"}` | For a native select, supply exactly one of `visibleText`, `value`, or zero-based integer `index`. An empty option value is valid. |
| `scroll_page` | `{"direction":"down"}` or `{"selector":"#footer"}` | Moves the current document by one viewport up or down, or centers a visible element. Page boundaries may limit movement. |
| `hover_element` | `{"selector":"#account-menu"}` | Moves the pointer with Selenium Actions. |
| `switch_frame` | `{"index":0}`, `{"id":"checkout"}`, `{"name":"payment"}`, `{"selector":"iframe.receipt"}`, or `{}` | Enters a child frame relative to the current document. Empty arguments return directly to the top document. A disallowed destination resets selection to the top document. |
| `switch_window` | `{}` then `{"handle":"<returned handle>"}` | Lists opaque handles, then selects a window at its default document. A denied switch attempts to restore the previous window. |
| `get_element_details` | `{"strategy":"name","selector":"quantity"}` | Returns tag, visible text, ID, name, type, computed role and accessible label, and visible, enabled and selected flags. |

Discovery and details return a locator suggestion when an ID or name uniquely identifies the element in the current document. Attribute and text summaries are limited to 256 characters. The tools omit form values and raw HTML. Accessible names and visible page text can still contain sensitive site content. Discovery inspects the current DOM, including hidden or disabled candidates, and skips detached candidates. Enter frames explicitly. Shadow-root traversal and arbitrary script evaluation are outside the tool interface.

The top document must use an allowed HTTP(S) origin. HTTP(S) child frames must share its origin and be able to read its browser-protected location. An `about:srcdoc` editor is supported when it is the first direct child browsing context of the top window and can read that location. The server rechecks this proof on every context check. Nested and later sibling inline frames, opaque sandboxes, `data:`, and `about:blank` are rejected. Screenshot verification still requires an allowed HTTP(S) URL for every visible or embedded document. Window listing returns handles even if the active window has closed. Timeouts, stale elements, missing options, and unavailable windows produce structured errors. Click interception and non-interactability return `ELEMENT_CLICK_INTERCEPTED` and `ELEMENT_NOT_INTERACTABLE` with fixed messages. A stale response after an interaction warrants checking the current page state before repeating the action.

## Assertions and scenarios

Assertions read the current page once. Use `wait_for_element` when the page updates asynchronously. Element assertions accept CSS, ID and name locators. The first match supplies the observation, except for counts.

| Tool | Example arguments | Behavior |
| --- | --- | --- |
| `assert_element_visible` | `{"selector":"#save","visible":true}` | Expected visibility defaults to true. Hidden and missing elements have actual visibility false. |
| `assert_element_text` | `{"selector":"h1","expected":"Example","match":"contains"}` | Compares visible element text. |
| `assert_element_attribute` | `{"selector":"#save","attribute":"type","expected":"submit"}` | Compares a DOM attribute exactly. Supported names are id, name, class, type, role, title, alt, disabled, checked, aria-label, aria-expanded, aria-checked and data-testid. |
| `assert_page_title` | `{"expected":"Example Domain"}` | Checks the top-level page title exactly. |
| `assert_current_url` | `{"expected":"example.com","match":"contains"}` | Compares the top-level URL, including query and fragment. |
| `assert_element_count` | `{"selector":"h1","count":1}` | Counts all DOM matches, including hidden elements. Zero is valid. |
| `execute_test_scenario` | Examples below | Validates a plan and executes its ordered steps on the active browser. |
| `get_test_execution_results` | `{}` | Returns the latest completed report in the current stdio server process. |

String comparisons are case-sensitive and default to exact matching. A missing element or attribute fails a text or attribute assertion, including when the expected string is empty. Mismatches produce structured FAIL results. Invalid arguments, invalid locators and browser failures in standalone tools use the existing tool error contract.

### Run a test case

Launch a browser first. Supply a JSON object as the `scenario` argument. Use a public case ID of 1–64 letters, digits, underscores, periods or hyphens, beginning with a letter or digit. Provide a description of 1–512 characters. Supported scenario steps are `navigate`, `click_element`, `type_text`, `wait_for_element`, the six assertions above, and `assert_barcode_value`. Each step uses its tool's normal arguments.

This example checks the heading on the origin configured for Inspector.

```json
{
  "scenario": {
    "id": "example-heading",
    "description": "Verify the example page heading",
    "steps": [
      {"tool":"navigate","arguments":{"url":"https://example.com/"}},
      {"tool":"wait_for_element","arguments":{"selector":"h1","condition":"visibility","timeoutSeconds":5}},
      {"tool":"assert_element_text","arguments":{"selector":"h1","expected":"Example Domain"}}
    ]
  }
}
```

On the same page, this example records a count mismatch and continues.

```json
{
  "scenario": {
    "id": "example-count",
    "description": "Demonstrate a count mismatch",
    "continueOnFailure": true,
    "timeoutSeconds": 10,
    "steps": [
      {"tool":"assert_element_count","arguments":{"selector":"h1","count":2}},
      {"tool":"assert_element_visible","arguments":{"selector":"h1"}}
    ]
  }
}
```

### Interpret the report

Both execution tools return the report in MCP `structuredContent`. The following durations are illustrative.

```json
{
  "id":"example-count","status":"FAIL","durationMs":210,"plannedSteps":2,
  "steps":[
    {"step":1,"tool":"assert_element_count","status":"FAIL","expected":2,"actual":1,"message":"Expectation mismatch.","durationMs":100},
    {"step":2,"tool":"assert_element_visible","status":"PASS","expected":true,"actual":true,"message":"Expectation satisfied.","durationMs":110}
  ]
}
```

A report contains one row per attempted step. Operational failures produce ERROR rows with a code and diagnostic message. Overall status records the highest severity seen, in order ERROR, FAIL, PASS. Execution stops at the first failure by default. With continuation enabled, later steps use the browser state left by earlier actions. Each step rechecks the current context. A first navigation may start from an empty top-level browser document.

A plan accepts 1–32 steps and a deadline of 1–60 seconds, defaulting to 30. Timing starts once the scenario acquires exclusive use of the session. Explicit waits and page-load timeouts are capped to the remaining budget. The engine checks before and after each step and stops on expiry even with continuation enabled. An in-flight driver command or cleanup may delay the response beyond the deadline.

The latest completed report stays in memory until another valid run completes or the process exits. Rejected plans leave that report available. Before the first run, retrieval returns `NO_RESULTS`. Reports retain the public case ID, tool names, timing, states and counts. String observations and expectations are masked, including page text and URLs. Descriptions, typed values, selectors and raw driver errors are omitted. Malformed JSON-RPC is handled by the SDK before scenario validation.

## Barcode decoding and validation

Two ZXing-backed tools decode rendered barcodes directly from the active browser session without downloading remote images outside the browser. Both accept CSS selectors by default (`"strategy":"css"`) or XPath expressions (`"strategy":"xpath"`) and work with rendered `<img>`, inline `<svg>`, and `<canvas>` elements (including transparent backgrounds).

| Tool | Example arguments | Behavior |
| --- | --- | --- |
| `decode_page_barcode` | `{"selector":"#ticket-qr"}` or `{"selector":"//canvas[@id='barcode']","strategy":"xpath"}` | Waits up to five seconds for the matched element to be visible and loaded, scrolls it into view, captures its rendered PNG with Selenium, and returns `value` and `format`. |
| `assert_barcode_value` | `{"selector":"#ticket-qr","expected":"ORDER-2026-9981","expectedFormat":"CODE_128"}` | Reads the rendered barcode once, compares exact value and optional `expectedFormat`, and returns structured `PASS`, `FAIL` (`isError: false`), or `ERROR` (`isError: true` with `code` and `message`). |

Supported ZXing formats are `CODE_128`, `CODE_39`, `EAN_13`, `UPC_A`, `QR_CODE`, and `DATA_MATRIX`. Before and after capturing the element screenshot, the server verifies both the active context and every document in the viewport against `SELENIUM_ALLOWED_ORIGINS`, and enforces PNG size (4 MiB) and image dimension limits (up to 4,096 × 4,096 and 8 megapixels). Captured pixels are composited onto an opaque white background with a quiet-zone margin before decoding.

`assert_barcode_value` is also supported as a step inside `execute_test_scenario`, so assertion failures (`FAIL`) and decode/element errors (`ERROR`) automatically capture viewport evidence and appear in execution history and JSON/HTML reports. Expected and actual barcode strings are redacted (`[redacted]`) in assertion responses and retained reports, while the non-sensitive format enum names (`actualFormat` and optional `expectedFormat`) are preserved.

**Exact-match and EAN-13 / UPC-A policy:** `assert_barcode_value` compares `expected` using strict, case-sensitive exact equality (no trimming, substring matching, or leading-zero normalization) and compares `expectedFormat` by exact enum name when supplied. When both `EAN_13` and `UPC_A` are enabled in ZXing, non-zero 13-digit EAN-13 symbols decode as 13-digit `EAN_13`, while 13-digit EAN-13 symbols beginning with `0` (for example `0036000291452`) decode as 12-digit `UPC_A` (`036000291452`). Under the exact-match policy, assert the 12-digit `UPC_A` form for leading-zero EAN-13/UPC-A codes.

## Data-driven test execution

Two tools execute parameterized scenarios across multiple bounded datasets while keeping datasets isolated and redacting sensitive values.

| Tool | Example arguments | Behavior |
| --- | --- | --- |
| `execute_data_driven_scenario` | `{"scenarioName":"login-matrix","steps":[...],"datasets":[{"id":"valid-user","variables":{...}}],"stopOnFailure":false,"timeoutSeconds":60}` | Prevalidates all datasets and resolved steps (including origin checks) before browser access, executes datasets sequentially with browser-state reset isolation, captures evidence on failure, and returns aggregate and per-dataset `PASS` / `FAIL` / `ERROR` results. |
| `get_data_driven_results` | `{"executionId":"<UUID>"}` | Returns scenario name, dataset IDs, per-dataset statuses and failure categories, step counts, execution timings, redacted diagnostics, verified evidence references, and available JSON/HTML report paths. |

### Placeholder rules and bounds

- **Explicit placeholders:** Use `{{variableName}}` where `variableName` matches `^[A-Za-z_][A-Za-z0-9_.-]{0,63}$`. Substitution is single-pass (values containing `{{...}}` are treated literally and never re-expanded) and resolves strictly from the current dataset's `variables` map.
- **Allowed substitution fields only:** Placeholders are permitted only in `navigate.url`, `type_text.text`, and `assert_*.expected` (`assert_element_text`, `assert_element_attribute`, `assert_page_title`, `assert_current_url`, `assert_barcode_value`). Placeholders in tool names, `selector`, `strategy`, `condition`, `attribute`, `match`, `expectedFormat`, or non-string fields are rejected with `UNSUPPORTED_PLACEHOLDER`.
- **Prevalidation:** Missing variables (`MISSING_VARIABLE`), malformed placeholder delimiters (`MALFORMED_PLACEHOLDER`), duplicate dataset IDs (`DUPLICATE_DATASET_ID`), empty datasets or empty variable maps (`INVALID_DATASET`), invalid step arguments, and disallowed resolved origins (`ORIGIN_DENIED`) fail before any browser action executes.
- **Execution bounds:** `scenarioName` and dataset `id` must match `^[A-Za-z0-9][A-Za-z0-9_.-]{0,63}$`; 1–20 datasets; 1–32 steps per dataset; maximum 200 total steps (`datasets × steps`); 1–20 string variables per dataset (up to 2,048 characters each); overall deadline 1–120 seconds (default 60).
- **Browser-state isolation:** Between datasets, the server closes extra windows, returns to `defaultContent()`, deletes cookies (including `HttpOnly`) and clears `localStorage` and `sessionStorage` on every visited allowed origin (verifying both are empty), then resets to `about:blank` (when step 1 is `navigate`) or reloads the initial allowed URL. If browser-state reset cannot be guaranteed, remaining execution aborts with `DATASET_RESET_FAILED` (`RESET_FAILURE`).
- **Redaction and reporting:** Dataset variable values are never returned in tool responses, error messages, history records, JSON/HTML exports, logs, or artifact filenames (`<uuid>-d<dataset>-<step>.png`). Dataset IDs are preserved as public labels; do not place secrets inside `dataset.id`, and note that captured screenshots still show whatever is visibly rendered in the viewport. Completed runs integrate directly with `list_test_executions`, `get_test_execution`, `get_execution_evidence`, and `export_test_report` (`json` and standalone `html`).

## Evidence, execution history and reports

Every completed execution receives a UUID in `executionId`. The public case identifier remains in `id`, so running the same case twice creates two distinct records. The latest-result tool continues to return the most recent completed report.

| Reporting tool | Arguments | Result |
| --- | --- | --- |
| `list_test_executions` | `{}` | Newest-first summaries containing execution ID, case ID, status, end timestamp and duration in milliseconds. |
| `get_test_execution` | `{"executionId":"<UUID>"}` | Complete retained report. |
| `get_execution_evidence` | `{"executionId":"<UUID>"}` | Screenshot metadata including execution ID, 1-based step, UTC timestamp, MIME type and relative file path. |
| `export_test_report` | `{"executionId":"<UUID>","format":"html"}` or `{"executionId":"<UUID>","format":"json"}` | A relative artifact path. Repeat an export to regenerate it. |

### Capture settings

FAIL and ERROR steps automatically attempt a PNG capture. Add the optional booleans shown below inside the `scenario` object to capture successful steps or collect console diagnostics.

```json
{
  "id": "heading-evidence",
  "description": "Record evidence for the heading check",
  "screenshotOnSuccess": true,
  "collectConsole": true,
  "steps": [
    {"tool":"assert_element_visible","arguments":{"selector":"h1"}}
  ]
}
```

A step links its saved PNG through `evidence`. If capture fails, the step keeps its original status and diagnostic. Its `screenshotStatus` is `UNAVAILABLE` or `CONTEXT_BLOCKED`. The report also lists all successful captures in its top-level `evidence` array. JSON and HTML carry references only. Screenshots **can contain sensitive visible page information**, including text that has been masked in the report.

The server checks the top-level viewport and its frame documents before and after capture. Unreadable cross-origin frames, inline documents without an allowed HTTP(S) URL, and frame trees larger than 64 documents prevent capture. Browser-origin checks are snapshots. Dynamic pages can change while WebDriver captures an image.

Console collection uses the driver's browser log capability and runs once at completion. It retains timestamps, normalized severity and the fixed message `[redacted]` for up to 50 entries dated since execution start. This avoids retaining cookies, tokens or application-provided console strings. The driver controls which levels are available and may supply a batch larger than the retained sample. The `console.status` field distinguishes `COLLECTED`, `UNSUPPORTED` and `UNAVAILABLE`. Reports and screenshots work independently of DevTools bindings.

### Storage and cleanup

Set these environment variables on the **server process**, including in the MCP client launch configuration as needed.

| Variable | Default | Behavior |
| --- | --- | --- |
| `SELENIUM_ARTIFACT_DIR` | `selenium-artifacts` under the working directory | Dedicated base for a private, generated `session-…` directory. Created on the first successful artifact write. |
| `SELENIUM_HISTORY_LIMIT` | `50` | Retains 1–200 completed executions per process. Eviction removes the execution from lookup and attempts to delete its PNGs and exports. |
| `SELENIUM_ARTIFACT_MAX_AGE_HOURS` | `168` | Maximum age by last modification, from 1 to 87,600 hours. |
| `SELENIUM_ARTIFACT_MAX_MIB` | `256` | Logical storage budget for managed PNG, HTML and JSON payloads in this root, from 1 to 65,536 MiB. |
| `SELENIUM_CLEANUP_ON_EXIT` | `false` | Set to `true` to remove retained artifacts and the session directory on orderly shutdown. |

History lookup is in memory and isolated to the current server process. Restarting starts an empty history. Exported files and PNGs remain available locally after exit by default. The count limit applies per process. Age and byte limits apply across Phase 5 session directories on one host. Before writes and once a minute, the server removes the oldest eligible files with matching ownership receipts. It protects in-progress runs and live peer processes. If there is insufficient eligible space, the write fails. Lookups omit retired screenshots and identify them as expired or unavailable. Unknown and expired IDs return `EXECUTION_NOT_FOUND`.

Use a directory controlled by the server OS user and protect it from concurrent modification by other accounts. Paths, filenames and output formats are server-controlled. The server rejects linked directory paths, uses owner-only permissions for newly created directories and files on POSIX filesystems, and inherits the operator's directory ACL on Windows. Individual PNGs are limited to 4 MiB and exported reports to 512 KiB. Export requires support for an atomic move within the artifact directory. A storage error returns `ARTIFACT_UNAVAILABLE` without changing the retained result.

### Open an exported report

Export HTML on the same connection that ran the scenario, then join the returned `path` to the configured artifact base. For example, with the default base in PowerShell:

```powershell
$reportPath = 'session-…/replace-with-execution-id.html' # use the path returned by the tool
Invoke-Item -LiteralPath (Join-Path 'selenium-artifacts' $reportPath)
```

The HTML opens locally with embedded styles, a status summary, timings, redacted diagnostics and a step table. Screenshot links resolve beside the HTML file. Keep the referenced PNGs alongside the HTML when copying it. The JSON export contains the same structured execution record returned by lookup, including browser type, UTC start/end timestamps, per-step results and evidence metadata. Neither export includes image bytes.

## REST API testing

API testing requires a production Java 21 runtime at patch level **21.0.5 or later**. Check the Java executable selected by your MCP client with `java -version`. An older Java 21 runtime can start the server and use the browser tools. API requests on it return `API_RUNTIME_UNSUPPORTED` before opening a connection. Development and CI use Eclipse Temurin 21.0.13.1.

The server sets the OpenJDK HTTP parser property `jdk.http.maxHeaderSize` at the start of the stdio entry point, before initializing HTTP clients. It preserves a stricter positive decimal setting and replaces a missing, invalid, disabled, or larger setting with 16,384 bytes. Oracle added this parser control in 21.0.5. The checks exercise the actual Temurin parser and the packaged launch path with an attempted unlimited override. Other runtime vendors must implement the same property and initialization behavior. [Oracle JDK 21.0.5 release notes](https://www.oracle.com/java/technologies/javase/21-0-5-relnotes.html)

The four API tools are available starting in v0.3.0. Use them from the same MCP connection as browser tools. The HTTP client owns its own connections and does not persist cookies or share authentication with WebDriver. Browser and data-driven scenario schemas are unchanged. Call the API tools directly from the MCP client.

### Configure API destinations

Set `SELENIUM_API_ALLOWED_ORIGINS` to a comma-separated list of exact HTTP(S) origins. An empty list denies all API requests. An invalid entry disables API requests with `API_CONFIGURATION_INVALID`. Browser authorization uses its own `SELENIUM_ALLOWED_ORIGINS` setting.

The API client accepts canonical dotted-decimal public IPv4 addresses. It rejects DNS names, IPv6, ambiguous IPv4 syntax, URL credentials and fragments, and local, private, shared, link-local, multicast, reserved, and known metadata addresses. Java 21's `HttpClient` has no per-request address pinning hook. Restricting requests to IPv4 literals avoids a hostname lookup between validation and connection. The client connects directly using HTTP/1.1, disables automatic redirects and cookie storage, and applies normal TLS certificate verification. HTTPS endpoints need a trusted certificate valid for the IP address. Domain-only and name-based virtual hosts are unsupported.

The development-only switch `SELENIUM_API_ALLOW_LOOPBACK=true` permits HTTP on exactly `127.0.0.1` and still requires an exact API origin entry. For a fixture on port 8080, set the following before launching the server. Apply the same variables in the client configuration when the client launches the process.

```powershell
$env:SELENIUM_API_ALLOW_LOOPBACK = 'true'
$env:SELENIUM_API_ALLOWED_ORIGINS = 'http://127.0.0.1:8080'
$env:SELENIUM_ALLOWED_ORIGINS = 'http://127.0.0.1:8080'
```

An allowed origin permits requests to any path and query on that origin. Route changes, NAT, and services reachable through a permitted IP are outside application-level origin validation. Use an outbound firewall for network containment and scope allowlists to trusted test services. Keep JDK HTTP logging disabled and the Java HTTP header parser bounded. Plain HTTP sends any supplied credentials without TLS. Use it for local fixtures and use HTTPS for authenticated remote tests.

### Tool contracts

All inputs are JSON objects and reject unknown fields. `requestId` is a lowercase hyphenated UUID returned by the server.

| Tool | Required input | Optional input | Structured result |
| --- | --- | --- | --- |
| `send_api_request` | `method` is `GET`, `POST`, `PUT`, `PATCH`, or `DELETE`. `url` is a string of 1–2,048 characters. | `headers` is a string map with up to 32 entries, 1–64-character names, and 0–2,048-character values. `body` is a string up to 65,536 characters and bytes in UTF-8. `timeoutSeconds` is an integer from 1 to 30, default 15. | `requestId`, `method`, `origin`, `startedAt`, `completedAt`, `durationMs`, `status`, and `statusCode` if received. A completed exchange includes `response`. A failed exchange includes `code` and `message`. |
| `assert_api_status` | `requestId`, `expectedStatus` (integer 100–599) | | `PASS` or `FAIL` with actual and expected status codes. `ERROR` if the request or HTTP status is unavailable. |
| `assert_api_response` | `requestId`, `jsonPath` (1–256 characters), `assertionType` (`equals`, `exists`, `not_exists`, `contains`, or `type`) | JSON `expected` is required for `equals`, `contains`, and `type`. Omit it for `exists` and `not_exists`. | `PASS`, `FAIL`, or `ERROR` with the assertion type and timestamp for a recorded request. JSON values and paths are excluded from results. |
| `get_api_execution_results` | `requestId` | | The sanitized execution record with an `assertions` array. |

Requests with a body default to `Content-Type: application/json` unless supplied explicitly. The combined header budget is 8 KiB for caller-supplied fields. Names must be HTTP tokens and values printable ASCII. Duplicate names, CRLF, framing and routing headers, and proxy overrides are rejected. Header values, request bodies, URL paths and queries, and raw HTTP exceptions are omitted from output and history.

HTTP error statuses are completed exchanges. Redirect responses return their status with no redirect target and no follow-up connection. The deadline covers headers and the streamed body, with a separate connection limit of five seconds. Timeouts and transport failures are MCP errors. Timing out does not undo a request the endpoint already received.

Response bodies are bounded to 128 KiB during streaming. The JDK rejects oversized header sections before handing a response to the application, which also checks the 16 KiB limit after parsing. A parser rejection returns `API_TRANSPORT_ERROR` without an HTTP status. The client requests identity encoding and rejects compressed responses. A completed response reports its byte count and kind (`JSON`, `INVALID_JSON`, `TEXT`, `EMPTY`, or `OTHER`). JSON is recognized by `application/json` or an `application/*+json` media type. Its preview contains only node types and container sizes, with up to four children per container and two levels below the root. Plain text and other body contents are omitted. JSON parsing also limits depth, tokens, and the sizes of names, strings, and numbers.

### JSON assertions and retention

- Start with `$` for the root. Use at most 24 dot properties or zero-based array indexes, such as `$.data.items[0].name`. A property starts with an ASCII letter or underscore, followed by ASCII letters, digits, underscores, or hyphens. Indexes use up to six digits with no leading zeroes. Quoted properties, wildcards, filters, recursive descent, and expressions are unsupported.
- An absent property, wrong container type, or out-of-range index is missing. JSON `null` exists and equals explicit `"expected": null`. A missing node fails `equals`, `contains`, and `type`. Malformed JSON, non-JSON or empty content, and unsupported paths return `ERROR`. A normal mismatch returns `FAIL` with MCP success.
- `equals` compares JSON values structurally. Object member order is irrelevant and array order matters. Numbers compare by numeric value so `1` and `1.0` are equal. Floating-point expectations have the precision supplied by MCP argument decoding. `contains` checks a case-sensitive substring for two strings or structural membership in an array. `type` accepts `object`, `array`, `string`, `number`, `boolean`, and `null`.
- Recognizable credential fields, such as `access_token` and `password`, are removed before storing parsed JSON, and direct assertions on protected fields fail. All values and member names are omitted from previews. Other parsed values stay in process memory for assertions, whose outcomes can reveal facts about the response. Use a trusted MCP client and fixture data without secrets in unexpected fields.
- History keeps the latest 32 completed API attempts, including failures after validation, and the latest 32 assertions per request. Entries become unavailable after 15 minutes and are pruned on history access. The client bounds simultaneous HTTP exchanges to four. API history is process-local and stays outside browser execution reports, JSON/HTML exports, and screenshot evidence.

### Combined API and browser example

Assume the loopback fixture serves `{"ready":true}` as JSON at `/api/health`, and a page titled `Test Dashboard` at `/dashboard`. Launch a browser using `launch_browser` with `{"browser":"chrome"}`, then call these tools over the same MCP connection.

| Call | Example arguments |
| --- | --- |
| `send_api_request` | `{"method":"GET","url":"http://127.0.0.1:8080/api/health","timeoutSeconds":5}` |
| `assert_api_status` | `{"requestId":"<returned UUID>","expectedStatus":200}` |
| `assert_api_response` | `{"requestId":"<returned UUID>","jsonPath":"$.ready","assertionType":"equals","expected":true}` |
| `navigate` | `{"url":"http://127.0.0.1:8080/dashboard"}` |
| `assert_page_title` | `{"expected":"Test Dashboard"}` |
| `get_api_execution_results` | `{"requestId":"<returned UUID>"}` |

A completed send includes fields such as `{"status":"COMPLETED","statusCode":200,"response":{"kind":"JSON","bytes":14,"shape":{"type":"object","size":1,"children":[{"type":"boolean"}]}}}`. The assertion results each include `"status":"PASS"`. The history result includes both outcomes in `assertions`. Close the browser with `close_browser` when finished.

## Architecture and operating limits

The codebase is organized by responsibility under `dev.seleniummcp`:

- `dev.seleniummcp` (`Main`): connects the official Java SDK's synchronous server to stdio and handles shutdown/disconnects.
- `dev.seleniummcp.protocol` (`ProtocolInput`): enforces the pre-decoding 4 MiB JSON-RPC message boundary.
- `dev.seleniummcp.tools` (`BrowserTools`, `AdvancedTools`, `AssertionsTool`, `BarcodeTools`): defines the 32 MCP tool schemas, validates calls, maps errors, and runs bounded explicit waits.
- `dev.seleniummcp.api` (`ApiTools`, `ApiRequestValidator`, `ApiClient`, `ApiAssertions`, `ApiExecutionHistory`): registers four API tools, validates origin and request inputs, bounds HTTP exchanges, evaluates JSON assertions, and retains sanitized process-local results.
- `dev.seleniummcp.engine` (`ScenarioEngine`, `DataDrivenEngine`): prevalidates and executes sequential and parameterized data-driven scenarios under shared session locking and scenario deadlines.
- `dev.seleniummcp.browser` (`BrowserSession`, `BrowserEvidence`): owns WebDriver lifecycle, serializes commands with teardown, and captures viewport-verified screenshots and redacted console diagnostics.
- `dev.seleniummcp.reporting` (`ExecutionHistory`, `HtmlReport`): retains bounded process-local execution history and exports redacted JSON and standalone HTML reports.
- `dev.seleniummcp.security` (`Origins`): enforces allowed-origin checks across top-level navigation and active frame documents.
- `dev.seleniummcp.support` (`ArtifactBudget`, `ToolFailure`): manages private local artifact quotas, SHA-256 ownership receipts, and structured error mapping.

Server shutdown calls `quit()` on the owned driver. A forced process kill can bypass JVM cleanup.

`Origins` checks the scheme, host, and effective port against the comma-separated `SELENIUM_ALLOWED_ORIGINS` setting. An empty setting denies navigation. To use a local fixture, explicitly include its origin, such as `http://127.0.0.1:8080`. The server checks the top-level URL before page tools and after navigation or clicks. Page operations also check the active document URL inside frames. Redirects, subresources, pop-ups, and page scripts can initiate network requests independently. Use trusted sites and apply network isolation when browser egress needs enforcement. Page content and screenshots are visible to the connected client.

## Tests

`mvn test` runs validation, session-state, and API fixture tests. To exercise the browser and mixed API/UI workflow with a real headless browser and temporary loopback HTTP fixtures, run one of:

```powershell
mvn "-Dbrowser.test=chrome" test
mvn "-Dbrowser.test=firefox" test
mvn "-Dbrowser.test=edge" test
```

The fixtures exercise text entry, click effects, discovery, explicit waits, selects, hover, scroll, frame and window transitions, blocked origins, PNG bytes, barcode decoding and assertions across `<img>`/`<svg>`/`<canvas>`, parameterized data-driven scenarios with cookie/storage isolation and redaction, upload rejection, and cleanup. Test reports appear under `target/surefire-reports`.

## Release build and local client

For a release installation, download `selenium-mcp-0.3.0.jar` and `selenium-mcp-0.3.0.jar.sha256` into the same directory. Compare the JAR's SHA-256 hash with the value in the checksum file before launching it with Java 21. [v0.3.0 release](https://github.com/Nayan76/selenium-ai-mcp-server/releases/tag/v0.3.0)

Use JDK 21 and Maven 3.9.9 for repeatable local builds. From a fresh checkout, run `mvn -B -ntp clean verify -Dbrowser.test=chrome` (quote `"-Dbrowser.test=chrome"` in PowerShell). The build produces `target/selenium-mcp-0.3.0.jar` and the identical compatibility copy `target/selenium-mcp.jar`. Build and test from the same source, dependency versions, JDK and Maven distribution when comparing release checksums. Maven uses a fixed archive timestamp. The shaded JAR includes the service-provider descriptors and third-party license and notice text.

Launch directly on Linux:

```sh
SELENIUM_ALLOWED_ORIGINS=https://example.com java -jar target/selenium-mcp-0.3.0.jar
sha256sum target/selenium-mcp-0.3.0.jar
```

Launch directly on Windows:

```powershell
$env:SELENIUM_ALLOWED_ORIGINS = 'https://example.com'
java -jar target/selenium-mcp-0.3.0.jar
Get-FileHash target/selenium-mcp-0.3.0.jar -Algorithm SHA256
```

For a desktop MCP client, copy the `mcpServers.selenium` entry from `inspector.json`. Set its second `args` item to the absolute installed JAR path (for example `/opt/selenium-mcp/selenium-mcp-0.3.0.jar` or `C:/Tools/selenium-mcp/selenium-mcp-0.3.0.jar`). Set trusted browser and API origins separately in the entry's `env` object. Restart the client and list tools. Version 0.3.0 advertises 36 tools.

### Operational boundaries

- A stdio message is limited to 4 MiB before JSON decoding. Oversize input closes the connection. The SDK logger namespace is disabled because SDK debug and parse-error diagnostics can include raw request data. Application logs use stderr. Keep browser and HTTP driver wire tracing disabled when entering sensitive values.
- Browser authorization checks compare exact scheme, host and effective port; use canonical numeric addresses when intentionally allowing a loopback or private service. A redirect request can already have reached its destination before WebDriver reports the final URL. Apply a network firewall or controlled outbound proxy for SSRF containment, including DNS changes, redirects, frames, downloads and subresources. Use a dedicated OS account and temporary browser profiles for untrusted pages.
- Set the artifact root on a private **local** filesystem with reliable OS file locks and atomic rename. Phase 5 checks a SHA-256 ownership receipt before removing a generated file. Unknown files, changed content and pre-Phase-5 directories are left for operator review. The quota covers payload bytes; staging files, receipts, locks, abandoned partial writes and filesystem overhead require additional disk space. Cleanup runs while a server is active. Empty directories can remain after retention cleanup.
- History and tool access are process-local. Anyone who can read the artifact directory can read saved screenshots. Protect the directory with OS permissions and keep it outside a shared checkout.

### CI and troubleshooting

GitHub Actions runs the JDK 21 Chrome suite on pushes and pull requests. A manual dispatch can additionally select Firefox or Edge. The workflow has read-only repository access and pins actions by commit. It uploads test-count summaries and the verified versioned JAR. It excludes evidence, profiles, raw driver logs and full Surefire XML from uploads.

To run the CI advisory check locally with PowerShell 7:

```powershell
mvn -B -ntp org.apache.maven.plugins:maven-dependency-plugin:3.7.0:tree "-DoutputType=json" "-DoutputFile=target/dependencies.json"
./scripts/audit-dependencies.ps1
```

Run the packaged protocol smoke test with Node.js using `node scripts/smoke-stdio.mjs`. It initializes an actual Java process, checks all 36 tool names and a structured error, exercises the four API tools against a temporary loopback fixture, and verifies shutdown on stdin EOF.

The check queries OSV with public Maven package coordinates, including transitives and test libraries. It fails on advisory matches or an incomplete response. Browser binaries, build plugins and npm tools need their own update policy.

| Symptom | What to check |
| --- | --- |
| Client fails to initialize | Confirm JDK 21, absolute JAR path and stdio transport. Launch Java directly and keep stdout reserved for MCP. |
| Browser launch fails | Install the chosen browser and a compatible driver or let Selenium Manager populate its cache. Verify access to the cache and temporary directories. |
| Origin or screenshot blocked | Check the effective port, final redirected URL and every frame in the viewport. Add only trusted origins. |
| Artifact unavailable | Check directory permissions, lock contention, storage budget, age, and availability of atomic moves. A modified file may require operator review. |
| CDP version warning | Selenium's optional DevTools modules track Chromium major versions. The tools use standard WebDriver and capability-checked browser logging. Use a supported browser/driver combination for features that require CDP. |
| Shade reports overlapping manifests | Dependencies each carry their own manifest. The launcher supplies one executable manifest and merges service entries and notices. Module descriptors and original archive signatures are excluded from the combined classpath JAR. |

## References

- [Official MCP Java SDK server documentation](https://java.sdk.modelcontextprotocol.io/latest/server/)
- [Official MCP Java SDK 1.1.5 sources](https://github.com/modelcontextprotocol/java-sdk/tree/v1.1.5)
- [MCP Inspector](https://github.com/modelcontextprotocol/inspector)
- [Selenium explicit waits](https://www.selenium.dev/documentation/webdriver/waits/)

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for the full text.
