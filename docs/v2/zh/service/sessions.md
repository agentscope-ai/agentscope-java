---
title: "执行参考：Session、Run 与 Attempt"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

日常对话从 Chat 开始，工作从 Issue 开始。Sessions 和 Executions 提供执行诊断，入口是否可见取决于运维权限。

## 关联一项工作

从 Issue 的 Executions 打开 Run，先看输入、运行方式与实际目标，然后查看节点、AgentTask 和最新 Attempt。Session 记录模型或 provider 上下文。提交排障信息时保留这些 ID，避免只提供可重复的显示名称。

| Run mode | 形态 |
| --- | --- |
| direct | 单 Agent 工作 |
| adaptive | Lead 动态协调的 Team 工作 |
| declared | 固定 Workflow revision |
| subrun | 父节点调用的子流程 |

## Run 状态与控制

planned 表示尚未开始，running 表示正在推进，waiting 表示等待条件、信号或外部结果。paused 停止新节点派发，cancelling 等待取消收敛。终态为 cancelled、succeeded、partial_succeeded 或 failed。

Pause 不冻结已经开始的外部进程。Cancel 也不自动回滚已经产生的文件或外部操作，更不会自动接受 Issue。查看节点和 Attempt 的最终状态确认取消是否完成。

## 三种重复执行

基础设施重试可以为同一 Task 产生新的 Attempt；节点策略重试可以产生新 Task；终态 Run 的人工 Rerun 创建新 Run 并保留来源。每次都要查看实际执行目标和输入，避免把旧的失败和新的成功混为一次运行。

显式配置 fresh fallback 才允许按策略跨候选后端重建执行上下文，恢复依赖 Issue、Comment 和 Artifact，而非原进程内状态。

## 等待与失败

先看 waitReason/error，再判断是否需要人工动作、在线 Host、Worker、模型凭据或更多容量。`requires_action` 的 Session 可能在等待工具结果，不能只依据该状态判断是人工审批。

工具事件、最终回复、Attempt 成功和 Issue 验收是不同证据。对最终交付使用 [Inbox](/v2/zh/service/inbox) 的审阅流程；Managed 结果语义见[任务结果](/v2/zh/service/managed-harness-task-outcomes)。

## 断线后继续观察

刷新页面后重新打开原工作，查询当前状态和已保存事件。SSE 长连接结束不代表任务失败。代理应及时转发事件；不要因为前端连接断开就用新的幂等键重复提交。

## 一次排障应记录什么

用[研发闭环案例](/v2/zh/service/cases/sdlc-team)练习：从主 Issue 打开 Run，在 Task map 中找到 Hosted 成员，定位最新 Attempt，再核对其 Session、日志与文件。

| 记录 | 用途 |
| --- | --- |
| Issue ID 与验收要求 | 确定要交付什么，以及是否仍待人工验收 |
| Run ID、mode 与目标 revision | 确定是哪次执行、采用哪种编排和定义 |
| Node / Task / Attempt ID | 找到实际失败步骤，区分重试层级 |
| Session ID、Host/provider（如果适用） | 定位上下文与执行主机 |
| 状态、错误、时间与最后事件游标 | 区分正在等待、已经终止和仅观察连接中断 |
| Artifact 与测试日志 | 判断结果是否满足要求，而非仅查看状态标签 |

若第二次 Attempt 成功，仍保留第一次失败的记录，并把交付指向成功执行的文件。若只有 SSE 断线，按[SSE 指南](/v2/zh/service/sse-events)使用原调用的游标恢复观察；不要把它当作新的业务任务。
