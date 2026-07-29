# AgentScope Go

**AgentScope Go 是用于构建智能体式 LLM 应用的 Go 框架。**

AgentScope Go 提供智能体开发需要的基础组件：模型调用、消息协议、工具调用、运行状态、权限控制、Sandbox 沙箱和事件化执行流程。它采用显式包结构和类型化 API，方便在 Go 服务中组合、测试和扩展。

AgentScope Go 适合在 Go 服务中嵌入 Agent 能力。应用可以只使用统一模型接口，也可以组合工具、权限、Sandbox 沙箱和中间件构建完整的多轮智能体。部署、模型服务、凭据和业务数据仍由应用侧管理。

```{mermaid}
flowchart LR
    App["Go 应用"] --> Model["model<br/>模型调用"]
    App --> Agent["agent<br/>智能体循环"]
    Agent --> Message["message<br/>消息和内容块"]
    Agent --> State["state<br/>会话状态"]
    Agent --> Model
    Agent --> Tool["tool<br/>工具注册与执行"]
    Agent --> Permission["permission<br/>执行前决策"]
    Agent --> Sandbox["Sandbox 沙箱<br/>文件、Shell、Skill、MCP"]
    Agent --> Middleware["middleware<br/>Hook 扩展"]
    Tool --> Message
    Model --> Message
    Sandbox --> Tool
```

## 适用场景

AgentScope Go 更适合以下场景：

- 在 Go 后端服务中接入一个或多个 LLM 供应商。
- 让模型调用业务函数、文件工具、MCP 工具或任务工具。
- 需要把智能体执行过程通过事件流返回给前端或服务调用方。
- 需要对工具执行做权限确认、审计、上下文压缩或运行环境隔离。

一次模型补全或简单文本处理通常只需要 `model.ChatModel`。多轮工具调用、权限确认和事件流式输出适合由 Agent 接管执行流程。

## 环境要求

- Go 1.26.4 或更高版本。
- 运行真实模型示例时，需要对应模型服务的 API Key。
- DashScope 示例默认读取 `AI_DASHSCOPE_API_KEY`。

## 文档入口

- 功能边界：[功能总览](quickstart/feature-overview.md)
- 安装与验证：[安装](quickstart/installation.md)
- 核心概念：[核心概念](quickstart/key-concepts.md)
- Agent 构建：[构建智能体](quickstart/agent.md)
- 完整示例：[示例](task/examples.md)

## 最小示例

```go
package main

import (
	"context"
	"fmt"
	"os"

	"github.com/yuluo-yx/agentscope-go/pkg/message"
	asmodel "github.com/yuluo-yx/agentscope-go/pkg/model"
	"github.com/yuluo-yx/agentscope-go/pkg/model/dashscope"
)

func main() {
	chat, err := dashscope.NewChatModel(
		dashscope.NewCredential(os.Getenv("AI_DASHSCOPE_API_KEY")),
		"qwen-plus",
	)
	if err != nil {
		panic(err)
	}

	user, err := message.NewUserMessage("user", "Say hello in one short sentence.")
	if err != nil {
		panic(err)
	}

	response, err := chat.Call(context.Background(), asmodel.CallRequest{
		Messages: []*message.Message{user},
	})
	if err != nil {
		panic(err)
	}

	if text := response.GetTextContent(); text != nil {
		fmt.Println(*text)
	}
}
```

## 包结构

### 核心入口

| 包 | 用途 |
| --- | --- |
| `github.com/yuluo-yx/agentscope-go` | 常用核心 API 的兼容别名入口 |
| `agent` | ReAct 风格智能体循环和中间件 Hook |
| `loop` | Loop Engineering 目标、预算、验证、状态和事件 API |
| `message` | 消息和内容块协议 |
| `model` | 聊天模型接口和供应商实现 |
| `tool` | 工具接口、Toolkit、函数适配器和工具组 |
| `permission` | 权限模式、规则、决策和执行引擎 |
| `state` | AgentState、ToolContext 和 TaskContext |
| `workspace` | Sandbox 沙箱和内容卸载能力 |
| `team` | 本地多 Agent 团队管理、成员创建和消息收件箱 |

### 工具和沙箱

| 包 | 用途 |
| --- | --- |
| `tool/builtin` | Bash、Edit、Glob、Grep、Read、Write 和 ResetTools |
| `tool/mcp` | MCP 客户端集成和 MCP 工具适配器 |
| `tool/task` | TaskCreate、TaskGet、TaskList 和 TaskUpdate |
| `tool/skill` | 本地 `SKILL.md` 加载器 |
| `workspace/local` | 本地沙箱后端 |
| `workspace/docker` | Docker 沙箱后端 |
| `workspace/microsandbox` | 本地 Microsandbox microVM 沙箱后端 |
| `workspace/agentsandbox` | Kubernetes Agent Sandbox 后端 |
| `workspace/gateway` | 沙箱与宿主侧 MCP gateway 连接能力 |

### 模型和多模态能力

| 包 | 用途 |
| --- | --- |
| `model/anthropic` | Anthropic 聊天模型适配 |
| `model/dashscope` | DashScope 聊天模型适配 |
| `model/deepseek` | DeepSeek 聊天模型适配 |
| `model/gemini` | Gemini 聊天模型适配 |
| `model/moonshot` | Moonshot 聊天模型适配 |
| `model/ollama` | Ollama 聊天模型适配 |
| `model/openai` | OpenAI Chat Completions 适配 |
| `model/openairesponse` | OpenAI Responses 适配 |
| `model/xai` | xAI 聊天模型适配 |
| `model/zhipu` | 智谱聊天模型适配 |
| `embedding` | Embedding 模型接口、缓存和文件缓存 |
| `embedding/dashscope` | DashScope Embedding 适配 |
| `embedding/gemini` | Gemini Embedding 适配 |
| `embedding/ollama` | Ollama Embedding 适配 |
| `embedding/openai` | OpenAI Embedding 适配 |
| `audio/tts` | 文本转语音接口、请求和响应类型 |
| `audio/tts/dashscope` | DashScope TTS 适配 |
| `audio/stt` | 批量和实时语音转文本接口、请求、响应和 Session 类型 |
| `audio/stt/dashscope` | DashScope 批量 STT 和 Qwen-ASR realtime STT 适配 |

### 扩展和基础设施

| 包 | 用途 |
| --- | --- |
| `middleware` | 预算、记忆、事件转换、TTS、工具结果卸载和追踪中间件 |
| `middleware/otel` | OpenTelemetry 追踪桥接 |
| `extensions/vectorstore` | 可选向量库接口、内存向量库、Indexer 和 Retriever |
| `extensions/memory/vectorstore` | 基于向量库的长期记忆存储适配 |
| `credential` | 供应商凭据、模型发现和模型卡片聚合 |
| `errors` | AgentScope 框架错误类型 |
| `types` | 模型、工具和 Agent 边界共享的轻量类型 |
| `utils` | ID 生成和深拷贝等通用辅助能力 |

## 社区

- GitHub：[yuluo-yx/agentscope-go](https://github.com/yuluo-yx/agentscope-go)

## 许可证

AgentScope Go 使用 Apache License 2.0。
