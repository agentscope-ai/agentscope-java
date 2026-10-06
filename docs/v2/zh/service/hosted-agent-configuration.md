---
title: "Hosted 主机与 Runtime 参数"
en_link: /v2/en/service/hosted-agent-configuration
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

`runtimeProfileId` 选择 provider 配置，`runtimePoolId` 选择可承载执行的主机池。通过 `runtime-options` 选择已发现的 Runtime，管理员再维护 Profile 和池。只增加 Host capacity 不等于增加模型额度或消除 Agent/任务策略限制。

## Agent 与运行配置 API

这些接口使用有权管理目标 namespace 的平台账户 Bearer。第一次创建的完整请求见[连接并创建 Agent](/v2/zh/service/connect-hosted-agent)。

| 操作 | API | 关键输入与响应 |
| --- | --- | --- |
| 发现可选 Runtime | `GET /api/v1/agents/runtime-options?tenant=...&namespace=...` | 返回 `runtimes`、`profiles`、`pools`；选项含 `provider`、`runtimeProfileId`、`runtimePoolId`、`hostCount`、`capabilities` |
| 创建 Hosted Agent | `POST /api/v1/agents` | `agentKey`、范围、`binding.kind: "hosted-runtime"`、`binding.configuration` 和 `definition`；返回 `agent`、`binding`、`policy`、`definition` |
| 读取定义 | `GET /api/v1/agents/{agentId}/definition` | 返回 `agentId`、`definition`，保存定义 `version` |
| 更新定义 | `PATCH /api/v1/agents/{agentId}/definition` | `name` 和有效 `version` 必填；提交要保存的定义；返回 `agent`、`definition` |
| 读取运行设置 | `GET /api/v1/agents/{agentId}/hosted-settings` | 返回 `settings`，含绑定、profile/pool、执行覆盖、并发及两个版本号 |
| 更新运行设置 | `PATCH /api/v1/agents/{agentId}/hosted-settings` | 字段见下表；返回更新后的 `settings` |
| 查看定义历史 | `GET /api/v1/agents/{agentId}/versions`、`/versions/{version}` | 已保存的版本列表或单个定义版本 |

创建时 `binding.configuration` 必须包含同一 scope 中的 `runtimeProfileId` 和 `runtimePoolId`，可选 `executionOverrides`。`definition` 与 Managed 使用同一格式，常用 `name`、`system`、`model`、`tools`、`mcpServers`、`skills`、`workspaceId`、`workspaceBinding`。字段能否作用到原生 provider 由其能力决定。更新定义前先读回完整配置并修改所需部分，避免把 PATCH 理解为任意字段都自动保留的浅合并。

### Hosted settings 字段

| 字段 | 用法 |
| --- | --- |
| `bindingVersion` | 必填，来自读取结果，用于并发更新检查 |
| `policyVersion` | 更新时带回当前策略版本，避免覆盖他人修改 |
| `runtimeProfileId`、`runtimePoolId` | 可选切换运行配置或主机池；省略时保留当前值 |
| `executionOverrides` | 可选，包含 `reasoningEffort`、`serviceTier`、`providerConfiguration`、`customArgs`；省略时保留当前覆盖 |
| `maxConcurrency` | 可选，0–50；0 不设置 Agent 级并发上限，执行仍受 Host 和其他策略限制 |

`providerConfiguration` 是对象，`customArgs` 是 argv 字符串数组；参数合法性与保留选项按 provider 检查。不要修改共享 Runtime Profile 来实现某一个 Agent 的专属偏好。

下面只调整并发，`AGENT_ID` 为已创建的 Agent UUID，`SERVICE_URL` 和 `TOKEN` 为管理连接配置：

```bash
curl -sS "$SERVICE_URL/api/v1/agents/$AGENT_ID/hosted-settings" \
  -H "Authorization: Bearer $TOKEN" > hosted-settings.json

jq '{bindingVersion: .settings.bindingVersion,
     policyVersion: .settings.policyVersion,
     maxConcurrency: 2}' hosted-settings.json > hosted-settings-update.json

curl -sS -X PATCH "$SERVICE_URL/api/v1/agents/$AGENT_ID/hosted-settings" \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  --data-binary @hosted-settings-update.json
```

遇到版本冲突时重新读取并合并变更，再提交。

### 管理共享 Profile 与 Pool

`GET /api/v1/runtime-profiles` 和 `/runtime-pools` 使用 `tenant`、`namespace` 查询参数，返回 `items`。单个资源用 `/runtime-profiles/{name}` 或 `/runtime-pools/{name}` 查询，返回 `profile` 或 `pool`。

| 保存 API | JSON 字段 |
| --- | --- |
| `POST /api/v1/runtime-profiles` 或 `PUT /api/v1/runtime-profiles/{name}` | `tenant`、`namespace`、`name`、`provider`、可选 `runtime`、`configuration`、`requirements` |
| `POST /api/v1/runtime-pools` 或 `PUT /api/v1/runtime-pools/{name}` | `tenant`、`namespace`、`name`、可选 `hostSelector`、`configuration` |

PUT 路径中的 `name` 决定更新对象。提交完整的期望配置，成功后保存响应中的 UUID 供 Agent binding 引用。主机容量、暂停接收新工作及 enrollment 接口见 [Runtime Host API](/v2/zh/service/runtime-host#主机接入与管理-api)。

## Provider 参数

以下是 Runtime Profile `configuration` 中的字段，不是 `connect` 参数，也不是所有 provider 通用的表单。

| Provider | 常用字段 | 说明 |
| --- | --- | --- |
| Codex | `model`, `profile`, `sandbox`, `reasoningEffort`, `serviceTier`, `skipGitRepoCheck` | 通过 app-server 启动/恢复线程；默认 sandbox 为 `workspace-write`，审批策略由适配器接入 |
| Claude Code | `model`, `permissionMode`, `allowedTools`, `disallowedTools`, `maxTurns`, `appendSystemPrompt`, `reasoningEffort` | 按 CLI 支持的参数配置；工具名称按 provider 的命名使用 |
| Qoder | `model`, `reasoningEffort`, `contextWindow`, `permissionMode`, `allowedTools`, `disallowedTools`, `maxTurns`, `maxOutputTokens`, `strictMCPConfig`, `appendSystemPrompt`, `agent` | 配合实际安装版本和任务确认流程 |
| QwenPaw | `agent`, `model`, `permissionMode`, `runtimeProvider`, `localDiagnostics` | 使用 ACP Session 与权限请求 |
| OpenClaw | `model`, `fallbacks`, `thinking`, `codeMode`, `timeoutSeconds`, `localModelLean`, `isolated`, `authEnvOnly`, `reasoningEffort` | 使用 `agent exec`；不具备本适配器的 MCP 和 Session resume 能力 |

provider 的模型名、推理档位和账号可用性以本机安装版本为准。Profile 保存参数并不会替你安装模型、登录账号或授予外部工具权限。

## 定义与参数冲突

Host 将可移植指令、技能和支持的 MCP/工具定义转换为 provider 配置。若 Workspace 要求 provider 不支持的能力，执行前会拒绝；不要把能力错误当作“可以忽略的提示”。详细差异见[支持的 provider](/v2/zh/service/hosted-agent-providers)。

自定义参数以 argv 项传递；工作目录、模型、输出协议、MCP 配置和权限相关的保留参数不能随意覆盖。配置 Codex 原生工具时使用 Profile sandbox/审批语义，不能直接复用 Managed 内置工具策略。
