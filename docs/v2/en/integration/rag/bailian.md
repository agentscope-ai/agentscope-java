# Bailian Knowledge

`agentscope-extensions-rag-bailian` integrates Alibaba Cloud Bailian Knowledge Base — embeddings, indexing, and retrieval are all managed by Bailian. The Agent only sends the query and receives documents back.

## When to use

- Your documents are already uploaded and processed in the Bailian console.
- You want enterprise features: rerank, filtering, and structured / unstructured / image KBs.
- You don't want to run your own vector store.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-rag-bailian</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Quickstart

```java
import io.agentscope.core.rag.integration.bailian.BailianConfig;
import io.agentscope.core.rag.integration.bailian.BailianKnowledge;
import io.agentscope.core.rag.model.RetrieveConfig;

BailianConfig config = BailianConfig.builder()
    .accessKeyId(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"))
    .accessKeySecret(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"))
    .workspaceId("llm-xxxxxx")
    .indexId("kb-xxxxxx")
    .build();

BailianKnowledge knowledge = BailianKnowledge.builder()
    .config(config)
    .build();

List<Document> hits = knowledge.retrieve(
    "How do I request an invoice?",
    RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build()
).block();
```

## Wire into an Agent

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(chatModel)
    .knowledge(knowledge)
    .ragMode(RAGMode.AGENTIC)
    .build();
```

Or expose it as a tool:

```java
KnowledgeRetrievalTools tools = new KnowledgeRetrievalTools(knowledge);
Toolkit toolkit = new Toolkit();
toolkit.registerObject(tools);
```

## Rerank / rewrite

`BailianConfig.builder()` accepts optional `rerankConfig(...)` and `rewriteConfig(...)`:

```java
BailianConfig config = BailianConfig.builder()
    .accessKeyId(ak).accessKeySecret(sk)
    .workspaceId("llm-xxx").indexId("kb-xxx")
    .rerankConfig(RerankConfig.builder().enable(true).topN(5).build())
    .rewriteConfig(RewriteConfig.builder().enable(true).build())
    .build();
```

When enabled, Bailian re-ranks initial recall results or rewrites the query for better relevance — at the cost of more latency and quota usage. Turn on what you actually need.

## Retrieval only

`BailianKnowledge.addDocuments(...)` is unsupported — use the Bailian console or platform SDK to manage documents. This is consistent with Dify, HayStack, and RAGFlow integrations: third-party RAG platforms keep ingestion responsibility, the Java side only reads.

## Configuration

| Field | Notes |
| --- | --- |
| `accessKeyId / accessKeySecret` | Alibaba Cloud credentials (required) |
| `workspaceId` | Bailian workspace ID (required) |
| `indexId` | KB index ID (required) |
| `rerankConfig` | Rerank toggle and parameters |
| `rewriteConfig` | Query rewrite toggle and parameters |
