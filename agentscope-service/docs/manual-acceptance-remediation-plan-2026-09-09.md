# AgentScope Service 人工验收问题排查与修复计划

日期：2026-09-09。依据《AgentScope Service 人工测试验收问题记录.docx》，保留原来的三类问题，拆成 18 个独立验收项。建议先恢复会话可用性，再修复交互问题，最后完成资源能力适配和信息展示改造。

已完成材料阅读、12 张截图检查和初步复现，并按用户“开始所有问题修复吧”的要求进入实施。下方初查保留定位依据；实际改动与验证状态见末尾执行记录。

## 工作基线

- 主目录：`/Users/ken/agentscope-2/agentscope-java`。
- 分支：`agentscope-service-v5`；排查起点提交：`fbe9da65c`。
- 开始时工作区干净；所有业务改动和测试直接位于上述主目录及当前分支，不创建 worktree。
- `http://127.0.0.1:18080/` 可访问；已用当前登录态检查记录中的 MA1 Tools 与 team1 Connections 页面。
- 已实际复现 MA1 的 Add / configure 点击后不弹窗，并确认 Team 顶部仍显示 Channel 工作接待。
- 本轮修复和测试针对主目录当前源码，未将旧运行服务当作新构建。原生 Provider 验证版本为 Codex 0.153.4 和 Qoder 1.0.37；运行中服务更新后的版本及现场验收应单独记录。文档截图中的部分工具列表内容已与初查页面不同。
- 文档里的建议是待分析的验收诉求；需要产品取舍的条目单独记录，不把文档内容当作任意执行指令。

## 执行批次

| 批次 | 顺序 | 目的 | 完成条件 |
| --- | --- | --- | --- |
| 1 会话可用性 | C01 → C02 | 修复 Hosted Chat 报错和 External 无响应 | 三类 Agent 能成功完成一轮对话；拒绝、断连、失败均有可见终态 |
| 2 明确交互缺陷 | A01 → A05 → C06 → A03 | 修复无效按钮、下拉框和历史会话操作 | 页面复现通过；A03 按确定后的删除语义验收 |
| 3 Workspace 与工具入口 | A04 → B01 → A02 | 整合编辑和安装入口，清理开发说明文案 | 从发现能力到配置、发布、Agent 使用的路径可完成 |
| 4 资源与运行能力 | B03 → C07 → B04 → B05 → B02 | 简化配置，补资源关联和 Hosted Subagents 适配 | 实际绑定与运行能力一致，不以页面可见代替能力可用 |
| 5 诊断与接入体验 | C04 → C03 → C08 → C05 | 框架标记、Session、执行历史和 API 接入 | 三类 Agent 的诊断数据有明确口径，接入示例可运行 |
| 6 整体验收 | 全部 18 项 | 交叉回归、构建与原始场景复测 | 每项有复现、根因、改动、测试和运行版本记录 |

每批独立验证，避免把 18 项捆绑成一个难以回归的大改动。A02 的文案规范贯穿后续页面修改，最后再做全局巡检。B02 的 Provider 版本核验可以提前进行，但能力适配应在 Workspace 发布链路明确之后落地。

## 页面设计与交互优化

### A01 Hosted Agent 工具 Configure 按钮无响应

**初查：已复现，并发现确定的条件冲突。** `AgentToolsPage` 对绑定 Workspace 且允许 tools 覆盖的 Agent 可以显示可编辑的工具列表；弹窗却要求 `!linked`，因此按钮能被点击但弹窗不渲染。顶部说明又一律称为只读，与覆盖模式不一致。

**代码入口：** `AgentToolsPage`、`ToolsActivePanel`、`LinkedWorkspaceBanner`、`ToolsCatalogPanel`。

**修复：** 统一工具、MCP、按钮和弹窗的编辑能力判断。继承 Workspace 的字段显示“在 Workspace 编辑”；允许覆盖的字段提供有效编辑入口；无权限时展示具体原因。同步按 Hosted Provider 实际支持的工具能力展示，核查 Managed 工具名是否被误当成 Hosted 原生能力。

**验收：** 未绑定、继承、允许覆盖、无编辑权限四种状态；保存后重进页面一致；Workspace 发布和 Agent 绑定更新后运行使用正确配置。

### A02 全局页面说明文案优化

**初查：已确认。** `ResolvedDefinitionFiles` 使用碎片式说明；工具警告中还直接展示旧接口被删除等实现历史。`Agent v4` 实际来自 `agent.version`，表示 Agent 定义版本，并非硬编码的开发阶段版本，但放在普通帮助文案里仍不合适。

**修复：** 页面说明用完整句子解释用途和生效时机；把定义版本放在 Versions 或必要的版本信息中。保留“需发布后生效”“只读来源”等影响用户操作的信息，删除旧 API、内部类型和迁移过程说明。覆盖 Agent、Workspace、Chat、Session、Team、Execution、Environment、Vault、Memory 的说明、空状态、提示与错误。

**示例方向：** “在此查看 Agent 当前使用的子代理定义；修改关联 Workspace 并更新绑定后，新执行会使用更新后的内容。”具体生效时机以实现和回归结果为准。

**验收：** 每个当前可达页面逐页检查；说明与权限、版本和运行行为一致；实际错误保留可操作原因及可复制的诊断标识。

### A03 Chat 历史会话支持右键删除

**初查：** 当前 `ChatPage` 提供置顶、归档与恢复，没有列表右键删除入口；Chat API 的状态更新仅接受 active/archived。不能只把“删除”映射成“归档”就算完成。

**待确定：** 删除个人 Chat 列表记录并保留执行诊断，还是永久删除消息与会话数据。推荐前者；最终以用户答复为准。

**修复：** 补列表右键菜单，并提供可发现的“更多”按钮和键盘操作；建立对应的后端删除语义、所有者/租户检查、当前选中项切换与缓存刷新。明确运行中是否须先停止，以及关联 Issue、AgentTask、Session 的保留关系。永久清理作为另一种语义，不与归档混淆。

**验收：** 删除当前项、非当前项、最后一项、归档项；刷新不恢复；重复操作幂等；他人或其他命名空间记录不可删除；运行中行为符合确定的规则。

### A04 Workspace Tools 页面层次及 MCP Catalog 顺序

**初查：已确认。** 页面顺序为连接编辑器、内置工具、MCP Catalog；连接列表和较长编辑表单连续铺开。

**修复：** MCP Catalog 提到前面，提供“从目录添加”和“手动添加”；已配置连接单独成区，展示连接身份、传输方式和认证状态；新增/编辑表单按操作打开。内置工具独立展示；保存与发布状态保持清晰。统一 Agent 页面复用的 `McpConnectionsEditor`。

**验收：** 目录添加、手动添加、编辑、移除；HTTP 和 stdio 显示各自字段；OAuth 连接后状态更新；较窄窗口无横向溢出；发布前后的生效状态可区分。

### A05 Agent 下拉框显示异常

**初查：代码已确认一个直接原因。** `AgentPicker` 使用 `bg-popover` 和 `text-popover-foreground`，而 `index.css` 没有对应主题颜色定义，与截图中菜单透明、透出下层按钮一致。另需实测父容器裁切和层叠关系。

**修复：** 补齐共享主题颜色；根据实测决定是否需要 Portal 或定位层调整，不仅叠加更大的 z-index。

**验收：** Team 创建和成员选择等复用位置；菜单背景不透明，不被容器截断；搜索、长名称、滚动、方向键、Enter、Escape 均可用。

## Resource 资源和 Workspace 优化

### B01 Skills 和 Marketplace 合并入口

**初查：已确认。** `WorkspaceDetailPage` 将 skills 和 marketplace 定义为两个顶级页签，手写 Skill 与市场安装的流程分离。

**修复：** 合成 Skills 页面，以已安装列表为主体，同时提供“新建 Skill”和“从市场安装”；市场源管理放在该页次级入口。展示来源、版本和本地修改状态；明确同名安装、更新冲突及卸载行为。保留旧 `tab=marketplace` 深链接的跳转兼容。

**验收：** 手写 Skill、市场安装、同名冲突、修改后更新、卸载；发布后的 Agent 快照与实际使用一致；旧链接仍可打开正确入口。

### B02 Hosted Agent 使用 Workspace Subagents

**初查：属于真实适配缺口。** Codex、Qoder 的 Provider Descriptor 均未声明 `Subagents` 能力，`ValidateDefinition` 会拒绝带 `subagents/` 的不支持定义。把文件复制到目录，或只把 Supported 改成 true，都不足以完成适配。

**修复路径：** 先定义平台支持的共同字段和不能等价转换的字段，再按 Provider 将 Workspace 子代理定义转换成其原生配置或插件。实现版本/能力检测、任务工作目录内加载、清理和重试恢复；验证模型、工具和权限限制确实生效；不支持的组合在执行前解释原因。

Qoder 官方 CLI 文档明确存在项目目录 `.qoder/agents/*.md`、插件及 `--agents` 注入入口，因此插件是一条可行路线，但不是唯一方案。当前项目调用的是 `qodercli`，必须核验实际安装版本的契约，不能直接假定最新文档与当前二进制完全一致。[Qoder CLI Subagent](https://docs.qoder.com/cli/subagent)

Codex 当前适配走 `app-server`；需核验对应版本可加载的原生子代理/插件接口，再决定生成格式。此项暂不承诺某个未经验证的配置路径。

**验收：** Provider 内确实调用到命名子代理；能观察调用及结果，子代理遵守工具边界；同名定义、非法配置、不支持版本、失败、取消、恢复均有明确表现；Managed 现有子代理能力保持可用。

### B03 Environment 的作用及入口简化

**初查：并非只有展示元数据。** `EnvironmentSpecFactory.applyEnvironment` 按 local、remote、self_hosted、sandbox 选择实际文件系统/执行方式；`HarnessAgentBuildService` 消费解析后的 Environment。self_hosted 还关联外部 Worker 工具；sandbox 有模板和运行配置。因此“Managed Harness 会识别配置”不等于“不再需要配置”。

**修正方案（用户反馈后）：** 保留 Environment 管理入口，遵循原有命名空间 configure 权限（admin/developer）；保留 local、remote、sandbox、self_hosted 创建类型、配置和 Worker 状态。Agent Runtime 中可保存默认环境，新 Session 直接展示环境选择并预填 Agent 默认值；Automatic default 只作为便利选项。避免在 Hosted 的 Runtime Host/Pool 与 Managed Environment 之间制造错误映射。

**用户纠正：** 不应将配置简化理解为隐藏 Environment 管理及 Agent/Session 关联。已撤销本次额外添加的管理员专属限制。

**验收：** 默认环境可运行；选择 self_hosted 后执行落到对应 Worker；失联可见；沙箱使用实际模板配置；旧 Agent/Session 绑定继续可解析。

### B04 Vault 显示关联使用方

**初查：** Vault 页面侧重 Vault 和 Credential 自身，缺少明确的使用方列表；现有 Resource Access 页面及依赖机制已有 dependencies/dependents 概念，可优先复用。Agent 默认 Vault 和 Session 挂载是不同层次的关联。

**修复：** 显示可访问的 Agent、连接用途及当前绑定来源；同名项增加来源、目标和简短标识，避免靠改名猜用途。区分“配置引用”与“某次执行实际挂载/使用”；后者只有有证据时才展示。先检查重复 OAuth Vault 是否来自中断流程，不能直接合并或删除同名项。

**验收：** 同名多 Vault、多个 Agent 共用、无引用、授权中断、解除引用；用户只能看到有权限的使用方；不读取或展示凭证值。

### B05 Memory 如何被 Managed Harness 使用

**初查：调用链已确认。** 控制面解析已绑定的 Memory Store → `HarnessAgentBuildService.applyManagedSessionBuildOptions` → `MemoryMountService.createResolvedFilesystems` → `applyMemoryStoreRoutes` → 注册 `ManagedMemoryTools`，同时追加目录说明到系统提示。内容由 Agent 按需读取，不是默认把全部文档塞入模型上下文。

**重要细节：** 当前构建逻辑对共享知识使用 `sharedKnowledgeAccess`，设为 read_only；私有工作记忆属于 Session 文件。`memory_store_list/read/write/edit` 是统一访问接口，具体写入仍受只读约束；普通 Shell 或 Worker 文件工具不等于访问这些实时挂载。底层通用 Memory 文件系统支持写入，不能据此误称当前 Managed Session 的共享知识可写。

**修复/说明：** 页面说明区分共享 Memory Store、Agent 私有工作记忆和当前上下文；显示绑定 Agent、访问模式及生效状态。核查当前用户的 Memory 是否真正绑定；若发现挂载缺失，再沿解析链修复。共享知识运行期只读的既有行为先保留。

**验收：** 绑定含唯一标记的文档，让 Agent 通过工具读取；未绑定时不可读；只读写入失败；内容更新后按当前缓存契约可见；跨 Session 读取同一共享知识但私有工作文件隔离。

## Agent 相关功能优化

### C01 Hosted Chat 报 conversation AgentTask was not materialized

**优先级最高。初查：错误位置已确认，根因尚待复现验证。** `dispatchHostedConversationTurn` 创建 Issue/Run、调用 `materializeEndpointTarget` 后，再按 RunID/AgentRef 查任务，查不到即产生原始报错。

**重点假设：** 创建的 Issue 使用 system actor，而任务查询应用 WorkAccess 过滤。私有 Issue、调用者身份与内部任务所有权不一致，可能造成“创建成功、同请求却查不到”。Memory/Postgres 都存在权限过滤；不能把空结果直接判断成数据库没写入。当前浏览器显示 admin，但仍需核验命名空间实际角色，不能预先认定这就是原故障根因。

**排查与修复：** 加可定位的阶段日志/测试证据，核对创建结果、Run/Node/Agent 标识、权限上下文、事务及重试路径。修复内部会话任务的所有权/访问或创建返回值传递；保持 Chat 私有，不以全局取消权限过滤或把 Issue 公开作为修复。失败时同步 Session/Turn 终态，避免残留“忙碌”阻止重试。

**验收：** 首轮、多轮、失败重试、并发提交；管理员与普通成员；Memory 和 Postgres 实现一致；Chat、Session、Run、AgentTask、Attempt 可关联；其他用户不能读取个人 Chat 的执行内容。

### C02 External Agent 不可用时没有可见错误

**初查：尚不能归因于前端缺一个提示。** `sendAgentConversationTurn` 已检查实例健康并返回错误，ASDP 发送端也会返回断连错误，`ChatWorkspace` 已有 HTTP 错误展示。因此要覆盖“创建时不可用”“发送前下线”和“命令发送成功后无响应”三种时序。

**排查与修复：** 跟踪 capability 检查 → Chat/Session → ASDP command → acknowledgement/report → Session events → 前端 pending。重点检查已发送但未确认、实例中途断线、报告失败、事件订阅断线以及无人上报终态。补持久化失败/超时与明确错误码，前端解除 busy，保留输入或提供重试。不能仅靠浏览器定时器将仍在执行的任务当成失败。

**验收：** 无实例、实例不健康、通道断连、命令拒绝、执行错误、超过截止时间、事件断线后补拉；错误可见且不会永久停在“working”；恢复后可重试，无重复轮次。

### C03 Session 页面按不同 Agent 能力展示

**初查：** 目前共用 `StatusStrip` 固定展示六张卡；缺失 totalTokens 被显示为 0；Hosted 没有 External 实例时仍显示 unknown。项目已有 telemetry state 类型和对话/事件适配层，可以继续统一数据表示。

**建议：统一页面骨架，按本次执行的实际能力展示扩展面板。** 同一个逻辑 Agent 可以有不同运行绑定，不能只按 Agent 列表标签决定整个 Session 页面。

| 展示层次 | Managed | Hosted | External |
| --- | --- | --- | --- |
| 共同主体 | 轮次、消息、状态、耗时、错误、结果、关联执行 | 同左 | 同左 |
| 用量 | 以实际记录展示输入/输出、单轮/累计 | 以 Provider 事件为准 | 以 SDK 上报为准 |
| 当前上下文 | 有快照时展示占用和压缩信息 | Provider 实际提供时展示 | SDK 实际提供时展示 |
| 执行位置 | 数据面/Environment | Provider、Host、Pool、Attempt | 框架、实例、健康及连接状态 |
| 深度诊断 | 工具、审批、Memory、子代理等已有能力 | 原生事件和支持的扩展 | 根据上报能力显示 |

当前上下文占用与历史 Token 消耗必须分开；未上报、不支持、不适用与数值 0 分开；附采集时间，避免把旧快照当当前状态。Token 计费未有可靠价格和实际模型时不编造成本。

行业参考：LangSmith 把一次操作组织为 Trace、模型/工具调用组织为 Run，并把多轮 Trace 归入 Thread；同时提供消息序列视角。这里采用“统一消息/执行骨架、按能力扩展”的方案是结合该实践与本项目多绑定结构作出的设计判断。[LangSmith observability concepts](https://docs.langchain.com/langsmith/observability-concepts)

**验收：** 三类 Agent、缺失/部分/完整遥测、多个绑定及重试换运行方式；缺数据不显示假 0；单轮和累计统计不重算重复事件；对话与事件分页/重连保持一致。

### C04 External Agent 列表展示框架

**初查：** `AgentInstance` 及前端 `CatalogAgentInstance` 已有 framework/frameworkVersion/SDKVersion；`AgentsHubPage` 主要显示 runtimeKind，数据未在列表中表达。

**修复：** 从实例注册信息聚合为列表摘要，显示 AgentScope、DeepAgents、ADK 等框架；不从 Agent 名称猜测。多个框架/版本或离线状态明确处理；避免每张卡片单独请求造成 N+1 查询。

**验收：** 单实例、多实例同框架、框架混合、未上报、全部离线；Agent 运行类型与框架名称不混淆。

### C05 Team Endpoint 展示可直接使用的 API 接入示例

**初查：** `PublishEndpointCard` 当前主要给发布、管理、测试和复制 URL 入口，没有完整调用流程。

**修复：** 针对 Endpoint 当前已发布模式和契约，生成实际 URL、认证方式、curl、请求/响应示例，以及异步任务查询、事件和错误处理说明。Team 的 jobs 接口不能套用其他 conversation 接口的示例。代码示例使用可替换凭证占位符；依据真实发布版本生成输入 schema。布局可用请求和响应切换，复杂接入信息展开显示。

**验收：** 用测试 Endpoint 跑通提交、状态查询、完成结果、失败和鉴权错误；示例 JSON 符合实际契约；复制内容在指定测试环境可执行。

### C06 Team 页面移除 Channel 工作接待摘要

**初查：已在页面确认。** `TeamDetailPage` 在页签上方渲染 `ChannelAssociations`。

**修复：** 移除 Team 顶部重复摘要；Channel 配置和实际工作接入行为继续由现有入口承担。

**验收：** Team 所有页签不再占用这块区域；Connections 中 API 发布可用；已有 Channel 路由和工作回传回归通过。

### C07 Team 新增 Worker 不要求 Runtime selection

**初查：有实际含义，但不必成为普通操作的必填心智负担。** `TeamMembersEditor` 默认使用 Agent 自动策略；选具体后端时会写入成员级 `runtimeBindingPolicy`，覆盖运行选择，并要求 Binding 等信息。

**建议：** 新增成员默认继承 Agent 策略；普通表单仅填写 Agent、角色与职责。管理员高级设置可保留指定运行方式能力。已有固定策略应可见并可取消，不因表单简化静默清空。与 B03 一起等待用户确认入口方向。

**验收：** 默认创建不新增覆盖；自动策略选出可用运行方式；旧固定策略加载/编辑保持不变；高级覆盖生效且错误明确。

### C08 AgentTask 执行历史和结果布局优化

**初查：已确认 JSON 来源。** `TaskDetailPage` 右下角直接输出 `task.runtimeBinding`，内容可能包含绑定标识及定义快照；两列还直接序列化 task.result 与 attempt.result。这些有诊断价值，但不能承担执行过程和结果的主要展示。

**修复：** 顶部展示任务名称/目标、执行 Agent、状态和操作；主区域突出最终结果、产物和失败原因；按时间列出各次尝试，展示等待、派发、开始、完成/失败及耗时，以已有事件为准；关联 Run、Issue、Session 使用名称和跳转。Inputs 展示真正输入摘要；结果按实际内容渲染，避免反复显示同一输出。原始 JSON 收入“诊断详情”，标识可复制，定义快照按需加载。

**验收：** 用户能快速找到“输入是什么、谁执行、现在到哪、为何失败、结果在哪里”；成功、失败、多次重试、长输出、无结果场景可读；取消/重试操作有请求中和失败反馈；敏感字段不进入普通结果区。

## 关键代码导航

以下路径均位于项目主目录，可用于后续逐项实施。

| 范围 | 入口 |
| --- | --- |
| Hosted Chat | [hosted_conversation.go](/Users/ken/agentscope-2/agentscope-java/agentscope-service/aistio/internal/httpapi/hosted_conversation.go)、[agent_endpoint_handler.go](/Users/ken/agentscope-2/agentscope-java/agentscope-service/aistio/internal/httpapi/agent_endpoint_handler.go) |
| 通用会话调用 | [agent_invocation_handler.go](/Users/ken/agentscope-2/agentscope-java/agentscope-service/aistio/internal/httpapi/agent_invocation_handler.go)、[chat_handler.go](/Users/ken/agentscope-2/agentscope-java/agentscope-service/aistio/internal/httpapi/chat_handler.go) |
| Chat UI | [ChatPage.tsx](/Users/ken/agentscope-2/agentscope-java/agentscope-service/frontend/src/features/chat/ChatPage.tsx) |
| 工具与 Workspace | [AgentToolsPage.tsx](/Users/ken/agentscope-2/agentscope-java/agentscope-service/frontend/src/pages/AgentToolsPage.tsx)、[WorkspaceDetailPage.tsx](/Users/ken/agentscope-2/agentscope-java/agentscope-service/frontend/src/pages/WorkspaceDetailPage.tsx)、[McpConnectionsEditor.tsx](/Users/ken/agentscope-2/agentscope-java/agentscope-service/frontend/src/components/McpConnectionsEditor.tsx) |
| 下拉框与主题 | [AgentPicker.tsx](/Users/ken/agentscope-2/agentscope-java/agentscope-service/frontend/src/components/AgentPicker.tsx)、[index.css](/Users/ken/agentscope-2/agentscope-java/agentscope-service/frontend/src/index.css) |
| Hosted 适配 | [compatibility.go](/Users/ken/agentscope-2/agentscope-java/agentscope-service/aistio/internal/runtimehost/provider/compatibility.go)、[Codex adapter.go](/Users/ken/agentscope-2/agentscope-java/agentscope-service/aistio/internal/runtimehost/provider/codex/adapter.go)、[Qoder adapter.go](/Users/ken/agentscope-2/agentscope-java/agentscope-service/aistio/internal/runtimehost/provider/qoder/adapter.go) |
| Environment 与 Memory | [HarnessAgentBuildService.java](/Users/ken/agentscope-2/agentscope-java/agentscope-service/service-dataplane/src/main/java/io/agentscope/builder/web/catalog/HarnessAgentBuildService.java)、[EnvironmentSpecFactory.java](/Users/ken/agentscope-2/agentscope-java/agentscope-service/service-dataplane/src/main/java/io/agentscope/builder/web/managed/EnvironmentSpecFactory.java)、[MemoryMountService.java](/Users/ken/agentscope-2/agentscope-java/agentscope-service/service-dataplane/src/main/java/io/agentscope/builder/web/managed/MemoryMountService.java) |
| Vault 依赖展示 | [VaultsPage.tsx](/Users/ken/agentscope-2/agentscope-java/agentscope-service/frontend/src/pages/VaultsPage.tsx)、[ResourceAccessPage.tsx](/Users/ken/agentscope-2/agentscope-java/agentscope-service/frontend/src/features/settings/ResourceAccessPage.tsx) |
| Session 与执行历史 | [OperateSessionDetailPage.tsx](/Users/ken/agentscope-2/agentscope-java/agentscope-service/frontend/src/features/operate/OperateSessionDetailPage.tsx)、[StatusStrip.tsx](/Users/ken/agentscope-2/agentscope-java/agentscope-service/frontend/src/features/operate/components/StatusStrip.tsx)、[TaskDetailPage.tsx](/Users/ken/agentscope-2/agentscope-java/agentscope-service/frontend/src/features/tasks/TaskDetailPage.tsx) |
| Team 与 API 接入 | [TeamDetailPage.tsx](/Users/ken/agentscope-2/agentscope-java/agentscope-service/frontend/src/features/teams/TeamDetailPage.tsx)、[TeamMembersEditor.tsx](/Users/ken/agentscope-2/agentscope-java/agentscope-service/frontend/src/features/teams/TeamMembersEditor.tsx)、[PublishEndpointCard.tsx](/Users/ken/agentscope-2/agentscope-java/agentscope-service/frontend/src/components/PublishEndpointCard.tsx) |
| Agents 列表 | [AgentsHubPage.tsx](/Users/ken/agentscope-2/agentscope-java/agentscope-service/frontend/src/features/build/agents/AgentsHubPage.tsx) |

## 回归与交付规则

修复每项行为时补有意义的回归测试：先用失败用例稳定复现，再改实现。纯说明文案主要通过逐页检查验收，避免对句子逐字断言。

- Go：优先执行相关 httpapi/store/runtimehost/provider 测试；C01 必须包含 Postgres 与受限身份，不能仅凭内存仓库和管理员用例通过就关闭问题。外部运行时用可控断连、延迟、拒绝报告覆盖异步路径。
- 前端：相关 Vitest、TypeScript/Vite 构建；复用 `e2e/conversation.e2e.ts`、`workspace-definition.e2e.ts`、`resource-management.e2e.ts`、`permissions.e2e.ts` 等场景补浏览器验收。新增删除语义同时验证 API 与 UI。
- Java：Environment、Memory 改动执行 service-dataplane 及依赖模块相关测试，重点检查只读边界、挂载、Worker/沙箱路径。
- 最终提交前按仓库约定完成 `mvn clean verify`，另完成 Go 和前端检查；Maven 通过不能代替其他技术栈的验证。
- 运行验证使用明确标记的测试记录；不清理现有验收数据，不因同名合并 Vault，不在本轮调用真实 Agent 执行新任务。
- 交付记录区分“代码修改完成”“测试通过”“构建完成”“服务已更新”“原始场景验收通过”；没有证据不跳级。

## 当前待确认项与完成状态

A03 使用可恢复的 deleted 状态并保留执行诊断。B03 最初擅自采用管理员专属高级入口，用户指出入口丢失后已纠正：恢复原有 configure 权限入口及直接可见的环境选择。C07 当前实现仍为自动默认和高级配置，不能将其视为用户已认可的入口方向；已有绑定继续保留。

这两项不阻塞 C01、C02、A01、A05 等明确缺陷的排查。共享 Memory 的只读语义暂按现有实现保留；若后续希望 Agent 写回共享知识，再单独定义访问模式和验收条件。

初始排查已结束。用户随后授权修复全部问题，当前代码与验证进度见下表；尚未提交或部署。


## 实施记录与回归证据

| 编号 | 已落地内容 | 验证 / 剩余工作 |
| --- | --- | --- |
| C01 | Hosted Chat 以所有者创建私有任务；Session 入口检查 Chat 活跃状态 | 原始回归用例先失败再通过；内存及独立 PostgreSQL 测试覆盖私有所有者、身份/作用域和 Hosted 会话任务生成。运行中旧服务需更新后做现场复测 |
| C02 | External 轮次持久化、报告身份与代次检查、完成/错误/取消、断连/超时清扫、前端流式合并与解除 busy | 内存及 PostgreSQL 完成/失败/取消/超时/掉线/派发失败六类终态与重试通过；补充旧 SDK 无序号分片兼容并验证相邻重复文本不丢失。有序号报告去重；无序号报告因缺乏身份无法保证重复投递去重，最终完整输出覆盖流式正文 |
| A01 | Configure 弹窗与工具/MCP 覆盖权限一致；继承说明和 Workspace 链接修复 | 四类权限/继承状态浏览器测试通过；Codex 明确拒绝无法执行的 Managed 内置工具策略，MCP-only 定义不受影响，UI 提示原生工具应在 Runtime Profile 配置 |
| A03 | 右键/更多/键盘删除，Deleted 列表和恢复，运行中拒绝删除，所有权/作用域校验 | 内存、PostgreSQL、右键与键盘浏览器流程通过；未永久清理数据 |
| A05 | 补齐 popover 颜色、窄屏宽度和键盘高亮滚动、aria-activedescendant | Team Picker 窄屏、不透明背景、长列表键盘滚动与选择测试通过 |
| C06 | 移除 Team 顶部 ChannelAssociations | Team 页面移除断言通过，Channel 路由所在 Go 全量测试通过 |
| A04 | MCP 目录先展示，点击后预填编辑表单；保存后加入已配置连接；Builtin 独立区域 | 目录→编辑→保存、HTTP 策略及移除、stdio 字段及保存、窄屏、OAuth 成功/取消/拒绝浏览器测试通过 |
| B01 | 合并 Skills/Marketplace；安装内容、引用、版本和来源一并持久化；同名冲突；缺失 Nacos 内容拒绝占位成功；识别本地修改 | UI 流程及 PostgreSQL 并发冲突、失败回滚、重试和来源测试通过；卸载含 _/% 名称不会删除邻近 Skill 的回归通过；发布/绑定使用已有版本链路 |
| B03 | 移除阻断默认回退的 required；撤销额外的管理员专属限制，恢复开发者 Environment 管理入口；Session 环境选择直接可见，Agent Runtime 提供管理链接 | 两条浏览器回归通过：不手选时创建默认 local；非管理员开发者创建 self_hosted、保存 Agent 默认环境、刷新保持关联并用该环境创建 Session。此轮测试使用模拟 API，不替代真实 Worker 接入验收 |
| C07 | 新 Worker 默认自动；高级选择折叠；旧策略保存并提供明确取消覆盖 | 默认创建不提交运行覆盖、编辑时保留旧策略、明确取消覆盖三种浏览器场景通过 |
| B04 | Vault 显示用户可见的配置引用，名称附资源类别与标识 | 同名 Vault/Agent 按 ID 关联及跳转浏览器测试通过；不宣称配置引用等于实际使用 |
| B05 | Memory 显示引用和共享只读语义，解释按需读取与工作记忆区别 | MemoryMountServiceTest 验证实际绑定、唯一标记读取及更新、写入拒绝、未绑定不访问；ManagedMemoryAccessTest 与 HarnessAgentBuildServiceCacheKeyTest 验证共享只读和会话/尝试隔离 |
| C04 | 批量读取注册实例，按框架及版本聚合徽标，区分离线/未上报，支持搜索 | 聚合单测与实际列表框架版本、离线状态、按框架搜索浏览器测试通过 |
| C03 | Session 返回本次 Attempt 冻结运行信息；External 使用精确实例代次；展示 Provider/Pool、缺失用量和采集时间 | 后端绑定来源/代次及 PostgreSQL presence 字段持久化测试通过；浏览器区分已采集零值与缺失；HTTP 显式 presence 可区分，旧 ASDP 标量 0 无 presence 时保持未知 |
| C08 | 结果、输入、尝试时间线为主，原始绑定折叠；输入版本变更时不冒充原始内容；操作中与错误反馈 | 成功/失败/多次尝试、重试报错及结果附带产物浏览器测试通过；输入原版本、修改后、删除后在内存及 PostgreSQL 回归通过；无 Run 的任务返回执行列表 |
| C05 | Team/API 页面提供认证、curl、请求与响应、轮询、SSE、schema 和错误说明 | 示例生成单测及浏览器复制验证通过；认证、幂等、jobs/conversation、查询和事件路径与 Go Endpoint 回归契约一致 |
| A02 | 清理相关页面碎片说明和内部实现文案 | 已检查本次涉及 Agent、Workspace、Chat、Session、Team、Execution、Environment、Vault、Memory 页面说明/空状态；错误保留原因，移除内部实现历史和碎片版本文案 |
| B02 | Codex 进程级 config_file 注册；Qoder --agents 注入；共享工作目录、字段/版本校验、父级工具边界、冲突/清理 | Provider 单测及 Codex 0.153.4 / Qoder 1.0.37 真实 CLI 委派通过；随机标记只写于指定子 Agent 指令，父 Agent 成功取得；子线程事件不能结束或覆盖父线程结果 |

已执行 Go 全量测试（通过）、前端 TypeScript/Vite 生产构建（通过）、全部 Vitest（141 个通过）、Go 控制面/Runtime Host/CLI 二进制构建（通过）。25 个相关 Playwright 场景均通过（组合回归与修正夹具后的分组重跑）；Java 全量 mvn clean verify 于 2026-09-09 20:08（Asia/Shanghai）通过，88 个模块构建成功；测试报告汇总 8183 个测试、0 失败、0 错误、149 跳过（跳过项不视为已验证）。其中 service-dataplane 73 个、service-scheduler 10 个测试均通过。PostgreSQL 测试使用独立 acceptance 数据库及测试 schema，不修改运行中业务数据。

**交付状态：18 项已完成本轮代码修复与对应自动化回归，全部修改直接位于主目录 `agentscope-service-v5`，无需合回其他目录；尚未提交。Go、前端及 Java 构建完成；未部署或重启现有服务，运行中环境的现场验收仍需更新服务后进行，不能把本轮结果视为现场验收已通过。**

### Hosted Subagents 的当前边界

- Codex 使用 CLI `agents.<name>.config_file` 显式注册 `.agentscope/native/codex/agents/*.toml`，避免无 Git 目录依赖自动发现；不写用户的个人配置。支持名称、描述、指令和模型，权限继承父级；不能等价实现的工具限制、maxIters、隔离目录在执行前拒绝。
- Qoder 使用当前版本公开的 `--agents` 接口传递定义；工具和轮数可映射，保留父级拒绝列表并限制子级不能扩展父级工具暴露。Workspace 必须设为 shared。
- 当前验证版本下限为 Codex 0.153.4、Qoder 1.0.37，代表本次核验契约，不声称这是历史首个支持版本。
- 官方参考：[Codex Subagents](https://learn.chatgpt.com/docs/agent-configuration/subagents)、[Qoder Subagent](https://docs.qoder.com/cli/subagent)。

### 补充验证说明

- 原生 CLI smoke 测试需通过 AGENTSCOPE_SMOKE_CODEX_BINARY / AGENTSCOPE_SMOKE_QODER_BINARY 显式启用；使用临时 Workspace，不创建控制面业务记录。真实运行消耗少量 Provider 请求，普通 go test 默认跳过。
- Java 全量检查中修复了 ToolConfirmationCoordinatorTest 的并发 Mock 竞态：轮询已启动后不再重设同一个 Mock 的返回值，改用 AtomicReference 模拟 lease 替换/丢失。目标测试已通过。
- 浏览器测试使用受控 API 与 OAuth provider fixture；它证明页面流程和契约，不代表运行中的旧服务已经更新，亦不代表真实第三方 OAuth 账户已重新授权。
- 当前构建产物位于 agentscope-service/aistio/bin/、agentscope-service/aistio/ui/ 和各 Maven 模块 target/。没有提交、没有自动重启现有服务。

### 2026-09-09 21:02 External Chat 现场补查（C02）

- Chat：ebf5d664-5280-40c8-92bf-2cf1e42acd50；Session：49cafd1d-3c2a-48f7-a119-ea9dc23a36df。13:02:19 UTC 接收，13:02:23 UTC 完成，仅有 user.message、started、accepted、started、completed 五条事件；完成 payload 为 {"accepted":true}，没有回答正文。
- 根因在真实 Java SDK：AgentScopeAdapter.injectUserMessage 对 Agent.call 的 Mono<Msg> 使用 then() 丢弃结果；SessionBridge.onConversationTurn 又固定回报 accepted=true。此前控制面测试使用带 content 的模拟完成报告，没有覆盖这段真实 SDK 契约，因此上轮 C02 的验证结论不充分。
- 本轮新增 FrameworkAdapter.runConversationTurn 返回终态正文，AgentScopeAdapter 保留 Agent/HarnessAgent 的真实回答，SessionBridge 将正文序列化为 content 回报。空完成或执行失败会回报 failed，不再伪装成功；原有仅注入消息的 API 保留兼容。
- SessionBridgeConversationTest 贯通真实 Bridge/Adapter 与可控 Agent、ASDP transport，验证异步回答、JSON 转义、轮次身份、执行异常及空完成。修复前 3 个测试中 2 个失败，修复后全部通过；相关 Adapter/Bridge 既有回归一并通过。
- 现场 External 实例来自 PID 21109 的 Paw Java 进程，启动于 17:08；当前 Service 控制面于 20:40 重启，因此此次不能归因为 Service 没更新。Paw 进程需加载新 SDK 后再次现场验证，历史缺失正文不会凭空恢复。

- Paw 与依赖模块 package 构建于 21:11 成功，已检查可执行 JAR 内嵌的 SDK 包含 runConversationTurn 修复。代码在主目录 agentscope-service-v5，未提交；尚未重启用户终端中的 Paw 进程。


## 后续修正：删除 Chat 后 Session 历史误报 404

用户提供 Agent 8534c196-9a9f-4d3d-bce0-1e4ae85cc6a0 下的 Session 49cafd1d-3c2a-48f7-a119-ea9dc23a36df。数据库中的 Session 仍存在，tenant/namespace 正确，其关联 Chat ebf5d664-5280-40c8-92bf-2cf1e42acd50 已为 deleted。权限函数 canAccessSession 只遍历 active/archived Chat，漏掉 deleted，造成 Session 列表可见、详情及 events/turns/commands 返回 resource not found in authorized scope。agentId 查询参数不是根因。

已在只读校验中加入 deleted Chat 的创建者关联；写操作不从 deleted Chat 获得授权，原有 namespace 和私有数据边界保持有效。回归先复现同样 404，再验证内存和隔离 PostgreSQL：创建者和 auditor 可读，其他成员、开发者、operator、非 auditor 的 namespace 管理员及外部用户不可读；跨 namespace 不可读，deleted Session 写入不放行。现有 Chat 删除/恢复、私有 Session 权限测试及 httpapi 全量测试通过。

控制面已完成构建，并保持原有配置重启本地 control 进程。真实 18080 原链接通过浏览器复测，Session 页面正常显示，详情、events、turns、commands 接口均返回 200；未恢复或改写用户的 deleted Chat。


## 后续修正：普通 Hosted Task 缺失 Session 投影及工作流错误链接

用户提供的运行时 ID 61e1cdf1-dc37-46e0-a246-ffa934a32b6c 属于 Qoder Attempt 1a2f87ae-4dd1-41b6-9269-027f07a532a0，状态为 waiting，原因为等待 approval:7437ae4b-9a8b-5a25-acb1-03deb764ab3a。该执行没有关联 Chat，也没有 rt.sessions 记录，与上一条删除 Chat 的权限遗漏无关。普通 Hosted Task 的 claim/report 链路此前没有生成 Session 投影；WorkflowExecution 又使用 sessionRef || sessionId 构造链接，把运行时 UUID 当成控制面主键。

已在 Hosted claim 和 Provider 事件接收时补齐 Session 投影；读取历史 Attempt 关联时可补建缺失的投影，并按 Attempt ID 从已有 Run provider events 恢复历史，沿用来源幂等键避免重复事件。保留现有 Chat Session，检测跨 namespace/binding 的身份冲突，Session 访问继续沿用原 AgentTask 所属 Issue 权限。工作流仅用 sessionRef 链接；未生成投影时展示提示与现有 Task 入口。

验证：新增测试先复现缺失 Session，再通过内存及隔离 PostgreSQL 回归，覆盖历史事件恢复、重复上报、Provider 首次上报创建、私有 Issue 访问边界和运行时 UUID 不冒充主键。httpapi/taskplane/runtimebinding 测试通过；浏览器链接回归及控制面、前端构建通过。已更新本地控制面与前端，原 Attempt 的诊断 Session 为 a6eec231-d78b-4bc0-adc2-2ef62be6384d，恢复 23 条 Provider 事件，详情/events/turns/commands 均返回 200。执行仍为 waiting，未替用户批准或重跑任务。


## 后续修正：审批已批准，但 Runtime Host 因控制面重启停止轮询

Run b6d22844-0812-458d-9a08-9701c07dae8e 的两笔审批 e9b22345-bbf5-5daf-a821-0735d67201fb、7437ae4b-9a8b-5a25-acb1-03deb764ab3a 均由 admin 批准，提交分别在 21:40:03、21:40:19 返回 200。第一笔由 Host 查询并 ack 成功。第二笔查询在 21:40:14 的控制面重启窗口收到 Gateway HTTP 500；Qoder 原生日志明确记载 WebSearch 被以 AgentScope approval failed 原因拒绝。该重启由本次此前的 Session 权限修复操作触发。

Client.AwaitToolApproval 对临时网络/服务错误直接退出，Qoder Adapter 又在停止读取 stdout 后无界等待子进程退出，导致子进程和租约心跳仍在，Task/Attempt 一直显示 waiting。Inbox approve 提交成功，不应归因于用户操作。

代码修复：审批创建、查询和 ack 对网络错误、408/429/5xx 在一分钟窗口内退避重试，遵从调用取消；403/409 等权限/执行代次错误直接返回。Qoder 协议读取失败时先结束子进程再 Wait，保留原始错误。测试先复现首次 503 中断及错误后挂起，再验证各阶段断线恢复、连接错误、旧执行拒绝、取消及子进程收尾；runtimehost 和 qoder 包测试通过，Runtime Host 二进制构建成功。

恢复操作已完成：21:57 只结束原挂起 Qoder 子进程，由仍运行的旧 Host 正常上报 provider_failed，原 Task/Attempt/Run 进入 failed，保留原错误及审批记录，未直接改数据库状态。已原子替换 ~/.local/bin/aistio-runtime-host 为主目录构建版本并校验 SHA-256，通过 agentscope runtime restart 启动新 Host（PID 28183、原 Host ID 不变）；旧二进制备份在 ~/.agentscope/runtime-host/state/aistio-runtime-host.before-approval-recovery-20260909。

使用 admin 登录及标准 Task retry API 返回 201，创建 Task 48d4a4e8-4158-465f-ba67-2399cafff8af / Run 0a4e933c-827a-4d98-97cf-e8f12d1677ec，Qoder 已产生模型回复和 task_get 调用。随后 Mac 多次睡眠造成心跳过期及网络中断；22:23:37–22:31:25 的睡眠区间与 Qoder SSE body idle timeout 对应，最终错误事件包含 errors=["fetch failed"]。浏览器页、graph/events 均正常，无 API 错误，但该轮最终失败，不能宣称任务成功。

电脑唤醒后于 22:34 再次使用标准 retry API，创建 Task 811c1920-009c-4317-9dce-06f0be145ea0 / Run e662927e-55ef-468e-a910-828b2ea2b924。新 Attempt 2132a4bd-0629-4a12-8724-e047894315db 已 running，Qoder provider session 为 3be8b52b-65c9-4d74-b39d-6db12c7e4bc0，已有 Provider 事件及正常心跳。重试生成新执行和新 Session，不是原进程原地续跑；新的工具审批需由用户继续处理。本地 Runtime Host 测试需保持电脑唤醒。

22:34:44 最终复核：新执行已到达真正的待审批节点，WebSearch Approval 58866954-3521-5e7f-9c58-3a069aebd585 为 pending；Host 每秒查询 decision 并正常收到 202，graph/events 为 200（24 条事件），Session 投影 f7ca86e5-565d-44a8-980b-d04022206ef8 正常。当前等待用户在 Inbox 批准这笔新审批，未代用户批准。

## Hosted Runtime Configuration：可视化 Host capacity

在 Agent 的 Runtime configuration → Execution 后增加 Host capacity 表单，按已保存的 Runtime Pool 展示各 Host 的状态、当前占用和容量。原 Concurrency 明确标为 Agent concurrency；Host 容量是同机所有 Agent/provider 共用的额度，等待审批也占用。范围 1–50，由 namespace 管理员或 operator 修改，独立保存，不修改 Agent 的模型或执行覆盖配置。

新增 PATCH /api/v1/runtime-hosts/:hostId/capacity，以 expectedCapacity 检测并发修改，沿用实际 Host 的 namespace 运维权限。容量在控制面持久保存，后续 Host 注册保留控制面设置；Host 使用注册及心跳响应里的有效容量控制领取任务，通常 15 秒内生效。调低容量不结束已有执行，等占用低于容量后再领取新任务。

按用户明确约定，当前为迭代开发阶段，不保留旧版本能力探测、兼容分支或升级提示。前端、控制面和 Runtime Host 按当前实现同步交付。新增数据库迁移只为保存共享 Host 配置，不改写现有任务状态。

验证：内存及隔离 PostgreSQL 的容量 API 测试通过，覆盖 namespace 权限、非法数值、并发修改、心跳保持、调低容量保留占用以及重新注册保留设置；httpapi/taskplane/runtimehost/memory/model 包回归通过。浏览器回归覆盖独立保存、刷新读取、输入校验、运维权限和保存冲突，均通过。控制面、Host 和前端构建通过。

本地部署：控制面已更新并健康启动（PID 38472），数据库迁移已执行；18080 的 Qoder Runtime configuration 页面已出现 Host capacity 表单，读到真实 1/1 占用，无页面或 API 错误。Host 构建已安装至 ~/.local/bin/aistio-runtime-host，但当前 Host 进程仍承载用户的 Qoder 审批任务；等待用户决定完成测试后重启还是立即中断并重启。未修改用户 capacity 值，尚不能宣称本机动态容量已生效。

后续部署完成：确认 Qoder Task 811c1920-009c-4317-9dce-06f0be145ea0 已 completed 且 Host 无非终态执行后，使用 agentscope runtime restart 启动当前构建。无需中断用户任务，capacity 动态配置所需的 Host 更新已完成；实际容量仍保留 1。

## Qoder 表单开放显式全权限模式

按用户要求，在 Runtime configuration → Provider settings → Permission mode 中加入 Full access — allow tools without approval（bypass_permissions）。单个 Qoder Agent 可显式保存该模式，后端校验 Qoder 的有效模式值，不要求先修改共享 Runtime Profile。未替用户选中或保存全权限，也未改变默认值。保存后的新执行由现有 Adapter 传入 --permission-mode bypass_permissions，不需要重启 Host。

验证：API 回归覆盖实际保存、读取、Runtime Policy 解析、共享 Profile 保持不变、无编辑权限者拒绝及非法模式；Qoder Adapter 测试确认显式参数，httpapi 和 qoder 包测试通过。浏览器测试覆盖选择、保存、刷新回显及切回 default。前端和控制面构建完成并部署；18080 实际页面确认新选项存在，后端用有意过期的 bindingVersion 验证全权限通过参数校验后以 409 拒绝写入，未修改用户配置。代码在主目录 agentscope-service-v5 分支。
