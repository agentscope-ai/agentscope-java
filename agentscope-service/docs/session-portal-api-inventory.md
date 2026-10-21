# Service 路由归属清单

本清单针对 `/Users/ken/agentscope-3/agentscope-java` 当前 `codex/automatic-acr-release` 工作区。归属目标仍是公开管理入口在控制面、公开运行入口在 Portal，执行和供应商工作通过 private 协议完成。以下记录实际路由和剩余迁移；没有把源码中声明的旧路由视为新部署已经可用。

## 明细与校验依据

- [Java Controller 声明明细](session-portal-java-routes.csv)：211 条静态映射（Portal 100、Data 110、Scheduler 1），包含方法、完整注解路径、调用者、鉴权边界、语义、当前/目标服务及源码位置。
- [Go 声明明细](session-portal-go-route-declarations.csv)：564 条方法映射，记录所在函数、group 和路径字面量；`group::/path` 表示需结合调用处确定前缀，不把无法静态解析的前缀猜成公网 URL。feature 条件、runtime-only 角色和旧的未挂载函数仍需按下表和启动代码核实。
- [Gateway](../service-gateway/src/main/resources/application.yml) 是公网实际路由优先级的依据；[Go server](../service-controlplane/internal/httpapi/server.go) 是角色/feature 挂载的依据；Spring 安全配置和各 service 内的原始身份检查是鉴权依据。声明明细不代替这些实际检查。

## 公网路由与归属

| URL / 方法 | 调用者与鉴权 | 管理/运行语义 | 当前入口 → handler | 目标与当前状态 |
| --- | --- | --- | --- | --- |
| `/api/auth/**`、`/api/user/**`、`/api/admin/**` | 用户 JWT/账号权限 | 账号管理 | Gateway → 控制面 Product | 控制面，已保持 |
| `/api/v1/agents`、Applications/credentials、Teams、runtime policies/profiles/pools、Workflow definitions 的 CRUD/publish | 平台用户及 Namespace/资源权限 | 资源定义 | Gateway → 控制面 Go | 控制面，已保持；启动/执行操作另列 |
| `/api/environments/**`、Vault/Memory/Skills/Channel/Deployment 等定义 CRUD | 用户权限和资源图 | 配置管理 | Gateway → 控制面 | 控制面；动态执行投影继续 P6/P8 |
| `POST/GET /api/v1/agent-sessions` | 原始用户 JWT/Application key；目标及 Namespace 权限 | 运行 Session 注册/列表 | Gateway → Portal | 已接通；控制面仅配置选择，Portal 注册/准入 |
| `GET/DELETE /api/v1/agent-sessions/{id}`；`POST archive/restore` | 原始调用者 read/invoke/cancel（按具体 handler） | 运行生命周期 | Gateway → Portal | 已接通；跨服务 durable lifecycle |
| `PATCH /api/v1/agent-sessions/{id}` | 原始调用者及资源选择权限 | 修改执行配置 | Gateway → Portal，但无新 PATCH handler | 待实现；不能绕到旧控制面/Data 路由 |
| `POST/GET /{session}/turns`、`GET /turns/{turn}` | 原始调用者 invoke/read + 原子额度 | Turn 受理/查询 | Gateway → Portal | 已接通；queued 与 started 区分 |
| Session/Turn 的 `GET events/events/{event}/events/stream/snapshot/resources/{kind}/usage` | 原始调用者 read，持续 SSE 权限刷新 | 标准运行观察 | Gateway → Portal | 已接通；有限页/游标/快照/O(1) 用量 |
| `POST inputs/inject`、`POST turns/{turn}/steer/actions/cancel/resume`、commands/actions/inputs 查询 | invoke/interact/approve/cancel；原始 actor；后端 capabilities | 交互与恢复 | Gateway → Portal → private Runtime | Managed 与部分原生后端接通；完整后端能力继续 P5 |
| `GET subagents/...`、子 Session journal/snapshot | 根 Session read，不独立授予运行权限 | 子执行观察 | Gateway → Portal | Managed 本地子 Harness 已接通；独立 Go 子 Issue/Run 继续 P5 |
| `POST/GET files`、`GET files/{file}/content`、artifacts publish/read/delete/content | 原始 read/invoke；执行任务/root fence | 输入与交付物 | Gateway → Portal → private Data/Go | Managed 文件和部分 Go 产物接通；混合后端和大文件 Task upload 继续 P7/P8 |
| `POST/GET /{session}/webhooks`、`DELETE /{id}`、`POST /{id}/retry`、`GET /{id}/deliveries` | 原始 read + key webhooks:write / human interact | 标准事件投递 | Gateway → Portal，Scheduler private claim/send/receipt | 本次真实权限/账号/重启验证通过；仅根 Session 订阅 |
| Session/Turn `GET export` | 原始调用者 read，每批复核 | 有限窗口标准公开事件 JSONL | Gateway → Portal | 固定水位、游标续读、最多 1,000 条/窗口；不暴露原生 trace |
| Managed Session budget GET/PUT | GET read；PUT interact；子 Session 只继承根预算 | Portal 期望设置，数据面逐调用收据和用量聚合 | Gateway → Portal；执行 grant 同步设置，private Data 提供有界用量摘要 | 已实现；PUT 202，If-Match 412，实际应用版本单独返回 |
| Managed Session GET trace | human ADMIN/SESSION_TRACE JWT + 当前 read 和 trace 策略；API key 拒绝 | 敏感原生日志，只读 | Gateway → Portal → private Data | 默认关闭；固定水位/序号分页、最多16事件和4MiB/页；不进入公开事件链路 |
| `GET Session checkpoints` | Session read；private Data 核对冻结 owner | checkpoint 元数据 | Gateway → Portal → private Data | 已接通；固定水位，每次最多扫描128事件，空页也需推进游标，原始状态私有 |
| Session checkpoint restore/fork | 两个根 Session interact，重新核验原始 actor | 运行恢复 | Gateway → Portal → private Data | 持久化202命令与查询、准入冻结、有界核验、精确原生提交凭证；拒绝非空目标、未决输入/工具/子执行 |
| `POST /api/v1/runtime-hosts/register/{host}/heartbeat/state/credentials/execution-attempts/**` | Host enrollment/runtime credential，Attempt/fence/有限授权 | 执行机器协议 | Gateway → Portal → private Go Runtime | 已接通；轮换/撤销和完整孤儿停止继续 P5 |
| Runtime Hosts 的 `GET`、capacity/drain/resume、enrollment 管理 | 管理用户权限 | 机器配置管理 | Gateway → 控制面 Go | 管理留控制面；执行停止需要 durable intents |
| `/mcp/collaboration`、带 `X-Agent-Task-Token` 的 tasks/issues/comments/team reads/writes | 当前物理 Task Attempt/fence + 根 Portal grant；机器凭据不取代原始用户 | 任务协作 | Gateway → Portal → private Go Runtime | 代表性 Team、Signal、approval/产物接通；完整委派继续 P5 |
| `/api/environments/{id}/tool-tasks/**` | Environment Worker 凭据/claim/fence；根执行授权 | Hands 工具协议 | Gateway → Portal → private Data | 新 Worker 协议已接通；托管 Hands 使用 private Hands 身份 |
| `/api/sessions/{id}/events/**`、`hands-stats` | 旧用户/内部 actor | 旧 Managed Turn/观察 | Gateway → Data | 待关闭或替换；新 Portal Session 不走这里，Channel/Cron 旧桥接仍依赖 |
| `/api/environments/{id}/work/**`、`sessions/{session}/pending-tools/tool-results/skills` | 旧 Environment key/Worker 身份 | 旧 Hands 队列/结果 | Gateway → Data | 待关闭；新 Hands 已用 ToolTask，不能仅在 Gateway 改代理 |
| `/api/hands/**` | 旧数据面安全/观察权限 | Worker 运维观察 | Gateway → Data | 待迁到 Portal 的机器运行视图 |
| `POST /api/outbound/send` | Scheduler JWT/agent access guard | 出站发送 | Gateway → Scheduler | 待迁 Portal durable delivery，Provider receipt 由 Scheduler 上报 |
| `/api/channels/feishu/**`、`wecom/**`、github/gitlab callbacks | 供应商签名/解密/消息 ID 去重；Channel identity binding | 入站外部触发 | Gateway → Scheduler adapter | 待经 Portal 原始 bytes ingress/验签/持久化后由 Worker 消费 |
| DingTalk callback/stream | 钉钉签名/协议凭据；实际 adapter 模式 | 入站外部触发 | extension adapter；Gateway 当前 callback 白名单没有 DingTalk | 待统一明确 HTTP 与主动 stream 的入口/部署配置 |
| `/hooks/v1/automations/{automation}/{trigger}`、work-source GitHub webhook | 外部供应商/automation secret，原始 bytes 验签和事件 ID | Automation 入站触发 | Gateway → 控制面 Go | 待 Portal ingress；控制面只保留定义/绑定/凭据配置 |
| Cron/Automation manual trigger/run cancel/rerun、Workflow definition `POST /runs`、Issue assign/create child 等实际启动行为 | 用户/配置来源权限，稳定触发 ID | 启动运行（虽与资源 CRUD 同前缀） | 旧控制面 handler/worker | 待 Portal 准入；不能继续以控制面后台任务执行新业务 |
| `/api/v1/agent-registrations` | 专用 registration credential | External 注册 | Gateway → 控制面 Go | 资源注册可留控制面；External 执行/task 协议继续 P5 |
| `/api/v1/dataplanes/{instance}/heartbeat`、`DELETE /{instance}` | 内部服务凭据 | 执行实例注册 | Gateway 通用 `/api/v1/**` → 控制面 | 待界定 private 机器注册与 runtime 管理边界；旧 register 已返回 Gone |
| `/api/v1/dp` KV/locks/tasks/snapshots/bus/async-tools（feature gated） | 旧共享内部 token；Hosted Store namespace | 执行持久化协调 | 控制面 Hosted DistributedStore handler | 待迁执行存储边界和私有监听；目前 `/api/v1` 外形不代表用户公开能力 |
| 旧 `/invoke/v1`、Endpoint invocations/service commands/events/webhooks 等声明 | 旧 endpoint/Application scopes | 旧运行 Invocation | Go 声明明细保留；当前主 Gateway 无专用映射 | P5/P8 用新统一 Session/Turn 替代；逐项核实 feature/挂载后关闭，不能按源码声明声称公网可用 |

## private 边界

`/api/internal/**` 在 Gateway 返回 404；对应 Servlet/Go/WebFlux 服务继续检查内部 transport 身份。Portal 中 ExecutionAuthorization、RuntimeFacts、Agent Signal、Hands continuation、Runtime collaboration/files/artifacts 和 Webhook claim/validate/receipt 的原始 actor、当前 Task/root、内容摘要、有效期与 fence 在各 handler/service 重新检查。内部 token 不能从用户请求体或伪造 `X-Builder-Internal-User` 获得用户权限。

数据面私有 bootstrap、Turn/interaction/lifecycle command、Sandbox/ToolTask/result/files 与原生日志导出拥有执行写入。Go runtime-only 私有 listener 接受 Portal 命令和已验证的 Host/Task 协议，控制面普通启动不运行该 worker。Scheduler 新 Webhook sender 只访问 Portal private API，不读 Portal 数据表。

## 继续改造的顺序

1. Channel/Cron/Automation：把原始请求验签、外部身份绑定和配置授权与运行准入分开；持久化稳定 trigger/ingress，再提交 Portal Session/Turn。失败恢复复用原触发 ID，不能重新创建逻辑 Session。
2. Outbound/Channel回复：Portal 保存投递意图，Scheduler 有界认领，provider message ID/fence 回执驱动状态；用户调用路径不直接发送。
3. 旧 Worker/Hosted Store/数据面注册：新机器协议与管理 API 接通后关掉旁路，补普通账号逐表/列权限。
4. 拆同前缀中的运行方法（Issue/Automation/Workflow start/cancel/retry）与资源 CRUD；按方法和调用者路由，不能把整个管理前缀移走。
5. 最后对实际启动的 Gateway/控制面/Portal/Runtime 逐路由探测；声明清单更新不能代替运行授权及旁路关闭验收。
