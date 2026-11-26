# Install Selenium MCP 0.6.0

Install a [Java 21 runtime](https://adoptium.net/temurin/releases/?version=21) for your platform. Java 21.0.5 or later in the Java 21 line is required for API requests and collection execution. Earlier Java 21 builds can run browser tools. Prefer the latest stable Java 21 update. Running the release JAR requires neither Maven nor a JDK compiler.

Download both `selenium-mcp-0.6.0.jar` and its `.jar.sha256` companion from the [official v0.6.0 release](https://github.com/Nayan76/selenium-ai-mcp-server/releases/tag/v0.6.0). Save them together in a directory you own, such as `$HOME/Selenium MCP`. Verify before running. The commands below assume that directory is current.

Windows PowerShell:

```powershell
$jar = (Resolve-Path -LiteralPath './selenium-mcp-0.6.0.jar').Path
$expected = ((Get-Content -Raw -LiteralPath "${jar}.sha256").Trim() -split '\s+')[0]
if ($expected -notmatch '^[0-9a-fA-F]{64}$' -or
    (Get-FileHash -Algorithm SHA256 -LiteralPath $jar).Hash -ne $expected) {
    throw 'Checksum mismatch. Discard the download.'
}
java -version
```

Linux or macOS:

```sh
jar="$PWD/selenium-mcp-0.6.0.jar"
expected=$(awk 'NR == 1 {print $1}' "${jar}.sha256") &&
  actual=$(shasum -a 256 "$jar") &&
  [ "${actual%% *}" = "$expected" ] && [ "${#expected}" -eq 64 ] ||
  { echo 'Checksum verification failed. Check the download and checksum tool.' >&2; exit 1; }
java -version
```

On Linux, `sha256sum "$jar"` can replace `shasum -a 256 "$jar"`. Keep the absolute JAR path for [client setup](mcp-clients.md). You can also give the client an absolute Java executable path. A user-local Java archive needs no administrator rights or PATH changes.

For browser tools, install Chrome, Firefox, or Edge in the same OS as Java. Selenium Manager normally resolves a compatible driver and may use the network and a writable cache at first browser launch. For offline use, provision a matching driver on the server process's PATH. Set the exact authorized browser origins explicitly. API access has its own opt-in [allowlist and loopback rules](../README.md#rest-api-testing).

| Platform | Runtime and storage |
| --- | --- |
| Windows | Java 21. Saved collections require a private fixed NTFS/ReFS location and protected DACLs. |
| Linux / WSL | Java 21. Use Linux-native Java, browser and driver. Provision saved storage on a private Linux-native filesystem. |
| macOS | Java 21. Browser and API startup are untested in the v0.6.0 review. Saved storage is unavailable. |

After checksum verification, run `java -jar "$jar" --doctor` for read-only preflight checks. The flag was introduced in v0.6.0. See [troubleshooting](troubleshooting.md) for exit codes and limitations.
