---
kind: configuration_system
name: AgentScope Java 配置系统：不可变记录 + Spring Boot Starter 分层装配
slug: configuration_system
category: configuration_system
scope:
    - '**'
---

## 1. 体系概览

AgentScope Java 的配置系统由两层组成：
- **核心层（agentscope-core）**：以不可变 record / Builder 对象承载运行时参数，提供默认值、校验与合并能力，不依赖任何外部框架。
- **装配层（agentscope-spring-boot-starters）**：通过 `@ConfigurationProperties` + `@EnableConfigurationProperties` 将 YAML/环境变量映射到上述不可变配置，再由 AutoConfiguration 组装为 Bean。

示例应用则多采用 `@Value("${...:默认}")` 直接注入，属于“快速上手”的轻量用法，不作为框架约定。

## 2. 核心配置模型

| 配置类 | 作用域 | 关键特性 |
|---|---|---|
| `ModelConfig` | 单次模型调用 | 不可变 record；`maxRetries > 0` 校验；`fallbackModel` 被 `@JsonIgnore` 避免序列化；提供 `defaults()` |
| `ReactConfig` | ReAct 推理循环 | 不可变 record；`max_iters` / `stop_on_reject`；`@JsonCreator` 支持 JSON 反序列化并回退默认值 |
| `ExecutionConfig` | 超时与重试（模型+工具） | Builder 模式；`MODEL_DEFAULTS`（5min/3次/指数退避）、`TOOL_DEFAULTS`（5min/1次）；`mergeConfigs(primary, fallback)` 实现逐字段覆盖 |
| `ToolkitConfig` | 工具执行策略 | Builder；并行/串行、自定义 `ExecutorService`、是否允许删除工具、默认 `ToolExecutionContext`（已弃用） |
| `GracefulShutdownConfig` | 优雅停机 | 不可变 record；`shutdownTimeout` 可为 null（无限等待）；`partialReasoningPolicy` 必填且非空 |

此外还有各扩展的专用配置（如 `HttpTransportConfig`、`WebSocketTransportConfig`、`ProxyConfig`、`RetrieveConfig`、`SubAgentConfig`、`McpClientBuilder.TransportConfig` 等），均遵循“不可变 + 默认值 + 可选”的设计。

## 3. Spring Boot 装配约定

每个 starter 模块遵循统一模式：
- 定义 `@ConfigurationProperties(prefix = "agentscope.xxx")` 的 Properties 类，字段名使用 kebab-case，对应 YAML 键。
- 在 AutoConfiguration 上使用 `@EnableConfigurationProperties(XXXProperties.class)` 启用绑定。
- 由 AutoConfiguration 根据 Properties 构建 core 层的不可变 Config 对象，再注册为 Bean。

典型前缀包括：
- `agentscope.agent-protocol`、`agentscope.admin`、`agentscope.agui`、`agentscope.anthropic`、`agentscope.chat-completions`、`agentscope.dashscope`、`agentscope.gemini`、`agentscope.nacos.*`、`agentscope.a2a.*` 等。

## 4. 配置分层与合并规则

```
per-request ExecutionConfig
  └─ merge(agent-level ExecutionConfig)
       └─ merge(component defaults (MODEL_DEFAULTS / TOOL_DEFAULTS))
            └─ merge(system defaults (JVM/system properties via Spring))
```

- `ExecutionConfig.mergeConfigs` 对每个字段做 `primary != null ? primary : fallback` 覆盖。
- `ReactConfig.fromJson` 对缺失字段回退到 `DEFAULT_MAX_ITERS` / `DEFAULT_STOP_ON_REJECT`。
- `ModelConfig`、`GracefulShutdownConfig` 在构造器中做参数校验，非法值立即抛异常。

## 5. 开发者规范

1. **新增配置**：优先使用不可变 record 或 Builder 类，提供 `defaults()` / 静态常量默认值，并在构造器中做最小合法性校验。
2. **Spring 绑定**：新建 `@ConfigurationProperties(prefix = "agentscope.<feature>")` 类，字段使用 kebab-case，必要时加 `@JsonProperty` 控制 JSON 行为。
3. **AutoConfiguration**：用 `@EnableConfigurationProperties` 显式启用，不要依赖隐式扫描。
4. **合并优先级**：按“请求级 > Agent 级 > 组件默认 > 系统默认”的顺序使用 `mergeConfigs`，避免覆盖掉安全默认值。
5. **敏感字段**：持有连接/凭据的字段应标注 `@JsonIgnore`，防止快照序列化泄露。
6. **示例应用**：可继续使用 `@Value("${key:default}")` 快速演示，但生产代码建议迁移到 `@ConfigurationProperties` 以获得类型安全与 IDE 提示。

## 6. 关键文件

- `agentscope-core/src/main/java/io/agentscope/core/agent/config/ModelConfig.java`
- `agentscope-core/src/main/java/io/agentscope/core/agent/config/ReactConfig.java`
- `agentscope-core/src/main/java/io/agentscope/core/model/ExecutionConfig.java`
- `agentscope-core/src/main/java/io/agentscope/core/tool/ToolkitConfig.java`
- `agentscope-core/src/main/java/io/agentscope/core/shutdown/GracefulShutdownConfig.java`
- `agentscope-core/src/main/java/io/agentscope/core/model/transport/HttpTransportConfig.java`
- `agentscope-core/src/main/java/io/agentscope/core/model/transport/ProxyConfig.java`
- `agentscope-core/src/main/java/io/agentscope/core/model/transport/websocket/WebSocketTransportConfig.java`
- `agentscope-core/src/main/java/io/agentscope/core/rag/model/RetrieveConfig.java`
- `agentscope-core/src/main/java/io/agentscope/core/tool/subagent/SubAgentConfig.java`
- `agentscope-extensions/agentscope-spring-boot-starters/agentscope-dashscope-spring-boot-starter/src/main/java/io/agentscope/spring/boot/dashscope/DashScopeProperties.java`
- `agentscope-extensions/agentscope-spring-boot-starters/agentscope-anthropic-spring-boot-starter/src/main/java/io/agentscope/spring/boot/anthropic/AnthropicProperties.java`
- `agentscope-extensions/agentscope-spring-boot-starters/agentscope-gemini-spring-boot-starter/src/main/java/io/agentscope/spring/boot/gemini/GeminiProperties.java`
- `agentscope-extensions/agentscope-spring-boot-starters/agentscope-agui-spring-boot-starter/src/main/java/io/agentscope/spring/boot/agui/common/AguiProperties.java`
- `agentscope-extensions/agentscope-spring-boot-starters/agentscope-admin-spring-boot-starter/src/main/java/io/agentscope/spring/boot/admin/properties/AdminProperties.java`
- `agentscope-extensions/agentscope-spring-boot-starters/agentscope-a2a-spring-boot-starter/src/main/java/io/agentscope/spring/boot/a2a/properties/A2aCommonProperties.java`
- `agentscope-extensions/agentscope-spring-boot-starters/agentscope-nacos-spring-boot-starter/src/main/java/io/agentscope/spring/boot/nacos/properties/AgentScopeNacosProperties.java`
