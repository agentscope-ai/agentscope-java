# 主 Issue 总结修复（2026-09-07）

实际目录：/Users/ken/agentscope-2/agentscope-java；分支：agentscope-service-v5。代码未提交 Git，原有暂存文件保留。Go 服务与 Runtime Host 已构建、部署，没有清理数据。本次未修改 Java 代码。

两个主 Issue 只有最初的拆分消息，最终 blocked 原因保存在子 Issue 和 Run 中。Lead 的 task.complete(blocked) / task.fail 绕过专用 coordinator 主 Issue 总结投影；原投影还存在先改状态、再写说明的问题。

## 修改

统一 Lead 普通失败与专用失败收尾；成功和失败都会先持久化主 Issue 总结，再推进最终状态。总结包含 Lead 结论、子任务链接及状态、交付内容、未完成原因和下一步；不路由新 AgentTask。稳定 Comment ID 保证并发与重复回调幂等。

Lead 异常退出、终态 Run 重放可补写系统总结。总结写入失败时不提前改变主 Issue 状态；旧失败重放不会覆盖新的恢复 Run 状态。Managed/Hosted Lead 指令要求收尾说明完成项、未完成项、状态原因及下一步。

## 验证

- go test ./... 通过。
- collaboration、orchestration、httpapi、runtimehost、runtimebinding 最终回归通过。
- 独立 PostgreSQL 数据库 agentscope_summary_20260907：并发 12 次补写仅一条总结，没有新任务。
- HTTP 回归覆盖 run.node.fail、task.fail、task.complete(blocked)，检查 Task/Attempt/Node/Run 收敛与总结先于主 Issue blocked。
- 成功、失败回归检查子任务交付保留、总结先于 in_review/blocked 和重复回调幂等。
- 注入总结存储失败，检查主 Issue 不先改状态、恢复可重放；旧失败不覆盖新恢复状态。

## 历史数据补写

使用 aistio/tools/repair-team-summaries，仅针对用户提供的两个终态 Run 补写系统总结，没有重新运行 Agent 或更改既有执行状态。

- 36c07ba6-f394-4d61-9951-c6ac44e6ab95：两项调研均被缺少 TAVILY_API_KEY 阻塞。
- d9fa3d3f-2be1-4861-9b4d-ff2ae07848a4：保留完整诗歌，说明科普文档后续执行 cancelled，主流程 objective_blocked。

部署后读取 Issue export 验证：各增加一条总结；Issue 状态、Task 集合及状态、Run 集合及状态保持一致。新增评论使 Issue version 按正常规则增加 1。断言见 verification.json，脱敏响应见 evidence/。

## 范围

本次解决主 Issue 总结缺失及收尾入口不一致。此前“Lead 等待 MA2 回复却报告 blocked，导致 MA2 被取消”的语义问题和跨 Agent 文件交付可见性仍待修复。补写总结不等于业务执行已成功。本次没有重新跑完整 LLM 业务矩阵；历史数据修复已通过部署后 API 核验。
