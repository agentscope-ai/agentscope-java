---
title: Overview
zh_link: /dev/zh/integration/rag/overview
---

For new applications, start with [RAG integration and migration](/dev/en/integration/rag/migration). It explains retrieval before the agent call and application-owned retrieval tools, without depending on deprecated RAG Builder methods.

`io.agentscope.core.rag.Knowledge` is the legacy compatibility interface for plugging in an external knowledge base. Existing integrations remain functional on current `main`, but the interface and Builder wiring are deprecated for removal. The `agentscope-extensions-*` modules provide the following adapters:

| Extension | Type | Best for |
| --- | --- | --- |
| [Simple](/dev/en/integration/rag/simple) | Self-managed: embeddings + vector store | Bring-your-own vector store (PgVector / Milvus / Qdrant / Elasticsearch / in-memory) |
| [Bailian](/dev/en/integration/rag/bailian) | Alibaba Cloud Bailian Knowledge Base | Use a Bailian-hosted enterprise KB |
| [Dify](/dev/en/integration/rag/dify) | Dify dataset | Already maintaining KB content in Dify |
| [HayStack](/dev/en/integration/rag/haystack) | Self-hosted HayStack RAG | Existing HayStack pipelines |
| [RAGFlow](/dev/en/integration/rag/ragflow) | RAGFlow service | Complex documents needing OCR / knowledge graphs |

## Legacy Builder wiring

This example is for existing applications using the compatibility layer, not the recommended starting point for new code.

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .knowledge(knowledge)        // any Knowledge implementation
    .ragMode(RAGMode.AGENTIC)    // or GENERIC, NONE
    .build();
```

Existing applications can also register the legacy retrieval tool explicitly. `KnowledgeRetrievalTools` is itself deprecated; new tools can wrap the application's retrieval service directly, as shown in the migration guide.

```java
KnowledgeRetrievalTools tools = new KnowledgeRetrievalTools(knowledge);
Toolkit toolkit = new Toolkit();
toolkit.registerTool(tools);
```

## Choosing one

| Need | Recommendation |
| --- | --- |
| Full control over embeddings + store | **Simple** |
| Alibaba Cloud ecosystem, enterprise hosting | **Bailian** |
| Team already curates content in Dify | **Dify** |
| Complex ETL (PDF tables, OCR, knowledge graphs) | **RAGFlow** |
| Existing HayStack RAG pipeline | **HayStack** |

> Aside from Simple, the platform-backed implementations are **retrieval only**: document ingestion / updates happen via the platform console or its native API. This keeps `Knowledge` implementations swappable on the Agent side.
