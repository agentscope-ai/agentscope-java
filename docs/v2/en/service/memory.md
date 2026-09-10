---
title: "Memory: shared knowledge"
---

[简体中文](/v2/zh/service/memory)

**Resources → Memory** manages shared documents for Managed Agents: terminology, operating guidance and durable facts. It is separate from Chat history, Session working memory and Issue comments.

## Create a store

Select **New store**, add a name and description, then use **Add memory** for a document path and content. For example, `product/glossary.md` can contain definitions, sources and an update date. Bind the Store to an Agent and inspect consumers in its resource detail.

In a new Chat, ask a question requiring the document and request its source. Confirm that the Agent found the intended content through memory tools.

## Understand retrieval

Managed Agents discover and read bound documents as needed. The entire Store is not automatically added to every prompt. Shared knowledge is read-only during execution; maintain durable content here. Temporary Session conclusions do not automatically become shared knowledge.

## Maintain documents

Edit changes a document; Redact removes content requiring redaction; Delete removes an entry. Archiving a Store prevents mounting on new Sessions. Deleting a Store removes its documents. Check consumers and your backup requirements first.

If retrieval misses the expected knowledge, inspect the binding, archive state, document path and tool capabilities, then verify in a new conversation. Mentioning a Store name in instructions does not establish a resource binding.

Next: [Managed Agents](/v2/en/service/managed-agent) · [Vault](/v2/en/service/vault).
