# Brain + Hands：Sandbox 内执行工具

[English](README.en.md) | [示例总览](../../README.md)

一个 Maven 模块打包**两个完全独立的 Spring Boot 应用与 runner**，不通过角色参数切换应用。
离线模型无需 API Key，但**运行任一应用需要真实 RocketMQ 5 Broker 与 gRPC Proxy**。
脚本不创建基础设施或 Topic。

## 进程边界

- **Brain**：`LocalBackend`（SQLite）、`DurableRuntime`、AgentScope ReAct adapter、
  `RocketMqRemoteExecutor`；不构造 worker、Sandbox client 或本地工具 executor。
- **Hands**：独立 `hands.db`、持久化 `SandboxBindings`、`LocalSandboxClient`、
  `RocketMqWorker`（内部管理 `WorkerRuntime`）；不构造 Brain backend、agent 或 model。
- `SandboxTool` 仅共享冻结工具契约、常量与参数：`sandbox.exec`，版本 `v1`，路由 `hands`，
  target 为 `{"operation":"sandbox.exec"}`。Hands 接纳**每条**命令（含控制命令）前验证业务
  namespace、session、工具摘要及固定 command/timeout。
- 资源 binding 为 `(namespace, sessionId, v1, {})`，保存于 Hands 数据库。重启保留原 Hands
  目录与 binding，恢复连接原 Sandbox，不能创建替代资源。

模型请求 `printf once >> count; printf recovered`，`timeout_seconds=30`。调用 ID 为
`sandbox-<turnId>`，同一 Turn 恢复时稳定，不同 Turn 独立。只有 `ToolResultState.SUCCESS`
且结果 JSON 的 `output` 严格为 `recovered` 时，才输出 `Sandbox result applied: recovered`。
失败、取消、未完成或无效结果不能伪装成功。Brain 再校验终态与最终答案，成功、失败、取消
的终态观察均会唤醒等待。

## 构建与启动双进程

前提：Java 17+、Maven、POSIX `sh`、Python 3，以及已运行的 RocketMQ 5 **gRPC Proxy**
（默认 `localhost:8081`，不是 NameServer 地址）。从仓库根目录执行，按部署环境配置
Broker ACL 与认证；这不是生产凭据配置。

两个应用默认使用 `mq-namespace=durable-sandbox`，与 echo 工具场景隔离。按
`RocketMqExecutionTopology`，路由 `hands` 需要预先创建以下普通 Topic：

- `durable-sandbox-hands-commands`
- `durable-sandbox-hands-controls`
- `durable-sandbox-hands-updates`

消费组为 `durable-sandbox-hands-brain`、`durable-sandbox-hands-worker`、
`durable-sandbox-hands-worker-controls`；需授予对应生产/消费权限。两个应用必须使用
相同 MQ namespace、业务 namespace 和 session ID；不要让独立 Brain 数据库混用同一消费组。

```sh
# 不连接 Broker，构建两个可执行 JAR（跳过测试）。
bash agentscope-examples/durable/brain-hands/tool-in-sandbox/run.sh --compile-only

# 终端 1：持续运行，Ctrl-C 关闭。
bash agentscope-examples/durable/brain-hands/tool-in-sandbox/run.sh \
  hands localhost:8081 durable-brain-hands-tool-in-sandbox-data/hands

# 终端 2：验证答案后退出。
bash agentscope-examples/durable/brain-hands/tool-in-sandbox/run.sh \
  brain localhost:8081 durable-brain-hands-tool-in-sandbox-data/brain
```

模块 `agentscope-durable-example-brain-hands-tool-in-sandbox` 在
`agentscope-examples/durable/brain-hands/tool-in-sandbox/target/` 生成：

- `agentscope-durable-example-brain-hands-tool-in-sandbox-brain.jar`
- `agentscope-durable-example-brain-hands-tool-in-sandbox-hands.jar`

两个 JAR 均可独立 `java -jar` 启动，例如：

```sh
java -jar agentscope-examples/durable/brain-hands/tool-in-sandbox/target/agentscope-durable-example-brain-hands-tool-in-sandbox-hands.jar --durable.brain-hands-tool-in-sandbox.endpoint=localhost:8081
java -jar agentscope-examples/durable/brain-hands/tool-in-sandbox/target/agentscope-durable-example-brain-hands-tool-in-sandbox-brain.jar --durable.brain-hands-tool-in-sandbox.endpoint=localhost:8081
```

等待 `tool-in-sandbox hands ready`。Brain 输出 `turn`、`status`、`cursor`、`answer`。
保留两侧目录，重复**完全相同**的 Brain 命令：`request-1` 返回原 Turn，
`hands/sandboxes/<resource-id>/count` 仍为 `once`。新 request ID 有意创建新 Turn，
在同一资源产生新副作用。

脚本选 JAR，不向 Java 传角色；合法接口仅为 `--compile-only` 或
`<brain|hands> <endpoint> [data-directory]`，不保留 `worker` 别名。无效输入在 Maven 前拒绝。
可通过 `DURABLE_MAVEN_REPOSITORY` 指定 Maven 本地仓库。

## 入口、配置与离线测试

源码目录 `src/main/java/io/agentscope/durable/examples/brainhands/toolinsandbox/`：
`SandboxBrainApplication.java` 仅导入 `SandboxBrainExample.java`（装配/runtime/model）；
`SandboxHandsApplication.java` 仅导入 `SandboxHandsExample.java`（worker/bindings/授权）。
两个应用不扫描包。`SandboxTool.java` 是共享工具契约；`SandboxToolProperties.java`
保存角色无关的配置与校验。

配置前缀 `durable.brain-hands-tool-in-sandbox`。`src/main/resources/application.yml` 默认值：
endpoint `localhost:8081`、namespace `demo`、session ID `conversation`、request ID `request-1`、
Brain 等待超时 `60s`、MQ namespace `durable-sandbox`。未指定 `data-directory` 时 Brain 使用
`durable-brain-hands-tool-in-sandbox-data/brain`，Hands 使用同级 `/hands` 目录。
其他属性通过直接 JAR Spring 参数、环境变量或外部 YAML 配置；更换 MQ namespace 时
两侧同时配置并预建派生 Topic。

```sh
mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone \
  -pl :agentscope-durable-example-brain-hands-tool-in-sandbox -am test \
  -Dtest=SandboxToolExampleTest,SandboxRoundTripTest -Dsurefire.failIfNoSpecifiedTests=false
```

离线测试复用**生产 Tool、模型与 Brain harness**，执行真实本地命令，Brain/Hands 使用独立
SQLite。仅传输使用内存 fixture，调用公开 `QueuedExecutor`、`TransactionalExecutionStore`、
`WorkerRuntime` API。测试重复命令/事件、扣留结果交付、关闭重开 backend、records、bindings、
worker、queue，再将结果应用到原 request/Turn；保留成功、最终答案、原资源/handle attachment
与 `count=once` 断言。同一 Thread 的新 request 产生新命令但保留原资源，得到 `onceonce`；
重复提交不再执行。另覆盖不执行 runner 的 Spring 上下文隔离、配置绑定、授权及模型不误报成功。

**这些测试不验证 RocketMQ SDK、Broker、真实双进程传输或进程强杀故障恢复。**
它们仍是独立基础设施验收项，不能用离线覆盖声称通过。

## 资源保留与安全边界

`LocalSandboxClient` 使用宿主用户权限执行命令，**不是容器，也不构成安全隔离边界**。
白名单不能替代调用者认证、Broker ACL 或 OS 隔离，仅在可信开发机器上运行。

Hands 启动 binding 维护，通过 shutdown hook 停止 worker/维护循环。保留默认
`canDelete=false`：关闭进程**不会**请求删除、关闭原 Sandbox 或擦除操作证据。
Sandbox 与 `count` 保留供恢复检查；不确定副作用不能成为在新 workspace 重试的理由。

生产环境应使用真实 `SandboxClient`、稳定资源身份及持久化记录，并在删除前提供覆盖所有
已接纳操作、取消与 UNKNOWN 结果的权威 cleanup barrier。不要无条件清理，也不要在结果
未确认时擦除恢复证据。
