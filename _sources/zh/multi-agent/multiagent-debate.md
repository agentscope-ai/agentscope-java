# 多智能体辩论

多智能体辩论是一种工作流模式，模拟不同智能体之间的多轮讨论。这种模式特别适用于需要多角度思考才能得出更好解决方案的问题求解任务。

## 概述

辩论工作流通常包括：

- **求解智能体（辩论者）**：生成并交换他们的答案，从不同角度进行论证
- **聚合智能体（主持人）**：收集和评估论点，决定何时达成正确答案

这种模式受到研究启发，研究表明多智能体辩论可以提高大语言模型的推理准确性（参考："Encouraging Divergent Thinking in Large Language Models through Multi-Agent Debate", EMNLP 2024）。

## 架构

```
┌─────────────────────────────────────────────────────────────┐
│                        辩论循环                              │
│                                                             │
│   ┌─────────┐    MsgHub     ┌─────────┐                    │
│   │ 辩论者  │ ◄──────────► │ 辩论者  │                    │
│   │  Alice  │    广播       │   Bob   │                    │
│   └────┬────┘              └────┬────┘                    │
│        │                        │                          │
│        └──────────┬─────────────┘                          │
│                   ▼                                         │
│            ┌────────────┐                                   │
│            │   主持人   │ ── 结构化输出 ──► 是否结束？      │
│            └────────────┘                                   │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

## 实现

### 步骤 1：创建辩论者智能体

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;

// 定义辩论主题
String topic = """
    两个圆外切且没有相对滑动。圆 A 的半径是圆 B 半径的 1/3。
    圆 A 绕圆 B 滚动一圈回到起点。圆 A 总共会自转多少圈？
    """;

// 创建模型，使用 MultiAgentFormatter 支持多智能体通信
DashScopeChatModel model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen3-max")
        .formatter(new DashScopeMultiAgentFormatter())
        .build();

// 创建辩论者 Alice
ReActAgent alice = ReActAgent.builder()
        .name("Alice")
        .sysPrompt(String.format("""
            你是一名叫 Alice 的辩论者。欢迎来到辩论赛。
            你不必完全同意对方的观点，因为我们的目标是找到正确答案。
            辩论主题是：%s
            """, topic))
        .model(model)
        .memory(new InMemoryMemory())
        .build();

// 创建辩论者 Bob
ReActAgent bob = ReActAgent.builder()
        .name("Bob")
        .sysPrompt(String.format("""
            你是一名叫 Bob 的辩论者。欢迎来到辩论赛。
            你不必完全同意对方的观点，因为我们的目标是找到正确答案。
            辩论主题是：%s
            """, topic))
        .model(model)
        .memory(new InMemoryMemory())
        .build();
```

### 步骤 2：创建主持人智能体

主持人评估辩论并决定何时找到正确答案：

```java
// 创建主持人智能体
ReActAgent moderator = ReActAgent.builder()
        .name("Moderator")
        .sysPrompt(String.format("""
            你是一名主持人。将有两位辩论者参与辩论。
            他们将就以下主题发表答案并讨论观点：
            ```
            %s
            ```
            在每轮结束时，你将评估双方的答案并决定哪个是正确的。
            如果你确定了正确答案，将 'finished' 设为 true 并提供 'correctAnswer'。
            """, topic))
        .model(model)
        .memory(new InMemoryMemory())
        .build();
```

### 步骤 3：定义判决的结构化输出

使用结构化输出类来捕获主持人的决定：

```java
/**
 * 主持人判决的结构化输出模型。
 */
public class JudgeResult {
    /**
     * 辩论是否已得出结论。
     */
    public boolean finished;

    /**
     * 如果辩论结束，正确答案是什么。
     */
    public String correctAnswer;
}
```

### 步骤 4：实现辩论循环

```java
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.pipeline.MsgHub;

public void runDebate() {
    int maxRounds = 5;

    for (int round = 1; round <= maxRounds; round++) {
        System.out.println("\n=== 第 " + round + " 轮 ===\n");

        // 辩论者在 MsgHub 中讨论
        // 他们的消息会自动广播给对方
        try (MsgHub hub = MsgHub.builder()
                .participants(alice, bob, moderator)
                .build()) {

            hub.enter().block();

            // Alice 发表观点（正方）
            Msg aliceMsg = alice.call(Msg.builder()
                    .name("user")
                    .role(MsgRole.USER)
                    .content(TextBlock.builder()
                            .text("你是正方。请发表你的观点。")
                            .build())
                    .build()).block();
            System.out.println("Alice: " + aliceMsg.getTextContent());

            // Bob 发表观点（反方）
            Msg bobMsg = bob.call(Msg.builder()
                    .name("user")
                    .role(MsgRole.USER)
                    .content(TextBlock.builder()
                            .text("你是反方。你可以提出不同意见。请说明你的理由和答案。")
                            .build())
                    .build()).block();
            System.out.println("Bob: " + bobMsg.getTextContent());
        }

        // 主持人评估（在 MsgHub 外部 - 辩论者不需要看到这个）
        Msg judgeMsg = moderator.call(
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder()
                                .text("现在你已经听取了双方辩论者的观点。" +
                                      "辩论是否结束？你能确定正确答案吗？")
                                .build())
                        .build(),
                JudgeResult.class  // 请求结构化输出
        ).block();

        // 提取结构化判决
        JudgeResult result = judgeMsg.getStructuredData(JudgeResult.class);

        if (result.finished) {
            System.out.println("\n=== 辩论结束 ===");
            System.out.println("正确答案是：" + result.correctAnswer);
            return;
        }

        System.out.println("主持人：辩论继续进入下一轮...");
    }

    System.out.println("\n=== 达到最大轮数，未得出结论 ===");
}
```

## 完整示例

以下是一个完整的可运行示例：

```java
package io.agentscope.examples;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.formatter.dashscope.DashScopeMultiAgentFormatter;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.pipeline.MsgHub;

public class MultiAgentDebateExample {

    public static class JudgeResult {
        public boolean finished;
        public String correctAnswer;
    }

    public static void main(String[] args) {
        // 主题
        String topic = """
            两个圆外切且没有相对滑动。圆 A 的半径是圆 B 半径的 1/3。
            圆 A 绕圆 B 滚动一圈回到起点。圆 A 总共会自转多少圈？
            """;

        // 创建模型
        DashScopeChatModel model = DashScopeChatModel.builder()
                .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                .modelName("qwen3-max")
                .formatter(new DashScopeMultiAgentFormatter())
                .build();

        // 创建辩论者
        ReActAgent alice = ReActAgent.builder()
                .name("Alice")
                .sysPrompt("你是辩论者 Alice。主题：" + topic)
                .model(model)
                .memory(new InMemoryMemory())
                .build();

        ReActAgent bob = ReActAgent.builder()
                .name("Bob")
                .sysPrompt("你是辩论者 Bob。主题：" + topic)
                .model(model)
                .memory(new InMemoryMemory())
                .build();

        // 创建主持人
        ReActAgent moderator = ReActAgent.builder()
                .name("Moderator")
                .sysPrompt("你是主持人，评估关于以下主题的辩论：" + topic)
                .model(model)
                .memory(new InMemoryMemory())
                .build();

        // 运行辩论
        for (int round = 1; round <= 5; round++) {
            System.out.println("\n=== 第 " + round + " 轮 ===\n");

            try (MsgHub hub = MsgHub.builder()
                    .participants(alice, bob, moderator)
                    .build()) {

                hub.enter().block();

                Msg aliceMsg = alice.call(Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder()
                                .text("发表你的观点。")
                                .build())
                        .build()).block();
                System.out.println("Alice: " + aliceMsg.getTextContent());

                Msg bobMsg = bob.call(Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder()
                                .text("回应 Alice 并发表你的观点。")
                                .build())
                        .build()).block();
                System.out.println("Bob: " + bobMsg.getTextContent());
            }

            // 主持人判决
            Msg judgeMsg = moderator.call(
                    Msg.builder()
                            .name("user")
                            .role(MsgRole.USER)
                            .content(TextBlock.builder()
                                    .text("评估辩论。是否有正确答案？")
                                    .build())
                            .build(),
                    JudgeResult.class
            ).block();

            JudgeResult result = judgeMsg.getStructuredData(JudgeResult.class);

            if (result.finished) {
                System.out.println("\n=== 辩论结束 ===");
                System.out.println("答案：" + result.correctAnswer);
                break;
            }
        }
    }
}
```

## 变体

### 多位辩论者

您可以将模式扩展为包含两位以上的辩论者：

```java
try (MsgHub hub = MsgHub.builder()
        .participants(alice, bob, charlie, moderator)
        .build()) {

    hub.enter().block();

    alice.call(prompt).block();
    bob.call(prompt).block();
    charlie.call(prompt).block();
}
```

### 无主持人辩论

对于更简单的场景，您可以运行无主持人的辩论，让辩论者自行达成共识：

```java
// 固定轮数，无主持人评估
for (int round = 0; round < 3; round++) {
    try (MsgHub hub = MsgHub.builder()
            .participants(alice, bob)
            .build()) {

        hub.enter().block();
        alice.call().block();
        bob.call().block();
    }
}

// 最终综合
ReActAgent synthesizer = ReActAgent.builder()
        .name("Synthesizer")
        .sysPrompt("综合所有观点并给出最终答案。")
        .model(model)
        .build();

Msg finalAnswer = synthesizer.call(summaryMessage).block();
```

## 相关文档

- [MsgHub](./msghub.md) - 多智能体对话的消息广播
- [Pipeline](./pipeline.md) - 顺序和并行智能体执行
- [结构化输出](../task/structured-output.md) - 从智能体响应中提取结构化数据
