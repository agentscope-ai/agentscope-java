# Console Issue-first 信息架构

> 状态：已落地

Console 以两个产品区组织：

- Control：Overview、Issues、AgentTasks、Runtime sessions、Inbox & approvals、Teams、Automations、Runtime infrastructure、Governance；
- Managed：Managed Agents、Registered Agents、Agent instances、Conversations 和资源管理。

Issue 详情显示 discussion、route outcome、AgentTask、child/parent、最新 result、摘要、验收、reopen、archive 和 export。Issues 支持 scope、状态、搜索与归档视图。AgentTask 详情展示 inputs、runtime binding、Session 与结果。WebSocket 事件只负责 React Query 缓存失效，断线后 REST 轮询恢复。

所有深链保留 tenant/namespace；命令面板只导航到当前终态资源。
