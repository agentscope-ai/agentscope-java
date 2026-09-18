---
title: "Memory: shared knowledge"
---

<Note>
This is preview documentation. The official release is not yet available.
</Note>

**Resources → Memory** manages shared documents for Managed Agents: terminology, operating guidance and durable facts. It is separate from Chat history, Session working memory and Issue comments.

## Interface tour

<Frame caption="Current console UI with fixed demonstration data.">
  <img src="/imgs/service/memory.png" alt="Memory store and its memory files" />
</Frame>

Select a store, then inspect its file paths and contents. Add verified, reusable findings to Memory; review temporary discussion and assumptions before turning them into shared knowledge.

## Create a store

Select **New store**, add a name and description, then use **Add memory** for a document path and content. For example, `product/glossary.md` can contain definitions, sources and an update date. Bind the Store to an Agent and inspect consumers in its resource detail.

In a new Chat, ask a question requiring the document and request its source. Confirm that the Agent found the intended content through memory tools.

## Bind and verify

1. Select the Store in the Managed Agent's **Runtime → Session defaults → Default memory stores** and save. This sets `defaultMemoryStoreIds`.
2. Add a distinctive demonstration fact to `product/glossary.md`, such as “This project defines Lark as the weekly-report archival task.” Start a new Chat and ask it to list knowledge documents and explain Lark.
3. Inspect tool activity: `memory_store_list` discovers mounted content and `memory_store_read` reads the document. Check the answer and source path so prior model knowledge does not look like successful binding.

The Session API uses `memoryStoreIds` to override defaults. Omission inherits defaults; `[]` mounts no default Store for that Session. Mentioning a Store in Instructions does not create a binding.

Shared documents are read live on demand instead of copying their contents permanently into each Session. Update the test fact and request another tool read to verify the change. Earlier conversation messages retain their previous quotations. Use a new Session when changing resource bindings.

## Understand retrieval

Managed Agents discover and read bound documents as needed. The entire Store is not automatically added to every prompt. Shared knowledge is read-only during execution; maintain durable content here. Temporary Session conclusions do not automatically become shared knowledge.

## Maintain documents

Edit changes a document; Redact removes content requiring redaction; Delete removes an entry. Archiving a Store prevents mounting on new Sessions. Deleting a Store removes its documents. Check consumers and your backup requirements first.

If retrieval misses the expected knowledge, inspect the binding, archive state, document path and tool capabilities, then verify in a new conversation. Mentioning a Store name in instructions does not establish a resource binding.

Next: [Managed Agents](/v2/en/service/managed-agent) · [Vault](/v2/en/service/vault).

Practice with the [presales Team](/v2/en/service/cases/presales-team): bind product and delivery knowledge, cite sources, identify missing information, and reread updated documents.
