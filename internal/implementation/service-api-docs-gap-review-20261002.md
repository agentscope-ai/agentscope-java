# AgentScope Service API 文档与能力核对

本报告为内部审计材料，不纳入用户文档导航。工作目录 `/Users/ken/agentscope-3/agentscope-java`，分支 `harness-context-redesign`。本轮文档工作起于 2026-10-02，核对续于 2026-10-05；未修改产品实现，未启动服务或进行全量回归。

## 结论

已有能力足以围绕 API 组织 Agent 创建与接入、Issue 工作分派与反馈、Team、Workflow、Automation、Channel 和 Endpoint 发布。需要修正的主要概念是：Agent API 是平台能力入口的统称，不能仅指 Managed Session API。对业务应用推荐统一 Endpoint / Invocation API；Managed Session API 是访问托管原生会话、文件、checkpoint 等能力的扩展入口。

Managed、External、Hosted 对应运行绑定 `managed`、`external-application`、`hosted-runtime`，共享逻辑 Agent 身份。Team 和 Workflow 使用 Agent 身份组织执行，发布为 Endpoint 后可以向业务交付同一形式的 invocation、snapshot、事件和结果。但运行后端支持的交互命令并不完全相同，文档不能宣称所有后端均可恢复 checkpoint 或进行任意中途交互。

## API 能力矩阵

下表路径均为 Gateway 上的入口；`/api/v1/agent-sessions` 由 Gateway 转发给 Managed data plane。平台账户 Bearer 受 namespace/资源权限约束；应用 Endpoint key 不是管理凭据。

| 场景 | 已实现 API | 身份与请求/结果 | 范围和约束 |
| --- | --- | --- | --- |
| 创建 Agent | `POST /api/v1/agents`；GET/PATCH `/{agentId}` | 平台 Bearer；`tenant,namespace,agentKey,displayName,binding,definition`；返回 `agent,binding,policy,definition` | Managed 与 Hosted 一次配置目录/绑定/定义；不带 binding 可创建目录记录。External 应走注册入口。PATCH 使用 `version` |
| 注册 External | `POST /api/v1/agent-registrations` | 当前入口不验证调用身份；`agentKey,instanceKey,tenant,namespace,routingKey,capabilities`；返回 `agent,binding,instance,registrationCredential` | 运行连接还须使用 IDs、generation 和注册凭据；仅注册不实现任务适配器。存在下述 P0 缺口 |
| Agent 生命周期与版本 | PATCH `/agents/{id}`；GET/PATCH `/definition`；GET `/versions`；GET/POST/PATCH `/bindings`；PUT `/agent-runtime-policies/{id}` | 账户 Bearer；目录支持 active/disabled/archived 等，定义与绑定独立版本 | 目录无 DELETE，不等于不能归档；不把旧 CRD push/rollback 路由作为当前平台主入口 |
| 运行状态 | GET `/agents/{id}/instances`、`/runtime-inventory`、`/overview`；GET `/execution-attempts` | 平台 Bearer；返回实例/遥测/attempt | External runtime-inventory 来自运行通道；没有报告时 `not_reporting`。Hosted 查看 Host/ExecutionAttempt |
| Hosted 供给与创建 | GET `/agents/runtime-options`、`/runtime-hosts`、`/runtime-profiles`、`/runtime-pools`；POST `/runtime-host-enrollments`、`/runtime-host-enrollment-tokens`；POST `/runtime-host-enrollments/exchange` | 管理调用用平台身份；exchange 用 enrollment token；runtime-options 返回 `runtimes,profiles,pools` | 创建 binding 配置使用 `runtimeProfileId,runtimePoolId` UUID。主机 daemon 安装、provider 登录仍是执行机器的准备工作，API 不能替代安装 |
| Issue 分派与反馈 | POST/GET `/issues`；GET/PATCH `/{id}`；POST `/assign`、`/comments`、`/accept`、`/reject`、`/reopen`；GET `/activity` | 平台 Bearer，Agent 协作方法可用任务范围 token；创建时 `assigneeType,assigneeRef`；返回 `issue,agentTask` 或 `orchestrationRun` | Agent/Team 分派后通过 outbox 自动异步派发；Workflow 使用发布版本。用户不必每次手动再调 dispatch |
| AgentTask 执行 | GET `/agent-tasks/{id}`；POST `/dispatch`、`/cancel`、`/retry`；runtime `context/ack/start/progress/respond/complete/fail` | 管理与 runtime token 分工；执行方法要求 task token | 任务进度/反馈/完成均有 API；最终消息不代替完成回报；generation/attempt 隔离旧执行 |
| Team | POST/GET `/teams`；GET/PATCH `/{id}`；POST/PATCH/DELETE `/members` | 平台身份或受限定的协作身份；leader/member 引用逻辑 Agent ID | 可混合多种后端；是内部协作对象，可以发布 job Endpoint |
| Workflow | POST/GET/PATCH `/orchestration-definitions`；validate/publish/revisions/runs；GET `/orchestration-runs/{id}/graph`、`/events`；pause/resume/cancel/rerun/signals | 平台身份；先发布不可变 revision，再启动运行 | Run 事件查询可用；未提供这里的独立 SSE 流，需与 invocation SSE 区分 |
| Automation | POST/GET/PATCH/DELETE `/automations`；trigger/runs/cancel/rerun/deliveries/replay/rotate-secret；`/hooks/v1/automations/{id}/{trigger}` | 管理 Bearer；入站 webhook 使用对应触发器认证 | 定时/手动/外部事件触发和投递处理已有 API，不依赖控制台 |
| Channel | GET/POST `/api/channels`；GET/PUT/DELETE `/{id}`；enable/disable；collaboration/pairing/activity/deliveries/messages/links | 当前 product 接口接受账户 JWT；请求 `type,properties,bindings,defaultAgentId,dmScope` 等 | Channel 操作和协作路由已有 API；collaboration 目标当前为 Agent/Team，未直接支持 Workflow |
| 发布服务 | `/api/v1/applications`；`/endpoints` CRUD、readiness/publish/disable/releases/rollback/credentials | 平台 Bearer；Endpoint 引用 agent/team/orchestration_revision | conversation 只允许 Agent；job 支持三种目标。发布冻结服务配置，凭据可归属 Application |
| 业务调用 | POST `/invoke/v1/endpoints/{slug}/conversations`、`/jobs`；POST `/invoke/v1/conversations/{id}/turns` | Endpoint key 或允许的平台调用身份；返回 durable invocation | 统一与三种 runtime 解耦；应用与权限范围不应由请求任意伪造 |
| 业务事件与恢复显示 | GET `/invoke/v1/invocations/{id}/snapshot`、`/events`、`/events/stream`、`/artifacts`、`/usage` | 同 invocation 的读取身份；SSE cursor / Last-Event-ID；过期 410 后读取 snapshot | 支持断线补取，所有可运行目标使用统一 projection；不等于恢复物理进程或工具副作用 |
| 业务交互与回调 | POST invocation `/actions`、`/inputs`、`/cancel`、`/resume`；GET `/commands/{id}`；POST/GET/DELETE `/webhooks` | 对应 interact/cancel/webhooks:write 等 scope；请求幂等键；回调 HTTPS+签名与重试 | 命令按 capabilities 和当前状态门控；webhook 是 invocation 范围，而非 namespace 全域事件订阅 |
| Managed 原生会话 | `/api/v1/agent-sessions` 及 turns/events/snapshot/items/files/checkpoints/restore/fork/subagents/budget/webhooks | Managed 会话权限；Java controller/原生事件协议 | 只承诺 Managed 原生能力，不把 checkpoint 扩展宣传为所有 Endpoint 已有 |
| namespace 实时变更 | `GET /api/v1/events?tenant=...&namespace=...` | WebSocket；平台身份、范围过滤和逐事件授权 | 是 best-effort 刷新信号，没有 SSE Last-Event-ID 持久重放；断线后通过 REST 刷新 |

主要实现位置：

- `agentscope-service/service-controlplane/internal/httpapi/server.go:380`：全部平台路由和认证分组。
- `agentscope-service/service-controlplane/internal/httpapi/agent_catalog_handler.go:314`：create DTO；`:445` 创建；`:732` 生命周期更新；`:796` 绑定；`:1035` Hosted settings。
- `agentscope-service/service-controlplane/internal/product/managed_definition.go:41`：Managed/Hosted 共享 portable definition。
- `agentscope-service/service-controlplane/internal/httpapi/collaboration_handler.go:135`、`:694`：Issue 创建/分派；`internal/controller/control_outbox_dispatcher.go:148` 自动派发。
- `agentscope-service/service-controlplane/internal/httpapi/service_invocation_handler.go:117`：cursor、SSE、snapshot；`service_invocation_capabilities.go:17` 能力矩阵；`service_invocation_webhook.go:45` 出站回调。
- `agentscope-service/service-controlplane/internal/product/handlers_channels.go:36`、`channel_work_api.go:37`：Channel 管理和协作 API。
- `agentscope-service/service-dataplane/src/main/java/io/agentscope/builder/web/api/AgentSessionCheckpointsController.java:31`：Managed checkpoint/restore/fork/export。

## 实际缺口与待决策事项

### P0：External 注册的认证与 namespace 授权未形成边界

证据：

1. `httpapi/server.go:388` 将注册直接挂到根路由，不经过后面 `/api/v1` 的 auth、scope、namespace、RBAC 链。
2. `httpapi/agent_catalog_handler.go:105` 的 `registerExternalAgent` 不校验 Authorization 或其他凭据；`:146` 向存储无条件传 `TrustedBootstrap: true`。
3. `httpapi/agent_catalog_handler_test.go:37` 的测试名称为 `TestExternalAgentRegistrationIsOpenAndKeepsStableIdentity`。`:76` 显式以无 Header 请求为同一逻辑 Agent 添加副本并期待 201；`:93` 对伪造旧 credential 也期待 201。
4. PostgreSQL 与 memory RegisterExternal 均会发放新的 registration credential；后续运行 claim 校验 credential 不能补上注册入口的身份缺口。

因此，现状并非“首次验证 bootstrap、后续验证 registration credential”。当前入口可在指定 scope 下登记或向已有逻辑 Agent 添加实例并获得运行凭据；需决定是否保留仅受控网络可见的开放登记，还是改为首次平台/工作负载授权、后续 Agent 注册凭据校验，并把 scope 绑定到经过验证的主体。多租户 API 服务发布前建议采用后者并补齐越权、伪造、轮换、撤销和重放验证。本次只更新文档事实，未改变既有开放注册行为。

### P1：缺少 namespace 范围的持久事件订阅

`internal/realtime/hub.go:17` 明确为 best-effort cache invalidation；`:83` 使用 WebSocket Upgrade，内存订阅队列仅 64，未持久保存消费 cursor。`GET /api/v1/events` 不能作为业务侧可靠事件总线；当前可用 Issue Activity、Workflow events 查询和 invocation SSE/回调。

若目标包括外部业务可靠监听任意 Issue、AgentTask、Team、Workflow、Automation 状态，需要增加 namespace/resource 事件订阅资源、持久游标/投递记录、权限重验和回调重试。应复用持久 outbox，但不要把内部 outbox 原样暴露为公共契约。

### P1：统一调用层尚未覆盖所有后端交互

`service_invocation_capabilities.go:17–37` 的真实声明：

- Managed conversation：actions/cancel/inputs/resume 为 true。
- Hosted conversation：仅 cancel 为 true，其余交互为 false。
- External conversation：cancel 按实例 `session-abort` 声明，其余交互为 false。
- job：actions/cancel/inputs 为 true；只有 Workflow job 的 resume 为 true。
- 所有 invocation 的 `checkpoint_restore` 目前均为 false。

这是公开能力差异，不能通过改文档消除。需要定义哪些能力是统一最低契约、哪些保留为 provider 能力；若要求所有后端中途补充、审批恢复或原生 checkpoint，就需要增加后端适配与恢复语义。

### P1：平台管理的机器身份与接口认证不统一

`httpapi/server.go:874` 的 v1 auth 可接受账户 JWT、Kubernetes TokenReview 或配置静态 token（取决部署）；`product/middleware.go:30` 的 `/api/...` 管理入口则要求账户 JWT。Endpoint Application key 仅用于发布服务的调用，不具备创建 Agent、Team、Channel 的管理权限。

当前能够用账户令牌执行管理 API，但长驻 CI/业务控制器没有覆盖全部管理入口的同一套 namespace scoped 机器凭据。是否新增平台 service account / access token、独立授权与轮换/撤销生命周期，需要产品决策；不应让用户把共享 internal token 当作管理凭据。

### P2：Team / Workflow 不能直接发布多轮 conversation

`agent_endpoint_handler.go:208` 明确拒绝非 Agent 的 conversation Endpoint。Team/Workflow 已支持 job、持续输入和服务事件，但若要跨多个 turn 保留 Team 工作上下文，需要新增 conversation 语义及其内部 Issue/Run 生命周期策略。目前文档应准确引导用 job。

### P2：外部接入的 Java/Python 运行传输不完全一致

Python `sdk/python/agentscope_service/__init__.py:85` 默认 `transport="http"`，`bridge.py:300` 可选择 HttpPullTransport；不是“Python 只能连接 gRPC 部署”。Java `ControlPlaneConfig` 与 `SessionBridge` 当前有 HTTP 注册/查询合约和 GrpcTransport；配置 `startGrpc(false)` 不能据此宣称已经建立派发 AgentTask 的运行通道。

建议统一可跨语言使用的 HTTP runtime transport 与完整 starter 示例。适配器能力以真实实现为准，任务 capability 叫 `agent-task`；不要手工填 `execute-task` 或无实现的 true 来通过调度检查。

### P2：Channel 到 Workflow 的直接协作路由缺失

`httpapi/channel_work.go:45` 的目标检查只处理 agent/team。Channel 管理、pairing、活动、重投和链接删除均已有 API，欠缺的是直接以 Workflow 为目标的接入契约，不能把整个 Channel API 说成未实现。

### P2：全平台机器契约和 SDK 还不完整

`docs/service-api/openapi-v1.json` 当前有 46 个路径，涵盖统一调用与部分管理；未完整覆盖注册、Agent 定义/绑定、Issue、AgentTask、Automation、Channel、Runtime Host 等接口。`docs/agent-api/openapi-v1.json` 是 51 个 Managed 原生会话路径，不能代表全平台 OpenAPI。

Python `ManagementClient` 已覆盖 Applications、Agents、Teams、Endpoint，并继承 Workflow 操作，但注册/定义/版本、Automation、Channel 等并非全部有高层 SDK 方法。REST 已有能力可先用 curl/HTTP，后续应集中补统一 OpenAPI、生成 SDK 和请求/响应契约检查，避免把“SDK 没有包装方法”误报为“服务端无 API”。

## 本轮文档处理原则

1. 三种 Agent 的创建/接入、Issue、Team、Workflow、Automation 和 Channel 以 API 为主线，Console 操作单列模块。
2. 统一业务消费入口写为 Agent API / Endpoint Invocation；Managed 原生会话单独标明能力范围。
3. 文档正确说明注册无认证、namespace WebSocket 无持久续传、运行能力差异，不把未实现的目标写成已支持。
4. 内部缺口与优先级集中在本报告；用户文档仅保留使用所需的准确边界。

## 资源 API 补充核对

Workspace、Environment、Memory、Vault 已有独立的资源管理 API，本轮中英文参考手册按实际路径、参数、返回值、权限和版本语义补齐。以下是需要产品或实现决策的剩余事项，不代表整类资源缺少 API。

### P2：Memory Store 缺少元数据更新入口

`internal/product/handlers_memory.go:27–39` 注册了 Store 创建、读取、归档、删除，以及文档读取、写入和脱敏操作，没有 Store 的 PATCH/PUT。`:55` 的 `name`、`description` 仅用于创建请求。因此目前可以更新 Store 内的记忆文档，不能通过公开资源 API 修改 Store 名称和描述。

建议补 `PATCH /api/memory-stores/{id}`，明确允许修改的元数据、授权和并发语义；用户文档目前不承诺重命名操作。

### P2：部分资源修改缺少条件更新，版本字段不能用于防止覆盖

`internal/product/handlers_workspaces.go:280` 的 Workspace PATCH、`:399` 的文件 PUT 和 `:451` 的工具 PUT 不接收预期版本；`head_version` 递增用于追踪草稿变化，并不阻止并发覆盖。`handlers_env.go:40` 的 Environment PATCH、`handlers_vault.go:47` 的 Vault PATCH 和凭据更新 DTO 也没有版本前置条件。

当 API 客户端和 Console 同时编辑时，先读取再写入不能保证避免覆盖。建议给这些修改引入统一的 expectedVersion 或 ETag/If-Match，并在冲突时返回可识别的 409/412；保留 Workspace 发布 revision 的不可变语义。Memory 文档 PUT 已支持 `expectedVersion`（`handlers_memory.go:310`），不在这一缺口之内。

### P2：Vault 的 validate 不是凭据有效性验证

`internal/product/handlers_vault.go` 的 `validateCredential` 主要检查服务能否解密 secret，以及 target 为 HTTP(S) 地址时能否访问；它没有携带该凭据完成 provider 身份与权限校验。现有资源 API 可验证本地存储和连接条件，但不能据 `ok:true` 判断业务权限有效。

如需发布可供自动化使用的凭据健康检查，应区分解密、网络可达和 provider 认证/授权结果，并针对受支持的 credential type 实现验证器。用户文档已明确当前 validate 的范围。


## 任务与自动化 API 补充

本节核对任务、Team、Workflow、Automation 与 Channel 的业务操作接口。以下为实际边界及待评估的改造项，本轮仅改文档，未补实现。

### Issue 完成策略没有进入普通创建 / 更新 DTO

- `service-controlplane/internal/httpapi/collaboration_handler.go` 的 `issueRequest`（约 116 行）接受标题、负责人、执行目标、验收标准等，但没有 `completionPolicy`；`createIssue` 也没有向 service 请求映射该字段。
- `updateIssue` 的 PATCH DTO 同样没有该字段。底层 `collaboration.CreateIssueRequest` 和 `controlmodel.Issue` 则已有 `CompletionPolicy`；`store/postgres/collaboration_issues.go` 在为空时设置 `review`。
- 因此普通 Issue API 不能按请求选择 `automatic` 或 `external`。指南明确普通创建采用人工验收，而 Automation 等专项入口可设置自己的完成策略。建议决定是否对普通创建 / 更新开放枚举，并明确谁能变更、工作已运行或已待验收时是否允许变更，以及外部工作源的策略所有权。

### Automation 尚不能直接编排 Workflow 或接收 Channel trigger

- `controlplane/model/automation.go` 的 `AutomationExecution` 只有 `assigneeType` / `assigneeRef`，执行逻辑仅支持 `agent`、`team`，没有 Workflow revision target；`automation/service.go` 校验与 runtime 派发均遵循这个范围。
- 同一 service 的 trigger 校验对新 execution 模式的 `channel` 明确返回 `channel triggers are not implemented`。目前可用的是 manual、cron、webhook；不能因为枚举出现 channel 就对用户承诺可用。
- 建议将 Automation 执行目标统一为与 Endpoint 一致的有类型 target，支持 pinned Workflow revision，并明确发布版本更新语义。Channel trigger 需要明确定义匹配条件、来源身份、事件去重及回传方式，不能只增加表单或枚举。

### Channel 持久工作入口只覆盖 Feishu，且目标为 Agent / Team

- `product/channel_work_api.go` 的 `putChannelWorkConfig` 在启用非 Feishu 渠道时返回 `durable work delivery currently requires Feishu`；接待时还要求 Feishu verification token 和用户身份绑定。
- `httpapi/channel_work.go` 的目标解析仅接受 `agent` / `team`，没有 Workflow target。其他渠道适配器存在，不代表它们都实现了 Issue 接待、身份映射、持续讨论和可靠回传。
- 用户文档现已分别说明“适配器连接能力”和“持久工作能力”。后续要扩展为统一事件入口，需要逐适配器补齐签名/身份验证、会话与 Issue 关联、消息去重、回传重试，并决定是否允许直接选择已发布 Workflow。

### 管理更新参数仍存在不一致，需要规范接口语义

- Team 的 `PATCH /api/v1/teams/{id}` 要求 `name` 和 `leaderAgentId`，并应用请求中的 `policy` / `description`，不是真正的任意字段局部合并；仅修改一个字段而遗漏原策略有误清空风险。成员 PATCH 同样使用完整配置字段与 `expectedTeamVersion`。
- 版本字段目前并存 `expectedVersion`（Team / Issue 验收 / Workflow / Automation）、`expectedTeamVersion`（成员）、`version`（Namespace / 资源授权 / Issue access / Channel work settings）；文档已按实际 DTO 区分，未统一编造。
- 建议在尚未正式发布时明确 PATCH / PUT 语义，统一乐观锁字段及冲突响应，并建立覆盖字段必需性、替换语义、权限和枚举的 API schema，减少 SDK 与应用误用。

### 事件入口已区分，仍需可靠平台事件订阅

Workflow `/orchestration-runs/{id}/events` 是按 sequence 游标读取的持久 JSON 历史；namespace `/api/v1/events` 则为 best-effort WebSocket 刷新通知，无 cursor 回放。应用发布服务已有 Invocation SSE 与回调，但不能代替全平台 Issue / Team / Automation 的可靠事件订阅。这与上文 namespace 事件缺口属于同一项后续改造，不另行统计为已实现能力。


## 文档验证结果（2026-10-05）

- `npm run check --prefix docs`：全站 464 页、512 条重定向，导航、MDX 语法、站内链接、锚点和资源检查通过。
- 中英文 Service 文档共 235 段 Shell、21 段 JSON、4 段 Python 示例通过静态语法检查；没有执行其中的 API 写入或模型请求。
- `git diff --check -- docs internal/implementation/service-api-docs-gap-review-20261002.md` 通过。
- 在本地 3001 文档服务验证了中文 Service 总览、Console、API 参考及英文 API 快速开始，确认新内容和导航可见。
- 删除中英文发布维护者页面、导航和旧重定向；文档目录无残留引用。
- 本轮没有改动产品实现，也未构建或部署产品服务。已有其他未提交改动予以保留。
