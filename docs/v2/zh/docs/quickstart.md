---
title: "快速开始"
description: "快速上手 AgentScope Java 2.0 —— 用 HarnessAgent 跑通第一个长期运行的智能体"
---

## 安装

AgentScope Java 需要 JDK 17 及以上版本，构建工具推荐 Maven 3.9+。

### Maven 依赖

`HarnessAgent` 是推荐的入口，把工作区、长期记忆、会话持久化、子 agent、沙箱等工程能力打包在一个 builder 里；依赖 `agentscope-harness` 会自动把核心 `agentscope-core` 一并拉进来：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-harness</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

:::{note}
把 `${agentscope.version}` 替换为最新版本号即可，最新版本请参考 [Release Notes](others/release-notes.md)。
:::

只想跑裸 `ReActAgent`（不需要工作区 / 持久化 / 子 agent / 沙箱），单独依赖 `agentscope-core` 即可。两种用法的区别详见 [Harness 架构](./harness/architecture.md)。

DashScope / OpenAI / Anthropic / Gemini / Ollama 的 formatter 与 chat model 都在 `agentscope-core` 里；MCP 集成需要官方 MCP SDK，参考 `agentscope-examples/documentation/pom.xml`。

## 第一个智能体

下面的例子用 `HarnessAgent` 跑通三件事：**工作区驱动的人格**（`AGENTS.md`）、**会话自动持久化**（相同 `sessionId` 的第二轮记得第一轮）、**对话压缩**（超阈值后自动压缩 + 长期事实落到 `MEMORY.md`）。模型 id 直接以字符串形式传给 `.model(...)`，由 `ModelRegistry` 解析并自动读取对应环境变量。

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Paths;

public class FirstAgent {
    public static void main(String[] args) {
        HarnessAgent agent = HarnessAgent.builder()
                .name("note-taker")
                .sysPrompt("你是一个帮助用户做笔记的助手。")
                // 字符串形式由 ModelRegistry 解析 —— 自动读取 DASHSCOPE_API_KEY；
                // 切换其他厂商时改用 "openai:gpt-5.5"、"anthropic:claude-sonnet-4-5"、
                // "gemini:gemini-2.0-flash" 或 "ollama:llama3"。
                .model("dashscope:qwen-plus")
                .workspace(Paths.get(".agentscope/workspace"))
                .compaction(CompactionConfig.builder()
                        .triggerMessages(30)
                        .keepMessages(10)
                        .build())
                .build();

        RuntimeContext ctx = RuntimeContext.builder()
                .sessionId("demo-session")
                .userId("alice")
                .build();

        // 第一轮：自我介绍 + 当天的事
        agent.call(new UserMessage("我叫天宇，今天准备一个关于 ReAct 的技术分享。"), ctx).block();

        // 第二轮：同 sessionId，自动恢复上一轮状态后回答
        agent.call(new UserMessage("我叫什么？我今天要干什么？"), ctx).block();
    }
}
```

跑完之后你会看到两棵目录树——**工作区**和**状态存储**：

```
.agentscope/workspace/                          ← 工作区（agent 内容）
├── AGENTS.md                                   ← 写一份就是 agent 的人格（不写也能跑）
└── agents/note-taker/
    └── sessions/                               ← 永不压缩的原始对话日志

~/.agentscope/state/note-taker/                 ← 状态存储（在工作区外面）
└── alice/demo-session/                         ← AgentState 自动写回 / 加载
    └── agent_state.json
```

`AgentState` 默认存储在**工作区之外**的 `~/.agentscope/state/<agentId>/` 下——因为状态是恢复工作区本身的前提条件（例如沙箱清空后需要先有状态才能重建工作区），不能和工作区数据耦合。进程重启、`sessionId` 不变，第二段对话依然记得第一段。

:::{warning}
默认的 `JsonFileAgentStateStore` 是基于本地文件的实现，适用于开发和单机部署。生产集群环境请使用分布式实现，如 `RedisAgentStateStore`（由 `agentscope-extensions-redis` 提供），或自行实现 `AgentStateStore` 接口。详见[上线指南](./others/going-to-production.md)。
:::

多聊几轮触发压缩后，提炼出来的事实会先落到 `workspace/memory/YYYY-MM-DD.md`，再被周期性合并到 `MEMORY.md`，并在下一轮推理时自动注入 system prompt。

### 流式查看推理与工具调用

把 `call(...)` 换成 `streamEvents(...)` 就能实时拿到文本片段、工具调用等中间事件，适合 Web / TUI 渲染：

```java
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;

agent.streamEvents(new UserMessage("帮我把今天的关键点列三条。"))
        .doOnNext(event -> {
            if (event.getType() == AgentEventType.TEXT_BLOCK_DELTA) {
                // 模型返回的流式文本片段 —— 追加到界面或标准输出
                System.out.print(((TextBlockDeltaEvent) event).getDelta());
            } else if (event.getType() == AgentEventType.TOOL_CALL_START) {
                // 智能体即将调用工具 —— 展示调用信息
                System.out.println("\n[tool] " + ((ToolCallStartEvent) event).getToolCallName());
            }
            // 其他事件：思考块、工具结果、回复结束等
        })
        .blockLast();
```

:::{tip}
运行前在环境变量里设置 `DASHSCOPE_API_KEY`。切换厂商只需改 `.model(...)` 的字符串并设置对应的 API key（`OPENAI_API_KEY`、`ANTHROPIC_API_KEY`、`GEMINI_API_KEY`）。需要更精细地控制超时 / 自定义 endpoint 等参数时，仍可显式 `DashScopeChatModel.builder()...build()` 构造实例后传给 `.model(Model)`。
:::

### 多用户并发

Agent 在调用之间是**无状态的**——同一个实例可以处理不同用户、不同会话的请求。通过 `RuntimeContext` 传入 `userId` / `sessionId`，每次调用自动加载并隔离各自的对话上下文：

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.UserMessage;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import java.nio.file.Path;
import java.nio.file.Paths;

// 应用启动时创建一个 agent 实例（单例即可）
HarnessAgent agent = HarnessAgent.builder()
        .name("note-taker")
        .sysPrompt("你是一个帮助用户做笔记的助手。")
        .model("dashscope:qwen-plus")
        .workspace(Paths.get(".agentscope/workspace"))
        .compaction(CompactionConfig.builder()
                .triggerMessages(30)
                .keepMessages(10)
                .build())
        .build();

// 在 HTTP handler 中——不同请求传入不同 RuntimeContext
agent.call(new UserMessage(userInput), RuntimeContext.builder()
        .sessionId(sessionId)
        .userId(userId)
        .build()).block();
```

同一 `(userId, sessionId)` 的请求自动串行化（不会并发写同一份状态）；不同 session 完全并行。完整生产部署模式（Redis session、沙箱、技能仓库等）参见[上线指南](./others/going-to-production.md)。

## 接下来

- [智能体（Agent）](./building-blocks/agent.md) —— `ReActAgent` 的完整接口、参数、`call` / `streamEvents` / `observe`、人机交互、`AgentStateStore` 配置
- [Harness 架构](./harness/architecture.md) —— `HarnessAgent` 的各项能力如何协作、状态如何流转
- [工作区](./harness/workspace.md) —— `AGENTS.md` / `MEMORY.md` / `skills/` / `subagents/` / `tools.json` 的目录布局与加载机制
- [文件系统](./harness/filesystem.md) —— 本机 + shell / 共享存储 / 沙箱三种部署模式
