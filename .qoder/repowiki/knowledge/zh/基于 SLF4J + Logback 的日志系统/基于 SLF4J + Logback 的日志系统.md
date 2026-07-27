---
kind: logging_system
name: 基于 SLF4J + Logback 的日志系统
slug: logging_system
category: logging_system
scope:
    - '**'
---

## 1. 使用的系统与框架
- 日志门面：SLF4J（`org.slf4j:slf4j-api`，版本由 `agentscope-dependencies-bom` 统一为 `2.0.17`）
- 默认实现：Logback（示例工程通过 `logback.xml` 提供 ConsoleAppender 与 pattern 配置）
- 核心模块仅依赖 `slf4j-api`，不绑定具体实现，属于无侵入的日志抽象层；应用侧自行引入 logback 或其它桥接实现。

## 2. 关键文件与位置
- 核心 POM 声明：`agentscope-core/pom.xml`（引入 `slf4j-api`）
- 全局版本治理：`agentscope-dependencies-bom/pom.xml`（集中管理 `slf4j.version` 及 BOM 中 slf4j 相关依赖）
- 示例 Logback 配置：`agentscope-examples/documentation/src/main/resources/logback.xml`
- 典型使用点（示例）：`ReActAgent.java`、`AbstractBaseFormatter.java`、`MediaUtils.java`、`JsonlTraceExporter.java`、`StaticLongTermMemoryHook.java`、`ModelRegistry.java`、`HttpTransportFactory.java` 等

## 3. 架构与约定
- 门面解耦：core 与扩展模块只依赖 `slf4j-api`，不直接依赖 logback，避免对下游产生运行时绑定压力；示例/应用通过自身 `logback.xml` 控制输出格式与级别。
- Logger 实例命名：各组件以 `private static final Logger log = LoggerFactory.getLogger(XXX.class);` 形式持有 logger，变量名多为 `log`/`logger`/`LOG`，未做统一封装类。
- 日志级别策略：代码中广泛使用 `debug/info/warn/error` 四级，用于记录模型调用流程、工具执行、异常路径等；未见自定义 Level 或结构化字段封装。
- 输出格式：示例 `logback.xml` 采用控制台 Appender，pattern 为 `%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n`，并显式将 `io.agentscope` 包设为 INFO 级别，root 也为 INFO。
- 第三方依赖处理：部分扩展（如 DashScope model）在测试范围引入 `slf4j-simple` 以满足编译期依赖，生产环境仍交由上层应用决定具体实现。

## 4. 开发者应遵循的规则
- 统一使用 SLF4J API：通过 `org.slf4j.LoggerFactory` 获取 logger，不要直接使用 logback/jul/log4j 的具体类。
- 合理选择日志级别：调试信息用 `debug`，正常流程用 `info`，可恢复异常/降级用 `warn`，不可恢复错误用 `error` 并附带异常对象。
- 避免打印敏感数据：当前代码存在直接拼接消息体的做法（如 `log.debug("Structured output generated: {}", contentText)`），在生产环境中应避免输出大文本或用户隐私内容。
- 保持包级日志开关一致：如需调整 AgentScope 内部日志，建议在应用侧通过 `logback.xml` 将 `io.agentscope` 子包按需调至 DEBUG/TRACE，而非修改源码。
- 扩展模块同样遵循门面原则：新增扩展时仅依赖 `slf4j-api`，不在 core 中引入具体实现，确保多应用可自由替换后端。