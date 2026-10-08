---
title: "编排 Workflow"
en_link: /v2/en/service/workflows
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

当业务明确规定“生成报告 → 人工审批 → 交付”时，可以用 Workflow 保存步骤和依赖。先发布一个 revision，再创建以该 Workflow 为目标的 Session，即可通过 Turn 提交任务。需要由 Leader 动态拆分工作时，使用 [Team](/v2/zh/service/create-team)；两者采用相同的应用调用路径。

Workflow 有可编辑的定义和发布后不可变的 revision。每次 Run 固定一个 revision，因此修改草稿不会改变已经开始的执行。

可以直接使用已经验证的 Managed Agent 作为流程节点；需要分工时再让节点调用 Team。先[运行一个托管 Agent](/v2/zh/service/create-managed-agent)，再固定流程与审批人。选型见[多 Agent 协作](/v2/zh/service/orchestration)。

## 创建并校验流程

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

将 `AGENT_ID` 设为已经可执行任务的 Agent ID，`APPROVER_ID` 设为审批人的账号标识。下面的流程先生成报告，再等待人工审批：

```bash
defined=$(api "$SERVICE_URL/api/v1/orchestration-definitions" --data "$(jq -n \
  --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
  --arg agent "$AGENT_ID" --arg approver "$APPROVER_ID" \
  '{tenant:$tenant,namespace:$namespace,name:"报告复核流程",
    draftSpec:{nodes:[
      {key:"draft",type:"agent",agentId:$agent,input:{request:"run.input.request"}},
      {key:"review",type:"approval",approval:{approverType:"human",approverRef:$approver,prompt:"请检查报告及证据"}}
    ],edges:[{from:"draft",to:"review",on:["succeeded"]}]}}')")
WORKFLOW_ID=$(jq -r '.definition.id' <<<"$defined")
api "$SERVICE_URL/api/v1/orchestration-definitions/$WORKFLOW_ID/validate" --data '{}'
```

节点 `key` 在流程中唯一，边把前一步的状态与后一步连接。`input` 将输入字段映射为 CEL 表达式；这里将请求里的 `request` 交给 draft 节点。表达式可读取 `run`、`issue`、`trigger` 和前序 `nodes`，不能作为任意脚本执行器。服务端校验检查节点类型、引用、表达式及环路；目标运行时是否可用仍要在实际执行前确认。

## 发布版本并开始执行

校验通过后发布当前定义，保存返回的 revision ID。后续修改使用 `PATCH /api/v1/orchestration-definitions/{definitionId}`，传入 `draftSpec` 和 `expectedVersion`，然后再次发布。

```bash
published=$(api "$SERVICE_URL/api/v1/orchestration-definitions/$WORKFLOW_ID/publish" \
  --data "$(jq -n --argjson version "$(jq '.definition.version' <<<"$defined")" \
  '{expectedVersion:$version}')")
REVISION_ID=$(jq -r '.revision.id' <<<"$published")
session=$(api "$SERVICE_URL/api/v1/agent-sessions" --data "$(jq -n \
  --arg workflow "$WORKFLOW_ID" --arg revision "$REVISION_ID" \
  '{target:{type:"workflow",id:$workflow,revisionId:$revision}}')")
SESSION_ID=$(jq -er '.id' <<<"$session")
turn=$(api "$SERVICE_URL/api/v1/agent-sessions/$SESSION_ID/turns" \
  -H 'Idempotency-Key: weekly-report-001' \
  --data '{"input":{"request":"Produce this week’s report with sources"}}')
TURN_ID=$(jq -er '.id' <<<"$turn")
```

保存 Session ID 和 Turn ID，后续查询、审批和取消都围绕这两个资源进行。Service 会自动建立流程执行与工作记录。重试同一任务时沿用相同幂等键；新任务使用新的 key。

## 跟进节点、输出与审批

```bash
api "$SERVICE_URL/api/v1/agent-sessions/$SESSION_ID/turns/$TURN_ID"
api "$SERVICE_URL/api/v1/agent-sessions/$SESSION_ID/snapshot"
```

Turn 详情返回当前任务状态、结果和失败原因；Session 快照用于恢复应用界面。需要实时展示时，按照 [SSE 文档](/v2/zh/service/sse-events)从快照游标订阅增量事件。只有诊断流程内部节点时，才需要从控制台查看关联 Run 的执行图。

review 节点会出现在 Turn 的 required_actions 中。指定审批人使用自己的平台 token，向该 Turn 的 `/actions` 提交实际的 `request_id`、`expected_version` 和 `decision`。审批通过后流程继续，应用密钥不能替代指定人员的身份。

## 逐步增加流程能力

| 节点类型 | 用途与关键字段 |
| --- | --- |
| `agent` / `team` | 执行单 Agent 或团队任务；使用 `agentId` / `teamRef` |
| `condition` | 用 `condition` 表达式判断路径，边也可设置条件 |
| `join` | 按 `join.mode` 的 `all`、`any` 或 `quorum` 汇合依赖 |
| `approval` | 用 `approval.approverRef` 指定审批人 |
| `timer` | 用 `timer.durationSeconds` 或 `timer.at` 等待 |
| `signal` | 等待 `signalName` 指定的外部信号 |
| `subrun` | 用 `definitionRevisionId` 调用固定版本的子流程 |

例如增加等待 `report.ready` 的 signal 节点后，外部业务通过下面的 API 推进流程：

```bash
api "$SERVICE_URL/api/v1/agent-sessions/$SESSION_ID/turns/$TURN_ID/inputs" \
  -H 'Idempotency-Key: report-upload-001' \
  --data '{"request_id":"RETURNED_SIGNAL_REQUEST_ID","expected_version":1,"payload":{"artifactId":"YOUR_ARTIFACT_ID"}}'
```

请求中的 ID 和版本必须取自当前 required_actions，不能直接使用示例占位值。signal 是流程的外部输入，不会自动替代审批。更复杂的流程先运行小样本，查看真实节点输出，再编写下游映射；不要假定不同运行时的结果结构完全相同。

## 取消任务与运维控制

应用通过 Turn 的 `/cancel` 取消当前任务，通过 capabilities 判断是否可以 `/resume`；再次从头执行时提交一个新的 Turn。以下 Run 接口供控制台运维和流程诊断使用，Run ID 从关联执行记录获取，不是 Turn ID。


`POST /api/v1/orchestration-runs/{runId}/pause` 阻止新节点调度，已运行步骤仍可能返回；`/resume` 恢复调度；`/cancel` 请求取消节点、任务和子运行，不会删除或验收 Issue。请求体可使用 `{}`。

终态后向 `/rerun` 提交 `{"idempotencyKey":"weekly-report-retry-001"}` 创建新的 Run，保留与原运行的关联；可加 `input` 替换输入。节点支持 `timeoutSeconds`、`retry` 和 `failurePolicy`，其中失败策略可选 `fail_fast`、`continue`、`partial_success`。重试不保证撤销已经发生的外部副作用。

应用创建 Session 时，使用 `target:{"type":"workflow","id":"WORKFLOW_ID","revisionId":"REVISION_ID"}`。省略 `revisionId` 时，Service 在创建会话时选择最新的已发布版本；已有会话不会自动切换到新版本。控制台设计器与运行图见[控制台](/v2/zh/service/console/index#console-orchestration)。
