# Text-to-Speech (TTS) 语音合成

AgentScope Java 提供了完整的 TTS 能力支持，让 Agent 不仅能思考和回复，还能开口说话。相比于纯文本的场景，语音是更自然的交互方式，适用于 智能客服、车载助手，以及边生成边朗读的实时对话场景。


## 选择合适的模型

**非实时模型**：适合对音频质量要求高、可以接受几秒延迟的场景，例如播客、有声书、短视频配音等。使用 `DashScopeTTSModel` 和 `qwen3-tts-flash` 模型，一次性发送完整文本，等待服务器处理完整个音频后返回，模型可以全局优化整段话的重音、语调和情感，获得最佳的音频质量。


**实时模型**：适合对响应速度要求高、需要边生成边播放的场景，例如 AI 助手、实时翻译等。使用 `DashScopeRealtimeTTSModel` 和 `qwen3-tts-flash-realtime` 模型，流式发送文本块片段，服务器实时返回音频块，延迟更低。虽然是分块合成，但模型也会保留上下文窗口来维持自然度。

## 使用方式

AgentScope 提供三种使用 TTS 的方式：

**ReActAgent 集成**：通过在 ReActAgent 中添加 TTSHook，可以实现 Agent 所有回复的自动朗读，只需添加 TTSHook 就能实现边生成边播放的效果。

**独立使用 TTSModel**：不依赖 Agent，直接调用 TTSModel 进行独立语音合成，可以灵活使用，适合需要单独进行语音转换的场景。

**作为工具使用 DashScopeMultiModalTool**：将 TTS 作为多模态工具提供给 Agent，Agent 可以自行判断在需要时将文字转成语音。

---

## 方式一：ReActAgent 集成

通过在 ReactAgent 添加 TTSHook 的方式，支持 ReactAgent 在回复时自动朗读。

**工作原理**：

- **事件监听机制**：TTSHook 实现了 Hook 接口，监听 Agent 执行过程中的事件。当 Agent 开始推理时触发 `PreReasoningEvent`，生成文本块时触发 `ReasoningChunkEvent`，推理完成时触发 `PostReasoningEvent`。

- **实时流式合成**：在实时模式下，TTSHook 监听 `ReasoningChunkEvent`，每当 Agent 生成一个文本块时，立即通过 WebSocket 推送到 TTS 模型进行语音合成。这样实现了"边生成边播放"的效果，用户几乎感觉不到延迟。

- **会话生命周期管理**：在第一次收到文本块时，TTSHook 会启动 TTS 会话（建立 WebSocket 连接）并订阅音频流。当 Agent 推理完成时，调用 `finish()` 提交剩余文本并关闭会话，确保所有音频都被合成和播放。

- **音频分发机制**：生成的音频块通过三种方式分发：1) 发送到响应式流（`audioSink`），供 SSE/WebSocket 前端订阅；2) 调用 `audioCallback` 回调函数，用于自定义处理；3) 通过 `AudioPlayer` 本地播放，适用于 CLI/桌面应用。

- **播放中断处理**：当新的推理开始时（`PreReasoningEvent`），TTSHook 会中断当前正在播放的音频，关闭旧的 TTS 会话，确保新回复的音频能够立即开始播放，避免音频混乱。

### 本地播放模式（CLI/桌面应用）

使用 WebSocket 实时流式合成，支持边生成边播放：

```java
// 1. 创建实时 TTS 模型（WebSocket 流式）
DashScopeRealtimeTTSModel ttsModel = DashScopeRealtimeTTSModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("qwen3-tts-flash-realtime")  // WebSocket 实时模型
    .voice("Cherry")
    .mode(DashScopeRealtimeTTSModel.SessionMode.SERVER_COMMIT)  // 服务端自动提交
    .build();

// 2. 创建 TTS Hook
TTSHook ttsHook = TTSHook.builder().ttsModel(ttsModel).build();

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

在 Web 应用中，音频需要发送到前端播放，可以将音频通过 SSE 发送到前端，或者使用响应式流，完整的代码可以参考 `agentscope-examples/chat-tts` 模块，包含前后端交互。

---

## 方式二：独立使用 TTSModel

不依赖 Agent，直接调用 TTS 模型进行语音合成。

### 2.1 非实时模式

适合一次性返回完整音频：

```java
// 创建 TTS 模型
DashScopeTTSModel ttsModel = DashScopeTTSModel.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("qwen3-tts-flash")
    .build();

// 合成语音
TTSOptions options = TTSOptions.builder().voice("Cherry").sampleRate(24000).format("wav").build();

TTSResponse response = ttsModel.synthesize("你好，欢迎使用语音合成功能！", options).block();

// 获取音频数据
byte[] audioData = response.getAudioData();
AudioBlock audioBlock = response.toAudioBlock();
```

### 2.2 实时模式 - 增量输入（Push/Finish 模式）

适用于 LLM 流式输出场景，边接收文本边合成：

```java
// 创建实时 TTS 模型
DashScopeRealtimeTTSModel ttsModel = DashScopeRealtimeTTSModel.builder()
    .apiKey(apiKey)
    .modelName("qwen3-tts-flash-realtime")
    .voice("Cherry")
    .mode(DashScopeRealtimeTTSModel.SessionMode.SERVER_COMMIT)  // 服务端自动提交
    .languageType("Auto")  // 自动检测语言
    .build();

// 创建音频播放器
AudioPlayer player = AudioPlayer.builder().sampleRate(24000).build();

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

## 完整示例

- 快速开始：`agentscope-examples/quickstart/TTSExample.java`
- 完整示例：`agentscope-examples/chat-tts` 模块，包含前后端交互

---

## 核心组件配置参数

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
| ttsModel | DashScopeRealtimeTTSModel | - | TTS 模型（必需） |
| audioPlayer | AudioPlayer | null | 本地播放器（可选） |
| audioCallback | Consumer<AudioBlock> | null | 音频回调（可选） |
| realtimeMode | boolean | true | 是否启用实时模式 |
| autoStartPlayer | boolean | true | 是否自动启动播放器 |