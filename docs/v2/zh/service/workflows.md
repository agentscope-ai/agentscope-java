---
title: "通过 API 编排 Workflow"
en_link: /v2/en/service/workflows
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

当业务明确规定“生成报告 → 人工审批 → 交付”，用 Workflow 保存步骤和依赖，再通过 API 发布与执行。需要由 Leader 根据目标动态拆分工作时，使用 [Team](/v2/zh/service/create-team)。两者都可以作为 Job Endpoint 的目标，提供统一的应用调用入口。

Workflow 有可编辑的定义和发布后不可变的 revision。每次 Run 固定一个 revision，因此修改草稿不会改变已经开始的执行。

## 创建并校验流程

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
started=$(api "$SERVICE_URL/api/v1/orchestration-definitions/$WORKFLOW_ID/runs" \
  --data "$(jq -n --arg revision "$REVISION_ID" \
  '{revisionId:$revision,idempotencyKey:"weekly-report-001",
    input:{request:"汇总 Workspace 中本周材料并生成带来源的报告"},
    issue:{title:"本周项目报告",description:"交付复核后的报告",access:{mode:"private"}}}')")
RUN_ID=$(jq -r '.run.id' <<<"$started")
ISSUE_ID=$(jq -r '.run.rootIssueId' <<<"$started")
```

`issue` 用于随执行创建工作；若要关联已有工作，改传 `issueId`，两者必须二选一。`idempotencyKey` 标识这次业务提交，重传同一请求时保持一致。应用应保存 Run ID 和 Issue ID，分别跟进执行与验收。

## 跟进节点、输出与审批

```bash
api "$SERVICE_URL/api/v1/orchestration-runs/$RUN_ID"
api "$SERVICE_URL/api/v1/orchestration-runs/$RUN_ID/graph"
api "$SERVICE_URL/api/v1/orchestration-runs/$RUN_ID/events?after=0&limit=200"
```

详情返回 `run.state`、输出和失败原因；graph 返回节点、任务、Attempt 及子运行。events 是 JSON 历史查询，按 `sequence` 递增读取，下一次将最后处理的 sequence 作为 `after`。它不是 SSE 连接。对外发布为 Endpoint 后，应用可以改用该 Invocation 的 [SSE](/v2/zh/service/sse-events)跟进调用。

示例中的 review 节点会生成审批，指定用户通过 [Approval API](/v2/zh/service/inbox#处理执行中的审批)作出决定。节点审批放行流程，Issue 的最终验收判断业务交付是否完成，两者分别记录。若后续步骤会发布文件或修改外部系统，执行 Agent 仍须具备对应工具与权限。

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
api "$SERVICE_URL/api/v1/orchestration-runs/$RUN_ID/signals/report.ready" \
  --data '{"idempotencyKey":"report-upload-001","payload":{"artifactId":"YOUR_ARTIFACT_ID"}}'
```

signal 是流程的外部输入，不会自动替代审批。更复杂的流程先运行小样本，查看真实节点输出，再编写下游映射；不要假定不同运行时的结果结构完全相同。

## 暂停、取消与重新执行

`POST /api/v1/orchestration-runs/{runId}/pause` 阻止新节点调度，已运行步骤仍可能返回；`/resume` 恢复调度；`/cancel` 请求取消节点、任务和子运行，不会删除或验收 Issue。请求体可使用 `{}`。

终态后向 `/rerun` 提交 `{"idempotencyKey":"weekly-report-retry-001"}` 创建新的 Run，保留与原运行的关联；可加 `input` 替换输入。节点支持 `timeoutSeconds`、`retry` 和 `failurePolicy`，其中失败策略可选 `fail_fast`、`continue`、`partial_success`。重试不保证撤销已经发生的外部副作用。

发布给业务应用时，在 [Endpoint API](/v2/zh/service/endpoints) 中使用 `targetType:"orchestration_revision"` 和具体 revision ID。发布新 Workflow revision 后，还需要更新 Endpoint release 才会切换调用目标。控制台设计器与运行图的操作见[控制台：团队与编排](/v2/zh/service/console/orchestration)。
