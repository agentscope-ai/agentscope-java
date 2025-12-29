# Multimodal

Multimodal capabilities enable Agents to understand and generate images, audio, video, and other media content.

---

## Core Features

- **Architecture Unified**: ContentBlock system handles text, images, audio, and video uniformly
- **Flexible Sources**: Support both Base64 encoding and URL media loading methods
- **Mixed Messages**: Single message can contain multiple media types and text
- **Model Adaptation**: Automatic conversion to different model API format requirements

---

## Core Concepts

### ContentBlock Architecture

AgentScope uses a unified ContentBlock system to handle all types of content:

```
ContentBlock (Base Class)
├── TextBlock          - Text content
├── ImageBlock         - Image content
├── AudioBlock         - Audio content
├── VideoBlock         - Video content
├── ThinkingBlock      - Reasoning process
├── ToolUseBlock       - Tool invocation
└── ToolResultBlock    - Tool result
```

### Media Sources

Two media source methods are supported:
- **Base64 Encoding**: Encode media files as strings (recommended, best compatibility)
- **URL Reference**: Reference via HTTP/HTTPS URL or local file path

---

## Quick Start

### Step 1: Create Media Content Blocks

```java
import io.agentscope.core.message.*;
import java.util.Base64;
import java.nio.file.Files;
import java.nio.file.Paths;

// Image: Base64 method (recommended)
String base64Image = Base64.getEncoder().encodeToString(
    Files.readAllBytes(Paths.get("image.png"))
);
ImageBlock imageBlock = ImageBlock.builder()
    .source(Base64Source.builder()
        .data(base64Image)
        .mediaType("image/png")
        .build())
    .build();

// Image: URL method
ImageBlock urlImage = ImageBlock.builder()
    .source(URLSource.builder()
        .url("https://example.com/image.jpg")
        .build())
    .build();

// Audio
AudioBlock audioBlock = AudioBlock.builder()
    .source(Base64Source.builder()
        .data(base64AudioData)
        .mediaType("audio/mp3")
        .build())
    .build();

// Video
VideoBlock videoBlock = VideoBlock.builder()
    .source(URLSource.builder()
        .url("https://example.com/video.mp4")
        .build())
    .build();
```

**Supported MIME Types**:
- Images: `image/png`, `image/jpeg`, `image/gif`, `image/webp`
- Audio: `audio/mp3`, `audio/wav`, `audio/mpeg`
- Video: `video/mp4`, `video/mpeg`

### Step 2: Build Multimodal Messages

```java
// Single image message
Msg singleImageMsg = Msg.builder()
    .role(MsgRole.USER)
    .content(List.of(
        TextBlock.builder().text("What color is this image?").build(),
        imageBlock
    ))
    .build();

// Multiple images message
Msg multiImageMsg = Msg.builder()
    .role(MsgRole.USER)
    .content(List.of(
        TextBlock.builder().text("Compare these two images").build(),
        ImageBlock.builder().source(base64Source1).build(),
        ImageBlock.builder().source(base64Source2).build()
    ))
    .build();
```

### Step 3: Configure Vision Agent

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeChatFormatter;
import io.agentscope.core.model.DashScopeChatModel;

ReActAgent agent = ReActAgent.builder()
    .name("VisionAssistant")
    .sysPrompt("You are an AI assistant with vision capabilities.")
    .model(DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-vl-max")
        .stream(true)
        .formatter(new DashScopeChatFormatter())  // Required
        .build())
    .build();

// Send request
Msg response = agent.call(singleImageMsg).block();
System.out.println(response.getTextContent());
```

**Key Configuration**:
- DashScope vision models **require** `DashScopeChatFormatter`
- Base64-encoded images are recommended for best compatibility

---

## Complete Example

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
        
        // 1. Create Vision Agent
        ReActAgent agent = ReActAgent.builder()
            .name("VisionAssistant")
            .sysPrompt("You are an AI assistant with vision capabilities.")
            .model(DashScopeChatModel.builder()
                .apiKey(apiKey)
                .modelName("qwen-vl-max")
                .stream(true)
                .formatter(new DashScopeChatFormatter())
                .build())
            .memory(new InMemoryMemory())
            .toolkit(new Toolkit())
            .build();
        
        // 2. Create multimodal message
        String base64Image = "iVBORw0KGgoAAAANSUhEUgAAABQAAAAUCAIAAAAC64pa...";
        
        Msg userMsg = Msg.builder()
            .role(MsgRole.USER)
            .content(List.of(
                TextBlock.builder()
                    .text("What color is this image?")
                    .build(),
                ImageBlock.builder()
                    .source(Base64Source.builder()
                        .data(base64Image)
                        .mediaType("image/png")
                        .build())
                    .build()
            ))
            .build();
        
        // 3. Send request and get response
        Msg response = agent.call(userMsg).block();
        System.out.println(response.getTextContent());
    }
}
```

---

## Supported Models

### DashScope (Alibaba Cloud)

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

## More Resources

- **Complete Example Code**: [VisionExample.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/VisionExample.java)
- **Message Mechanism**: [message.md](../quickstart/message.md) - Learn about message structure
- **Model Configuration**: [model.md](./model.md) - Learn about model configuration options
