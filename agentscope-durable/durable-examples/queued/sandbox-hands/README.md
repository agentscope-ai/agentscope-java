# Queued Sandbox Hands：RocketMQ Brain + Worker

[English](README.en.md) | [示例总览](../../README.md)

这是一个 Spring Boot CLI 示例，Brain 与 Hands Worker **真正运行在两个独立进程**。离线模型无需 API Key，但**应用运行需要真实的 RocketMQ 5 Broker 与 gRPC Proxy**。脚本不会创建 Broker、Proxy 或 Topic。

## 进程边界

- **Brain**：`LocalBackend`（SQLite）、`DurableRuntime`、AgentScope ReAct adapter、`RocketMqRemoteExecutor`。不构造 Worker、Sandbox client 或本地工具 executor。
- **Worker**：独立的 `hands.db`、持久化 `SandboxBindings`、`LocalSandboxClient`、`RocketMqWorker`（内部管理 `WorkerRuntime`）。不构造 Brain backend、agent 或 model。
- 共享冻结工具为 `sandbox.exec`，版本 `v1`，路由 `hands`，target 为 `{"operation":"sandbox.exec"}`。Worker 在接纳**每条**命令（含控制命令）前验证业务 namespace、session、工具摘要及固定 command/timeout。
- Sandbox 资源 binding 为 `(namespace, sessionId, v1, {})`，保存于 Hands 数据库。重启必须保留相同 Worker 目录与 binding，恢复时连接原 Sandbox，不能创建替代资源。

模型固定请求 `printf once >> count; printf recovered`，`timeout_seconds=30`。调用 ID 为 `sandbox-<turnId>`，同一 Turn 恢复时保持稳定，不同 Turn 的调用身份独立。只有 `ToolResultState.SUCCESS` 且结果 JSON 的 `output` 严格为 `recovered` 时，才输出 `Queued sandbox result applied: recovered`。失败、取消、未完成或无效结果不能伪装成功。Brain 再校验终态与最终答案；成功、失败、取消的终态观察均会唤醒等待。

## 启动双进程

前提：Java 17+、Maven、POSIX `sh`、Python 3，以及已运行的 RocketMQ 5 gRPC Proxy（默认 `localhost:8081`）。以下命令从仓库根目录执行。请按部署环境配置 Broker ACL 与认证；本地示例不提供生产凭据配置。

两个角色默认使用 `mq-namespace=durable-sandbox`，与 echo Hands 示例隔离。已核对 `RocketMqExecutionTopology`：路由 `hands` 需要预先创建以下 Topic：

- `durable-sandbox-hands-commands`
- `durable-sandbox-hands-controls`
- `durable-sandbox-hands-updates`

派生消费组为 `durable-sandbox-hands-brain`、`durable-sandbox-hands-worker`、`durable-sandbox-hands-worker-controls`；需授予相应生产/消费权限。Brain 与 Worker 必须使用相同 MQ namespace、业务 namespace 和 session ID。不要让独立 Brain 数据库混用同一示例消费组。

```bash
# 终端 1：持续运行，Ctrl-C 关闭。
bash agentscope-durable/durable-examples/queued/sandbox-hands/run.sh \
  worker localhost:8081 durable-queued-sandbox-hands-data/worker

# 终端 2：验证答案后退出。
bash agentscope-durable/durable-examples/queued/sandbox-hands/run.sh \
  brain localhost:8081 durable-queued-sandbox-hands-data/brain
```

等待日志 `queued-sandbox-hands worker ready`。Brain 输出 `turn`、`status`、`cursor`、`answer`。保留两侧目录，重复**完全相同**的 Brain 命令：默认 `request-1` 返回原 Turn，`worker/sandboxes/<resource-id>/count` 仍为 `once`。更换 request ID 则有意创建新 Turn 与新副作用。

脚本透传额外 Spring 参数，例如 `--durable.queued-sandbox-hands.mq-namespace=my-sandbox`（两侧同时配置并预建对应 Topic）。配置前缀：`durable.queued-sandbox-hands`。默认角色 `BRAIN`、endpoint `localhost:8081`、namespace `demo`、session ID `conversation`、request ID `request-1`、Brain 等待超时 `60s`。未指定目录时，按角色分别使用 `durable-queued-sandbox-hands-data/brain` 或 `/worker`。

## 编译与离线测试

```bash
bash agentscope-durable/durable-examples/queued/sandbox-hands/run.sh --compile-only

mvn -B -ntp -T1 -f agentscope-durable/pom.xml -Pdurable-standalone \
  -pl :agentscope-durable-example-queued-sandbox-hands -am test \
  -Dtest=QueuedSandboxHandsExampleTest,QueuedSandboxHandsRoundTripTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

脚本跳过测试构建可执行 jar；第二条命令运行测试。可通过 `DURABLE_MAVEN_REPOSITORY` 指定脚本使用的 Maven 本地仓库。Jar 路径：`agentscope-durable/durable-examples/queued/sandbox-hands/target/agentscope-durable-example-queued-sandbox-hands.jar`。

离线测试复用**生产 Tool、模型与 Brain harness**，执行真实本地命令，Brain/Hands 使用分开的 SQLite。仅命令/事件传输使用内存 fixture，调用公开的 `QueuedExecutor`、`TransactionalExecutionStore`、`WorkerRuntime` API。测试故意重复命令与事件、扣留结果交接、关闭并重开 backend、records、bindings、Worker、queue，再将结果应用至同一 request/Turn，检查成功、最终答案、原资源 attachment 与 `count` 仅为 `once`。回归测试还覆盖同一 Thread 的新 requestId 产生新命令但仍使用原资源，`count` 变为 `onceonce`，重复提交该 requestId 不再执行。其他测试覆盖 Spring 配置绑定、namespace/session/工具授权以及模型不误报成功。

**这些测试不验证 RocketMQ SDK、Broker、真实双进程传输或进程强杀故障恢复。** 这些仍需在预建基础设施上进行独立验收；离线覆盖不是 Broker 验收通过证据。

## 资源保留与安全边界

`LocalSandboxClient` 使用宿主用户权限执行命令，**不是容器，也不构成安全隔离边界**。固定命令白名单只是示例防线，不能替代调用者认证、Broker ACL 或操作系统隔离；仅在可信开发机器上运行。

Worker 启动 binding 维护，并通过 shutdown hook 停止 MQ Worker 与维护循环。保留默认 `canDelete=false`：关闭进程**不会**请求删除、关闭原 Sandbox 或擦除操作证据。Sandbox 与 `count` 有意保留，供恢复检查；不确定副作用不能成为在新 workspace 重试的理由。

生产环境应将本地 resolver 替换为真实 `SandboxClient`，保持稳定资源身份与持久化记录，并提供覆盖所有已接纳操作、取消和 UNKNOWN 结果的权威 cleanup barrier 后再申请删除。不要无条件启用清理，也不要在结果未确认时删除恢复证据。
