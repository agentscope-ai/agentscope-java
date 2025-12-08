# 安装

AgentScope Java 支持多种大模型供应商、RAG 后端和扩展功能，每种都需要不同的第三方 SDK。如果将所有依赖打包在一起，您的项目将被大量可能永远用不到的依赖拖累——增加 JAR 体积、拖慢构建速度，还可能引发版本冲突。

为了平衡**易用性**和**依赖控制**，我们提供两种方式：

- **All-in-one**：单一依赖，内置合理的默认配置（DashScope SDK、MCP SDK）。适合快速上手，按需添加额外依赖即可。
- **Core + 扩展**：从最小化的核心包开始，只添加实际使用的扩展模块。适合对依赖有严格要求的生产环境。

**我们的建议：** 快速开发时使用 all-in-one。当需要优化依赖体积或解决冲突时，再切换到 core + 扩展方式。

**未来规划：** 我们正在将模型供应商 SDK 替换为原生 HTTP 实现。这将大幅减少外部依赖，同时保持对所有已支持模型的完全兼容。

AgentScope Java 需要 **JDK 17 或更高版本**。

## 依赖选择

AgentScope Java 提供两种依赖方式：

| 方式 | 适用场景 | 特点 |
|-----|---------|------|
| **all-in-one** | 快速开始、大多数用户 | 单一依赖，默认传递 DashScope SDK |
| **core + 扩展** | 需要精细控制依赖 | 按需引入，减少不必要的依赖 |

## 方式一：All-in-One（推荐）

**Maven：**
```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.1</version>
</dependency>
```

**Gradle：**
```gradle
implementation 'io.agentscope:agentscope:1.0.1'
```

### 默认传递的依赖

All-in-one 包默认传递以下依赖，无需额外配置：

- DashScope SDK（通义千问系列模型）
- MCP SDK（模型上下文协议）
- Reactor Core、Jackson、SLF4J（基础框架）

### 额外功能所需依赖

使用其他模型或扩展功能时，需要手动添加对应依赖：

| 功能 | 所需依赖 | Maven 坐标 |
|-----|---------|-----------|
| **OpenAI 模型** | [OpenAI Java SDK](https://central.sonatype.com/artifact/com.openai/openai-java) | `com.openai:openai-java` |
| **Google Gemini 模型** | [Google GenAI SDK](https://central.sonatype.com/artifact/com.google.genai/google-genai) | `com.google.genai:google-genai` |
| **Anthropic 模型** | [Anthropic Java SDK](https://central.sonatype.com/artifact/com.anthropic/anthropic-java) | `com.anthropic:anthropic-java` |
| **Mem0 长期记忆** | [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp) | `com.squareup.okhttp3:okhttp` |
| **百炼 RAG** | [百炼 SDK](https://central.sonatype.com/artifact/com.aliyun/bailian20231229) | `com.aliyun:bailian20231229` |
| **Qdrant RAG** | [Qdrant Client](https://central.sonatype.com/artifact/io.qdrant/client) | `io.qdrant:client` |
| **PDF 文档处理** | [Apache PDFBox](https://central.sonatype.com/artifact/org.apache.pdfbox/pdfbox) | `org.apache.pdfbox:pdfbox` |
| **Word 文档处理** | [Apache POI](https://central.sonatype.com/artifact/org.apache.poi/poi-ooxml) | `org.apache.poi:poi-ooxml` |

#### 示例：使用 OpenAI 模型

```xml
<!-- 在 agentscope 基础上添加 -->
<dependency>
    <groupId>com.openai</groupId>
    <artifactId>openai-java</artifactId>
</dependency>
```

#### 示例：使用 Qdrant RAG + PDF 处理

```xml
<!-- 在 agentscope 基础上添加 -->
<dependency>
    <groupId>io.qdrant</groupId>
    <artifactId>client</artifactId>
</dependency>
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
</dependency>
```

### Studio 集成

集成 [AgentScope Studio](https://github.com/modelscope/agentscope) 实现可视化和调试，需要添加以下依赖：

| 所需依赖 | Maven 坐标 |
|---------|-----------|
| [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp) | `com.squareup.okhttp3:okhttp` |
| [Socket.IO Client](https://central.sonatype.com/artifact/io.socket/socket.io-client) | `io.socket:socket.io-client` |
| [OpenTelemetry API](https://central.sonatype.com/artifact/io.opentelemetry/opentelemetry-api) | `io.opentelemetry:opentelemetry-api` |
| [OpenTelemetry OTLP Exporter](https://central.sonatype.com/artifact/io.opentelemetry/opentelemetry-exporter-otlp) | `io.opentelemetry:opentelemetry-exporter-otlp` |
| [OpenTelemetry Reactor](https://central.sonatype.com/artifact/io.opentelemetry.instrumentation/opentelemetry-reactor-3.1) | `io.opentelemetry.instrumentation:opentelemetry-reactor-3.1` |

完整配置：

```xml
<!-- 在 agentscope 基础上添加 -->
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

## 方式二：Core + 扩展

如果您需要更精细地控制依赖，可以使用 `agentscope-core` 配合扩展模块：

**Maven：**
```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-core</artifactId>
    <version>1.0.1</version>
</dependency>
```

**Gradle：**
```gradle
implementation 'io.agentscope:agentscope-core:1.0.1'
```

### 扩展模块

| 模块 | 功能 | Maven 坐标 |
|-----|------|-----------|
| [agentscope-extensions-mem0](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-mem0) | Mem0 长期记忆 | `io.agentscope:agentscope-extensions-mem0` |
| [agentscope-extensions-rag-bailian](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-rag-bailian) | 百炼知识库 RAG | `io.agentscope:agentscope-extensions-rag-bailian` |
| [agentscope-extensions-rag-simple](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-rag-simple) | Qdrant 向量检索 RAG | `io.agentscope:agentscope-extensions-rag-simple` |
| [agentscope-extensions-studio](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-studio) | AgentScope Studio 集成 | `io.agentscope:agentscope-extensions-studio` |

扩展模块会自动传递所需的第三方依赖，无需手动添加。

#### 示例：Core + Mem0 扩展

```xml
<!-- 在 agentscope-core 基础上添加 -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-mem0</artifactId>
    <version>1.0.1</version>
</dependency>
```
