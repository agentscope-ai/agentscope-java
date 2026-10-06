---
title: "通过 API 分派与跟进任务"
en_link: /v2/en/service/issues
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Issue 保存一项工作的目标、负责人、讨论、执行记录和交付物。业务应用可以通过 API 创建工作、交给 Agent 或 Team、补充信息并验收结果。例如，用户提交“分析日志并整理错误报告”后，后端创建 Issue，页面持续展示进展，最后让用户按验收标准确认交付。

这个流程适用于已经注册且具备任务执行能力的 Agent，不取决于它采用 Managed、External 还是 Hosted 运行方式。运行时接入见[创建与注册 Agent](/v2/zh/service/agents)。若应用只需要调用一个已发布的服务并取得结果，也可以使用 [Endpoint](/v2/zh/service/endpoints)，由 Service 关联执行所需的工作记录。

## 创建第一项工作

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

需要实时更新时，平台工作通知入口 `GET /api/v1/events?tenant=...&namespace=...` 使用 **WebSocket**，用于提示应用重新读取状态，不提供断点回放。重连后仍要刷新上述 REST 数据。如果请求来自已发布的 Endpoint，使用对应 Invocation 的 [SSE 事件](/v2/zh/service/sse-events)跟进该次调用；两种事件入口用途不同。

## 理解状态并完成验收

Issue 从待处理进入执行中，完成后可能进入 `in_review` 等待验收，也可能因缺少输入进入 `blocked`。一次 AgentTask 或 Run 成功，表示那次执行已结束；业务交付是否完成仍由 Issue 的完成策略决定。普通 Issue 创建接口当前采用 `review`，需要验收后变为 `done`。

验收时重新读取 Issue 及交付物，把当前版本提交给 `accept`；不符合要求时调用 `reject` 并说明原因。完整流程见[通知、审批与验收 API](/v2/zh/service/inbox)。退回会记录反馈并将 Issue 置为 `in_progress`，不会自动启动下一次执行；接着通过评论路由明确交给负责人继续处理。

失败任务可以调用 `POST /api/v1/agent-tasks/{taskId}/retry`，停止任务使用对应 `/cancel`。先读取失败原因和当前状态，再决定操作；取消执行、验收工作和归档 Issue 是不同动作。重新执行会保留之前的执行记录，也可能再次产生外部副作用。

## Agent 如何回报任务

对于 Managed Agent 和已经接入平台协议的运行时，任务进度、结果和错误由执行适配器回报。业务应用读取它们即可。接入自有运行时时，可使用任务协议的 `/agent-tasks/{taskId}/progress`、`/respond`、`/complete`、`/fail` 等入口；这些接口要求执行上下文下发的 **AgentTask token**，普通用户 token 不能代替任务执行身份。详见[运行时协议](/v2/zh/service/external-agent-execution)。

需要先在对话中澄清需求时，应用可以先使用 [Agent API 聊天流程](/v2/zh/service/agent-api-chat)，再把明确后的目标写入 Issue。控制台中的创建、讨论、文件与 Chat 转任务入口统一见[控制台：任务与反馈](/v2/zh/service/console/tasks)。
