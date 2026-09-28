# Agent 应用与 Hosted Runtime 接入

> 状态：终态架构基线
> 最后更新：2026-09-03

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
provider adapters。daemon 首次启动会在 `state-root/host.id` 生成权限为 `0600` 的稳定
UUID；除非显式传入 `--host-key`，后续注册都复用该身份，不使用可能变化或碰撞的 hostname。

普通用户不直接运行 daemon。产品入口是 `agentscope connect`，它负责登录/换取 Host-scoped
`asrh_` credential、自动探测 provider、写入 `~/.agentscope/runtime-host/config.json` 并后台
启动 daemon。Runtime Host credential 使用 Bearer 认证，可安全通过公共 Gateway；共享 internal
token 仅为私网兼容路径。CLI 提供 `agentscope runtime start|stop|restart|status|logs|probe`，启动命令
只有在控制面注册完成并写入 readiness 后才返回成功。

当前内置 Codex adapter：

- 通过 `codex --version` 探测能力；
- 使用 `codex app-server --listen stdio://`，通过 `thread/start` 和 `turn/start` 执行新任务；
- app-server 原生接受 Runtime Host 创建的非 Git 隔离 workspace，因此 Playground/Issue
  不再依赖 `codex exec` 专用的 `--skip-git-repo-check`；
- 使用 app-server `thread/resume` 延续 provider session；
- 将 JSON-RPC 通知写入 journal，并把 thread ID/checkpoint 持久化到控制面；完成、失败和取消
  先写入本地 terminal outbox，控制面回调可按相同 lease/fencing 幂等重放；
- 在租约续期失败时取消本地进程，旧 fencing token 不能再提交结果。

Claude Code、Qoder、QwenPaw 与 OpenClaw 均通过同一 `provider.Adapter` 扩展，不得把品牌分支
写入 Task Plane。

当前 Host 已内置 `codex`、`claude-code`、`qoder`、`qwenpaw` 与 `openclaw` 五个 adapter。
Runtime Host 默认使用 `--providers auto` 探测本机安装的这些 CLI；也可以通过
`--providers codex,claude-code,qoder,qwenpaw,openclaw`（或 `AISTIO_RUNTIME_PROVIDERS`）显式声明。
对应二进制可以用 `--codex-binary`、`--claude-binary`、`--qoder-binary`、
`--qwenpaw-binary` 和 `--openclaw-binary` 覆盖。Host 启动时探测版本并将能力注册到内部
RuntimePool。不要声明本机未安装的 provider，否则 Host 应启动失败，避免领取无法执行的任务。
Claude Code 使用单向非交互 `stream-json`；Qoder 使用双向 `stream-json`；Codex 使用 app-server stdio JSON-RPC；QwenPaw 使用官方 `qwenpaw acp` stdio
JSON-RPC，支持 session load、model 切换和 session-scoped MCP；OpenClaw 使用官方
`openclaw agent exec --json` 隔离执行入口。OpenClaw 的该入口是 one-shot，因此 adapter 明确
上报 `resume=false`，也不会虚构临时 MCP 注入能力。

每个 adapter 还通过 `provider.Descriptor` 上报统一能力说明，包括 instructions、workspace、
skills、tools、shell、MCP、model 与 session resume 的支持方式。控制面把在线 Host、它所在的 Pool
以及匹配的 Profile 合成为一个面向 Agent 创建者的 Runtime 选项，例如
`Codex (macbook.local)`。Host 首次注册时会自动补齐内部默认 Pool 与 `auto-<provider>` Profile，
因此普通用户无需先理解或手工创建 Runtime Host、Runtime Pool 与 Runtime Profile。三者作为
控制面内部调度资源保留，不提供独立的产品页面；容量、约束、sandbox 和 provider 专属参数通过
自动探测、Agent 高级设置或平台诊断接口维护。

Agent 的 Instructions、Model、Workspace、Skills、Tools 和 MCP 属于可移植 Definition，不属于
RuntimeProfile。Hosted 与 Managed Agent 保存相同的 Definition；Hosted 任务在 claim 时把该
Definition 下发给 Runtime Host，再由 adapter 映射成底层 CLI 的 prompt、model 参数与能力配置。
Runtime Host 将 Definition 中的 Workspace 文件隔离写入任务目录的
`.agentscope/definition`，不会覆盖代码仓库自身的 `AGENTS.md`。运行 Codex 时，
`.agentscope/definition/skills` 会临时投影到原生 `.agents/skills`；运行 Claude Code 时会投影到
原生 `.claude/skills`。QwenPaw 与 OpenClaw adapter 则投影到各自的 workspace `skills/`，其中
QwenPaw 同步生成本次运行所需的 `skill.json` enablement。投影目录带 AgentScope 所有权标记，
遇到仓库自有的同名原生 skill 时拒绝覆盖，进程结束后只清理由 AgentScope 创建的内容。

Claude Code 与 Qoder 会把可移植 tool enable/disable 策略映射为各自的 allowlist 参数，并将
Agent MCP servers 与任务级 collaboration MCP 合并到临时 `0600` 配置文件。Codex 使用
`AGENTSCOPE_TASK_TOKEN` 环境变量注入 collaboration MCP；QwenPaw 通过 ACP `mcpServers` 注入，且
未显式配置 `bypassPermissions` 时会拒绝而不是自动批准权限请求。OpenClaw headless adapter
准确上报 `mcp.supported=false`，但只要 shell 和相邻的 `agentscope` CLI 可用，仍可通过
task-scoped CLI 参与协作。Team leader execution 要求 MCP 或 CLI 至少有一条协作通路。

每次 hosted dispatch 都把 RuntimeProfile 与 RuntimePool（包括 version、provider、requirements、
configuration 与 host selector）固化进 ExecutionAttempt 的 runtime snapshot。scheduler 和 claim
都会验证 Host 的 `capabilities.providers`、profile requirements、pool selector 与安全约束，避免
排队期间配置漂移或错误 provider 抢占任务。

claim 响应同时下发绑定当前 Attempt/generation 的 task token。daemon 把控制面的
`/mcp/collaboration` 作为 `agentscope-collaboration` MCP 注入支持该能力的 CLI，使本地 Codex、
Claude Code、Qoder 与 QwenPaw 能使用与其他数据面相同的 Issue、Comment、Artifact、Team、
delegation 与 Run tools；token 不写入 RuntimeProfile，Codex 通过环境变量读取，Claude Code 与
Qoder 使用临时 `0600` MCP 配置文件，QwenPaw 通过 ACP stdio 传递。

daemon 同时把相邻的 `agentscope` CLI 放入所有 provider 子进程的 `PATH`，并注入
`AGENTSCOPE_CONTROL_PLANE`、`AGENTSCOPE_TASK_ID`、`AGENTSCOPE_ISSUE_ID`、
`AGENTSCOPE_AGENT_ID`、`AGENTSCOPE_TEAM_ID`、`AGENTSCOPE_RUN_ID` 和
`AGENTSCOPE_TASK_TOKEN`。CLI 与 MCP 调用同一 REST/Service 状态机；MCP 可用时优先，CLI 是
shell-only runtime 和文件型结果的标准 fallback。子进程环境会剥离 human API token、Host
credential 和 internal token，CLI 也不会在 task context 中回退到用户身份。

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
分工，所有结果回到 discussion。Hosted Team leader 的 prompt 会明确其 Team/role，并要求通过
task-scoped collaboration MCP 完成分工与显式 `run.node.complete` / `run.node.fail` 收敛。

## 7. Provider adapter 约束

每个 Hosted Runtime adapter 必须实现：

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
