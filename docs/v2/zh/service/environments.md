---
title: "Environments：配置执行位置"
description: "准备工具执行后端，将 Environment 设为 Agent 默认资源或在创建 Session 时选择，并验证实际执行位置。"
en_link: /v2/en/service/environments
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Environment 为 Managed Agent 选择文件、Shell 等工具的执行后端。创建 Environment 后，还需要把它的 ID 保存到 Agent 定义的 `defaultEnvironmentId`，或在创建 Session 时通过 `environmentId` 指定。前者为该 Agent 的新会话提供默认值，后者只为这次会话选择执行位置。工具是否启用和是否需要确认，仍由 [Agent 工具配置](/v2/zh/service/tools)决定。

Environment 与 [Workspace](/v2/zh/service/workspaces) 一起参与执行，但分工不同：Workspace 提供指令、Skills 和工具定义，Environment 决定文件和命令实际在哪里执行。即使使用 self_hosted Worker，Managed Agent 的模型调用和推理仍由 Dataplane 承担。Hosted Agent 的 Runtime Host 则负责运行另一类 Agent 运行时，不能用它的注册凭据代替 Environment Worker 凭据。

下面沿用[API 身份准备](/v2/zh/service/create-managed-agent#api-setup)中的平台身份变量，并使用[第一个托管 Agent](/v2/zh/service/create-managed-agent)的 `AGENT_ID` 验证文件工具。如果已有合适的 Environment，可以直接取得它的 ID，跳到“绑定与配置”。自行创建时，需要同时准备对应的执行后端；资源创建成功不代表 Worker 已经上线或沙箱凭据已经可用。

## 选择类型

| 类型 | 适用方式 |
| --- | --- |
| local | 在 Dataplane 所在环境执行；管理员必须启用 Local |
| sandbox | 使用 E2B 云沙箱隔离 Shell/文件执行 |
| remote | 共享 BaseStore 文件系统，不提供 Shell |
| self_hosted | 通过自行运行的 Worker 提供执行能力 |

Docker 下 Local 指 Dataplane 容器内部，并不是宿主机的任意目录。生产环境按工具隔离和网络需求选择后端。

## 创建执行环境

下面创建一个 `self_hosted` Environment。Service 返回资源 ID 和只显示一次的 `apiKey`；ID 用于 Agent 或 Session 的资源选择，key 用于 Worker 连接平台。两者与执行管理 API 的用户 `TOKEN` 用途不同。

```bash
ENVIRONMENT_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/environments" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data '{"name":"Report worker","type":"self_hosted","config":{}}')
ENVIRONMENT_ID=$(printf '%s' "$ENVIRONMENT_JSON" | jq -er '.id')
ENVIRONMENT_KEY=$(printf '%s' "$ENVIRONMENT_JSON" | jq -er '.apiKey')
```

请保留 `ENVIRONMENT_ID` 和 `ENVIRONMENT_KEY`，并继续启动下面的 Worker。若选择其他类型，应按该类型准备执行后端；Local 需要管理员允许本地执行，Sandbox 需要可用的 E2B 配置。修改 JSON 中的 `type` 不会替你完成这些准备。

<span id="self-hosted-接入"></span>

## 从发布镜像运行 self-hosted Worker

创建 self_hosted Environment 并保存 API key。设置下列变量：`SCHEDULER_IMAGE` 为发布清单中的 scheduler 镜像全名，`BASE_URL` 为 Worker 可访问的 Gateway URL，`ENVIRONMENT_ID` 和 `ENVIRONMENT_KEY` 为刚创建环境的值。

```bash
docker run --rm \
  --name agentscope-hands \
  -v agentscope-hands:/data \
  --entrypoint java "$SCHEDULER_IMAGE" \
  -Dloader.main=io.agentscope.builder.worker.HandsWorkerMain \
  -cp /app.jar org.springframework.boot.loader.launch.PropertiesLauncher \
  --base-url "$BASE_URL" \
  --environment-id "$ENVIRONMENT_ID" \
  --environment-key "$ENVIRONMENT_KEY" \
  --hands-root /data/hands \
  --worker-id hands-1
```

该进程只向 Gateway 发起出站请求，不需要暴露 Worker 端口。将 Managed Agent 绑定到此 Environment，发起一个读取并写回小文件的任务，观察工具挂起后由 Worker 回传结果并继续。Worker 工作目录位于命名卷中；任务需要的系统程序应预装到你的 Worker 镜像。

正式运行由你的进程或容器管理器负责重启，多个 Worker 使用不同 worker ID。停止前检查已领取的工作，避免将停止进程误认为已取消业务任务。

## 绑定与配置

### 为一个 Session 选择环境

Worker 或其他执行后端就绪后，下面的请求把 `ENVIRONMENT_ID` 传给新 Session。它不会修改 Agent 的默认环境，因此适合验证一个新环境，或让同一个 Agent 在不同环境中处理各自的任务。请求中的资源 ID 必须对当前身份可用。

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg agent "$AGENT_ID" --arg env "$ENVIRONMENT_ID" \
    '{target:{type:"agent",id:$agent},environmentId:$env}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
printf '%s' "$SESSION_JSON" | jq '{id, target, environmentId}'
```

### 设为 Agent 的默认环境

如果希望这个 Agent 后续的新会话默认使用该环境，把下面的内容写入 `resource-defaults.json`，再按[设置 Agent 的默认资源](/v2/zh/service/managed-agent-configuration#设置-agent-的默认资源)中的完整 GET/PATCH 步骤保存。该步骤会保留 Agent 的工具、指令及其他资源绑定，并携带当前定义版本，避免只修改环境时清空其他配置。

```bash
jq -n --arg env "$ENVIRONMENT_ID" '{defaultEnvironmentId:$env}' > resource-defaults.json
```

保存后，新 Session 省略 `environmentId` 就会使用该默认值；显式传入其他 ID 时，只覆盖本次会话的环境。修改默认值不会把已有 Session 移到新环境。已有 Managed Session 可以通过自身的 PATCH 接口调整 `environmentId`，建议在当前任务结束后操作，并验证后续任务；切换绑定不会自动复制原环境中的工作文件。

### 检查实际执行

创建 Session 只记录环境选择，还没有执行文件工具。使用[第一个托管 Agent](/v2/zh/service/create-managed-agent)已经启用的读写工具，向 `$SESSION_URL/turns` 提交写入 `environment-check.txt` 并读回的任务，再检查快照和事件中的工具记录。文件应位于所选后端的工作目录，而不是运行 curl 的终端目录。对于 Worker，还应核对它领取和回传工具任务的记录。

如果 Agent 可以回复但文件操作失败，先查看 Session 返回的 `environmentId`，再检查对应 Worker 或沙箱的连接、目录挂载、程序依赖和权限。仅更换 Agent 指令不能修复后端缺失的文件或程序。所需工具没有启用时，则按[工具指南](/v2/zh/service/tools)修改 Agent 定义，并创建新 Session 验证。

## E2B sandbox 配置示例

管理员先在部署配置中提供 `BUILDER_E2B_API_KEY`。创建 sandbox 环境后，Config 可使用：

```json
{
  "templateId": "base",
  "isolationScope": "SESSION",
  "sandboxTimeoutSeconds": 300
}
```

需要额外程序时选择包含这些依赖的自定义 E2B template。`workspaceRoot` 设置沙箱工作路径；`persistenceMode` 可选择 `TAR` 或 `NATIVE_SNAPSHOT`，按模板与后端能力验证保存和恢复。remote 类型只有文件系统能力，不能作为远程 Shell Worker 使用。

### Sandbox 参数

下列字段填写在 Environment 的 Config 中；未填写的 E2B 连接项沿用管理员的部署配置。

| 字段 | 含义与缺省行为 |
| --- | --- |
| `templateId` | E2B 模板；没有部署覆盖时使用 `base` |
| `workspaceRoot` | 沙箱工作路径；没有部署覆盖时使用 `/home/user` |
| `sandboxTimeoutSeconds` | 沙箱存活超时秒数；没有部署覆盖时使用 300 |
| `isolationScope` | Harness 文件系统隔离范围，默认 `SESSION` |
| `persistenceMode` | `TAR` 或 `NATIVE_SNAPSHOT`；默认 TAR，可由部署覆盖 |
| `apiBaseUrl` / `domain` | 自定义 E2B 接入地址；通常沿用部署配置 |
| `apiKey` | 按环境覆盖 E2B 认证；通常由管理员统一配置 |

Config 中填写 `packages`、Docker 镜像或网络参数不会自动安装依赖或落实网络限制。所需程序应准备在 E2B template 中，网络策略由实际后端配置。这些 sandbox 参数不用于配置 local 或 self_hosted 的容器。

## 界面导览

<Frame caption="当前控制台截图，使用固定演示数据。">
  <img src="/imgs/service/environments.png" alt="Local 与 self_hosted Environment 示例" />
</Frame>

在 **Resources → Environments** 创建和维护执行环境，然后在 Managed Agent 的 **Runtime configuration → Session defaults → Default environment** 选择它。保存后创建新会话，核对环境选择再提交任务。资源页面和 Agent 页面分别负责维护后端与选择默认值，因此只完成资源创建还不能确认目标 Agent 正在使用它。

## 无法执行时

排查时先区分资源关联错误和后端执行错误。前者通常需要检查 Agent 默认值、Session 显式选择和当前账号的资源权限；后者需要检查 Worker 在线状态、沙箱凭据、目标目录及工具依赖。环境归档、删除或配置更新可能影响其他引用者，维护前应检查使用情况；它们都不能替代 Turn 的取消接口。

Environment 的 `config` 是资源级配置，PATCH 会替换整个对象，因此更新前应读取并保留其他字段。它没有像 Workspace 那样的发布版本；修改同一个 Environment 时，不应把已有 Session 的固定 Agent 版本理解为对后端配置的隔离。验证配置变更时优先使用新 Session，轮换 Worker key 后同步更新所有连接者。

`config.memoryAccess` 接受按 Store ID 配置的 `read_only` 或 `read_write`，但当前 Managed HarnessAgent 运行路径对共享知识统一使用只读挂载。设置 `read_write` 不会让该路径获得共享知识写入能力，也不会自动绑定 Store。知识读取与管理 API 更新的区别见 [Memory](/v2/zh/service/memory#读取与写入权限)。

## 管理 API

使用平台用户 Bearer token 和 `X-AgentScope-Tenant`、`X-AgentScope-Namespace` 请求头，变量准备见[API 身份准备](/v2/zh/service/create-managed-agent#api-setup)。列表按当前身份可检查的资源过滤；读取需要 inspect，修改需要 edit，创建需要空间资源创建权限。

| 操作 | API | 参数与响应 |
| --- | --- | --- |
| 列表 | `GET /api/environments` | 返回数组；可用 `limit`（1–500）、`offset`（非负，须同时提供 limit）；总数在 `X-Total-Count` |
| 创建 | `POST /api/environments` | `name`、`type`、可选 `config`；返回 Environment 和只显示一次的 `apiKey` |
| 详情 | `GET /api/environments/{id}` | `id`、`name`、`type`、`config`、`ownerId`、`archivedAt`、时间戳；不返回 key |
| 更新 | `PATCH /api/environments/{id}` | 可选 `name`、`config`；config 整体替换，type 不可变 |
| 归档 | `POST /api/environments/{id}/archive` | 返回带 `archivedAt` 的 Environment；不再出现在活动列表 |
| 轮换 key | `POST /api/environments/{id}/rotate-key` | 返回新的 `apiKey`，旧 key 随即失效 |
| 删除 | `DELETE /api/environments/{id}` | 返回 204 |

这些 Environment 修改接口没有版本条件参数。更新 config 前先读取并保留其他需要的设置；归档后的资源不能继续 PATCH。删除或归档前检查 Agent 与 Session 的使用情况，资源维护不是取消正在运行任务的接口。
