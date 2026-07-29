# 核心概念

AgentScope Go 围绕少量小接口组织。这些接口可以单独使用，也可以通过 `agent.Agent` 组合成完整智能体。

## 执行关系

一次带工具的 Agent 回复可以拆成下面几步：

1. 调用方把用户输入转换成 `message.Message`。
2. `Agent` 读取 `AgentState` 中的历史上下文和工具组状态。
3. `Agent` 从 `Toolkit` 取出模型可见的工具 Schema。
4. `ChatModel` 生成文本、思考块、工具调用或多模态数据块。
5. 如果模型请求工具，`Agent` 先调用权限引擎。
6. 权限通过后，`Toolkit` 执行具体工具并生成 `ToolResultBlock`。
7. `Agent` 把工具结果回填给模型，直到拿到最终回复或达到 ReAct 限制。

```{mermaid}
sequenceDiagram
    autonumber
    participant Caller as 调用方
    participant Agent as Agent
    participant State as AgentState
    participant Toolkit as Toolkit
    participant Model as ChatModel
    participant Permission as 权限引擎
    participant Tool as Tool
    participant Sandbox as Sandbox 沙箱

    Caller->>Agent: 提交用户 message.Message
    loop ReAct 循环
        Agent->>State: 读取历史上下文和工具组状态
        Agent->>Toolkit: 获取模型可见的工具 Schema
        Toolkit-->>Agent: 返回工具 Schema
        Agent->>Model: 发送消息、工具 Schema 和系统提示
        Model-->>Agent: 返回文本、思考块或 ToolCallBlock

        alt 模型请求工具
            Agent->>Permission: 检查工具调用权限
            Permission-->>Agent: 返回权限决策
            alt 权限通过
                Agent->>Toolkit: 执行 ToolCallBlock
                Toolkit->>Tool: 调用具体工具
                opt 工具需要运行环境
                    Tool->>Sandbox: 读写文件、Shell、Skill 或 MCP
                    Sandbox-->>Tool: 返回运行结果
                end
                Tool-->>Toolkit: 返回工具输出
                Toolkit-->>Agent: 生成 ToolResultBlock
                Agent->>Model: 回填工具结果
            else 权限未通过
                Agent-->>Caller: 返回权限结果或中断回复
            end
        else 模型给出最终回复
            Agent-->>Caller: 返回最终 message.Message
        end
    end
```

理解这个关系后，再看各个包会更清楚：`model` 只关心模型，`tool` 只关心能力注册与执行，`permission` 只做执行前决策，`workspace` 提供 Sandbox 沙箱运行环境，`agent` 负责把这些能力串起来。

## Message

`message.Message` 是对话单元，包含角色、发送者名称、时间戳、元数据和内容块列表。

常见内容块包括：

- `TextBlock`：文本内容。
- `ThinkingBlock`：模型的思考或推理内容。
- `HintBlock`：系统或中间件注入给模型的提示内容。
- `DataBlock`：Base64 或 URL 媒体数据。
- `ToolCallBlock`：模型请求执行的工具调用。
- `ToolResultBlock`：工具执行结果。

基础消息概念边界较直接：模型输入和输出都由消息列表表达，工具调用和工具结果也是内容块。

## Model

`model.ChatModel` 是聊天模型的统一接口：

```go
type ChatModel interface {
	Name() string
	Call(context.Context, CallRequest) (*ChatResponse, error)
	Stream(context.Context, CallRequest) (<-chan ChatResponse, error)
	CountTokens(CallRequest) (int, error)
}
```

模型供应商包负责把 AgentScope 的消息和工具 Schema 转换为各自 SDK 的格式。

普通聊天、摘要或分类可以直接使用 `ChatModel`。AgentScope Go 不要求所有模型调用都经过 Agent。

## Tool

`tool.Tool` 表达一个可调用能力。工具会暴露名称、描述、JSON Schema、权限行为和执行方法。

使用 `tool.NewToolkit` 注册工具，并执行模型返回的 `ToolCallBlock`。

工具既可以是进程内 Go 函数，也可以是内置文件工具、任务工具、Skill 工具或 MCP 工具。所有工具最终都会回到同一个 `Tool` 接口，因此权限、Agent 和模型 Schema 可以共用一套流程。

## Agent

`agent.Agent` 组合模型、工具提供者、状态、权限引擎和中间件 Hook。它处理以下循环：

1. 向模型发送消息。
2. 读取模型回复中的工具调用。
3. 检查工具权限。
4. 执行工具。
5. 追加工具结果。
6. 直到模型返回最终文本。

Agent 适合“模型需要决定后续动作”的任务。流程已经由业务代码确定时，手写模型和工具调用通常更简单。

## State

`state.AgentState` 保存运行期状态：

- 对话上下文。
- 权限上下文。
- 工具读取缓存。
- 任务上下文。
- 当前迭代计数。
- 当前上下文窗口压力状态。

状态对象是可变的。多个并发请求不要共享同一个 `AgentState`，除非这些请求需要共用上下文和权限规则。

## Sandbox 沙箱

`workspace/local.Workspace` 是本地沙箱实现，用于文件工具、Skill 加载、上下文卸载和工具结果卸载。

Sandbox 沙箱不是 Agent 的必需依赖。只有当工具需要文件系统、Shell、Skill、MCP 持久化或大内容卸载时，才需要接入。
