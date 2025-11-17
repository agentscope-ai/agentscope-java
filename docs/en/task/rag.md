# RAG (Retrieval-Augmented Generation)

AgentScope provides built-in support for Retrieval-Augmented Generation (RAG) tasks, enabling agents to access and utilize external knowledge bases to provide more accurate and informative responses.

## Overview

The RAG module in AgentScope consists of two core components:

- **Reader**: Responsible for reading and chunking input documents into processable units
- **Knowledge**: Responsible for storing documents, generating embeddings, and retrieving relevant information

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
    .role("user")
    .name("user")
    .content("What is AgentScope?")
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
    .role("user")
    .name("user")
    .content("What is RAG?")
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
