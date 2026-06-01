---
title: "AgentScope 2.0 是什么？"
description: "更安全、更高效、更灵活、更完备"
---

AgentScope 2.0 是对框架的一次重大升级，核心目标是提升开发体验，让智能体在生产环境中更易构建和运行。

:::{note}
AgentScope Java 2.0 与 1.0 存在破坏性变更，在核心抽象、API 和架构上均有大幅改进。建议现有用户迁移到 2.0，以享受新特性带来的提升。
:::

2.0 带来了以下主要变化和改进：

- **事件系统**：智能体的每一步操作——文本输出、思考过程、工具调用、工具结果——都以类型化的流式事件暴露出来，无需额外适配器即可渲染丰富的响应式界面。
- **执行安全**：危险的工具调用可被拦截或暂缓审查 —— 智能体不会悄悄操作宿主机或泄露凭据。
- **人工介入**：用户可以在运行中途确认或修改工具参数，敏感操作也可转交自定义后端处理，智能体会在暂停处精确恢复执行。
- **更高效率**：多工具步骤通过并发执行加速完成，超大工具输出不再撑爆提示词，模型提供商的短暂故障也能优雅回退。
- **Middleware 体系**：将 1.x 的 hook 机制重构为 5 钩子的 middleware 体系，便于把日志、追踪、限速、动态 prompt 等能力以可组合的方式装配到任意 agent 上。

对于正在评估是否进行迁移的开发者，可以查阅 [Changelog](/zh/v2/change-log) 了解每项变更的完整说明，帮助更好地规划迁移到 AgentScope 2.0 的计划。

::::{grid} 2

:::{grid-item-card} 事件系统
:link: /zh/v2/building-blocks/message-and-event

观测智能体的每一步操作，直接流式推送到界面。
:::
	:::{grid-item-card} 执行安全
:link: /zh/v2/building-blocks/permission-system

在危险工具调用触达宿主机之前进行拦截。
:::
	:::{grid-item-card} 人工介入
:link: /zh/v2/building-blocks/agent

支持在执行前审核或修改工具参数，也可将敏感操作完全转交自定义后端处理。
:::
	:::{grid-item-card} 高效智能体
:link: /zh/v2/building-blocks/agent

工具调用根据各工具的属性自动分批，并发或顺序执行——在保证正确性的前提下获得更高的吞吐效率。
:::
	:::{grid-item-card} Middleware 体系
:link: /zh/v2/building-blocks/middleware

以可组合的方式装配日志、追踪、限速、动态 prompt 等能力。
:::
	:::{grid-item-card} 可观测的 Tool 系统
:link: /zh/v2/building-blocks/tool

统一的 ToolBase 抽象、注解式注册、MCP 适配与 Tool Group。
:::

::::
