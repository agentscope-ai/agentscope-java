---
title: "Managed 支持的能力与接入类型"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Managed Agent 执行平台提供的 Harness。扩展能力时，区分模型连接、执行环境、工具连接与工作入口。

## 模型接入

| 接入方式 | 需要准备什么 |
| --- | --- |
| 标准 DashScope 默认模型 | 在 Dataplane 配置 `DASHSCOPE_API_KEY` 与默认模型 |
| 显式 DashScope 模型 | 使用 `dashscope:model-name`；部署内有 DashScope 扩展与凭据 |
| 其他 ModelProvider | 自定义 Dataplane 发行包包含对应模型扩展，并配置该 provider 的连接与认证 |
| 自定义模型对象 | 在自定义 Dataplane 中提供 `Model` bean 作为默认值，或用 `ModelRegistry` 注册命名模型/工厂 |

Java SDK 提供 OpenAI 及兼容接口、Anthropic、Gemini、Ollama 等[模型扩展](/v2/zh/integration/model/index)。它们属于可集成能力；标准 Service 镜像不因 SDK 存在扩展就自动包含全部 provider。模型需要支持任务使用的工具调用和输入类型。

## 工具与 MCP

内置工具由 Harness 和所选 Environment 提供。`agent_toolset` 控制内置工具，`mcp_toolset` 关联一个 `mcpServerName`；具体连接在 `mcpServers` 中定义。

| 连接形式 | Managed 使用条件 |
| --- | --- |
| HTTP MCP | Dataplane 可访问 endpoint，协议与服务端匹配；通过 Vault 提供所需凭据 |
| stdio MCP | 必须显式选择 `local` Environment；命令及其依赖安装在 Dataplane 环境内 |
| Shell / 文件工具 | 能力由 Environment 类型决定；`remote` 不提供 Shell |
| 外部系统工具 | 配置允许的操作、认证和工具确认策略，并先执行只读调用 |

`mcpServers` 常用字段包括 `name`、`transport`、`url`、`command`、`args`、`headers`、`env`、`queryParams`、`enableTools`、`disableTools`、`required`、`initializationTimeout` 和 `timeout`。`command/args` 用于 stdio；网络 endpoint 使用 `url`。超时字段使用适配器接受的时长字符串，例如 `PT30S`。

工具策略的 `enabled` 控制是否启用；`permissionPolicy.type` 使用 `always_allow`、`always_ask` 或 `deny`。只有实际请求需要确认时才会进入相应确认流程，不能把“工具已配置”当作“工具允许执行”。

## Skills 与内部 Subagents

[Workspace](/v2/zh/service/workspaces) 保存 `AGENTS.md`、Skill 文件和子 Agent 声明。Skills 提供可复用步骤与辅助资料；Subagents 提供 Agent 内部的专项委派。系统依赖仍需安装在实际 Environment 中。

需要独立负责人、持久讨论、跨运行方式或统一交付的协作时，使用 [Team](/v2/zh/service/teams)。Team 成员不是简单地把同一个进程中的所有 Subagent 暴露为独立服务。

## 知识、凭据与入口

- [Memory Store](/v2/zh/service/memory)：已绑定的共享知识，执行期间按需只读访问。
- [Vault](/v2/zh/service/vault)：解析工具连接凭据，不自动配置所有模型连接。
- Chat：个人多轮会话；Issue：工作交付与验收。
- Automation、Workflow、Team：安排、组合或委派 Managed 工作。
- [Endpoint](/v2/zh/service/endpoints) 与 Channel：按发布的协议和路由将能力提供给应用或消息平台。

验证新能力时，保留一个最小输入、预期工具调用和可核对的输出。先单独验证 Agent，再加入 Team 或 Workflow。
