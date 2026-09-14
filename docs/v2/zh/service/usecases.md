---
title: "场景案例"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

通过三个完整业务场景，学习如何把 Agent 创建、应用接入、Team 协作、任务反馈和人工验收连接起来。选择与你的使用方式接近的一条主线开始。

| 场景 | 团队组成 | 完整交付 |
| --- | --- | --- |
| [从 GitHub Issue 到 PR 合并](/v2/zh/service/cases/sdlc-team) | 全 Hosted：Leader、开发、Review、QA | 需求分析、实现、PR、CI、返工、Approve 与合并记录 |
| [订单履约异常处理](/v2/zh/service/cases/order-fulfillment) | 全 External：多个 AgentScope 业务应用 | 跨系统调查、处置方案、审批、执行与结果核对 |
| [从客户需求到售前方案](/v2/zh/service/cases/presales-team) | Managed 起步，扩展 External 与 Hosted | 有来源的方案、PoC 计划、复核与混合实施 |

## 如何使用

每篇都包含角色、准备条件、操作步骤、输入资料、失败分支和验收方法。研发案例提供 Java 起点、检查程序、Issue 和 CI 模板；订单案例提供固定业务数据和 SDK 接入片段，业务工具需要开发或对接；售前案例提供客户需求和可直接录入 Memory 的知识资料。

先按[快速开始](/v2/zh/service/first-session)验证 Service 和单个 Agent，再搭建完整团队。GitHub 命令会创建或修改练习仓库中的对象，执行前配置相应身份与授权范围；企业数据和客户资料使用虚构样例。

Java 样例已验证修复前后检查结果。真实 GitHub、企业系统、模型与团队端到端执行仍需在你的环境验收；样例资料与预期结果不是实跑记录。保留版本、输入、Issue/Run/Invocation ID、代码提交与实际产物，作为后续升级回归依据。
