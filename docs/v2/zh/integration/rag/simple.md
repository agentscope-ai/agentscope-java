---
title: Simple
en_link: /v2/en/integration/rag/simple
---

`agentscope-extensions-rag-simple` 提供一个"自己掌控全部链路"的 RAG 实现：自带文档读取器、分块策略、Embedding 模型适配、以及 5 个开箱即用的向量库适配器。

适合：你愿意自己跑 embedding + 向量库，不想接入第三方 RAG 平台。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-rag-simple</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 快速上手

```java
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.store.InMemoryStore;
import io.agentscope.core.rag.model.RetrieveConfig;

// 1) Embedding 模型
EmbeddingModel embeddings = DashScopeTextEmbedding.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("text-embedding-v3")
    .dimensions(1024)
    .build();

// 2) 向量库（这里用进程内的实现）
VDBStoreBase store = InMemoryStore.builder().dimensions(1024).build();

// 3) 组装 Knowledge
SimpleKnowledge knowledge = SimpleKnowledge.builder()
    .embeddingModel(embeddings)
    .embeddingStore(store)
    .build();

// 4) 写入文档
List<Document> docs = new TikaReader().read(input).block();
knowledge.addDocuments(docs).block();

// 5) 检索
List<Document> hits = knowledge.retrieve(
    "什么是 AgentScope？",
    RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build()
).block();
```

## 内置文档读取器

`io.agentscope.core.rag.reader` 包提供了一组常见格式的 Reader，全部产出 `List<Document>`：

| Reader | 输入 |
| --- | --- |
| `TextReader` | 纯文本 |
| `PDFReader` | PDF（基于 PDFBox） |
| `WordReader` | Word 文档 |
| `ImageReader` | 图片，配合多模态 embedding 使用 |
| `TikaReader` | Apache Tika 通用解析（兜底） |
| `ExternalApiReader` | 调外部 API 解析（OCR / 自定义流水线） |

读取出来的 `Document` 已经带有元数据，配合 `TextChunker` 与 `SplitStrategy` 做分块。

默认 `TikaReader` 每次读取订阅都会创建新的内容处理器，顺序和并发读取不会累积此前文档的内容。自定义处理器请使用 `TikaReader.perReadHandler(512, SplitStrategy.PARAGRAPH, 50, () -> new BodyContentHandler(-1))`（`BodyContentHandler` 来自 `org.apache.tika.sax`）。原有接受 `ContentHandler` 的构造器保持兼容，但自 2.0.5 起已弃用：它仍会在多次读取中复用同一实例。

## 内置 Embedding 提供方

| 类 | 服务 | 模式 |
| --- | --- | --- |
| `DashScopeTextEmbedding` | 阿里云百炼 DashScope | 文本 |
| `DashScopeMultiModalEmbedding` | 阿里云百炼 DashScope | 多模态（文本/图像） |
| `OpenAITextEmbedding` | OpenAI 兼容接口 | 文本 |
| `OllamaTextEmbedding` | Ollama 本地 | 文本 |

也可以实现 `EmbeddingModel` 自行扩展。

## 内置向量库适配

| 实现 | 部署 |
| --- | --- |
| `InMemoryStore` | 进程内（开发/测试用） |
| `PgVectorStore` | PostgreSQL + pgvector |
| `MilvusStore` | Milvus |
| `QdrantStore` | Qdrant |
| `ElasticsearchStore` | Elasticsearch（dense_vector） |

切换向量库只需要换一个 `VDBStoreBase` 实现，传给 `SimpleKnowledge.builder().embeddingStore(...)`。

`QdrantStore.location(...)` 支持不带协议的 gRPC 地址，例如 `127.0.0.1:16334`、`localhost:6334` 和 `[::1]:6334`，并保留显式指定的端口。未指定端口时默认使用 6334；端口为 6333 的 HTTP URL 仍通过 6334 端口连接 gRPC。

## 检索参数

`RetrieveConfig` 控制检索行为：

| 字段 | 说明 |
| --- | --- |
| `limit` | TopK |
| `scoreThreshold` | 最低分数阈值（0~1） |
| `metadata` | 按文档 metadata 做过滤 |

## 与 Agent 集成

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .knowledge(knowledge)
    .ragMode(RAGMode.AGENTIC)   // Agent 自行决定何时检索
    .build();
```
