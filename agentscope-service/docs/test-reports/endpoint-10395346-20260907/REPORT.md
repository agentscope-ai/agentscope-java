# Endpoint 输入与执行记录修复（2026-09-07）

代码实际位置：`/Users/ken/agentscope-2/agentscope-java`，分支 `agentscope-service-v5`。所有本次修改直接写入主目录，未创建 worktree，未提交 Git。保留目录中其他会话的暂存及未暂存修改。

## 根因与修复

1. 原 Invocation/Run 保存了 `{"prompt":"帮我查看当前目录下的文件，写一首诗"}`，但 Issue.description 为空，Lead 的实际 task.get.currentRequest 只有 `team1 API invocation`。因此模型误以为需要调用一个未指定的 API，反复追问 API 参数。现在创建 Issue 时完整附加输入，并为历史 Endpoint 根任务构造上下文时补齐原输入；保留后续 mention 请求优先级和子任务自身目标。JSON 使用 UseNumber 避免大整数精度损失，同时将 Unicode 转义还原为可读中文。
2. Run 已失败并把 Issue 置为 blocked，Endpoint sweeper 又把 blocked 改成 cancelled。现在失败投影为 blocked，只有真实 RunCancelled 才投影为 cancelled。先完成 Issue 投影再终结 Invocation，版本冲突可在下轮重试。
3. Issue 页面原先拉取全局任务的默认页再过滤，且工具失败统计混入同一 Run 的其他 Issue。现在按 tenant/namespace/issueId 查询并分页拉全，诊断统计限定当前 Issue；完整执行页补充原始 input，以及每个事件的时间、Issue、Task、Attempt 关联和 Lead/Worker 角色。
4. 第一次真实回放发现模型声称“已写诗”，却只提交完成声明。已强化 Lead 对照主 Issue 全部要求逐项核对，并把实际交付正文放入 run.node.complete.output 的要求；完成工具的说明同步修正。第二次回放实际诗歌同时出现在 Endpoint 响应和主 Issue 总结中。此项为运行指令改进，不等于对任意模型输出的语义完整性提供确定性保证。

## 历史样例

原 Issue：10395346-02dc-5fff-bb96-bef5404cb642；Run：37a0e973-c617-5a3b-a446-087dbdf4d963。

通过限定 ID、版本、Endpoint 类型、失败 Run、无活动任务及明确系统误取消 Activity 的事务补回描述、恢复 blocked，并写入 Activity 与 outbox 状态事件。旧 Run、Task、Attempt 和评论保留原始失败记录。没有自动重跑旧任务。备份、SQL 和修复后导出分别见 evidence/repair-backup.json、repair-history.sql、evidence/repair-after.json。

## 新集群真实回放

只传 input，不用 title/description 携带问题；新测试 Endpoint 指向原 team1，原 MA1/MA2 配置不变。

| 场景 | Issue | Invocation / Run / Issue | Task / Attempt | Run 事件 |
| --- | --- | --- | --- | --- |
| 原问题：查看目录、写诗 | 657f90fe-1d4c-5433-b226-a59e9e21c0d3 | completed / succeeded / done | 3 completed / 3 succeeded | 17 |
| 必需工具未配置且禁止替代 | e53898ac-4554-5aa0-bd5a-ea2a099b72a5 | failed / failed / blocked | 1 failed / 1 failed | 6 |

正向场景有一个已完成子 Issue，MA2 实际调用目录工具并提供诗歌。Lead 经 waiting 后接回结果；无取消事件，无未完成 Task/Attempt。唯一回流 TaskInput 为 processed，并关联回复评论。负向场景明确返回 TOOL_MISSING，无重复追问 API 参数。

两条 Run 的事件序号连续，事件的 Task/Attempt 关联一致；每个 Attempt 都有 Session、消息、事件、Turn 诊断。已检查主 Issue 总结创建时间不晚于最终状态变更时间。读取任务专用 context REST 路径时，管理员凭证得到预期的 401；上下文验证使用 Agent 实际调用 task.get 后保留在 Session 的工具结果，不绕过任务认证。

Endpoint 实际返回见 evidence/retest2-api-result.json 和 evidence/retest2-negative-api-result.json；完整断言见 audit.py、audit-results.json；输入状态见 evidence/final-input-states.json。第一次未交付诗歌的回放也保留作为负面证据，不计入最终通过结果。

## 验证与部署

- Endpoint 创建/幂等、输入渲染、历史上下文、mention 优先级、子任务隔离、失败/取消状态投影回归通过。
- 最后一次 `go test ./...` 全部通过；早期 Inbox 审核通知测试失败后，目录中同步进行的 Inbox 修改已修复，该次旧失败不被隐去但不代表最终结果。
- PostgreSQL StoreSuite 使用独立数据库 agentscope_endpoint_20260907 实际执行通过，未使用业务数据库测试。
- 最后一次前端测试 19 个文件、56 项通过；TypeScript/Vite 构建通过。
- 本机未配置 Kubernetes envtest 的 etcd，相关 envtest 集成测试跳过；本次真实回放针对本地四平面服务、PostgreSQL、Java DP 和 managed Agent 链路。
- 构建 aistiod 并部署，四平面健康；启动显式使用 BUILDER_REBUILD=0、BUILDER_RESET_DB=0，未重置数据。最近一次本次部署约 20:27。
- 浏览器确认原 Issue 可见原始中文问题、修复说明和 blocked 状态；执行页可见原 input 和 Issue/Task/Attempt 链接。

本报告仅覆盖本次 Endpoint 问题及上述回放，不把更早的所有 Agent 类型、Team、多行业研究和 conversation 全量矩阵算作本次重新执行。
