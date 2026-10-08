# AgentScope Service：Agent as a Service 场景与实现评估

评估日期：2026-10-05。代码位置：`/Users/ken/agentscope-3/agentscope-java`，分支 `harness-context-redesign`，包含评估时已有的未提交实现。本文是工程分析与演进建议，不是已发布功能承诺。本次修改文档，不修改 Service / Harness 运行代码，不迁移数据库或部署服务。

## 1. 结论与定位

**当前架构已能支持业务应用中的专业 Agent 服务。应沿 Endpoint / Invocation 主线完善应用接入、资源授权和交付契约，无需重建一套托管 Harness 或任务运行体系。** 对单个受控内部应用，可先用现有能力完成真实场景试点；若面向多用户 SaaS、批量文档和规模化后台执行，则需要补齐下文相应条件。

建议产品定位：AgentScope Service 是面向业务应用的 Agent as a Service 平台。开发者将 Agent 能力发布为 API，让应用提交工作、持续跟踪、参与人工交互，并获取结果与交付物。用户可以留在原有 CRM、任务板、文档系统、研发平台或聊天界面中。

能力说明顺序应为：业务任务与交付 → 应用调用生命周期 → 服务与应用的责任边界 → 执行及组织方式 → 管理与运维。Managed、External、Hosted 是运行方式；Team、Workflow 是组织方式；它们不应承担一级业务场景分类。

## 2. 调研证据如何转化为场景

以下为厂商客户访谈、客户工程文章或产品发布材料中的陈述，未由本项目独立复核其业务指标。相关公司不是 AgentScope Service 客户案例。本评估取其使用形态，不以竞争产品的能力或规模推导本项目已经具备同等能力。

| 公开证据 | 可归纳的使用形态 | 对 Service 的要求 |
| --- | --- | --- |
| [Notion 客户访谈](https://claude.com/customers/notion-qa)：任务板和自动触发调用后台 Agent，交付回到原产品 | SaaS 内嵌后台任务与文件交付 | 持久调用、原页面恢复、用户/资源权限、交付物 |
| [Sentry 案例](https://claude.com/customers/sentry)：诊断交给 Agent 实现修改并创建 PR | 专用系统与通用执行能力衔接 | 受控工作环境、任务范围、代码与测试证据、外部副作用处理 |
| [Wisedocs 工程文章](https://www.wisedocs.ai/blogs/building-managed-agents-for-document-verification)：在专用文档流水线旁加入验证 Agent | 既有流程中的核验节点 | 输入文件、规则与来源版本、结构化输出、人工复核 |
| [Buying Agent 实践](https://claude.com/blog/how-anthropics-sales-team-rebuilt-inbound-with-claude-managed-agents)：咨询与人工转接 | 交互式业务助手 | 会话身份、连续交互、业务工具、人工衔接 |
| [OpenAI Agents API 发布说明](https://openai.com/index/introducing-the-agents-api/)：Nash 长时间物流工作流，Dwelly 异步批量测试 | 长任务与多对象后台执行 | 接单可靠性、并发治理、回调、批次对账；生产陈述与测试陈述分开使用 |
| [Pendo 案例](https://claude.com/customers/pendo-qa)：MCP 入口触发后台托管 Agent | Agent 调用专业服务 | 工具适配、异步句柄、身份传递、嵌套调用预算 |

共同点是：业务产品负责上下文、触发和验收，Agent 服务承担一项有明确范围的工作。由此产生的主要需求是可靠交付与集成，而非每个场景都必须使用多 Agent。

## 3. 当前实现已经具备什么

| 能力 | 实现证据 | 对场景的意义与边界 |
| --- | --- | --- |
| 服务契约与发布 | [Endpoint 模型](../../agentscope-service/service-controlplane/internal/controlplane/model/agent_endpoint.go)、[契约冻结](../../agentscope-service/service-controlplane/internal/invocation/freeze.go)、[定义补全](../../agentscope-service/service-controlplane/internal/httpapi/service_invocation_worker.go) | 已有 schema、Release、目标策略、Team 与递归 Workflow 引用冻结，并补入 Agent 定义；不能称为“没有版本管理” |
| 异步接单与持久派发 | [Job 接口](../../agentscope-service/service-controlplane/internal/httpapi/agent_endpoint_handler.go)、[后台 worker](../../agentscope-service/service-controlplane/internal/httpapi/service_invocation_worker.go) | 先持久化 Invocation，幂等提交，确定性内部任务 ID，跨副本锁及到期扫描；无需保持前端连接来驱动执行 |
| 统一进度与重连 | [公共 journal](../../agentscope-service/service-controlplane/internal/invocation/journal.go)、[读取与流接口](../../agentscope-service/service-controlplane/internal/httpapi/service_invocation_handler.go) | 有 snapshot、游标、事件分页、SSE、保留期过期后的 410 恢复路径 |
| 会话与交互 | [Conversation](../../agentscope-service/service-controlplane/internal/httpapi/service_invocation_conversation.go)、[命令](../../agentscope-service/service-controlplane/internal/httpapi/service_invocation_command.go)、[capabilities](../../agentscope-service/service-controlplane/internal/httpapi/service_invocation_capabilities.go) | 同会话单个活跃 Invocation，持久命令、输入、审批、取消等；操作依赖目标能力，终态 Invocation 不等于可任意 resume |
| 应用身份与治理 | [Application 管理](../../agentscope-service/service-controlplane/internal/httpapi/application_handler.go)、[调用治理](../../agentscope-service/service-controlplane/internal/httpapi/service_invocation_governance.go) | 已有应用归属、凭证 scope、人工角色、并发与 token 用量约束；不是缺少认证或配额系统 |
| 结构化结果与交付物 | [结果映射与校验](../../agentscope-service/service-controlplane/internal/invocation/result.go)、[产物存储](../../agentscope-service/service-controlplane/internal/artifact/provider.go) | 有 JSON Pointer 映射、输出 schema、二进制产物与下载；结构合格不代表语义正确 |
| 回调与失败重投 | [Invocation Webhook](../../agentscope-service/service-controlplane/internal/httpapi/service_invocation_webhook.go) | 持久投递、签名、重试与人工 retry；接收方必须按至少一次投递处理去重 |
| 结果意图与人工验收 | [Managed 任务结果指南](../../docs/v2/zh/service/managed-harness-task-outcomes.md)、[Issue 验收测试](../../agentscope-service/service-controlplane/internal/httpapi/issue_review_test.go) | 已区分任务结果、等待/阻塞/失败及 Issue 验收；不能把“添加验收”当作从零建设 |

架构上，公共调用层对接三种执行方式，Managed 数据面继续承接 Harness 与原生会话。公共 Invocation 是应用的观察对象；原生 Session/Turn 和内部 Issue/Run/Attempt 负责各自生命周期。后续应保持这一边界，避免让调用方因执行方式不同而重新实现多套任务系统。

## 4. 按场景评估是否能落地

“可试点”表示基础调用链支持该设计，仍需准备业务工具、数据与真实质量验收，不表示开箱即用的行业产品。

| 场景 | 当前判断 | 首要补充 |
| --- | --- | --- |
| CRM 方案、报告与文件生成 | 可试点；Job、协作、产物及页面恢复已有基础 | CRM 数据授权、文件引用、来源版本、商机版本关联与人工发送 |
| 故障调查与 PR 修复 | 可试点；Managed / Hosted 与任务编排可用 | 仓库授权、隔离环境、真实 provider 验证、CI、PR 写入幂等与仓库审批 |
| 文档验证与质量检查 | 可用定制工具接入；尚不是统一的文档服务 | 公共文件输入、读取授权、来源定位、版本、领域输出与评测集 |
| 面向终端用户的业务助手 | 可经业务后端试点；不应直接把共享应用 key 当用户身份 | 用户—会话 ACL、数据委托权限、人工转接、针对目标运行时的能力验证 |
| 周期研究与批量后台任务 | 可由外部调度器逐对象调用 | 批次账本、重试/对账、触发统一、容量和真实成本验证 |
| Agent 调用专业服务 | 可通过 HTTP 工具适配器实现 | 长任务句柄、MCP 包装、取消传播、调用链与预算传播 |

最适合作为第一个产品闭环的是“应用内生成可复核方案”或“只读异常调查”：输入与授权可限定，交付可检查，也能验证 API、进度、文件和人工反馈。它们通常不需要先扩展一整套通用业务工作流。

## 5. 内核演进建议

优先级按目标设置：P0 指进入多用户 SaaS / 文件密集生产场景前的必要条件，不阻塞已有受控内部试点；P1 指重复出现的通用接入需求；P2 指在容量和成本数据支持后推进。

### P0：可验证的终端用户与资源委托身份

**现状。** Invocation 保存 Application、Credential、Actor、Principal 与 Correlation ID。[读取鉴权](../../agentscope-service/service-controlplane/internal/httpapi/service_invocation_handler.go)按应用归属及指定的人类角色进行检查。同一 Application 的凭证共享归属，是轮换与统一治理所需行为；模型中尚未形成独立的、经验证的业务 end-user 委托契约。

**现在如何落地。** 由业务后端保存 key、验证登录身份并执行任务/会话/资料 ACL。不能把客户端任意传入的 `userId` 当作权限证明。平台 Namespace 与应用成员角色仍然有效，但不能自动替代外部 SaaS 的用户授权模型。

**建议改造。** 在调用上下文中显式区分调用应用、发起用户、实际操作者与资源授权。增加由可信后端签发或交换的受限委托凭据，约束 audience、有效期、资源范围及可执行动作。将授权上下文传到 tool / Memory / Vault / Environment 访问边界，并保留全链审计。不要仅增加一个未验证的字符串字段。

**验收。** 同一 Application 下两个用户不能互读私有 Conversation、事件与产物；撤销资料授权后不能继续读取；API key 轮换不改变原有归属；人工审批不能由普通应用凭证或 Agent 冒充。内部应用可以继续使用应用级服务账号模式。

### P0（文档场景）：统一输入文件与可追溯交付契约

**现状。** 公共 Job 请求为 title、description、JSON input；已有内部产物上传、公共产物下载及 Managed 原生文件能力，但公共调用者尚无统一的文件上传/引用/授权契约。将任意 URL 放进 input 并不能完成文件访问、所有权和版本校验。

**建议改造。** 增加调用方可用的文件资源与受控上传流程；Invocation 引用不可变 file ID、版本/摘要、类型、大小与授权范围。提交时验证引用，执行时按受限身份挂载或读取，并使 Managed、External、Hosted 遵循一致的可访问性约定。复用已有产物存储与校验能力，而非另起一个文件系统。

输出增加可选交付清单：实际 artifact ID、类型、校验摘要、来源引用、生成任务及版本。领域 schema 仍由 Endpoint 定义；来源定位可作为业务输出而非强制所有任务采用文档页码模型。

**验收。** 未授权引用拒绝、过期文件明确失败、大文件不进入整段 prompt、重复上传可识别、重启后仍可读取；下载内容与清单一致；删除与保留期不破坏仍有效的审计引用。

### P1：所有触发方式复用公开服务调用

**现状。** [Automation action 定义和校验](../../agentscope-service/service-controlplane/internal/automation/actions.go)及[运行实现](../../agentscope-service/service-controlplane/internal/automation/runtime.go)支持创建 Issue、添加评论、启动与通知 Workflow，没有直接 invoke Endpoint 的 action。当前可以由外部调度器调用 API，功能可落地，但产生两条不同的工作入口。

**建议改造。** 增加调用已发布 Endpoint 的触发动作，复用现有 admission、契约绑定和持久派发逻辑。规则明确所用 Application、release 选择策略、输入映射、幂等键生成方式与业务关联；记录触发事件到 Invocation 的映射。不要在 Automation 中再造一套派发器或绕开配额。

**验收。** 同一事件重投返回相同逻辑调用；禁用应用和撤销凭证有效；配额一致；定时错过执行、失败重试及死信可见；可以从触发记录追到结果。批次业务状态仍可由外部系统维护。

### P1：交付、验收与返工的稳定关联

**现状。** 已有 Task Outcome、Issue acceptance、Workflow 人工关卡及 output schema。缺少的是不同业务应用复用这些能力时的清晰关联与质量证据约定，而非运行状态机完全缺失。

**建议改造。** 优先完善交付清单、证据链接以及修订调用之间的关联，例如由原 Invocation 派生的新修订调用与被替代产物；公开查询应能取得相应关联。允许 Workflow 在执行完成前运行确定性校验或人工关卡。若业务系统持有最终验收状态，保留其权威性，无需给所有 Invocation 强加一个通用 `accepted` 终态。

**验收。** 运行成功、结构校验通过、业务批准各有可核查证据；拒绝后能够明确发起修订，保留原输入与交付；返工不覆盖历史、失败不冒充完成。PR 合并、订单履约和方案发送的验收规则由各自业务系统定义。

### P1：资源版本与实际读取来源的可追溯性

**现状。** Release 已冻结目标、Agent 定义和 Workspace 相关配置；[定义摘要](../../agentscope-service/service-controlplane/internal/product/handlers_agents.go)包含 tools、skills、files、workspaceVersion 等。另一方面，[Memory 文件系统](../../agentscope-service/service-dataplane/src/main/java/io/agentscope/builder/web/managed/MemoryMountService.java)提供实时挂载与写入。稳定配置不等于每次读取外部知识都得到同一内容，这是需要明确的版本策略，而不是所有资源都必须冻结。

**建议改造。** 区分发布时固定的能力版本与执行时读取的动态资料。对审计型服务支持固定资料版本，或记录读取时的 URI、版本/摘要、时间及权限主体；将来源与交付关联。实时订单或库存仍按实时语义读取，记录查询证据，不应为追求复现返回过期业务状态。

**验收。** 同一 Release 下更新知识后，能解释两次结果为何不同；历史结果能够定位所用资料；固定版本场景不因资料更新静默漂移。密钥值不进入发布快照或证据文件。

### P1/P2：作为工具消费的专业 Agent 服务

**现状。** HTTP Endpoint 已可被工具调用。[路由](../../agentscope-service/service-controlplane/internal/httpapi/server.go)中的协作 MCP 用于内部任务协调，没有发现发布 Endpoint 自动导出公共 MCP 的实现。

**建议。** 先提供标准适配器，将 Endpoint schema 转成工具输入，提交后返回 Invocation 句柄，提供查询/交互/取消操作。MCP 导出可在接入层实现，无需更换 Harness。若希望 Service 原生治理多层调用，再增加父子调用关联、超时/取消传播和委托预算；下游仍执行自己的鉴权。

**验收。** 长任务不会依赖一个一直阻塞的同步工具请求；调用方退出后子任务可查可取消；重试不重复创建；跨应用调用不能放大权限；防止无界递归和预算重复计算。

### P2：根据实测扩展调度、背压和计费治理

**现状。** 已有跨副本 admission 锁、应用并发额度、到期工作队列、journal checkpoint 和保留期。worker 当前按最多 100 条读取、局部 4 并发处理，并用 CAS 领取下一次扫描；公共 SSE 也会刷新/投影调用状态。这是需要压测的运行方式，不能仅凭代码直接判定它无法扩展。

token budget 根据已上报用量触发限制与取消，不是模型调用前的精确金额预扣。[用量治理](../../agentscope-service/service-controlplane/internal/httpapi/service_invocation_governance.go)已经做汇总去重及累计记录，后续应在其上完善，不能另加一套口径冲突的计数器。

**建议。** 先量化等待、执行、人工等待、结果可见与回调耗时，以及数据库连接、扫描成本、SSE 慢消费者和多应用公平性。必要时增加通知唤醒与批处理、按应用公平调度、隔离大批任务，并保留持久扫描兜底。对需要强成本约束的服务，设计执行前预算预留、模型/工具开销记账、迟到 usage 对账、过期预留释放及取消后的最终结算。

**验收。** 在明确目标并发与保留期下测得 p95/p99，而非引用其他产品规模；长短任务相互不饥饿；消费者掉线不拖住执行；模型用量迟报与多子任务并发时费用边界可解释。目标值由选定场景的需求与实测确定。

## 6. 哪些工作留在应用与适配层

| 工作 | 建议归属 | 原因 |
| --- | --- | --- |
| CRM、GitHub、订单、客服系统连接 | 业务工具 / 连接器 | 权限、数据结构和业务副作用各不相同 |
| 按钮、任务板、聊天窗口与通知 | 应用 UI 与后端 | 用户需要留在原产品；复用快照和事件协议即可 |
| 领域 SOP、规则、来源标准与质量评测集 | Skill / 配置 / 领域代码 | 通用 Harness 无法决定合同、订单或方案的正确性 |
| 发邮件、改订单、合并 PR 的幂等与核对 | 业务系统和工具适配器 | Invocation 幂等不保证外部事务 exactly-once |
| 商机、案件、工单和批次的最终状态 | 业务系统 | Service 返回执行事实，业务系统负责其生命周期 |
| 通用身份、文件资源、任务关联、事件与预算 | Service 共性层 | 跨应用重复出现，应形成稳定的公共契约 |

## 7. 建议实施顺序

1. **验证一个真实闭环。** 选方案生成或只读异常调查，使用现有 Endpoint、单 Agent、应用后端、快照/事件与真实产物；收集结果质量、耗时、成本和人工修改率。
2. **补齐目标场景的生产条件。** 多用户产品优先委托身份；文档密集场景优先文件与来源契约。两者以越权、重启、版本变更和真实文件用例验收。
3. **扩大入口与复用。** 加入 Endpoint Automation、修订关系、标准工具适配；按需要引入 Team / Workflow，而非在第一个场景强制多 Agent。
4. **凭容量数据扩展。** 完成真实模型、工具、worker crash、长时间运行与成本对账，再决定队列、通知、调度公平性和预算机制的改造规模。

## 8. 验证证据与本次边界

本次只读核查和针对性验证中，`go test ./internal/invocation ./internal/httpapi ./internal/automation ./internal/artifact` 通过。需要 `CONTROL_PLANE_TEST_POSTGRES_DSN` 的测试在未配置时会跳过，因此这次包测试不被表述为重跑了数据库、实际模型或全链故障实验。

[2026-10-02 集中回归](session-service-regression-20261002.md)已有真实 Gateway / CP / DP、PostgreSQL、HTTP worker、Agent/Team/Workflow Job、Managed Conversation、产物字节、CP 强制重启和事件恢复的历史记录；使用确定性模型和 runner。该记录也明确列出多日容量、真实 provider、部分 worker 故障矩阵及云端验证的未覆盖范围。本报告引用其历史结果，没有把它算作本次重新执行。

本次文档验证结果：

- `npm test`：12 项通过。
- `npm run validate`：464 页、512 个重定向的导航、语法、站内链接、锚点与资源检查通过；Mintlify 构建校验通过。
- `npm run broken-links`：未发现断链；另行检查本报告的仓库相对链接，目标文件均存在。
- 本地浏览器检查中英文首页、Service 总览和场景页，确认新文案、六类场景、表格与中文调用链路图正常呈现。
- 原 `3001` 文档预览已停止，本次从指定工作目录以 `npm run dev -- --port 3001 --no-open` 重新启动，供继续查看。未启动、重建或部署 Service 后端。

文档通过不能代替业务场景端到端验收。生产条件、建议改造与已实现能力在本文中分别标识。
