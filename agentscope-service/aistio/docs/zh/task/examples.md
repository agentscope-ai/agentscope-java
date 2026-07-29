# 示例

示例位于 `example/`。多数可运行叶子目录都是独立 Go Module，包含自己的 `go.mod`、`main.go`、`README.md` 和 `README-zh.md`。`loop/event-runner`、`loop/goal-runner` 这类根模块 package 通过仓库根模块测试。

示例按功能拆分，而不是按教程顺序拆分。模型、工具、Agent 和 Sandbox 沙箱示例可按目标单独运行；涉及在线模型的示例需要先配置对应供应商 API Key。

## 运行示例

```bash
cd example/tool/mcp
go run .
```

模型相关示例通常会发起真实服务请求。运行前需要设置对应供应商的 API Key。Ollama 示例需要本地 Ollama 服务和已拉取的模型。

不想配置在线模型时，可以先运行不发起在线模型请求的本地示例：

```bash
cd example/workspace/local
go run .
```

## 选择路线

```{mermaid}
flowchart TD
    Start["选择示例"] --> HasKey{"是否配置<br/>在线模型 Key？"}
    HasKey -- 否 --> Local["本地优先<br/>workspace/local<br/>或本地 Ollama"]
    HasKey -- 是 --> Goal{"目标能力"}
    Goal -- 模型供应商 --> Model["model/*/chat<br/>embedding / tts / stt"]
    Goal -- 工具开发 --> Tools["tool/function<br/>tool/builtin<br/>tool/mcp<br/>tool/task<br/>tool/skill"]
    Goal -- Agent 流程 --> Agent["agent/basic<br/>agent/configuration<br/>agent/context_strategy<br/>agent/permission"]
    Goal -- 服务集成 --> Integration["integration/gin<br/>integration/kratos"]
    Goal -- 隔离执行 --> Sandbox["workspace/local<br/>workspace/docker<br/>workspace/microsandbox<br/>workspace/agentsandbox"]
    Goal -- 观测和 Hook --> Hooks["agent/hooks<br/>agent/middleware_tracing<br/>o11y"]
```

| 目标 | 建议示例 |
| --- | --- |
| 了解消息结构 | `example/message` |
| 了解 Agent ReAct 循环 | `example/agent/basic` |
| 写自定义工具 | `example/tool/function` |
| 接 MCP 工具 | `example/tool/mcp` |
| 接文件和 Shell 工具 | `example/workspace/local`、`example/tool/builtin` |
| 理解权限确认 | `example/agent/permission` |
| 理解中间件 | `example/agent/hooks`、`example/agent/middleware_tracing` |
| 构建 Loop Engineering Agent | `example/loop/basic`、`example/loop/assisted-verifier` |
| 接真实模型 | `example/model/*/chat` |
| 接 HTTP 服务 | `example/integration/gin`、`example/integration/kratos` |
| 做隔离执行 | `example/workspace/docker`、`example/workspace/microsandbox`、`example/workspace/agentsandbox` |

## 示例矩阵

| 目录 | 用途 |
| --- | --- |
| `message` | system、user、assistant 消息组成的对话历史 |
| `model/anthropic/chat` | Anthropic ChatModel 非流式、流式、token 估算和工具调用闭环 |
| `model/dashscope/chat` | DashScope OpenAI-compatible ChatModel、多模态消息、token 估算和工具调用闭环 |
| `model/dashscope/embedding` | DashScope 文本向量模型，覆盖多输入 embedding 和维度配置 |
| `model/dashscope/stt` | DashScope 语音识别模型，读取本地 WAV 或 PCM 文件并输出批量或 realtime 识别文本 |
| `model/dashscope/stt_microphone` | DashScope Qwen-ASR realtime 麦克风示例，监听默认麦克风并实时输出控制台文本 |
| `model/dashscope/tts` | DashScope 语音合成模型，流式接收音频块并写入 `output.wav` |
| `model/deepseek/chat` | DeepSeek ChatModel 非流式、流式和工具调用闭环 |
| `model/gemini/chat` | Gemini ChatModel 多模态消息、token 估算、非流式、流式和工具调用闭环 |
| `model/moonshot/chat` | Moonshot ChatModel 多模态消息、token 估算、非流式、流式和工具调用闭环 |
| `model/ollama/chat` | 本地 Ollama ChatModel 非流式、流式和工具调用闭环 |
| `model/openai/chat` | OpenAI ChatModel 非流式、流式、代理 HTTP client 和工具调用闭环 |
| `model/xai/chat` | xAI ChatModel 多模态消息、token 估算、非流式、流式和工具调用闭环 |
| `model/zhipu/chat` | 智谱 AI ChatModel 非流式、流式、token 估算和工具调用闭环 |
| `agent/basic` | 使用 DashScope ChatModel 和任务工具的智能体示例 |
| `agent/team` | 进程内 leader/worker Agent team tools 与 inbox 投递 |
| `agent/configuration` | Agent model fallback、ReAct 配置和本地上下文清理 |
| `agent/context_strategy` | 摘要压缩、沙箱 offload 和自定义上下文策略 |
| `agent/external` | Agent 外部工具执行暂停与恢复流程 |
| `agent/hooks` | Agent middleware Hook 示例，覆盖 reply、reasoning、model call、acting 和 system prompt |
| `agent/middleware_tracing` | reply、model call 和 tool execution span 的 tracing middleware |
| `agent/permission` | Agent 权限确认与恢复流程 |
| `loop/basic` | report-only Loop Engineering Agent，演示目标、状态和事件 |
| `loop/assisted-verifier` | assisted Loop Engineering Agent，演示 verifier 和 maker/checker 分离 |
| `integration/gin` | Gin HTTP 集成，演示底层 ChatModel 流式和 Agent 事件流式 |
| `integration/kratos` | Kratos HTTP 集成，演示底层 ChatModel 流式和 Agent 事件流式 |
| `tool/function` | 自定义函数工具 |
| `tool/builtin` | 内置本地工具 |
| `tool/mcp` | MCP 客户端和通过 Toolkit 执行 MCP 工具 |
| `tool/task` | 任务工具用法 |
| `tool/skill` | 加载本地 `SKILL.md` |
| `workspace/local` | 本地沙箱工具、Skill 和卸载能力 |
| `workspace/docker` | Docker 沙箱工具、容器文件操作和 DashScope ChatModel 回复 |
| `workspace/microsandbox` | Microsandbox microVM 沙箱工具和 DashScope ChatModel 回复 |
| `workspace/agentsandbox` | Kubernetes Agent Sandbox 后端工具和可选 Agent 集成 |
| `o11y` | 轻量 tracing、middleware 事件和模型调用观测示例 |

## 真实模型调用

不同 provider 使用不同环境变量。常见变量如下：

```bash
export AI_OPENAI_API_KEY=your-openai-key
export AI_ANTHROPIC_API_KEY=your-anthropic-key
export AI_DASHSCOPE_API_KEY=your-dashscope-key
export AI_DEEPSEEK_API_KEY=your-deepseek-key
export AI_GEMINI_API_KEY=your-gemini-key
export AI_MOONSHOT_API_KEY=your-moonshot-key
export AI_XAI_API_KEY=your-xai-key
export AI_ZHIPU_API_KEY=your-zhipu-key
```

OpenAI 示例额外支持 `AI_OPENAI_PROXY_URL`，用于本地代理访问。Ollama 示例默认连接 `http://127.0.0.1:11434`。

`example/model/dashscope/stt_microphone` 会访问本机默认麦克风，并通过 DashScope WebSocket 发起真实 realtime STT 请求。首次在 macOS 上运行时，需要允许当前终端或 IDE 访问麦克风。

## 示例文档

每个示例目录都有自己的 `README-zh.md`。主题页只解释入口和取舍；具体运行参数、输出样例和排错说明以示例目录内文档为准。
