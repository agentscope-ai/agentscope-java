---
title: "执行环境"
description: "通过 Hands 在本地、Service 管理的 ACS 沙箱或用户已有实例中执行 Session 工具。"
en_link: /v2/en/service/environments
---

Environment 决定 Managed Agent 的文件和 Shell 工具在哪里执行。HarnessAgent 在 Dataplane 推理，Hands 执行工具并将结果送回原 Turn。Worker 凭据决定它能访问哪个 Environment，`config.type` 决定执行模式；使用平台 token 启动 Hands 并不意味着必须采用 cloud。

## 选择执行模式

| `config.type` | 执行位置 | 资源归属 |
| --- | --- | --- |
| `local` | Hands 容器或主机内执行 Shell 和文件工具 | 不创建远端 Sandbox |
| `cloud` | Hands 为每个 Session 创建并复用 ACS Sandbox | Service 负责创建、TTL 续期和清理 |
| `self_hosted` | Hands 连接用户已有的 E2B 兼容实例 | 用户负责创建、存活时间和删除 |
| `remote` | 现有分布式文件系统接入 | 仅文件系统，不属于本页 Hands 流程 |

local 按 Session 区分工作目录，但共用 Hands 的操作系统。管理员必须启用 `BUILDER_ALLOW_LOCAL_ENVIRONMENT=true`。cloud 本轮支持 ACS 原生 E2B 路由，不支持 `/kruise/...` 私有路由。按 [ACS 接入文档](https://help.aliyun.com/zh/cs/user-guide/connect-to-agent-sandbox-using-the-e2b-sdk) 准备 SandboxManager、模板、DNS/TLS 和租户 API key；模板需要 `/bin/bash`、`python3` 和 `tar`，供 Hands 工具及工作目录归档使用。

self_hosted 要求 Hands 能访问用户的 SandboxManager 和执行端。用户在 Environment 中提供 Sandbox API key，不会回退使用平台 cloud 凭据。连接已有实例时只查询实例，不续期、不重建、不用归档覆盖文件，也不删除实例；用户负责保持它存活。已有实例保留给一个 Session；删除空闲 Session 会释放绑定，但不会删除实例。Hands 不会自动把 Agent 定义文件写入用户实例。后续通过这个 Session 创建新 Turn，不应将相同实例再绑定给第二个 Session。

## 1. 创建 Environment

使用实际 Gateway 地址；当前本地 portal 部署使用 18081 端口。下列命令按免 API 鉴权的本地开发模式编写；启用鉴权的部署，需要在每条公开 API 请求中加上已授权的用户或应用请求头。

```bash
export BASE_URL="http://localhost:18081"
curl -sS --fail-with-body "$BASE_URL/actuator/health"
```

在控制面启用 Local 后，创建本地执行环境：

```bash
ENVIRONMENT_JSON=$(curl -sS --fail-with-body "$BASE_URL/api/environments" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Hands local demo","config":{"type":"local"}}') || exit 1
export ENVIRONMENT_ID=$(jq -er '.id' <<< "$ENVIRONMENT_JSON")
export ENVIRONMENT_KEY=$(jq -er '.apiKey' <<< "$ENVIRONMENT_JSON")
```

cloud 使用下面的请求体。`templateId` 是 ACS SandboxSet 对应的模板名；省略 `apiBaseUrl` 时使用 `https://api.<domain>`。可以设置 `config.apiKey`，也可以在启动 Dataplane 前设置平台 cloud 的备用凭据 `BUILDER_E2B_API_KEY`。

```json
{
  "name": "ACS cloud",
  "config": {
    "type": "cloud",
    "provider": "acs",
    "domain": "sandbox.example.com",
    "templateId": "code-interpreter",
    "workspaceRoot": "/home/user",
    "sandboxTimeoutSeconds": 300
  }
}
```

self_hosted 使用用户自己的连接参数与 key。这里的 key 是 Sandbox 服务商凭据，与 Service 返回的 Environment key 不同。

```json
{
  "name": "User Sandbox",
  "config": {
    "type": "self_hosted",
    "provider": "e2b",
    "apiBaseUrl": "https://api.user-sandbox.example.com",
    "domain": "user-sandbox.example.com",
    "apiKey": "USER_SANDBOX_TENANT_KEY",
    "workspaceRoot": "/home/user"
  }
}
```

专用实例可以填写 `config.sandboxId`，也可以在创建 Session 时传入 `agentscope.sandbox_id`，后者优先。用户已有的 ACS 实例也支持 `provider: acs`。直接配置凭据属于 AgentScope 扩展，Environment 配置应仅向已授权的操作者开放。

## 2. 启动 Hands

构建当前源码或使用配套 Hands 镜像。Compose profile 使用平台身份启动 Worker，它按所配置 Environment 的模式执行，支持上述三种模式：

```bash
# Set BUILDER_HANDS_ENVIRONMENT_ID in the deployment env file first.
# Run from agentscope-service with the same env file and project options as your stack.
docker compose --env-file deploy/.env -f deploy/compose.yaml --profile hands up -d hands
```

`/data/hands` 需要持久化保存 Worker 日志，`/data/workspaces` 用于共享 cloud 归档。Helm 可通过 `hands.enabled=true` 和 `hands.environmentID` 启动相同 Worker；cloud 多副本需要共享归档存储。

用户侧 Worker 可以用 Environment key 领取 local 或 self_hosted 任务，不能领取 cloud 任务或获取平台 cloud 凭据。应在可以访问用户实例的网络中启动：

```bash
java -jar agentscope-service/service-hands/target/service-hands-2.1.0-SNAPSHOT.jar \
  --base-url "$BASE_URL" --environment-id "$ENVIRONMENT_ID" \
  --environment-key "$ENVIRONMENT_KEY" \
  --hands-root /tmp/agentscope-hands-demo --worker-id demo-hands
```

将 JAR 版本替换为实际构建产物。每个 Worker 配置一个 Environment ID，不同进程使用独立日志目录；local 工作目录固定在其 Worker 上。两种身份都根据 Environment 选择执行方式。

## 3. 创建 Agent 和 Session

按 [创建第一个 Agent](/v2/zh/service/create-managed-agent) 创建 Managed Agent，选择此 Environment，启用 `bash`、`read` 和 `write` 工具配置。它们映射为 Hands 的 execute、read_file 和 write_file 等调用。将 Agent ID 保存为 `AGENT_ID`。

```bash
SESSION_JSON=$(jq -n --arg agent "$AGENT_ID" --arg environment "$ENVIRONMENT_ID" \
  '{agent:$agent,environment_id:$environment,title:"Sandbox demo"}' \
  | curl -sS --fail-with-body "$BASE_URL/v1/sessions" \
      -H 'Content-Type: application/json' --data-binary @-) || exit 1
export SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
export SESSION_URL="$BASE_URL/v1/sessions/$SESSION_ID"
```

self_hosted Environment 未指定 `config.sandboxId` 时，在创建请求中加入以下扩展：

```json
"agentscope": { "sandbox_id": "EXISTING_SANDBOX_ID" }
```

创建 Environment 成功不代表 Hands 已连接或后端可达。创建 self_hosted Session 会保留实例绑定，重复绑定返回 409。Session 使用冻结的执行配置，Environment 的执行配置修改对新 Session 生效。

## 4. 测试工具、长任务和中断

在另一个终端使用相同 `SESSION_URL` 和鉴权信息打开事件流：

```bash
curl -N -sS --fail-with-body "$SESSION_URL/events/stream"
```

发送包含写文件和读文件操作的任务：

```bash
curl -sS --fail-with-body "$SESSION_URL/events" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: sandbox-file-001' \
  -d '{"events":[{"type":"user.message","content":[{"type":"text","text":"Use execute to run uname -a. Write the result to platform.txt, read it back, and report the contents."}]}]}' | jq
```

在事件流或历史中确认工具调用、工具结果和最终回复。local 模式可查看 Hands 内的 `/data/hands/workspaces`；cloud 或 self_hosted 模式应查看所绑定的远端实例，确认命令确实在那里执行。

可以通过已授权的工具任务视图查询 cloud 或用户实例的 ID：

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions/$SESSION_ID/tool-tasks" \
  | jq '.data[] | {task_id, environment_type, sandbox_id, status}'
```

换一个 Idempotency-Key 发送：`调用 execute 执行 sleep 60; printf done，timeout 设为 120，等待完成后报告输出。` 无输出的命令不应在 15 秒后提前失败。流式执行由工具执行期限控制，与管理 API 的短 HTTP 超时区分。

测试中断时，发送更长的命令，看到工具调用后执行：

```bash
curl -sS --fail-with-body "$SESSION_URL/events" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: sandbox-stop-001' \
  -d '{"events":[{"type":"user.interrupt"}]}' | jq
```

检查远端进程列表或测试心跳文件，确认执行确实停止。Hands 发送进程终止请求并检查进程是否消失，不会删除用户实例。如果无法确认终止，任务保持 unknown，执行绑定继续受保护，等待恢复；事件流断开不能证明进程已退出。

用新的 key 发送下一条 user.message，会在相同 Session 和工作目录中开启新 Turn；自动工具结果则继续原来等待的 Turn。重复使用同一批次 key 是重试接收操作，不会提交另一条新消息。

## 配置与恢复边界

| 字段 | 范围与行为 |
| --- | --- |
| `config.type` | local、cloud 或 self_hosted；也接受顶层 type，但两者必须一致 |
| `provider` | cloud 为 acs；self_hosted 为 e2b 或 acs |
| `domain` / `apiBaseUrl` | 原生 E2B 执行域名 / 管理 API 地址 |
| `envdBaseUrl` | 可选固定执行代理，通过 Sandbox ID 请求头路由 |
| `templateId` | cloud 必填，由 ACS 配置提供 |
| `sandboxId` | self_hosted 可选的已有实例 |
| `workspaceRoot` | 远端工作目录，默认 /home/user |
| `runUser` / `codec` | 远端用户和 Connect 流编码，默认 user / PROTO |
| `readTimeoutSeconds` | 管理客户端参数；进程流使用执行期限 |
| `sandboxTimeoutSeconds` | cloud TTL，Hands 限制在 120–3600 秒 |
| `sandboxIdleTimeoutSeconds` / `sandboxMaxLifetimeSeconds` | cloud 清理阈值，默认 300 / 3600 秒 |

cloud 当前在获取确定工具结果后将工作目录保存为 TAR。设置 `persistenceMode` 不会启用服务商内存快照或休眠唤醒。归档恢复只恢复文件，不恢复运行进程。结果不确定的写操作和远端执行不会自动重跑；Worker 重启后的已保存结果重投与实际执行分开。

升级会将旧 sandbox Environment 迁移为 cloud，将旧的本地执行 self_hosted Environment 迁移为 local；旧加密运行配置在读取时归一化。执行迁移任务前先备份数据库并停止旧 Worker，随后启动全部更新后的组件及 Hands。本地 Compose 覆盖配置会启用 Local；源码和配置更新本身不会升级正在运行的容器。
