---
title: "计划与事件触发"
en_link: /v2/en/service/automation
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Automation 将“执行什么”和“何时触发”分开保存。同一份日报 Runbook 可以由工作日定时触发，也可以在收到外部事件时执行。每次触发留下 Delivery 或 Run 记录，业务应用可查询是否接收、是否执行以及交付结果。

配置规则时，可以选择 Agent 或 Team 作为执行目标，再决定由手动请求、Cron 计划还是 Webhook 事件发起工作。如果工作需要固定步骤，应按 [Workflow](/v2/zh/service/workflows) 定义流程，并通过 [Session API](/v2/zh/service/service-api) 调用已发布的 revision。当前 Automation 还不能直接触发 Workflow，来自消息平台的工作则需要单独[接入消息渠道](/v2/zh/service/channels)，不能将 Channel 配置为 Automation 的触发器。

## 创建日报规则

以下示例使用 Bash、`curl` 和 `jq`。先按[认证与空间](/v2/zh/service/api-reference#认证与空间)准备 `SERVICE_URL`（Service 地址）、`TOKEN`（用户 Bearer token）、`TENANT`、`NAMESPACE`，并定义请求函数：

```bash
api() {
  curl --fail-with-body --silent --show-error \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H 'Content-Type: application/json' "$@"
}
```


设置 `AGENT_ID` 为日报 Agent。下面创建一条暂不启用的规则，同时定义工作日计划和 Webhook；先验证一次执行，再开启自动触发。

```bash
created=$(api "$SERVICE_URL/api/v1/automations" --data "$(jq -n \
  --arg tenant "$TENANT" --arg namespace "$NAMESPACE" --arg agent "$AGENT_ID" \
  '{tenant:$tenant,namespace:$namespace,name:"项目日报",enabled:false,
    execution:{runbook:"汇总 Workspace 中的项目进展，标明来源和待确认事项。",
      assigneeType:"agent",assigneeRef:$agent,outputMode:"create_issue",
      completionPolicy:"review",concurrencyPolicy:"skip",
      queueTimeoutSeconds:3600,runTimeoutSeconds:3600},
    triggers:[{type:"cron",enabled:true,schedule:"0 9 * * 1-5",timezone:"Asia/Shanghai"},
      {type:"webhook",enabled:true,events:["build.completed"]}]}')")
AUTOMATION_ID=$(jq -r '.automation.id' <<<"$created")
TRIGGER_ID=$(jq -r '.automation.triggers[] | select(.type=="webhook") | .id' <<<"$created")
AUTOMATION_SECRET=$(jq -r '.webhookSecret' <<<"$created")
```

`execution.runbook` 是交给 Agent 的工作说明。使用 Team 时将负责人类型改为 `team`；运行环境和工具来自目标本身。`outputMode:"create_issue"` 保留可协作、验收的工作项；`run_only` 用于只通过自动化执行查看结果，并采用自动完成策略。

`webhookSecret` 只在创建或轮换时返回，保存到发送方的凭据配置。创建响应中的 trigger ID 也应保留，后续更新 triggers 时复用原 ID，保持触发器身份稳定。

## 核对计划并试运行

计划必须明确时区。预览返回未来五次执行时间：

```bash
api "$SERVICE_URL/api/v1/automations/schedule-preview" \
  --data '{"schedule":"0 9 * * 1-5","timezone":"Asia/Shanghai"}'
tested=$(api "$SERVICE_URL/api/v1/automations/$AUTOMATION_ID/trigger?test=true" \
  -H 'Idempotency-Key: digest-test-001' --data '{"project":"example"}')
AUTOMATION_RUN_ID=$(jq -r '.run.id' <<<"$tested")
api "$SERVICE_URL/api/v1/automations/$AUTOMATION_ID/runs/$AUTOMATION_RUN_ID"
```

Test run 会实际调用模型和工具，可以用于尚未启用的规则。普通手动执行使用同一 `/trigger` 路径但不加 `test=true`。响应的 `run.id` 用于后续查询，不能把 HTTP 202 当作工作完成。

读完执行结果及产物后，再用当前版本启用：

```bash
current=$(api "$SERVICE_URL/api/v1/automations/$AUTOMATION_ID")
api "$SERVICE_URL/api/v1/automations/$AUTOMATION_ID" -X PATCH \
  --data "$(jq -n --argjson version "$(jq '.automation.version' <<<"$current")" \
  '{enabled:true,expectedVersion:$version}')"
```

更新 Runbook、目标和触发器也使用 PATCH 与 `expectedVersion`；遇到冲突先重新读取。规则与对应 trigger 都启用后，计划或 Webhook 才能自动派发。

## 接收外部 Webhook

外部系统向下面的地址提交 JSON 对象或数组。该入口使用 `X-Automation-Secret`，不使用用户 Bearer token：

```bash
curl --fail-with-body "$SERVICE_URL/hooks/v1/automations/$AUTOMATION_ID/$TRIGGER_ID" \
  -H 'Content-Type: application/json' \
  -H "X-Automation-Secret: $AUTOMATION_SECRET" \
  -H 'Idempotency-Key: build-001' -H 'X-Event-Type: build.completed' \
  --data '{"project":"example","commit":"COMMIT_SHA","result":"passed"}'
```

重传同一事件使用相同 key 和内容，新事件使用新 key。`events` 为空时接收全部事件；也可以通过 payload 的 `event` 字段提供事件类型。输入会作为 `Trigger data` 加入工作说明，应在 Runbook 中约定字段含义、资料读取方式与缺失信息处理。

这里的入站 Webhook 用于让外部系统触发 Service 工作。需要 Service 把结果通知你的后端时，应注册 [Session 或 Turn Webhook](/v2/zh/service/sse-events#webhooks)。两种通知方向不同，地址和认证方式也不同；外部平台无法提供必需 header 时，可由业务后端适配。

## 查询结果、控制积压与重试

```bash
api "$SERVICE_URL/api/v1/automations/$AUTOMATION_ID/deliveries?limit=25"
api "$SERVICE_URL/api/v1/automations/$AUTOMATION_ID/runs?limit=25"
api "$SERVICE_URL/api/v1/automations/$AUTOMATION_ID/runs/$AUTOMATION_RUN_ID"
```

Delivery 说明事件收到、过滤或拒绝的情况；Run 记录实际执行的 `status`、`waitReason`、输入输出和错误，详情还关联 Issue、任务及 Artifact。`review` 工作完成计算后仍可能等待人工验收，按[验收 API](/v2/zh/service/issues#inbox)继续。

`concurrencyPolicy:"skip"` 在已有运行占用时跳过新触发，`queue` 依次排队。`queueTimeoutSeconds` 限制排队等待，`runTimeoutSeconds` 限制执行，两者允许 60～604800 秒。关闭规则只停止后续触发；停止已有运行使用 `POST /api/v1/automations/{id}/runs/{runId}/cancel`。

失败后可调用 `/runs/{runId}/rerun`；重放某次事件使用 `/deliveries/{deliveryId}/replay`，两者均提交新的 `Idempotency-Key`，并保留来源关联。先查看失败原因再重试，因为重新执行可能重复外部副作用。轮换密钥调用 `/rotate-secret`，传入 `expectedVersion` 并更新发送方。

控制台的配置和执行记录入口见[控制台：自动化与渠道](/v2/zh/service/console/index#console-automation)。
