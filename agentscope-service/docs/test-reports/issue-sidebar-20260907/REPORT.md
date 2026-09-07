# Issue 详情页右侧能力：Multica 对照、修复与实测

本次已完成右侧属性与顶部相关处理入口的分析、缺口修复和本地实际操作验证。代码直接位于 `/Users/ken/agentscope-2/agentscope-java`，分支为 `agentscope-service-v5`；没有创建 worktree，也没有自行提交或调整现有暂存区。

用户指定 Issue：`5e660a82-72da-4a31-8fed-bb7c037fc006`。开始检查时实际状态已为 Done、负责人为 team1、优先级为 Normal。测试使用另外创建的 Issue；原 Issue 的标题、描述、状态、优先级、负责人、验收条件、截止日期和版本均做了前后核对，保持一致。

| 能力 | 用途和 Multica 参考 | 原有情况 | 本次结果与验证 |
| --- | --- | --- | --- |
| Status | 表示整项工作的生命周期，和一次 Agent 执行的状态分别管理。Multica 允许直接编辑工作状态；本项目保留后端状态机及验收约束。 | 基本流转和验收守卫存在；错误显示为条目 UUID，评论后快速切换状态可能版本冲突。 | 实测 In progress → In review → Done、Done → In progress，以及 In review → In progress。未满足验收项会拦截；错误改为具体条目名称。评论保存后同步刷新 Issue 版本，回归连续操作通过。 |
| Assignee | Human 负责跟进；Agent / Team 启动执行。Multica 用可搜索选择器，团队交给 leader。 | Agent 可选择；Team / Human 需手填 reference；编辑时不回填当前值；不能取消分配。 | Team 改为按名称搜索选择；当前值回填；支持 Unassigned、人工用户名、Assign to me。实测取消分配及恢复 admin。实际分配 MA1 和 team1，均产生执行并返回预期结果。 |
| 分配执行边界 | 指派是执行入口，不应在已关闭记录上意外再启动工作。 | Done / Cancelled 仍可直接通过 assign 创建任务。 | 前后端禁止向 Done / Cancelled 分配 Agent / Team，需先按现有状态机重新打开；归档 Issue 禁止重新分配。取消分配、改为人工负责人不会取消已有执行。 |
| Priority | 表示处理顺序；不是 SLA 保证。 | 后端默认值 `none` 未出现在下拉框中，被浏览器误显示为 Low。 | 增加 No priority，实测 High、No priority，刷新后保持正确。 |
| Due date | 工作计划中的截止时刻；本项目使用本地日期时间输入，存储带时区时间。 | 可以设置但不能清除；后端忽略 `dueAt: null`；输入空间拥挤；其它属性刷新可能覆盖日期草稿。 | 增加 Save / Clear；PATCH 区分“未传”和“显式 null”；实测设置、清除、刷新持久化。单测覆盖时区往返、缺省字段保留与非法日期拒绝。草稿不被其它属性的刷新覆盖。 |
| Acceptance | 本项目在 Multica 工作验收概念上加入结构化条件，约束转为 Done。 | 只能新增、勾选；不能编辑、删除；高级条件只能阅读。 | 支持条目新增、修改、删除、勾选，以及结果评论、最低附件数、最低审批数设置。保留扩展字段并拒绝错误结构和负数。实测清单拦截、缺附件拦截、缺审批拦截与条件满足后验收；取消负责人也不能绕过显式结果要求。 |
| 人工 Result | 人工负责的 Issue 可以提交明确结果以满足验收。 | 评论区只能发普通评论，无法在页面满足 requiredResult。 | 增加 Comment / Result 类型选择；实测人工结果进入 Activity，并可通过结果验收条件。 |
| Source | 展示工作从哪里进入系统；Multica 可展示关联 PR，并由真实集成维护关系。 | 无关联来源时为空；来源引用只显示文本。 | 改进本地创建的空态说明；有效 HTTP(S) 来源提供 Open source，拒绝脚本 URL、带凭据 URL。当前 Issue 没有外部来源：本次验证了空态和链接解析逻辑，未宣称真实 GitHub / PR 双向同步已验证。 |
| Executions | 查看历次执行、步骤及运行诊断。执行 succeeded 并不意味着 Issue 已人工验收。 | 已有真实运行记录和跳转；partial_succeeded 使用绿色容易误解为完全成功。 | partial_succeeded 改为提示色。实测指派后出现执行卡片，点击进入真实执行详情，查看 Agent / Team 步骤及结果；MA1 与 team1 执行均 succeeded，工作先停留 In review。 |
| Artifacts | 留存和交付文件，也可作为验收依据。 | 页面可上传，但右栏只是文件名，无法下载。 | 补齐经过鉴权的二进制下载；保留文件类型和字节。页面实际上传、下载 73 字节 UTF-8 文本，和原文件 SHA-256 完全一致。支持再次选择同一文件。 |
| Subscribe | 决定自己是否接收 Issue 更新；不是执行控制。 | 基本订阅有效，但加载未完成时按“未订阅”展示，错误查询被当作空列表。 | 订阅状态未知时禁用按钮并显示加载占位，失败可重试。实测 0 Subscribe → 1 Subscribed → 0 Subscribe；订阅关系正确保存。通知分发契约由既有 Inbox 测试覆盖。 |
| Export | 导出完整可追溯工作记录；附件以元数据形式列出，不是文件 ZIP 包。 | 原接口正常；导出错误未纳入页面错误反馈。 | 实际点击生成本地 JSON，核对 schemaVersion=2，包含 issue/comments/tasks/children/artifacts/subscribers/activity/runs/runDiagnostics；补齐 pending 与失败反馈。 |
| Archive | 把已结束工作移入归档视图，保留记录。 | 原有实现可用。 | 实际归档验证 Issue，页面返回列表，Archived 视图能找到它。保留现有归档语义，没有增加永久删除入口。 |
| Properties / Details | 属性区提供可操作字段；Details 提供创建人、时间、完成策略、ID 与父 Issue 入口。 | Properties 画了箭头但不能折叠；全侧栏和执行/附件区的折叠已存在。 | Properties 变成真实可访问折叠按钮；实测折叠和展开。调整负责人下拉列表向左展开，避免右侧边缘裁切；原 Issue 的 Details 保持真实数据。 |

本次采用 Multica 的职责划分与选择器交互，没有照搬其所有能力。Multica 的 Project、Labels、自定义属性、Stage、Quick Actions 和外部 PR 集成需要本项目相应的产品数据模型和服务支持，不应在右侧加空按钮。另一个明确差异是：本项目当前 Agent / Team 分配会直接创建执行（包括 Backlog 上的分配），页面现已说明这一行为；没有暗中改成 Multica 的 Backlog 暂缓调度语义。

Status 中“重新打开/退回处理中”表示工作状态调整，不保证自动产生一次新执行；执行启动、取消和重试由各自执行入口负责。取消分配也不会终止正在运行的任务。验收清单的勾选只更新条件，仍需通过状态操作验收。

参考代码和文档：

- [Multica Issues 文档](/Users/ken/agentscope-2/multica/apps/docs/content/docs/issues.zh.mdx)
- [Multica 详情页侧栏](/Users/ken/agentscope-2/multica/packages/views/issues/components/issue-detail.tsx:1972)
- [Multica 订阅状态处理](/Users/ken/agentscope-2/multica/packages/views/issues/hooks/use-issue-subscribers.ts)
- [本项目详情页实现](/Users/ken/agentscope-2/agentscope-java/agentscope-service/frontend/src/features/issues/IssueDetailPage.tsx)
- [验收编辑器](/Users/ken/agentscope-2/agentscope-java/agentscope-service/frontend/src/features/issues/IssueAcceptanceEditor.tsx)
- [服务端属性校验](/Users/ken/agentscope-2/agentscope-java/agentscope-service/aistio/internal/httpapi/issue_properties.go)

验证记录：

| 验证方式 | 结果 / 证据 |
| --- | --- |
| 前端 TypeScript + Vite build | 通过；构建产物已写入 aistio/ui，本地 18080 已加载。见 frontend-build.log。 |
| 前端 Vitest | 23 个测试文件、98 项测试通过；包括日期往返、旧数据兼容、安全来源 URL、二进制下载、取消指派、结果评论。见 frontend-tests.log。 |
| 修改文件 ESLint | 通过，frontend-lint.log 无错误。 |
| Go 构建 | aistiod 构建通过。见 backend-build.log。 |
| Go 全量测试 | `go test ./...` 通过。见 go-all.log。 |
| 真实 PostgreSQL 存储契约 | 在单独测试数据库运行 TestPostgresStore，通过，包括新的 IssueProperties。见 postgres-tests.log。没有在工作数据库跑存储测试套件。 |
| 真实浏览器操作 | 使用当前本地服务和真实 HTTP 接口，未用 mock 代替页面验证。浏览器中完成属性、验收、分配、上传、下载、导出、归档流程。 |
| 真实 Agent | Issue `54d594eb-2f75-4508-be1e-35064fcb22ab`，Run `a23f90c3-ddcb-4850-91e2-5a9549310995`，返回 SIDEBAR_AGENT_OK。见 evidence/agent-current.json。 |
| 真实 Team | Issue `01c41567-ef1c-4786-8325-a2d70eae1823`，Run `aec06dcf-8355-4932-b2c8-6a04cbcfb690`，返回 SIDEBAR_TEAM_OK，包含一个团队子 Issue。见 evidence/team-current.json。 |
| 附件与导出 | evidence/download-verification.json 与 evidence/browser-export.json。附件 SHA-256：`5d26bf7e95b7f4d02cf368696cdfe7d290658e067817d06410d295824afa92ee`。 |
| 验收边界 | 最低附件/审批数量不满足返回 400；已关闭 Issue 再指派 Agent 返回 409。见 evidence/acceptance-boundary-checks.json。 |
| 原 Issue 保持不变 | evidence/original-issue.json 与 evidence/original-issue-after.json。 |

测试主 Issue 为 `638f9ec3-1d75-45b3-a4f0-016c2d0be42e`。本次创建的人工验证 Issue、Agent 验证 Issue、Team 验证 Issue 及其团队子 Issue 共 4 条，验证后均已结束并归档，保留可审计证据。

本地部署使用主目录现有的 aistiod 启动参数和环境，只重启本任务涉及的 Go 控制面，没有重置业务数据库。验证期间另一个集成测试任务也重启过整套本地服务；相关短暂连接失败已经在服务恢复后重测。浏览器原会话在前端重新构建后遇到一次旧动态资源 404，刷新后路由跳转正常。附件与导出均核验了实际下载文件；内置浏览器对同一页面多次下载的捕获不稳定，导出另开页面验证成功。

本次没有修改 Java 模块，也没有运行全仓 Maven 构建或外部部署。保留了用户和其它任务已有的代码、暂存及运行数据；构建中顺带修复了已有 MCP 改动中 `mentionsArg` / `mentionArgs` 的拼写不一致，使编译可通过。

![原 Issue 的最终页面](/Users/ken/agentscope-2/agentscope-java/agentscope-service/docs/test-reports/issue-sidebar-20260907/evidence/sidebar-final.png)

![负责人选择器](/Users/ken/agentscope-2/agentscope-java/agentscope-service/docs/test-reports/issue-sidebar-20260907/evidence/sidebar-picker.png)
