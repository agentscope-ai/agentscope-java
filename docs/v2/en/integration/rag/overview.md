# RAG Knowledge Base

`io.agentscope.core.rag.Knowledge` is AgentScope's interface for plugging in an external knowledge base. The Agent uses it during inference to retrieve documents that are then handed to the model. The `agentscope-extensions-*` repository ships several implementations:

| Extension | Type | Best for |
| --- | --- | --- |
| [Simple](simple.md) | Self-managed: embeddings + vector store | Bring-your-own vector store (PgVector / Milvus / Qdrant / Elasticsearch / in-memory) |
| [Bailian](bailian.md) | Alibaba Cloud Bailian Knowledge Base | Use a Bailian-hosted enterprise KB |
| [Dify](dify.md) | Dify dataset | Already maintaining KB content in Dify |
| [HayStack](haystack.md) | Self-hosted HayStack RAG | Existing HayStack pipelines |
| [RAGFlow](ragflow.md) | RAGFlow service | Complex documents needing OCR / knowledge graphs |

## Same wiring everywhere

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .knowledge(knowledge)        // any Knowledge implementation
    .ragMode(RAGMode.AGENTIC)    // or STATIC, NONE
    .build();
```

You can also expose retrieval as a tool that the Agent calls explicitly:

```java
KnowledgeRetrievalTools tools = new KnowledgeRetrievalTools(knowledge);
Toolkit toolkit = new Toolkit();
toolkit.registerObject(tools);
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
