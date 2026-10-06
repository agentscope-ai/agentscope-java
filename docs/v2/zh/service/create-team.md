---
title: "通过 API 创建与调用 Team"
en_link: /v2/en/service/create-team
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

Team 将多个已注册 Agent 组织成一个可被分派和调用的协作单元。Leader 理解目标、选择成员并汇总交付，成员通过任务与讨论交换结果。业务应用只需提交目标和输入，不必把团队内部每次委派改写成前端流程。

Managed、External、Hosted Agent 可以按各自能力加入同一 Team。先确认每个 Agent 已注册、可执行任务，且运行时支持所需协作协议；组成 Team 不会自动为运行时增加工具或协议能力。

## 创建一个复核团队

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


准备资料助手和复核助手，将其 ID 分别设为 `LEADER_AGENT_ID`、`REVIEWER_AGENT_ID`。下面让 Leader 整理材料，成员负责复核。Leader 不需要重复列入 `members`，每个成员的 Agent ID 和 role 必须唯一。

```bash
created=$(api "$SERVICE_URL/api/v1/teams" --data "$(jq -n \
  --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
  --arg leader "$LEADER_AGENT_ID" --arg reviewer "$REVIEWER_AGENT_ID" \
  '{tenant:$tenant,namespace:$namespace,name:"资料复核团队",leaderAgentId:$leader,
    instructions:"Leader 整理材料后委派 reviewer 检查证据与缺失项，按反馈修订并交付统一报告。",
    policy:{maxActiveTasks:3,maxFanout:2,requireReview:true},
    members:[{agentId:$reviewer,role:"reviewer",instructions:"检查事实、证据及未确认信息。"}]}')")
TEAM_ID=$(jq -r '.team.id' <<<"$created")
api "$SERVICE_URL/api/v1/teams/$TEAM_ID/overview"
```

`instructions` 描述协作方式，成员的 `instructions` 说明各自职责，`policy` 约束并发、委派与验收。概览返回团队的配置和运行情况；创建成功不代表每个运行时已经在线，应进一步核对成员可用性并执行一个小任务。完整策略见 [Team 配置](/v2/zh/service/team-configuration)。

## 派发目标，读取团队结果

给 Team 创建 Issue 的方式与给单个 Agent 相同，只需切换负责人类型：

```bash
work=$(api "$SERVICE_URL/api/v1/issues" --data "$(jq -n \
  --arg tenant "$TENANT" --arg namespace "$NAMESPACE" --arg team "$TEAM_ID" \
  '{tenant:$tenant,namespace:$namespace,title:"整理并复核本周项目报告",
    description:"读取 Workspace 中本周材料，交付带来源的 report.md，列出待确认事项。",
    assigneeType:"team",assigneeRef:$team,access:{mode:"private"},
    acceptanceCriteria:["Leader 汇总为一份报告","复核发现均有处理结论"]}')")
ISSUE_ID=$(jq -r '.issue.id' <<<"$work")
api "$SERVICE_URL/api/v1/orchestration-runs" --get \
  --data-urlencode "tenant=$TENANT" --data-urlencode "namespace=$NAMESPACE" \
  --data-urlencode "issueId=$ISSUE_ID"
```

Service 异步调度 Leader，委派后生成成员任务，并保留团队执行关联。获取 Run ID 后调用 `GET /api/v1/orchestration-runs/{runId}/graph` 查看节点、任务和 Attempt；通过 Issue 的评论和 Artifact 接口读取进度与交付物。Leader 汇总完成后，再按照 Issue 策略[验收](/v2/zh/service/inbox)。成员完成一次执行不等于整个团队已经完成目标。

## 调整成员与协作方式

读取 `GET /api/v1/teams/{teamId}` 获得完整配置。新增成员调用 `POST /api/v1/teams/{teamId}/members`，请求体包含 `agentId`、`role` 和可选 `instructions`；服务返回的 `member.id` 用于后续成员更新与移除。

更改团队名称、指令或策略使用 `PATCH /api/v1/teams/{teamId}`，携带当前 `expectedVersion`、`name`、`leaderAgentId`，并保留要沿用的策略和说明；更改成员角色使用 `PATCH /api/v1/teams/{teamId}/members/{memberId}`，携带 `expectedTeamVersion`。这些配置用于后续执行，已经开始的任务仍要按其实际执行记录跟进。不要通过移除成员来代替取消正在运行的任务。

## 提供给业务应用

如果应用希望调用稳定的服务地址并隔离管理权限，可以将 Team 发布为 Job Endpoint。通过 [Endpoint 管理 API](/v2/zh/service/endpoints)选择 `targetType:"team"` 与 Team ID，创建 release 和应用凭据。调用方随后提交 Invocation、订阅 SSE、查询结果和取消执行，不需要获得创建 Agent、修改成员等管理权限。

这也是 Agent API 覆盖 Team 的方式：对外提供统一的调用与结果协议，内部仍使用团队自己的委派和协作机制。Team 的任务执行不等于 Managed Agent 的可恢复会话；不要把 Managed 专属的 checkpoint、文件等会话接口套用于所有成员。

如果步骤、依赖和审批顺序由业务固定，继续阅读 [Workflow API](/v2/zh/service/workflows)。需要在页面上操作，见[控制台：团队与编排](/v2/zh/service/console/orchestration)。协作原理见 [Team 参考](/v2/zh/service/teams)。
