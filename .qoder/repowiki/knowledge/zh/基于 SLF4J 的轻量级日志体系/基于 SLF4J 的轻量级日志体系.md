---
kind: logging_system
name: 基于 SLF4J 的轻量级日志体系
category: logging_system
scope:
    - '**'
source_files:
    - agentscope-dependencies-bom/pom.xml
    - agentscope-core/pom.xml
    - agentscope-examples/documentation/src/main/resources/logback.xml
    - agentscope-examples/agents/agentscope-builder/src/main/resources/logback-spring.xml
    - agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-dashscope/pom.xml
    - agentscope-extensions/agentscope-extensions-protocol/agentscope-extensions-a2a/agentscope-extensions-a2a-client/src/main/java/io/agentscope/core/a2a/agent/utils/LoggerUtil.java
---

## 系统概述
AgentScope Java 采用 **SLF4J API + Logback** 作为统一日志抽象与实现，核心模块仅依赖 `slf4j-api`（版本由 BOM 统一管理为 2.0.17），具体绑定由运行期应用提供。仓库未内置全局日志框架实现，而是通过示例/扩展模块各自引入 logback 或 slf4j-simple 完成绑定。

## 关键文件与包
- `agentscope-dependencies-bom/pom.xml` — 集中声明 `slf4j.version=2.0.17`，所有子模块通过 BOM 继承该版本，避免冲突
- `agentscope-core/pom.xml` — 仅声明对 `org.slf4j:slf4j-api` 的依赖，不绑定具体实现
- `agentscope-examples/documentation/src/main/resources/logback.xml` — 文档站点示例：ConsoleAppender + `%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n` 模式，`io.agentscope` 包默认 INFO
- `agentscope-examples/agents/*/src/main/resources/logback-spring.xml` — Spring Boot 示例使用 `springframework.boot.logging.logback` 默认配置，并将 `io.agentscope` 设为 INFO、Spring 安全/WEB 设为 WARN
- `agentscope-extensions-model-dashscope/pom.xml` — 测试场景下引入 `slf4j-simple` 以便在无外部绑定时输出到 stdout
- `agentscope-extensions-protocol/.../a2a-client/.../utils/LoggerUtil.java` — A2A 扩展中封装的日志工具类，提供带 `isXxxEnabled()` 检查的 trace/debug/info/warn/error 静态方法以及事件/消息详情打印辅助方法

## 架构与约定
- **抽象层**：全仓统一使用 `org.slf4j.Logger` / `LoggerFactory.getLogger(...)`，无直接依赖任何具体实现
- **级别策略**：代码中广泛使用 debug/info/warn/error；trace 仅在 WebSocket 传输等高频路径出现；未见自定义级别
- **结构化字段**：未发现 MDC / 上下文追踪字段注入；日志以字符串模板为主，部分通过 `LoggerUtil.logTextMsgDetail` / `logA2aClientEventDetail` 将 AgentScope 事件/消息序列化为 JSON 后输出
- **性能习惯**：`LoggerUtil` 显式在调用前做 `isTraceEnabled()/isDebugEnabled()` 检查，避免不必要的字符串拼接；但 core 模块内大量类仍直接调用 `log.debug(...)` 而省略前置检查，风格尚未完全统一
- **绑定方式**：核心库不绑定实现；示例工程各自提供 `logback.xml` / `logback-spring.xml`；DashScope 扩展在测试 scope 引入 `slf4j-simple` 保证独立可运行

## 开发者应遵循的规则
1. **统一使用 SLF4J**：通过 `import org.slf4j.Logger; import org.slf4j.LoggerFactory;` 获取 logger，禁止直接使用 java.util.logging、Log4j2 等底层 API
2. **谨慎使用 trace**：trace 仅用于网络 I/O 等极高频调试信息；常规业务逻辑使用 debug/info
3. **避免大对象直接入参**：对可能昂贵的序列化（如 Msg、Event）优先用 `LoggerUtil` 提供的专用方法或在 `isDebugEnabled()` 分支内构造参数
4. **不要修改根 logger 级别**：示例工程中 `io.agentscope` 包默认 INFO，生产环境应在应用侧 logback 配置调整，而非在库代码中硬编码
5. **不要在核心模块引入具体绑定**：core 及扩展库只依赖 `slf4j-api`，具体绑定由最终应用或 Spring Boot Starter 负责