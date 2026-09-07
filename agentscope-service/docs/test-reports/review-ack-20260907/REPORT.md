# Review 评论误触发重新分解：修复与验证

代码实际位置：`/Users/ken/agentscope-2/agentscope-java`，分支 `agentscope-service-v5`。本次直接在用户主目录修改，无独立 worktree，保留已有改动。Go 后端已构建，部署保留现有数据库；没有修改 Java 或前端源码。

## 原因

原 Issue：<http://127.0.0.1:18080/work/issues/e9039b21-d163-4d61-be6a-199c3ed3ea6f>

21:40:22 的“非常好”创建了 Team Run `dcf877ba-e1c6-4d0f-a1a6-db6f8a0c514e`。原 Run 已结束，ParentTaskID 为空，被识别为初始 Lead，收到分解任务指令。任务启动又把 in_review 提前改为 in_progress。实际 task.get 中 currentRequest、已完成的子 Issue、原结果和人类补充输入都存在，问题主要是回合分类与生命周期管理，并非简单丢失全部上下文。

## 修复

- 根 Issue 在 in_review/done 时的人类评论创建 `review_comment` 回复回合。仍保留 Task、Run、execution 与输入处理记录，但不重开原工作。
- feedback prompt 优先读取当前评论、reviewResults、已有子结果，不走初始 Team 分解逻辑。
- 普通回复通过 task.complete 保存为普通评论，只回到人类；不产生新的交付结果或终态团队总结，不隐式 mention 其他 Agent，不改变原验收状态。
- 反馈回合不能直接创建子 Issue、修改协调节点、通过额外评论调度 Agent。允许直接完成回复，避免原初始 Lead 必须先委派才能结束的限制。
- 当前人类确有新要求时，`task.begin_work` 要求引用本次输入，并校验任务状态、Issue 所有权及活动 Run。Task/Run、Issue 状态、Inbox 和 review.work_requested 事件在同一事务/内存锁中更新。
- 新要求写入当前 Run 输入；后续 Lead 上下文明确以新要求为本轮目标，原需求仅作背景。
- 工具加入 managed agent 的 availableActions；反馈 Run 的后台总结补写也被排除。重试保留 feedback 类型。

没有使用“非常好”等关键词黑名单。语义由 Agent 结合当前评论判断，服务端用反馈回合和显式开始工作动作约束副作用。普通认可不自动代替人的正式验收。

## 验证

- memory 与真实 PostgreSQL：`TestReviewFeedbackPreservesWorkUntilExplicitNewRequest` 通过，覆盖 in_review/done 认可、状态保留、普通评论结果类型、禁止直接委派、禁止从旧需求开始新工作、显式修改要求、上下文传递、反馈执行结束且不生成团队总结。
- MCP 回归：反馈可以直接结束，工作变更和 mention 绕过被拒绝；原有评论新需求上下文测试改为明确开始工作后再断言重开。
- Go 全量测试通过；最后补充的 MCP 路由限制通过 httpapi/collaboration/orchestration 测试并重新构建。
- 独立已交付测试 fixture 用于验证三种认可回复及新增计算。fixture 的既有结果是显式测试预置，不冒充 Agent 真实执行历史；评论之后的 Task/Run/execution/Session 来自实际 managed agent 执行。
- 最终实例审计见 audit-results.json，脱敏 API 证据位于 evidence/。

测试过程中发现并修复了 availableActions 缺失、普通回复被强制改成 result、后台继续生成团队总结的问题。早期测试证据保留，不记作最终通过用例。第一次 fixture 意外附带初始指派，已改成无任务的已交付 fixture，并在提交测试评论前断言无 Task/Run。

本次未重写、删除或恢复用户原 Issue 的既有执行历史。修复用于后续评论。

## 最终集群结果

| 场景 | Issue | 最终状态 | 新增子任务 | Task / execution |
|---|---|---|---|---|
| 非常好 | e389496f-beaa-4121-9225-890293f3a90b | in_review 保持 | 0 | 1 completed / 1 succeeded |
| 显式 @MA1 致谢 | f29cd8eb-3604-4f86-8aa8-bbdb8169ba06 | in_review 保持 | 0 | 1 completed / 1 succeeded |
| 已验收后致谢 | 52bafd3b-7201-4f3c-adb1-35d1a478ffd2 | done 保持 | 0 | 1 completed / 1 succeeded |
| 新增 7×8 | c7fde31d-e553-49d7-9b9d-be06fcdc2fe5 | in_review → in_progress → in_review | 1 | 3 completed / 3 succeeded |

三个认可场景均为单个 review_comment 回复 Run，0 条 agent_tool.failed；回复为普通评论，原交付结果未被替换，子 Issue 集合不变，无新的状态变更事件。追加计算通过 task.begin_work 记录了 review.work_requested；最终交付 56，保留原结果 84，并产生一条人工验收待办。

追加场景有 1 条真实 agent_tool.failed：Lead 在子 Issue 尚未完成时尝试 run.node.complete，被活动子任务校验拦截，随后成功让出回合、等待 Worker 并汇总。该保护事件保留；没有重复失败循环，不能声称该场景是零工具错误。

最终审计检查了每个 Run 的事件序号、Task 和 execution 终态、输入 processed/responseCommentId、Session 消息/事件/turns、原子 Issue 集合、回复类型、总结先于验收状态、以及 Inbox。完整结果见 audit-results.json，所有六个恢复/回复执行均成功。
