---
title: "Hosted 支持的 Provider 与能力差异"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

当前 Runtime Host 包含以下五类 provider 适配器。`runtime probe` 验证可执行文件可探测，实际工作还需要 provider 登录、模型和工具可用。

## 选择运行类型

| 控制台名称 | `--providers` 值 | 默认可执行文件 | 执行方式 |
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
