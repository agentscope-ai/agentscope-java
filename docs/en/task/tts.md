# Text-to-Speech (TTS)

AgentScope Java provides comprehensive TTS capabilities, enabling Agents to not only "think and respond" but also "speak aloud."

## Design Philosophy

### Why TTS?

Traditional Agent interactions are purely text-based: users input text, and Agents return text. However, in many scenarios, voice is a more natural way to interact:

- **Customer Service**: Phone/voice customer service scenarios
- **Smart Assistants**: In-car assistants, smart speakers
- **Accessibility**: Providing voice output for visually impaired users
- **Real-time Conversations**: Speaking while generating, reducing wait time

### Design Principles

AgentScope's TTS design follows these principles:

1. **Decoupling**: TTS capability is separated from Agent core logic, integrated non-invasively through Hook mechanism
2. **Streaming First**: Supports "speak while generating" for real-time speech synthesis
3. **Flexibility**: Can be used as a standalone tool or as an Agent Hook for automatic triggering
4. **Multi-scenario**: Supports local playback (CLI/desktop) and server mode (Web/SSE)


## Usage Patterns

AgentScope provides three ways to use TTS:

| Pattern | Use Case | Features |
|---------|----------|----------|
| TTSHook | Auto-speak all Agent responses | Non-invasive, speak while generating |
| TTSModel | Standalone speech synthesis | Independent of Agent, flexible calling |
| DashScopeMultiModalTool | Agent invokes TTS actively | Agent converts text to speech when needed |

---

### Pattern 1: TTSHook (Agent Auto-Speak)

This is the recommended approach, allowing the Agent to automatically speak while generating responses, supporting "speak as you generate."

#### Local Playback Mode (CLI/Desktop Apps)

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.TTSHook;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.tts.AudioPlayer;
import io.agentscope.core.model.tts.DashScopeRealtimeTTSModel;

// 1. Create TTS model
DashScopeRealtimeTTSModel ttsModel = DashScopeRealtimeTTSModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("qwen3-tts-flash")
    .voice("Cherry")
    .build();

// 2. Create audio player
AudioPlayer player = AudioPlayer.builder()
    .sampleRate(24000)
    .build();

// 3. Create TTS Hook
TTSHook ttsHook = TTSHook.builder()
    .ttsModel(ttsModel)
    .audioPlayer(player)
    .realtimeMode(true)  // Speak while generating
    .build();

// 4. Create Agent with TTS
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("You are a friendly assistant")
    .model(chatModel)
    .hook(ttsHook)  // Add TTS Hook
    .build();

// 5. Chat with Agent
Msg response = agent.call(Msg.user("Hello, how's the weather today?")).block();
// Agent will speak while generating response
```

#### Server Mode (Web/SSE)

In web applications, audio needs to be sent to the frontend for playback:

```java
import io.agentscope.core.hook.TTSHook;
import io.agentscope.core.message.Base64Source;
import reactor.core.publisher.Sinks;

// Create SSE sink
Sinks.Many<ServerSentEvent<Map<String, Object>>> sink = 
    Sinks.many().multicast().onBackpressureBuffer();

// Create TTS Hook (server mode, no AudioPlayer needed)
TTSHook ttsHook = TTSHook.builder()
    .ttsModel(ttsModel)
    .realtimeMode(true)
    .audioCallback(audio -> {
        // Send audio to frontend via SSE
        if (audio.getSource() instanceof Base64Source src) {
            sink.tryEmitNext(ServerSentEvent.<Map<String, Object>>builder()
                .event("audio")
                .data(Map.of("audio", src.getData()))
                .build());
        }
    })
    .build();

// Or use reactive stream
Flux<AudioBlock> audioStream = ttsHook.getAudioStream();
audioStream.subscribe(audio -> sendToClient(audio));
```

---

### Pattern 2: TTSModel (Standalone Usage)

Use TTS model directly without Agent. Supports three calling modes:

#### 2.1 Non-streaming Call

Suitable for short text, returns complete audio at once:

```java
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

#### 2.2 Streaming Synthesis

Suitable for long text, returns audio chunks progressively:

```java
DashScopeRealtimeTTSModel ttsModel = DashScopeRealtimeTTSModel.builder()
    .apiKey(apiKey)
    .modelName("qwen3-tts-flash")
    .voice("Cherry")
    .build();

// Streaming synthesis - can start playing as each audio chunk arrives
ttsModel.synthesizeStream("This is a long text that will be returned in multiple audio chunks...")
    .doOnNext(audioBlock -> player.play(audioBlock))
    .blockLast();
```

#### 2.3 Incremental Input Mode

Suitable for LLM streaming output scenarios, synthesizing while receiving text (used internally by TTSHook):

```java
// Start session
ttsModel.startSession();

// Push text incrementally (from LLM streaming output)
ttsModel.push("Hello, ").subscribe(audio -> player.play(audio));
ttsModel.push("I am your ").subscribe(audio -> player.play(audio));
ttsModel.push("smart assistant.").subscribe(audio -> player.play(audio));

// End session, get remaining audio
ttsModel.finish()
    .doOnNext(audio -> player.play(audio))
    .blockLast();
```

---

### Pattern 3: DashScopeMultiModalTool (As Agent Tool)

Allow Agent to actively call TTS tool, suitable for scenarios where Agent needs to "speak proactively":
- User requests "Please read this text for me"
- Agent decides voice response is more appropriate

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.multimodal.DashScopeMultiModalTool;

// 1. Create multimodal tool
DashScopeMultiModalTool multiModalTool = new DashScopeMultiModalTool(apiKey);

// 2. Create Agent with tool registered
ReActAgent agent = ReActAgent.builder()
    .name("MultiModalAssistant")
    .sysPrompt("You are a multimodal assistant. When user asks to read aloud, use dashscope_text_to_audio tool.")
    .model(chatModel)
    .tools(multiModalTool)  // Register multimodal tool
    .build();

// 3. Agent can actively call TTS tool
Msg response = agent.call(Msg.user("Please say 'Welcome' in audio")).block();
// Agent will call dashscope_text_to_audio tool, returning result with AudioBlock
```

## Core Components

### 1. TTSModel Interface

Base interface for all TTS models:

```java
public interface TTSModel {
    /**
     * Synthesize speech (non-streaming)
     */
    Mono<TTSResponse> synthesize(String text, TTSOptions options);
    
    /**
     * Get model name
     */
    String getModelName();
}
```

### 2. DashScopeRealtimeTTSModel

DashScope TTS model supporting real-time streaming synthesis, recommended for "speak while generating" scenarios:

```java
// Create realtime TTS model
DashScopeRealtimeTTSModel ttsModel = DashScopeRealtimeTTSModel.builder()
    .apiKey("sk-xxx")
    .modelName("qwen3-tts-flash")  // or qwen3-tts / qwen-tts
    .voice("Cherry")                // voice option
    .sampleRate(24000)              // sample rate
    .format("wav")                  // audio format
    .build();
```



### 3. TTSHook

Hook for integrating TTS capability into Agent:

```java
TTSHook ttsHook = TTSHook.builder()
    .ttsModel(ttsModel)           // Required: TTS model
    .realtimeMode(true)           // Realtime mode (speak while generating)
    .audioPlayer(audioPlayer)     // Optional: local player
    .audioCallback(callback)      // Optional: audio callback
    .build();
```

### 4. AudioPlayer

Component for local audio playback:

```java
AudioPlayer player = AudioPlayer.builder()
    .sampleRate(24000)
    .channels(1)
    .bitsPerSample(16)
    .build();
```

### 5. DashScopeMultiModalTool

Multimodal tool that allows Agent to use TTS through tool calls:

```java
DashScopeMultiModalTool multiModalTool = new DashScopeMultiModalTool(apiKey);

// Tool method: dashscope_text_to_audio
// Agent can call this tool to convert text to speech
```

### Supported TTS Models:

| Model Name | Features |
|------------|----------|
| qwen3-tts-flash | Fast, low latency, recommended |
| qwen3-tts | High quality |
| qwen-tts | Multiple Chinese/English voices |

---



## Complete Examples

For quick start, refer to `TTSExample.java` in the `agentscope-examples/quickstart` module.

For complete examples, refer to the `agentscope-examples/chat-tts` module.

---

## Configuration Parameters

### DashScopeRealtimeTTSModel

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| apiKey | String | - | DashScope API Key (required) |
| modelName | String | qwen3-tts-flash | Model name |
| voice | String | Cherry | Voice name |
| sampleRate | int | 24000 | Sample rate (8000/16000/24000) |
| format | String | wav | Audio format (wav/pcm/mp3) |
| speed | float | 1.0 | Speech rate (0.5-2.0) |
| volume | float | 1.0 | Volume (0.0-1.0) |
| pitch | float | 1.0 | Pitch (0.5-2.0) |

### TTSHook

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| ttsModel | DashScopeRealtimeTTSModel | - | TTS model (required) |
| audioPlayer | AudioPlayer | null | Local player (optional) |
| realtimeMode | boolean | true | Whether realtime mode |
| audioCallback | Consumer<AudioBlock> | null | Audio callback (optional) |
| autoStartPlayer | boolean | true | Whether to auto-start player |

### AudioPlayer

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| sampleRate | int | 24000 | Sample rate |
| channels | int | 1 | Number of channels |
| bitsPerSample | int | 16 | Bit depth |

---

## FAQ

### Q: No audio sound?

1. Check if `sampleRate` matches the model output (usually 24000)
2. Check if `format` is correct (wav includes headers, pcm is raw data)
3. Web frontend requires user interaction before playing audio (AudioContext limitation)

### Q: Too much latency?

1. Use `realtimeMode(true)` to enable realtime mode
2. Use `qwen3-tts-flash` model (faster)
3. Adjust text chunking strategy appropriately

### Q: What's the difference between TTSHook and DashScopeMultiModalTool?

| Feature | TTSHook | DashScopeMultiModalTool |
|---------|---------|-------------------------|
| Trigger | Automatic (Hook listens to Agent output) | Manual (Agent decides to call tool) |
| Use Case | All responses need to be spoken | Only specific cases need voice |
| Real-time | Speak while generating | Synthesize after complete text |
| Control | Developer configured | Agent autonomous decision |

**Selection Guide**:
- Need "speak while responding" effect → Use `TTSHook`
- Need Agent to decide when to speak → Use `DashScopeMultiModalTool`
- Can combine both: Use Hook for normal responses, use tool for specific voice generation

### Q: How to play audio returned by DashScopeMultiModalTool?

```java
Msg response = agent.call(Msg.user("Please say 'Hello' in audio")).block();

for (ContentBlock block : response.getContent()) {
    if (block instanceof AudioBlock audio) {
        if (audio.getSource() instanceof Base64Source src) {
            // Base64 data, decode and play
            byte[] data = Base64.getDecoder().decode(src.getData());
            // Play or save...
        } else if (audio.getSource() instanceof URLSource src) {
            // URL link, download and play
            String url = src.getUrl();
            // Download or play directly...
        }
    }
}
```

