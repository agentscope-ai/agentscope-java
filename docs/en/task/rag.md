# RAG (Retrieval-Augmented Generation)

AgentScope provides built-in support for Retrieval-Augmented Generation (RAG) tasks, enabling agents to access and utilize external knowledge bases to provide more accurate and informative responses.

## Overview

The RAG module in AgentScope consists of two core components:

- **Reader**: Responsible for reading and chunking input documents into processable units
- **Knowledge**: Responsible for storing documents, generating embeddings, and retrieving relevant information

AgentScope supports two types of knowledge base implementations:

| Type | Implementation | Features | Use Cases |
|------|----------------|----------|-----------|
| **Local Knowledge** | `SimpleKnowledge` | Requires local embedding model and vector store | Development, testing, full data control |
| **Cloud-hosted Knowledge** | `BailianKnowledge` | Uses Alibaba Cloud Bailian Knowledge Base service | Enterprise production, zero maintenance, advanced retrieval |

## Supported Readers

AgentScope provides several built-in readers for different document formats:

| Reader | Description | Supported Formats |
|--------|-------------|-------------------|
| `TextReader` | Reads and chunks plain text documents | text |
| `PDFReader` | Extracts text from PDF files | pdf |
| `WordReader` | Extracts text, tables, and images from Word documents | docx |
| `ImageReader` | Reads image files (for multimodal RAG) | jpg, jpeg, png, gif, bmp, tiff, webp |

Each reader chunks documents into `Document` objects with the following fields:

- `metadata`: Contains content (TextBlock/ImageBlock), doc_id, chunk_id, and total_chunks
- `embedding`: The embedding vector (filled when added to or retrieved from knowledge base)
- `score`: The relevance score (filled during retrieval)

## Quick Start

### 1. Creating a Knowledge Base

First, create a knowledge base with an embedding model and vector store:

```java
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;

// Create embedding model
EmbeddingModel embeddingModel = DashScopeTextEmbedding.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("text-embedding-v3")
        .dimensions(1024)
        .build();

        // Create vector store
        VDBStoreBase vectorStore = InMemoryStore.builder()
                .dimensions(1024)
                .build();

        // Create knowledge base
        Knowledge knowledge = SimpleKnowledge.builder()
                .embeddingModel(embeddingModel)
                .embeddingStore(vectorStore)
                .build();
```

### 2. Adding Documents

Use readers to process documents and add them to the knowledge base:

```java
import io.agentscope.core.rag.reader.TextReader;
import io.agentscope.core.rag.reader.SplitStrategy;
import io.agentscope.core.rag.model.ReaderInput;

// Create a text reader
TextReader reader = new TextReader(512, SplitStrategy.PARAGRAPH, 50);

// Read and chunk a document
String text = "AgentScope is a multi-agent framework...";
ReaderInput input = ReaderInput.fromString(text);
List<Document> documents = reader.read(input).block();

// Add to knowledge base
knowledge.addDocuments(documents).block();
```

### 3. Retrieving Knowledge

Query the knowledge base to retrieve relevant documents:

```java
import io.agentscope.core.rag.model.RetrieveConfig;

// Configure retrieval parameters
RetrieveConfig config = RetrieveConfig.builder()
    .limit(3)                    // Return top 3 results
    .scoreThreshold(0.5)         // Minimum similarity score
    .build();

// Retrieve documents
List<Document> results = knowledge.retrieve("What is AgentScope?", config).block();

for (Document doc : results) {
    System.out.println("Score: " + doc.getScore());
    System.out.println("Content: " + doc.getMetadata().getContent());
}
```

## Cloud-hosted Knowledge Base (Bailian)

AgentScope supports Alibaba Cloud Bailian Knowledge Base service, providing an enterprise-grade cloud-hosted RAG solution. Unlike local knowledge bases, Bailian Knowledge requires no local embedding model or vector store - all document processing, embedding, and retrieval are handled by the cloud service.

### Core Features

- **Zero Infrastructure**: No need to deploy and maintain vector databases
- **Automatic Processing**: Documents are automatically parsed, chunked, and embedded
- **Enterprise-grade Retrieval**: Supports reranking and query rewriting
- **Multi-turn Conversations**: Automatically leverages conversation history to improve retrieval accuracy
- **Structured/Unstructured Data**: Supports various knowledge base types

### Quick Start

#### 1. Configure Bailian Connection

```java
import io.agentscope.core.rag.integration.bailian.BailianConfig;
import io.agentscope.core.rag.integration.bailian.BailianKnowledge;

// Configure Bailian connection
BailianConfig config = BailianConfig.builder()
    .accessKeyId(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"))
    .accessKeySecret(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"))
    .workspaceId("llm-xxx")        // Your workspace ID
    .indexId("mymxbdxxxx")         // Your knowledge base index ID
    .build();

// Create knowledge base instance
BailianKnowledge knowledge = BailianKnowledge.builder()
    .config(config)
    .build();
```

#### 2. Configure Advanced Retrieval Options

Bailian supports rich retrieval configuration options:

```java
import io.agentscope.core.rag.integration.bailian.RerankConfig;
import io.agentscope.core.rag.integration.bailian.RewriteConfig;

BailianConfig config = BailianConfig.builder()
    .accessKeyId(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"))
    .accessKeySecret(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"))
    .workspaceId("llm-xxx")
    .indexId("mymxbdxxxx")
    // Configure dense vector retrieval
    .denseSimilarityTopK(20)       // Dense retrieval returns top 20
    // Configure sparse vector retrieval (optional)
    .sparseSimilarityTopK(10)      // Sparse retrieval returns top 10
    // Enable reranking
    .enableReranking(true)
    .rerankConfig(
        RerankConfig.builder()
            .modelName("gte-rerank-hybrid")
            .rerankMinScore(0.3f)   // Minimum reranking score
            .rerankTopN(5)          // Return top 5 results
            .build())
    // Enable query rewriting (multi-turn conversations)
    .enableRewrite(true)
    .rewriteConfig(
        RewriteConfig.builder()
            .modelName("conv-rewrite-qwen-1.8b")
            .build())
    .build();
```

#### 3. Retrieve Documents

```java
import io.agentscope.core.rag.model.RetrieveConfig;

// Configure retrieval parameters
RetrieveConfig retrieveConfig = RetrieveConfig.builder()
    .limit(5)                       // Return up to 5 documents
    .scoreThreshold(0.3)            // Minimum similarity score
    .build();

// Retrieve documents
List<Document> results = knowledge.retrieve("What is RAG?", retrieveConfig).block();

for (Document doc : results) {
    System.out.println("Score: " + doc.getScore());
    System.out.println("Document ID: " + doc.getMetadata().getDocId());
    System.out.println("Content: " + doc.getMetadata().getContent());
}
```

#### 4. Multi-turn Retrieval with Conversation History

Bailian can leverage conversation history to improve retrieval effectiveness by automatically rewriting queries based on context:

```java
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;

// Prepare conversation history
List<Msg> conversationHistory = List.of(
    Msg.builder().textContent("What is AgentScope?").build(),
    Msg.builder().role(MsgRole.ASSISTANT).textContent("AgentScope is a multi-agent framework...").build()
);

// Retrieval config with history
RetrieveConfig config = RetrieveConfig.builder()
    .limit(5)
    .scoreThreshold(0.3)
    .conversationHistory(conversationHistory)  // Add conversation history
    .build();

// Query will be automatically rewritten to consider context
List<Document> results = knowledge.retrieve("What are its features?", config).block();
```

### Integration with ReActAgent

In Agentic mode, the agent automatically extracts conversation history from its Memory and passes it to Bailian for context-aware retrieval:

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.rag.integration.KnowledgeRetrievalTools;

// Create Bailian knowledge base
BailianKnowledge knowledge = BailianKnowledge.builder()
    .config(bailianConfig)
    .build();

// Use Agentic mode
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .sysPrompt("You are a helpful assistant with a knowledge retrieval tool. " +
               "Use the retrieve_knowledge tool when you need information.")
    .model(chatModel)
    .toolkit(new Toolkit())
    .knowledge(knowledge)
    .ragMode(RAGMode.AGENTIC)      // Agent autonomously decides when to retrieve
    .retrieveConfig(
        RetrieveConfig.builder()
            .limit(5)
            .scoreThreshold(0.3)
            .build())
    .build();

// Multi-turn conversations automatically leverage historical context
agent.call(Msg.builder().textContent("What is AgentScope?").build());
agent.call(Msg.builder().textContent("What models does it support?").build());
// The second query will leverage the first conversation's context to improve retrieval accuracy
```

### Document Management

**Note**: Currently, document upload and management need to be done through the Bailian console. API-based document management will be supported in future releases.

1. Log in to [Alibaba Cloud Bailian Platform](https://bailian.console.aliyun.com/)
2. Create a knowledge base and upload documents
3. Obtain workspace ID and index ID
4. Use these IDs in your code for retrieval

### Bailian vs SimpleKnowledge

| Feature | SimpleKnowledge | BailianKnowledge |
|---------|----------------|------------------|
| **Deployment** | Requires local embedding model and vector store | Cloud service, zero deployment |
| **Document Processing** | Need to write Reader code yourself | Upload via console, automatic processing |
| **Retrieval Capabilities** | Basic vector retrieval | Advanced retrieval (reranking, rewriting) |
| **Scalability** | Limited by local resources | Cloud service auto-scaling |
| **Cost** | Computing resource costs | Pay per use |
| **Data Control** | Full local control | Hosted in cloud |
| **Multi-turn Conversations** | Need manual implementation | Automatically supported |
| **Use Cases** | Development, testing, small-scale | Production, enterprise, large-scale |

### Complete Example

See the complete Bailian RAG example:
- `examples/src/main/java/io/agentscope/examples/BailianRAGExample.java`

Run the example:
```bash
cd examples
# Set environment variables
export ALIBABA_CLOUD_ACCESS_KEY_ID="your-access-key-id"
export ALIBABA_CLOUD_ACCESS_KEY_SECRET="your-access-key-secret"
export BAILIAN_WORKSPACE_ID="your-workspace-id"
export BAILIAN_INDEX_ID="your-index-id"

mvn exec:java -Dexec.mainClass="io.agentscope.examples.BailianRAGExample"
```

## Integrating with ReActAgent

AgentScope supports two integration modes for RAG with ReActAgent:

| Mode | Description | Advantages | Disadvantages |
|------|-------------|------------|---------------|
| **Generic Mode** | Automatically retrieves and injects knowledge before each reasoning step | Simple, works with any LLM | Retrieves even when unnecessary |
| **Agentic Mode** | Agent decides when to retrieve using a tool | Flexible, only retrieves when needed | Requires strong reasoning capabilities |

### Generic Mode

In Generic mode, knowledge is automatically retrieved and injected into the user's message:

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.rag.RAGMode;

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
    .enableOnlyForUserQueries(true)  // Only retrieve for user messages
    .build();

// The agent will automatically retrieve knowledge for each query
agent.call(Msg.builder()
    .name("user")
    .textContent("What is AgentScope?")
    .build());
```

**How it works:**
1. User sends a query
2. Knowledge base automatically retrieves relevant documents
3. Retrieved documents are prepended to the user's message
4. Agent processes the enhanced message and responds

### Agentic Mode

In Agentic mode, the agent has a `retrieve_knowledge` tool and decides when to use it:

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

// The agent decides when to retrieve
agent.call(Msg.builder()
    .name("user")
    .textContent("What is RAG?")
    .build());
```

**How it works:**
1. User sends a query
2. Agent reasons and decides whether to retrieve knowledge
3. If needed, agent calls `retrieve_knowledge(query="...")`
4. Retrieved documents are returned as tool results
5. Agent reasons again with the retrieved information

## Reading Different Document Types

### Text Documents

```java
TextReader reader = new TextReader(
    512,                      // Chunk size
    SplitStrategy.PARAGRAPH,  // Split by paragraph
    50                        // Overlap size
);

ReaderInput input = ReaderInput.fromString("Your text content...");
List<Document> docs = reader.read(input).block();
```

Supported split strategies:
- `SplitStrategy.CHARACTER`: Split by character count
- `SplitStrategy.PARAGRAPH`: Split by paragraphs (double newline)
- `SplitStrategy.SENTENCE`: Split by sentences
- `SplitStrategy.TOKEN`: Split by approximate token count

### PDF Documents

```java
import io.agentscope.core.rag.reader.PDFReader;

PDFReader reader = new PDFReader(512, SplitStrategy.PARAGRAPH, 50);
ReaderInput input = ReaderInput.fromString("/path/to/document.pdf");
List<Document> docs = reader.read(input).block();
```

### Word Documents

```java
import io.agentscope.core.rag.reader.WordReader;
import io.agentscope.core.rag.reader.TableFormat;

WordReader reader = new WordReader(
    512,                      // Chunk size
    SplitStrategy.PARAGRAPH,  // Split strategy
    50,                       // Overlap size
    true,                     // Include images
    true,                     // Separate tables as chunks
    TableFormat.MARKDOWN      // Table format (MARKDOWN or JSON)
);

ReaderInput input = ReaderInput.fromString("/path/to/document.docx");
List<Document> docs = reader.read(input).block();
```

### Image Documents (Multimodal RAG)

```java
import io.agentscope.core.rag.reader.ImageReader;
import io.agentscope.core.embedding.dashscope.DashScopeMultiModalEmbedding;

// Create multimodal embedding model
EmbeddingModel embeddingModel = DashScopeMultiModalEmbedding.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("multimodal-embedding-one")
    .dimensions(1024)
    .build();

// Create knowledge base with multimodal embedding
Knowledge knowledge = SimpleKnowledge.builder()
    .embeddingModel(embeddingModel)
    .embeddingStore(vectorStore)
    .build();

// Read image
ImageReader reader = new ImageReader(false);  // OCR disabled
ReaderInput input = ReaderInput.fromString("/path/to/image.jpg");
// or from URL
// ReaderInput input = ReaderInput.fromString("https://example.com/image.jpg");

List<Document> docs = reader.read(input).block();
knowledge.addDocuments(docs).block();
```

## Vector Stores

AgentScope supports multiple vector store backends:

### In-Memory Store

Fast, suitable for development and small datasets:

```java
InMemoryStore store = InMemoryStore.builder()
    .dimensions(1024)
    .build();
```

### Qdrant Store

Production-ready vector database with persistence:

```java
import io.agentscope.core.rag.store.QdrantStore;

QdrantStore store = QdrantStore.builder()
    .location("localhost:6334")      // Qdrant server location
    .collectionName("my_collection")
    .dimensions(1024)
    .apiKey("your-api-key")          // Optional: for cloud
    .useTransportLayerSecurity(true) // Enable TLS
    .build();
```

Qdrant supports various storage backends via the `location` parameter:
- `:memory:` - In-memory storage
- `path/to/db` - Local file storage
- `localhost:6334` - Remote server (gRPC)
- `http://localhost:6333` - Remote server (REST)

## Customizing RAG Components

AgentScope encourages customization of RAG components. You can extend the following base classes:

| Base Class | Description | Abstract Methods |
|------------|-------------|------------------|
| `Reader` | Base for document readers | `read()`, `getSupportedFormats()` |
| `VDBStoreBase` | Base for vector stores | `add()`, `search()` |
| `Knowledge` | Base for knowledge implementations | `addDocuments()`, `retrieve()` |

### Custom Reader Example

```java
import io.agentscope.core.rag.reader.Reader;
import reactor.core.publisher.Mono;

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

1. **Chunk Size**: Choose chunk size based on your model's context window and use case. Typical values: 256-1024 characters.

2. **Overlap**: Use 10-20% overlap to maintain context across chunks.

3. **Score Threshold**: Start with 0.3-0.5 and adjust based on retrieval quality.

4. **Top-K**: Retrieve 3-5 documents initially, adjust based on context window limits.

5. **Mode Selection**:
   - Use **Generic mode** for: Simple Q&A, consistent retrieval patterns, weaker LLMs
   - Use **Agentic mode** for: Complex tasks, selective retrieval, strong LLMs

6. **Vector Store Selection**:
   - Use **InMemoryStore** for: Development, testing, small datasets (<10K documents)
   - Use **QdrantStore** for: Production, large datasets, persistence required

7. **Embedding Models**:
   - Use **text embedding** for text-only documents
   - Use **multimodal embedding** for mixed content (text + images)

## Complete Example

See the full RAG example at:
- `examples/src/main/java/io/agentscope/examples/RAGExample.java`

Run the example:
```bash
cd examples
mvn exec:java -Dexec.mainClass="io.agentscope.examples.RAGExample"
```

## Further Reading

- [Tool System](./tool.md) - Learn about creating custom tools
- [Hook System](./hook.md) - Understand how Generic RAG mode uses hooks
- [Model Configuration](./model.md) - Configure embedding and chat models
