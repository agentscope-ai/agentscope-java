---
title: "Runtime Host 安装与运维"
en_link: /v2/en/service/runtime-host
---

<Note>
本地免鉴权开发需要当前源码；生产发布命令使用 `2.1.0-BETA1`。
</Note>

Runtime Host 运行在安装 Coding Agent 的电脑或服务器上。控制面负责派发和记录工作，Host 使用本地 provider 执行。

## 使用 Go 安装

准备 Go 1.26 或更新版本。从包含本地开发模式的当前源码安装 CLI 与 Runtime Host，在仓库根目录运行：

```bash
cd agentscope-service/service-controlplane
go install ./cmd/as ./cmd/agentscope-runtime-host
```

已发布的 `v2.1.0-BETA1` CLI 不支持自动进入本地开发身份。连接生产服务时可以安装发布版本，命令见[生产安装指南](/v2/zh/service/kubernetes#production-cli-install)。

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

将 PATH 配置写入 shell 配置文件，以便新终端使用。源码安装的版本输出以当前源码的构建信息为准。

另外在这台主机安装、登录所需的 Coding Agent provider，并确认它能完成一次请求。CLI 和 Runtime Host 连接已有 Service，不负责部署平台。升级时先停止 Runtime Host，从同一源码或发布版本重新安装两个命令，安装后重启，并保留原状态目录。

## 连接

```bash
as connect http://localhost:18080
as runtime status
as runtime probe
```

本地模式下 CLI 自动取得开发身份，保存本机配置并启动守护进程，无需输入账号密码。生产登录与 enrollment 见[生产指南](/v2/zh/service/kubernetes#production-api-access)。

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

单 scope 部署使用服务配置的固定 scope；多 scope 部署创建 enrollment token 时必须指定 `tenant`、`namespace`。交换请求不能更改 token 中的范围。drain 用于维护前停止接收新任务，取消正在执行的任务仍使用 [AgentTask API](/v2/zh/service/issues#curl-management)。

例如，在已经配置管理访问的终端生成接入凭据：

```bash
set -euo pipefail

ENROLLMENT_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/runtime-host-enrollment-tokens" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "tenant": "$TENANT",
  "namespace": "$NAMESPACE"
}
JSON
)
ENROLLMENT_TOKEN=$(jq -er '.enrollmentToken' <<< "$ENROLLMENT_JSON")
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
| `/observe` | 无 | 使用原 lease token 和 fence 读取 Attempt，不延长租约或执行许可 |
| `/preparing` | 无 | 报告正在准备 |
| `/running` | 可选 `providerSessionId`、`workspaceKey` | 报告已开始 provider 执行 |
| `/checkpoint` | `checkpoint`、可选 `providerSessionId` | 记录 provider 恢复信息 |
| `/events` | 正整数 `ordinal`、`provider`、`eventType`、可选 `providerSessionId`、`raw` | 提交执行事件；`raw` 最大 256 KiB |
| `/complete` | 可选 `result`、`checkpoint` | 报告完成 |
| `/fail` | `failureCode`、`failureMessage`、可选 `checkpoint` | 报告失败 |
| `/cancelled` | 无 | 确认执行已取消 |
| `/physical-stop` | 无 | Portal 所属任务在 provider 停止且持久事件 outbox 排空后封存事件 |

普通状态方法返回 `attempt`；事件接收响应应按 `accepted` 判断，Attempt 已封存时停止继续提交。Host checkpoint 是 provider 恢复材料，不是统一 Agent API 已支持的 checkpoint 恢复入口。能力区别见[Hosted 恢复](/v2/zh/service/runtime-host#hosted-agent-execution)。

新的 Portal 执行路径中，`/complete`、`/fail`、`/cancelled` 先记录原生结果。原 Host 随后上传剩余已提交事件，使用同一 Attempt 凭据及 fence 调用 `/physical-stop`。Portal 收齐停止证明、最终输出和用量后才发布 Turn 终态。Host journal 跨重启保留这项操作，响应丢失时用原身份重试。权限到期会拒绝新执行及续租，原 Host 仍可排空已提交结果并证明执行已停止。

续租被拒绝后，daemon 通过 `/observe` 区分取消和许可到期。查询不会授权执行。取消会停止 provider，先确认 `/cancelled`，再提交最终停止证明；停止回执成功之前保留 Attempt 凭据。

Portal 所属 Hosted 工具确认通过任务身份调用 `POST /api/v1/agent-tasks/{taskId}/runtime-approvals`，并在该路径下调用 `GET /{approvalId}/decision` 和 `POST /{approvalId}/ack`。Portal 保留原物理任务凭据，将回调转入私有 Go 运行角色。等待只续物理租约，不延长根执行许可。人工通过公开 Turn actions 答复标准 `required_action.created` 待办；持久化受理会隔离旧激活，刷新调用者和审批人的权限，并记录原人工决定。运行角色只在当前许可与审批匹配时交付结果；Host 确认后才发出 `required_action.resolved` 和命令 applied 回执，受理并不等同于交付。

Provider 事件先提交到独立本地 journal 文件，再投递。每轮最多投递 64 个事件、处理 8 个待交付 Attempt；最多保留 256 个待交付 Attempt，每个 Attempt 最多 65,536 个事件、256 MiB 事件数据，单个编码事件最多 1 MiB。投递失败保留记录并有限退避，journal 满时停止领取新任务；清理也按有限批次推进。这能在重启后恢复已提交的投递操作，不保证恢复中断的 provider 进程。

Portal 所属 Hosted 任务通过 `POST /mcp/collaboration` 使用原任务 Bearer。签名身份包含 Attempt 代次、实际 fence 和 Host ID，运行角色校验根许可及当前物理租约后才允许执行。Portal 补充内部传输凭据，provider 不接收该凭据。daemon 在注册前取得状态目录的操作系统锁，第二个使用同目录的 daemon 会被拒绝；保留锁文件，进程退出时自动释放锁。

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
| 请求任务取消或重试 | `POST /api/v1/agent-tasks/{taskId}/cancel`、`/retry` | 按 [Issue API](/v2/zh/service/issues#curl-management) 提交版本等参数；随后读取最终状态 |
| 查看 Workflow 运行过程 | `GET /api/v1/orchestration-runs/{runId}/graph`、`/events` | 节点图和运行事件，反映 Host 的执行结果 |
| 恢复应用画面 | `GET /api/v1/agent-sessions/{sessionId}/turns/{turnId}/snapshot`，再连接 `/events/stream` | 按 snapshot 游标接续 SSE；使用对应业务调用凭据 |

Host 运行协议中的 `/checkpoint` 保存 `providerSessionId` 和 checkpoint，供适配器与任务恢复路径使用。它不是供应用任意选择 checkpoint 并恢复所有后端的接口。统一 invocation 的 `checkpoint_restore` 当前为 false；Hosted conversation 已接通取消及通过 Turn actions 答复的根工具确认；Hosted 尚不提供 Managed 的补充输入和显式 resume。始终读取 `/api/v1/agent-sessions/{sessionId}/turns/{turnId}/capabilities` 的 `available_commands` 后再显示交互操作。

SSE 断线续传只恢复已经记录的输出，不重新执行工具，也不等于恢复 Host 进程。需要保留的交付物应上传为 Artifact；Host 磁盘、provider 会话和框架内部状态仍有各自生命周期。Host 接口与参数见 [Runtime Host API](/v2/zh/service/runtime-host#runtime-host-协议接口)。

<span id="curl-management"></span>

## 主机管理示例

以下示例沿用[API 身份准备](/v2/zh/service/create-managed-agent#api-setup)中的本地地址和默认空间变量。将资源 ID 替换为前面创建或查询得到的值。

发行 enrollment token 后，可让目标主机用这个短期 token 换取自己的 runtime token。`HOST_KEY` 应稳定且只属于这台主机；不要使用平台 token 替代 enrollment token。通常由 `as connect` 自动完成交换。

```bash
HOST_KEY="report-worker-1"
```

```bash
ENROLLED_HOST=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/runtime-host-enrollments/exchange" \
    -H "Authorization: Bearer $ENROLLMENT_TOKEN" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "hostKey": "$HOST_KEY"
}
JSON
)
RUNTIME_TOKEN=$(jq -er '.runtimeToken' <<< "$ENROLLED_HOST")
```

```bash
curl -sS --fail-with-body -G "$BASE_URL/api/v1/runtime-hosts" \
  --data-urlencode "tenant=$TENANT" \
  --data-urlencode "namespace=$NAMESPACE"
```

从主机列表选择 HOST_ID，再读取当前容量。

```bash
HOST_ID="HOST_ID_FROM_LIST"
```

```bash
HOST_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/runtime-hosts/$HOST_ID"
)
HOST_CAPACITY=$(jq -er '.host.capacity' <<< "$HOST_JSON")
```

```bash
curl -sS --fail-with-body -X PATCH "$BASE_URL/api/v1/runtime-hosts/$HOST_ID/capacity" \
  -H "Content-Type: application/json" \
  --data-binary @- <<JSON
{
  "capacity": 2,
  "expectedCapacity": $HOST_CAPACITY
}
JSON
```

<Accordion title="排空与恢复主机">

维护前排空以停止领取新工作，等待已领取任务结束。维护完成后再恢复。排空不会取消已领取任务。

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/v1/runtime-hosts/$HOST_ID/drain"
```

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/v1/runtime-hosts/$HOST_ID/resume"
```

</Accordion>


Session Portal 重构期间，Host 机器协议（register、heartbeat/state 及 Attempt claim/renew/checkpoint/events/终态回调）可经 Portal 进入独立 Go 编排运行角色。Portal 保留 Host 凭据和任务代次；内部服务凭据不能替代 Host 身份。容量、enrollment 等用户管理操作仍归控制面。该通道已通过本地进程验证，其他后端接入和部署更新仍在实施。


重构开发中的 Go 运行角色使用 PostgreSQL 时，要求配置至少 32 字节、所有运行副本稳定
一致的 `BUILDER_VAULT_MASTER_KEY`。Portal 配置和 Turn Inbox 的 payload 加密存储。
配置/Inbox 链路已验证 Portal 与 Go 的进程重启，Hosted Job 与 Conversation 已从新
Inbox 接通原生执行；其他控制和后端仍在实施。本地进程验证使用真实 daemon 与 fake
Codex app-server CLI，不调用外部模型。


新 Portal 路径的 Workflow 交互（包括嵌套子图）使用
`POST /api/v1/agent-sessions/{sessionId}/turns/{turnId}/actions`。读取待办
`required_actions` 资源后，在同一批次内提交确认（`request_id`、`allow`、可选 `reason`）
或信号（`request_id`、`value`）；单个信号值最多 64 KiB。答复保留原调用者，推进前刷新
准入，原生节点消费后才记为已应用。人工等待不会暂停独立的可执行分支。显示控制前读取
Turn capabilities。图和步骤使用 UUID 区分重复节点名，事件携带图与父级引用。
Session 和 Turn 快照中的 `steps` 资源保留图与节点状态；分页时复用同一
`snapshot_id`，保持一致视图。
Agent 任务可通过协作 MCP 提交定向信号：

```json
{"name":"run.signal","arguments":{"name":"report.ready","requestId":"PENDING_SIGNAL_NODE_UUID","idempotencyKey":"report-001","payload":{"artifactId":"ARTIFACT_ID"}}}
```

任务 CLI 对应命令为 `as task run signal report.ready --request-id PENDING_SIGNAL_NODE_UUID --idempotency-key report-001 --file signal.json`。
节点 UUID 来自当前运行图或待处理交互。转发服务在创建 Portal 持久化命令前，校验物理
Task/Attempt 租约、根执行授权和待处理 Signal 节点。回执区分已排队与原生已应用，
通过 Session 命令 API 查询 `applied`。同一键和内容重试返回原命令 ID，更换内容返回冲突。
任务信号沿用原始 Turn 调用者的调用权限，执行身份记为 Agent，不能回答人类确认。
每个 Turn 同时受理一条待处理信号命令，前一条达到 `applied` 后再提交下一条。
已持久化受理的信号允许在发送任务结束后应用，前提是 Attempt/fence 未被替换，
且根 Turn 仍具备当前执行授权。

暂停、显式 resume 和独立 Issue 子 Run 仍在接入。

Hosted 任务和报告 token 有效期为 15 分钟。daemon 在到期前及重放过期日志凭据时，
调用 `POST /api/v1/runtime-hosts/{hostId}/execution-attempts/{attemptId}/credentials`
刷新，要求当前 Host 凭据、匹配的 lease token 与 fence。刷新不延长执行租约，也不
恢复已撤销的执行许可。停止或撤权的任务可以刷新报告权限以完成停止回执投递，
不会获得新的任务权限。provider 通过每个 Attempt 独立的本地凭据转发访问 MCP 和
任务 CLI，使固定环境变量也能使用最新任务 token。转发限制为 8 个并发调用和有界
请求及响应，随 Attempt 关闭。Host 入驻凭据仍使用独立的到期与轮换策略。

公开 Session 创建也支持 Hosted Team Job。协调者在未完成工作节点结束后，通过带
fence 的协作 MCP 身份提交结论。公开 Turn 等待每个 Hosted Worker 的实际停止回执。
本地检查覆盖动态并行节点、完成和取消；完整委派及其余图控制仍在接入。

Portal 资源快照在 `resources` 中返回有界页；后续页复用 `snapshot_id` 和
`next_cursor`。UI 用 `order=desc` 读取最新 Turn，并可用 `status=running` 单独查询
正在运行的 Turn，避免长队列遮住较早的运行任务。列表游标绑定排序及状态过滤条件，
所选 Turn 的控件依据其 capabilities 展示。用量事件包含权威 `session_usage` 和
`turn_usage` 摘要，未加载完整模型历史不会减少已报告用量。预算按已报告用量检查，
在途并发调用可能超额，超额没有固定上界。

UI 在晚到的历史页上重放实时变更，包括失败的部分输出和已删除产物。显示窗口最多
保留 4096 条实时事件、8192 条索引记录及 16 MiB 序列化文本。达到上限后从新快照
刷新视图；这只关闭当前读取连接，不会停止执行。

Managed Turn capabilities 在等待工具时提供 `tool_retry`，Session 就绪时提供
`workspace_recover`。`resume` 用于失败或中断的 Turn；工具或确认等待使用对应动作。
UI 工具执行面板读取有界任务页，使用当前授权和 Workspace 代次。unknown 工具重试
要求确认可能重复外部副作用。重新连接要求已核实实例空闲；从归档恢复或丢弃要求已
核实旧实例不存在，并接受未归档文件变更丢失。Workspace 恢复不会重试 unknown
工具；检查持久化命令状态、刷新代次，再按需要另行提交工具重试。

Hosted 派发记录在可读元数据中保留调度身份、供应商能力及 profile/pool 版本。冻结
供应商配置、Agent 覆盖值和 Workspace 定义在写入原生 Task、Attempt 和派发 outbox
之前，使用运行角色的稳定 Vault key 加密。已鉴权的 Host 认领读取同一冻结配置，
重试和节点选择也使用原 Task 身份解密。运行角色重启需使用相同 key。新 Portal
Hosted 派发要求该格式；之前重建中的明文记录不能作为新 Portal 执行配置。这一步
没有加密所有控制面资源定义或供应商本地文件。

当前重建中，两个 Go 服务入口默认 `--schema-mode=validate`。普通副本启动前，
用数据库 owner 单独执行 `--migrate-only`；编排运行时初始化 runtime schema，
控制面还可以初始化已启用的 Product schema。任务在启动 HTTP 和 Worker 前退出，
也不初始化用户。普通运行副本仍需稳定的内部凭据、任务签名 secret 和 Vault key。
Java Portal/数据面的初始化入口及已验证的账号分离见仓库中的
`agentscope-service/docs/session-portal-schema.md`。Compose/Helm 已接入独立角色及迁移
任务；完整运行表写权限拆分仍在实施中。

独立 Go 运行角色在推进 Run、派发 Task、将物理 Attempt 交给 Host，以及续租执行
权限前，要求执行契约归属 Portal。旧资源操作正在迁移为持久化 Portal 运行意图；
新运行角色读取同一 Store 不会让旧任务自动取得执行资格。

独立 Go 运行时镜像支持 `--healthcheck`，只探测 `/readyz`，不启动数据库连接或 Worker。
执行来源解析拒绝循环，最多查询 256 个父 Issue，避免异常父子链产生无界查询。

Host 机器凭据剩余有效期不足 24 小时时，daemon 自动发起续期。请求经 Gateway 和
Portal 到运行角色，携带仍有效的 Host bearer 与当前注册代次。续期保留 tenant、
namespace 和 Host key，不延长注册或 Attempt 租约，也不授予任务执行权限。
控制台注册保留调用者实际获授权的 namespace。

续期凭据保存在已加锁的状态目录内 `host-credential.json` 中；用相同初始注册凭据
重启时继续使用该缓存。缓存绑定初始凭据及机器作用域，重新注册的新凭据优先。
状态目录应保持私有：Unix 文件权限为 0600，Windows 继承目录 ACL。临时续期失败
时继续使用当前有效凭据；离线到所有机器凭据均已过期后，需要重新注册。
续期不撤销此前签发的 token，也不轮换服务签名 key。
