# Connect an MCP client

Install and verify the JAR first. Replace the sample absolute Java and JAR paths with yours. Pass `-jar` and the path as separate arguments. Spaces belong inside the path string. On Linux and macOS, use native paths such as `/opt/java21/bin/java` and `/home/developer/Selenium MCP/selenium-mcp-0.6.0.jar`.

All configurations use stdio. The process writes MCP messages to stdout and diagnostics to stderr. These examples explicitly deny API access. For the local workflows, opt in to the fixture's origin as described in [Examples](../examples/README.md). Keep credentials out of client config files and version control.

## Codex

Add this to your user `~/.codex/config.toml`, then start a new Codex session. Use `codex mcp list` to verify registration and `/mcp` in the CLI to check the connection. [Official Codex MCP configuration](https://learn.chatgpt.com/docs/extend/mcp?surface=app)

```toml
[mcp_servers.selenium]
command = 'C:\Tools\Java 21\bin\java.exe'
args = ['-jar', 'C:\Tools\Selenium MCP\selenium-mcp-0.6.0.jar']

[mcp_servers.selenium.env]
SELENIUM_ALLOWED_ORIGINS = ""
SELENIUM_API_ALLOWED_ORIGINS = ""
SELENIUM_API_ALLOW_LOOPBACK = "false"
```

## Claude Desktop

Open Settings → Developer → Edit Config. Merge the entry into `claude_desktop_config.json`, fully quit Claude Desktop, then reopen it. Check the server in the app's connectors or tools menu. [Official local MCP client guide](https://modelcontextprotocol.io/docs/develop/connect-local-servers)

```json
{
  "mcpServers": {
    "selenium": {
      "command": "C:\\Tools\\Java 21\\bin\\java.exe",
      "args": ["-jar", "C:\\Tools\\Selenium MCP\\selenium-mcp-0.6.0.jar"],
      "env": {
        "SELENIUM_ALLOWED_ORIGINS": "",
        "SELENIUM_API_ALLOWED_ORIGINS": "",
        "SELENIUM_API_ALLOW_LOOPBACK": "false"
      }
    }
  }
}
```

## VS Code

Use **MCP: Open User Configuration**. For a workspace-local configuration use `.vscode/mcp.json` and keep personal absolute paths out of Git. Start the server with **MCP: List Servers** and review the trust prompt. [Official VS Code MCP configuration](https://code.visualstudio.com/docs/copilot/customization/mcp-servers)

```json
{
  "servers": {
    "selenium": {
      "type": "stdio",
      "command": "C:\\Tools\\Java 21\\bin\\java.exe",
      "args": ["-jar", "C:\\Tools\\Selenium MCP\\selenium-mcp-0.6.0.jar"],
      "env": {
        "SELENIUM_ALLOWED_ORIGINS": "",
        "SELENIUM_API_ALLOWED_ORIGINS": "",
        "SELENIUM_API_ALLOW_LOOPBACK": "false"
      }
    }
  }
}
```

The installed Codex app-server connected over stdio and discovered all 45 tools on Windows with an isolated profile. VS Code 1.146.0 accepted registration in an isolated profile. The VS Code live connection, Codex desktop UI, and Claude Desktop are **untested**. Use the packaged stdio smoke and Inspector checks in the [project README](../README.md#verify-with-mcp-inspector) for local protocol verification.
