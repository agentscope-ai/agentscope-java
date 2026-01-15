# Text-to-Speech (TTS)

AgentScope Java provides comprehensive TTS capabilities, enabling Agents to not only "think and respond" but also "speak aloud."

## Why TTS?

Traditional Agent interactions are purely text-based: users input text, and Agents return text. However, in many scenarios, voice is a more natural way to interact:

- **Customer Service**: Phone/voice customer service scenarios
- **Smart Assistants**: In-car assistants, smart speakers
- **Accessibility**: Providing voice output for visually impaired users
- **Real-time Conversations**: Speaking while generating, reducing wait time

---

## Realtime vs Non-realtime Mode Comparison

AgentScope provides two TTS modes for different scenarios:

| Dimension | Non-realtime Mode (Batch) | Realtime Mode (Streaming) |
|-----------|---------------------------|---------------------------|
| **Interaction Logic** | Send complete text first, wait for server to process entire audio before returning. | Stream and synthesize simultaneously. Send text chunks, server returns audio chunks in real-time. |
| **Time to First Byte (TTFB)** | Usually takes several seconds (depending on text length), as the model needs to calculate the entire audio. | Usually in milliseconds, users feel almost instant speech. |
| **Communication Protocol** | REST (HTTPS) | WebSocket |
| **Audio Continuity** | Perfect. Model can globally optimize stress, intonation, and emotion for the entire speech. | Good. Although synthesized in chunks, the model maintains context window to preserve naturalness. |
| **Use Cases** | Podcasts, audiobooks, short video dubbing (quality priority). | AI assistants, real-time translation, conversations requiring "speak while generating". |
| **Corresponding Model** | `qwen3-tts-flash` | `qwen3-tts-flash-realtime` |
| **Corresponding Class** | `DashScopeTTSModel` | `DashScopeRealtimeTTSModel` |

## Usage Patterns

AgentScope provides three ways to use TTS:

| Pattern | Use Case | Features |
|---------|----------|----------|
| **TTSHook** | Auto-speak all Agent responses | Just add TTSHook to enable speak-while-generating |
| **TTSModel** | Standalone speech synthesis | Can be used flexibly without depending on Agent |
| **DashScopeMultiModalTool** | Agent invokes TTS via tool | Agent decides when to convert text to speech |

---

## Pattern 1: TTSHook (Agent Auto-Speak)

Recommended for ReActAgent, supports ReActAgent automatically speaking when responding.

### Local Playback Mode (CLI/Desktop Applications)

Uses WebSocket realtime streaming synthesis, supports "speak while generating" with lowest latency:

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.TTSHook;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.tts.AudioPlayer;
import io.agentscope.core.model.tts.DashScopeRealtimeTTSModel;

// 1. Create realtime TTS model (WebSocket streaming)
DashScopeRealtimeTTSModel ttsModel = DashScopeRealtimeTTSModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("qwen3-tts-flash-realtime")  // WebSocket realtime model
    .voice("Cherry")
    .mode(DashScopeRealtimeTTSModel.SessionMode.SERVER_COMMIT)  // Server auto-commit
    .build();

// 2. Create TTS Hook
TTSHook ttsHook = TTSHook.builder()
    .ttsModel(ttsModel)
    .build();

// 3. Create Agent with TTS
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("You are a friendly assistant")
    .model(chatModel)
    .hook(ttsHook)  // Add TTS Hook
    .build();

// 4. Chat with Agent - Agent will speak while generating response
Msg response = agent.call(Msg.user("Hello, how's the weather today?")).block();
```

### Server Mode (Web/SSE)

In web applications, audio needs to be sent to the frontend for playback:

```java
import io.agentscope.core.hook.TTSHook;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import java.util.Map;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

// Create SSE sink
Sinks.Many<ServerSentEvent<Map<String, Object>>> sink = 
    Sinks.many().multicast().onBackpressureBuffer();

// Create TTS Hook (server mode, no AudioPlayer needed)
TTSHook ttsHook = TTSHook.builder()
    .ttsModel(ttsModel)
    .audioCallback(audio -> {
        // Send audio to frontend via SSE
        if (audio.getSource() instanceof Base64Source src) {
            sink.tryEmitNext(
                ServerSentEvent.<Map<String, Object>>builder()
                    .event("audio")
                    .data(Map.of("audio", src.getData()))
                    .build());
        }
    })
    .build();

// Or use reactive stream
Flux<AudioBlock> audioStream = ttsHook.getAudioStream();
audioStream.subscribe(audio -> {
    // Process audio block, e.g., send to frontend
    if (audio.getSource() instanceof Base64Source src) {
        sendToClient(src.getData());
    }
});

// Return SSE stream
return sink.asFlux();
```

---

## Pattern 2: TTSModel (Standalone Usage)

Use TTS model directly without depending on Agent.

### 2.1 Non-realtime Mode

Suitable for short text, returns complete audio at once:

```java
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.model.tts.DashScopeTTSModel;
import io.agentscope.core.model.tts.TTSOptions;
import io.agentscope.core.model.tts.TTSResponse;

// Create TTS model
DashScopeTTSModel ttsModel = DashScopeTTSModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("qwen3-tts-flash")
    .build();

// Synthesize speech
TTSOptions options = TTSOptions.builder()
    .voice("Cherry")
    .sampleRate(24000)
    .format("wav")
    .build();

TTSResponse response = ttsModel.synthesize("Hello, welcome to speech synthesis!", options).block();

// Get audio data
byte[] audioData = response.getAudioData();
AudioBlock audioBlock = response.toAudioBlock();
```

### 2.2 Realtime Mode - Incremental Input (Push/Finish Pattern)

Suitable for LLM streaming output scenarios, synthesizing while receiving text:

```java
import io.agentscope.core.model.tts.AudioPlayer;
import io.agentscope.core.model.tts.DashScopeRealtimeTTSModel;

// Create realtime TTS model
DashScopeRealtimeTTSModel ttsModel = DashScopeRealtimeTTSModel.builder()
    .apiKey(apiKey)
    .modelName("qwen3-tts-flash-realtime")
    .voice("Cherry")
    .mode(DashScopeRealtimeTTSModel.SessionMode.SERVER_COMMIT)  // Server auto-commit
    .languageType("Auto")  // Auto-detect language
    .build();

// Create audio player
AudioPlayer player = AudioPlayer.builder()
    .sampleRate(24000)
    .build();

// 1. Start session (establish WebSocket connection)
ttsModel.startSession();

// 2. Subscribe to audio stream
ttsModel.getAudioStream()
    .doOnNext(audio -> player.play(audio))
    .subscribe();

// 3. Push text incrementally (simulating LLM streaming output)
ttsModel.push("Hello, ");
ttsModel.push("I am your ");
ttsModel.push("smart assistant.");

// 4. End session, wait for all audio to complete
ttsModel.finish().blockLast();

// 5. Close connection
ttsModel.close();
```

#### SessionMode Explanation

| Mode | Description |
|------|-------------|
| `SERVER_COMMIT` | Server automatically commits text buffer for synthesis (recommended) |
| `COMMIT` | Client must manually call `commitTextBuffer()` to commit |

---

## Pattern 3: DashScopeMultiModalTool (As Agent Tool)

Agent invokes TTS via tool, Agent decides when to convert text to speech:

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.multimodal.DashScopeMultiModalTool;

// 1. Create multimodal tool
DashScopeMultiModalTool multiModalTool = new DashScopeMultiModalTool(apiKey);

// 2. Create Agent, register tool
ReActAgent agent = ReActAgent.builder()
    .name("MultiModalAssistant")
    .sysPrompt("You are a multimodal assistant. When user asks to read aloud, use dashscope_text_to_audio tool.")
    .model(chatModel)
    .tools(multiModalTool)
    .build();

// 3. Agent can actively call TTS tool
Msg response = agent.call(Msg.user("Please say 'Welcome' in audio")).block();
```

---

## Core Components

### 1. DashScopeRealtimeTTSModel (Realtime Mode)

WebSocket realtime streaming synthesis model:

```java
DashScopeRealtimeTTSModel ttsModel = DashScopeRealtimeTTSModel.builder()
    .apiKey("sk-xxx")                              // API Key (required)
    .modelName("qwen3-tts-flash-realtime")         // Model name
    .voice("Cherry")                               // Voice
    .sampleRate(24000)                             // Sample rate
    .format("pcm")                                 // Audio format
    .mode(SessionMode.SERVER_COMMIT)               // Session mode
    .languageType("Auto")                          // Language type
    .build();
```

**Main Methods:**

| Method | Description |
|--------|-------------|
| `startSession()` | Establish WebSocket connection |
| `push(text)` | Push text incrementally |
| `finish()` | End input, get remaining audio |
| `getAudioStream()` | Get audio stream (asynchronous reception) |
| `synthesizeStream(text)` | One-time streaming synthesis |
| `close()` | Close connection |

### 2. DashScopeTTSModel (Non-realtime Mode)

Standard HTTP mode TTS model:

```java
DashScopeTTSModel ttsModel = DashScopeTTSModel.builder()
    .apiKey("sk-xxx")
    .modelName("qwen3-tts-flash")
    .voice("Cherry")
    .build();
```

### 3. TTSHook

Used to integrate TTS capability into Agent:

```java
TTSHook ttsHook = TTSHook.builder()
    .ttsModel(ttsModel)           // TTS model (required)
    .audioPlayer(audioPlayer)     // Local player (optional)
    .audioCallback(callback)      // Audio callback (optional, for server mode)
    .realtimeMode(true)           // Realtime mode (default true)
    .autoStartPlayer(true)        // Auto-start player (default true)
    .build();
```

### 4. AudioPlayer

Used for local audio playback:

```java
AudioPlayer player = AudioPlayer.builder()
    .sampleRate(24000)
    .channels(1)
    .sampleSizeInBits(16)
    .signed(true)
    .bigEndian(false)
    .build();
```

---

## Supported Models

| Model Name | Type | Protocol | Features |
|------------|------|----------|----------|
| `qwen3-tts-flash` | Non-realtime | HTTP | One-time input, streaming output, fast |
| `qwen3-tts-flash-realtime` | Realtime | WebSocket | Streaming I/O, lowest latency |
| `qwen3-tts-vd-realtime` | Realtime | WebSocket | Supports voice design (create voice via text description) |
| `qwen3-tts-vc-realtime` | Realtime | WebSocket | Supports voice cloning (based on audio samples) |

---

## Complete Examples

- Quick start: `agentscope-examples/quickstart/TTSExample.java`
- Complete example: `agentscope-examples/chat-tts` module, includes frontend and backend interaction

---

## Configuration Parameters

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
| ttsModel | TTSModel/RealtimeTTSModel | - | TTS model (required) |
| audioPlayer | AudioPlayer | null | Local player (optional) |
| audioCallback | Consumer<AudioBlock> | null | Audio callback (optional) |
| realtimeMode | boolean | true | Whether to enable realtime mode |
| autoStartPlayer | boolean | true | Whether to auto-start player |
