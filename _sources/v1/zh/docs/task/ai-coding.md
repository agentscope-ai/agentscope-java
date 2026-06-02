# 使用 AI 编程

AgentScope Java 文档支持 [`llms.txt` 标准](https://llmstxt.org/)，让 AI 编程助手能够理解框架文档并生成准确的代码。

## 什么是 llms.txt？

`llms.txt` 是一个专为大模型设计的文档索引文件，包含文档结构和关键页面说明，方便 AI 工具快速定位所需内容。

AgentScope 提供两个文件：

| 文件 | 说明 | URL |
|------|------|-----|
| `llms.txt` | 索引文件，包含各页面链接 | `https://java.agentscope.io/llms.txt` |
| `llms-full.txt` | 完整文档，单文件包含所有内容 | `https://java.agentscope.io/llms-full.txt` |

## 配置指南

### Claude Code

[Claude Code](https://docs.anthropic.com/en/docs/claude-code) 通过 MCP 服务器接入文档。

**安装：**

```bash
claude mcp add agentscope-docs -- uvx --from mcpdoc mcpdoc --urls AgentScopeJava:https://java.agentscope.io/llms.txt
```

**示例：**

安装后直接提问即可：

> 如何用 AgentScope Java 创建一个工具？

### Cursor

[Cursor](https://cursor.com/) 支持两种接入方式。

**方式一：添加文档（推荐）**

1. 打开 **Cursor Settings** -> **Features** -> **Docs**
2. 点击 **+ Add new Doc**
3. 填入：`https://java.agentscope.io/llms-full.txt`

**方式二：MCP 服务器**

1. 打开 **Cursor Settings** -> **Tools & MCP**
2. 点击 **New MCP Server** 编辑 `mcp.json`
3. 添加配置：

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

**示例：**

> 参考 AgentScope 文档，写一个带天气查询工具的 ReActAgent。

### Windsurf

[Windsurf](https://codeium.com/windsurf) 通过 MCP 服务器接入。

1. 打开设置，进入 MCP 配置
2. 添加服务器：

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

### 其他工具

**支持文档/知识库的工具：**

直接添加 `https://java.agentscope.io/llms-full.txt`

**支持 MCP 的工具：**

参考上述 MCP 配置模板

**环境要求：**

MCP 方式需要先安装 [`uv`](https://docs.astral.sh/uv/)。
