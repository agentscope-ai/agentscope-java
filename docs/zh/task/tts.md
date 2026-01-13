# Text-to-Speech (TTS) 语音合成

AgentScope Java 提供了完整的 TTS 能力支持，让 Agent 不仅能"思考和回复"，还能"开口说话"。

## 设计理念

### 为什么需要 TTS？

传统的 Agent 交互是纯文本的：用户输入文字，Agent 返回文字。但在很多场景下，语音是更自然的交互方式：

- **智能客服**：电话/语音客服场景
- **智能助手**：车载助手、智能音箱
- **无障碍访问**：为视障用户提供语音输出
- **实时对话**：边生成边朗读，减少等待感

### 设计原则

AgentScope 的 TTS 设计遵循以下原则：

1. **解耦**：TTS 能力与 Agent 核心逻辑分离，通过 Hook 机制无侵入式集成
2. **流式优先**：支持"边生成边播放"，实时合成语音
3. **灵活性**：既可作为独立工具调用，也可作为 Agent Hook 自动触发
4. **多场景**：支持本地播放（CLI/桌面）和服务器模式（Web/SSE）


## 使用方式

AgentScope 提供三种使用 TTS 的方式：

| 方式 | 适用场景 | 特点                |
|------|---------|-------------------|
| TTSHook | Agent 所有回复自动朗读 | 无侵入，边生成边播放        |
| TTSModel | 独立语音合成 | 不依赖 Agent，灵活调用    |
| DashScopeMultiModalTool | Agent 主动调用 TTS | Agent 在需要时将文字转成语音 |

---

### 方式一：TTSHook（Agent 自动朗读）

这是最推荐的方式，可以让 Agent 在回复时自动朗读，支持"边生成边播放"。

#### 本地播放模式（CLI/桌面应用）

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.hook.TTSHook;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.model.tts.AudioPlayer;
import io.agentscope.core.model.tts.DashScopeRealtimeTTSModel;

// 1. 创建 TTS 模型
DashScopeRealtimeTTSModel ttsModel = DashScopeRealtimeTTSModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("qwen3-tts-flash")
    .voice("Cherry")
    .build();

// 2. 创建音频播放器
AudioPlayer player = AudioPlayer.builder()
    .sampleRate(24000)
    .build();

// 3. 创建 TTS Hook
TTSHook ttsHook = TTSHook.builder()
    .ttsModel(ttsModel)
    .audioPlayer(player)
    .realtimeMode(true)  // 边生成边播放
    .build();

// 4. 创建带 TTS 的 Agent
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("你是一个友好的助手")
    .model(chatModel)
    .hook(ttsHook)  // 添加 TTS Hook
    .build();

// 5. 与 Agent 对话
Msg response = agent.call(Msg.user("你好，今天天气怎么样？")).block();
// Agent 会边生成回复边朗读
```

#### 服务器模式（Web/SSE）

在 Web 应用中，音频需要发送到前端播放：

```java
import io.agentscope.core.hook.TTSHook;
import io.agentscope.core.message.Base64Source;
import reactor.core.publisher.Sinks;

// 创建 SSE sink
Sinks.Many<ServerSentEvent<Map<String, Object>>> sink = 
    Sinks.many().multicast().onBackpressureBuffer();

// 创建 TTS Hook（服务器模式，不需要 AudioPlayer）
TTSHook ttsHook = TTSHook.builder()
    .ttsModel(ttsModel)
    .realtimeMode(true)
    .audioCallback(audio -> {
        // 将音频通过 SSE 发送到前端
        if (audio.getSource() instanceof Base64Source src) {
            sink.tryEmitNext(ServerSentEvent.<Map<String, Object>>builder()
                .event("audio")
                .data(Map.of("audio", src.getData()))
                .build());
        }
    })
    .build();

// 或者使用响应式流
Flux<AudioBlock> audioStream = ttsHook.getAudioStream();
audioStream.subscribe(audio -> sendToClient(audio));
```

---

### 方式二：TTSModel（独立调用）

不依赖 Agent，直接调用 TTS 模型进行语音合成。支持三种调用模式：

#### 2.1 非流式调用

适合短文本，一次性返回完整音频：

```java
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

#### 2.2 流式合成

适合长文本，边合成边返回音频块：

```java
DashScopeRealtimeTTSModel ttsModel = DashScopeRealtimeTTSModel.builder()
    .apiKey(apiKey)
    .modelName("qwen3-tts-flash")
    .voice("Cherry")
    .build();

// 流式合成 - 每收到一个音频块就可以开始播放
ttsModel.synthesizeStream("这是一段很长的文本，会被分成多个音频块返回...")
    .doOnNext(audioBlock -> player.play(audioBlock))
    .blockLast();
```

#### 2.3 增量输入模式

适用于 LLM 流式输出场景，边接收文本边合成（TTSHook 内部使用此模式）：

```java
// 开始会话
ttsModel.startSession();

// 增量推送文本（来自 LLM 流式输出）
ttsModel.push("你好，").subscribe(audio -> player.play(audio));
ttsModel.push("我是你的").subscribe(audio -> player.play(audio));
ttsModel.push("智能助手。").subscribe(audio -> player.play(audio));

// 结束会话，获取剩余音频
ttsModel.finish()
    .doOnNext(audio -> player.play(audio))
    .blockLast();
```

---

### 方式三：DashScopeMultiModalTool（作为 Agent 的工具被调用）

让 Agent 能够主动调用 TTS 工具，适合 Agent 需要"主动说话"的场景：
- 用户请求"请帮我朗读这段话"
- Agent 决定用语音回复更合适

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.tool.multimodal.DashScopeMultiModalTool;

// 1. 创建多模态工具
DashScopeMultiModalTool multiModalTool = new DashScopeMultiModalTool(apiKey);

// 2. 创建 Agent，注册工具
ReActAgent agent = ReActAgent.builder()
    .name("MultiModalAssistant")
    .sysPrompt("你是一个多模态助手。当用户要求朗读时，使用 dashscope_text_to_audio 工具。")
    .model(chatModel)
    .tools(multiModalTool)  // 注册多模态工具
    .build();

// 3. Agent 可以主动调用 TTS 工具
Msg response = agent.call(Msg.user("请用语音说一句'欢迎光临'")).block();
// Agent 会调用 dashscope_text_to_audio 工具，返回包含 AudioBlock 的结果
```

## 核心组件

### 1. TTSModel 接口

所有 TTS 模型的基础接口：

```java
public interface TTSModel {
    /**
     * 合成语音（非流式）
     */
    Mono<TTSResponse> synthesize(String text, TTSOptions options);
    
    /**
     * 获取模型名称
     */
    String getModelName();
}
```

### 2. DashScopeRealtimeTTSModel

支持实时流式合成的 DashScope TTS 模型，这是推荐用于"边生成边播放"场景的实现：

```java
// 创建实时 TTS 模型
DashScopeRealtimeTTSModel ttsModel = DashScopeRealtimeTTSModel.builder()
    .apiKey("sk-xxx")
    .modelName("qwen3-tts-flash")  // 或 qwen3-tts / qwen-tts
    .voice("Cherry")                // 可选声音
    .sampleRate(24000)              // 采样率
    .format("wav")                  // 音频格式
    .build();
```



### 3. TTSHook

用于将 TTS 能力集成到 Agent 的 Hook：

```java
TTSHook ttsHook = TTSHook.builder()
    .ttsModel(ttsModel)           // 必需：TTS 模型
    .realtimeMode(true)           // 实时模式（边生成边播放）
    .audioPlayer(audioPlayer)     // 可选：本地播放器
    .audioCallback(callback)      // 可选：音频回调
    .build();
```

### 4. AudioPlayer

用于本地播放音频的组件：

```java
AudioPlayer player = AudioPlayer.builder()
    .sampleRate(24000)
    .channels(1)
    .bitsPerSample(16)
    .build();
```

### 5. DashScopeMultiModalTool

多模态工具，让 Agent 可以通过工具调用来使用 TTS：

```java
DashScopeMultiModalTool multiModalTool = new DashScopeMultiModalTool(apiKey);

// 工具方法：dashscope_text_to_audio
// Agent 可以调用此工具将文本转换为语音
```

### 支持的 TTS 模型：

| 模型名称 | 特点 |
|---------|------|
| qwen3-tts-flash | 快速，低延迟，推荐 |
| qwen3-tts | 高质量 |
| qwen-tts | 中英文多种声音 |

---



## 完整示例

快速开始请参考 `agentscope-examples/quickstart` 模块的 TTSExample.java 文件。

完整示例请参考 `agentscope-examples/chat-tts` 模块。

---

## 配置参数

### DashScopeRealtimeTTSModel

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| apiKey | String | - | DashScope API Key（必需） |
| modelName | String | qwen3-tts-flash | 模型名称 |
| voice | String | Cherry | 声音名称 |
| sampleRate | int | 24000 | 采样率 (8000/16000/24000) |
| format | String | wav | 音频格式 (wav/pcm/mp3) |
| speed | float | 1.0 | 语速 (0.5-2.0) |
| volume | float | 1.0 | 音量 (0.0-1.0) |
| pitch | float | 1.0 | 音调 (0.5-2.0) |

### TTSHook

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| ttsModel | DashScopeRealtimeTTSModel | - | TTS 模型（必需） |
| audioPlayer | AudioPlayer | null | 本地播放器（可选） |
| realtimeMode | boolean | true | 是否实时模式 |
| audioCallback | Consumer<AudioBlock> | null | 音频回调（可选） |
| autoStartPlayer | boolean | true | 是否自动启动播放器 |

### AudioPlayer

| 参数 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| sampleRate | int | 24000 | 采样率 |
| channels | int | 1 | 声道数 |
| bitsPerSample | int | 16 | 位深 |

---

## 常见问题

### Q: 音频没有声音？

1. 检查 `sampleRate` 是否与模型返回的一致（通常是 24000）
2. 检查 `format` 是否正确（wav 包含头信息，pcm 是原始数据）
3. Web 端需要用户交互后才能播放音频（AudioContext 限制）

### Q: 延迟太大？

1. 使用 `realtimeMode(true)` 启用实时模式
2. 使用 `qwen3-tts-flash` 模型（速度更快）
3. 适当调整文本分块策略

### Q: TTSHook 和 DashScopeMultiModalTool 有什么区别？

| 特性 | TTSHook | DashScopeMultiModalTool |
|------|---------|------------------------|
| 触发方式 | 自动（Hook 监听 Agent 输出） | 手动（Agent 决定调用工具） |
| 使用场景 | 所有回复都要朗读 | 特定情况才需要语音 |
| 实时性 | 边生成边播放 | 完整文本后合成 |
| 控制权 | 开发者配置 | Agent 自主决定 |

**选择建议**：
- 需要"边说边回复"效果 → 使用 `TTSHook`
- 需要 Agent 自主判断何时说话 → 使用 `DashScopeMultiModalTool`
- 两者可以组合使用：Agent 正常回复用 Hook 朗读，特殊情况用工具生成特定语音

### Q: DashScopeMultiModalTool 返回的音频如何播放？

```java
Msg response = agent.call(Msg.user("请用语音说'你好'")).block();

for (ContentBlock block : response.getContent()) {
    if (block instanceof AudioBlock audio) {
        if (audio.getSource() instanceof Base64Source src) {
            // Base64 数据，可以解码后播放
            byte[] data = Base64.getDecoder().decode(src.getData());
            // 播放或保存...
        } else if (audio.getSource() instanceof URLSource src) {
            // URL 链接，可以下载后播放
            String url = src.getUrl();
            // 下载或直接播放...
        }
    }
}
```

