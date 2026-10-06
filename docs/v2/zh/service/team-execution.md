---
title: "Team 工作原理与结果收敛"
en_link: /v2/en/service/team-execution
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Team 是持久团队定义。一次团队工作创建 Run 和团队快照，由 Leader 驱动协调；它不是每次无条件并行运行所有成员。

```mermaid
flowchart TD
  A[Issue / Automation / Endpoint job] --> B[冻结 Team 快照并创建 Run]
  B --> C[唯一协调节点与初始 Leader 任务]
  C --> D[Leader 根据目标委派]
  D --> E[成员任务与各自 Attempt]
  E --> F[评论、Artifact、子 Issue 与处理记录]
  F --> D
  D --> G[汇总并完成或失败协调节点]
  G --> H[Run 终态与 Issue 验收]
```

## 定义与一次运行的关系

Team 定义包含 Leader、成员和策略；Run 固定这次协作的快照，便于追查当时的团队配置。控制面为 Team 创建一个 coordinator node 和初始 Leader 任务，而不是为每个成员无条件创建一次调用。

Leader 根据上下文选择成员。需要固定节点顺序、条件和汇合规则时使用 Workflow；Workflow 内也可以调用 Team 节点，把动态协作嵌入更大的流程。

## 从目标到执行

| 对象 | 在协作中记录什么 |
| --- | --- |
| Issue | 目标、负责人、讨论、交付与验收 |
| Run / Node | 本次协作及协调步骤的状态 |
| AgentTask | 指派给一个 Agent 的工作义务 |
| Attempt | 某个后端上的一次实际执行 |
| Comment / Artifact | 持久沟通与共享结果 |
| Child Issue | 可独立跟踪的子目标 |

同一 AgentTask 可以因重试产生多个 Attempt。执行者使用任务范围上下文与凭据，不能拿管理员凭据在普通终端代替一项正在派发的任务。

## 委派与持久沟通

成员根据职责提交结果，Leader 阅读、追问或要求修订。评论/mention 与任务输入的交付、确认、处理状态共同支持后续协作；重试时要保留去重和已处理信息，避免把同一条消息当成新任务重复执行。

跨成员共享使用 Issue、评论、Artifact 等持久对象。Managed Environment、Hosted 工作目录、External 私有文件不会仅因在同一 Team 中而自动合并。

## 如何结束工作

成员回复、Attempt 成功、coordinator node 完成、Run 终态和 Issue 验收分别表达不同层次。Leader 的最终文字不能代替节点完成。仍有必要子工作未完成时，应等待、调整计划或明确失败，不能用“报告已生成”掩盖未完成义务。

人工验收需要检查最终结果、重要来源和失败说明，再接受结果或要求修改。查看 Run succeeded/partial_succeeded 时也要核对业务验收要求。

## 通过 API 观察一次协作

业务提交先得到 Issue ID，再通过 `GET /api/v1/orchestration-runs?tenant=...&namespace=...&issueId=...` 查询关联 Run。`GET /api/v1/orchestration-runs/{runId}/graph` 一次返回 `run`、`nodes`、`edges`、`tasks`、`attempts` 及可选 `childRuns`，适合构建业务侧任务地图。

`GET /api/v1/orchestration-runs/{runId}/events?after=0&limit=200` 返回 `{events}`；事件的 `sequence` 是下一次 `after` 的游标，`nodeId`、`agentTaskId`、`attemptId` 关联具体执行。它是持久事件的 JSON 查询；平台 `/api/v1/events` WebSocket 只是刷新通知。已发布服务的调用方优先使用 [Invocation snapshot 与 SSE](/v2/zh/service/service-api)，避免把内部编排结构当作稳定的应用协议。

Run 暂停、继续和取消分别调用 `/pause`、`/resume`、`/cancel`。这些控制作用于执行，不能代替 Issue 的验收。状态、重试层次和 Session 关联见[执行 API 参考](/v2/zh/service/sessions)。

## 扩展与恢复

新增成员前先单独验证它的能力，再检查 Leader 是否能正确使用该角色。混合 Managed、Hosted、External 时，用[成员配置](/v2/zh/service/team-configuration)中的检查条件逐个验收。

恢复依赖持久工作和执行状态；Runtime fresh fallback 重建上下文，不迁移旧进程。排障时沿 Issue → Run → Node → Task → Attempt 检查：未派发看就绪度与策略，执行卡住看后端与确认请求，交付未结束看成员义务和协调节点状态。

操作示例见 [Team API 指南](/v2/zh/service/create-team)，页面诊断见[控制台：团队与编排](/v2/zh/service/console/orchestration)。
