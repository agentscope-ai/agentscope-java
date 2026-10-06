---
title: "Memory: shared knowledge"
zh_link: /v2/zh/service/memory
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

A Memory Store holds shared documents for Managed Agents: terminology, operating guidance, and durable facts. Applications maintain it through `/api/memory-stores`, and Agents use mounted memory tools on demand. It is separate from Chat history, Session working memory, and Issue comments.

## Management APIs and versions

Use a platform user Bearer token and `X-AgentScope-Tenant` and `X-AgentScope-Namespace`; prepare variables as in the [API quickstart](/v2/en/service/first-session). Reads require inspect, mutations require edit, and creation requires namespace resource creation rights. Listings contain only inspectable Stores.

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

## Create a Store and add knowledge

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

## Inspect in the console

<Frame caption="Current console UI with fixed demonstration data.">
  <img src="/imgs/service/memory.png" alt="Memory store and its memory files" />
</Frame>

The same resources are available under **Resources → Memory**. See [Console](/v2/en/service/console/index) for graphical entry points.

## Bind and verify

Add the Store ID to `defaultMemoryStoreIds` through the [Agent definition API](/v2/en/service/managed-agent-configuration). Read and preserve other fields and include the current definition version when updating. Create a new Session, ask it to list knowledge documents and explain Lark, and inspect `memory_store_list`, `memory_store_read`, and source paths to verify it read the new content.

The Session API uses `memoryStoreIds` to override defaults. Omission inherits defaults; `[]` mounts no default Store for that Session. Mentioning a Store in Instructions does not create a binding.

Shared documents are read live on demand instead of copying their contents permanently into each Session. Update the test fact and request another tool read to verify the change. Earlier conversation messages retain their previous quotations. Use a new Session when changing resource bindings.

## Read and write access

Managed Agents discover and read bound documents on demand; the entire Store is not added to every prompt. Stores default to `read_write`: `memory_store_write` creates documents, and `memory_store_edit` performs exact text replacement with a concurrent-version check. Discussing a conclusion does not persist it; an actual write tool or management API operation changes shared content.

For knowledge that the Agent should only read, configure `memoryAccess` in the Session's [Environment](/v2/en/service/environments):

```json
{"memoryAccess":{"STORE_ID":"read_only"}}
```

Replace `STORE_ID` with the actual ID and preserve other config fields. `read_only` rejects runtime writes, while `read_write` permits them. This mount policy does not prevent an authorized application backend from maintaining documents through management APIs. Verify binding and mount policy changes in a new Session.

## Maintain documents

Use document PUT for ordinary edits and Redact when sensitive version history must be removed. An archived Store is excluded from active mounts; deleting it removes its documents. Check consumers and your backup requirements first.

If retrieval misses the expected knowledge, inspect the binding, archive state, document path and tool capabilities, then verify in a new conversation. Mentioning a Store name in instructions does not establish a resource binding.

Next: [Managed Agents](/v2/en/service/managed-agent) · [Vault](/v2/en/service/vault).

Start with the inline sources in the [CRM proposal case](/v2/en/service/cases/in-product-delivery) to verify citations and missing information. Then move its three sources into Memory and test authorized reads and source updates.
