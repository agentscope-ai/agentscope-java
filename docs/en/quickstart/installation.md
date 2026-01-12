# Installation

AgentScope Java supports multiple models, RAG backends, and extensions, each requiring different third-party SDKs. Bundling everything together would bloat your project, so we offer two ways to add dependencies:

- **All-in-one**: Single dependency with DashScope SDK and MCP SDK included, get started quickly
- **Core + extensions**: Minimal core package, add extension modules as needed, for strict dependency control

For most cases, all-in-one is enough. Switch to core + extensions when you need fine-grained dependency control.

**Requirements: JDK 17+**

## Dependency Options

| Approach | Use Case | Features |
|----------|----------|----------|
| **all-in-one** | Quick start, most users | Single dependency, includes DashScope SDK |
| **core + extensions** | Fine-grained control | On-demand imports, minimal dependencies |

## All-in-One (Recommended)

**Maven:**
```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.6</version>
</dependency>
```

**Gradle:**
```gradle
implementation 'io.agentscope:agentscope:1.0.6'
```

### Included Dependencies

The all-in-one package includes these dependencies by default:

- DashScope SDK (Qwen series models)
- MCP SDK (Model Context Protocol)
- Reactor Core, Jackson, SLF4J (base frameworks)

### Additional Dependencies

When using other models or features, add the corresponding dependencies:

| Feature                   | Dependency                                                                               | Maven Coordinates                |
|---------------------------|------------------------------------------------------------------------------------------|----------------------------------|
| **OpenAI Models**         | [OpenAI Java SDK](https://central.sonatype.com/artifact/com.openai/openai-java)          | `com.openai:openai-java`         |
| **Google Gemini Models**  | [Google GenAI SDK](https://central.sonatype.com/artifact/com.google.genai/google-genai)  | `com.google.genai:google-genai`  |
| **Anthropic Models**      | [Anthropic Java SDK](https://central.sonatype.com/artifact/com.anthropic/anthropic-java) | `com.anthropic:anthropic-java`   |
| **Mem0 Long-term Memory** | [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp)              | `com.squareup.okhttp3:okhttp`    |
| **ReME Long-term Memory** | [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp)              | `com.squareup.okhttp3:okhttp`    |
| **Bailian RAG**           | [Bailian SDK](https://central.sonatype.com/artifact/com.aliyun/bailian20231229)          | `com.aliyun:bailian20231229`     |
| **Qdrant RAG**            | [Qdrant Client](https://central.sonatype.com/artifact/io.qdrant/client)                  | `io.qdrant:client`               |
| **Dify RAG**              | [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp)              | `com.squareup.okhttp3:okhttp`    |
| **RAGFlow RAG**           | [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp)              | `com.squareup.okhttp3:okhttp`    |
| **HayStack RAG**          | [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp)              | `com.squareup.okhttp3:okhttp`    |
| **MySQL Session**         | [MySQL Connector](https://central.sonatype.com/artifact/com.mysql/mysql-connector-j)     | `com.mysql:mysql-connector-j`    |
| **Redis Session**         | [Jedis](https://central.sonatype.com/artifact/redis.clients/jedis)                       | `redis.clients:jedis`            |
| **PDF Processing**        | [Apache PDFBox](https://central.sonatype.com/artifact/org.apache.pdfbox/pdfbox)          | `org.apache.pdfbox:pdfbox`       |
| **Word Processing**       | [Apache POI](https://central.sonatype.com/artifact/org.apache.poi/poi-ooxml)             | `org.apache.poi:poi-ooxml`       |
| **Nacos Registry**        | [Nacos Client](https://central.sonatype.com/artifact/com.alibaba.nacos/nacos-client)     | `com.alibaba.nacos:nacos-client` |

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

Connect to [AgentScope Studio](https://github.com/modelscope/agentscope) for visualization and debugging:

| Dependency | Maven Coordinates |
|------------|-------------------|
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

## Core + Extensions

For fine-grained dependency control, use `agentscope-core` with extension modules:

**Maven:**
```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-core</artifactId>
    <version>1.0.6</version>
</dependency>
```

**Gradle:**
```gradle
implementation 'io.agentscope:agentscope-core:1.0.6'
```

### Extension Modules

#### Long-term Memory

| Module | Feature | Maven Coordinates |
|--------|---------|-------------------|
| [agentscope-extensions-mem0](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-mem0) | Mem0 Long-term Memory | `io.agentscope:agentscope-extensions-mem0` |
| [agentscope-extensions-reme](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-reme) | ReME Long-term Memory | `io.agentscope:agentscope-extensions-reme` |
| [agentscope-extensions-autocontext-memory](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-autocontext-memory) | AutoContext Memory | `io.agentscope:agentscope-extensions-autocontext-memory` |

#### RAG

| Module | Feature | Maven Coordinates |
|--------|---------|-------------------|
| [agentscope-extensions-rag-bailian](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-rag-bailian) | Bailian RAG | `io.agentscope:agentscope-extensions-rag-bailian` |
| [agentscope-extensions-rag-simple](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-rag-simple) | Qdrant RAG | `io.agentscope:agentscope-extensions-rag-simple` |
| [agentscope-extensions-rag-dify](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-rag-dify) | Dify RAG | `io.agentscope:agentscope-extensions-rag-dify` |
| [agentscope-extensions-rag-ragflow](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-rag-ragflow) | RAGFlow RAG | `io.agentscope:agentscope-extensions-rag-ragflow` |
| [agentscope-extensions-rag-haystack](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-rag-haystack) | HayStack RAG | `io.agentscope:agentscope-extensions-rag-haystack` |

#### Session Storage

| Module | Feature | Maven Coordinates |
|--------|---------|-------------------|
| [agentscope-extensions-session-mysql](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-session-mysql) | MySQL Session | `io.agentscope:agentscope-extensions-session-mysql` |
| [agentscope-extensions-session-redis](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-session-redis) | Redis Session | `io.agentscope:agentscope-extensions-session-redis` |

#### Multi-Agent Collaboration

| Module | Feature | Maven Coordinates |
|--------|---------|-------------------|
| [agentscope-extensions-a2a-client](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-a2a-client) | A2A Client | `io.agentscope:agentscope-extensions-a2a-client` |
| [agentscope-extensions-a2a-server](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-a2a-server) | A2A Server | `io.agentscope:agentscope-extensions-a2a-server` |

#### Scheduling

| Module | Feature | Maven Coordinates |
|--------|---------|-------------------|
| [agentscope-extensions-scheduler-common](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-scheduler-common) | Scheduler Common | `io.agentscope:agentscope-extensions-scheduler-common` |
| [agentscope-extensions-scheduler-xxl-job](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-scheduler-xxl-job) | XXL-Job Scheduler | `io.agentscope:agentscope-extensions-scheduler-xxl-job` |

#### User Interface

| Module | Feature | Maven Coordinates |
|--------|---------|-------------------|
| [agentscope-extensions-studio](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-studio) | Studio Integration | `io.agentscope:agentscope-extensions-studio` |
| [agentscope-extensions-agui](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-agui) | AG-UI Protocol | `io.agentscope:agentscope-extensions-agui` |

Extension modules automatically include their required third-party dependencies.

#### Example: Core + Mem0 Extension

```xml
<!-- Add on top of agentscope-core -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-mem0</artifactId>
    <version>1.0.6</version>
</dependency>
```

## Framework Integration

### Spring Boot

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-spring-boot-starter</artifactId>
    <version>1.0.6</version>
</dependency>
```

Additional starters:

| Starter | Feature | Maven Coordinates |
|---------|---------|-------------------|
| agentscope-a2a-spring-boot-starter | A2A Integration | `io.agentscope:agentscope-a2a-spring-boot-starter` |
| agentscope-agui-spring-boot-starter | AG-UI Integration | `io.agentscope:agentscope-agui-spring-boot-starter` |

### Quarkus

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-quarkus-extension</artifactId>
    <version>1.0.6</version>
</dependency>
```

### Micronaut

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-micronaut-extension</artifactId>
    <version>1.0.6</version>
</dependency>
```
