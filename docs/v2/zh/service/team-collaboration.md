---
title: "Team 协作：委派、汇总与扩展"
en_link: /v2/en/service/team-collaboration
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Team 适合目标明确、实现步骤需要动态决定的工作。与 Workflow 的固定拓扑相比，Lead 根据上下文选择成员、拆分任务和汇总结果。创建与派发入口见 [Team API 指南](/v2/zh/service/create-team)。

## 设计可协作的角色

以技术调研为例，Lead 明确问题并整合报告；Researcher 收集带来源的事实；Reviewer 检查证据与遗漏。每个角色都应有独立可验证的输出，避免多个 Agent 同时无边界地改同一文件。

成员可以混用 Managed、Hosted 和 External，但必须具备对应任务派发与协作能力。能在 Chat 回答并不一定能充当 coordinator。先单独验证每个成员，再把任务交给 Team。

## 从 Issue 观察一次协作

通过 `POST /api/v1/issues` 创建“比较两种部署方案”，在请求中提供限制、资料、验收标准与 Team 负责人。读取 Lead 的计划、成员评论、子 Issue、产物和 Run graph，核对每个输出如何被 Lead 引用。

工作归属关系是：Issue 保存目标和验收；Run 保存本次协作；Node 表示步骤；AgentTask 表示派发；Attempt 表示实际执行。同一工作可以有多次执行，结果和失败证据仍关联原 Issue。

## 业务应用的协作 API

以下操作使用用户 Bearer token 和相应工作授权。它们操作持久业务记录，不要求调用方知道成员当前在哪个运行时执行。

| 操作 | API 与关键参数 |
| --- | --- |
| 补充信息、请求成员处理 | `POST /api/v1/issues/{issueId}/comments`：`content`、可选 `parentId`、`type`、`mentions:[{type,ref}]` |
| 预览评论路由 | `POST /api/v1/issues/{issueId}/comments/preview-routing`：与评论相同的输入，返回 `targets` |
| 读取讨论 | `GET /api/v1/issues/{issueId}/comments`：`limit`、`cursor`、可选 `threadId`、`rootsOnly`；返回 `items`、`nextCursor` |
| 创建独立子目标 | `POST /api/v1/issues/{issueId}/children`：标题、说明、负责人及验收字段，返回 `issue`、`agentTask` |
| 查询子工作 | `GET /api/v1/issues`：`tenant`、`namespace`、`parentIssueId` |
| 文件交付 | `POST /api/v1/artifacts/uploads`：multipart 的 `tenant`、`namespace`、`issueId`、`relation`、`file` |
| 读取产物 | `GET /api/v1/issues/{issueId}/artifacts`，随后 `POST /api/v1/artifacts/{artifactId}/download` |

`mentions[].type` 选择 `agent`、`team` 或 `human`，`ref` 使用对应身份标识。正文中的名字不是结构化路由。提交评论后检查返回的 `routes`，确认是已排队、已合并还是被策略阻止；这些结果比“评论写入成功”更能说明是否产生了后续工作。仅记录进度时使用 `type:"progress"`，不设置 mentions，避免隐式派发。

## 持久沟通

用 Comment 和 mention 传递进展、问题与后续请求，用 Artifact 交付文件，用 child Issue 拆出可独立跟踪的子目标。不要依赖某个成员进程内的消息作为唯一协作记录。处理一次输入后需要记录处理情况，避免重试时重复响应。

Runtime Host 在任务执行中注入范围凭据和上下文。支持 Shell 的 provider 可以使用：

```bash
agentscope task context
agentscope issue current
agentscope task progress --content-file ./progress.md
agentscope task respond --content-file ./reply.md
agentscope artifact upload ./report.md
agentscope team current
agentscope task run graph
```

这些命令在 Host 启动的任务环境中运行，不是在管理员普通终端里通过复制内部令牌模拟运行。MCP provider 可使用对应 collaboration 工具。Coordinator 使用节点完成/失败能力明确交付结果；普通回复不能替代流程收敛。

## 策略与扩展

团队 Instructions 定义共同交付要求；成员 Instructions 定义专项职责。Runtime policy 决定可用后端和约束，按节点覆盖、成员覆盖、Agent 策略的优先级生效。显式启用 fresh fallback 后，跨后端重试从持久 Issue、评论和产物重建上下文，并不搬迁原进程或私有会话。

添加成员时先明确新能力如何补足团队，再验证 Lead 是否能正确选择。增加并发前检查共享文件冲突、工具副作用和预算，不能只增加成员数量。

## 结束与验收

检查 Lead 最终汇总是否包含所有必要成员的结果，未完成的部分是否明确说明。Run succeeded 或 partial_succeeded 不能单独证明整个目标已达成。人工验收的 Issue 仍需通过 [Issue 验收 API](/v2/zh/service/inbox)接受结果。

用于应用集成时发布 Team job [Endpoint](/v2/zh/service/endpoints)，让调用方通过 invocation 状态与结果跟踪工作。

## 交付模板：让另一个成员能够复核

当[代码修复服务](/v2/zh/service/cases/incident-to-pr)需要扩展为多角色团队时，Developer 的交付评论可包含以下内容，并附 PR 和实际测试记录：

```text
目标：实现订单筛选与分页；保留原验收检查并补充边界测试。
修改：列出变更的文件与规则。
验证：JDK 版本、执行目录、命令、退出码、测试日志和 CI 链接。
交付：GitHub Issue、PR、head SHA、Review 与 Artifact 标识。
未完成项：列出没有验证的条件；没有则明确说明。
```

模板只是交付结构，不能把其中的占位内容当作已发生的执行。Lead 打开真实 Artifact 并核对证据后再汇总；成员各自的工作目录不自动共享，评论中的本地绝对路径也不等于另一个成员可以读取的文件。

## 运行时回报的身份与参数

执行适配器使用平台下发的任务凭据访问 `/api/v1/agent-tasks/{taskId}` 下的协议入口，业务用户 token 不能冒充正在执行的任务。`GET .../context` 返回当前工作上下文；`POST .../progress` 接受 `content`、可选 `parentId` 与 `mentions`；`POST .../complete` 可回报 `expectedVersion`、`summary`、`result`、`usage` 及已处理输入 ID。失败回报 `POST .../fail` 使用 `expectedVersion`、`code`、`message`。

Hosted provider 通常通过注入的 CLI / MCP 使用这些能力，External 适配器按[任务接入协议](/v2/zh/service/external-agent-execution)接入。协调节点完成/失败还有专门的 coordinator 权限；普通成员的结果回报不会自动取得 Leader 权限。

页面操作见[控制台：团队与编排](/v2/zh/service/console/orchestration)与[任务反馈](/v2/zh/service/console/tasks)。
