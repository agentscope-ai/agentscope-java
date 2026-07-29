# Models

The `model` package defines the provider contract. Provider packages adapt SDK-specific messages, tool schemas, streaming events, and token counting to this contract.

## ChatModel

```go
type ChatModel interface {
	Name() string
	Call(context.Context, CallRequest) (*ChatResponse, error)
	Stream(context.Context, CallRequest) (<-chan ChatResponse, error)
	CountTokens(CallRequest) (int, error)
}
```

## Providers

| Package | Notes |
| --- | --- |
| `model/openai` | OpenAI SDK integration |
| `model/anthropic` | Anthropic SDK integration |
| `model/dashscope` | DashScope OpenAI-compatible endpoint |
| `model/deepseek` | OpenAI-compatible wrapper |
| `model/moonshot` | OpenAI-compatible wrapper |
| `model/xai` | OpenAI-compatible wrapper |
| `model/ollama` | Ollama official Go API |

## Embeddings

The `embedding` package defines embedding requests, responses, cache helpers,
and provider metadata. Python-compatible embedding model cards are embedded in
provider packages that have Python sources:

```go
cards, err := dashscopeembedding.ListModels()
```

Current embedded embedding model cards are copied from Python AgentScope for
`embedding/dashscope`, `embedding/gemini`, and `embedding/openai`.

## Audio

Audio capabilities live under `audio/*` packages. `audio/tts` defines the
text-to-speech provider contract, and `audio/stt` defines the speech-to-text
provider contract. Chat and generation models stay in `model/*`; their audio
input/output support is separate from standalone speech synthesis and
recognition providers.

DashScope native TTS support is available through `audio/tts/dashscope`:

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

DashScope TTS model cards are also copied from Python AgentScope and exposed by
`dashscopetts.ListModels()`, including both normal and realtime cards.

`WithStream(true)` emits WAV-compatible streaming chunks: the first chunk
contains a streaming WAV header followed by PCM bytes, and later chunks contain
additional PCM bytes under the same `audio/wav` media type. `WithStream(false)`
aggregates provider PCM chunks into one complete WAV payload.

DashScope speech recognition is available through `audio/stt/dashscope`.
Recorded-file recognition uses DashScope async tasks, so batch input must be a
public HTTP(S) audio URL:

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

Realtime speech recognition uses `NewRealtimeModel` and creates one WebSocket
session through `NewSession`. `Responses()` emits `IsLast=false` partial text and
`IsLast=true` final text. Provider-side failures are returned as terminal
responses with `Error` set.

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

The realtime default is DashScope server-side VAD with
`input_audio_format=pcm`, `sample_rate=16000`, `vad_threshold=0.0`, and
`silence_duration_ms=400`. For manual boundaries, set
`RealtimeParameters{Mode: dashscopestt.RealtimeModeManual}`, call
`session.Commit(ctx)` after one utterance, then call `session.Finish(ctx)`.

DashScope STT model cards are exposed by `dashscopestt.ListModels()`, including
batch `paraformer-v2` and realtime `qwen3-asr-flash-realtime`.

## Tool Schemas

Tools are passed to models as OpenAI-compatible function schemas:

```go
schemas, err := kit.ToolSchemas()
response, err := chat.Call(ctx, model.CallRequest{
	Messages: messages,
	Tools:    schemas,
})
```

`Call` and `Stream` both use `ChatResponse`. Use the content query helpers to read text blocks or tool calls:

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
