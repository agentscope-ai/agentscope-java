# Dify Knowledge

`agentscope-extensions-rag-dify` integrates with [Dify](https://dify.ai/) datasets, reusing knowledge bases you already maintain in Dify.

## When to use

- Your team operates content and document management in Dify.
- You want to leverage Dify's multiple retrieval modes (keyword / semantic / hybrid / full-text).

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-rag-dify</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Quickstart

```java
import io.agentscope.core.rag.integration.dify.DifyKnowledge;
import io.agentscope.core.rag.integration.dify.DifyRAGConfig;
import io.agentscope.core.rag.integration.dify.RetrievalMode;
import io.agentscope.core.rag.model.RetrieveConfig;

DifyRAGConfig config = DifyRAGConfig.builder()
    .apiKey(System.getenv("DIFY_RAG_API_KEY"))
    .datasetId("your-dataset-id")
    .retrievalMode(RetrievalMode.HYBRID_SEARCH)
    .enableRerank(true)
    .build();

DifyKnowledge knowledge = DifyKnowledge.builder()
    .config(config)
    .build();

List<Document> hits = knowledge.retrieve(
    "How do I renew my membership?",
    RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build()
).block();
```

## Retrieval modes

`RetrievalMode` selects how Dify searches the dataset:

| Enum | Description |
| --- | --- |
| `KEYWORD_SEARCH` | Keyword only |
| `SEMANTIC_SEARCH` | Vector / semantic only |
| `HYBRID_SEARCH` | Keyword + vector hybrid (recommended) |
| `FULL_TEXT_SEARCH` | Full-text |

## Self-hosted Dify

Point `baseUrl` at your deployment:

```java
DifyRAGConfig config = DifyRAGConfig.builder()
    .apiKey("dataset-xxx")
    .baseUrl("https://dify.mycompany.com")
    .datasetId("ds-xxxx")
    .retrievalMode(RetrievalMode.HYBRID_SEARCH)
    .build();
```

## Metadata filtering

Use `MetadataFilter / MetadataFilterCondition` to filter by metadata fields you've configured in Dify:

```java
DifyRAGConfig config = DifyRAGConfig.builder()
    .apiKey(apiKey)
    .datasetId(datasetId)
    .retrievalMode(RetrievalMode.HYBRID_SEARCH)
    .metadataFilter(MetadataFilter.builder()
        .conditions(List.of(
            MetadataFilterCondition.builder()
                .name("category").comparisonOperator("=")
                .value(List.of("faq")).build()))
        .logicalOperator("and")
        .build())
    .build();
```

## Retrieval only

`addDocuments(...)` is unsupported — use the Dify console: log in → Knowledge → choose dataset → upload documents → wait for indexing. This matches Bailian, HayStack, and RAGFlow.

## Key parameters

| Field | Notes |
| --- | --- |
| `apiKey` | Dify dataset API key (required) |
| `datasetId` | Dataset ID (required) |
| `baseUrl` | Default `https://api.dify.ai/v1`, override for self-hosted |
| `retrievalMode` | See table above |
| `enableRerank` | Enable rerank |
| `metadataFilter` | Metadata filter conditions |
