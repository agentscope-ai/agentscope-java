# Coding with AI

AgentScope Java documentation supports the [`llms.txt` standard](https://llmstxt.org/), providing a machine-readable index optimized for Large Language Models. This allows you to use the documentation as context in your AI-powered development environment.

## What is llms.txt?

`llms.txt` is a standardized text file that acts as a map for LLMs, listing the most important documentation pages and their descriptions. This helps AI tools understand the structure of the documentation and retrieve relevant information.

AgentScope provides the following files:

| File | Best For | URL |
|------|----------|-----|
| `llms.txt` | Tools that can fetch links dynamically | `https://java.agentscope.io/llms.txt` |
| `llms-full.txt` | Tools that need a single, static text dump | `https://java.agentscope.io/llms-full.txt` |

## Development Tools

### Claude Code

[Claude Code](https://docs.anthropic.com/en/docs/claude-code) can be configured to query the AgentScope documentation by adding an MCP server.

**Installation:**

```bash
claude mcp add agentscope-docs -- uvx --from mcpdoc mcpdoc --urls AgentScopeJava:https://java.agentscope.io/llms.txt
```

**Usage:**

Once installed, you can ask questions about AgentScope directly in Claude Code:

> How do I create a tool with AgentScope Java?

### Cursor

[Cursor](https://cursor.com/) IDE can be configured to access the AgentScope documentation in two ways.

**Method 1: Docs Feature (Recommended)**

1. Open **Cursor Settings** -> **Features** -> **Docs**
2. Click **+ Add new Doc**
3. Add URL: `https://java.agentscope.io/llms-full.txt`

**Method 2: MCP Server**

1. Open **Cursor Settings** -> **Tools & MCP**
2. Click **New MCP Server** to edit `mcp.json`
3. Add the following configuration:

```json
{
  "mcpServers": {
    "agentscope-docs": {
      "command": "uvx",
      "args": [
        "--from", "mcpdoc", "mcpdoc",
        "--urls", "AgentScopeJava:https://java.agentscope.io/llms.txt"
      ]
    }
  }
}
```

**Usage:**

Once configured, you can prompt the coding agent:

> Use the AgentScope docs to build a ReActAgent with a weather tool.

### Windsurf

[Windsurf](https://codeium.com/windsurf) supports MCP servers for documentation access.

**Configuration:**

1. Open Windsurf Settings
2. Navigate to MCP configuration
3. Add the following server:

```json
{
  "mcpServers": {
    "agentscope-docs": {
      "command": "uvx",
      "args": [
        "--from", "mcpdoc", "mcpdoc",
        "--urls", "AgentScopeJava:https://java.agentscope.io/llms.txt"
      ]
    }
  }
}
```

### Other Tools

Any tool that supports the `llms.txt` standard or can ingest documentation from a URL can benefit from these files.

**For tools with Docs/Knowledge Base feature:**
- Add URL: `https://java.agentscope.io/llms-full.txt`

**For tools with MCP support:**
- Use the MCP configuration template above with `mcpdoc`

**Prerequisites:**

MCP configurations require [`uv`](https://docs.astral.sh/uv/) to be installed, as they use `uvx` to run the documentation server.
