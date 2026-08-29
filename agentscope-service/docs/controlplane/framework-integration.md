# Agent 应用与 Coding Runtime 接入

> 状态：终态架构基线
> 最后更新：2026-08-24

本文说明 AgentScope Service 如何接入用户 Agent 应用与 Coding Agent runtime。系统不提供
Sidecar 模式；接入边界由 Application SDK、Application ASDP 和 Runtime Host Protocol
明确区分。

## 1. 四类数据面

| kind | 生命周期拥有者 | 接入/调度协议 | 典型场景 |
|---|---|---|---|
| `managed` | AgentScope Service | Managed Session / Turn API | Harness 与 Hands Worker |
| `external-application` | 用户 | Application SDK + ASDP/HTTP contract | AgentScope、Claude Agent SDK、LangGraph、ADK |
| `hosted-runtime` | 用户部署的 Runtime Host，控制面分配任务 | Runtime Host Protocol | Codex、Claude Code 等 Coding Agent |

分类依据是“谁拥有进程或应用生命周期”，不是模型、SDK 或命令行工具的品牌。

## 2. Application SDK

`aistio-application-sdk` 嵌入用户应用，负责：

- 以 tenant / namespace / agent / instance 身份自动注册；
- 上报 framework、SDK version、capabilities、capacity 与健康状态；
- 通过 ASDP 上报 Session、Event、Context 与 Inventory；
- 接收 Session command、配置更新和 ExecutionAttemptCommand；
- 在控制面不可用时旁路降级，不能阻塞 Agent 主路径。

SDK 不负责创建应用副本、领取 Coding Agent execution，也不实现企业级部署平台。用户可以
把 SDK 嵌入任意常驻服务、Job 或消息消费者中。

### ASDP 身份与安全

每个上行消息都携带：

```text
tenant / namespace / agentName / instanceId / timestamp
```

生产环境必须使用 bearer/internal token 或 mTLS；mTLS 证书身份需要与 namespace 和
agentName 匹配。连接建立与断开会写入持久化 `AgentInstance`，进程内连接表只保存当前
副本上的实时路由。

HTTP contract 是 Application SDK 的可选查询/命令传输，可用于 ASDP 之外的能力探测；
它不是独立的数据面类型，也不包装或代理 LLM 请求。

## 3. Runtime Host Core

`aistio-runtime-host-core` 是 provider-neutral 的执行内核，负责：

- Host capability 探测与自动注册；
- 并发槽位、claim / lease / renew / fencing；
- workspace 分配、仓库物化与本地 journal；
- provider adapter 选择；
- 标准事件、provider session、checkpoint、结果与失败归一化；
- 取消、超时、崩溃和失联后的收敛。

Core 不含某个部署环境的服务安装方式，也不创建任意用户容器。

## 4. Runtime Host Daemon

`aistio-runtime-host` 是可直接部署的 daemon。它组合 Host Core、控制面客户端和已安装的
provider adapters。当前内置 Codex adapter：

- 通过 `codex --version` 探测能力；
- 使用 `codex exec --json` 执行新任务；
- 使用 `codex exec resume <thread-id> -` 延续 provider session；
- 将 JSONL 事件写入 journal，并把 thread ID/checkpoint 持久化到控制面；
- 在租约续期失败时取消本地进程，旧 fencing token 不能再提交结果。

Claude Code、Qoder 等均通过同一 `provider.Adapter` 扩展，不得把品牌分支写入 Task Plane。

当前 Host 已内置 `codex` 与 `claude-code` 两个 adapter。部署时通过
`--providers codex,claude-code`（或 `AISTIO_RUNTIME_PROVIDERS`）声明本机实际安装的
provider，通过 `--codex-binary` / `--claude-binary` 指定可执行文件；Host 启动时探测
版本并将能力注册到 RuntimePool。不要声明本机未安装的 provider，否则 Host 应启动失败，
避免领取无法执行的任务。Claude Code 使用非交互 `stream-json`，session ID 仍只作为 opaque
provider checkpoint。

Runtime Host 由用户按机器、Pod 或受控执行节点部署并自动注册。控制面选择 Host，但不通过
Host 管理用户应用副本。

## 5. 企业级用户 Agent 部署

当平台需要拉起任意用户 Agent 容器时，使用独立的：

```text
WorkloadTemplate -> AgentDeployment -> Launcher -> Kubernetes / other infrastructure
```

Launcher 管理副本、滚动升级、Service、Secret、网络与存储；应用 Ready 后仍通过
Application SDK / ASDP 注册为 `AgentInstance`。详细设计见
[Agent Application Deployment 与 Launcher](./agent-application-launcher-plan.md)。

## 6. 与 Issue / AgentTask / Team 的关系

```text
Issue / Comment
  -> AgentTask
     -> ExecutionAttempt
       -> managed adapter
       -> external application adapter
       -> hosted runtime / Runtime Host
       -> launched application adapter (future)

Team
  -> leader AgentTask
       -> child Issue or worker mention
            -> worker AgentTask (lazy)
```

协作服务只创建 AgentTask/Execution 和持久 Comment，不直接启动进程或 Pod。Hosted Agent
进入 Runtime Host 队列；External Application Agent 由已有实例处理；未来确实需要新
应用容量时，才通过 Launcher 创建 `AgentDeployment`。Team leader 通过 child Issue 或 mention
分工，所有结果回到 discussion。

## 7. Provider adapter 约束

每个 Coding Runtime adapter 必须实现：

```go
type Adapter interface {
    Name() string
    Detect(context.Context) (string, error)
    Run(context.Context, Request, EventSink) (*Result, error)
}
```

adapter 必须：

- 使用参数数组启动进程，不拼接 shell 命令；
- 接受已分配 workspace 和受版本控制的 RuntimeProfile；
- 尽早报告 provider session ID，并持续保存可恢复 checkpoint；
- 将 stdout 事件标准化，同时保留受大小限制的 raw event；
- 尊重 context cancellation；
- 不读取控制面数据库，不自行领取其他任务，不管理应用副本。

## 8. 最小生产要求

- PostgreSQL 是 Task、Execution、Registry、Team 与 Outbox 的权威存储；
- 所有 claim 使用 lease + fencing token；
- Runtime Host 与 Application SDK 使用不同机器身份和权限域；
- RuntimeProfile 不保存明文 Secret，只保存 Secret reference；
- workspace 必须限制 tenant/task 路径并采用最小权限 sandbox；
- 所有状态变更产生审计事件；事件投递允许 at-least-once，消费者必须幂等；
- 控制面多副本不能依赖单副本内存连接状态保证任务正确性。
