---
title: "Connect messaging channels"
zh_link: /v2/zh/service/channels
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

A Channel connects an external messaging platform to AgentScope Service. It receives messages, routes them to a target, and delivers results. Applications can create channels, configure routing, enable or disable connections, and inspect delivery through APIs. Use the [Session API](/v2/en/service/service-api) for a direct HTTP application interface; use a Channel for work initiated in an existing messaging platform.

Adapters currently include DingTalk, Feishu, WeCom, GitHub, and GitLab. Query their configuration requirements through the type API. The complete durable-work flow—linking external messages to Issues, routing work to Agents or Teams, and returning progress—currently supports Feishu only. Other adapters do not automatically have the same collaboration capabilities.

## Discover requirements and create a connection

The examples use Bash, `curl`, and `jq`. Set `SERVICE_URL` to your Service address, `TOKEN` to a user Bearer token, and `TENANT` / `NAMESPACE` to your authorized scope; see [API authentication](/v2/en/service/api-reference). Define this request helper:

```bash
api() {
  curl --fail-with-body --silent --show-error \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H 'Content-Type: application/json' "$@"
}
```


Channel management currently uses `/api/channels`, with a user identity and namespace headers. Query required credentials, transport, and callback templates:

```bash
api "$SERVICE_URL/api/channels/types" | jq .
```

Create a disabled Feishu connection. Set `FEISHU_APP_ID`, `FEISHU_APP_SECRET`, and `FEISHU_VERIFICATION_TOKEN` to your platform application's values. Configure the external application, permissions, and event subscriptions first.

```bash
channel=$(api "$SERVICE_URL/api/channels" --data "$(jq -n \
  --arg app "$FEISHU_APP_ID" --arg secret "$FEISHU_APP_SECRET" \
  --arg verification "$FEISHU_VERIFICATION_TOKEN" \
  '{type:"feishu",disabled:true,dmScope:"PER_PEER",
    properties:{appId:$app,appSecret:$secret,verificationToken:$verification}}')")
CHANNEL_ID=$(jq -r '.channelId' <<<"$channel")
```

The response is the Channel object directly. Substitute its ID into the type's `callbackUrlTemplate` and configure that path under your public HTTPS origin in Feishu. Types and required `properties` differ by platform.

## Route work to an Agent or Team

Prepare `TEAM_ID` in the same namespace and enable work intake. Read the current `version` before saving; an initial configuration typically has version 0:

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

Use `targetType:"agent"` for a single Agent. Durable-work routing does not currently accept Workflow targets. `defaultTarget` handles messages without a specific match. `routes` can select targets by `accountId`, `peerKind` (`DIRECT` / `GROUP`), `peerId`, and optional `threadId`. Group work requires both `allowGroupWork` and an explicit shared-work request from the user.

`started:true` means the adapter started; it does not prove subscription, routing, and delivery work end to end. Send a small task from the external platform next.

## Bind the user's identity

Work intake checks permissions for the actual sender. An authenticated user obtains a one-time binding command through:

```bash
api "$SERVICE_URL/api/channels/$CHANNEL_ID/pairing" -X POST
```

Send the returned `command`, such as `/bind ...`, in a direct message to the bot within `expiresInSeconds`. This links the external identity to the platform account. Subsequent requests create or follow an Issue according to routing, authorization, and the current conversation association. Unlink the current user through `DELETE /api/channels/{channelId}/identity`.

Applications should not simulate messages through `/api/internal/channels/...`; those paths belong to trusted adapter delivery. Use [Issue APIs](/v2/en/service/issues) or [Automation webhooks](/v2/en/service/automation) for ordinary business-system triggers.

## Follow receipt, execution, and delivery

```bash
api "$SERVICE_URL/api/channels/$CHANNEL_ID/activity"
```

Activity includes received messages, work associations, and outbound deliveries visible to the current user. Follow linked Issues through the [task APIs](/v2/en/service/issues) and inspect outbound delivery separately. Agent completion does not establish that the external platform received the result.

Retry intake through `POST /api/channels/{channelId}/messages/{messageId}/retry`; retry outbound delivery through `/deliveries/{deliveryId}/retry`. Remove a conversation's work subscription with `DELETE /api/channels/{channelId}/links/{linkId}`; this does not cancel the Issue execution.

Update credentials through `PUT /api/channels/{channelId}`; detail responses mask secrets. Use `/enable` and `/disable` to control connections and `DELETE /api/channels/{channelId}` to remove one. Recheck both inbound and outbound delivery after changing credentials or callback settings.

See [Console: automation and channels](/v2/en/service/console/index#console-automation) for configuration forms, routing, and identity binding.
