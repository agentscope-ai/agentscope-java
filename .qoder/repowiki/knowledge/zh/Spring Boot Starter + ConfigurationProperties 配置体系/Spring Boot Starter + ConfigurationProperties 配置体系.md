---
kind: configuration_system
name: Spring Boot Starter + ConfigurationProperties 配置体系
category: configuration_system
scope:
    - '**'
source_files:
    - agentscope-extensions/agentscope-spring-boot-starters/agentscope-spring-boot-starter/src/main/java/io/agentscope/spring/boot/AgentscopeAutoConfiguration.java
    - agentscope-extensions/agentscope-spring-boot-starters/agentscope-spring-boot-starter/src/main/java/io/agentscope/spring/boot/properties/AgentscopeProperties.java
    - agentscope-extensions/agentscope-spring-boot-starters/agentscope-spring-boot-starter/src/main/java/io/agentscope/spring/boot/properties/AgentProperties.java
    - agentscope-extensions/agentscope-spring-boot-starters/agentscope-spring-boot-starter/src/main/java/io/agentscope/spring/boot/properties/ModelProperties.java
    - agentscope-extensions/agentscope-spring-boot-starters/agentscope-dashscope-spring-boot-starter/src/main/java/io/agentscope/spring/boot/dashscope/DashScopeAutoConfiguration.java
    - agentscope-extensions/agentscope-spring-boot-starters/agentscope-dashscope-spring-boot-starter/src/main/java/io/agentscope/spring/boot/dashscope/DashScopeProperties.java
    - agentscope-extensions/agentscope-spring-boot-starters/agentscope-openai-spring-boot-starter/src/main/java/io/agentscope/spring/boot/openai/OpenAIAutoConfiguration.java
    - agentscope-extensions/agentscope-spring-boot-starters/agentscope-openai-spring-boot-starter/src/main/java/io/agentscope/spring/boot/openai/OpenAIProperties.java
---

## 系统概述
AgentScope Java 采用 Spring Boot AutoConfiguration + `@ConfigurationProperties` 作为统一的运行时配置加载机制，通过多模块 starter 按能力域拆分（核心、模型提供商、Nacos、A2A、AGUI 等），以 `agentscope.*` 为统一前缀进行分层组织。

## 核心架构与约定
- **根属性类**：`AgentscopeProperties`（prefix=`agentscope`）聚合 `agent` 和 `model` 两个子命名空间；每个 starter 再定义自己的 `*Properties` 类绑定到 `agentscope.<provider>` 前缀。
- **自动装配入口**：每个 starter 提供一个 `@AutoConfiguration` 类，使用 `@EnableConfigurationProperties` 注册对应 Properties，并通过 `@ConditionalOnProperty(prefix=..., name="enabled", havingValue="true", matchIfMissing=true)` 控制是否生效。
- **Provider 选择**：`agentscope.model.provider` 指定默认 provider（dashscope/openai/gemini/anthropic），各 provider starter 再通过 `@ConditionalOnProperty(prefix="agentscope.<provider>", name="enabled")` 独立开关。
- **Bean 作用域**：Memory、Toolkit 暴露为 `@Scope(PROTOTYPE)` 单例 Bean，ReActAgent 为线程安全 singleton；用户可通过自定义同名 Bean 覆盖默认实现（`@ConditionalOnMissingBean`）。
- **构建期定制**：通过 `ObjectProvider<XxxChatModelBuilderCustomizer>` 注入扩展点，允许应用对 Model Builder 做二次修改。

## 关键文件与包
- `agentscope-spring-boot-starter/.../properties/AgentscopeProperties.java` — 根配置聚合
- `agentscope-spring-boot-starter/.../properties/AgentProperties.java` — Agent 行为（name/sysPrompt/maxIters/enabled）
- `agentscope-spring-boot-starter/.../properties/ModelProperties.java` — 通用 provider 选择
- `agentscope-spring-boot-starter/.../AgentscopeAutoConfiguration.java` — 默认 Memory/Toolkit/ReActAgent 装配
- `agentscope-dashscope-spring-boot-starter/.../DashScopeProperties.java` — DashScope 配置（api-key/model-name/base-url/stream/enable-thinking）
- `agentscope-dashscope-spring-boot-starter/.../DashScopeAutoConfiguration.java` — DashScope Model 装配（默认 provider）
- `agentscope-openai-spring-boot-starter/.../OpenAIProperties.java` — OpenAI 配置（含 endpointPath）
- `agentscope-openai-spring-boot-starter/.../OpenAIAutoConfiguration.java` — OpenAI Model 装配
- 示例 `application.yml` 位于 `agentscope-examples/agents/*/src/main/resources/application.yml`，展示完整配置片段。

## 开发者规则
1. **统一前缀**：所有 AgentScope 相关配置均以 `agentscope.` 开头，避免散落的 `@Value` 硬编码。
2. **显式开关**：新增 starter 必须提供 `*.enabled` 布尔开关，并设置 `matchIfMissing=true` 保持向后兼容。
3. **必填校验**：在 AutoConfiguration 中对缺失的敏感字段（如 api-key、model-name）抛出 `IllegalStateException`，而非静默失败。
4. **覆盖优先**：对外暴露的 Bean 一律加 `@ConditionalOnMissingBean`，允许应用通过同名 Bean 完全接管。
5. **环境变量注入**：Properties 注释中用 `${ENV_VAR}` 形式标注可外部化的环境变量名，便于容器化部署。