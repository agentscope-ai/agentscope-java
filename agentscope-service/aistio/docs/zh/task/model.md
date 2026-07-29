# 模型集成

`model` 包定义统一模型接口。各模型供应商包负责把 SDK 的消息、工具 Schema、流式事件和 Token 统计适配到该接口。

模型层是 AgentScope Go 的最小可用层。普通聊天、摘要、分类或结构化抽取服务可以只使用 `ChatModel`，不需要引入 Agent。只有当模型需要自动调用工具、处理权限或产生执行事件时，才需要继续接入 `agent.Agent`。

```{mermaid}
flowchart LR
    App["业务代码"] --> Request["model.CallRequest"]
    Request --> Message["message.Message 列表"]
    Request --> Tools["可选工具 Schema"]
    Request --> Params["模型参数"]
    Request --> ChatModel["model.ChatModel"]
    ChatModel --> Adapter["供应商适配包"]
    Adapter --> Provider["OpenAI / DashScope / Anthropic / Gemini 等"]
    Provider --> Adapter
    Adapter --> Response["model.ChatResponse"]
    Response --> Blocks["TextBlock / ThinkingBlock / ToolCallBlock / DataBlock"]
    Blocks --> App
```

## ChatModel

```go
type ChatModel interface {
	Name() string
	Call(context.Context, CallRequest) (*ChatResponse, error)
	Stream(context.Context, CallRequest) (<-chan ChatResponse, error)
	CountTokens(CallRequest) (int, error)
}
```

接口含义如下：

| 方法 | 用途 | 使用建议 |
| --- | --- | --- |
| `Name` | 返回供应商和模型名，用于日志、事件和诊断 | 适合打入请求日志 |
| `Call` | 一次性返回完整模型响应 | 普通后端 API 最容易接入 |
| `Stream` | 返回模型响应分块，最后一个分块 `IsLast=true` | 前端流式输出、Agent 事件流使用 |
| `CountTokens` | 估算或计算消息和工具 Schema 的 token 数 | 上下文压缩和预算控制使用 |

`Call` 和 `Stream` 都接收 `model.CallRequest`。请求中可以只传消息，也可以传工具 Schema、工具选择、模型参数和元数据。

```go
user, err := message.NewUserMessage("user", "Summarize AgentScope Go in one sentence.")
if err != nil {
	panic(err)
}

response, err := chat.Call(ctx, model.CallRequest{
	Messages: []*message.Message{user},
	Parameters: map[string]any{
		"temperature": 0.2,
	},
})
if err != nil {
	panic(err)
}

if text := response.GetTextContent(); text != nil {
	fmt.Println(*text)
}
```

不同供应商对 `Parameters` 的支持不完全一致。生产代码更推荐使用供应商包提供的类型化参数 Option，例如 `dashscope.WithChatParameters`、`openai.WithChatParameters` 或 `gemini.WithChatParameters`。

## 供应商

| 包 | 能力 | 常用凭据入口 | 说明 |
| --- | --- | --- | --- |
| `model/openai` | Chat Completions | `credential.NewOpenAI(...).ChatCredential()` 或 `openai.NewCredential` | 支持 OpenAI SDK、代理 HTTP client 和模型元数据 |
| `model/openairesponse` | Responses API | `credential.NewOpenAI(...).ResponseCredential()` 或 `openairesponse.NewCredential` | 面向 Responses API 的独立适配 |
| `model/anthropic` | Messages API | `credential.NewAnthropic(...).ChatCredential()` 或 `anthropic.NewCredential` | 使用 Anthropic 官方 SDK |
| `model/dashscope` | OpenAI 兼容聊天端点 | `credential.NewDashScope(...).ChatCredential()` 或 `dashscope.NewCredential` | 适合通义千问聊天和工具调用 |
| `model/deepseek` | OpenAI 兼容聊天端点 | `deepseek.NewCredential` | 复用 OpenAI 兼容请求路径 |
| `model/gemini` | Gemini 聊天模型 | `credential.NewGemini(...).ChatCredential()` 或 `gemini.NewCredential` | 使用 Gemini 官方 Go SDK |
| `model/moonshot` | OpenAI 兼容聊天端点 | `credential.NewMoonshot(...).ChatCredential()` 或 `moonshot.NewCredential` | 面向 Moonshot/Kimi 模型 |
| `model/xai` | OpenAI 兼容聊天端点 | `xai.NewCredential` | 面向 xAI 模型 |
| `model/zhipu` | OpenAI 兼容聊天端点 | `zhipu.NewCredential` | 面向智谱模型 |
| `model/ollama` | 本地 Ollama | `ollama.NewCredential` | 默认连接本地 Ollama 服务 |

多数供应商包都提供 `ListModels()`，用于读取仓库内置的模型卡片。模型卡片适合做能力展示、配置校验或管理后台候选列表，但它不是线上服务的实时模型列表。

```go
cards, err := dashscope.ListModels()
if err != nil {
	panic(err)
}
for _, card := range cards {
	fmt.Println(card.Name)
}
```

## 创建模型

推荐在业务代码中把模型创建集中到一个小函数里。这样 API Key、base URL、超时、代理和默认参数都能统一管理。

```go
func newDashScopeChat() (model.ChatModel, error) {
	maxTokens := int64(512)
	temperature := 0.2

	return dashscope.NewChatModel(
		credential.NewDashScope(os.Getenv("AI_DASHSCOPE_API_KEY")).ChatCredential(),
		"qwen-plus",
		dashscope.WithStream(false),
		dashscope.WithChatParameters(dashscope.ChatParameters{
			MaxTokens:   &maxTokens,
			Temperature: &temperature,
		}),
	)
}
```

`WithStream` 在部分供应商包中用于记录兼容性偏好。调用 `Call` 时仍走非流式路径，调用 `Stream` 时仍走流式路径。不要把它当成上层是否返回流式 HTTP 的唯一开关，上层接口应由服务边界决定。

## 工具调用

工具 Schema 使用 OpenAI 兼容的 function schema 表示。模型层只负责把 Schema 发给供应商，并把供应商返回的工具调用转换成 `message.ToolCallBlock`。是否执行工具由调用方代码或 `agent.Agent` 决定。

```go
schemas, err := kit.ToolSchemas()
if err != nil {
	panic(err)
}

response, err := chat.Call(ctx, model.CallRequest{
	Messages: messages,
	Tools:    schemas,
})
if err != nil {
	panic(err)
}

for _, block := range response.GetContentBlocks("tool_call") {
	call := block.(*message.ToolCallBlock)
	result, err := kit.RunTool(ctx, call, state.NewAgentState())
	if err != nil {
		panic(err)
	}
	fmt.Println(result.State)
}
```

手动执行工具适合需要完全控制模型轮次的场景。需要多轮工具回填、权限确认和事件流时，可以把模型和 `Toolkit` 交给 `agent.Agent`。

## Embedding

`embedding` 包定义 embedding 请求、响应、缓存辅助能力和供应商模型元数据。Go
实现会为已覆盖的 provider 内嵌 embedding model card：

```go
cards, err := dashscopeembedding.ListModels()
```

当前已覆盖 `embedding/dashscope`、`embedding/gemini`、`embedding/openai` 和
`embedding/ollama`。

文本向量模型不实现 `ChatModel`。它们属于单独的 `embedding` 包，适合检索、召回、相似度搜索和上下文构建。

```go
embedder, err := dashscopeembedding.NewTextModel(
	credential.NewDashScope(os.Getenv("AI_DASHSCOPE_API_KEY")).EmbeddingCredential(),
	"text-embedding-v4",
)
if err != nil {
	panic(err)
}

response, err := embedder.Embed(ctx, embedding.EmbeddingRequest{
	Inputs: []embedding.EmbeddingInput{
		embedding.NewTextInput("AgentScope Go"),
		embedding.NewTextInput("tool calling"),
	},
})
```

## 语音能力

语音能力统一放在 `audio/*` 包下。`audio/tts` 定义文本转语音供应商接口，
`audio/stt` 定义语音识别供应商接口。`model/*` 继续负责 chat/generation
多模态模型；其中的音频输入输出能力与独立语音合成、语音识别 provider 分开维护。

DashScope 原生 TTS 适配位于 `audio/tts/dashscope`：

```go
speech, err := dashscopetts.NewModel(
	dashscopetts.NewCredential(os.Getenv("AI_DASHSCOPE_API_KEY")),
	"qwen3-tts-flash",
	dashscopetts.WithStream(false),
)
chunks, err := speech.Synthesize(ctx, tts.Request{Text: "hello"})
for chunk := range chunks {
	if chunk.Content == nil {
		continue
	}
	source := chunk.Content.Source.(*message.Base64Source)
	fmt.Println(source.MediaType, len(source.Data))
}
```

DashScope TTS model card 通过 `dashscopetts.ListModels()` 暴露，包含普通和 realtime 两类模型定义。

`WithStream(true)` 会输出兼容 WAV 的流式分块：首个分块包含 streaming WAV
header 和 PCM 字节，后续分块在同一个 `audio/wav` media type 下追加 PCM
字节。`WithStream(false)` 会把供应商返回的 PCM 分块聚合成一个完整 WAV 载荷。

DashScope 语音识别适配位于 `audio/stt/dashscope`。录音文件识别走
DashScope 异步任务，因此批量输入必须是公网 HTTP(S) 音频 URL：

```go
speech, err := dashscopestt.NewModel(
	dashscopestt.NewCredential(os.Getenv("AI_DASHSCOPE_API_KEY")),
	"paraformer-v2",
)
chunks, err := speech.Recognize(ctx, stt.Request{
	Audio: message.NewDataBlock(message.NewURLSource(audioURL, "audio/wav")),
})
for chunk := range chunks {
	if chunk.Error != nil {
		return chunk.Error
	}
	fmt.Println(chunk.Text)
}
```

实时语音识别使用 `NewRealtimeModel` 创建长连接模型，并通过 `NewSession`
管理一次 WebSocket 会话。一个 Session 可以持续接收多个 PCM 音频块，适合
长连接、多轮和多段语音识别。`Responses()` 会持续输出 `IsLast=false`
的实时文本和 `IsLast=true` 的最终文本；如果服务端返回错误，终止响应会携带
`Error`。

```go
speech, err := dashscopestt.NewRealtimeModel(
	dashscopestt.NewCredential(os.Getenv("AI_DASHSCOPE_API_KEY")),
	"qwen3-asr-flash-realtime",
	dashscopestt.WithRealtimeParameters(dashscopestt.RealtimeParameters{
		Language: "zh",
	}),
)
session, err := speech.NewSession(ctx, stt.SessionRequest{})
defer session.Close(context.WithoutCancel(ctx))

if err := session.Push(ctx, stt.NewAudioBlock(rawPCM, "audio/pcm")); err != nil {
	return err
}
if err := session.Finish(ctx); err != nil {
	return err
}
for chunk := range session.Responses() {
	if chunk.Error != nil {
		return chunk.Error
	}
	if chunk.Text != "" {
		fmt.Println(chunk.Text, chunk.IsLast)
	}
}
```

默认实时模式是 DashScope 服务端 VAD，`input_audio_format=pcm`，
`sample_rate=16000`，`vad_threshold=0.0`，`silence_duration_ms=400`。
如果需要手动断句，可设置 `RealtimeParameters{Mode: dashscopestt.RealtimeModeManual}`，
推送一段完整音频后调用 `session.Commit(ctx)`，最后再调用 `session.Finish(ctx)`。

DashScope STT model card 通过 `dashscopestt.ListModels()` 暴露，包含批量
`paraformer-v2` 和实时 `qwen3-asr-flash-realtime`。

如果需要从电脑麦克风实时识别，可以运行 `example/model/dashscope/stt_microphone`。
该示例使用 `malgo` 打开本机默认麦克风，采集 16-bit PCM 单声道音频，并通过
`session.Push` 持续发送到 DashScope realtime STT。按 `Ctrl+C` 后，示例会先
停止麦克风，再调用 `session.Finish` 获取最终文本。

```bash
cd example/model/dashscope/stt_microphone
export AI_DASHSCOPE_API_KEY="your-dashscope-key"
go run .
```

常用参数如下：

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `--language` | `zh` | 传给 Qwen-ASR realtime 的语言提示 |
| `--sample-rate` | `16000` | 麦克风采样率，支持 `8000` 和 `16000` |
| `--chunk-ms` | `100` | 每个音频块的目标时长，单位为毫秒 |
| `--silence-ms` | `400` | 服务端 VAD 判断一句话结束的静音时长，单位为毫秒 |
| `--queue-size` | `32` | 麦克风回调到网络发送之间的有界队列长度 |

## 流式响应

`Stream` 返回 `<-chan ChatResponse`。实现约定是先发送若干 `IsLast=false` 的增量块，最后发送一个 `IsLast=true` 的完整响应。若供应商流式过程中失败，最终块会带 `Error`，调用方应检查。

```go
chunks, err := chat.Stream(ctx, model.CallRequest{Messages: messages})
if err != nil {
	panic(err)
}

for chunk := range chunks {
	if chunk.Error != nil {
		panic(chunk.Error)
	}
	if text := chunk.GetTextContent(); text != nil {
		fmt.Print(*text)
	}
	if chunk.IsLast {
		fmt.Println("\nstream done")
	}
}
```

`Call` 和 `Stream` 都使用同一个 `ChatResponse` 结构。需要读取模型返回的文本块内容时，可以直接调用：

```go
text := response.GetTextContent()
if text != nil {
	fmt.Println(*text)
}

toolCalls := response.GetContentBlocks("tool_call")
if len(toolCalls) > 0 {
	fmt.Println(toolCalls[0].BlockID())
}
```

## 取舍建议

- 简单模型调用：只依赖 `model` 和 `message`。
- 需要工具 Schema：再引入 `tool.Toolkit`。
- 需要多轮工具闭环：使用 `agent.Agent`。
- 需要上下文压缩：给 Agent 配置 `ContextConfig` 和上下文策略。
- 需要供应商特有参数：优先使用供应商包的类型化参数结构，不要把所有内容塞进 `Parameters`。
