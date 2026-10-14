---
title: "Memory: shared knowledge"
description: "Create a knowledge Store, bind it to a Managed Agent or Session, and verify read-only access and document updates."
zh_link: /v2/zh/service/memory
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

A Memory Store holds knowledge that multiple tasks can reuse, such as terminology, operating guidance, and verified facts. After creating a Store and adding documents, include its ID in an Agent's `defaultMemoryStoreIds` or select it through `memoryStoreIds` when creating a Session. The Agent uses `memory_store_list` and `memory_store_read` on demand; the documents are not automatically inserted into every model request.

Shared knowledge and conversation records have separate lifecycles. The current Managed HarnessAgent path mounts shared Stores read-only. Authorized users or application backends create and update documents through management APIs. Working notes, chat history, and task artifacts are not automatically written back to a Store. Reusable behavioral procedures belong in [Workspaces and Skills](/v2/en/service/workspaces).

Use the platform identity variables and an available `ENVIRONMENT_ID` from the [deployment guide](/v2/en/service/quickstart). The following steps create a sourced glossary entry and verify that a Managed Agent actually reads it.

## Create a Store and add knowledge

Create a product knowledge Store and write the project's definition of Lark to `product/glossary.md`. `expectedVersion: 0` requires that the path not yet exist, avoiding an accidental overwrite when repeating the exercise. Save `STORE_ID` for the resource binding below.

```bash
STORE_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/memory-stores" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data '{"name":"Product knowledge","description":"Verified terms and guidance"}')
STORE_ID=$(printf '%s' "$STORE_JSON" | jq -er '.id')

curl --fail-with-body -sS -X PUT \
  "$BASE_URL/api/memory-stores/$STORE_ID/memories/product/glossary.md" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data '{"content":"This project defines Lark as the weekly-report archival task. Source: project glossary.","expectedVersion":0}' \
  | jq '{id, path, headVersion}'
```

Store and document creation responses are objects, each with its own `id`. Record verified, reusable findings and their sources; do not maintain unconfirmed assumptions as facts.

## Bind and verify

A Store binding and tool configuration work together: the binding selects accessible knowledge, while tools provide the operations used to read it. The following creates a Managed knowledge assistant with `STORE_ID` as its default source and explicitly enables the two read operations. To reuse an existing Agent, update `defaultMemoryStoreIds` through [default resource configuration](/v2/en/service/managed-agent-configuration#set-default-resources-on-an-agent) and enable these operations using the [tool guide](/v2/en/service/tools#save-the-configuration-to-an-agent), retaining its other tools.

```bash
AGENT_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agents" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg tenant "$TENANT" --arg namespace "$NAMESPACE" \
    --arg env "$ENVIRONMENT_ID" --arg store "$STORE_ID" '{
      tenant:$tenant, namespace:$namespace,
      agentKey:"knowledge-assistant", displayName:"Knowledge assistant",
      binding:{kind:"managed"},
      definition:{name:"Knowledge assistant", defaultEnvironmentId:$env,
        defaultMemoryStoreIds:[$store],
        system:"Answer using bound knowledge documents. Locate and read relevant sources, cite their document paths, and report missing knowledge without guessing from names.",
        tools:[{type:"agent_toolset", defaultConfig:{enabled:false}, configs:[
          {name:"memory_store_list",enabled:true,permissionPolicy:{type:"always_allow"}},
          {name:"memory_store_read",enabled:true,permissionPolicy:{type:"always_allow"}}
        ]}]}
    }')")
AGENT_ID=$(printf '%s' "$AGENT_JSON" | jq -er '.agent.id')
printf '%s' "$AGENT_JSON" | jq '.definition | {version, defaultMemoryStoreIds, tools}'
```

After creation, `definition.defaultMemoryStoreIds` should contain this Store. The example uses `agentKey: "knowledge-assistant"`; retain the returned `AGENT_ID` when repeating the exercise, or choose another key for a separate Agent. Create a Session next, omitting `memoryStoreIds` so it inherits the saved default.

```bash
SESSION_JSON=$(curl --fail-with-body -sS "$BASE_URL/api/v1/agent-sessions" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "X-AgentScope-Tenant: $TENANT" -H "X-AgentScope-Namespace: $NAMESPACE" \
  --data "$(jq -n --arg agent "$AGENT_ID" '{target:{type:"agent",id:$agent}}')")
SESSION_ID=$(printf '%s' "$SESSION_JSON" | jq -er '.id')
SESSION_URL="$BASE_URL/api/v1/agent-sessions/$SESSION_ID"
printf '%s' "$SESSION_JSON" | jq '{id, target, memoryStoreIds}'
```

Check that the response's `memoryStoreIds` contains the actual `STORE_ID`, then submit a task asking the Agent to read the project glossary, explain Lark, and cite the document path. Follow snapshots and events for `$SESSION_URL` using the [Session guide](/v2/en/service/session-event-log). Verify a knowledge-tool read of `product/glossary.md` and an answer preserving the weekly-report archival definition. A similar answer without a corresponding read does not establish that the Store was used.

To select the Store for only one Session, explicitly send `memoryStoreIds: ["ACTUAL_STORE_ID"]` during Session creation. This replaces that Session's complete list rather than appending to the Agent defaults; include every desired ID when using multiple Stores. Omission inherits defaults, while `[]` attaches none of the default Stores. Changing an Agent's default list does not change existing Session selections.

## Read and write access

The current Managed HarnessAgent execution path mounts shared knowledge as `read_only`, allowing tasks to query maintained sources. Enabling `memory_store_write` or `memory_store_edit` from the tool catalog does not bypass that mount restriction. Environment `config.memoryAccess` accepts `read_only` and `read_write`, but selecting `read_write` does not enable shared-knowledge writes through the current Managed path.

Maintain knowledge through the management APIs on this page, which require resource editing permission. An application backend can review source material and PUT a document, while the running Agent reads it. To promote task output into shared knowledge, have the application explicitly review and write it; discussing a conclusion does not save it to the Store.

`memory_store_list` and `memory_store_read` access bound Stores through the platform. When file and Shell tools run on a self_hosted Worker, knowledge reads do not become access to a Worker-local directory. Mentioning a Store name or disk path, or enabling a read tool, does not replace binding the resource by ID.

## Maintain documents

Read the current content and `headVersion` before a document PUT, then submit the new content with that `expectedVersion`. On conflict, reread and merge instead of overwriting another maintainer's work. Use Redact when sensitive history must be removed. Archiving prevents subsequent resolution or reads from using the Store; deletion removes its documents and history. Inspect consumers and retention requirements before maintenance.

A Session pins the Store ID, not a snapshot of the shared document contents. After a management API update, submit another task in the same Session asking for a fresh read of the same path, and inspect the returned content. Earlier messages or artifacts retain their old quotations, so distinguish new tool results from conclusions already present in the conversation.

If retrieval misses the expected knowledge, inspect the binding, archive state, document path and tool capabilities, then verify in a new conversation. Mentioning a Store name in instructions does not establish a resource binding.

Next: [Managed Agents](/v2/en/service/index#managed-agent) · [Vault](/v2/en/service/vault).

Start with the inline sources in the [CRM proposal case](/v2/en/service/cases/in-product-delivery) to verify citations and missing information. Then move its three sources into Memory and test authorized reads and source updates.

## Inspect in the console

<Frame caption="Current console UI with fixed demonstration data.">
  <img src="/imgs/service/memory.png" alt="Memory store and its memory files" />
</Frame>

Maintain Stores and documents under **Resources → Memory**, then select defaults on the Managed Agent's **Runtime configuration → Session defaults → Default memory stores**. Inspect the Memory selection when creating a Session. A document existing on the resource page does not mean every Agent has access to it.

## Management APIs and versions

Use a platform user Bearer token and `X-AgentScope-Tenant` and `X-AgentScope-Namespace`; prepare variables as in the [deployment preparation](/v2/en/service/quickstart). Reads require inspect, mutations require edit, and creation requires namespace resource creation rights. Listings contain only inspectable Stores.

Below, `path` is a document path such as `product/glossary.md`. Encode individual URL path segments while preserving directory separators. `versions/` is reserved for history reads and should not prefix a document path.

| Operation | API | Parameters and response |
| --- | --- | --- |
| List and create Stores | `GET/POST /api/memory-stores` | GET returns an array; POST takes `name`, optional `description`, and returns `id`, name, description, and timestamps |
| Read and delete a Store | `GET/DELETE /api/memory-stores/{id}` | GET returns the resource; DELETE returns 204 and removes its documents |
| Archive | `POST /api/memory-stores/{id}/archive` | Returns `id`, `archivedAt` |
| List documents | `GET /api/memory-stores/{id}/memories` | Array of documents including `path`, `content`, and `headVersion` |
| Read and write a document | `GET/PUT /api/memory-stores/{id}/memories/{path}` | PUT takes `content`, optional `expectedVersion`; returns the document and new `headVersion` |
| Version history | `GET /api/memory-stores/{id}/memories/versions/{path}` | Array of `memoryId`, `version`, `content`, `createdAt`, newest version first |
| Delete a document | `DELETE /api/memory-stores/{id}/memories/{path}` | Removes content and version history; returns 204 |
| Redact | `POST /api/memory-stores/{id}/redact` | `path`, optional `replacement`; replaces content and clears old history; default replacement is `[REDACTED]` |

Store listings support `limit` (1–500), `offset` (requires limit), and `X-Total-Count`. There is currently no PATCH endpoint for a Store's name or description.

Each document PUT creates a version. Use `expectedVersion:0` for creation; for an update, read and submit the current `headVersion`. A changed version returns 409, requiring a reread and merge. Omitting the condition allows overwriting current content. Ordinary updates retain old versions. Redact removes sensitive content from stored history but does not change text already copied into sessions, artifacts, or other systems.
