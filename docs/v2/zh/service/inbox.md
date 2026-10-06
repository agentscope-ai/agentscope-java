---
title: "通知、审批与验收 API"
en_link: /v2/en/service/inbox
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

执行反馈包含两种不同的决策：工作完成后，需要判断交付是否满足要求；执行过程中，也可能需要批准一次操作。业务应用可以读取 Inbox 找到当前用户的待办，再分别调用 Issue 验收接口或 Approval 决策接口。阅读、审批与验收都有独立状态，标记“已读”不会替用户作出决定。

## 找到需要处理的消息

以下示例使用 Bash、`curl` 和 `jq`。先按[认证与空间](/v2/zh/service/api-reference#认证与范围)准备 `SERVICE_URL`（Service 地址）、`TOKEN`（用户 Bearer token）、`TENANT`、`NAMESPACE`，并定义请求函数：

```bash
api() {
  curl --fail-with-body --silent --show-error \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-AgentScope-Tenant: $TENANT" \
    -H "X-AgentScope-Namespace: $NAMESPACE" \
    -H 'Content-Type: application/json' "$@"
}
```


Inbox 代表当前登录用户的信箱，不能通过请求参数把它切换成其他人的信箱。使用用户身份访问：

```bash
api "$SERVICE_URL/api/v1/inbox" --get \
  --data-urlencode "tenant=$TENANT" --data-urlencode "namespace=$NAMESPACE" \
  --data-urlencode 'view=action' --data-urlencode 'limit=50'
api "$SERVICE_URL/api/v1/inbox/summary" --get \
  --data-urlencode "tenant=$TENANT" --data-urlencode "namespace=$NAMESPACE"
```

列表返回 `items`、`hasMore` 和 `nextCursor`；继续翻页时传入 `cursor`。`view=action` 只看待决策事项，`unread` 只看未读，`attention` 查看需要关注的内容，`all` 查看全部未归档消息；查归档时增加 `archived=true`。

选择一条消息后，通过 `GET /api/v1/inbox/{inboxId}` 读取内容，再用其中的工作或审批引用加载详情。页面应展示用户正在决定的对象、当前结果及版本，不能仅凭通知标题完成验收。

## 验收工作结果

沿用[任务指南](/v2/zh/service/issues)中的 `ISSUE_ID`。先加载完整结果，确认 Issue 当前为 `in_review`：

```bash
review=$(api "$SERVICE_URL/api/v1/issues/$ISSUE_ID")
api "$SERVICE_URL/api/v1/issues/$ISSUE_ID/artifacts"
api "$SERVICE_URL/api/v1/issues/$ISSUE_ID/comments?limit=50"
printf '%s\n' "$review" | jq '.issue | {title,status,version,acceptanceCriteria}'
```

应用让用户检查验收标准、最终报告和必要的子任务结果。用户确认通过后，提交刚才检查的版本：

```bash
api "$SERVICE_URL/api/v1/issues/$ISSUE_ID/accept" --data "$(jq -n \
  --argjson version "$(jq '.issue.version' <<<"$review")" \
  '{expectedVersion:$version,reason:"已核对报告与证据，满足验收要求"}')"
```

不通过时改用 `/reject`，请求体仍包含 `expectedVersion`，并在 `reason` 写清缺失项。退回把工作置为 `in_progress`，不会自动重新执行；随后按[评论与分派流程](/v2/zh/service/issues#分派已有工作与补充信息)通知负责人继续。不要把查看子 Issue 或解决评论线程当作对当前 Issue 的验收。

若返回版本冲突，重新加载并让用户检查更新后的结果。不要自动换成最新版本重发旧的验收决定。

## 处理执行中的审批

审批可以来自 Workflow 的人工节点或运行时的操作请求。`APPROVAL_ID` 来自通知的审批引用，也可以用 `GET /api/v1/approvals?tenant=...&namespace=...` 查询。先读取请求者、操作对象与原因；只有指定的审批人可以决定：

```bash
approval=$(api "$SERVICE_URL/api/v1/approvals/$APPROVAL_ID")
printf '%s\n' "$approval" | jq .
```

在用户明确作出批准决定后调用：

```bash
api "$SERVICE_URL/api/v1/approvals/$APPROVAL_ID/decide" --data "$(jq -n \
  --argjson version "$(jq '.approval.version' <<<"$approval")" \
  '{expectedVersion:$version,status:"approved",decision:{reason:"已核对操作范围"}}')"
```

拒绝使用 `status:"rejected"`。决定可能使等待执行继续或失败；这不会同时验收最终交付。过期或与当前执行不再匹配的请求需要重新读取和判断。

直接通过 Agent API 发起会话时，工具确认还可能通过会话的待处理输入来完成，见[输入与工具确认](/v2/zh/service/session-event-log)。不要假定所有会话确认都会成为 Inbox Approval。

## 已读、归档与页面更新

读完通知后调用 `POST /api/v1/inbox/{inboxId}/read`；不再需要保留在当前列表时调用 `/archive`。这些操作不会删除 Issue、取消执行或替代审批。Inbox 的计数通过 `/inbox/summary` 更新。

应用可以定时刷新 Inbox，或在平台 WebSocket 收到工作更新后重新查询。它是当前用户的待办投影，不是完整的执行日志；执行过程与断点续传使用 [SSE 与状态 API](/v2/zh/service/sse-events)。

需要直接在平台页面完成这些操作，见[控制台：任务与反馈](/v2/zh/service/console/tasks)。
