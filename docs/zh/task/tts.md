# Text-to-Speech (TTS) 语音合成

AgentScope Java 提供了完整的 TTS 能力支持，让 Agent 不仅能思考和回复，还能语音播放。

## 为什么需要 TTS？

传统的 Agent 交互是纯文本的：用户输入文字，Agent 返回文字。但在很多场景下，语音是更自然的交互方式：

- **智能客服**：电话/语音客服场景
- **智能助手**：车载助手、智能音箱
- **无障碍访问**：为视障用户提供语音输出
- **实时对话**：边生成边朗读，减少等待感

---

## 实时 vs 非实时模式对比

AgentScope 提供两种 TTS 模式，适用于不同场景：

| 维度 | 非实时模式 (Batch) | 实时模式 (Streaming)                                     |
|------|-------------------|------------------------------------------------------|
| **交互逻辑** | 先发送完整文本，等待服务器处理完整个音频后返回。 | 边传边发，边合成。发送文本块 (Text Chunks)，服务器实时返回音频块 (Audio Chunks)。 |
| **首包延迟 (TTFB)** | 通常需要几秒钟（取决于文本长度），因为模型要计算整段音频。 | 通常在毫秒级别，用户感觉几乎是瞬时发声。                               |
| **通信协议** | REST (HTTPS) | WebSocket                                            |
| **音频连续性** | 完美。模型可以全局优化整段话的重音、语调和情感。 | 良好。虽然是分块合成，但模型会保留上下文窗口来维持自然度。                        |
| **适用场景** | 播客、有声书、短视频配音（质量优先）。 | AI 助手、实时翻译、需要"边说边生成"的对话。                             |
| **对应模型** | `qwen3-tts-flash` | `qwen3-tts-flash-realtime`                           |
| **对应类** | `DashScopeTTSModel` | `DashScopeRealtimeTTSModel`                          |

## 使用方式

AgentScope 提供三种使用 TTS 的方式：

| 方式 | 适用场景               | 特点                      |
|------|--------------------|-------------------------|
| **TTSHook** | Agent 所有回复自动朗读     | 只需添加 TTSHook 就能实现边生成边播放 |
| **TTSModel** | 独立语音合成             | 可以不依赖 Agent 灵活使用        |
| **DashScopeMultiModalTool** | Agent 通过工具方式调用 TTS | Agent 自行判断在需要时将文字转成语音   |

---

## 方式一：TTSHook（Agent 自动朗读）

ReactAgent 推荐用这个方式，支持 ReactAgent 在回复时自动朗读。

### 本地播放模式（CLI/桌面应用）

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.TTSHook;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.tts.AudioPlayer;
import io.agentscope.core.model.tts.DashScopeRealtimeTTSModel;

// 1. 创建实时 TTS 模型（WebSocket 流式）
DashScopeRealtimeTTSModel ttsModel = DashScopeRealtimeTTSModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("qwen3-tts-flash-realtime")  // WebSocket 实时模型
    .voice("Cherry")
    .mode(DashScopeRealtimeTTSModel.SessionMode.SERVER_COMMIT)  // 服务端自动提交
    .build();

// 2. 创建 TTS Hook
TTSHook ttsHook = TTSHook.builder()
    .ttsModel(ttsModel)
    .build();

// 3. 创建带 TTS 的 Agent
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("你是一个友好的助手")
    .model(chatModel)
    .hook(ttsHook)  // 添加 TTS Hook
    .build();

// 4. 与 Agent 对话 - Agent 会边生成回复边朗读
Msg response = agent.call(Msg.user("你好，今天天气怎么样？")).block();
```

### 服务器模式（Web/SSE）

在 Web 应用中，音频需要发送到前端播放：

```java
import io.agentscope.core.hook.TTSHook;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import java.util.Map;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

// 创建 SSE sink
Sinks.Many<ServerSentEvent<Map<String, Object>>> sink = 
    Sinks.many().multicast().onBackpressureBuffer();

// 创建 TTS Hook（服务器模式，不需要 AudioPlayer）
TTSHook ttsHook = TTSHook.builder()
    .ttsModel(ttsModel)
    .audioCallback(audio -> {
        // 将音频通过 SSE 发送到前端
        if (audio.getSource() instanceof Base64Source src) {
            sink.tryEmitNext(
                ServerSentEvent.<Map<String, Object>>builder()
                    .event("audio")
                    .data(Map.of("audio", src.getData()))
                    .build());
        }
    })
    .build();

// 或者使用响应式流
Flux<AudioBlock> audioStream = ttsHook.getAudioStream();
audioStream.subscribe(audio -> {
    // 处理音频块，例如发送到前端
    if (audio.getSource() instanceof Base64Source src) {
        sendToClient(src.getData());
    }
});

// 返回 SSE 流
return sink.asFlux();
```

---

## 方式二：直接调用 TTSModel 和 RealtimeTTSModel

不依赖 Agent，直接调用 TTS 模型进行语音合成。

### 2.1 非实时模式

适合短文本，一次性返回完整音频：

```java
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.model.tts.DashScopeTTSModel;
import io.agentscope.core.model.tts.TTSOptions;
import io.agentscope.core.model.tts.TTSResponse;

// 创建 TTS 模型
DashScopeTTSModel ttsModel = DashScopeTTSModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("qwen3-tts-flash")
    .build();

// 合成语音
TTSOptions options = TTSOptions.builder()
    .voice("Cherry")
    .sampleRate(24000)
    .format("wav")
    .build();

TTSResponse response = ttsModel.synthesize("你好，欢迎使用语音合成功能！", options).block();

// 获取音频数据
byte[] audioData = response.getAudioData();
AudioBlock audioBlock = response.toAudioBlock();
```

### 2.2 实时模式 - 增量输入（Push/Finish 模式）

适用于 LLM 流式输出场景，边接收文本边合成：

```java
import io.agentscope.core.model.tts.AudioPlayer;
import io.agentscope.core.model.tts.DashScopeRealtimeTTSModel;

// 创建实时 TTS 模型
DashScopeRealtimeTTSModel ttsModel = DashScopeRealtimeTTSModel.builder()
    .apiKey(apiKey)
    .modelName("qwen3-tts-flash-realtime")
    .voice("Cherry")
    .mode(DashScopeRealtimeTTSModel.SessionMode.SERVER_COMMIT)  // 服务端自动提交
    .languageType("Auto")  // 自动检测语言
    .build();

// 创建音频播放器
AudioPlayer player = AudioPlayer.builder()
    .sampleRate(24000)
    .build();

// 1. 开始会话（建立 WebSocket 连接）
ttsModel.startSession();

// 2. 订阅音频流
ttsModel.getAudioStream()
    .doOnNext(audio -> player.play(audio))
    .subscribe();

// 3. 增量推送文本（模拟 LLM 流式输出）
ttsModel.push("你好，");
ttsModel.push("我是你的");
ttsModel.push("智能助手。");

// 4. 结束会话，等待所有音频完成
ttsModel.finish().blockLast();

// 5. 关闭连接
ttsModel.close();
```

#### SessionMode 说明

| 模式 | 说明 |
|------|------|
| `SERVER_COMMIT` | 服务端自动提交文本进行合成（推荐） |
| `COMMIT` | 客户端需要手动调用 `commitTextBuffer()` 提交 |

---

## 方式三：DashScopeMultiModalTool（作为 Agent 工具）

Agent 通过工具方式调用 TTS，Agent 自行判断在需要时将文字转成语音

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.tool.multimodal.DashScopeMultiModalTool;

// 1. 创建多模态工具
DashScopeMultiModalTool multiModalTool = new DashScopeMultiModalTool(apiKey);

// 2. 创建 Agent，注册工具
ReActAgent agent = ReActAgent.builder()
    .name("MultiModalAssistant")
    .sysPrompt("你是一个多模态助手。当用户要求朗读时，使用 dashscope_text_to_audio 工具。")
    .model(chatModel)
    .tools(multiModalTool)
    .build();

// 3. Agent 可以主动调用 TTS 工具
Msg response = agent.call(Msg.user("请用语音说一句'欢迎光临'")).block();
```

---

## 核心组件

### 1. DashScopeRealtimeTTSModel（实时模式）

WebSocket 实时流式合成模型：

```java
DashScopeRealtimeTTSModel ttsModel = DashScopeRealtimeTTSModel.builder()
    .apiKey("sk-xxx")                              // API Key（必需）
    .modelName("qwen3-tts-flash-realtime")         // 模型名称
    .voice("Cherry")                               // 声音
    .sampleRate(24000)                             // 采样率
    .format("pcm")                                 // 音频格式
    .mode(SessionMode.SERVER_COMMIT)               // 会话模式
    .languageType("Auto")                          // 语言类型
    .build();
```

**主要方法：**

| 方法 | 说明 |
|------|------|
| `startSession()` | 建立 WebSocket 连接 |
| `push(text)` | 增量推送文本 |
| `finish()` | 结束输入，获取剩余音频 |
| `getAudioStream()` | 获取音频流（异步接收） |
| `synthesizeStream(text)` | 一次性流式合成 |
| `close()` | 关闭连接 |

### 2. DashScopeTTSModel（非实时模式）

标准 HTTP 模式的 TTS 模型：

```java
DashScopeTTSModel ttsModel = DashScopeTTSModel.builder()
    .apiKey("sk-xxx")
    .modelName("qwen3-tts-flash")
    .voice("Cherry")
    .build();
```

### 3. TTSHook

用于将 TTS 能力集成到 Agent：

```java
TTSHook ttsHook = TTSHook.builder()
    .ttsModel(ttsModel)           // TTS 模型（必需）
    .audioPlayer(audioPlayer)     // 本地播放器（可选）
    .audioCallback(callback)      // 音频回调（可选，用于服务器模式）
    .realtimeMode(true)           // 实时模式（默认 true）
    .autoStartPlayer(true)        // 自动启动播放器（默认 true）
    .build();
```

### 4. AudioPlayer

用于本地播放音频：

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

## 支持的模型

| 模型名称 | 类型 | 协议 | 特点 |
|---------|------|------|------|
| `qwen3-tts-flash` | 非实时 | HTTP | 一次性输入，流式输出，快速 |
| `qwen3-tts-flash-realtime` | 实时 | WebSocket | 流式输入+输出，最低延迟 |
| `qwen3-tts-vd-realtime` | 实时 | WebSocket | 支持声音设计（通过文本描述创建音色） |
| `qwen3-tts-vc-realtime` | 实时 | WebSocket | 支持声音复刻（基于音频样本） |

---

## 完整示例

- 快速开始：`agentscope-examples/quickstart/TTSExample.java`
- 完整示例：`agentscope-examples/chat-tts` 模块，包含前后端交互

---

## 配置参数

### DashScopeRealtimeTTSModel

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| apiKey | String | - | DashScope API Key（必需） |
| modelName | String | qwen3-tts-flash-realtime | 模型名称 |
| voice | String | Cherry | 声音名称 |
| sampleRate | int | 24000 | 采样率 (8000/16000/24000) |
| format | String | pcm | 音频格式 (pcm/mp3/opus) |
| mode | SessionMode | SERVER_COMMIT | 会话模式 |
| languageType | String | Auto | 语言类型 (Chinese/English/Auto 等) |

### DashScopeTTSModel

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| apiKey | String | - | DashScope API Key（必需） |
| modelName | String | qwen3-tts-flash | 模型名称 |
| voice | String | Cherry | 声音名称 |

### TTSHook

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| ttsModel | TTSModel/RealtimeTTSModel | - | TTS 模型（必需） |
| audioPlayer | AudioPlayer | null | 本地播放器（可选） |
| audioCallback | Consumer<AudioBlock> | null | 音频回调（可选） |
| realtimeMode | boolean | true | 是否启用实时模式 |
| autoStartPlayer | boolean | true | 是否自动启动播放器 |
