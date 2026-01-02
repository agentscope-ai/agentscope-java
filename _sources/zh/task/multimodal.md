# 多模态（Multimodal）

多模态功能使 Agent 能够理解和生成图像、音频、视频等多种媒体内容。

---

## 核心特性

- **架构统一**：ContentBlock 体系统一处理文本、图像、音频、视频
- **灵活来源**：支持 Base64 编码和 URL 两种媒体加载方式
- **混合消息**：单条消息可包含多种媒体类型和文本
- **模型适配**：自动转换为不同模型 API 的格式要求

---

## 核心概念

### ContentBlock 架构

AgentScope 使用统一的 ContentBlock 体系处理所有类型的内容：

```
ContentBlock (基类)
├── TextBlock          - 文本内容
├── ImageBlock         - 图像内容
├── AudioBlock         - 音频内容
├── VideoBlock         - 视频内容
├── ThinkingBlock      - 推理过程
├── ToolUseBlock       - 工具调用
└── ToolResultBlock    - 工具结果
```

### 媒体来源

支持两种媒体来源方式：
- **Base64 编码**：将媒体文件编码为字符串（推荐，兼容性好）
- **URL 引用**：通过 HTTP/HTTPS URL 或本地文件路径引用

---

## 快速开始

### 步骤 1：创建媒体内容块

```java
import io.agentscope.core.message.*;
import java.util.Base64;
import java.nio.file.Files;
import java.nio.file.Paths;

// 图像：Base64 方式（推荐）
String base64Image = Base64.getEncoder().encodeToString(
    Files.readAllBytes(Paths.get("image.png"))
);
ImageBlock imageBlock = ImageBlock.builder()
    .source(Base64Source.builder()
        .data(base64Image)
        .mediaType("image/png")
        .build())
    .build();

// 图像：URL 方式
ImageBlock urlImage = ImageBlock.builder()
    .source(URLSource.builder()
        .url("https://example.com/image.jpg")
        .build())
    .build();

// 音频
AudioBlock audioBlock = AudioBlock.builder()
    .source(Base64Source.builder()
        .data(base64AudioData)
        .mediaType("audio/mp3")
        .build())
    .build();

// 视频
VideoBlock videoBlock = VideoBlock.builder()
    .source(URLSource.builder()
        .url("https://example.com/video.mp4")
        .build())
    .build();
```

**支持的 MIME 类型**：
- 图像：`image/png`、`image/jpeg`、`image/gif`、`image/webp`
- 音频：`audio/mp3`、`audio/wav`、`audio/mpeg`
- 视频：`video/mp4`、`video/mpeg`

### 步骤 2：构建多模态消息

```java
// 单张图像消息
Msg singleImageMsg = Msg.builder()
    .role(MsgRole.USER)
    .content(List.of(
        TextBlock.builder().text("这张图片是什么颜色？").build(),
        imageBlock
    ))
    .build();

// 多张图像消息
Msg multiImageMsg = Msg.builder()
    .role(MsgRole.USER)
    .content(List.of(
        TextBlock.builder().text("比较这两张图片").build(),
        ImageBlock.builder().source(base64Source1).build(),
        ImageBlock.builder().source(base64Source2).build()
    ))
    .build();
```

### 步骤 3：配置 Vision Agent

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.model.DashScopeChatModel;

ReActAgent agent = ReActAgent.builder()
    .name("VisionAssistant")
    .sysPrompt("你是一个具有视觉能力的 AI 助手。")
    .model(DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-vl-max")
        .stream(true)
        .formatter(new DashScopeChatFormatter())  // 必需
        .build())
    .build();

// 发送请求
Msg response = agent.call(singleImageMsg).block();
System.out.println(response.getTextContent());
```

**关键配置**：
- DashScope 视觉模型**必须**使用 `DashScopeChatFormatter`
- 推荐使用 Base64 编码图像以获得最佳兼容性

---

## 完整示例

```java
package io.agentscope.examples;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.*;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;
import java.util.List;

public class VisionExample {
    
    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        
        // 1. 创建 Vision Agent
        ReActAgent agent = ReActAgent.builder()
            .name("VisionAssistant")
            .sysPrompt("你是一个具有视觉能力的 AI 助手。")
            .model(DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-vl-max")
                .stream(true)
                .formatter(new DashScopeChatFormatter())
                .build())
            .memory(new InMemoryMemory())
            .toolkit(new Toolkit())
            .build();
        
        // 2. 创建多模态消息
        String base64Image = "iVBORw0KGgoAAAANSUhEUgAAABQAAAAUCAIAAAAC64pa...";
        
        Msg userMsg = Msg.builder()
            .role(MsgRole.USER)
            .content(List.of(
                TextBlock.builder()
                    .text("这张图片是什么颜色？")
                    .build(),
                ImageBlock.builder()
                    .source(Base64Source.builder()
                        .data(base64Image)
                        .mediaType("image/png")
                        .build())
                    .build()
            ))
            .build();
        
        // 3. 发送请求并获取响应
        Msg response = agent.call(userMsg).block();
        System.out.println(response.getTextContent());
    }
}
```

---

## 支持的模型

### DashScope（阿里云）

```java
DashScopeChatModel.builder()
    .modelName("qwen-vl-max")        
    .modelName("qwen-vl-plus")       
    .modelName("qwen-audio-turbo")   
    .formatter(new DashScopeChatFormatter())  
    .build();
```

### OpenAI

```java
.modelName("gpt-4o")               
.modelName("gpt-4-vision-preview")
```

### Anthropic

```java
.modelName("claude-3-opus")        
.modelName("claude-3-sonnet")      
```

---

## 更多资源

- **完整示例代码**: [VisionExample.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/VisionExample.java)
- **消息机制**: [message.md](../quickstart/message.md) - 了解消息结构
- **模型配置**: [model.md](./model.md) - 了解模型配置选项
