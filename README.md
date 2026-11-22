# Selenium MCP Server

Control one Chrome, Firefox, or Edge session from an MCP client over stdio. The 30 tools share a fresh browser profile and run headlessly by default.

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

Expect 30 tools from the first command and a structured `NO_SESSION` error from the second. Each CLI invocation launches a new process. For a sequence of browser operations, start the interactive Inspector and keep the connection open:

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

## Architecture and operating limits

`Main` connects the official Java SDK's synchronous server to stdio and handles disconnects. `BrowserTools` defines schemas, validates calls, maps errors, and runs explicit waits. `BrowserSession` serializes driver commands and teardown. Server shutdown calls `quit()` on the owned driver. A forced process kill can bypass JVM cleanup.

`Origins` checks the scheme, host, and effective port against the comma-separated `SELENIUM_ALLOWED_ORIGINS` setting. An empty setting denies navigation. To use a local fixture, explicitly include its origin, such as `http://127.0.0.1:8080`. The server checks the top-level URL before page tools and after navigation or clicks. Page operations also check the active document URL inside frames. Redirects, subresources, pop-ups, and page scripts can initiate network requests independently. Use trusted sites and apply network isolation when browser egress needs enforcement. Page content and screenshots are visible to the connected client.

## Tests

`mvn test` runs validation and session-state tests. To exercise all 30 tools using a real headless browser and a temporary loopback HTTP fixture, run one of:

```powershell
mvn "-Dbrowser.test=chrome" test
mvn "-Dbrowser.test=firefox" test
mvn "-Dbrowser.test=edge" test
```

The fixtures exercise text entry, click effects, discovery, explicit waits, selects, hover, scroll, frame and window transitions, blocked origins, PNG bytes, barcode decoding and assertions across `<img>`/`<svg>`/`<canvas>`, upload rejection, and cleanup. Test reports appear under `target/surefire-reports`.

## Release build and local client

Use JDK 21 and Maven 3.9.9 for repeatable local builds. From a fresh checkout, run `mvn -B -ntp clean verify -Dbrowser.test=chrome` (quote `"-Dbrowser.test=chrome"` in PowerShell). The build produces `target/selenium-mcp-0.1.0.jar` and the identical compatibility copy `target/selenium-mcp.jar`. Build and test from the same source, dependency versions, JDK and Maven distribution when comparing release checksums. Maven uses a fixed archive timestamp. The shaded JAR includes the service-provider descriptors and third-party license and notice text.

Launch directly on Linux:

```sh
SELENIUM_ALLOWED_ORIGINS=https://example.com java -jar target/selenium-mcp-0.1.0.jar
sha256sum target/selenium-mcp-0.1.0.jar
```

Launch directly on Windows:

```powershell
$env:SELENIUM_ALLOWED_ORIGINS = 'https://example.com'
java -jar target/selenium-mcp-0.1.0.jar
Get-FileHash target/selenium-mcp-0.1.0.jar -Algorithm SHA256
```

For a desktop MCP client, copy the `mcpServers.selenium` entry from `inspector.json`. Set its second `args` item to the absolute installed JAR path (for example `/opt/selenium-mcp/selenium-mcp-0.1.0.jar` or `C:/Tools/selenium-mcp/selenium-mcp-0.1.0.jar`). Set the exact trusted HTTP(S) origins in the entry's `env` object. Restart the client and list tools. A successful connection advertises 30 tools.

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

Run the packaged protocol smoke test with Node.js using `node scripts/smoke-stdio.mjs`. It initializes an actual Java process, checks all 30 tool names and a structured error, and verifies shutdown on stdin EOF.

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
