# RAGFlow Knowledge

`agentscope-extensions-rag-ragflow` integrates with [RAGFlow](https://ragflow.io/). RAGFlow is strong on document parsing (OCR, table extraction, knowledge graph augmentation) and shines for unstructured-heavy KBs.

## When to use

- Your KB is mostly scanned PDFs, complex layouts, or images and tables.
- You want to use RAGFlow's chunking strategies and reranker.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-rag-ragflow</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Quickstart

```java
import io.agentscope.core.rag.integration.ragflow.RAGFlowConfig;
import io.agentscope.core.rag.integration.ragflow.RAGFlowKnowledge;
import io.agentscope.core.rag.model.RetrieveConfig;

RAGFlowConfig config = RAGFlowConfig.builder()
    .apiKey("ragflow-xxxxxxxx")
    .baseUrl("http://localhost:9380")
    .knowledgeBaseId("kb-xxxxx")
    .topK(10)
    .similarityThreshold(0.5)
    .enableRerank(true)
    .build();

RAGFlowKnowledge knowledge = RAGFlowKnowledge.builder()
    .config(config)
    .build();

List<Document> hits = knowledge.retrieve(
    "What is AI?",
    RetrieveConfig.builder().limit(5).build()
).block();
```

## How it works

The plugin calls RAGFlow's `POST /api/v1/datasets/{dataset_id}/retrieve-chunks`:

- Vector similarity search with configurable `topK`.
- Server-side `similarityThreshold` filtering.
- Metadata filtering via `RAGFlowConfig`.
- Optional RAGFlow rerank.

> Note: RAGFlow's retrieve-chunks API does not currently accept conversation history for context-aware retrieval. If you need that, prepend instructions into the query yourself.

## Document management goes through RAGFlow

`addDocuments(...)` is unsupported — upload and index documents through the RAGFlow console or its native API.

## Key parameters

| Field | Notes |
| --- | --- |
| `apiKey` | RAGFlow API key (required) |
| `baseUrl` | RAGFlow service URL (required) |
| `knowledgeBaseId` | Dataset / KB ID (required) |
| `topK` | Server-side top-K |
| `similarityThreshold` | Server-side minimum similarity |
| `enableRerank` | Enable rerank |
