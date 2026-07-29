# 模型上下文协议

`tool/mcp` 把 Model Context Protocol 服务连接到 AgentScope Go 工具系统。

MCP 适合复用已经存在的外部工具服务，例如搜索、数据库、浏览器、知识库或企业系统。AgentScope Go 不要求所有工具都做成 MCP。进程内 Go 函数可以直接用 `tool.NewFunctionTool`，只有当工具需要跨进程、跨语言、跨团队复用时，MCP 才更合适。

```{mermaid}
flowchart LR
    Server["MCP Server"] --> Client["tool/mcp Client"]
    Client --> ListTools["ListTools"]
    ListTools --> Wrap["包装为 tool.Tool"]
    Wrap --> Toolkit["tool.Toolkit / DeferredToolkit"]
    Toolkit --> Agent["agent.Agent"]
    Agent --> ToolCall["mcp__server__tool"]
    ToolCall --> Client
    Client --> CallTool["MCP tools/call"]
    CallTool --> Server
    Server --> Content["MCP 内容"]
    Content --> Blocks["TextBlock / DataBlock"]
    Blocks --> Agent
```

## 提供能力

- Stdio MCP 客户端。
- HTTP MCP 客户端，支持 SSE 或 Streamable HTTP 传输选择。
- 用于本地示例和测试的进程内 MCP 客户端。
- 有状态和无状态连接模式。
- 工具启用和禁用过滤。
- MCP 工具包装为 AgentScope `tool.Tool`。
- MCP 内容转换为 `message.TextBlock` 和 `message.DataBlock`。
- `DeferredToolkit` 延迟加载 MCP 工具 Schema，首次读取 Schema、查找工具或执行工具时才调用 `ListTools`。
- `WithTaskTTL` 会在 MCP `tools/call` 请求中写入标准 `task` 参数，让支持 task augmentation 的服务端按任务方式执行。

## 客户端类型

| 客户端 | 构造函数 | 适用场景 |
| --- | --- | --- |
| Stdio | `mcp.NewStdioClient` | 本地命令启动的 MCP server，例如 Node.js 或脚本工具服务 |
| HTTP | `mcp.NewHTTPClient` | 远端或本地 HTTP MCP 服务，支持 SSE 和 Streamable HTTP |
| In-process | `mcp.NewInProcessClient` | 测试、本地示例或把 `mark3labs/mcp-go` server 嵌入当前进程 |

Stdio 客户端必须是 stateful，因为它依赖持续运行的子进程。HTTP 客户端可以根据服务端能力选择 stateful 或 stateless。

```go
client, err := mcp.NewStdioClient(
	"filesystem",
	mcp.StdioConfig{
		Command: "npx",
		Args:    []string{"-y", "@modelcontextprotocol/server-filesystem", "/tmp/work"},
	},
	mcp.WithEnabledTools("read_file", "list_directory"),
)
if err != nil {
	panic(err)
}
if err := client.Connect(ctx); err != nil {
	panic(err)
}
defer client.Close()
```

HTTP 客户端可以显式指定传输，也可以用 `HTTPTransportAuto` 让客户端按 URL 形态选择：

```go
client, err := mcp.NewHTTPClient(
	"search",
	mcp.HTTPConfig{
		URL:       "https://example.com/mcp",
		Transport: mcp.HTTPTransportStreamable,
		Timeout:   30 * time.Second,
		Headers:   map[string]string{"Authorization": "Bearer " + token},
	},
	mcp.WithDisabledTools("delete_index"),
)
```

## 工具命名

MCP 工具使用稳定的命名规则：

```text
mcp__<server>__<tool>
```

例如，客户端名称为 `people`，原始工具名为 `lookup_profile`，暴露后的工具名是：

```text
mcp__people__lookup_profile
```

规则中的 `<server>` 来自 MCP 客户端名称，`<tool>` 来自原始 MCP 工具名。权限规则、事件流和模型 tool call 都使用包装后的名称。

## 进程内示例

```go
client, err := mcp.NewInProcessClient("people", server)
if err != nil {
	panic(err)
}
if err := client.Connect(ctx); err != nil {
	panic(err)
}
defer client.Close()

tools, err := client.ListTools(ctx)
if err != nil {
	panic(err)
}

kit, err := tool.NewToolkit(tools...)
```

完整可运行示例见 `example/tool/mcp`。该示例创建一个进程内 MCP server，把 `lookup_profile` 包装成 `mcp__people__lookup_profile`，并演示直接执行工具和真实 DashScope 模型工具调用闭环。

## 过滤工具

可以在创建客户端时限制 MCP 工具暴露范围：

```go
client, err := mcp.NewHTTPClient(
	"people",
	mcp.HTTPConfig{URL: "https://example.com/mcp"},
	mcp.WithEnabledTools("lookup_profile"),
)
```

`WithEnabledTools` 表示只暴露指定原始工具名。`WithDisabledTools` 表示隐藏指定原始工具名。两者适合把大 MCP 服务裁剪成当前 Agent 需要的最小工具集。

## 延迟加载

需要避免启动时立即拉取 MCP 工具列表时，可以使用 `DeferredToolkit`：

```go
kit, err := mcp.NewDeferredToolkit(client)
if err != nil {
	panic(err)
}

schemas, err := kit.ToolSchemas()
```

`DeferredToolkit` 会缓存包装后的工具。收到 MCP `tools/list_changed` 通知或应用层确认工具集变化后，可以调用
`kit.Invalidate()`，下一次读取 Schema、查找工具或执行工具时会重新加载。

延迟加载适合以下场景：

- MCP 服务启动较慢，不希望阻塞应用启动。
- 工具列表很大，只有部分请求会真正用到。
- MCP 服务支持 `tools/list_changed`，工具列表可能动态变化。

服务启动时验证 MCP 可用性，可以直接 `Connect` 并调用 `ListTools`。

## Task augmentation

需要让支持 task augmentation 的 MCP 服务端按任务方式处理工具调用时，创建客户端时传入 TTL：

```go
client, err := mcp.NewHTTPClient(
	"tasks",
	mcp.HTTPConfig{URL: "https://example.com/mcp"},
	mcp.WithTaskTTL(5*time.Minute),
)
```

`WithTaskTTL` 会把 TTL 转成毫秒并写入 `CallToolRequest.Params.Task`。如果服务端工具声明
`TaskSupportOptional` 或 `TaskSupportRequired`，服务端可以据此走任务执行路径；普通工具仍按常规工具调用返回。

## 权限行为

如果 MCP 工具声明 `readOnlyHint=true`，AgentScope Go 默认允许执行。其他 MCP 工具默认需要询问。

生产环境不应只依赖 MCP 服务端的说明文字判断风险。建议同时使用工具过滤和权限规则：只暴露当前 Agent 需要的工具，再对写入类工具设置明确的确认或允许范围。

## 与 Sandbox 沙箱集成

Sandbox 沙箱可以保存 MCP 配置，并在构建 Agent 资源时把 MCP 工具合并到同一个 `Toolkit`：

```go
ws, err := local.NewWorkspace(
	"/tmp/agentscope-sandbox",
	local.WithMCPs(client),
)
if err != nil {
	panic(err)
}

runner, err := agent.NewAgent(
	"Friday",
	"Use sandbox MCP tools when useful.",
	chat,
	agent.WithWorkspace(ctx, ws),
)
```

`agent.WithWorkspace` 会初始化沙箱，读取沙箱工具、MCP 工具和 Skill，并把它们装配到 Agent。需要自己控制资源装配时，可以先调用 `workspace.BuildAgentResources`，再用 `agent.WithAgentResources`。

## 取舍建议

- 本地 Go 函数：优先用 `tool.NewFunctionTool`。
- 已有 MCP 服务：用 `NewHTTPClient` 或 `NewStdioClient` 包装。
- 测试和示例：用 `NewInProcessClient`。
- 启动性能敏感：用 `NewDeferredToolkit`。
- 工具很多：用 `WithEnabledTools` 或 `WithDisabledTools` 裁剪。
- 需要持久化 MCP 配置：通过 Sandbox 沙箱管理。
