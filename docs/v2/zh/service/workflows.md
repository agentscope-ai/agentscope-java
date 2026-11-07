---
title: "编排 Workflow"
en_link: /v2/en/service/workflows
---

<Note>
本页使用 `2.1.0-BETA1` 预发布版本。
</Note>

当业务明确规定“生成报告 → 人工审批 → 交付”时，可以用 Workflow 保存步骤和依赖。先发布一个 revision，再创建以该 Workflow 为目标的 Session，即可通过 Turn 提交任务。需要由 Leader 动态拆分工作时，使用 [Team](/v2/zh/service/create-team)；两者采用相同的应用调用路径。

Workflow 有可编辑的定义和发布后不可变的 revision。每次 Run 固定一个 revision，因此修改草稿不会改变已经开始的执行。

可以直接使用已经验证的 Managed Agent 作为流程节点；需要分工时再让节点调用 Team。先[运行一个托管 Agent](/v2/zh/service/create-managed-agent)，再固定流程与审批人。选型见[多 Agent 协作](/v2/zh/service/orchestration)。

## 创建并校验流程

以下示例使用 Bash、`curl` 和 `jq`。先按[认证与空间](/v2/zh/service/api-reference#认证与空间)准备 `BASE_URL`（Service 地址）、`TENANT`、`NAMESPACE`，在同一个 Bash 终端执行后续步骤：

```bash
set -euo pipefail
```

将 `AGENT_ID` 设为已经可执行任务的 Agent ID，`APPROVER_ID` 设为审批人的账号标识。下面的流程先生成报告，再等待人工审批：

```bash
WORKFLOW_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/orchestration-definitions" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "tenant": "$TENANT",
  "namespace": "$NAMESPACE",
  "name": "报告复核流程",
  "draftSpec": {
    "nodes": [
      {
        "key": "draft",
        "type": "agent",
        "agentId": "$AGENT_ID",
        "input": {
          "request": "run.input.request"
        }
      },
      {
        "key": "review",
        "type": "approval",
        "approval": {
          "approverType": "human",
          "approverRef": "$APPROVER_ID",
          "prompt": "请检查报告及证据"
        }
      }
    ],
    "edges": [
      {
        "from": "draft",
        "to": "review",
        "on": [
          "succeeded"
        ]
      }
    ]
  }
}
JSON
)
WORKFLOW_ID=$(jq -er '.definition.id' <<< "$WORKFLOW_JSON")
WORKFLOW_VERSION=$(jq -er '.definition.version' <<< "$WORKFLOW_JSON")
```

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/v1/orchestration-definitions/$WORKFLOW_ID/validate"
```

节点 `key` 在流程中唯一，边把前一步的状态与后一步连接。`input` 将输入字段映射为 CEL 表达式；这里将请求里的 `request` 交给 draft 节点。表达式可读取 `run`、`issue`、`trigger` 和前序 `nodes`，不能作为任意脚本执行器。服务端校验检查节点类型、引用、表达式及环路；目标运行时是否可用仍要在实际执行前确认。

## 发布版本并开始执行

校验通过后发布当前定义，保存返回的 revision ID。后续修改使用 `PATCH /api/v1/orchestration-definitions/{definitionId}`，传入 `draftSpec` 和 `expectedVersion`，然后再次发布。

```bash
PUBLISHED_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/orchestration-definitions/$WORKFLOW_ID/publish" \
    -H "Content-Type: application/json" \
    --data-binary @- <<JSON
{
  "expectedVersion": $WORKFLOW_VERSION
}
JSON
)
REVISION_ID=$(jq -er '.revision.id' <<< "$PUBLISHED_JSON")
```

```bash
SESSION_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: workflow-session-001" \
    --data-binary @- <<JSON
{
  "target": {
    "type": "workflow",
    "id": "$WORKFLOW_ID",
    "revisionId": "$REVISION_ID"
  }
}
JSON
)
SESSION_ID=$(jq -er '.id' <<< "$SESSION_JSON")
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

```bash
TURN_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: weekly-report-001" \
    --data-binary @- <<'JSON'
{
  "input": {
    "request": "Produce this week’s report with sources"
  }
}
JSON
)
TURN_ID=$(jq -er '.id' <<< "$TURN_JSON")
```

保存 Session ID 和 Turn ID，后续查询、审批和取消都围绕这两个资源进行。Service 会自动建立流程执行与工作记录。重试同一任务时沿用相同幂等键；新任务使用新的 key。

## 跟进节点、输出与审批

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions/$SESSION_ID/turns/$TURN_ID"
```

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/agent-sessions/$SESSION_ID/snapshot"
```

Turn 详情返回当前任务状态、结果和失败原因；Session 快照用于恢复应用界面。需要实时展示时，按照 [SSE 文档](/v2/zh/service/sse-events)从快照游标订阅增量事件。只有诊断流程内部节点时，才需要从控制台查看关联 Run 的执行图。

review 节点会出现在 Turn 的 required_actions 中。指定审批人使用自己的平台 token，向该 Turn 的 `/actions` 提交实际的 `request_id`、`expected_version` 和 `decision`。审批通过后流程继续，应用密钥不能替代指定人员的身份。

## 逐步增加流程能力

<Accordion title="答复 review 节点的审批">

以下沿用[API 身份准备](/v2/zh/service/create-managed-agent#api-setup)的本地地址和默认空间变量。

本地模式直接答复；生产审批身份见[生产部署](/v2/zh/service/kubernetes#production-api-access)。读取待办后核对审批内容，选择 review 节点对应的 request_id 与 expected_version，确认同意时再提交；不同意时使用 decision:"rejected"。

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions"
```

```bash
REQUEST_ID="REVIEW_REQUEST_ID_FROM_ACTIONS"
EXPECTED_VERSION="VERSION_FROM_ACTIONS"
```

```bash
ACTION_JSON=$(
  curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/actions" \
    -H "Content-Type: application/json" \
    -H "Idempotency-Key: workflow-review-001" \
    --data-binary @- <<JSON
{
  "request_id": "$REQUEST_ID",
  "expected_version": $EXPECTED_VERSION,
  "decision": "approved"
}
JSON
)
COMMAND_ID=$(jq -er '.command.id' <<< "$ACTION_JSON")
```

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/commands/$COMMAND_ID"
```

</Accordion>

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
REQUEST_ID="SIGNAL_REQUEST_ID_FROM_ACTIONS"
EXPECTED_VERSION="SIGNAL_VERSION_FROM_ACTIONS"
ARTIFACT_ID="YOUR_ARTIFACT_ID"
```

```bash
curl -sS --fail-with-body "$SESSION_URL/turns/$TURN_ID/inputs" \
  -H "Content-Type: application/json" \
  -H "Idempotency-Key: report-upload-001" \
  --data-binary @- <<JSON
{
  "request_id": "$REQUEST_ID",
  "expected_version": $EXPECTED_VERSION,
  "payload": {
    "artifactId": "$ARTIFACT_ID"
  }
}
JSON
```

请求中的 ID 和版本必须取自当前 required_actions，不能直接使用示例占位值。signal 是流程的外部输入，不会自动替代审批。更复杂的流程先运行小样本，查看真实节点输出，再编写下游映射；不要假定不同运行时的结果结构完全相同。

## 取消任务与运维控制

应用通过 Turn 的 `/cancel` 取消当前任务，通过 capabilities 判断是否可以 `/resume`；再次从头执行时提交一个新的 Turn。以下 Run 接口供控制台运维和流程诊断使用，Run ID 从关联执行记录获取，不是 Turn ID。


`POST /api/v1/orchestration-runs/{runId}/pause` 阻止新节点调度，已运行步骤仍可能返回；`/resume` 恢复调度；`/cancel` 请求取消节点、任务和子运行，不会删除或验收 Issue。请求体可使用 `{}`。

终态后向 `/rerun` 提交 `{"idempotencyKey":"weekly-report-retry-001"}` 创建新的 Run，保留与原运行的关联；可加 `input` 替换输入。节点支持 `timeoutSeconds`、`retry` 和 `failurePolicy`，其中失败策略可选 `fail_fast`、`continue`、`partial_success`。重试不保证撤销已经发生的外部副作用。

应用创建 Session 时，使用 `target:{"type":"workflow","id":"WORKFLOW_ID","revisionId":"REVISION_ID"}`。省略 `revisionId` 时，Service 在创建会话时选择最新的已发布版本；已有会话不会自动切换到新版本。控制台设计器与运行图见[控制台](/v2/zh/service/console/index#console-orchestration)。

<span id="curl-management"></span>

## 查询与修改草稿

以下沿用[API 身份准备](/v2/zh/service/create-managed-agent#api-setup)的本地地址和默认空间变量。

```bash
curl -sS --fail-with-body -G "$BASE_URL/api/v1/orchestration-definitions" \
  --data-urlencode "tenant=$TENANT" \
  --data-urlencode "namespace=$NAMESPACE"
```

```bash
WORKFLOW_JSON=$(
  curl -sS --fail-with-body "$BASE_URL/api/v1/orchestration-definitions/$WORKFLOW_ID"
)
```

以下保留当前节点和边，只修改流程名称。编辑 draftSpec 时同样保留其他配置，保存后校验并重新发布。新 revision 不会修改正在执行的 Run。

```bash
jq '.definition | {name,description,draftSpec,expectedVersion:.version}
  | .name = "Evidence review workflow"' <<< "$WORKFLOW_JSON" > workflow-update.json
```

```bash
curl -sS --fail-with-body -X PATCH "$BASE_URL/api/v1/orchestration-definitions/$WORKFLOW_ID" \
  -H "Content-Type: application/json" \
  --data-binary @workflow-update.json
```

```bash
curl -sS --fail-with-body -X POST "$BASE_URL/api/v1/orchestration-definitions/$WORKFLOW_ID/validate"
```

```bash
curl -sS --fail-with-body "$BASE_URL/api/v1/orchestration-definitions/$WORKFLOW_ID/revisions"
```
