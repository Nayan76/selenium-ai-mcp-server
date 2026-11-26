# Troubleshooting

## Read-only startup diagnostics

With v0.6.0 or later, run `java -jar "absolute path to selenium-mcp-0.6.0.jar" --doctor` with the same working directory and environment as the client. Output goes to stderr. The check reads Java and platform metadata, looks for browser executables, parses browser and API origin lists, and checks the private saved-profile file and store directory using the server's filesystem policy.

| Exit | Meaning |
| --- | --- |
| 0 | No failing checks. Optional features may report WARN or SKIP. |
| 1 | A runtime or supplied configuration check failed. |
| 2 | Extra arguments were passed after `--doctor`. |

Java versions below 21 can fail in the JVM launcher before Doctor runs.

Doctor does not initialize MCP, dispatch HTTP, run Selenium Manager, launch a browser, resolve secrets, create files, change permissions, or recover the store. Browser discovery checks common installation directories and can miss custom installs. On Windows it skips network drives and reparse points. Store checks cover the configuration schema, directory security and basic access. Store records, locks, atomic rename, endpoint reachability, driver compatibility and secret availability are checked in their normal operation when applicable. Run the server without `--doctor` for MCP.

| Symptom | What to check |
| --- | --- |
| Client cannot initialize | Confirm Java 21, checksum, absolute executable and JAR paths, separate arguments, and the client's server stderr. Normal startup waits on stdin. |
| `API_RUNTIME_UNSUPPORTED` | Set the client executable to Java 21.0.5 or newer within Java 21 and reconnect. |
| `API_CONFIGURATION_INVALID` or origin denied | Check the exact scheme, IPv4 address and port against the [API allowlist](../README.md#rest-api-testing). Plain HTTP requires the explicit loopback opt-in and exactly `127.0.0.1`. |
| Browser launch fails | Install the selected browser and let Selenium Manager resolve its driver, or provision a compatible driver. |
| `STORE_UNAVAILABLE` | Check absolute paths, working directory, ownership, ancestor permissions, and platform. See [secure storage and recovery](../README.md#private-storage-and-recovery). |
| `ENVIRONMENT_DISABLED` or `ROUTE_NOT_ALLOWED` | Check operator configuration. Staging and mutating routes require separate explicit permission. |
| `SECRET_UNAVAILABLE` or `SECRET_INVALID` | Check the server process's environment through the operator. Keep values out of diagnostics and files. |
| `STORE_BUSY` or `STORE_CORRUPT` | Follow [storage recovery](../README.md#private-storage-and-recovery). After an interrupted save, list collections before retrying. |
