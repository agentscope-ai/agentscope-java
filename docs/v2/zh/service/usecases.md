---
title: "场景案例"
---

[English](/v2/en/service/usecases)

本栏目预留完整场景教程。以下案例正在规划，尚未提供端到端案例实现；每篇将包含样例输入、环境准备、配置步骤、交付物、验收标准与故障处理，便于完整复现。

## 软件研发协作

计划使用 Hosted 实施者、Managed 审阅者与 Team Lead，从缺陷 Issue 开始，展示代码修改、检查结果、Artifact 与人工验收。验收重点是需求与修改可追溯、失败可恢复、审阅有证据。

现在可以先实践：[Hosted Agent](/v2/zh/service/hosted-agent)、[Team 协作](/v2/zh/service/team-collaboration)。

## 资料调研与报告

计划提供一组公开样例资料，用 Memory 和 Workspace 支持检索、来源比对及报告汇总，展示不确定性和缺失材料处理。预期交付包括带来源的报告、证据索引和验收清单。

现在可以先实践：[第一次交付](/v2/zh/service/first-session)、[Managed Agent](/v2/zh/service/managed-agent)。

## 定时报表与事件响应

计划以定时触发和 webhook 输入创建日报 Issue，覆盖去重、积压、失败重试、订阅和验收。验收重点是同一事件不重复产生副作用，异常时能找到 delivery 与 run。

现在可以先实践：[Automations](/v2/zh/service/automation)、[Inbox](/v2/zh/service/inbox)。

## 业务系统接入

计划提供调用 Endpoint 的示例应用，覆盖异步 job、多轮 conversation、SSE、凭据轮换与版本切换。使用固定样例输入，明确成功与失败响应的处理方式。

现在可以先实践：[Endpoint](/v2/zh/service/endpoints)、[API 参考](/v2/zh/service/api-reference)。
