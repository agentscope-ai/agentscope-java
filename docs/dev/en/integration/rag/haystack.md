---
title: HayStack
zh_link: /dev/zh/integration/rag/haystack
---

`agentscope-extensions-rag-haystack` connects AgentScope to a [HayStack](https://haystack.deepset.ai/) RAG service. Document management and indexing happen on the HayStack side; AgentScope only invokes its retrieval API.

## When to use

- You already run RAG on HayStack (indexing pipeline, ChromaDB, rerankers, etc.).
- You want to reuse HayStack's end-to-end retrieval capability.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-rag-haystack</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Quickstart

```java
import io.agentscope.core.rag.integration.haystack.HayStackConfig;
import io.agentscope.core.rag.integration.haystack.HayStackKnowledge;
import io.agentscope.core.rag.model.RetrieveConfig;

HayStackConfig config = HayStackConfig.builder()
    .baseUrl("http://localhost:8080")  // your HayStack service
    .topK(10)
    .build();

HayStackKnowledge knowledge = HayStackKnowledge.builder()
    .config(config)
    .build();

List<Document> hits = knowledge.retrieve(
    "What is AI?",
    RetrieveConfig.builder().limit(5).build()
).block();
```

## Wire into an Agent

```java
ReActAgent agent = ReActAgent.builder()
    .knowledge(knowledge)
    .ragMode(RAGMode.AGENTIC)
    .build();
```

## Document management is not handled here

`addDocuments(...)` throws `UnsupportedOperationException`. To add or update documents:

1. Place source files in HayStack's pipeline source directory.
2. Trigger / re-run HayStack's indexing pipeline.
3. Once indexing completes, this plugin can retrieve the new documents.

This separation prevents inconsistent indexing states across two sides.

## Key parameters

| Field | Notes |
| --- | --- |
| `baseUrl` | HayStack service URL (required) |
| `topK` | Default top-K |
| `filterPolicy` | Filter policy (see `FilterPolicy`) |

`HayStackConfig` also exposes timeouts, custom headers, and API keys to fit your HayStack deployment's auth scheme.

## HTTP failures and retries

When retries are enabled, server errors may be retried with backoff, while client errors such as HTTP 401 are returned immediately. If the final response is an HTTP error, retrieval fails with `HayStackApiException` carrying that response's status code. Use the code to distinguish authentication or request failures from an unavailable service; enabling retries does not turn a failed response into an empty result.
