# Changelog

## 0.1.0 — Unreleased

- The Java 21 stdio server exposes 30 Selenium automation, assertion, barcode, scenario and reporting tools.
- Added Phase 7 website barcode decoding and validation using ZXing Core (`3.5.4`): `decode_page_barcode` and `assert_barcode_value` capture rendered `<img>`, `<svg>`, and `<canvas>` elements by CSS or XPath with viewport origin verification, decode `CODE_128`, `CODE_39`, `EAN_13`, `UPC_A`, `QR_CODE`, and `DATA_MATRIX`, and integrate `assert_barcode_value` with scenario evidence capture and redacted JSON/HTML reporting.
- Incoming JSON-RPC lines have a 4 MiB limit. Browser startup enforces certificate validation. Numeric host checks reject ambiguous address forms.
- Artifact maintenance applies configurable age and storage limits. SHA-256 ownership receipts, private session directories and a root lock protect files during cleanup. Reports identify expired evidence.
- Updated the official Java MCP SDK to 1.1.5, Selenium to 4.53.0 and the Jackson 3 BOM to 3.3.0. Added an OSV check for resolved Maven dependencies.
- Release packaging produces a versioned executable and the existing stable launcher name. CI runs browser regressions, a stdio smoke test and the dependency audit.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
