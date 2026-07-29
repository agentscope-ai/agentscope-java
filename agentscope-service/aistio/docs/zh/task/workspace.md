# Sandbox 沙箱

`workspace/local.Workspace` 是本地沙箱实现，为工具和资源提供运行环境。

Sandbox 沙箱是 AgentScope Go 中 Agent 运行时和工具执行环境的抽象。它负责准备文件目录、暴露内置工具、加载 Skill、恢复 MCP 配置，并把过大的上下文或工具结果卸载到外部存储。相关 Go API 位于 `workspace` 包。

沙箱不是每个 Agent 都必需。只有当工具需要文件系统、Shell、Skill、MCP 持久化或大内容卸载时，才需要接入。

```{mermaid}
flowchart TD
    Workspace["Sandbox 沙箱"] --> Dirs["data / skills / sessions"]
    Workspace --> Tools["Bash / Read / Write / Edit / Glob / Grep"]
    Workspace --> Skills["Skill 摘要和 Skill 查看工具"]
    Workspace --> MCP["MCP 配置和 MCP 工具"]
    Workspace --> Offload["上下文、工具结果和 DataBlock 卸载"]
    Workspace --> Resources["workspace.BuildAgentResources"]
    Resources --> Agent["agent.WithAgentResources"]
    Workspace --> WithWorkspace["agent.WithWorkspace"]
    WithWorkspace --> Agent
```

## 什么时候需要 Sandbox 沙箱

适合使用沙箱的场景：

- Agent 需要读写文件、运行 Shell 或搜索目录。
- 需要把 `SKILL.md` 作为智能体可读取的操作说明。
- 需要把 MCP 配置和工具恢复放在一个工作环境中管理。
- 需要把 base64 数据块、大工具结果或旧上下文卸载到文件。
- 需要让同一套工具在本地、Docker 或 Agent Sandbox 运行时之间切换。

不需要沙箱的场景：

- 只有一次模型调用。
- 只有少量无副作用函数工具。
- 文件、任务和业务状态已经由应用服务管理，不希望暴露给模型。

## 后端选择

| 后端 | 包 | 适用场景 | 主要依赖 |
| --- | --- | --- | --- |
| 本地沙箱 | `workspace/local` | 本地开发、测试、普通服务端文件工具 | 本机文件系统 |
| Docker 沙箱 | `workspace/docker` | 需要容器隔离的文件和 Shell 工具 | Docker engine |
| Microsandbox 沙箱 | `workspace/microsandbox` | 需要本地 microVM 隔离的文件和 Shell 工具 | Microsandbox Go SDK、KVM 或 Apple Silicon |
| Agent Sandbox 后端 | `workspace/agentsandbox` | Kubernetes 中运行隔离工具任务 | Kubernetes、agent-sandbox controller 和 SandboxTemplate |
| Daytona 沙箱 | `workspace/daytona` | 使用 Daytona 托管或自托管沙箱执行文件和 Shell 工具 | Daytona API 与 Go SDK |

本地沙箱最容易开始。Docker 和 Agent Sandbox 适合隔离更强、工具执行风险更高或运行环境需要复现的场景。

## 本地沙箱

本地沙箱会初始化一个目录，并创建以下子目录：

- `data/`：工具使用的文件。
- `skills/`：本地 Skill。
- `sessions/`：卸载后的上下文和工具结果。

```go
ws, err := local.NewWorkspace("/tmp/agentscope-sandbox")
if err != nil {
	panic(err)
}
if err := ws.Initialize(ctx); err != nil {
	panic(err)
}
```

`Initialize` 是幂等的。使用 `agent.WithWorkspace(ctx, ws)` 时，如果沙箱尚未初始化，Agent 会自动初始化它。

## Docker 沙箱

`workspace/docker.Workspace` 会在 Docker 容器中执行内置工具。它适合本地隔离执行 Shell、文件读写和搜索任务。

```go
ws, err := docker.NewWorkspace(
	docker.WithImage("ubuntu:latest"),
	docker.WithHostWorkdir("/tmp/agentscope-docker-sandbox"),
)
if err != nil {
	panic(err)
}
```

当设置 `WithHostWorkdir` 时，offload、Skill 和 MCP 索引会写入宿主机 mirror 目录。
如果没有设置 `WithHostWorkdir`，Docker 后端仍可执行容器内工具，但 `OffloadContext`、`OffloadToolResult`、`OffloadDataBlock`、`AddSkill` 和 `RemoveSkill` 会返回需要 host workdir 的错误。

## Microsandbox 沙箱

`workspace/microsandbox.Workspace` 会通过 Microsandbox 官方 Go SDK 创建本地 microVM，并在 microVM 中执行 `Bash`、`Read`、`Write`、`Edit`、`Glob` 和 `Grep` 工具。

```go
ws, err := microsandbox.NewWorkspace(
	microsandbox.WithImage("python:3.12"),
	microsandbox.WithHostWorkdir("/tmp/agentscope-microsandbox-sandbox"),
)
if err != nil {
	panic(err)
}
if err := ws.Initialize(ctx); err != nil {
	panic(err)
}
```

前置条件：

- Linux 且启用 KVM，或 Apple Silicon macOS。
- Microsandbox runtime 资产可用。默认情况下，`Initialize` 会调用 `EnsureInstalled`，SDK 会把缺失资产下载到 `~/.microsandbox/`。

如果运行时已经安装且启动阶段不能下载资产，可以使用 `WithEnsureInstalled(false)`。如果需要在 `Close` 后保留 microVM 便于排查，可以使用 `WithKeepSandbox(true)`。

## Agent Sandbox 后端

`workspace/agentsandbox.Workspace` 会通过 agent-sandbox Go SDK 创建 Kubernetes `SandboxClaim`，并在 Agent Sandbox runtime 中执行 `Bash`、`Read`、`Write`、`Edit`、`Glob` 和 `Grep` 工具。

```go
ws, err := agentsandbox.NewWorkspace(
	agentsandbox.WithTemplateName("agent-sandbox-template"),
	agentsandbox.WithNamespace("default"),
	agentsandbox.WithHostWorkdir("/tmp/agentscope-agent-sandbox"),
)
if err != nil {
	panic(err)
}
if err := ws.Initialize(ctx); err != nil {
	panic(err)
}
```

前置条件：

- Kubernetes 集群可访问。
- agent-sandbox controller、extensions 和 sandbox-router 已安装。
- 当前 kubeconfig 有权限创建 `SandboxClaim`。
- 目标 namespace 中存在 `SandboxTemplate`，模板名称以集群配置为准。

连接模式：

- 默认：port-forward 模式，适合本地和 kind 测试。
- `WithAPIURL`：连接 sandbox-router direct URL。
- `WithGateway`：通过 Kubernetes Gateway API 连接。

`Write` 工具继续接受绝对路径。由于 agent-sandbox Go SDK 的 `Write()` 只接受普通文件名，AgentScope-Go 会先上传临时文件，再在 sandbox 内移动到目标绝对路径。

Agent Sandbox 后端的部署前置条件较多。已经在 Kubernetes 中运行 agent-sandbox，并且需要复用其隔离能力时，才适合选择该后端。普通本地开发优先使用本地或 Docker 沙箱。

## Daytona 沙箱

`workspace/daytona.Workspace` 会通过 Daytona 官方 Go SDK 创建或连接 Daytona sandbox，并在 Daytona runtime 中执行 `Bash`、`Read`、`Write`、`Edit`、`Glob` 和 `Grep` 工具。

```go
ws, err := daytona.NewWorkspace(
	daytona.WithImage("python:3.12"),
	daytona.WithHostWorkdir("/tmp/agentscope-daytona-sandbox"),
)
if err != nil {
	panic(err)
}
if err := ws.Initialize(ctx); err != nil {
	panic(err)
}
```

前置条件：

- Daytona 账号，或兼容的自托管 Daytona API。
- `DAYTONA_API_KEY`，或 Daytona SDK 支持的 JWT 环境变量组合。
- 可选 `DAYTONA_API_URL` 与 `DAYTONA_TARGET`，用于自定义 API 地址和 target/region。

默认情况下，新建的 Daytona sandbox 会在 `Close` 时删除。需要保留沙箱用于排查时，使用 `WithKeepSandbox(true)`；需要接入已有沙箱时，使用 `WithSandboxID` 或 `WithSandboxName`，关闭时只断开本地连接，不删除远端沙箱。

## 工具

`ListTools` 会暴露内置本地文件和 Shell 工具：

```go
tools, err := ws.ListTools(ctx)
```

当智能体需要使用沙箱支持的工具时，把这些工具注册到 Toolkit。

```go
tools, err := ws.ListTools(ctx)
if err != nil {
	panic(err)
}

kit, err := tool.NewToolkit(tools...)
if err != nil {
	panic(err)
}
```

Docker、Microsandbox 与 Agent Sandbox 后端会保留和本地沙箱一致的模型可见
`Bash`、`Read`、`Write`、`Edit`、`Glob`、`Grep` schema，但执行时会进入对应
后端运行时：Docker 工具通过 Docker engine 作用于沙箱容器，Microsandbox
工具通过 Microsandbox SDK handle 执行，Agent Sandbox 工具通过 sandbox handle
执行。这些工具调用不得回落到宿主机执行。

AgentScope Go 对内置沙箱工具采用类型化 runtime adapter：Docker
工具通过 Docker engine 执行，Microsandbox 工具通过 SDK handle 执行，Agent Sandbox 工具通过 sandbox handle 执行。
MCP server 仍可通过 `workspace/gateway.Server` 和宿主侧 gateway client 暴露。
依赖 Docker、Microsandbox 或 Agent Sandbox 的测试继续显式门控：
`AGENTSCOPE_TEST_DOCKER=1`、`AGENTSCOPE_TEST_MICROSANDBOX=1` 与 `AGENTSCOPE_TEST_AGENT_SANDBOX=1`。

## 与 Agent 组合

最简单的组合方式是使用 `agent.WithWorkspace`：

```go
ws, err := local.NewWorkspace("/tmp/agentscope-sandbox")
if err != nil {
	panic(err)
}

runner, err := agent.NewAgent(
	"Friday",
	"Use the sandbox when files are needed.",
	chat,
	agent.WithWorkspace(ctx, ws),
)
```

这个 Option 会完成以下事情：

1. 初始化沙箱。
2. 读取沙箱系统提示词。
3. 收集沙箱工具、MCP 工具和 Skill。
4. 创建 `Toolkit` 并注入 Agent。
5. 把沙箱设置为上下文、工具结果和 DataBlock 的 offloader。
6. 把沙箱根目录加入权限上下文的工作目录。

如果服务层想先检查资源或与其他工具合并，可以显式调用：

```go
resources, err := workspace.BuildAgentResources(ctx, ws)
if err != nil {
	panic(err)
}

runner, err := agent.NewAgent(
	"Friday",
	"Use prepared resources.",
	chat,
	agent.WithAgentResources(resources),
)
```

## Skills

使用 `local.WithSkillPaths` 预置 Skill：

```go
ws, err := local.NewWorkspace(
	"/tmp/agentscope-sandbox",
	local.WithSkillPaths("./skills/review"),
)
```

沙箱初始化时会把 Skill 复制或链接到 `skills/` 下。`BuildAgentResources` 会把 Skill 摘要写入系统提示词，并额外暴露 `Skill` 查看工具，让模型按需读取完整 `SKILL.md`。

Skill 是操作说明，不是函数调用。需要执行具体动作时，仍应通过工具或业务代码完成。

## MCP 管理

本地沙箱可以保存 MCP 配置，并在后续初始化时恢复：

```go
client, err := mcp.NewHTTPClient(
	"people",
	mcp.HTTPConfig{URL: "https://example.com/mcp"},
	mcp.WithEnabledTools("lookup_profile"),
)
if err != nil {
	panic(err)
}

ws, err := local.NewWorkspace(
	"/tmp/agentscope-sandbox",
	local.WithMCPs(client),
)
```

如果需要从 `.mcp` 索引恢复自定义 MCP 客户端，可以提供 `local.WithMCPClientFactory`。

## 卸载

Sandbox 沙箱可以把对话上下文和工具结果卸载到文件中。这样可以把大内容移出当前模型上下文，同时保留可追溯记录。

```go
path, err := ws.OffloadContext(ctx, "session-1", []*message.Message{user})
if err != nil {
	panic(err)
}
fmt.Println(path)
```

卸载常见于三类内容：

- 旧消息摘要：上下文压缩后保留引用。
- 超长工具结果：模型上下文中只保留摘要或引用。
- base64 数据块：把二进制内容转成 URL-backed `DataBlock`。

## 生命周期

Sandbox 沙箱生命周期由调用方管理：

- `Initialize`：创建目录、恢复 MCP、加载 Skill。
- `Close`：释放运行时资源或标记为不活跃，不保证删除持久化数据。
- `Reset`：清空沙箱拥有的数据、sessions、skills 和 MCP 索引。

服务端应用通常在请求或会话开始时初始化，在会话结束时关闭。是否重用同一个沙箱取决于隔离需求：共享沙箱可以复用文件和 Skill，独立沙箱更容易避免会话互相影响。

## 取舍建议

- 本地开发和单机服务：优先 `workspace/local`。
- 需要容器隔离：使用 `workspace/docker`，并配置 `WithHostWorkdir`。
- 需要本地 microVM 隔离：使用 `workspace/microsandbox`。
- 已部署 agent-sandbox：使用 `workspace/agentsandbox`。
- 只需要普通函数工具：不要引入沙箱。
- 需要 Agent 自动装配工具和 offloader：使用 `agent.WithWorkspace`。
- 需要先审查资源再装配：使用 `workspace.BuildAgentResources`。
