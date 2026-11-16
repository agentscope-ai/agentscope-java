# Tool-in-sandbox 场景

[English](README.en.md) · [返回总览](../../README.md)

该场景是一个完整的 Brain → Sandbox Hands → Brain 示例。它使用离线 `ReActAgent` 作为
Brain，由 `DurableRuntime` 保存 Turn、Run、Agent 原生 Thread Journal 和工具意图；本地
`WorkerRuntime` 作为 Hands，通过 Hands execution ledger 驱动 `SandboxBindings`。Brain 把
`worker.localExecutor()` 注册为明确的同 JVM 执行端点；WorkerRuntime 本身负责 Hands 调度和
生命周期。两者在同一个示例进程内组装，但只通过 durable 协议交接工具请求和结果。

这是 `integrated` 一体式场景，不使用队列。独立部署 Brain 与 Hands 的版本见
[Brain + Hands 沙箱工具示例](../../brain-hands/tool-in-sandbox/README.md)。

`ToolInSandboxExample` 分成 `start` 与 `resume` 两个独立 Spring Boot 进程；它们是恢复
阶段，不是 Brain/Hands 角色。两个进程共享 Brain 的 `brain/durable.db`、Hands 的 `hands.db`
和 Sandbox 目录，不共享 Java 内存对象。

```mermaid
sequenceDiagram
    participant B1 as start Brain
    participant DB as Durable SQLite
    participant H1 as start Hands
    participant Box as Detached Sandbox
    participant H2 as resume Hands
    participant B2 as resume Brain
    B1->>DB: submit Turn + invoke model
    B1->>DB: checkpoint Agent journal + sandbox.exec intent
    DB->>H1: admit execution
    H1->>Box: launch gated command
    B1-->>B1: exit with Turn WAITING
    H2->>DB: recover existing execution
    H2->>Box: release continue gate
    Box-->>H2: exactly-once effect + recovered output
    H2->>DB: seal Hands result
    B2->>DB: apply result to original Turn
    B2->>B2: restore Agent context + invoke model
    B2->>DB: complete the same Turn
```

`start` 中的模型生成 `sandbox.exec` tool call。命令先写入 `launched`，随后等待
`continue` 文件。示例确认 Brain Turn 已进入 `WAITING`、Hands execution 为 `RUNNING`、
副作用文件 `count` 尚不存在，然后让第一个 JVM 正常退出。

`resume` 使用相同的 session/request ID 恢复原 Turn，从持久化 Sandbox binding 找回同一
resource，释放闸门并驱动 Hands 恢复。Hands 将输出 `recovered` 封存到 execution ledger；
`DurableRuntime` 把结果应用回 Agent 原生上下文，再次调用模型并得到最终回答
`Sandbox result applied: recovered`。

验证覆盖：

- 两个进程获得相同的 Turn ID 和 Sandbox resource ID；
- 模型在 `start` 只调用一次，恢复后总调用两次；
- `count` 只包含一次 `once`，证明沙箱副作用没有重复；
- Hands 输出被封存为 `recovered`，Brain Turn 最终为 `SUCCEEDED`；
- execution 终态后，Sandbox workspace 可以安全删除。

```sh
bash agentscope-examples/durable/integrated/tool-in-sandbox/run.sh
```

脚本依次断言 `WAITING/RUNNING/modelInvocations=1/deleted=false` 和
`SUCCEEDED/SUCCEEDED/modelInvocations=2/deleted=true`。示例为了可重复验证而把 Brain 和
Hands 组装在每个 JVM 中；生产部署可以把 Hands 独立部署并替换 transport、Sandbox target
与资源保留策略，Brain checkpoint、Hands ledger 和结果回灌协议不变。

## 模块与配置

独立 Maven 模块 `agentscope-durable-example-tool-in-sandbox`，打包为
`agentscope-examples/durable/integrated/tool-in-sandbox/target/agentscope-durable-example-tool-in-sandbox.jar`。配置前缀为 `durable.tool-in-sandbox`，可用属性：
`phase`（`start`/`resume`）、`data-directory`、`namespace`、`session-id`、`request-id`、`timeout`；默认值见 `src/main/resources/application.yml`，可通过命令行参数、环境变量或外部
`application.yml` 覆盖。
