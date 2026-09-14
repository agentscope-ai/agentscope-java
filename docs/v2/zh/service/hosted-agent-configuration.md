---
title: "Hosted 主机与 Runtime 参数"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Hosted 有三层配置：Host 的连接与容量、Runtime Profile 的 provider 参数、Agent 的职责与模型覆盖。先按[Runtime Host 安装连接](/v2/zh/service/runtime-host)建立在线主机。

## 连接参数

```bash
agentscope connect https://agentscope.example.com \
  --providers codex,qoder \
  --pool coding-default \
  --capacity 1
agentscope runtime probe
```

示例只发现并暴露所选 provider；对应 CLI 必须已安装、登录并可执行。

| `connect` 参数/配置 | 含义 |
| --- | --- |
| URL 或 `--server` | 主机可访问的 Service HTTP 地址 |
| `--providers` | `auto` 或逗号分隔的 `codex,claude-code,qoder,qwenpaw,openclaw` |
| `--pool` | Runtime Pool 名称，默认 `coding-default` |
| `--capacity` | Host 最大并发执行数，默认 1 |
| `--workspace-root` | 任务目录根路径 |
| `--state-root` | Host 身份及持久执行状态目录 |
| `--runtime-host-binary` | `aistio-runtime-host` 可执行文件路径 |
| `AGENTSCOPE_ENROLLMENT_TOKEN` | 初次连接用的短期 enrollment 凭据 |
| `AGENTSCOPE_RUNTIME_TOKEN` | 已有 Runtime Host 凭据 |
| `AGENTSCOPE_RUNTIME_CONFIG` | 覆盖本地配置文件路径 |

默认配置在 `~/.agentscope/runtime-host/config.json`。其中 `controlPlane`、`tenant`、`namespace`、`pool`、`capacity`、`workspaceRoot`、`stateRoot` 与 `providers` 描述连接；`credential` 是身份凭据，不复制进公共示例。修改已运行 Host 的设置前先检查活跃工作，更新后重启并再次 probe。

## Agent 与 Profile 的分工

Agent 的 `system` 定义职责，`model` 提供可选模型覆盖。Model 非空时优先于 Profile 的 `model`；两者都为空时使用 provider 自己的默认配置。

`runtimeProfileId` 选择 provider 配置，`runtimePoolId` 选择可承载执行的主机池。通常在控制台选择发现的 Runtime 即可，管理员再维护 Profile 和池。只增加 Host capacity 不等于增加模型额度或消除 Agent/任务策略限制。

## Provider 参数

以下是 Runtime Profile `configuration` 中的字段，不是 `connect` 参数，也不是所有 provider 通用的表单。

| Provider | 常用字段 | 说明 |
| --- | --- | --- |
| Codex | `model`, `profile`, `sandbox`, `reasoningEffort`, `serviceTier` | 通过 app-server 启动/恢复线程；默认 sandbox 为 `workspace-write`，审批策略由适配器接入 |
| Claude Code | `model`, `permissionMode`, `allowedTools`, `disallowedTools`, `maxTurns`, `appendSystemPrompt`, `reasoningEffort` | 按 CLI 支持的参数配置；工具名称按 provider 的命名使用 |
| Qoder | `model`, `reasoningEffort`, `contextWindow`, `permissionMode`, `allowedTools`, `disallowedTools`, `maxTurns`, `appendSystemPrompt`, `agent` | 配合实际安装版本和任务确认流程 |
| QwenPaw | `agent`, `model`, `permissionMode`, `runtimeProvider`, `localDiagnostics` | 使用 ACP Session 与权限请求 |
| OpenClaw | `model`, `fallbacks`, `thinking`, `codeMode`, `timeoutSeconds`, `localModelLean`, `isolated`, `authEnvOnly`, `reasoningEffort` | 使用 `agent exec`；不具备本适配器的 MCP 和 Session resume 能力 |

provider 的模型名、推理档位和账号可用性以本机安装版本为准。Profile 保存参数并不会替你安装模型、登录账号或授予外部工具权限。

## 定义与参数冲突

Host 将可移植指令、技能和支持的 MCP/工具定义转换为 provider 配置。若 Workspace 要求 provider 不支持的能力，执行前会拒绝；不要把能力错误当作“可以忽略的提示”。详细差异见[支持的 provider](/v2/zh/service/hosted-agent-providers)。

自定义参数以 argv 项传递；工作目录、模型、输出协议、MCP 配置和权限相关的保留参数不能随意覆盖。配置 Codex 原生工具时使用 Profile sandbox/审批语义，不能直接复用 Managed 内置工具策略。
