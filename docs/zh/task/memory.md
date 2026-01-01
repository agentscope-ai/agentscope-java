# 记忆 (Memory)

## 概述

记忆负责管理 AgentScope 中智能体的对话历史和上下文。AgentScope 提供两种类型的记忆：

- **短期记忆 (Short-term Memory)**：存储当前会话的对话历史，需要结合 Session 进行持久化和恢复
- **长期记忆 (Long-term Memory)**：存储跨会话的用户偏好和知识，依赖外部记忆组件（如 Mem0、ReMe）自动持久化

## 记忆架构

在 ReActAgent 中，短期记忆与长期记忆协同工作：

```
┌──────────────┐    ┌──────────────┐    ┌──────────┐    ┌──────────────┐    ┌──────────────┐
│   用户输入    │───▶│   短期记忆     │───▶│   LLM    │───▶│   短期记忆    │───▶│   回复用户     │
└──────────────┘    │   (Memory)   │    │  (推理)  │     │   (Memory)   │    └──────────────┘
                    └───────┬──────┘    └──────────┘    └───────┬──────┘
                            │                                   │
                            │ 召回记忆                          │ 异步存入
                            ▼                                   ▼
                    ┌───────────────────────────────────────────────────┐
                    │              长期记忆 (独立组件)                     │
                    └───────────────────────────────────────────────────┘
```

**分工说明**：

- **短期记忆**：存储当前会话消息，提供给 LLM 作为上下文，支持推理循环

- **长期记忆**（独立组件）：
  - 内部集成 LLM（记忆提取/总结）和向量数据库（存储/检索）
  - **召回**：对话开始时，召回相关记忆注入短期记忆
  - **存储**：回复用户后，异步存入长期记忆进行提取和持久化

## Memory 接口

所有短期记忆实现都扩展 `Memory` 接口：

```java
public interface Memory extends StateModule {
    void addMessage(Msg message);
    List<Msg> getMessages();
    void deleteMessage(int index);
    void clear();
}
```

`Memory` 继承自 `StateModule`，支持状态序列化和反序列化，可结合 `SessionManager` 实现持久化。

## 短期记忆 (Short-term Memory)

### InMemoryMemory

默认的短期记忆实现，在内存中存储消息。

**特点**：
- 简单的内存存储
- 没有上下文管理能力，消息会无限增长
- 适用于简单短对话场景

**使用示例**：

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;

ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .memory(new InMemoryMemory())
        .build();

// 消息自动存储
agent.call(msg1).block();
agent.call(msg2).block();

// 访问历史
List<Msg> history = agent.getMemory().getMessages();
System.out.println("消息总数: " + history.size());
```

### AutoContextMemory

智能上下文内存管理系统，自动压缩、卸载和摘要对话历史。

**特点**：
- 具备上下文管理能力，自动控制 token 使用量
- 6 种渐进式压缩策略
- 支持大型消息卸载和按需重载
- 适用于长对话、token 成本优化、复杂 Agent 任务

**核心特性**：
- 自动压缩：当消息数量或 token 数量超过阈值时自动触发
- 智能摘要：使用 LLM 模型智能摘要历史对话
- 内容卸载：将大型内容卸载到外部存储，通过 UUID 按需重载
- 双存储机制：工作存储（压缩后）和原始存储（完整历史）

**使用示例**：

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.autocontext.AutoContextConfig;
import io.agentscope.core.memory.autocontext.AutoContextMemory;
import io.agentscope.core.memory.autocontext.ContextOffloadTool;
import io.agentscope.core.tool.Toolkit;

// 配置
AutoContextConfig config = AutoContextConfig.builder()
        .msgThreshold(30)
        .lastKeep(10)
        .tokenRatio(0.3)
        .build();

// 创建内存
AutoContextMemory memory = new AutoContextMemory(config, model);

// 注册上下文重载工具
Toolkit toolkit = new Toolkit();
toolkit.registerTool(new ContextOffloadTool(memory));

// 创建 Agent
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .memory(memory)
        .toolkit(toolkit)
        .build();
```

**详细文档**：[AutoContextMemory 详细文档](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-extensions/agentscope-extensions-autocontext-memory/README_zh.md)

### 短期记忆持久化

短期记忆需要结合 `SessionManager` 实现持久化，以支持重启恢复会话。

```java
import io.agentscope.core.session.JsonSession;
import io.agentscope.core.session.SessionManager;

// 创建 Agent 和 Memory
InMemoryMemory memory = new InMemoryMemory();
ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .memory(memory)
        .build();

// 创建 SessionManager，注册需要持久化的组件
SessionManager sessionManager = SessionManager.forSessionId(sessionId)
        .withSession(new JsonSession(sessionPath))
        .addComponent(agent)
        .addComponent(memory);

// 加载已有会话（如果存在）
sessionManager.loadIfExists();

// ... 对话交互 ...

// 保存会话
sessionManager.saveSession();
```

**完整示例**：`agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/SessionExample.java`

## 长期记忆 (Long-term Memory)

### LongTermMemory 接口

长期记忆用于存储和召回跨会话的用户偏好和知识：

```java
public interface LongTermMemory {
    // 记录消息到长期记忆（框架在 Agent 回复后自动调用）
    Mono<Void> record(List<Msg> msgs);
    
    // 根据输入消息检索相关记忆（框架在推理前自动调用）
    Mono<String> retrieve(Msg msg);
}
```

**持久化说明**：长期记忆依赖外部记忆组件（如 Mem0、ReMe 服务），数据自动持久化到外部存储，无需手动管理。

**LongTermMemoryMode**：

在 ReActAgent 中配置长期记忆的工作模式：

- `STATIC_CONTROL`：静态控制模式，框架自动在推理前召回记忆、回复后记录记忆
- `AGENT_CONTROL`：Agent 控制模式，通过工具让 Agent 自主决定何时记录和召回
- `BOTH`：同时启用两种模式

### Mem0LongTermMemory

基于 [Mem0](https://mem0.ai/) 的长期记忆实现。

#### 背景说明

Mem0 的自建部署和 Platform 提供的 OpenAPI 接口不一致（端点路径和响应格式不同）。`Mem0LongTermMemory` 内部提供了兼容适配机制，通过 `apiType` 参数指定 Mem0 部署类型，自动选择正确的 API 端点和响应解析方式。

#### 使用示例

**Platform Mem0（默认）**：

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.mem0.Mem0LongTermMemory;

// 使用 Platform Mem0（默认，无需指定 apiType）
Mem0LongTermMemory longTermMemory = Mem0LongTermMemory.builder()
        .agentName("SmartAssistant")
        .userId("user-001")
        .apiBaseUrl("https://api.mem0.ai")
        .apiKey(System.getenv("MEM0_API_KEY"))
        .build();

ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .longTermMemory(longTermMemory)
        .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
        .build();
```

**自建 Mem0**：

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.mem0.Mem0ApiType;
import io.agentscope.core.memory.mem0.Mem0LongTermMemory;

// 使用自建 Mem0，需要指定 apiType 为 Mem0ApiType.SELF_HOSTED
Mem0LongTermMemory selfHostedMemory = Mem0LongTermMemory.builder()
        .agentName("SmartAssistant")
        .userId("user-001")
        .apiBaseUrl("http://localhost:8000")  // 自建 Mem0 服务地址
        .apiKey(System.getenv("MEM0_API_KEY"))  // 可选，取决于自建服务配置
        .apiType(Mem0ApiType.SELF_HOSTED)  // 指定为自建 Mem0
        .build();

ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .longTermMemory(selfHostedMemory)
        .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
        .build();
```

**配置说明**：

- `apiType`：可选参数，指定 Mem0 部署类型
  - `Mem0ApiType.PLATFORM`（默认）：使用 Platform Mem0 的 API 端点
  - `Mem0ApiType.SELF_HOSTED`：使用自建 Mem0 的 API 端点
- `apiBaseUrl`：Mem0 服务的基地址
  - Platform Mem0：通常为 `https://api.mem0.ai`
  - 自建 Mem0：通常为 `http://localhost:8000` 或您的服务器地址
- `apiKey`：API 密钥（可选）
  - Platform Mem0：必需
  - 自建 Mem0：取决于您的服务配置，可能不需要

**完整示例**：`agentscope-examples/advanced/src/main/java/io/agentscope/examples/advanced/Mem0Example.java`

**运行示例**：

```bash
# Platform Mem0（默认）
export MEM0_API_KEY=your_api_key
export MEM0_API_BASE_URL=https://api.mem0.ai  # 可选，默认为此值
cd agentscope-examples/advanced
mvn exec:java -Dexec.mainClass="io.agentscope.examples.advanced.Mem0Example"

# 自建 Mem0
export MEM0_API_KEY=your_api_key  # 可选，取决于服务配置
export MEM0_API_BASE_URL=http://localhost:8000
export MEM0_API_TYPE=self-hosted
cd agentscope-examples/advanced
mvn exec:java -Dexec.mainClass="io.agentscope.examples.advanced.Mem0Example"
```

### ReMeLongTermMemory

基于 [ReMe](https://github.com/agentscope-ai/ReMe) 的长期记忆实现。

**使用示例**：

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.LongTermMemoryMode;
import io.agentscope.core.memory.reme.ReMeLongTermMemory;

ReMeLongTermMemory longTermMemory = ReMeLongTermMemory.builder()
        .userId("example_user")
        .apiBaseUrl("http://localhost:8002")
        .build();

ReActAgent agent = ReActAgent.builder()
        .name("Assistant")
        .model(model)
        .longTermMemory(longTermMemory)
        .longTermMemoryMode(LongTermMemoryMode.STATIC_CONTROL)
        .build();
```

**完整示例**：`agentscope-examples/advanced/src/main/java/io/agentscope/examples/advanced/ReMeExample.java`

**运行示例**：

```bash
# 需要配置 REME_API_BASE_URL 环境变量（可选，默认为 http://localhost:8002）
cd examples/advanced
mvn exec:java -Dexec.mainClass="io.agentscope.examples.advanced.ReMeExample"
```

## 相关文档

- [AutoContextMemory 详细文档](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-extensions/agentscope-extensions-autocontext-memory/README_zh.md)
- [Session 管理](./session.md)
- [ReActAgent 使用指南](./react-agent.md)
