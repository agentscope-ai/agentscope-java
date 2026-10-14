---
title: "Workspaces, Skills, and subagents"
description: "Create shared capability definitions, publish a Workspace revision, bind it to an Agent, and verify it in a new Session."
zh_link: /v2/zh/service/workspaces
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

A Workspace maintains instructions, Skills, tool connections, and internal subagent definitions that several Agents can reuse. Prepare the content, publish a revision, and select it through the Agent definition's `workspaceId` and `workspaceBinding.version`. Service resolves that revision into the Agent definition, so subsequently created Sessions can use those capabilities. Creating a Workspace or editing its files alone does not associate it with an Agent.

A Workspace defines how the Agent should work. An [Environment](/v2/en/service/environments) supplies the execution location for file and Shell tools, [Memory](/v2/en/service/memory) supplies shared knowledge that can change, and a [Vault](/v2/en/service/vault) supplies tool credentials. Session inputs and outputs follow the [files and artifacts](/v2/en/service/files) lifecycle; sharing a Workspace does not automatically share those files among Agents.

The following steps add shared report-review capabilities to `AGENT_ID` from [your first Managed Agent](/v2/en/service/create-managed-agent). Retain `BASE_URL`, `TOKEN`, `TENANT`, and `NAMESPACE`, and ensure you can edit the Agent and create and publish a Workspace. A complete API index appears at the end of this page.

## Create and maintain a draft

First create a reporting Workspace and write the team's shared guidance to `AGENTS.md`. The returned `id` identifies the Workspace for publication and binding. These changes affect its draft and do not replace a published revision.

```bash
WORKSPACE_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/workspaces" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data '{"name":"Reporting workspace","description":"Shared reporting guidance"}')
WORKSPACE_ID=$(printf '%s' "$WORKSPACE_JSON" | jq -er '.id')

curl --fail-with-body -sS -X PUT "$BASE_URL/api/workspaces/$WORKSPACE_ID/file" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data '{"path":"AGENTS.md","content":"Facts must have sources. Include separate Sources and Open Questions sections."}'
```

Creation generates an initial `AGENTS.md`, which you can update before adding skills, tools, and subagents. This request omits `tools`, so the platform supplies its default built-in toolset. To share a restricted tool configuration, prepare `tools` and `mcpServers` using the [tool guide](/v2/en/service/tools) and save them through the Workspace's `/tools` endpoint before publication. Referenced directories and task inputs still need to be prepared in the execution environment.

<span id="skills"></span>
<span id="add-a-report-review-skill"></span>
<span id="verify-actual-use"></span>
<span id="when-to-use-team-or-workflow"></span>

## Add Skills and subagents

Use the saved `WORKSPACE_ID` to add a report-review Skill and its checklist. This endpoint saves the Skill files and adds `report-review` to the Workspace's `skills` configuration. After publication, Agents inheriting that configuration can use it.

```bash
curl --fail-with-body -sS -X PUT \
  "$BASE_URL/api/workspaces/$WORKSPACE_ID/skills/report-review" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data '{"markdown": "---\nname: report-review\ndescription: Review a report against its supplied evidence.\n---\n\nRead the report and its sources. List unsupported claims, missing dates, and open questions. Never invent sources.\n", "resources": {"checklist.md": "- Every factual claim has a source.\n- Unknown dates remain unconfirmed.\n- Distinguish evidence from suggestions.\n"}}'
```

The Skill name identifies its resource path; frontmatter name and description help the Agent recognize its purpose. Use relative paths for `resources` keys. Scripts and reference files can accompany a Skill, but interpreters, programs, and network permissions must be prepared in the Environment.

If you upload `skills/report-review/SKILL.md` through the general file API, also select it in the Workspace's `skills` list, for example with `{"type":"workspace","name":"report-review"}`. Saving files and selecting a Skill are separate concerns; the dedicated Skill endpoint used here performs both. An Agent that overrides `skills` still uses its own selection.

### Add an internal subagent

To let the current Agent delegate specialist review to an internal subagent, save its responsibilities and instructions in the Workspace draft through `PUT /api/workspaces/{id}/subagents/{name}`. The request uses `description` and `inlineBody`, with optional model, tool, and working-directory settings. Like a Skill, it enters the selected definition through Workspace publication, Agent binding, and creation of a new Session. Saving the definition does not start a child task.

After delegation occurs, query `GET /api/v1/agent-sessions/{sessionId}/subagents` for child associations, then inspect child snapshots and events. Each child has its own event cursor, so do not reuse the parent's cursor. Confirm completion from the child's execution record and check that its result reaches the parent task. See [budgets](/v2/en/service/session-event-log#budgets) for aggregate usage.


## Publish and bind an Agent

Publish the prepared Workspace draft first. The returned `version` identifies the immutable publication, while `draftVersion` records the source draft. Save the publication as `WORKSPACE_VERSION` so the Agent binding can reference it.

```bash
REVISION_JSON=$(curl --fail-with-body -sS -X POST \
  "$BASE_URL/api/workspaces/$WORKSPACE_ID/publish" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE")
WORKSPACE_VERSION=$(printf '%s' "$REVISION_JSON" | jq -er '.version')
printf '%s' "$REVISION_JSON" | jq '{version, draftVersion, digest}'
```

Next, bind that publication to the existing `AGENT_ID`. The request reads and preserves the Agent's other writable fields before setting `workspaceId` and `workspaceBinding`. This example uses an empty `overrides` array, so tools, MCP connections, and Skills come from the Workspace. Existing Agent-specific instructions are preserved in `instructions` and appended to the shared guidance. To keep the Agent's own tool configuration, adjust the overrides as described below before saving.

```bash
CURRENT=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agents/$AGENT_ID/definition" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE")
UPDATED=$(printf '%s' "$CURRENT" | jq --arg workspace "$WORKSPACE_ID" \
  --argjson revision "$WORKSPACE_VERSION" '
  .definition | {
    name, description, system, model, maxIters, tools, mcpServers, skills, multiagent,
    workspaceId, workspacePath, workspaceBinding, defaultEnvironmentId,
    defaultVaultIds, defaultMemoryStoreIds, version
  }
  | .workspaceId = $workspace
  | .workspaceBinding = {
      version:$revision, overrides:[],
      instructions:(if .workspaceBinding != null then
        (.workspaceBinding.instructions // "") else (.system // "") end)
    }')
SAVED=$(curl --fail-with-body -sS -X PATCH "$BASE_URL/api/v1/agents/$AGENT_ID/definition" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$UPDATED")
AGENT_VERSION=$(printf '%s' "$SAVED" | jq -er '.definition.version')
printf '%s' "$SAVED" | jq '.definition | {version, workspaceId, workspaceBinding, skills, tools}'
```

The returned `definition.version` is the new Agent definition version, while `workspaceBinding.version` identifies the Workspace publication it uses. Do not interchange them. The PATCH request's top-level `version` checks concurrent Agent edits. On `409 Conflict`, reread the definition and review the changes before resubmitting.

`workspaceBinding.overrides` accepts `tools`, `mcpServers`, and `skills`. A listed field uses the Agent's own complete configuration; an omitted field uses the selected Workspace revision. For example, `["tools","mcpServers"]` keeps this Agent's tool and connection configuration while inheriting Workspace Skills. `instructions` adds requirements specific to this Agent.

Binding version `0` publishes and selects the current draft, without continuously following future edits. The example selects an explicit publication so maintainers can inspect what each Agent uses. Workspace snapshots contain instructions, tools, and definition files, excluding execution data such as `sessions`, `memory`, `logs`, `artifacts`, `inputs`, `outputs`, and `.git`.

## Verify in a new Session

Create a Session using the returned Agent definition version. The Session request does not need `workspaceId`, because the selected Workspace publication is already part of that definition.

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg agent "$AGENT_ID" --argjson version "$AGENT_VERSION" \
    '{target:{type:"agent",id:$agent,version:$version}}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
```

Follow the [Session guide](/v2/en/service/session-event-log) to submit a report and sources to `$SESSION_URL/turns`, explicitly requesting the `report-review` Skill. Include an unconfirmed date in a source, then check whether the Agent loads the procedure and checklist, preserves the uncertainty, and follows the shared `AGENTS.md` guidance. Tool calls, retrieved content, and the final answer together establish whether the capability works; file existence or a model's claim alone does not.

After editing a Workspace draft, publish again, update the Agent's binding revision, and create a new Session. Publishing alone does not update every consumer, and changing an Agent does not replace existing Session definitions. For shared Workspaces, inspect `/api/workspaces/{id}/agents` and arrange updates for each consumer.

## Inspect in the console

<Frame caption="Current console UI with fixed demonstration data.">
  <img src="/imgs/service/workspaces.png" alt="Shared workspace list" />
</Frame>

Edit and publish shared content under **Resources → Workspaces**. Then open the target Agent's **Definition → Workspace**, select the Workspace and publication, review overrides and instructions, and choose **Save definition binding**. Publishing the resource and saving the Agent binding are separate steps. Check both displayed versions before creating a new Session.

## Choose the right content

| Content | Purpose |
| --- | --- |
| AGENTS.md | Project operating guidance and shared constraints |
| Skills | Reusable procedures and supporting files |
| Tools / MCP configuration | External capability connections |
| Subagents | Specialist delegation definitions |

Use [Memory](/v2/en/service/memory) for shared knowledge and [Vault](/v2/en/service/vault) for secrets. Reference credentials explicitly in tool connections instead of storing plaintext.

## Execution directories

Managed Agents access inputs, temporary files and outputs through their [Environment](/v2/en/service/environments). The Workspace supplies capability definitions; the Environment supplies the actual filesystem and Shell execution location. Creating a Workspace does not start a Worker or install programs. Bind the definition, then verify paths and dependencies in the actual Environment.

Inspect consumers before editing and verify changes with new work. Resolve dependent references before deleting a shared Workspace. Backups need both database references and Workspace storage.

## APIs and access

Authenticate with a platform user Bearer token and select a scope with `X-AgentScope-Tenant` and `X-AgentScope-Namespace`. Reading, editing, and publishing require the corresponding inspect, edit, and publish resource permissions; creation requires namespace resource creation rights. Set variables as shown in the [API identity setup](/v2/en/service/create-managed-agent#api-setup). Below, `{id}` is the returned Workspace ID; encode URL parameters.

| Operation | API | Request or response |
| --- | --- | --- |
| List and create | `GET /api/workspaces`, `POST /api/workspaces` | GET returns an array; creation takes `name` and optional `description`, `tools`, `mcpServers`, `skills`, and returns the resource |
| Read, update, delete | `GET/PATCH/DELETE /api/workspaces/{id}` | PATCH accepts the creation fields; responses include `id`, `version`, configuration, and timestamps; deletion returns 204 |
| File listing | `GET /api/workspaces/{id}/files` | `{files:[paths...]}` |
| Read or delete a file | `GET/DELETE /api/workspaces/{id}/file?path=AGENTS.md` | GET returns `path` and `content`; DELETE returns 204 |
| Write a file | `PUT /api/workspaces/{id}/file` | `{path,content}`, returning `path` |
| Tool configuration | `GET/PUT /api/workspaces/{id}/tools` | `tools` and `mcpServers`; PUT replaces both collections |
| Skills | `GET /api/workspaces/{id}/skills`; `GET/PUT/DELETE .../skills/{name}` | PUT takes `markdown` and optional `resources:{relativePath:content}` |
| Subagents | `GET /api/workspaces/{id}/subagents`; `PUT/DELETE .../subagents/{name}` | PUT takes `description`, `inlineBody`, and optional `model`, `maxIters`, `tools`, `workspaceMode`, `workspacePath`, `sourceAgentId` |
| Publish and list revisions | `POST /api/workspaces/{id}/publish`, `GET .../revisions` | Publish needs no body and returns a revision; listing returns `{items:[...]}` |
| Consumers | `GET /api/workspaces/{id}/agents` | `{items:[{id,name,version}]}` |

The Workspace resource's `version` tracks the draft. Draft PATCH, file, and capability writes currently have no `expectedVersion` condition; avoid concurrent overwrites. Published revisions are immutable snapshots. Publishing identical content again returns the existing revision. Deleting a Workspace still referenced by Agents returns 409.
