# 完整集成测试修复与复验报告

**35/35 业务检查通过，完整状态审计 0 异常。重启后复验结果相同。**

测试前缀：`it-20260907-fix2`。最终采样：2026-09-07T14:09:21.799680+08:00。

代码实际位于 `/Users/ken/agentscope-2/agentscope-java`，分支 `agentscope-service-v5`。本轮所有修复直接写入主目录，尚未提交 Git；保留原有 28 个已暂存前端资源重命名。集群和 Runtime Host 已更新，最后一次保留数据重启后重复核验全部结果。

## 场景

| 场景 | 结果 |
|---|---|
| Managed、Qoder、Codex 单 Agent | 计算 400、排序 apple/pear/zebra、101 为质数，均正确 |
| 管理员与 Agent 同 Issue 交互 | A→B→A 恰好 3 个 Task，回复 CROSS_ACK=144；独立更正后返回 ADMIN_FINAL=156，并由管理员验收 |
| Managed Team 正常任务 | 两个子 Issue 真实委派、结果验收；算术子任务和汇总均为 400 |
| 混合 Team 行业研究 | 新能源汽车和手机两个子任务均创建；Web 工具缺少凭证时失败，协调员及其子任务完整收敛 |
| Agent/Team Endpoint Job | 实际发布服务，算术、文本和 Team 任务正确返回业务结果；失败任务保留明确错误码和原因 |
| Managed/Qoder Conversation | 两轮对话保留标记和上下文，答案均为 42，内部 Turn 与公开状态均 completed |
| 幂等、鉴权、SSE | 相同请求立即及完成后重试复用 Invocation；不同内容返回 409，无 Key 返回 401；9 个 SSE 有序结束，断点续读正确且返回业务结果 |
| 能力边界 | Team Conversation 当前不支持，创建明确返回 400 |

行业调研属于**预期失败路径通过**，没有声称完成实时研究。手机 Agent 真实调用 web_search，缺少 TAVILY_API_KEY 后产生结构化失败及对应 Run 错误事件；协调员失败并终止另一子任务。Qoder API 的 Web 任务遇到需要工具确认、但没有可负责确认的人类账号，Invocation 返回 `hitl_approver_unavailable` 并收敛，未伪报成功。测试未配置额外凭证或扩大权限。

## 全图审计

| 对象 | 数量 |
|---|---:|
| issues | 20 |
| runs | 14 |
| tasks | 30 |
| attempts | 30 |
| sessions | 22 |
| runEvents | 312 |
| sessionEvents | 418 |
| activities | 104 |

- Issue 状态：{'done': 13, 'blocked': 5, 'cancelled': 2}。
- Run 状态：{'succeeded': 11, 'failed': 3}。
- Task 状态：{'completed': 23, 'cancelled': 2, 'failed': 5}；Attempt 与 Task 终态一致。
- 8 个输入 processed，2 个失败协调员输入 blocked，无悬挂输入。
- 每个终态 Run 恰有一个对应结束事件，无事件乱序、重复工具错误、孤立 Task/Attempt、错误父子关联或终态 Run 下的活跃 Node/Task/Attempt。
- 人工创建的失败研究及其子 Issue 保持 blocked，等待人工处理；API 失败根 Issue 按现有策略 cancelled。全部执行已结束，正常场景的 Issue 已完成。

逐项检查覆盖 Issue/子 Issue、Comment/Activity、Run/Node、Task/Input、Execution Attempt、Session/Turn、Session Event、Run Event、Invocation 与公开 SSE。

## 修复

1. 当前评论优先于旧 Issue 指令；补充原始委派和返回结果上下文，避免旧答案与多余确认回合。
2. 已显式回复时避免完成结果重复投递；独立后续任务使用独立 Node，防止 Run 提前成功。
3. 增加精确算术工具并要求交付和验收前核验，保证子任务与 Team 汇总一致。
4. Managed Endpoint 事件携带 Invocation/Turn 关联，正确投影结果和终态，旧事件重放不结束新 Turn。
5. JSON 幂等比较忽略对象键顺序并保留数字精度；Task/Run/API/SSE 保留实际业务结果。
6. 失败输入事务性收敛，Web 工具使用结构化错误；Run 终态与结束事件原子写入并去重。
7. 全量构建中发现并修复 RAG 图片字符串化和 DashScope HTTP 错误使用 file:// 的问题。保留多模态内容并传输本地图片字节，真实图片用例读出了版本与端口。

## 验证

Go 全量、内存 Store、隔离数据库上的真实 PostgreSQL 合约测试，以及新增 Runtime Host 回归均通过。Maven 从 `mvn clean verify` 开始，旧 formatter 的 file:// 预期失败后，更新固定测试预期为实际图片数据，以 `mvn verify -rf :agentscope-extensions-model-dashscope` 继续剩余模块；没有跳过失败用例。

**Maven 剩余模块续跑 BUILD SUCCESS，整套校验已完成。** 详细命令、首次失败原因、修正及通过日志摘要见 `evidence/validation.json`。

## 证据

- `business-checks.json`：35 项检查、实际业务值和对应对象。
- `final-inventory.json` / `final-checks.json`：重启后的全图快照和审计结果。
- `before-restart-*`：重启前的通过结果；`audit1-*`、`audit2-*` 保留运行中快照。
- `evidence/`：脱敏 HTTP、对象详情、Session/Run 事件、公开 API 和 SSE。
- `evidence/baseline-build.json`、`evidence/post-verification-build.json`、`evidence/source-files.json`：测试及最终部署版本、主目录源码指纹。
- `state.json`：可在控制台定位的 Agent、Team、Issue、Endpoint 和 Invocation ID。
- 相邻 `it-20260907-fix1` 保留失败轮次证据；本轮使用全新资源，没有覆盖或删除失败记录。

## 场景定位

| 场景 | Issue ID | 状态 |
|---|---|---|
| single-managed | `069ca7f9-3a3e-4dd3-9a95-7e9a9c34c9a2` | done |
| single-qoder | `20fcf6ca-7de3-48b4-9ac3-b1ff77b437f9` | done |
| single-codex | `7f191ad2-6fc1-41d6-b020-5c232660060b` | done |
| team-success | `2e2036ab-7ba2-4bc9-8ef2-d8d68897dfa4` | done |
| team-web-research | `fa0a1def-6ba2-404a-befd-dd1e59660d7e` | blocked |
| api-managed-job-ok | `ff8f4313-f8fa-57e8-ae5a-9d1be4b58222` | done |
| api-qoder-job-ok | `2e99b18c-73d6-50bd-8664-4a6293833258` | done |
| api-team-job-ok | `627a694e-2b5b-5a60-b070-a663069eca07` | done |
| api-team-job-research | `f2798b5c-cc96-5e75-ab08-5710eee0d172` | cancelled |
| api-qoder-job-fail | `528c1af2-7751-562d-ab31-eb7ed90ab3f3` | cancelled |
