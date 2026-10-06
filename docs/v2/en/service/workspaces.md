---
title: "Workspaces: shared instructions and capabilities"
zh_link: /v2/zh/service/workspaces
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

Workspaces store reusable Agent material: `AGENTS.md`, skills, tools, and subagent definitions. Applications create, maintain, and publish them through `/api/workspaces`, then bind a published version to an Agent. A Workspace is separate from an account Namespace and an execution's temporary directory.

## APIs and access

Authenticate with a platform user Bearer token and select a scope with `X-AgentScope-Tenant` and `X-AgentScope-Namespace`. Reading, editing, and publishing require the corresponding inspect, edit, and publish resource permissions; creation requires namespace resource creation rights. Set variables as shown in the [API quickstart](/v2/en/service/first-session). Below, `{id}` is the returned Workspace ID; encode URL parameters.

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

## Create and maintain a draft

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

Creation generates an initial `AGENTS.md`. Update it with project guidance, then add skills, tools, and subagents as needed. Prepare any referenced directories and input files yourself; writing instructions does not create task materials.

## Publish and bind an Agent

```bash
REVISION_JSON=$(curl --fail-with-body -sS -X POST \
  "$BASE_URL/api/workspaces/$WORKSPACE_ID/publish" \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE")
printf '%s' "$REVISION_JSON" | jq '{version, draftVersion, digest}'
```

The revision's `version` identifies the publication, `draftVersion` identifies its source draft, and `digest` identifies its content. The snapshot contains instructions, tools, and definition files; it excludes execution data such as `sessions`, `memory`, `logs`, `artifacts`, `inputs`, `outputs`, and `.git`.

Set `workspaceId` and `workspaceBinding` through the [Agent definition API](/v2/en/service/managed-agent-configuration). When updating an existing definition, GET it first, preserve other fields, and include its current `version`. This is the binding fragment:

```json
{
  "workspaceId": "WORKSPACE_ID",
  "workspaceBinding": {
    "version": 1,
    "overrides": [],
    "instructions": "This Agent writes weekly reports with an Open Questions section."
  }
}
```

Replace `version: 1` with the published revision. `overrides` can contain `tools`, `mcpServers`, and `skills`, selecting the Agent's own definition for those fields; an empty array inherits them. `instructions` appends Agent-specific guidance. Binding version 0 explicitly publishes and selects the current draft, rather than continuously following it. A new association without a binding also resolves to a fixed version.

Editing the Workspace and updating an Agent binding are separate operations. Existing Sessions use resolved definition snapshots; a new Workspace publication does not rewrite them. Update each consuming Agent separately, then create a new session to verify instructions, skills, and tools.

## Inspect in the console

<Frame caption="Current console UI with fixed demonstration data.">
  <img src="/imgs/service/workspaces.png" alt="Shared workspace list" />
</Frame>

Inspect the same resources under **Resources → Workspaces**. See [Console](/v2/en/service/console/agents) for graphical entry points and Agent binding.

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
