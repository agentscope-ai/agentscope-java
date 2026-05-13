# harness-example-local — Filesystem **模式三（本机 + 宿主 Shell）**

本模块是 **CLI 版 Chinook Text-to-SQL** 示例（`TextToSqlExample`），演示 **`HarnessAgent` 不显式配置 `filesystem(...)`** 时的默认行为：与 [`docs/zh/harness/filesystem.md`](../../docs/zh/harness/filesystem.md) 中的 **模式三**（以及 `HarnessAgent.Builder#filesystem(LocalFilesystemSpec)` / 源码注释 **Mode 3**）一致。

---

## 三种 Filesystem 示例对照

| 模式 | 文档（中文） | 典型 `HarnessAgent` 配置 | 本仓库模块 |
|------|----------------|---------------------------|------------|
| **模式一** | 复合 + 共享存储 | `.filesystem(new RemoteFilesystemSpec(store)...)` + 分布式 `Session` | [`harness-example-remote`](../harness-example-remote/README.md) |
| **模式二** | 沙箱 | `.filesystem(sandboxFilesystemSpec)` + `SandboxStateStore` 等 | [`harness-example-sandbox`](../harness-example-sandbox/README.md) |
| **模式三** | 本机 + shell | **不写** `filesystem(...)`，或 `.filesystem(new LocalFilesystemSpec())` | **本模块** |

---

## 模式三在本示例里如何体现

`TextToSqlExample` 构建 Agent 时**没有**调用 `.filesystem(...)`：

```java
HarnessAgent agent =
        HarnessAgent.builder()
                .name("text-to-sql")
                .sysPrompt("...")
                .model(modelId)          // 例如 "dashscope:qwen-max"，经 ModelRegistry 解析
                .workspace(workspace)
                .enableAgentTracingLog(true)
                .toolkit(toolkit)
                .build();
```

`HarnessAgent` 内部 `resolveFilesystem(...)` 在三种 Spec 都未配置时，**直接**返回 **`LocalFilesystemWithShell(workspace, namespaceFactory)`** —— 工作区根在本地目录，**`ShellExecuteTool` 在宿主上执行 `sh -c`**。这与「显式 `new LocalFilesystemSpec().toFilesystem(...)` 且保持默认参数」在能力上等价（显式 Spec 用于调节超时、`virtualMode`、环境变量等）。

**后果（设计上的取舍）**：

- 适合 **单机 / 信任环境 / 本地开发**。
- **不适合**把不受信 shell 暴露给多租户；也不自带跨副本的 `MEMORY.md` 共享（与模式一不同）。

---

## 业务与仓库布局（CLI）

- **入口**：`io.agentscope.harness.example.TextToSqlExample`
- **工作区**：`WorkspaceInitializer` 将 `src/main/resources/workspace/` 解压到磁盘（默认 `.agentscope/workspace`）。
- **工具**：`SqliteTool`（`sql_list_tables` / `sql_get_schema` / `sql_execute_query`）通过 `Toolkit` 注册。
- **Chinook**：classpath 自带 `chinook-default.sqlite`，首次运行可复制到 `AGENTSCOPE_DB_PATH`（默认 `chinook.db`）。

详细目录树与自定义方式见下文「项目布局」与「自定义 Agent」。

---

## 快速开始

### 1. 构建

```bash
cd agentscope-java
mvn -pl agentscope-examples/harness-example-local -am package -DskipTests
```

### 2. 环境变量

```bash
export DASHSCOPE_API_KEY=your_key_here
# 可选：AGENTSCOPE_MODEL（默认 qwen-max）、AGENTSCOPE_WORKSPACE、AGENTSCOPE_DB_PATH
```

或复制 `.env.example` → `.env` 后自行 `export`。

### 3. 运行

本模块为**普通 JAR**（非 Spring Boot fat jar），需把 **`target/classes` + 依赖 classpath** 一并传给 `java`。

在 **`agentscope-examples/harness-example-local`** 目录下：

```bash
mvn package -DskipTests
export CP="target/classes:$(mvn -q -DincludeScope=runtime dependency:build-classpath -Dmdep.outputFile=/dev/stdout)"
java -cp "$CP" io.agentscope.harness.example.TextToSqlExample
```

交互（无参数）；单次问答可在末尾追加问题字符串，例如：

```bash
java -cp "$CP" io.agentscope.harness.example.TextToSqlExample "What are the top 5 best-selling artists?"
```

从仓库根目录构建时：

```bash
cd agentscope-java
mvn -pl agentscope-examples/harness-example-local -am package -DskipTests
cd agentscope-examples/harness-example-local
export CP="target/classes:$(mvn -q -DincludeScope=runtime dependency:build-classpath -Dmdep.outputFile=/dev/stdout)"
java -cp "$CP" io.agentscope.harness.example.TextToSqlExample
```

---

## 项目布局（节选）

```
agentscope-examples/harness-example-local/
├── pom.xml
├── .env.example
├── README.md
└── src/main/
    ├── java/io/agentscope/harness/example/
    │   ├── TextToSqlExample.java
    │   ├── SqliteTool.java
    │   └── WorkspaceInitializer.java
    └── resources/
        ├── log4j2.xml
        ├── io/agentscope/harness/example/chinook-default.sqlite
        └── workspace/
            ├── AGENTS.md
            ├── MEMORY.md
            ├── knowledge/KNOWLEDGE.md
            ├── skills/...
            └── subagents/...
```

---

## 调用与 `RuntimeContext`

```java
RuntimeContext ctx = RuntimeContext.builder().sessionId(sessionId).build();
Msg reply = agent.call(Msg.builder().role(MsgRole.USER).textContent(question).build(), ctx).block();
```

`sessionId` 用于会话级状态与 memory hooks；本 CLI 默认可复用同一 session 以做多轮。

---

## 日志（Log4j2）

模块使用 **Log4j2**（`log4j-slf4j2-impl`）。配置见 `src/main/resources/log4j2.xml`。可通过环境变量 **`AGENTSCOPE_LOG_LEVEL`**（如 `DEBUG`）调整 `io.agentscope.*` 日志级别。

---

## 自定义 Agent

无需重新编译：直接编辑工作区目录下文件即可。

| 文件 | 作用 |
|------|------|
| `AGENTS.md` | 人设与规则 |
| `MEMORY.md` | 预置长期记忆 |
| `knowledge/KNOWLEDGE.md` | 领域知识（如 Chinook  schema） |
| `skills/*/SKILL.md` | 技能流程 |
| `subagents/*.md` | 子 Agent 规格 |

---

## 依赖

| 依赖 | 用途 |
|------|------|
| `agentscope-harness` | HarnessAgent、工具、Hook、工作区 |
| `jackson-dataformat-yaml` | Skill / subagent 的 YAML front matter |
| `sqlite-jdbc` | `SqliteTool` |

---

## License

Apache 2.0 — 见仓库根目录 `LICENSE`。
