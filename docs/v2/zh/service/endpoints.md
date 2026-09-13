---
title: "Endpoint：把 Agent 能力接入应用"
---

[English](/v2/en/service/endpoints)

Endpoint 是提供给应用调用的稳定入口，将 Agent、Team 或已发布 Workflow revision 包装为具有认证、输入输出 schema 和调用记录的服务。调用方无需了解内部调度和运行时地址。

## 选择调用形式

| 形式 | 用途 | 请求入口 |
| --- | --- | --- |
| conversation | 支持会话能力的目标，多轮交互 | `/invoke/v1/endpoints/{slug}/conversations` |
| job | 一次可跟踪的工作，适用于 Agent、Team、Workflow | `/invoke/v1/endpoints/{slug}/jobs` |

在目标详情的 Endpoint 发布区域创建入口，设置 slug、模式、schema、认证、超时和请求大小限制。发布前查看 Readiness，处理目标缺失或能力不匹配。不要强行将只支持 job 的 Team/Workflow 作为会话目标。

## 发布与凭据

Draft 不接收正式调用；Publish 后产生可用发布版本。选择 `api_key` 时创建调用凭据，并以 `X-API-Key` 发送；选择 `platform` 时使用 `Authorization: Bearer`。两种凭据用途不能互换。

把 key 保存在应用后端的 secret 配置中。为不同调用方建立独立凭据，按需要设置到期时间、轮换或撤销。浏览器前端应调用你自己的后端，不嵌入长期 key。

## 发起一个 job

从 Endpoint 详情复制生成的调用示例。下面假设你的 slug 为 `report`，input schema 允许 `request` 字段；实际调用必须匹配发布的 schema：

先将 `BASE_URL` 设为 Gateway 的公开 origin（例如 `https://agentscope.example.com`，末尾不带斜杠），`ENDPOINT_TOKEN` 设为该入口的调用凭据。

```bash
curl --fail-with-body "$BASE_URL/invoke/v1/endpoints/report/jobs" \
  -H "X-API-Key: $ENDPOINT_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: report-order-001' \
  --data '{"title":"Prepare report","description":"Summarize the supplied material","input":{"request":"List the open questions"}}'
```

响应提供 `invocationId`、`status`、`statusUrl`、`eventsUrl`。保存这些值，使用返回的 URL 查询状态或订阅事件，不自行拼接假定的内部 Run 地址：

```bash
curl --fail-with-body "$BASE_URL$STATUS_PATH" -H "X-API-Key: $ENDPOINT_TOKEN"
curl -N --fail-with-body "$BASE_URL$EVENTS_PATH" -H "X-API-Key: $ENDPOINT_TOKEN" -H 'Accept: text/event-stream'
```

`STATUS_PATH` 和 `EVENTS_PATH` 填返回的相对 URL；如响应为绝对 URL，直接使用，不再拼接 BASE_URL。终态查询中的 `invocation.result` 承载结果，按该 Endpoint 的 output schema 读取。

## 多轮 conversation

首次请求 body 为 `{"message":"请介绍你的职责"}`。保存 `conversationId`；下一轮发送到 `/invoke/v1/conversations/{conversationId}/turns`，仍使用 `{"message":"..."}` 和新的 Idempotency-Key。订阅每轮响应返回的 eventsUrl，保留其中的 invocationId 查询参数。

## 重试和状态

同一业务请求超时重传时使用相同 Idempotency-Key 和内容；用户明确发起的新工作使用新 key。accepted、dispatching、running、waiting 都不是完成。只有 completed 表示成功终态；failed、cancelled、timed_out 应交给对应错误处理。

SSE 断开不代表任务失败，先按 statusUrl 查询。遇到 401/403 检查认证和权限，429 按返回的限流提示退避，输入错误先修复 schema；不要无条件换 key 重试，防止重复业务副作用。

## 更新版本

发布新的 Endpoint release 切换目标版本，保留发布记录。Rollback 用于把入口指回此前 release，不回滚数据库或已经执行的外部操作。Disable 停止新的使用；需要取消某次工作时查看具体 invocation/执行状态。

相关：[API 参考](/v2/zh/service/api-reference) · [Workflow](/v2/zh/service/workflows)。
