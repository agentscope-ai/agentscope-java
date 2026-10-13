---
title: "工作分派、审批与验收"
en_link: /v2/en/service/issues
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Issue 保存一项工作的目标、负责人、讨论、执行记录和交付物。业务应用可以通过 API 创建工作、交给 Agent 或 Team、补充信息并验收结果。例如，用户提交“分析日志并整理错误报告”后，后端创建 Issue，页面持续展示进展，最后让用户按验收标准确认交付。

这个流程适用于已经注册且具备任务执行能力的 Agent，不取决于它采用 Managed、External 还是 Hosted 运行方式。运行时接入见[创建与注册 Agent](/v2/zh/service/api-reference#agents)。如果应用只需要提交任务、跟进执行并取得结果，可以直接通过 [Session API](/v2/zh/service/service-api) 创建以 Agent、Team 或 Workflow 为目标的 Session，无需先自行创建 Issue。需要在业务层管理负责人、讨论和验收过程时，再按本页使用 Issue API。

<span id="理解状态并完成验收"></span>

## 创建第一项工作

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


将 `AGENT_ID` 设为同一空间中可执行任务的 Agent ID。创建时直接指定负责人，Service 会生成任务并异步派发；API 返回成功说明工作已接收，实际执行状态仍需后续查询。

```bash
created=$(api "$SERVICE_URL/api/v1/issues" --data "$(jq -n \
  --arg tenant "$TENANT" --arg namespace "$NAMESPACE" --arg agent "$AGENT_ID" \
  '{tenant:$tenant,namespace:$namespace,
    title:"分析示例日志并输出错误分类报告",
    description:"读取 Workspace 中的 sample.log，生成 report.md，标明无法确认的原因。",
    acceptanceCriteria:["包含错误分类与数量","每类给出带行号的证据"],
    assigneeType:"agent",assigneeRef:$agent,access:{mode:"private"}}')")
ISSUE_ID=$(jq -r '.issue.id' <<<"$created")
TASK_ID=$(jq -r '.agentTask.id // empty' <<<"$created")
printf '%s\n' "$created" | jq .
```

`issue.id` 标识业务工作，`agentTask.id` 标识交给 Agent 的一次任务。后续重试、协作或补充信息可能关联新的任务记录，应用应围绕 Issue 展示完整过程。Team 任务把 `assigneeType` 改为 `team`，`assigneeRef` 改为 Team ID；人工负责人使用 `human`。省略负责人时先保存工作，稍后再分派。

`access.mode` 默认为 `private`。需要空间成员共同查看时使用 `namespace`；指定协作者时使用 `shared` 并提供 `members`。授权范围同时影响讨论和执行结果。Agent 能否读取 `sample.log` 则由其实际 Workspace 和工具权限决定。

## 分派已有工作与补充信息

修改已有工作时先读取最新 `version`，将它作为 `expectedVersion` 提交，避免覆盖其他参与者的修改。下面把已有 Issue 分派给一个 Team：

```bash
current=$(api "$SERVICE_URL/api/v1/issues/$ISSUE_ID")
api "$SERVICE_URL/api/v1/issues/$ISSUE_ID/assign" --data "$(jq -n \
  --arg team "$TEAM_ID" --argjson version "$(jq '.issue.version' <<<"$current")" \
  '{assigneeType:"team",assigneeRef:$team,expectedVersion:$version}')"
```

分派后无需再调用一次 `dispatch` 才能开始正常调度。检查返回的 `agentTask` 和后续执行状态；分派到人类负责人时不会生成 Agent 执行。

任务进行中，使用评论补充输入或提出修改要求。需要明确通知某个 Agent 时，传入结构化 `mentions`，不要只在正文写一个名字：

```bash
api "$SERVICE_URL/api/v1/issues/$ISSUE_ID/comments" --data "$(jq -n \
  --arg agent "$AGENT_ID" \
  '{content:"请额外统计超时错误，并在报告中区分已确认和待确认的原因。",
    mentions:[{type:"agent",ref:$agent}]}')"
```

响应中的 `routes` 说明信息是排队、合并到已有任务还是被策略阻止。评论可能触发后续工作；纯进度记录可使用 `type:"progress"` 且不设置 mentions。回复某条评论时增加 `parentId`。需要在提交前展示路由预览，可向 `POST /api/v1/issues/{issueId}/comments/preview-routing` 提交同样的请求体。

## 读取进展和交付物

页面重新打开时，先读取 Issue，再读取相关任务、评论和文件，恢复完整工作视图：

```bash
api "$SERVICE_URL/api/v1/issues/$ISSUE_ID"
api "$SERVICE_URL/api/v1/agent-tasks" --get \
  --data-urlencode "tenant=$TENANT" --data-urlencode "namespace=$NAMESPACE" \
  --data-urlencode "issueId=$ISSUE_ID"
api "$SERVICE_URL/api/v1/issues/$ISSUE_ID/comments?limit=50"
api "$SERVICE_URL/api/v1/issues/$ISSUE_ID/artifacts"
api "$SERVICE_URL/api/v1/issues/$ISSUE_ID/activity?limit=50"
```

任务返回的 `result`、错误和执行关联用于展示结果或排查失败。评论分页继续使用返回的 `nextCursor`。文件列表给出 Artifact ID，后续通过 `POST /api/v1/artifacts/{artifactId}/download` 获取下载信息；上传使用 `POST /api/v1/artifacts/uploads`，具体字段见 [API 参考](/v2/zh/service/api-reference)。

需要实时更新时，平台工作通知入口 `GET /api/v1/events?tenant=...&namespace=...` 使用 **WebSocket**，用于提示应用重新读取状态，不提供断点回放。重连后仍要刷新上述 REST 数据。如果请求来自Session API，使用对应 Turn 的 [SSE 事件](/v2/zh/service/sse-events)跟进该次调用；两种事件入口用途不同。

## 状态、结果与验收

Issue 从待处理进入执行中，完成后可能进入 `in_review` 等待验收，也可能因缺少输入进入 `blocked`。一次 AgentTask 或 Run 成功，表示那次执行已结束；业务交付是否完成仍由 Issue 的完成策略决定。普通 Issue 创建接口当前采用 `review`，需要验收后变为 `done`。

<span id="managed-harness-task-outcomes"></span>
<span id="读取结果并提交反馈"></span>
<span id="结果意图"></span>
<span id="子任务和交付物"></span>
<span id="恢复与人工验收"></span>
<span id="示例判定售前方案交付"></span>

下表是执行器报告的 Outcome，不是客户端可任意 PATCH 的 Issue 状态。Turn completed、原生 turn completed、Run succeeded 和 Issue accepted 属于不同资源，不能相互替代。

| Outcome | 意义 | 下一步 |
| --- | --- | --- |
| succeeded | 有明确交付且执行可完成 | 检查产物并按 Issue 策略验收 |
| waiting | 等待已存在、可追踪的依赖任务 | 查看依赖 ID 和真实状态 |
| blocked | 缺少信息或条件，保留当前已完成部分 | 补充具体输入，再沿工作流程继续 |
| failed | 执行失败 | 阅读错误与部分结果，修复后决定重试 |

“稍后继续”“正在等待”这类普通文本不构成成功。还有未结束的后台任务或异常停止时，也不能据此判定成功。Runtime 为续轮和等待设置预算，超过限制时会停止自动推进。

子任务使用独立执行上下文，通过持久任务记录返回结果。创建子 Agent 不会自动增加它没有的网络、工具或权限。查询不到依赖时应报告错误，不能无限等待一个不存在的任务。

Lead 应把子结果整理到主 Issue 的结果和 Artifact 中，标明部分完成、失败与未确认事实。文件搜索结果被截断时需要缩小范围继续查询，而非把截断内容当作完整证据。

<span id="inbox"></span>
<span id="找到需要处理的消息"></span>
<span id="验收工作结果"></span>

沿用上文创建的 `ISSUE_ID`。先加载完整结果，确认 Issue 当前为 `in_review`：

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

## Agent 如何回报任务

对于 Managed Agent 和已经接入平台协议的运行时，任务进度、结果和错误由执行适配器回报。业务应用读取它们即可。接入自有运行时时，可使用任务协议的 `/agent-tasks/{taskId}/progress`、`/respond`、`/complete`、`/fail` 等入口；这些接口要求执行上下文下发的 **AgentTask token**，普通用户 token 不能代替任务执行身份。详见[运行时协议](/v2/zh/service/external-agent#external-agent-execution)。

需要先在对话中澄清需求时，应用可以先使用 [Agent API 聊天流程](/v2/zh/service/agent-api-chat)，再把明确后的目标写入 Issue。控制台中的创建、讨论、文件与 Chat 转任务入口统一见[控制台：任务与反馈](/v2/zh/service/console/index#console-tasks)。


## Inbox 通知与审批

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

### 处理执行中的审批

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

### 已读、归档与页面更新

读完通知后调用 `POST /api/v1/inbox/{inboxId}/read`；不再需要保留在当前列表时调用 `/archive`。这些操作不会删除 Issue、取消执行或替代审批。Inbox 的计数通过 `/inbox/summary` 更新。

应用可以定时刷新 Inbox，或在平台 WebSocket 收到工作更新后重新查询。它是当前用户的待办投影，不是完整的执行日志；执行过程与断点续传使用 [SSE 与状态 API](/v2/zh/service/sse-events)。

需要直接在平台页面完成这些操作，见[控制台：任务与反馈](/v2/zh/service/console/index#console-tasks)。


失败任务可以调用 `POST /api/v1/agent-tasks/{taskId}/retry`，停止任务使用 `/cancel`。先读取失败原因与当前状态；重试保留旧记录，也可能再次产生外部副作用。
