# 安装

AgentScope Go 是标准 Go Module，可在现有 Go 项目中通过 `go get` 引入。

## 环境要求

- Go 1.26.4 或更高版本。当前版本以仓库根目录的 `go.mod` 为准。
- 下载 Go Module 时需要网络访问。
- 运行真实模型调用时，需要对应模型服务的 API Key。
- 运行 Docker 沙箱示例时，需要本机 Docker 可用。
- 运行 Agent Sandbox 后端示例时，需要可访问的 Kubernetes 集群和 agent-sandbox 组件。

## 添加依赖

```bash
go mod init example.com/my-agent
go get github.com/yuluo-yx/agentscope-go
```

现有项目接入模型调用时，不需要额外生成配置文件。AgentScope Go 的主要入口都是 Go 构造函数和 Option。

## 配置模型 Key

DashScope 示例默认使用 `AI_DASHSCOPE_API_KEY`：

```bash
export AI_DASHSCOPE_API_KEY="your-key"
```

可以通过环境变量覆盖示例中的 DashScope 模型：

```bash
export AI_DASHSCOPE_MODEL="qwen3.7-max"
```

不同模型示例会读取各自供应商的环境变量。常见变量如下：

```bash
export AI_OPENAI_API_KEY="your-openai-key"
export AI_ANTHROPIC_API_KEY="your-anthropic-key"
export AI_DASHSCOPE_API_KEY="your-dashscope-key"
export AI_DEEPSEEK_API_KEY="your-deepseek-key"
export AI_GEMINI_API_KEY="your-gemini-key"
export AI_MOONSHOT_API_KEY="your-moonshot-key"
export AI_XAI_API_KEY="your-xai-key"
export AI_ZHIPU_API_KEY="your-zhipu-key"
```

Ollama 示例默认连接 `http://127.0.0.1:11434`。运行前需要先启动 Ollama，并拉取示例使用的模型。

## 验证仓库

在本仓库内开发时运行：

```bash
go test ./...
```

仓库也提供 Makefile 目标：

```bash
make fmt
make lint-go
make test
make docs-check
```

`make test` 会运行单元测试和本地 E2E profile。快速验证 Go 单元测试时，可以运行：

```bash
make test-unit
```

## 运行示例

每个示例目录都是独立 Go Module：

```bash
cd example/tool/mcp
go run .
```

模型相关示例通常会发起真实服务请求。没有对应 API Key 时，请先运行不依赖在线模型的示例，例如：

```bash
go run ./example/agent/basic
go run ./example/tool/function
go run ./example/workspace/local
```

需要模型供应商完整调用路径时，再进入 `example/model/*/chat`、`example/model/dashscope/embedding`、`example/model/dashscope/tts`、`example/model/dashscope/stt` 或 `example/model/dashscope/stt_microphone`。
