# Project guidelines

Use Java 21 and Maven. Keep MCP transport, tool contracts, and driver ownership separate.
Reserve stdout for the SDK. Send diagnostics to stderr and omit user input and page content.
Validate requests before touching a driver. Use bounded explicit waits. Serialize driver access
with teardown and quit every owned session. Keep browser profiles temporary.
Check both the top-level URL and active document before reading or interacting with a page.
Keep any browser-side scripts fixed and pass data as WebDriver arguments.
Prevalidate scenario plans before driver actions, share the session lock, and cap waits to the scenario deadline.
Keep assertion mismatches separate from tool errors. Retain only redacted execution diagnostics.
Keep execution history process-local and bound its retention. Generate artifact names on the server.
Validate the viewport before and after capturing evidence. Preserve the step outcome if evidence or diagnostics fail.
Keep artifact maintenance inside the private local root. Verify ownership receipts before eviction and protect active executions.
Keep protocol input bounded before decoding. Exercise the packaged stdio entry point and run the resolved dependency audit for releases.
Use read-only CI permissions and publish only explicitly selected test summaries and release JARs.
Run `mvn verify` after changes and use the Inspector config to check packaged stdio behavior.
Report which browsers were actually exercised. Keep development within the reviewed phase.
