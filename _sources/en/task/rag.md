# RAG (Retrieval-Augmented Generation)

AgentScope provides built-in RAG support, enabling Agents to access external knowledge bases.

## Overview
### Core Components
The RAG module in AgentScope consists of two core components:

- **Reader**: Responsible for reading and chunking input documents, converting them into processable units
- **Knowledge**: Responsible for storing documents, generating embeddings, and retrieving relevant information

### Scope of Support

AgentScope supports multiple types of knowledge base implementations:

| Type | Implementation | Supported Features | Document Management | Use Cases |
|------|----------------|-------------------|---------------------|-----------|
| **Local Knowledge Base** | `SimpleKnowledge` | Full document management and retrieval | Via code (using Reader) | Development, testing, full data control |
| **Cloud-hosted Knowledge Base** | `BailianKnowledge` | Retrieval only | [Bailian Console](https://bailian.console.aliyun.com/) | Enterprise, multi-turn conversations, query rewriting |
| **Dify Knowledge Base** | `DifyKnowledge` | Retrieval only | Dify Console | Multiple retrieval modes, reranking |
| **RAGFlow Knowledge Base** | `RAGFlowKnowledge` | Retrieval only | RAGFlow Console | Powerful OCR, knowledge graph, multi-dataset |


### Integration Modes

AgentScope supports two RAG integration modes:

| Mode | Description | Pros | Cons |
|------|-------------|------|------|
| **Generic Mode** | Automatically retrieves and injects knowledge before each inference step | Simple, works with any LLM | Retrieves even when not needed |
| **Agentic Mode** | Agent uses tools to decide when to retrieve | Flexible, retrieves only when needed | Requires strong reasoning capabilities |

#### Generic Mode

In Generic mode, knowledge is automatically retrieved and injected into the user's message:

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("You are a helpful assistant with access to a knowledge base.")
    .model(chatModel)
    .toolkit(new Toolkit())
    // Enable Generic RAG mode
    .knowledge(knowledge)
    .ragMode(RAGMode.GENERIC)
    .retrieveConfig(
        RetrieveConfig.builder()
            .limit(3)
            .scoreThreshold(0.3)
            .build())
    .enableOnlyForUserQueries(true)  // Retrieve only for user messages
    .build();
```

How it works:
1. User sends a query
2. Knowledge base automatically retrieves relevant documents
3. Retrieved documents are prepended to the user message
4. Agent processes the enhanced message and responds

#### Agentic Mode

In Agentic mode, the Agent has a `retrieve_knowledge` tool and decides when to use it:

```java
ReActAgent agent = ReActAgent.builder()
    .name("Agent")
    .sysPrompt("You are a helpful assistant with a knowledge retrieval tool. " +
               "Use the retrieve_knowledge tool when you need information.")
    .model(chatModel)
    .toolkit(new Toolkit())
    // Enable Agentic RAG mode
    .knowledge(knowledge)
    .ragMode(RAGMode.AGENTIC)
    .retrieveConfig(
        RetrieveConfig.builder()
            .limit(3)
            .scoreThreshold(0.5)
            .build())
    .build();
```

**How it works:**
1. User sends a query
2. Agent reasons and decides whether to retrieve knowledge
3. If needed, Agent calls `retrieve_knowledge(query="...")`
4. Retrieved documents are returned as tool results
5. Agent reasons again using the retrieved information


## Local Knowledge Base (SimpleKnowledge)



### Quick Start

```java
// 1. Create knowledge base
EmbeddingModel embeddingModel = DashScopeTextEmbedding.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("text-embedding-v3")
    .dimensions(1024)
    .build();

Knowledge knowledge = SimpleKnowledge.builder()
    .embeddingModel(embeddingModel)
    .embeddingStore(InMemoryStore.builder().dimensions(1024).build())
    .build();

// 2. Add documents
TextReader reader = new TextReader(512, SplitStrategy.PARAGRAPH, 50);
List<Document> docs = reader.read(ReaderInput.fromString("Text content...")).block();
knowledge.addDocuments(docs).block();

// 3. Retrieve
List<Document> results = knowledge.retrieve("query",
    RetrieveConfig.builder().limit(3).scoreThreshold(0.5).build()).block();
```

### Reader Configuration
AgentScope provides multiple built-in Readers for SimpleKnowledge:

Split strategies: `CHARACTER`, `PARAGRAPH`, `SENTENCE`, `TOKEN`

```java
// Text
new TextReader(512, SplitStrategy.PARAGRAPH, 50);

// PDF
new PDFReader(512, SplitStrategy.PARAGRAPH, 50);

// Word (supports images and tables)
new WordReader(512, SplitStrategy.PARAGRAPH, 50, true, true, TableFormat.MARKDOWN);

// Image (requires multimodal embedding model)
new ImageReader(false);
```

### Vector Store

```java
// In-memory store (development/testing)
InMemoryStore.builder().dimensions(1024).build();

// Qdrant (production)
QdrantStore.builder()
    .location("localhost:6334")
    .collectionName("my_collection")
    .dimensions(1024)
    .build();
```


## Cloud-hosted Knowledge Base (Bailian)

Alibaba Cloud Bailian Knowledge Base, supporting reranking, query rewriting, and multi-turn conversations. Manage documents via [Bailian Console](https://bailian.console.aliyun.com/).

### Quick Start

```java
// Create knowledge base
BailianConfig config = BailianConfig.builder()
    .accessKeyId(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"))
    .accessKeySecret(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"))
    .workspaceId("llm-xxx")
    .indexId("mymxbdxxxx")
    .build();

BailianKnowledge knowledge = BailianKnowledge.builder().config(config).build();

// Retrieve
List<Document> results = knowledge.retrieve("query",
    RetrieveConfig.builder().limit(5).scoreThreshold(0.3).build()).block();
```

### Advanced Configuration

```java
BailianConfig config = BailianConfig.builder()
    .accessKeyId(accessKeyId).accessKeySecret(accessKeySecret)
    .workspaceId("llm-xxx").indexId("mymxbdxxxx")
    .denseSimilarityTopK(20)
    .enableReranking(true)
    .rerankConfig(RerankConfig.builder()
        .modelName("gte-rerank-hybrid").rerankMinScore(0.3f).rerankTopN(5).build())
    .enableRewrite(true)
    .rewriteConfig(RewriteConfig.builder().modelName("conv-rewrite-qwen-1.8b").build())
    .build();
```

### Multi-turn Conversation Retrieval

```java
RetrieveConfig config = RetrieveConfig.builder()
    .limit(5).scoreThreshold(0.3)
    .conversationHistory(conversationHistory)  // Auto-rewrite query
    .build();
```

### Complete Configuration Example

```java
BailianConfig config = BailianConfig.builder()
    // === Connection Configuration (Required) ===
    .accessKeyId(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"))
    .accessKeySecret(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"))
    .workspaceId("llm-xxx")                    // Bailian workspace ID
    .indexId("mymxbdxxxx")                     // Knowledge base index ID
    
    // === Endpoint Configuration (Optional) ===
    .endpoint("bailian.cn-beijing.aliyuncs.com")  // Default value
    // Other available endpoints:
    // - bailian.cn-shanghai-finance-1.aliyuncs.com (Finance Cloud)
    // - bailian-vpc.cn-beijing.aliyuncs.com (VPC)
    
    // === Retrieval Configuration (Optional) ===
    .denseSimilarityTopK(100)                  // Vector retrieval Top K, range 0-100, default 100
    .sparseSimilarityTopK(100)                 // Keyword retrieval Top K, range 0-100, default 100
    // Note: denseSimilarityTopK + sparseSimilarityTopK <= 200
    
    // === Reranking Configuration (Optional) ===
    .enableReranking(true)                     // Enable reranking, default true
    .rerankConfig(RerankConfig.builder()
        .modelName("gte-rerank-hybrid")        // Rerank model
        .rerankMinScore(0.3f)                  // Minimum score threshold
        .rerankTopN(5)                         // Number of results to return
        .build())
    
    // === Query Rewriting Configuration (Optional, for multi-turn conversations) ===
    .enableRewrite(true)                       // Enable query rewriting, default false
    .rewriteConfig(RewriteConfig.builder()
        .modelName("conv-rewrite-qwen-1.8b")   // Rewrite model
        .build())
    
    // === Other Configuration (Optional) ===
    .searchFilters(List.of(Map.of("tag", "value")))  // Search filters
    .saveRetrieverHistory(false)               // Save retrieval history, default false
    
    .build();
```

## Dify Knowledge Base Integration

Supports cloud service and self-hosting, providing four retrieval modes: keyword, semantic, hybrid, and fulltext. Manage documents via [Dify Console](https://cloud.dify.ai).

### Quick Start

```java
DifyRAGConfig config = DifyRAGConfig.builder()
    .apiKey(System.getenv("DIFY_RAG_API_KEY"))
    .datasetId("your-dataset-id")
    .retrievalMode(RetrievalMode.HYBRID_SEARCH)
    .topK(10).scoreThreshold(0.5)
    .build();

DifyKnowledge knowledge = DifyKnowledge.builder().config(config).build();

List<Document> results = knowledge.retrieve("query",
    RetrieveConfig.builder().limit(5).build()).block();
```

### Retrieval Modes

```java
.retrievalMode(RetrievalMode.KEYWORD)         // Keyword search
.retrievalMode(RetrievalMode.SEMANTIC_SEARCH) // Semantic search
.retrievalMode(RetrievalMode.HYBRID_SEARCH)   // Hybrid search (recommended)
.retrievalMode(RetrievalMode.FULLTEXT)        // Fulltext search
```

### Advanced Configuration

```java
DifyRAGConfig config = DifyRAGConfig.builder()
    .apiKey(apiKey).datasetId(datasetId)
    .retrievalMode(RetrievalMode.HYBRID_SEARCH)
    .weights(0.6)  // Hybrid search semantic weight
    // Reranking
    .enableRerank(true)
    .rerankConfig(RerankConfig.builder()
        .providerName("cohere").modelName("rerank-english-v2.0").build())
    // Metadata filtering
    .metadataFilter(MetadataFilter.builder()
        .logicalOperator("AND")
        .addCondition(MetadataFilterCondition.builder()
            .name("category").comparisonOperator("=").value("AI").build())
        .build())
    .build();
```

### Complete Configuration Example

```java
DifyRAGConfig config = DifyRAGConfig.builder()
    // === Connection Configuration (Required) ===
    .apiKey(System.getenv("DIFY_RAG_API_KEY"))  // Dataset API Key
    .datasetId("your-dataset-uuid")             // Dataset ID (UUID format)
    
    // === Endpoint Configuration (Optional) ===
    .apiBaseUrl("https://api.dify.ai/v1")       // Dify Cloud (default)
    // .apiBaseUrl("https://your-dify.com/v1")  // Self-hosted instance
    
    // === Retrieval Configuration (Optional) ===
    .retrievalMode(RetrievalMode.HYBRID_SEARCH) // Retrieval mode, default HYBRID_SEARCH
    // Available modes: KEYWORD, SEMANTIC_SEARCH, HYBRID_SEARCH, FULLTEXT
    .topK(10)                                   // Number of results, range 1-100, default 10
    .scoreThreshold(0.5)                        // Similarity threshold, range 0.0-1.0, default 0.0
    .weights(0.6)                               // Hybrid search semantic weight, range 0.0-1.0
    
    // === Reranking Configuration (Optional) ===
    .enableRerank(true)                         // Enable reranking, default false
    .rerankConfig(RerankConfig.builder()
        .providerName("cohere")                 // Rerank model provider
        .modelName("rerank-english-v2.0")       // Rerank model name
        .topN(5)                                // Number of results after reranking
        .build())
    
    // === Metadata Filtering (Optional) ===
    .metadataFilter(MetadataFilter.builder()
        .logicalOperator("AND")                 // Logical operator: AND or OR
        .addCondition(MetadataFilterCondition.builder()
            .name("category")                   // Metadata field name
            .comparisonOperator("=")            // Comparison operator
            .value("documentation")             // Filter value
            .build())
        .build())
    
    // === HTTP Configuration (Optional) ===
    .connectTimeout(Duration.ofSeconds(30))     // Connection timeout, default 30s
    .readTimeout(Duration.ofSeconds(60))        // Read timeout, default 60s
    .maxRetries(3)                              // Max retries, default 3
    .addCustomHeader("X-Custom-Header", "value") // Custom headers
    
    .build();
```

## RAGFlow Knowledge Base Integration

Open-source RAG engine, supporting Docker deployment, powerful OCR, knowledge graph, and multi-dataset retrieval.

### Deployment

```bash
git clone https://github.com/infiniflow/ragflow.git && cd ragflow
docker compose up -d  
```

### Quick Start

```java
RAGFlowConfig config = RAGFlowConfig.builder()
    .apiKey("ragflow-your-api-key")             // Required: API Key
    .baseUrl("http://address")                  // Required: RAGFlow service address
    .addDatasetId("dataset-id")                 // Required: Set at least dataset_ids or document_ids
    .topK(10).similarityThreshold(0.3)
    .build();

RAGFlowKnowledge knowledge = RAGFlowKnowledge.builder().config(config).build();

List<Document> results = knowledge.retrieve("query",
    RetrieveConfig.builder().limit(5).build()).block();
```

### Multi-dataset and Document Filtering

> **Note**: `dataset_ids` and `document_ids` **require at least one to be set**. If only setting `document_ids`, ensure all documents use the same embedding model.

```java
// Method 1: Set only datasets (search entire datasets)
RAGFlowConfig config1 = RAGFlowConfig.builder()
    .apiKey("ragflow-your-api-key")
    .baseUrl("http://localhost:9380")
    .addDatasetId("dataset-1")
    .addDatasetId("dataset-2")
    .build();

// Method 2: Set only documents (search specified documents directly, must use same embedding model)
RAGFlowConfig config2 = RAGFlowConfig.builder()
    .apiKey("ragflow-your-api-key")
    .baseUrl("http://localhost:9380")
    .addDocumentId("doc-id-1")
    .addDocumentId("doc-id-2")
    .build();

// Method 3: Set both (search specified documents within datasets)
RAGFlowConfig config3 = RAGFlowConfig.builder()
    .apiKey("ragflow-your-api-key")
    .baseUrl("http://localhost:9380")
    .addDatasetId("dataset-1")
    .addDocumentId("doc-id-1")  // Limit to specified documents in the dataset
    .build();
```

### Metadata Filtering

```java
Map<String, Object> condition = Map.of(
    "logic", "and",
    "conditions", List.of(
        Map.of("name", "author", "comparison_operator", "=", "value", "Toby"),
        Map.of("name", "date", "comparison_operator", "=", "value", "2024-01-01")
    )
);

RAGFlowConfig config = RAGFlowConfig.builder()
    .apiKey("ragflow-your-api-key")
    .baseUrl("http://localhost:9380")
    .addDatasetId("dataset-id")
    .metadataCondition(condition)
    .build();
```

### Complete Configuration Example

```java
RAGFlowConfig config = RAGFlowConfig.builder()
    // === Connection Configuration (Required) ===
    .apiKey("ragflow-your-api-key")             // RAGFlow API Key
    .baseUrl("http://address")                  // RAGFlow service address (Required)


    // === Dataset/Document Configuration (At least one required) ===
    .addDatasetId("datasetId1")
    .addDatasetId("datasetId2")  // Supports multiple datasets
    // Or batch set: .datasetIds(List.of("id1", "id2"))
    
    // === Document Filtering (Optional, limits search scope) ===
    .addDocumentId("documentId1")
    .addDocumentId("documentId2")
    // Or batch set: .documentIds(List.of("doc1", "doc2"))
    // Note: If only setting document_ids, ensure all documents use the same embedding model
    
    // === Retrieval Configuration (Optional) ===
    .topK(1024)                                 // Number of chunks for vector computation, default 1024
    .similarityThreshold(0.2)                   // Similarity threshold, range 0.0-1.0, default 0.2
    .vectorSimilarityWeight(0.3)                // Vector similarity weight, range 0.0-1.0, default 0.3
                                                // (1 - weight) is term similarity weight
    //=== Pagination Parameters ===
    .page(1)                                    // Page number, default 1
    .pageSize(30)                               // Page size, default 30
    
    // === Advanced Retrieval Features (Optional) ===
    .useKg(false)                               // Knowledge graph multi-hop query, default false
    .tocEnhance(false)                          // TOC-enhanced retrieval, default false
    .rerankId(1)                                // Rerank model ID
    .keyword(false)                             // Keyword matching, default false
    .highlight(false)                           // Highlight matched results, default false
    .addCrossLanguage("en")                     // Add target language
    // Or batch set: .crossLanguages(List.of("en", "zh", "ja"))
    
    // === Metadata Filtering (Optional) ===
    .metadataCondition(Map.of(
        "logic", "and",                         // Logical operator: and or or
        "conditions", List.of(
            Map.of(
                "name", "author",               // Metadata field name
                "comparison_operator", "=",     // Comparison operator
                "value", "Toby"                 // Filter value
            ),
            Map.of(
                "name", "date",
                "comparison_operator", ">=",
                "value", "2024-01-01"
            )
        )
    ))
    
    // === HTTP Configuration (Optional) ===
    .timeout(Duration.ofSeconds(30))            // HTTP timeout, default 30s
    .maxRetries(3)                              // Max retries, default 3
    .addCustomHeader("X-Custom-Header", "value") // Custom headers
    
    .build();
```

**Supported Comparison Operators:**
- `=` - Equals
- `≠` - Not equals
- `>`, `<`, `≥`, `≤` - Numeric comparisons
- `contains` - Contains
- `not contains` - Does not contain
- `start with` - Starts with
- `empty` - Is empty
- `not empty` - Is not empty




## Custom RAG Components

AgentScope encourages custom RAG components. You can extend the following base classes:

| Base Class | Description | Abstract Methods |
|------------|-------------|------------------|
| `Reader` | Document reader base class | `read()`, `getSupportedFormats()` |
| `VDBStoreBase` | Vector store base class | `add()`, `search()` |
| `Knowledge` | Knowledge base implementation base class | `addDocuments()`, `retrieve()` |

### Custom Reader Example

```java
public class CustomReader implements Reader {
    @Override
    public Mono<List<Document>> read(ReaderInput input) throws ReaderException {
        return Mono.fromCallable(() -> {
            // Your custom reading logic
            String content = processInput(input);
            List<String> chunks = chunkContent(content);
            return createDocuments(chunks);
        });
    }

    @Override
    public List<String> getSupportedFormats() {
        return List.of("custom", "fmt");
    }

    private List<Document> createDocuments(List<String> chunks) {
        // Create Document objects with metadata
        // ...
    }
}
```


## Best Practices

1. **Chunk Size**: Choose chunk size based on model context window and use case. Typical values: 256-1024 characters.

2. **Overlap**: Use 10-20% overlap to maintain context continuity between chunks.

3. **Score Threshold**: Start with 0.3-0.5, adjust based on retrieval quality.

4. **Top-K**: Initially retrieve 3-5 documents, adjust based on context window limits.

5. **Mode Selection**:
   - Use **Generic Mode**: Simple Q&A, consistent retrieval patterns, weaker LLMs
   - Use **Agentic Mode**: Complex tasks, selective retrieval, powerful LLMs

6. **Vector Store Selection**:
   - Use **InMemoryStore**: Development, testing, small datasets (<10K documents)
   - Use **QdrantStore**: Production, large datasets, persistence required


## Complete Examples

- **Local Knowledge Base Example**: [RAGExample.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/advanced/src/main/java/io/agentscope/examples/advanced/RAGExample.java)
- **Bailian Knowledge Base Example**: [BailianRAGExample.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/advanced/src/main/java/io/agentscope/examples/advanced/BailianRAGExample.java)
- **Dify Knowledge Base Example**: [DifyRAGExample.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/DifyRAGExample.java)
- **RAGFlow Knowledge Base Example**: [RAGFlowRAGExample.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/RAGFlowRAGExample.java)
