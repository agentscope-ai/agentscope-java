---
title: "FAQ"
description: "AgentScope Java 2.0 常见问题"
---

:::{dropdown} AgentScope Java 2.0 与 1.0 兼容吗？
AgentScope Java 2.0 版本尽量保持了对 1.x 版本的兼容，确保大部分用户的平滑升级；但同时 2.0 也带来了 API 层面的不兼容变更（重新设计的 agent 抽象，以及新增的事件系统、权限系统、middleware 体系等），详情可参考 [Changelog](../change-log.md)。

    对所有新项目，我们建议直接采用 2.0，以获得新版能力；1.0 的文档仍会保留供存量用户参考。
:::

  :::{dropdown} AgentScope Java 2.0 有配套的前端吗？
有。仓库中包含 `agentscope-admin` 模块，提供与 ReActAgent 协议对齐的开箱即用 Web 应用。开发者无需自行编写 UI 即可直接体验已部署的 agent，并可通过事件系统（`AgentEvent`）与权限系统的 HITL 流程无缝集成。
:::

  :::{dropdown} 2.0 还会提供 RAG 和 long-term memory 吗？
会。`io.agentscope.core.rag` 与 `io.agentscope.core.memory.LongTermMemory` 模块已在仓库中存在，但 knowledge base、document reader 等组件正在持续完善，具体进度请关注 changelog 与 GitHub 发版。
:::

  :::{dropdown} 除了 Java 还有其他语言版本吗？
有。AgentScope 目前提供三种语言实现，各自独立仓库：

    - **Java** —— [`agentscope-ai/agentscope-java`](https://github.com/agentscope-ai/agentscope-java)（即本文档对应仓库）
    - **Python** —— [`agentscope-ai/agentscope`](https://github.com/agentscope-ai/agentscope)
    - **TypeScript** —— [`agentscope-ai/agentscope-typescript`](https://github.com/agentscope-ai/agentscope-typescript)
:::
