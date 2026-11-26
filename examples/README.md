# Local workflows

Run `node examples/fixture.mjs` from the checkout with Node.js 22 or later. The fixture serves only synthetic data on `http://127.0.0.1:8080`. Stop it with Ctrl+C. Open one MCP connection per workflow and keep it open for the entire workflow. Use the [client configuration guide](../docs/mcp-clients.md) to pass the following environment settings to the server.

- Browser workflow only: `SELENIUM_ALLOWED_ORIGINS=http://127.0.0.1:8080`.
- API workflows only: `SELENIUM_API_ALLOWED_ORIGINS=http://127.0.0.1:8080` and `SELENIUM_API_ALLOW_LOOPBACK=true`. Use patched Java 21.
- For report exports, use a private writable `SELENIUM_ARTIFACT_DIR` and resolve returned report paths under it.

The packaged smoke uses the same [fixture](fixture.mjs) and [workflow inputs](workflows.mjs) on an ephemeral port. Run `node scripts/smoke-stdio.mjs "absolute path to selenium-mcp-0.6.0.jar" --examples`. Add `--browser-example` when Chrome and its driver are available. Add `--saved-fixture` only with the disposable empty private store and empty profile file described in [private storage](../README.md#private-storage-and-recovery). That option writes the test profile and saves fixture records.

## A. Browser and evidence

| Tool | Arguments |
| --- | --- |
| `launch_browser` | `{"browser":"chrome"}` |
| `navigate` | `{"url":"http://127.0.0.1:8080/"}` |
| `find_elements` | `{"selector":"#ready"}` |
| `assert_element_text` | `{"selector":"#ready","expected":"Fixture ready"}` |
| `take_screenshot` | `{}` |
| `close_browser` | `{}` |

Expect PASS and an inline PNG of the synthetic fixture. Close the browser even if a step fails.

## B. REST API

| Tool | Arguments |
| --- | --- |
| `send_api_request` | `{"method":"GET","url":"http://127.0.0.1:8080/api/health"}` |
| `assert_api_status` | `{"requestId":"<returned requestId>","expectedStatus":200}` |
| `assert_api_response` | `{"requestId":"<returned requestId>","jsonPath":"$.ready","assertionType":"equals","expected":true}` |
| `get_api_execution_results` | `{"requestId":"<returned requestId>"}` |

Expect two passing assertions.

## C. API collection and export

Call `execute_api_collection` with:

```json
{
  "collectionName": "Fixture read chain",
  "requests": [
    {
      "id": "first", "name": "Read ID", "method": "GET",
      "url": "http://127.0.0.1:8080/api/first",
      "assertions": [{"type":"status","expectedStatus":200}],
      "extract": {"itemId":"$.id"}
    },
    {
      "id": "second", "name": "Check item", "method": "GET",
      "url": "http://127.0.0.1:8080/api/items/{{itemId}}",
      "assertions": [{"type":"json","jsonPath":"$.name","assertionType":"equals","expected":"Fixture item"}]
    }
  ]
}
```

Expect two passing requests. Pass the returned `executionId` to `get_api_collection_results`, then call `export_api_collection_report` with that ID and `"format":"json"`. The report uses sanitized execution summaries.

## D. Saved collection

Provision a new private directory and operator-owned config file using the [platform-specific storage instructions](../README.md#private-storage-and-recovery). Put this JSON in the file and configure `SELENIUM_API_ENVIRONMENTS_FILE` and `SELENIUM_SAVED_COLLECTION_DIR` with absolute paths. Restart the server to load it.

```json
{
  "schemaVersion": 1,
  "profiles": {
    "dev": {
      "enabled": true,
      "origin": "http://127.0.0.1:8080",
      "routes": {"health":{"method":"GET","path":"/api/health"}}
    }
  }
}
```

1. Call `list_api_environments` with `{}`. Expect only `dev`.
2. Call `save_api_collection` with `{"name":"Saved health check","requests":[{"id":"health","name":"Check health","route":"health","expectedStatus":200}]}`.
3. Call `execute_saved_api_collection` with `{"collectionId":"<returned collectionId>","environmentId":"dev"}`. Expect one passing request.
4. Call `get_api_collection_results` with `{"executionId":"<returned executionId>"}` to retrieve the status-only result.

Each save creates a new immutable record and consumes a store slot. Use a disposable store for this workflow.
