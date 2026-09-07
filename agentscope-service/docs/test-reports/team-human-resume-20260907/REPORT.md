# Team 子 Issue 经人类回复后的恢复与 Lead 等待修复

代码目录：`/Users/ken/agentscope-2/agentscope-java`，分支：`agentscope-service-v5`。直接修改用户使用的主目录，未创建 worktree，未提交或覆盖其他已有改动。后端 Go 构建已完成，使用 `BUILDER_RESET_DB=0` 部署，保留业务数据。本轮没有修改 Java 或前端源码。

## 原始问题与修复

主 Issue：<http://127.0.0.1:18080/work/issues/5e660a82-72da-4a31-8fed-bb7c037fc006>

原 Team Run `900e100e-1206-4acf-8fa8-9777af13a182` 因缺少联网工具凭据失败。管理员在两个子 Issue 中同意使用已有知识后，Worker 都完成了任务，但新任务失去 Team/父任务关联，结果落入两个独立 Run，因此没有继续唤醒原始主 Issue 的 Lead。内部子 Issue 的 Inbox 过滤又跳过了人工验收通知。

修复后，人类回复（含普通回复和明确 mention）会恢复既有 Team 委派关系，多个子任务共用一个以原主 Issue 为根的 continuation Run。原失败 Run 保持失败历史，不被改写。Lead 上下文包含人类修改后的要求，以及已经验收的兄弟子任务结果；Worker 指令要求提交实际交付内容。需人工验收的用户子 Issue 会创建 Inbox 待办。

用户报告的恢复 Session：<http://127.0.0.1:18080/work/sessions/0818d54a-08ff-430f-9e98-a5bb5036fc52>

该 Session 首次验收一个子 Issue 后，另一个结果已经排队。整体完成校验正确拒绝提前结束，但 waiting 校验只识别本回合直接委派的任务，未识别共享协调节点中的兄弟结果回合，造成反复报错。现在 waiting 会识别已决定当前子 Issue 后的兄弟工作及其排队结果；未验收当前结果时仍需先作出决定，避免跳过验收。

缺少人类输入时的 waiting 拒绝信息还补充了可执行的工具动作：明确的人类 mention 后结束决策回合，或用 run.node.fail 发布整体阻塞总结；普通文本不会自行变更 Issue 状态。

## 原始实例恢复结果

对旧的两个已完成 Worker 任务执行了窄范围恢复工具，仅补结果回流，没有重新运行 Worker 或伪造其历史。首次恢复中发现 waiting 校验问题后，对已经验收当前子 Issue 的 Lead 回合执行一次受校验的让出操作，排队的另一个 Lead 回合随后实际运行并完成总结。

| 对象 | 核验结果 |
|---|---|
| 主 Issue `5e660a82-72da-4a31-8fed-bb7c037fc006` | 已发布综合结果并进入 `in_review`；管理员于 21:27:21 验收后当前为 `done` |
| 子 Issue `87938322-9bfd-4785-b140-ba60fc74a179` | `done` |
| 子 Issue `aad6727d-ba57-4709-b83c-a375762c3cf4` | `done` |
| 恢复 Run `3258d720-64a6-4502-9bcb-278cff822f73` | `succeeded`，根仍为原主 Issue |
| 恢复 Lead AgentTasks | 2 个 `completed` |
| 恢复 execution attempts | 2 个 `succeeded` |
| 输入、结果关联 | 全部处理完成，有 responseCommentId |
| 总结与状态顺序 | 先发布主 Issue 总结，再进入 `in_review` |
| 管理员 Inbox | 恢复时生成 1 条主 Issue actionable `review_request`；管理员验收后待办已收敛 |
| 原失败 Run、原独立 Worker 任务 | 历史状态保持不变 |

报告中的 Session turn 已于 2026-09-07 21:17:33（北京时间）完成。最后实际工具错误发生于 21:14:01；之后的文字属于旧回合的尾部输出，历史错误仍可在页面看到，不代表当前仍在执行。该恢复 Run 保留了 7 条真实 `agent_tool.failed` 事件，不能把这一历史恢复过程描述为零错误运行。

## 测试与证据

- `go test ./...` 通过；后续工具指令与错误提示调整再次通过 collaboration、runtimebinding、httpapi 测试并重新构建。
- `TestHumanFollowUpOnFailedTeamCreatesRootContinuation` 在 memory 和真实 PostgreSQL 上通过，覆盖失败 Team、两个子 Issue 人类回复（普通回复与 mention）、共享 continuation、结果回 Lead、人类补充进入上下文、先验收后等待兄弟结果，以及保留失败历史。
- PostgreSQL StoreSuite、Inbox 子 Issue review 通知与 operational job 排除测试通过。
- 本轮未运行 Java Maven 验证；没有 Java 修改。Kubernetes envtest 缺少本地依赖，未将其记为通过。
- `audit.py` 从部署实例读取 Issue、Run、event、Task、execution、Session、Inbox 并作关联与状态断言，结果见 `audit-results.json`。
- 脱敏 API 原始证据位于 `evidence/`，测试与部署输出位于 `logs/`。未保存登录 token。

## 新建实例回归及额外发现

测试 Issue：<http://127.0.0.1:18080/work/issues/e9039b21-d163-4d61-be6a-199c3ed3ea6f>

1. MA1 委派两个子 Issue 给 MA2，分别需要未知输入 ALPHA、BETA。两个 Worker 正确报告输入缺失。第一阶段暴露了无效 waiting 反复重试，部署更明确的工具反馈后，Lead 建立了显式人类 mention 待办并结束回合，主 Issue 进入 blocked。原 Run 保持 waiting，属于等待人类的活动 Run，不是失败 Run。
2. 管理员通过正常评论提供 ALPHA=21、BETA=32，其中一条使用明确 Agent mention。两个 Worker 自动恢复并交付 A=42、B=42。两个 Lead 回合自动逐一验收，第一个回合成功等待已排队的兄弟结果，第二个回合用 math.evaluate 验算合计 84，并调用 run.node.complete 发布实际汇总。这个阶段没有使用结果回流或让出回合的修复工具，四个 Task completed、四个 execution succeeded，无新增 agent_tool.failed。
3. 最终状态审计发现另一处缺陷：收敛代码只推进 in_progress 的主 Issue，曾等待人类的 blocked 主 Issue 被漏掉。已修复：只允许最新 adaptive Team Run、成功的根协调节点且所有直接子 Issue 已决定时，继续推进 blocked 主 Issue；旧完成 Run 不得覆盖新 Run 的状态。新增 human_recovery、partial success 和旧 Run 重放保护回归均通过。对这个已经结束的测试 Run 调用一次修复后的标准 ReconcileRun，使其状态进入 in_review 并生成验收待办，没有改写 Task、Attempt 或 Run 历史。
4. Run 最终为 partial_succeeded：它保留了初始两个 Worker 的失败节点；后续四次恢复执行全部成功。这是物理节点历史汇总，最终用户交付已完整并等待验收，不能把 Run 误记为 succeeded。

本新用例初始阶段保留了 22 条工具错误，因此只将“人类补充后的恢复执行阶段”记为零工具错误。最终原始证据及断言见 fresh-audit-results.json 和 fresh-final-* 文件。新用例证明自动接力和等待修复；其最后的状态收敛缺陷是在本轮发现后补修并重放验证，不能声称整条新用例第一次运行就完全通过。

最新全量测试与构建：logs/agentscope-human-resume-all-final3.log。最新部署：logs/agentscope-human-resume-deploy-final3.log。
