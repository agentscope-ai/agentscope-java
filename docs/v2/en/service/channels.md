---
title: "Connect messaging channels"
zh_link: /v2/zh/service/channels
---

<Note>
This page uses the `2.1.0-BETA1` prerelease.
</Note>

A Channel connects an external messaging platform to AgentScope Service. It receives messages, routes them to a target, and delivers results. Applications can create channels, configure routing, enable or disable connections, and inspect delivery through APIs. Use the [Session API](/v2/en/service/service-api) for a direct HTTP application interface; use a Channel for work initiated in an existing messaging platform.

Adapters currently include DingTalk, Feishu, WeCom, GitHub, and GitLab. Query their configuration requirements through the type API. The complete durable-work flow—linking external messages to Issues, routing work to Agents or Teams, and returning progress—currently supports Feishu only. Other adapters do not automatically have the same collaboration capabilities.

## Discover requirements and create a connection

The examples use Bash, `curl`, and `jq`. Set `BASE_URL` to your Service address, and `TENANT` / `NAMESPACE` to the default scope; see [API authentication](/v2/en/service/api-reference). Run the following steps in the same Bash terminal:

```bash
set -euo pipefail
```


Channel management currently uses `/api/channels`, with a user identity and namespace headers. Query required credentials, transport, and callback templates:

```bash
curl -sS --fail-with-body "$BASE_URL/api/channels/types" \
  | jq .
```

Create a disabled Feishu connection. Set `FEISHU_APP_ID`, `FEISHU_APP_SECRET`, and `FEISHU_VERIFICATION_TOKEN` to your platform application's values. Configure the external application, permissions, and event subscriptions first.

```bash
umask 077

jq -n \
  --arg app "$FEISHU_APP_ID" \
  --arg secret "$FEISHU_APP_SECRET" \
  --arg verification "$FEISHU_VERIFICATION_TOKEN" \
  '{type:"feishu",disabled:true,dmScope:"PER_PEER",
    properties:{appId:$app,appSecret:$secret,verificationToken:$verification}}' > request.json

channel=$(
  curl -sS --fail-with-body "$BASE_URL/api/channels" \
    -H "Content-Type: application/json" \
    --data-binary @request.json
)
CHANNEL_ID=$(jq -r '.channelId' <<<"$channel")
```

The response is the Channel object directly. Substitute its ID into the type's `callbackUrlTemplate` and configure that path under your public HTTPS origin in Feishu. Types and required `properties` differ by platform.

## Route work to an Agent or Team

Prepare `TEAM_ID` in the same namespace and enable work intake. Read the current `version` before saving; an initial configuration typically has version 0:

```bash
settings=$(
  curl -sS --fail-with-body "$BASE_URL/api/channels/$CHANNEL_ID/collaboration"
)
jq -n --arg team "$TEAM_ID" \
  --argjson version "$(jq '.version' <<<"$settings")" \
  '{enabled:true,version:$version,defaultTarget:{targetType:"team",targetRef:$team},
    routes:[],allowGroupWork:false,notifyEvents:["result","status"]}' > request.json
```

```bash
curl -sS --fail-with-body -X PUT "$BASE_URL/api/channels/$CHANNEL_ID/collaboration" \
  -H "Content-Type: application/json" \
  --data-binary @request.json
```

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/channels/$CHANNEL_ID/enable"
```

```bash
curl -sS --fail-with-body "$BASE_URL/api/channels/$CHANNEL_ID"
```

Use `targetType:"agent"` for a single Agent. Durable-work routing does not currently accept Workflow targets. `defaultTarget` handles messages without a specific match. `routes` can select targets by `accountId`, `peerKind` (`DIRECT` / `GROUP`), `peerId`, and optional `threadId`. Group work requires both `allowGroupWork` and an explicit shared-work request from the user.

`started:true` means the adapter started; it does not prove subscription, routing, and delivery work end to end. Send a small task from the external platform next.

## Bind the user's identity

Work intake checks permissions for the actual sender. An authenticated user obtains a one-time binding command through:

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/channels/$CHANNEL_ID/pairing"
```

Send the returned `command`, such as `/bind ...`, in a direct message to the bot within `expiresInSeconds`. This links the external identity to the platform account. Subsequent requests create or follow an Issue according to routing, authorization, and the current conversation association. Unlink the current user through `DELETE /api/channels/{channelId}/identity`.

Applications should not simulate messages through `/api/internal/channels/...`; those paths belong to trusted adapter delivery. Use [Issue APIs](/v2/en/service/issues) or [Automation webhooks](/v2/en/service/automation) for ordinary business-system triggers.

## Follow receipt, execution, and delivery

```bash
curl -sS --fail-with-body "$BASE_URL/api/channels/$CHANNEL_ID/activity"
```

Activity includes received messages, work associations, and outbound deliveries visible to the current user. Follow linked Issues through the [task APIs](/v2/en/service/issues) and inspect outbound delivery separately. Agent completion does not establish that the external platform received the result.

Retry intake through `POST /api/channels/{channelId}/messages/{messageId}/retry`; retry outbound delivery through `/deliveries/{deliveryId}/retry`. Remove a conversation's work subscription with `DELETE /api/channels/{channelId}/links/{linkId}`; this does not cancel the Issue execution.

Update credentials through `PUT /api/channels/{channelId}`; detail responses mask secrets. Use `/enable` and `/disable` to control connections and `DELETE /api/channels/{channelId}` to remove one. Recheck both inbound and outbound delivery after changing credentials or callback settings.

See [Console: automation and channels](/v2/en/service/console/index#console-automation) for configuration forms, routing, and identity binding.

<span id="curl-management"></span>

## Channel maintenance examples

Use the local URL and default scope variables from [API setup](/v2/en/service/create-managed-agent#api-setup).

Take message, delivery or link IDs from actual activity records. Resolve the failure before retrying; unsubscribing does not cancel running work.

<Tabs>
<Tab title="Retry received message">

```bash
MESSAGE_ID="MESSAGE_ID_FROM_ACTIVITY"
```

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/channels/$CHANNEL_ID/messages/$MESSAGE_ID/retry"
```

</Tab>
<Tab title="Retry delivery">

```bash
DELIVERY_ID="DELIVERY_ID_FROM_ACTIVITY"
```

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/channels/$CHANNEL_ID/deliveries/$DELIVERY_ID/retry"
```

</Tab>
<Tab title="Unsubscribe">

```bash
LINK_ID="LINK_ID_FROM_ACTIVITY"
```

```bash
curl -sS --fail-with-body -X DELETE "$BASE_URL/api/channels/$CHANNEL_ID/links/$LINK_ID"
```

</Tab>
<Tab title="Unbind identity">

```bash
curl -sS --fail-with-body -X DELETE "$BASE_URL/api/channels/$CHANNEL_ID/identity"
```

</Tab>
</Tabs>

<Accordion title="Disable or delete a connection">

Disable to stop accepting new channel work; enable again after maintenance. Deletion is a separate choice that removes the connection and does not replace task cancellation.

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/channels/$CHANNEL_ID/disable"
```

```bash
curl -sS --fail-with-body -X DELETE "$BASE_URL/api/channels/$CHANNEL_ID"
```

</Accordion>


<Accordion title="Update channel connection credentials">

Write the replacement to channel-update.json, for example `{"properties":{"appSecret":"YOUR_NEW_APP_SECRET"}}`. Properties merge by field, so omit unchanged settings. Do not submit masked values from GET as new secrets. Verify both receipt and delivery afterward.

```bash
chmod 600 channel-update.json
```

```bash
curl -sS --fail-with-body -X PUT "$BASE_URL/api/channels/$CHANNEL_ID" \
  -H "Content-Type: application/json" \
  --data-binary @channel-update.json
```

</Accordion>
