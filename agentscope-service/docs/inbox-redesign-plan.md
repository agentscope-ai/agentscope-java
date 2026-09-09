# AgentScope Service Inbox 改造方案

日期：2026-09-07。状态：四项核心改造已实施，验证结果见 实施与验证报告（历史本地验收记录，保存在发布前备份中）。

分析与实施基于 `/Users/ken/agentscope-2/agentscope-java` 主目录、`agentscope-service-v5` 分支的当前工作区，保留已有及并行修改。下文保留原始设计依据；已实现范围、技术取舍、后续增强和实际构建状态以实施报告为准。未部署或重启用户服务。

## 1. 产品定位与确定的方向

Inbox 是当前登录用户的「待处理事项和重要通知」入口。Issue / Run / Task 仍是业务状态的事实来源，Activity / Execution log 保存完整过程，Agent 仍通过 AgentTask 和 task inputs 接收工作。

建议一并完成以下改造：

1. 将 Pending approvals 合并到左侧 Inbox 列表，每个审批只显示一次，并保留显著的待审批标记。
2. 左侧保留消息列表形态，右侧成为所选消息的详情和操作区。
3. 点击并成功展示消息详情后自动已读；已读、归档、业务是否待处理分别管理。
4. 主 Issue blocked、人工验收 in_review、明确发送给用户的消息必须可靠进入 Inbox；过程事件按规则过滤、合并。
5. 统一收件人、去重、计数与生命周期规则，修复执行路径之间的通知差异。

“筛选按钮”按消息类型与阅读/处理状态筛选理解。

## 2. 改造前实现与具体缺口

| 位置 | 当前行为 | 需要解决的问题 |
|---|---|---|
| `frontend/src/features/approvals/ApprovalsPage.tsx` | 左侧 Inbox，右侧独立 Pending approvals；分别每 5 秒查询 | 同一审批已有 Inbox 记录，却作为两份内容展示；右侧不能查看所选消息 |
| 同上 | Open issue 跳转独立页面，Mark read 单独操作 | 查看消息与已读脱节，没有稳定的选中项和详情深链接 |
| `aistio/internal/store/postgres/collaboration_resources.go`，CreateApproval / CreateManagedToolApproval | Approval 与 Inbox 在同一事务创建，Inbox 带 approvalId | 可以直接复用，不需要前端重新伪造另一套审批消息 |
| 同上，DecideApproval | 审批决策后关联消息 read=true、archived=true | 审批处理状态与阅读状态耦合；系统取消/过期不应被解释为用户已阅读 |
| `collaboration_issues.go`，TransitionIssue | 状态变更写 Activity 和 Outbox，没有创建 Inbox | blocked / in_review 没有通用通知保障 |
| `collaboration_completion.go`，requestIssueReviewForCompletedRunTx | 完成执行后可直接更新根 Issue 为 in_review 或 done | 不能只修改普通 TransitionIssue，否则仍漏掉直接完成路径 |
| `internal/collaboration/service.go`，CompleteTask | 部分 Team RequireReview 完成路径通过评论路由生成 review_request | 不是所有 in_review 都有通知；复用既有结果评论等路径也需统一覆盖 |
| `collaboration_comments.go`，CreateComment | human route 生成 mention/review_request；另向全部 human subscribers 生成 issue_update | 同一用户既被 @ 又订阅时可重复；自动 status/progress 也可能刷屏 |
| `collaboration_tasks.go` 与 `collaboration_failure.go` | 普通失败 transition 写 agent_task_failed；带 Attempt 的原子失败路径主要写 Task/Attempt/Outbox | 是否通知依赖失败入口；子任务失败也可能过早打扰用户 |
| `collaboration_resources.go`，ListInbox；前端 listInbox | 服务端默认最多 100 条，前端只取一页后过滤和计数 | 老的未读/待审批可能不在当前页；全局角标与筛选结果不准确 |
| `frontend/src/app/approvalAttention.ts` | 已对 pending approvals 与未读 Inbox 做集合去重 | 方向可复用，但仍依赖两份已加载列表，不能代表服务端全量 |
| `frontend/src/features/issues/IssueDetailPage.tsx` | 直接依赖 useParams、navigate，内部还带 Properties 侧栏 | 直接嵌入会出现路由耦合、跳离 Inbox 和过窄的三栏布局 |

另外两处应在实现中同步处理：

- Task 的 accountableHumanRef 在没有父 Task 的人类评论触发分支存在未赋值风险，需要按触发用户/工作负责人补全；SLA、Outbox dead-letter 的硬编码 `admin` 收件人也需要改为可解析的真实负责人。
- memory 与 PostgreSQL 的 `issue.status-changed.v1` payload 当前形状不完全一致；统一事件契约，避免未来消费者对不同存储产生不同结果。

本节是方案阶段的静态检查记录，后续测试结果见实施报告。

## 3. 页面与交互

### 3.1 结构

页面和导航统一命名为 **Inbox**。建议新主路由 `/work/inbox`，旧 `/work/approvals`、`/control/approvals` 保留重定向及已有 scope 查询参数。

```text
Inbox                              待处理 4 · 未读 8
┌──────────────────────────┬──────────────────────────────────────────┐
│ 待关注 / 未读 / 全部      │ 所选消息的详情                          │
│ 筛选 [类型、状态]        │                                          │
├──────────────────────────┤ 审批：请求原因、操作对象、参数预览、    │
│ 🛡 工具执行请求   待审批  │       关联 Issue/Task、时效、审批意见、  │
│ ! 主 Issue       已阻塞  │       Approve / Reject                  │
│ ○ 主 Issue       待验收  │                                          │
│ @ Agent 给我的回复       │ Issue：完整内容、讨论、执行与验收操作   │
│   已订阅 Issue 的更新    │                                          │
│                          │ 普通消息：正文、来源、时间、关联对象    │
└──────────────────────────┴──────────────────────────────────────────┘
```

- 左侧建议宽 360–420px，延续现有标题、摘要、发送者、相对时间和未读点；右侧占剩余宽度，两边独立滚动。
- 行整体可点击并显示选中态。Pending approvals 使用盾牌图标、琥珀色和明确的“待审批”文本，不仅靠颜色区分。
- 默认“待关注”展示未读通知与仍需当前用户处理的事项的并集。快捷切换“未读”“全部”；筛选按钮提供审批、待验收、阻塞、@/回复、普通更新、运维告警，以及已归档入口。
- 待关注视图中待处理事项排前，同组按最近事件时间倒序；不在用户阅读时自动重排当前选中行或自动切换详情。
- 窄屏先展示列表，选中后切换详情，返回时恢复筛选、选择和滚动位置。
- 没有选中项时显示操作提示，不默认打开首条造成自动已读。

### 3.2 详情分派与复用

分派顺序：`approvalId → ApprovalDetail`，否则 `issueId → IssueDetailContent`，否则 `MessageDetail`。审批同时关联 Issue 时优先打开审批。

- **Issue**：从当前 IssueDetailPage 提取可接收 issueId 的内容组件，独立 Issue 页面和 Inbox 共用。保留讨论、回复、附件、执行记录、状态流转及验收能力。通过 commentId 定位并高亮触发通知的评论；需要时加载其线程，不假定评论在首屏列表中。
- **嵌入布局**：默认收起 Issue Properties，按详情容器宽度使用抽屉/折叠面板；返回、归档成功、父子 Issue 导航都通过宿主回调处理，避免无意离开 Inbox。提供“独立打开”作为显式操作。
- **Approval**：提取现有审批展示和操作代码，增加按 ID 获取详情。展示原因、具体动作、脱敏输入、请求人、失效时间、关联实体及决策记录；批准和拒绝继续使用版本校验和现有权限、Attempt/turn 绑定校验。
- **其他消息**：展示可读正文及相关 Issue/Run/Task 链接。无关联 Issue 的运维消息也有可用详情，不出现空白页。
- 保留原始通知摘要与事件时间，同时展示实体最新状态。旧通知不应把已恢复的 Issue 显示成仍阻塞。
- URL 保存 `item=<inboxId>` 和筛选条件；刷新、浏览器前进后退和跨页链接可恢复详情。Work overview 中的通知统一链接到该项。
- 切换项时隔离异步请求及草稿，审批意见按 approvalId 保存；不得把上一项内容、操作对象或迟到响应套到新项上。

### 3.3 自动已读与处理状态

| 状态维度 | 含义 | 由谁改变 |
|---|---|---|
| read / readAt | 用户已查看这条消息 | 消息详情成功显示后调用幂等 read API |
| archived / archivedAt | 是否退出当前收件列表 | 用户归档普通消息，或业务结束后自动归档过期行动项 |
| actionKind / actionState | 是否仍有当前用户需要处理的业务 | 根据 Approval、Issue 或关联处置事实更新 |

- 选中项所需的详情成功渲染且仍是当前项，再提交已读；预加载、仅拉列表、鼠标悬停和请求失败不算阅读。
- 更新本地消息与 summary 缓存，让未读点即时消失；API 失败恢复缓存并提供轻量重试提示。自动已读只针对所选 inboxId，不把同 Issue 其他消息一起读掉。
- “未读”视图下，已读的当前选中项暂留到切换/离开，防止列表移除导致右侧内容消失或跳到下一条。
- 已读待审批仍显示“待审批”，仍计入待处理；点击已读不批准、不拒绝、不触发 Agent 执行。
- 主 Issue 离开 blocked/in_review，结束对应的本轮待处理事项；审批已决定、取消、失效，结束审批待处理事项。自动结束不伪造 read=true。
- 待处理项不能通过归档绕过业务决策，后端同样约束。业务结束的原项可以归档，但当前详情保留结果供用户确认，历史可按 ID 或已归档筛选查看。
- 全部已读只影响阅读状态，不减少仍未解决的待处理数量。

## 4. Inbox 事件矩阵

主工作 Issue 定义为面向用户的根工作项：通常为 `kind=user_work`、`visibility=work_hub`、无 parentIssueId；对历史空值使用现有领域默认规则。不能只依据 parentIssueId 判定，否则 Endpoint/会话产生的根执行记录也会进入工作 Inbox。

| 业务事件 | 是否进入 Inbox | 收件人及处理语义 |
|---|---|---|
| Approval pending，包括普通审批及 managed tool approval | **必须** | 指定 approver；一个审批一项，直到决策/失效前持续待处理 |
| 主工作 Issue 进入 blocked | **必须** | 工作负责人；说明原因、影响和下一步，持续待处理直到恢复/终结 |
| 主工作 Issue 进入 in_review，确需人工验收 | **必须** | 应验收的负责人；打开 Issue 执行接受/要求修改，持续待处理直到离开该状态 |
| 子 Issue blocked / in_review | **默认不发给人** | 先交 Team leader 处理；明确请求人处理、指定 human assignee/reviewer 或汇总导致主 Issue 阻塞时才进入人的 Inbox |
| Agent 或人显式结构化 @human | **必须** | 精确的被提及账户；正文、来源评论、关联 Issue 可追溯 |
| Agent 对用户问题的直接答复；回复用户参与的目标线程 | **应进入** | 提问人/被回复用户；沿已有 human route 定向通知，和 mention 去重 |
| Agent 明确请求用户补充资料、选择方案或介入 | **必须** | 指定 human；有问题正文和可执行的回复入口。只有有明确业务条件的请求才标为待处理，普通通知不一律变成待办 |
| 主工作 Issue 自动完成 done | **应进入** | 工作负责人和显式订阅者，作为结果通知；用户刚刚亲自验收成功时不再向其发送重复通知 |
| 主工作 Issue 被他人取消、重新打开、分配给用户 | **应进入** | 受影响负责人；对订阅者按重要变更投递。自己执行的普通变更不再提醒自己 |
| 已订阅 Issue 的普通讨论 | **仅发订阅者** | 低优先级更新；与同条评论的 mention/reply/review 合并，过滤自动 progress/status/system 评论 |
| SLA 超时 | **有责任对象时进入** | 主工作负责人；子任务先由 leader 协调，按本次截止时间去重；恢复/修改截止时间时结束旧告警 |
| AgentTask/Attempt 失败、工具失败、重试 | **不逐次通知** | 保留执行记录；自动恢复期间不发。最终耗尽并需要人介入时汇总为主 Issue blocked 或一条明确的执行异常 |
| routing_blocked / input dead-letter / outbox dead-letter | **需人处理时进入** | 路由/执行负责人或配置的运维账户；同一根因已形成主 Issue blocked 时合并上下文，不连续叠加数条告警 |
| started/running、token 流、普通进度、心跳、Agent 间 mention、leader 内部评审 | **不进入** | 留在 Activity、Run events 或 AgentTask 队列 |
| Endpoint job / automation job / conversation turn 正常完成 | **默认不进入** | 返回调用方/会话或显示在执行页；明确的人类通知与审批不受此限制 |
| Endpoint/自动化最终失败 | **按责任账户升级** | 有明确需要人处理的失败才投递给配置的负责人/运维账户；不向所有用户广播 |

补充约束：

- `in_review` 是 Issue 验收状态，不等于 Approval；它显示“待验收”，打开 Issue，而不是制造一个额外审批。
- Review 通知以实际状态/业务决策为依据，不依赖 LLM 是否写出某句提示或是否再次发布结果评论。
- 仅有 waiting 不说明要通知用户，可能是在等 sibling、重试或信号。必须有审批、明确的人类请求或最终无法推进的原因。
- 子 Issue 显式发给某用户的消息仍需保留。与后续主 Issue blocked 属于同一次升级时，可合并在主项中并保留原评论入口，不能丢掉具体问题。
- Inbox 只记录和引导动作。继续执行仍走现有评论路由、Issue transition 或 Approval decision 链路，防止阅读消息导致任务自动重启。

## 5. 收件人、去重与一致性

### 5.1 收件人

- Approval 使用 approverRef；mention/reply 使用结构化目标或被回复用户，不从自然语言正文猜用户名。
- Issue 行动项优先使用明确的人类处理对象；缺省使用人类 assignee，再使用对应根执行的 accountableHumanRef，再回退到根 Issue 的 human creator。
- 根执行的责任账户必须从用户触发或配置负责人可靠建立，并沿子 Task 继承；不能取任意最近子 Task 的操作者作为工作负责人。
- 订阅者收到重要状态的知会副本，只有实际处理人副本具有 action required；同一用户同时有多种身份时只写一项。
- 无有效工作负责人时使用 namespace/自动化配置的运维责任人；未配置时保留可观测的“无法投递”诊断，不发送给不存在的硬编码 `admin`，也不全租户广播。
- human 收件目标需解析为真实账户并检查 scope/可见性，避免当前仅接受字符串引用造成的误投与静默漏收。
- Inbox 的读取、已读、归档、详情与 summary 均绑定登录用户及 tenant/namespace；打开消息不能额外授予关联实体权限。权限变化时返回不可用状态，不泄露详情。

### 5.2 去重与恢复

- 通知键至少涵盖 tenant、namespace、recipient、业务事件身份。源事件重放不能新增消息，不能把用户已读的旧项重置未读。
- Approval 按 approvalId + recipient 唯一；评论按 commentId + recipient 合并，优先保留明确行动请求、mention/reply，再到普通 subscriber update，并记录多种触发原因。
- Issue blocked / review 按“本轮状态进入事件”生成；可以用进入状态时的版本作为轮次标识。普通评论导致版本上涨不创建新行动项；离开后再次进入，才是新的未读事项。
- 同轮多个子任务失败合并到主工作项的原因摘要；不同业务事件不能仅按 issueId 永久去重，避免后续真正的阻塞丢失。
- 同一次完成产生的 result 与 review_request 对同一用户合并为待验收项。普通直接问答回复仍单独保留，不能误吞后续用户问题的答案。
- 审批拒绝可能使执行失败并最终令主 Issue blocked：审批项结束，主 Issue 是否新增阻塞项由实际收敛状态决定，不凭前端按钮直接生成。

### 5.3 落地方式

建议抽出共用的 `InboxPolicy` 和收件人解析器，集中定义输入事件、通知对象、合并键和结束条件。PostgreSQL 负责事务内写入，memory 在同一锁内执行等价更新；保持两种存储的领域语义一致。

- 复用现有 Approval + Inbox 的原子写入，将评论、Issue 状态变化与其通知更新也放入同一事务。
- 普通 TransitionIssue、完成 Run 时直接更新 Issue、重新启动 Task 时直接推进 Issue、assignment 和人工介入路径都接同一规则；覆盖直接 SQL 状态更新入口。
- Task/Attempt 失败不在底层无差别写消息，在现有重试/leader/主 Issue 收敛确定是否需人工介入后，调用统一规则。没有主工作 Issue 的运维异常使用同一政策生成独立通知。
- 复用持久 Outbox 做提交后的实时刷新，增加收件人定向的 Inbox 创建/已读/归档/行动状态变更通知。列表、summary 和关联实体均以 REST/存储状态为准；断线轮询兜底。
- 此阶段不引入另一套事件基础设施，也不同时用直接写入和第二个事件消费者重复生产相同 Inbox。
- 状态事件统一包含实体 ID、scope、previousStatus、新状态、状态进入版本、actor、reason 与可用的因果标识；单元测试与存储契约测试固定格式。

## 6. 数据与 API 改造范围

保留 inbox_items 及现有 read/archive 接口，增量扩展，不重建审批或 Issue 状态机。

**必要数据：**

- 前端补齐后端已有的 details；统一事件类型与 severity 枚举。
- 新增或规范 actionKind、actionState、actionRef、sourceEventKey/状态轮次、readAt、archivedAt、resolvedAt。核心筛选字段使用明确列与索引，详情快照使用 details。
- 返回与列表关联的 approval 当前状态、必要的 Issue 当前状态，以及服务端计算的 needsAction；列表不逐行发请求，也不返回整份大对象。
- actionState 是可修复的 Inbox 投影，操作前仍校验领域实体最新状态；增加扫描补偿，防止已结束事项永远留在待处理。
- 普通 @ 和回复先采用现有评论路由；首期不新增任意写 Inbox 的通用 Agent 工具。明确人工请求可增量扩展结构化通知意图及关联回复/Issue 条件，不能根据正文猜测“已解决”。

**建议 API：**

| API | 用途 |
|---|---|
| `GET /api/v1/inbox` | 增加 view、type、actionState、read、archived 和分页参数；在服务端完整集合上筛选，再排序分页 |
| `GET /api/v1/inbox/summary` | 返回 unread、actionRequired、pendingApprovals、attentionTotal 和分类计数；不受当前列表分页影响 |
| `GET /api/v1/inbox/{id}` | 支持选中项、深链接、已归档项的授权读取 |
| `POST /api/v1/inbox/{id}/read` | 保持幂等，支持精准更新消息和摘要缓存 |
| 现有 archive 接口 | 约束仍需操作的事项；按需要补 unarchive，保持阅读状态独立 |
| 现有 `GET /api/v1/approvals/{id}` | 前端补 getApproval 调用，复用授权及完整状态返回 |

建议使用稳定游标（排序时间 + id，待关注视图含优先分组键）；保留旧分页兼容。summary 与列表采用同一收件人、去重和 archive 规则。

计数定义：`attentionTotal = 非归档未读项 ∪ 当前用户仍待处理项` 的去重数量，`pendingApprovals` 是待处理子集。界面分别显示“未读”“待处理”，导航角标使用 attentionTotal，不能把二者直接相加。

## 7. 实施顺序与迁移

1. **规则与后端闭环**：固定事件类型、收件人和独立状态；补 Issue blocked/review 及负责人；统一所有失败入口的升级规则、评论去重和审批结束语义；增加共享存储契约测试。
2. **查询与数据迁移**：增量字段/索引、服务端筛选/分页/summary/详情，回填当前有效行动项。仍 pending 的审批即使旧消息被手工归档，也须恢复可见；历史重复审批/评论合并保留阅读信息。对已有 blocked/in_review 只补当前一轮，避免重放全部历史刷屏。
3. **前端两栏改造**：InboxPage、InboxList、InboxFilters、InboxDetailPane，抽取 IssueDetailContent 和 ApprovalDetail；接入自动已读、URL 选择、稳定选中态和错误处理。
4. **全局联动与验证**：更新导航、Command palette、Work overview、summary 缓存与事件刷新，兼容旧链接；验证审批后实际执行恢复/失败和 Issue 验收/重新规划链路。

历史补偿必须可重复执行，保留既有 read/archived 事实，仅对仍有效的行动项恢复可见；不改审批决定，不替用户推进 Issue。新事件轮次与回填项应能合并，避免上线并发时一项出现两次。

后续可独立增加搜索、批量已读、可配置订阅等级、暂停提醒。首期不依赖这些功能才能保证四项核心需求成立。

## 8. 验收标准

1. 同一审批仅一行，有明确“待审批”标识；已读后仍在待关注中，仍计入待处理。
2. 点击 Issue 类消息在右侧显示完整 Issue；commentId 可定位线程，评论、状态流转、验收、附件和执行入口可用。
3. 点击审批进入右侧审批页；批准/拒绝成功后显示最终结果，重复提交与版本冲突正确处理；过期/旧 Attempt 请求不可继续执行。
4. 详情成功显示自动已读；失败不已读，快速切换不误读，未读筛选下当前内容不消失；列表、导航、概览计数一致。
5. 主 Issue 通过人工接口、Agent 完成、结果复用、Run 失败等不同路径进入 blocked/in_review，都有且只有一项对应通知。
6. 子 Agent 失败后重试成功或 leader 自行处理，不刷用户 Inbox；最终主工作被阻塞或明确请求用户时，可靠收到原因与处理入口。
7. @human、回复与订阅针对同条评论去重；Agent 的主动通知精确到用户；原有 AgentTask 唤醒路径不被改变。
8. 同一事件重复投递不重复创建、不重置已读；解除阻塞后重新阻塞、退回修改后再次待验收会产生新一轮通知。
9. 超过 100 条通知仍能找到较早的未读/待审批；跨页计数正确，深链接与旧路由可用。
10. 自动 Endpoint/会话/自动化正常完成不刷屏；审批与明确的人类升级仍可收到。
11. memory / PostgreSQL 对事件、接收人、幂等、阅读和结束状态通过同一组契约测试；用户与 scope 隔离、无负责人、权限变化、并发读/决策有覆盖。
12. 后续实施时运行前端 `npm test`、`npm run build`，Go 相关包测试及实际 PostgreSQL 集成测试，并完成桌面/窄屏的页面验证。正式提交按仓库要求补齐适用的完整构建检查。

## 9. 主要代码入口

以下路径相对 `agentscope-service/`：

- 前端：`frontend/src/features/approvals/ApprovalsPage.tsx`、`frontend/src/features/issues/IssueDetailPage.tsx`、`frontend/src/api/collaboration.ts`。
- 入口与计数：`frontend/src/main.tsx`、`frontend/src/app/AppShell.tsx`、`frontend/src/app/CommandPalette.tsx`、`frontend/src/app/approvalAttention.ts`、`frontend/src/app/useCollaborationEvents.ts`、`frontend/src/features/work/WorkOverviewPage.tsx`。
- 领域与 API：`aistio/internal/controlplane/model/collaboration.go`、`aistio/internal/store/collaboration.go`、`aistio/internal/httpapi/collaboration_handler.go`、`aistio/internal/collaboration/service.go`。
- 持久化：`aistio/internal/store/postgres/collaboration_{issues,comments,completion,tasks,failure,resources}.go`、`aistio/internal/store/postgres/control_outbox.go`、对应 memory 实现及 migrations。
- 执行联动：`aistio/internal/orchestration/engine.go`、`aistio/internal/controller/control_outbox_dispatcher.go`、`aistio/internal/controller/runtime_control_sweeper.go`。
- 验证：`aistio/internal/store/storetest/`、collaboration/httpapi/controller 现有测试与前端新增交互测试。
