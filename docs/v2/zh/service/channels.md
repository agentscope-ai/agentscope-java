---
title: "接入消息渠道"
en_link: /v2/en/service/channels
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Channel 将外部消息平台连接到 AgentScope Service，负责接收消息、匹配目标并回传结果。创建渠道、配置路由、启停连接和查询回传状态都可以通过 API 完成。自有业务应用若只需要 HTTP 调用入口，使用 [Session API](/v2/zh/service/service-api)；Channel 适合让用户从已有聊天平台发起工作。

当前适配器包含钉钉、飞书、企业微信、GitHub 和 GitLab，具体配置通过类型接口查询。其中把外部消息关联为持久 Issue、路由给 Agent / Team 并回传工作进展的完整流程，目前仅支持飞书。其他适配器不能据此推定具有相同的任务协作能力。

## 查询平台要求并创建连接

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


Channel 管理路径目前为 `/api/channels`，使用平台用户身份和空间请求头。先查询所需凭据、传输方式和回调地址模板：

```bash
api "$SERVICE_URL/api/channels/types" | jq .
```

下面创建暂不启动的飞书连接。将 `FEISHU_APP_ID`、`FEISHU_APP_SECRET`、`FEISHU_VERIFICATION_TOKEN` 设为平台应用的值；外部平台的应用、权限与事件订阅需先配置好。

```bash
channel=$(api "$SERVICE_URL/api/channels" --data "$(jq -n \
  --arg app "$FEISHU_APP_ID" --arg secret "$FEISHU_APP_SECRET" \
  --arg verification "$FEISHU_VERIFICATION_TOKEN" \
  '{type:"feishu",disabled:true,dmScope:"PER_PEER",
    properties:{appId:$app,appSecret:$secret,verificationToken:$verification}}')")
CHANNEL_ID=$(jq -r '.channelId' <<<"$channel")
```

创建响应直接返回 Channel 对象。根据类型接口的 `callbackUrlTemplate` 替换 channel ID，并使用部署的公开 HTTPS origin，在飞书侧配置回调。连接类型和必填 `properties` 随平台而变，不要把飞书字段原样用于其他适配器。

## 将收到的工作交给 Agent 或 Team

准备同一空间内的 `TEAM_ID`，为该渠道启用工作接待。首次配置读取的版本通常为 0，后续保存都复用当前 `version`：

```bash
settings=$(api "$SERVICE_URL/api/channels/$CHANNEL_ID/collaboration")
api "$SERVICE_URL/api/channels/$CHANNEL_ID/collaboration" -X PUT \
  --data "$(jq -n --arg team "$TEAM_ID" \
  --argjson version "$(jq '.version' <<<"$settings")" \
  '{enabled:true,version:$version,defaultTarget:{targetType:"team",targetRef:$team},
    routes:[],allowGroupWork:false,notifyEvents:["result","status"]}')"
api "$SERVICE_URL/api/channels/$CHANNEL_ID/enable" -X POST
api "$SERVICE_URL/api/channels/$CHANNEL_ID"
```

单 Agent 目标使用 `targetType:"agent"`；当前工作路由不支持直接选择 Workflow。`defaultTarget` 处理未匹配细分规则的消息，`routes` 可按 `accountId`、`peerKind`（`DIRECT` / `GROUP`）、`peerId`、可选 `threadId` 设置目标。群工作需同时启用 `allowGroupWork` 并由用户明确发起共享工作。

`started:true` 表示适配器已经启动，不能证明消息订阅、工作路由和回传都成功。下一步从外部平台发送一个小任务，验证完整往返。

## 关联用户身份并开始工作

工作接待按实际发消息的用户检查权限。登录用户调用 pairing 获取一次性绑定命令：

```bash
api "$SERVICE_URL/api/channels/$CHANNEL_ID/pairing" -X POST
```

响应的 `command` 形如 `/bind ...`，在 `expiresInSeconds` 指定的有效期内私聊机器人发送，完成外部身份与平台账号关联。之后从该对话提出工作请求；Service 根据路由、访问范围及当前会话关联创建或跟进 Issue。需要解除当前用户关联时调用 `DELETE /api/channels/{channelId}/identity`。

应用无需调用 `/api/internal/channels/...` 模拟用户消息；这些路径是受信任适配器的内部交付协议。希望从普通业务系统触发任务时，选择 [Issue API](/v2/zh/service/issues) 或 [Automation Webhook](/v2/zh/service/automation)。

## 跟进接收、执行和回传

```bash
api "$SERVICE_URL/api/channels/$CHANNEL_ID/activity"
```

activity 包含当前用户可访问的消息接收、工作关联与发送记录。沿关联 Issue 使用[任务 API](/v2/zh/service/issues)读取执行与结果；同时检查渠道的 outbound delivery。Agent 完成工作不代表外部平台已经收到结果。

接收失败可通过 `POST /api/channels/{channelId}/messages/{messageId}/retry` 重试，回传失败使用 `/deliveries/{deliveryId}/retry`。取消某个外部会话对工作的订阅使用 `DELETE /api/channels/{channelId}/links/{linkId}`；这不会取消 Issue 执行。

更改连接凭据使用 `PUT /api/channels/{channelId}`，读取详情时密钥会被遮盖。启停使用 `/enable`、`/disable`，删除使用 `DELETE /api/channels/{channelId}`。更换凭据或回调设置后，重新验证接收与发送两条路径。

控制台配置表单、工作路由和身份绑定入口见[控制台：自动化与渠道](/v2/zh/service/console/index#console-automation)。
