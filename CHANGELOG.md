# Changelog

## 0.3.0

- API requests require Java 21.0.5 or a later Java 21 update. The stdio entry point caps JDK response-header parsing at 16 KiB before HTTP client initialization. Browser tools remain available on older Java 21 runtimes.
- Added four REST API testing tools for HTTP requests, HTTP status assertions, restricted JSON assertions, and sanitized execution lookup. This release exposes 36 tools.
- API testing uses separate origin authorization, direct IPv4 connections, explicit development-only HTTP loopback access, and bounded response handling and history. API results can be used alongside browser tools on the same MCP connection.

## 0.2.0

- Organized the Java source into packages for browser sessions, tools, scenario execution, reporting, protocol handling, and security. Existing MCP tool contracts are unchanged.

- Added Phase 9 data-driven test execution (32 tools total):
  - `execute_data_driven_scenario` executes parameterized scenarios across bounded named datasets (1–20 datasets, 1–32 steps per dataset, up to 200 total steps, 1–120s timeout) using explicit `{{variable}}` placeholders restricted to safe data fields (`navigate.url`, `type_text.text`, and `assert_*.expected`) with single-pass resolution and pre-execution origin/schema validation.
  - Explicit inter-dataset browser isolation resets extra windows, active frame selection, cookies (including `HttpOnly`), `localStorage`, and `sessionStorage` on visited allowed origins, and aborts clearly with `DATASET_RESET_FAILED` if reset cannot be guaranteed.
  - `get_data_driven_results` returns aggregate and per-dataset `PASS` / `FAIL` / `ERROR` statuses, failure categories, step counts, execution timings, redacted diagnostics, verified evidence references, and exported report paths.
  - Extended `ExecutionHistory`, `ArtifactBudget` receipts, and standalone `HtmlReport` exports to support numbered data-driven evidence (`<uuid>-d<dataset>-<step>.png`) and per-dataset JSON/HTML reporting without leaking sensitive variable values.

## 0.1.0 — 2026-04-19

- The Java 21 stdio server exposes 30 Selenium automation, assertion, barcode, scenario and reporting tools.
- Added Phase 7 website barcode decoding and validation using ZXing Core (`3.5.4`): `decode_page_barcode` and `assert_barcode_value` capture rendered `<img>`, `<svg>`, and `<canvas>` elements by CSS or XPath with viewport origin verification, decode `CODE_128`, `CODE_39`, `EAN_13`, `UPC_A`, `QR_CODE`, and `DATA_MATRIX`, and integrate `assert_barcode_value` with scenario evidence capture and redacted JSON/HTML reporting.
- Incoming JSON-RPC lines have a 4 MiB limit. Browser startup enforces certificate validation. Numeric host checks reject ambiguous address forms.
- Artifact maintenance applies configurable age and storage limits. SHA-256 ownership receipts, private session directories and a root lock protect files during cleanup. Reports identify expired evidence.
- Updated the official Java MCP SDK to 1.1.5, Selenium to 4.53.0 and the Jackson 3 BOM to 3.3.0. Added an OSV check for resolved Maven dependencies.
- Release packaging produces a versioned executable and the existing stable launcher name. CI runs browser regressions, a stdio smoke test and the dependency audit.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
