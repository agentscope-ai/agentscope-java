# Installation

AgentScope Java supports multiple LLM providers, RAG backends, and extension features, each requiring different third-party SDKs. If we bundled everything into a single package, your project would be bloated with dependencies you may never use—increasing JAR size, slowing builds, and risking version conflicts.

To balance **ease of use** and **dependency control**, we provide two approaches:

- **All-in-one**: A single dependency with sensible defaults (DashScope SDK, MCP SDK). Perfect for getting started quickly—add extra dependencies only when needed.
- **Core + extensions**: Start with a minimal core, then add only the extension modules you actually use. Ideal for production environments with strict dependency requirements.

**Our recommendation:** Start with all-in-one for rapid development. Switch to core + extensions when you need to optimize dependency footprint or resolve conflicts.

**Future plan:** We are working on replacing model provider SDKs with native HTTP implementations. This will significantly reduce external dependencies while maintaining full compatibility with all supported models.

AgentScope Java requires **JDK 17 or higher**.

## Dependency Options

AgentScope Java provides two dependency approaches:

| Approach | Use Case | Features |
|----------|----------|----------|
| **all-in-one** | Quick start, most users | Single dependency, includes DashScope SDK by default |
| **core + extensions** | Fine-grained dependency control | On-demand imports, reduces unnecessary dependencies |

## Option 1: All-in-One (Recommended)

**Maven:**
```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.1</version>
</dependency>
```

**Gradle:**
```gradle
implementation 'io.agentscope:agentscope:1.0.1'
```

### Default Transitive Dependencies

The all-in-one package includes the following dependencies by default, no additional configuration needed:

- DashScope SDK (Qwen series models)
- MCP SDK (Model Context Protocol)
- Reactor Core, Jackson, SLF4J (base frameworks)

### Additional Dependencies for Extra Features

When using other models or extension features, you need to manually add the corresponding dependencies:

| Feature | Required Dependency | Maven Coordinates |
|---------|---------------------|-------------------|
| **OpenAI Models** | [OpenAI Java SDK](https://central.sonatype.com/artifact/com.openai/openai-java) | `com.openai:openai-java` |
| **Google Gemini Models** | [Google GenAI SDK](https://central.sonatype.com/artifact/com.google.genai/google-genai) | `com.google.genai:google-genai` |
| **Anthropic Models** | [Anthropic Java SDK](https://central.sonatype.com/artifact/com.anthropic/anthropic-java) | `com.anthropic:anthropic-java` |
| **Mem0 Long-term Memory** | [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp) | `com.squareup.okhttp3:okhttp` |
| **Bailian RAG** | [Bailian SDK](https://central.sonatype.com/artifact/com.aliyun/bailian20231229) | `com.aliyun:bailian20231229` |
| **Qdrant RAG** | [Qdrant Client](https://central.sonatype.com/artifact/io.qdrant/client) | `io.qdrant:client` |
| **PDF Processing** (optional) | [Apache PDFBox](https://central.sonatype.com/artifact/org.apache.pdfbox/pdfbox) | `org.apache.pdfbox:pdfbox` |
| **Word Processing** (optional) | [Apache POI](https://central.sonatype.com/artifact/org.apache.poi/poi-ooxml) | `org.apache.poi:poi-ooxml` |

#### Example: Using OpenAI Models

```xml
<!-- Add on top of agentscope -->
<dependency>
    <groupId>com.openai</groupId>
    <artifactId>openai-java</artifactId>
</dependency>
```

#### Example: Using Qdrant RAG + PDF Processing

```xml
<!-- Add on top of agentscope -->
<dependency>
    <groupId>io.qdrant</groupId>
    <artifactId>client</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
</dependency>
```

### Studio Integration

Integrate [AgentScope Studio](https://github.com/modelscope/agentscope) for visualization and debugging. The following dependencies are required:

| Required Dependency | Maven Coordinates |
|---------------------|-------------------|
| [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp) | `com.squareup.okhttp3:okhttp` |
| [Socket.IO Client](https://central.sonatype.com/artifact/io.socket/socket.io-client) | `io.socket:socket.io-client` |
| [OpenTelemetry API](https://central.sonatype.com/artifact/io.opentelemetry/opentelemetry-api) | `io.opentelemetry:opentelemetry-api` |
| [OpenTelemetry OTLP Exporter](https://central.sonatype.com/artifact/io.opentelemetry/opentelemetry-exporter-otlp) | `io.opentelemetry:opentelemetry-exporter-otlp` |
| [OpenTelemetry Reactor](https://central.sonatype.com/artifact/io.opentelemetry.instrumentation/opentelemetry-reactor-3.1) | `io.opentelemetry.instrumentation:opentelemetry-reactor-3.1` |

Full configuration:

```xml
<!-- Add on top of agentscope -->
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>okhttp</artifactId>
</dependency>
<dependency>
    <groupId>io.socket</groupId>
    <artifactId>socket.io-client</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry.instrumentation</groupId>
    <artifactId>opentelemetry-reactor-3.1</artifactId>
</dependency>
```

## Option 2: Core + Extensions

If you need more fine-grained control over dependencies, you can use `agentscope-core` with extension modules:

**Maven:**
```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-core</artifactId>
    <version>1.0.1</version>
</dependency>
```

**Gradle:**
```gradle
implementation 'io.agentscope:agentscope-core:1.0.1'
```

### Extension Modules

| Module | Feature | Maven Coordinates |
|--------|---------|-------------------|
| [agentscope-extensions-mem0](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-mem0) | Mem0 Long-term Memory | `io.agentscope:agentscope-extensions-mem0` |
| [agentscope-extensions-rag-bailian](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-rag-bailian) | Bailian Knowledge Base RAG | `io.agentscope:agentscope-extensions-rag-bailian` |
| [agentscope-extensions-rag-simple](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-rag-simple) | Qdrant Vector Search RAG | `io.agentscope:agentscope-extensions-rag-simple` |
| [agentscope-extensions-studio](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-studio) | AgentScope Studio Integration | `io.agentscope:agentscope-extensions-studio` |

Extension modules automatically include their required third-party dependencies, no manual addition needed.

#### Example: Core + Mem0 Extension

```xml
<!-- Add on top of agentscope-core -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-mem0</artifactId>
    <version>1.0.1</version>
</dependency>
```
