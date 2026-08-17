# Simple Knowledge

`agentscope-extensions-rag-simple` is the "DIY end-to-end" RAG implementation: it bundles document readers, chunking strategies, embedding adapters, and five out-of-the-box vector store adapters.

Use it when: you're happy to run embeddings + vector store yourself and don't want a third-party RAG platform.

## Add the dependency

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-rag-simple</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## Quickstart

```java
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.store.InMemoryStore;
import io.agentscope.core.rag.model.RetrieveConfig;

// 1) Embedding model
EmbeddingModel embeddings = DashScopeTextEmbedding.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("text-embedding-v3")
    .dimensions(1024)
    .build();

// 2) Vector store (in-process here)
VDBStoreBase store = InMemoryStore.builder().dimensions(1024).build();

// 3) Assemble Knowledge
SimpleKnowledge knowledge = SimpleKnowledge.builder()
    .embeddingModel(embeddings)
    .embeddingStore(store)
    .build();

// 4) Ingest documents
List<Document> docs = new TikaReader().read(input).block();
knowledge.addDocuments(docs).block();

// 5) Retrieve
List<Document> hits = knowledge.retrieve(
    "What is AgentScope?",
    RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build()
).block();
```

## Built-in document readers

The `io.agentscope.core.rag.reader` package contains readers for common formats; each produces `List<Document>`:

| Reader | Input |
| --- | --- |
| `TextReader` | Plain text |
| `PDFReader` | PDF (PDFBox-backed) |
| `WordReader` | Microsoft Word documents |
| `ImageReader` | Images, paired with multimodal embeddings |
| `TikaReader` | Generic Apache Tika fallback |
| `ExternalApiReader` | External parser APIs (OCR / custom pipelines) |

The resulting `Document` objects already carry metadata; pair with `TextChunker` and `SplitStrategy` for chunking.

## Built-in embedding providers

| Class | Service | Mode |
| --- | --- | --- |
| `DashScopeTextEmbedding` | Alibaba Cloud DashScope | Text |
| `DashScopeMultiModalEmbedding` | Alibaba Cloud DashScope | Multimodal (text/image) |
| `OpenAITextEmbedding` | OpenAI-compatible API | Text |
| `OllamaTextEmbedding` | Local Ollama | Text |

Implement `EmbeddingModel` to add your own.

## Built-in vector stores

| Implementation | Deployment |
| --- | --- |
| `InMemoryStore` | In-process (dev / testing) |
| `PgVectorStore` | PostgreSQL + pgvector |
| `MilvusStore` | Milvus |
| `QdrantStore` | Qdrant |
| `ElasticsearchStore` | Elasticsearch (`dense_vector`) |

Switching stores is a one-line change: pass a different `VDBStoreBase` to `SimpleKnowledge.builder().embeddingStore(...)`.

## Retrieval parameters

`RetrieveConfig` controls retrieval:

| Field | Notes |
| --- | --- |
| `limit` | Top-K |
| `scoreThreshold` | Minimum score (0–1) |
| `metadata` | Filter by document metadata |

## Wire into an Agent

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .knowledge(knowledge)
    .ragMode(RAGMode.AGENTIC)   // Agent decides when to retrieve
    .build();
```
