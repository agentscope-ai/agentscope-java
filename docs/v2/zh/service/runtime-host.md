---
title: "Runtime Host 安装与运维"
en_link: /v2/en/service/runtime-host
---

<Note>
本页使用 `2.1.0-BETA1` 预发布版本。
</Note>

Runtime Host 运行在安装 Coding Agent 的电脑或服务器上。控制面负责派发和记录工作，Host 使用本地 provider 执行。

## 使用 Go 安装

在目标 Linux 或 macOS 主机安装 [Go](https://go.dev/doc/install) 1.26 或更新版本。从同一个已发布版本安装两个命令，无需下载仓库源码或在该主机部署 Service：

```bash
go install github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/cmd/as@v2.1.0-BETA1
go install github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/cmd/agentscope-runtime-host@v2.1.0-BETA1
```

Go 为当前机器编译二进制，安装到 `GOBIN`；未设置时使用 `$(go env GOPATH)/bin`。将该目录加入 PATH，并检查两个命令：

```bash
AS_CLI_BIN_DIR="$(go env GOBIN)"
if [ -z "$AS_CLI_BIN_DIR" ]; then
  AS_CLI_BIN_DIR="$(go env GOPATH)/bin"
fi
export PATH="$AS_CLI_BIN_DIR:$PATH"
as version
agentscope-runtime-host -help
```

将 PATH 配置写入 shell 配置文件，以便新终端使用。`as version` 应显示 `2.1.0-BETA1`。`/v2` 模块路径和 `@v2.1.0-BETA1` 指定本次已发布的预发布版本。

另外在这台主机安装、登录所需的 Coding Agent provider，并确认它能完成一次请求。CLI 和 Runtime Host 连接已有 Service，不负责部署平台。升级时先停止 Runtime Host，将两个 `go install` 命令中的版本一起更新，安装后重启，并保留原状态目录。

## 连接

```bash
as connect https://agentscope.example.com
as runtime status
as runtime probe
```

按 CLI 提示完成登录或 enrollment。连接会保存本机配置并启动守护进程；用户正常使用无需反复传递共享内部令牌。

## 日常操作

```bash
as runtime logs
as runtime stop
as runtime start
```

配置与状态默认在 `~/.agentscope/runtime-host/`。保留 Host 身份和状态文件，避免把已有主机错误地注册为新实例。不要把该目录当成公开的配置样例。

连接成功后，通过管理 API 检查 Host 在线、provider 可用，再绑定 Hosted Agent 并派发一项小任务。失败时同时查看 Task Attempt 和 Host 日志。

Runtime Host 不是托管 Agent 的 Hands Worker；有关执行环境见 [执行环境](/v2/zh/service/environments)。

## 无交互服务器连接

由有权限的操作者生成短期 enrollment token，再交给待连接主机使用：

```bash
as runtime enrollment-token create
```

在目标主机将该值放入 `AGENTSCOPE_ENROLLMENT_TOKEN` 环境变量，再执行 connect。服务交换出绑定 Host 身份和范围的凭据。不要把 enrollment token 或 `config.json` 放进共享脚本。

## CLI 参考

| 命令 | 用途 |
| --- | --- |
| `as connect URL` | 登录/注册本机，保存配置并启动 daemon |
| `as runtime status` | 查看运行状态 |
| `as runtime probe` | 检查 provider 可用性 |
| `as runtime logs -f` | 跟踪日志 |
| `as runtime restart` | 重启 daemon |
| `as runtime stop` / `start` | 停止或启动 |
| `as connect --help` | 查看该版本提供的高级参数 |

配置目录中的 `config.json` 含连接身份，`state/host.id` 保存稳定 Host ID，`daemon.log` 用于排障，`workspaces/` 保存任务工作目录。升级 CLI 前检查活跃任务，再替换配套二进制并重新启动，保留这些持久状态。

## 主机接入与管理 API

CLI 使用同一套 Host API。平台账户凭据用于授权接入和管理主机；enrollment token 用于首次交换；`runtimeToken` 只供对应 Host 的运行协议使用。

| 方法与路径 | 请求字段/查询参数 | 响应 |
| --- | --- | --- |
| `POST /api/v1/runtime-host-enrollment-tokens` | 平台 Bearer；JSON `tenant`、`namespace` | 201；`enrollmentToken`、scope、`expiresAt` |
| `POST /api/v1/runtime-host-enrollments/exchange` | enrollment Bearer；JSON `hostKey`，最长 200 字符 | 201；`runtimeToken`、`hostKey`、scope、`expiresAt`；范围来自 token |
| `POST /api/v1/runtime-host-enrollments` | 平台 Bearer；JSON `hostKey`、`tenant`、`namespace` | 直接签发对应 Host 的 `runtimeToken` |
| `GET /api/v1/runtime-hosts` | 平台 Bearer；查询 `tenant`、`namespace`，可选 `poolName`、`state` | `items`；包含 `id`、`hostKey`、`state`、`capacity`、`active`、`lastSeenAt`、`capabilities` |
| `GET /api/v1/runtime-hosts/{hostId}` | 平台 Bearer；Host UUID | `host` |
| `PATCH /api/v1/runtime-hosts/{hostId}/capacity` | 平台 Bearer；`capacity` 为 1–50，`expectedCapacity` 为当前值 | 更新后的 `host`；并发修改冲突时重新读取 |
| `POST /api/v1/runtime-hosts/{hostId}/drain` | 平台 Bearer | `host`；停止领取新工作 |
| `POST /api/v1/runtime-hosts/{hostId}/resume` | 平台 Bearer | `host`；恢复可调度状态 |

单 scope 部署使用服务配置的固定 scope；多 scope 部署创建 enrollment token 时必须指定 `tenant`、`namespace`。交换请求不能更改 token 中的范围。drain 用于维护前停止接收新任务，取消正在执行的任务仍使用 [AgentTask API](/v2/zh/service/issues)。

例如，在已经配置管理访问的终端生成接入凭据：

```bash
curl -sS "$SERVICE_URL/api/v1/runtime-host-enrollment-tokens" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"tenant":"default","namespace":"default"}'
```

把响应的 `enrollmentToken` 安全地传到目标主机，再运行 `as connect`，无需业务应用手动调用 Host register、heartbeat 或 claim。

### Runtime Host 协议接口

下面的接口由 daemon 使用 Host 范围的 Bearer 凭据调用。需要实现自定义 Host 时才直接对接它们；业务任务分派应调用 Issue 或 Session API。

| POST 路径 | 关键请求字段 | 行为 |
| --- | --- | --- |
| `/api/v1/runtime-hosts/register` | `hostKey`、`poolName`、scope、`capacity`，可选 `daemonVersion`、`os`、`arch`、`labels`、`capabilities` | 返回 `host` 与 `heartbeatIntervalSeconds`，保存 `host.id` 与 `host.leaseGeneration` |
| `/api/v1/runtime-hosts/{hostId}/heartbeat` | `generation`、`active`、可选 `capabilities` | 更新存活与能力，返回 `host` |
| `/api/v1/runtime-hosts/{hostId}/state` | `generation`、`state` | 更新状态，返回 `host` |
| `/api/v1/runtime-hosts/{hostId}/execution-attempts/claim` | `tenant`、`namespace`、`runtimePoolName`、`generation`、`leaseOwner`、`leaseToken`，可选 `leaseSeconds` | 无任务返回 204；成功返回 `task`、`context`、`attempt`、`taskToken`、`attemptToken`、`runtimeProfile`、`executionOverrides`、`definition` |

领取后，以下相对路径均位于 `/api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}` 下。除 Host Bearer 外，必须在 `X-Execution-Attempt-Token` Header 传入 claim 返回的 `attemptToken`。请求 JSON 至少携带本次 `leaseToken`、`fencingToken`，daemon 同时传入 `generation`、`leaseOwner`；不能使用另一次执行的值。

| POST 相对路径 | 额外字段 | 用途 |
| --- | --- | --- |
| `/renew` | 可选 `leaseSeconds`，默认 30 秒 | 续租，读取服务端取消/终态 |
| `/preparing` | 无 | 报告正在准备 |
| `/running` | 可选 `providerSessionId`、`workspaceKey` | 报告已开始 provider 执行 |
| `/checkpoint` | `checkpoint`、可选 `providerSessionId` | 记录 provider 恢复信息 |
| `/events` | 正整数 `ordinal`、`provider`、`eventType`、可选 `providerSessionId`、`raw` | 提交执行事件；`raw` 最大 256 KiB |
| `/complete` | 可选 `result`、`checkpoint` | 报告完成 |
| `/fail` | `failureCode`、`failureMessage`、可选 `checkpoint` | 报告失败 |
| `/cancelled` | 无 | 确认执行已取消 |

普通状态方法返回 `attempt`；事件接收响应应按 `accepted` 判断，Attempt 已封存时停止继续提交。Host checkpoint 是 provider 恢复材料，不是统一 Agent API 已支持的 checkpoint 恢复入口。能力区别见[Hosted 恢复](/v2/zh/service/runtime-host#hosted-agent-execution)。

<span id="hosted-agent-execution"></span>

## 主机注册和任务选择

连接时 Host 注册稳定身份、范围、池、provider 描述与容量。调度结合 Agent 绑定、能力要求和运行策略产生 Attempt；Host 领取符合条件的工作并维持租约。Host 在线、provider 能 probe、Agent 可派发是三个不同检查点。

Runtime Profile 决定 provider 参数，Pool 提供可选执行主机。并发受 Host capacity 和上层调度策略共同约束。

## 准备与执行

Host 为执行准备任务工作目录，将平台支持的指令和能力文件映射到 provider 格式，再用该目录启动 provider。它不会默认切换到用户正在编辑的本地仓库；仓库、输入资料与分支需要在任务准备中明确。

任务范围凭据和工作上下文通过环境及 MCP/CLI 提供给执行者。provider 可以读取工作、发表评论和上传产物。把文件留在 Host 磁盘上并不等于其他协作者可以访问，应使用 Artifact 交付需要共享的结果。

## 事件、确认与恢复

适配器将 provider 事件转换为平台执行记录。支持的平台确认由 Host 转发工具请求并等待决定；不支持的平台确认必须按 provider 自身的权限方式处理。

Host 保留日志、provider 会话标识和 checkpoint，用于支持的恢复路径。重启时保留状态目录与 Host 身份；只声明 Resume 支持不能保证任意中断都能恢复，目标 provider 的会话仍需存在且可访问。OpenClaw 当前适配器不提供 Session resume。

## 重试和取消

取消会向执行链路传播，应检查 Attempt 终态以及 provider 进程是否结束。重试是新的 Attempt；只有符合恢复条件时才使用原 provider 会话。跨后端 fresh fallback 依赖持久 Issue、评论和 Artifact 重建上下文，不能搬迁进程内记忆。

使用 `as runtime logs -f` 配合 Task/Attempt 诊断。若无任务可领，检查范围、池、绑定、容量与所需能力；若领取后失败，检查 provider 登录、参数、工作目录和工具依赖。

相关：[安装连接](/v2/zh/service/runtime-host) · [支持的 provider](/v2/zh/service/hosted-agent-configuration#hosted-agent-providers) · [Team 协作](/v2/zh/service/create-team#team-collaboration)。

## 使用 API 跟踪和控制工作

平台账户通过 Issue 或 Session 分派任务；Host 凭据仅用于 daemon 领取、续租和回报。业务调用者无需接触 `leaseToken` 或 provider 进程。

| 场景 | API 与参数 | 响应/用途 |
| --- | --- | --- |
| 读取 AgentTask | `GET /api/v1/agent-tasks/{taskId}` | 当前任务状态与执行关联 |
| 查询物理尝试 | `GET /api/v1/execution-attempts?tenant=...&namespace=...&taskId=...`，可选 `state`、`limit` | `attempts`，每次重试有独立记录 |
| 读取单个 Attempt | `GET /api/v1/execution-attempts/{attemptId}` | `attempt`，包含 backend、Host、租约、失败与恢复信息 |
| 请求任务取消或重试 | `POST /api/v1/agent-tasks/{taskId}/cancel`、`/retry` | 按 [Issue API](/v2/zh/service/issues) 提交版本等参数；随后读取最终状态 |
| 查看 Workflow 运行过程 | `GET /api/v1/orchestration-runs/{runId}/graph`、`/events` | 节点图和运行事件，反映 Host 的执行结果 |
| 恢复应用画面 | `GET /api/v1/agent-sessions/{sessionId}/turns/{turnId}/snapshot`，再连接 `/events/stream` | 按 snapshot 游标接续 SSE；使用对应业务调用凭据 |

Host 运行协议中的 `/checkpoint` 保存 `providerSessionId` 和 checkpoint，供适配器与任务恢复路径使用。它不是供应用任意选择 checkpoint 并恢复所有后端的接口。统一 invocation 的 `checkpoint_restore` 当前为 false；Hosted conversation 支持取消，补充输入、审批与 resume 则不能套用 Managed 的能力承诺。始终读取 `/api/v1/agent-sessions/{sessionId}/turns/{turnId}/capabilities` 的 `available_commands` 后再显示交互操作。

SSE 断线续传只恢复已经记录的输出，不重新执行工具，也不等于恢复 Host 进程。需要保留的交付物应上传为 Artifact；Host 磁盘、provider 会话和框架内部状态仍有各自生命周期。Host 接口与参数见 [Runtime Host API](/v2/zh/service/runtime-host#runtime-host-协议接口)。
