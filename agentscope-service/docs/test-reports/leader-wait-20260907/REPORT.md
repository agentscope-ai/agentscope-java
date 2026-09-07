# Lead 等待 MA2 被误取消修复

目录：`/Users/ken/agentscope-2/agentscope-java`，分支：`agentscope-service-v5`。直接修改主项目，未使用独立 worktree，未提交 Git，保留已有修改和暂存文件。本次只有 Go 代码变更，已构建部署 Go 服务及 Runtime Host，没有清理数据。

## 根因与修复

Lead 已通过结构化 mention 派出 MA2 的后续工作，却调用 task.complete(outcome=blocked)。此前该调用直接转为整个 coordinator 失败，触发 fail_fast 取消 MA2。

新增 task.complete(outcome=waiting)：根据持久化任务关系确认当前 Lead 确实有尚未结束的委派工作或已排队的回报，再结束 Lead 本轮 Task/Attempt。共享协调 Node 保持 waiting，Run 和 Issue 保持可继续；MA2 回报负责唤醒新 Lead 回合。

兼容原始 blocked 用法：如果当前 Lead 派出的任务或回报仍在处理中，转换为 waiting，而不是失败。不根据回复中的“等待”等文字猜测。普通 task.fail 在该情况下拒绝，明确指引使用 waiting；真正需要终止整组任务时仍可显式调用 run.node.fail。

等待回合产生 coordinator.waiting 事件，结清已处理输入，不发布最终主 Issue 总结，也不从等待说明生成额外 AgentTask。等待说明存为 status 评论。没有实际委派回报来源的 Lead、以及普通 worker 均不能使用 waiting，避免产生无法继续的等待。

Managed 和 Hosted Lead 提示词与工具说明同步区分等待、完成和主动中止。

## 回归

- go test ./... 通过，最终等待评论类型变更后再次全量通过。
- Memory / PostgreSQL 各覆盖 waiting、旧 blocked 兼容、显式整组失败，以及 MA2 已完成但 Lead 尚未让出回合的竞态，共 12 组；含无唤醒来源和 worker 非法等待的拒绝检查。
- 成功路径验证 5 个 Task、5 个 Attempt 全部完成/成功，3 个 Node 成功，所有 inputs processed；等待不取消 worker、不生成自唤醒，最后主 Issue 总结先于 in_review。
- 三个失败入口原有主 Issue 总结回归继续通过。
- 独立 PostgreSQL 数据库 agentscope_wait_store_20260907 上完整 Store Suite 通过。

## 真实集群验证

复用原 MA1（67a796b7-02e7-493f-8990-931f0759f95e）、MA2（b86719e8-f495-402f-bffa-b6d76eee8965），创建独立测试 Team / Issue，没有改写原失败 Issue。

主 Issue：d1f1ce82-d0f1-46fd-9f8c-3186115bf24b。
Run：564a06ad-4b10-4f52-b65c-ce7104426063。

MA2 先提交 DRAFT_READY；MA1 在同一个子 Issue 用结构化 mention 追问 12×12，并以 waiting 结束当前回合；MA2 用工具完成验证，返回 FOLLOWUP_OK_144；新的 Lead 回合验收并在主 Issue 汇总。

结果：主 Issue in_review、子 Issue done、Run succeeded；5 Task completed、5 Attempt succeeded、3 Node succeeded；2 次 coordinator.waiting；无失败/取消事件，所有输入 processed，主 Issue 包含最终总结。verification.json 保存断言结果，evidence/ 保存 Issue、Run、Task、Attempt、Session 消息与事件的脱敏 API 响应。

真实业务链路通过后，将等待评论类型从 result 调整为 status，并通过最终 Go/PG 回归、重新构建部署。该类型调整没有改写已经结束的真实验证记录。历史失败 Task/Attempt 仍按原记录保留，未把已取消记录改成成功。
