# 消息

消息是 AgentScope 的核心概念，用于支持多模态数据、工具 API、信息存储/交换和提示词构造。

## 消息结构

消息由四个字段组成：

| 字段     | 类型                  | 描述                                                         |
|----------|-----------------------|-------------------------------------------------------------|
| id       | `String`              | 消息的唯一标识符（自动生成）                                    |
| name     | `String`              | 消息发送者的名称/身份                                          |
| role     | `MsgRole`             | 发送者的角色：`USER`、`ASSISTANT`、`SYSTEM` 或 `TOOL`         |
| content  | `List<ContentBlock>`  | 内容块列表（文本、图像、音频、视频、工具调用等）                   |
| metadata | `Map<String, Object>` | 可选的元数据，用于附加信息或结构化输出                            |

> **注意**：
> - 在具有多个身份的应用中，`name` 字段用于区分不同的参与者。
> - `metadata` 字段推荐用于结构化输出，不会包含在提示词构造中。

## 创建消息

### 文本消息

创建包含文本内容的消息最简单的方式：

```java
package io.agentscope.tutorial.quickstart;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.List;

public class MessageExample {
    public static void main(String[] args) {
        Msg msg = Msg.builder()
                .name("Alice")
                .role(MsgRole.USER)
                .content(List.of(TextBlock.builder().text("你好，AgentScope！").build()))
                .build();

        System.out.println("发送者: " + msg.getName());
        System.out.println("角色: " + msg.getRole());
        System.out.println("内容: " + msg.getTextContent());
    }
}
```

### 多模态消息

AgentScope 通过不同的内容块支持多模态内容：

| 块类型          | 描述                 | 示例                                                         |
|----------------|----------------------|-------------------------------------------------------------|
| TextBlock      | 纯文本数据            | `TextBlock.builder().text("你好，世界！")`                                |
| ImageBlock     | 图像数据              | `ImageBlock.builder().source(URLSource.of("https://example.com/img.jpg")).build()`|
| AudioBlock     | 音频数据              | `AudioBlock.builder().source(URLSource.of("https://example.com/audio.mp3")).build()`|
| VideoBlock     | 视频数据              | `VideoBlock.builder().source(URLSource.of("https://example.com/video.mp4")).build()`|
| ThinkingBlock  | 推理内容              | `ThinkingBlock.builder().thinking("让我想想...").build()`                            |
| ToolUseBlock   | 工具调用请求          | 由 LLM 在推理阶段创建                                         |
| ToolResultBlock| 工具执行结果          | 工具执行后创建                                                |

#### 使用 Base64 的图像消息

```java
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Base64Source;

Msg imgMsg = Msg.builder()
        .name("Assistant")
        .role(MsgRole.ASSISTANT)
        .content(List.of(
                TextBlock.builder().text("这是您请求的图像：").build(),
                ImageBlock.builder().source(Base64Source.builder()
                        .mediaType("image/jpeg")
                        .data("/9j/4AAQSkZ...")  // Base64 编码数据
                        .build()).build()
        ))
        .build();
```

#### 使用 URL 的图像消息

```java
import io.agentscope.core.message.URLSource;

Msg urlImgMsg = Msg.builder()
        .name("Assistant")
        .role(MsgRole.ASSISTANT)
        .content(List.of(
                TextBlock.builder().text("正在处理来自 URL 的图像：").build(),
                ImageBlock.builder().source(URLSource.of("https://example.com/image.jpg")).build()
        ))
        .build();
```

### 思考消息

对于支持思维链的推理模型：

```java
import io.agentscope.core.message.ThinkingBlock;

Msg thinkingMsg = Msg.builder()
        .name("Reasoner")
        .role(MsgRole.ASSISTANT)
        .content(List.of(
                ThinkingBlock.builder().thinking("首先，我需要理解这个问题... " +
                                 "然后我应该将其分解为步骤...").build(),
                TextBlock.builder().text("根据我的分析，答案是 42。").build()
        ))
        .build();
```

### 工具调用消息

工具调用消息通常由 LLM 在推理阶段生成：

```java
import io.agentscope.core.message.ToolUseBlock;
import java.util.Map;

Msg toolCallMsg = Msg.builder()
        .name("Assistant")
        .role(MsgRole.ASSISTANT)
        .content(List.of(
                ToolUseBlock.builder()
                        .id("call_123")
                        .name("get_weather")
                        .arguments(Map.of("location", "北京"))
                        .build()
        ))
        .build();
```

### 工具结果消息

工具结果消息在执行工具后生成：

```java
import io.agentscope.core.message.ToolResultBlock;

Msg toolResultMsg = Msg.builder()
        .name("system")
        .role(MsgRole.TOOL)
        .content(List.of(
                ToolResultBlock.builder()
                        .id("call_123")
                        .name("get_weather")
                        .result(TextBlock.builder().text("晴天，25°C").build())
                        .build()
        ))
        .build();
```

> **提示**：有关 AgentScope 中工具 API 的更多信息，请参阅[工具](../task/tool.md)部分。


### 结构化输出

处理 LLM 的结构化输出时：

```java
public class TaskPlan {
    public String goal;
    public List<String> steps;
    public int estimatedHours;
}

// 智能体生成结构化输出后
Msg planMsg = agent.call(inputMsg, TaskPlan.class).block();

// 提取结构化数据
if (planMsg.hasStructuredData()) {
    TaskPlan plan = planMsg.getStructuredData(TaskPlan.class);
    System.out.println("目标: " + plan.goal);
    System.out.println("步骤: " + plan.steps);
}
```

## 下一步

- [智能体](agent.md) - 构建处理消息的智能体
- [工具](../task/tool.md) - 学习工具集成
- [钩子](../task/hook.md) - 自定义消息处理
- [模型](../task/model.md) - 配置 LLM 模型
