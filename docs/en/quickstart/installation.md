# Installation

AgentScope Java requires **JDK 17 or higher**. You can install the library through Maven or build from source.

## Architecture Overview

AgentScope Java provides flexible dependency options:

```
┌─────────────────────────────────────────────────────────────┐
│                    agentscope (all-in-one)                  │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                   agentscope-core                     │  │
│  │  (DashScope, OpenAI, Gemini, Anthropic, MCP built-in) │  │
│  └───────────────────────────────────────────────────────┘  │
│         + DashScope SDK + MCP SDK (transitive)              │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│              Extensions (add dependencies manually)         │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌───────┐  │
│  │    mem0     │ │ rag-bailian │ │ rag-simple  │ │studio │  │
│  └─────────────┘ └─────────────┘ └─────────────┘ └───────┘  │
└─────────────────────────────────────────────────────────────┘
```

## Quick Start (Recommended)

For most users, we recommend the **all-in-one** package which includes the core library with DashScope SDK:

**Maven:**
```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Gradle:**
```gradle
implementation 'io.agentscope:agentscope:1.0.0'
```

## Fine-Grained Dependencies

If you prefer more control over dependencies, you can use `agentscope-core` directly:

**Maven:**
```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Gradle:**
```gradle
implementation 'io.agentscope:agentscope-core:1.0.0'
```

The `agentscope-core` module includes built-in support for:
- **DashScope** (Qwen models)
- **OpenAI** (GPT models)
- **Google Gemini**
- **Anthropic** (Claude models)
- **MCP** (Model Context Protocol)

## Extensions

Extensions are **not included** in the all-in-one package. To use extension features, add the required dependencies manually:

### Mem0 - Long-Term Memory

For persistent memory storage across sessions using [Mem0](https://mem0.ai), add OkHttp:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.0</version>
</dependency>
<!-- Required for Mem0 -->
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>okhttp</artifactId>
</dependency>
```

### RAG - Bailian Knowledge Base

For RAG using Alibaba Cloud [Bailian](https://bailian.console.aliyun.com/) knowledge base:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.0</version>
</dependency>
<!-- Required for Bailian RAG -->
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>bailian20231229</artifactId>
</dependency>
```

### RAG - Simple (Qdrant)

For RAG using [Qdrant](https://qdrant.tech/) vector database with local document processing:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.0</version>
</dependency>
<!-- Required for Qdrant RAG -->
<dependency>
    <groupId>io.qdrant</groupId>
    <artifactId>client</artifactId>
</dependency>
<!-- Optional: PDF document processing -->
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
</dependency>
<!-- Optional: Word document processing -->
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
</dependency>
```

### Studio Integration

For integration with [AgentScope Studio](https://github.com/modelscope/agentscope) visualization and debugging:

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.0</version>
</dependency>
<!-- Required for Studio -->
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

## Build from Source

To build AgentScope Java from source, clone the repository and install locally:

```bash
git clone https://github.com/agentscope-ai/agentscope-java
cd agentscope-java
mvn clean install
```
