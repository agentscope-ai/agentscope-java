---
title: "Team 协作：委派、汇总与扩展"
---

[English](/v2/en/service/team-collaboration)

Team 适合目标明确、实现步骤需要动态决定的工作。与 Workflow 的固定拓扑相比，Lead 根据上下文选择成员、拆分任务和汇总结果。配置入口见 [Teams](/v2/zh/service/teams)。

## 设计可协作的角色

以技术调研为例，Lead 明确问题并整合报告；Researcher 收集带来源的事实；Reviewer 检查证据与遗漏。每个角色都应有独立可验证的输出，避免多个 Agent 同时无边界地改同一文件。

成员可以混用 Managed、Hosted 和 External，但必须具备对应任务派发与协作能力。能在 Chat 回答并不一定能充当 coordinator。先单独验证每个成员，再把任务交给 Team。

## 从 Issue 观察一次协作

创建“比较两种部署方案” Issue，提供限制、资料和验收标准并选择 Team。查看 Lead 的计划、成员评论、子 Issue、产物和 Task map，核对每个输出如何被 Lead 引用。

工作归属关系是：Issue 保存目标和验收；Run 保存本次协作；Node 表示步骤；AgentTask 表示派发；Attempt 表示实际执行。同一工作可以有多次执行，结果和失败证据仍关联原 Issue。

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

检查 Lead 最终汇总是否包含所有必要成员的结果，未完成的部分是否明确说明。Run succeeded 或 partial_succeeded 不能单独证明整个目标已达成。人工验收的 Issue 仍需在 Inbox 接受结果。

用于应用集成时发布 Team job [Endpoint](/v2/zh/service/endpoints)，让调用方通过 invocation 状态与结果跟踪工作。
