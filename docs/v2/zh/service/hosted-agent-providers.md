---
title: "Hosted 支持的 Provider 与能力差异"
en_link: /v2/en/service/hosted-agent-providers
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

当前 Runtime Host 包含以下五类 provider 适配器。`runtime probe` 验证可执行文件可探测，实际工作还需要 provider 登录、模型和工具可用。

## 选择运行类型

| Provider 名称 | `--providers` 值 | 默认可执行文件 | 执行方式 |
| --- | --- | --- | --- |
| Codex | `codex` | `codex` | app-server 线程与事件 |
| Claude Code | `claude-code` | `claude` | CLI 流式 JSON |
| Qoder | `qoder` | `qodercli` | CLI 流式事件与控制请求 |
| QwenPaw | `qwenpaw` | `qwenpaw` | ACP Session |
| OpenClaw | `openclaw` | `openclaw` | `agent exec` |

安装 provider 本体并完成登录后，再连接 Host。以上是 Service 适配范围，不代表 CLI 二进制随 Service 发布包附带。

## 可移植定义如何生效

| Provider | 指令/技能 | MCP | Managed 风格内置工具策略 | 平台 Subagent 定义映射 | 会话恢复 |
| --- | --- | --- | --- | --- | --- |
| Codex | developer instructions / `.agents/skills` | 支持 | 不支持；使用原生 sandbox/审批 | 支持共享工作区形式 | 支持 |
| Claude Code | `CLAUDE.md` / `.claude/skills` | 支持 | 映射允许/拒绝列表 | 当前未声明支持 | 支持 |
| Qoder | Prompt / 定义技能目录 | 支持 | 映射允许/拒绝列表 | 支持共享工作区形式 | 支持 |
| QwenPaw | Prompt / `skills` | 支持 | 原生策略 | 当前未声明支持 | 支持 |
| OpenClaw | Prompt / `skills` | 当前不支持 | 原生策略 | 当前未声明支持 | 当前不支持 |

“支持”表示适配器有对应集成，仍需要目标 CLI 版本和账户能力。Codex 的原生 Subagent 映射要求 0.153.4+，Qoder 要求 1.0.37+；当前映射要求共享工作区。Codex Subagent 声明不能携带此映射无法执行的 `tools` 或 `maxIters`；Qoder 的工具需要能映射为原生工具名。

Codex、Qoder 和 QwenPaw 适配器提供控制面工具确认接入。Claude Code 的权限通过其 CLI 配置控制，不能据此承诺与前述 provider 相同的 Inbox 确认流程。OpenClaw 使用支持 Shell 的任务 CLI 完成协作；它不支持本适配器的 MCP 注入。

## 选择建议

需要持久会话、工具确认或子 Agent 时，逐列检查所需能力。复用含 Managed 工具策略的定义前，先看 provider 是否可执行该策略；例如 Codex 可以使用 MCP，但不能执行 Managed 的内置工具权限配置。

同一 Team 可以使用不同 provider。先验证每个成员的独立任务与协作工具，再检查 Leader 的委派和汇总。原生子 Agent 能力与 Service Team 的多成员协作是不同层次。

下一步：[参数配置](/v2/zh/service/hosted-agent-configuration) · [运行原理](/v2/zh/service/hosted-agent-execution)。

## 从 API 获取当前主机的能力

文中的表格用于理解映射方式，实际可用项以主机上报为准。用平台账户 Bearer 请求：

```bash
curl -sS "$SERVICE_URL/api/v1/agents/runtime-options?tenant=default&namespace=default" \
  -H "Authorization: Bearer $TOKEN"
```

在返回的 `runtimes` 中选择 provider；每项提供 `provider`、`version`、`runtimeProfileId`、`runtimePoolId`、`hostCount` 和 `capabilities`。能力对象包含 `instructions`、`workspace`、`skills`、`subagents`、`tools`、`shell`、`mcp`、`model`、`customArgs`、`approval` 和 `resume`。除 `resume` 为布尔值外，能力项通过 `supported`、`mode`、`target` 说明是否支持及如何映射。

可以从 `GET /api/v1/runtime-hosts?tenant=...&namespace=...` 返回的 `items[].capabilities` 查看各 Host 的 provider 版本和描述。创建 Agent 时，把选项的 profile/pool UUID 写入 binding；更新 provider 专属参数使用 `/api/v1/agents/{agentId}/hosted-settings`，字段见[配置参考](/v2/zh/service/hosted-agent-configuration)。

这里的 `resume` 表示原生 provider 会话恢复；统一 Agent API 的 `capabilities.resume` 表示调用层当前是否有恢复命令，二者范围不同。对外服务应读取 Endpoint/invocation 的 capabilities，而非用 provider 能力直接推导所有业务操作可用。
