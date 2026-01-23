# Text-to-Speech (TTS)

AgentScope Java provides comprehensive TTS capabilities, enabling Agents not only to think and respond, but also to speak. Compared to text-only scenarios, voice is a more natural interaction method, suitable for intelligent customer service, in-car assistants, and real-time conversation scenarios that generate and speak simultaneously.

## Choosing the Right Model

**Non-real-time Model**: Suitable for scenarios that require high audio quality and can accept a few seconds of delay, such as podcasts, audiobooks, and short video dubbing. Uses `DashScopeTTSModel` and `qwen3-tts-flash` model, sending complete text at once and waiting for the server to process the entire audio before returning. The model can globally optimize stress, intonation, and emotion for the entire speech, achieving the best audio quality.

**Real-time Model**: Suitable for scenarios that require high response speed and need to generate and play simultaneously, such as AI assistants and real-time translation. Uses `DashScopeRealtimeTTSModel` and `qwen3-tts-flash-realtime` model, streaming text chunks and the server returns audio chunks in real-time with lower latency. Although synthesized in chunks, the model also maintains a context window to preserve naturalness.

## Usage Methods

AgentScope provides three ways to use TTS:

**ReActAgent Integration**: By adding TTSHook to ReActAgent, you can achieve automatic speech for all Agent responses. Simply adding TTSHook enables the speak-while-generating effect.

**Standalone TTSModel Usage**: Independent of Agent, directly call TTSModel for standalone speech synthesis, providing flexible usage suitable for scenarios that require separate voice conversion.

**Using DashScopeMultiModalTool as a Tool**: Provide TTS as a multimodal tool to Agent, allowing Agent to decide when to convert text to speech.

---

## Method 1: ReActAgent Integration

By adding TTSHook to ReActAgent, ReActAgent can automatically speak when responding.

**Working Principle**:

- **Event Listening Mechanism**: TTSHook implements the Hook interface and listens to events during Agent execution. When Agent starts reasoning, it triggers `PreReasoningEvent`, when generating text chunks it triggers `ReasoningChunkEvent`, and when reasoning completes it triggers `PostReasoningEvent`.

- **Real-time Streaming Synthesis**: In real-time mode, TTSHook listens to `ReasoningChunkEvent`. Whenever Agent generates a text chunk, it immediately pushes it to the TTS model via WebSocket for speech synthesis. This achieves the "speak-while-generating" effect, with users feeling almost no delay.

- **Session Lifecycle Management**: When receiving the first text chunk, TTSHook starts a TTS session (establishes WebSocket connection) and subscribes to the audio stream. When Agent reasoning completes, it calls `finish()` to commit remaining text and close the session, ensuring all audio is synthesized and played.

- **Audio Distribution Mechanism**: Generated audio blocks are distributed in three ways: 1) Sent to reactive stream (`audioSink`) for SSE/WebSocket frontend subscription; 2) Call `audioCallback` callback function for custom processing; 3) Play locally via `AudioPlayer`, suitable for CLI/desktop applications.

- **Playback Interruption Handling**: When new reasoning starts (`PreReasoningEvent`), TTSHook interrupts currently playing audio, closes the old TTS session, ensuring new response audio can start playing immediately, avoiding audio confusion.

### Local Playback Mode (CLI/Desktop Application)

Uses WebSocket real-time streaming synthesis, supporting speak-while-generating:

```java
// 1. Create real-time TTS model (WebSocket streaming)
DashScopeRealtimeTTSModel ttsModel = DashScopeRealtimeTTSModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("qwen3-tts-flash-realtime")  // WebSocket real-time model
    .voice("Cherry")
    .mode(DashScopeRealtimeTTSModel.SessionMode.SERVER_COMMIT)  // Server auto-commit
    .build();

// 2. Create TTS Hook
TTSHook ttsHook = TTSHook.builder().ttsModel(ttsModel).build();

// 3. Create Agent with TTS
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("你是一个友好的助手")
    .model(chatModel)
    .hook(ttsHook)  // Add TTS Hook
    .build();

// 4. Chat with Agent - Agent will speak while generating response
Msg response = agent.call(Msg.user("你好，今天天气怎么样？")).block();
```

### Server Mode (Web/SSE)

In web applications, audio needs to be sent to the frontend for playback. You can send audio to the frontend via SSE or use reactive streams. Complete code can be found in the `agentscope-examples/chat-tts` module, which includes frontend and backend interaction.

---

## Method 2: Standalone TTSModel Usage

Independent of Agent, directly call TTS model for speech synthesis.

### 2.1 Non-real-time Mode

Suitable for returning complete audio at once:

```java
// Create TTS model
DashScopeTTSModel ttsModel = DashScopeTTSModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("qwen3-tts-flash")
    .build();

// Synthesize speech
TTSOptions options = TTSOptions.builder().voice("Cherry").sampleRate(24000).format("wav").build();

TTSResponse response = ttsModel.synthesize("你好，欢迎使用语音合成功能！", options).block();

// Get audio data
byte[] audioData = response.getAudioData();
AudioBlock audioBlock = response.toAudioBlock();
```

### 2.2 Real-time Mode - Incremental Input (Push/Finish Mode)

Suitable for LLM streaming output scenarios, synthesize while receiving text:

```java
// Create real-time TTS model
DashScopeRealtimeTTSModel ttsModel = DashScopeRealtimeTTSModel.builder()
    .apiKey(apiKey)
    .modelName("qwen3-tts-flash-realtime")
    .voice("Cherry")
    .mode(DashScopeRealtimeTTSModel.SessionMode.SERVER_COMMIT)  // Server auto-commit
    .languageType("Auto")  // Auto-detect language
    .build();

// Create audio player
AudioPlayer player = AudioPlayer.builder().sampleRate(24000).build();

// 1. Start session (establish WebSocket connection)
ttsModel.startSession();

// 2. Subscribe to audio stream
ttsModel.getAudioStream()
    .doOnNext(audio -> player.play(audio))
    .subscribe();

// 3. Incrementally push text (simulate LLM streaming output)
ttsModel.push("你好，");
ttsModel.push("我是你的");
ttsModel.push("智能助手。");

// 4. End session, wait for all audio to complete
ttsModel.finish().blockLast();

// 5. Close connection
ttsModel.close();
```

#### SessionMode Description

| Mode | Description |
|------|-------------|
| `SERVER_COMMIT` | Server automatically commits text for synthesis (recommended) |
| `COMMIT` | Client needs to manually call `commitTextBuffer()` to commit |

---

## Method 3: DashScopeMultiModalTool (As Agent Tool)

Agent calls TTS via tool, Agent decides when to convert text to speech:

```java
// 1. Create multimodal tool
DashScopeMultiModalTool multiModalTool = new DashScopeMultiModalTool(apiKey);

// 2. Create Agent, register tool
ReActAgent agent = ReActAgent.builder()
    .name("MultiModalAssistant")
    .sysPrompt("你是一个多模态助手。当用户要求朗读时，使用 dashscope_text_to_audio 工具。")
    .model(chatModel)
    .tools(multiModalTool)
    .build();

// 3. Agent can actively call TTS tool
Msg response = agent.call(Msg.user("请用语音说一句'欢迎光临'")).block();
```

---

## Complete Examples

- Quick Start: `agentscope-examples/quickstart/TTSExample.java`
- Complete Example: `agentscope-examples/chat-tts` module, includes frontend and backend interaction

---

## Core Component Configuration Parameters

### DashScopeRealtimeTTSModel

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| apiKey | String | - | DashScope API Key (required) |
| modelName | String | qwen3-tts-flash-realtime | Model name |
| voice | String | Cherry | Voice name |
| sampleRate | int | 24000 | Sample rate (8000/16000/24000) |
| format | String | pcm | Audio format (pcm/mp3/opus) |
| mode | SessionMode | SERVER_COMMIT | Session mode |
| languageType | String | Auto | Language type (Chinese/English/Auto, etc.) |

### DashScopeTTSModel

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| apiKey | String | - | DashScope API Key (required) |
| modelName | String | qwen3-tts-flash | Model name |
| voice | String | Cherry | Voice name |

### TTSHook

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| ttsModel | DashScopeRealtimeTTSModel | - | TTS model (required) |
| audioPlayer | AudioPlayer | null | Local player (optional) |
| audioCallback | Consumer<AudioBlock> | null | Audio callback (optional) |
| realtimeMode | boolean | true | Whether to enable real-time mode |
| autoStartPlayer | boolean | true | Whether to auto-start player |