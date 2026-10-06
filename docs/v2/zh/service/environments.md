---
title: "Environments：配置执行位置"
en_link: /v2/en/service/environments
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Environment 定义 Managed Agent 在哪里执行文件、Shell 等工具，通过 `/api/environments` 管理。它与保存定义的 Workspace 分工不同，也不是 Hosted Agent 的 Runtime Host。控制台对应 **Resources → Environments**，操作入口见 [Console](/v2/zh/service/console/index)。

## 管理 API

使用平台用户 Bearer token 和 `X-AgentScope-Tenant`、`X-AgentScope-Namespace` 请求头，变量准备见[API 快速开始](/v2/zh/service/first-session)。列表按当前身份可检查的资源过滤；读取需要 inspect，修改需要 edit，创建需要空间资源创建权限。

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

例如创建一个 self-hosted 工具执行环境：

```bash
ENVIRONMENT_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/environments" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data '{"name":"Report worker","type":"self_hosted","config":{}}')
ENVIRONMENT_ID=$(printf '%s' "$ENVIRONMENT_JSON" | jq -er '.id')
ENVIRONMENT_KEY=$(printf '%s' "$ENVIRONMENT_JSON" | jq -er '.apiKey')
```

保存 Environment ID 和 key，并按本页后面的命令连接 Worker。其他类型使用下面的类型与配置说明；创建 local 需要部署允许本地执行，否则返回 403。

## 界面导览

<Frame caption="当前控制台截图，使用固定演示数据。">
  <img src="/imgs/service/environments.png" alt="Local 与 self_hosted Environment 示例" />
</Frame>

先按执行位置区分环境，再进入对应配置。图中 **Local development** 用于本机开发，**Research worker** 是 self_hosted 配置示例；创建环境记录后仍需启动并连接对应 Worker。

## 选择类型

| 类型 | 适用方式 |
| --- | --- |
| local | 在 Dataplane 所在环境执行；管理员必须启用 Local |
| sandbox | 使用 E2B 云沙箱隔离 Shell/文件执行 |
| remote | 共享 BaseStore 文件系统，不提供 Shell |
| self_hosted | 通过自行运行的 Worker 提供执行能力 |

Docker 下 Local 指 Dataplane 容器内部，并不是宿主机的任意目录。生产环境按工具隔离和网络需求选择后端。

## 绑定与配置

通过 [Agent 定义 API](/v2/zh/service/managed-agent-configuration)设置 `defaultEnvironmentId`。更新时先读取完整定义，保留其他字段并携带当前定义版本。创建 Managed session 的 `environmentId` 可以覆盖该次会话的环境选择；省略时采用 Agent 默认配置。

后端所需凭据和能力必须与所选 provider 匹配；不要把 Runtime Host 的 enrollment 凭据用于 Worker。保存后建立新 Session，用只读文件操作验证连接、工作目录和权限，再验证写入或执行命令。即使使用 self_hosted，Managed 模型推理仍在 Dataplane 中运行。

`config.memoryAccess` 可以按 Memory Store ID 指定挂载权限，例如 `{"memoryAccess":{"STORE_ID":"read_only"}}`。支持 `read_only`、`read_write`，未配置的 Store 默认可读写；这是已绑定共享知识的运行时访问策略，不会自动绑定 Store。详见 [Memory](/v2/zh/service/memory)。

## Self-hosted 接入

Self-hosted Worker 使用 Environment 的 API key 建立连接。创建 key 时安全保存，按照所用 Worker 的连接配置提供；重连后检查在线状态和一次实际工具调用。Runtime Host 运行 Coding Agent，而 Worker 承载 Managed Agent 的工具执行，两者不是可互换的进程。

## 无法执行时

依次检查环境是否存在且可用、网络和认证、目标目录挂载、工具二进制及权限。模型可以回复不代表文件工具也已配置完成。更新配置后用新任务验证，轮换 key 后更新所有使用者。

下一步：[Managed Agent](/v2/zh/service/managed-agent) · [配置参考](/v2/zh/service/configuration)。

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
