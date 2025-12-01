# 安装

AgentScope Java 需要 **JDK 17 或更高版本**。您可以通过 Maven 安装或从源代码构建。

## 架构概览

AgentScope Java 提供灵活的依赖选项：

```
┌─────────────────────────────────────────────────────────────┐
│                    agentscope (all-in-one)                  │
│  ┌───────────────────────────────────────────────────────┐  │
│  │                   agentscope-core                     │  │
│  │  (内置 DashScope, OpenAI, Gemini, Anthropic, MCP)     │  │
│  └───────────────────────────────────────────────────────┘  │
│         + DashScope SDK + MCP SDK (传递依赖)                │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                 扩展模块 (需手动添加依赖)                    │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐ ┌───────┐  │
│  │    mem0     │ │ rag-bailian │ │ rag-simple  │ │studio │  │
│  └─────────────┘ └─────────────┘ └─────────────┘ └───────┘  │
└─────────────────────────────────────────────────────────────┘
```

## 快速开始（推荐）

对于大多数用户，我们推荐使用 **all-in-one** 包，它包含核心库并传递 DashScope SDK：

**Maven：**
```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Gradle：**
```gradle
implementation 'io.agentscope:agentscope:1.0.0'
```

## 精细化依赖

如果您需要更精细地控制依赖，可以直接使用 `agentscope-core`：

**Maven：**
```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-core</artifactId>
    <version>1.0.0</version>
</dependency>
```

**Gradle：**
```gradle
implementation 'io.agentscope:agentscope-core:1.0.0'
```

`agentscope-core` 模块内置支持以下模型：
- **DashScope**（通义千问系列）
- **OpenAI**（GPT 系列）
- **Google Gemini**
- **Anthropic**（Claude 系列）
- **MCP**（模型上下文协议）

## 扩展模块

扩展模块**不包含**在 all-in-one 包中。使用扩展功能时，需要手动添加相应的依赖：

### Mem0 - 长期记忆

使用 [Mem0](https://mem0.ai) 实现跨会话的持久化记忆存储，需添加 OkHttp：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.0</version>
</dependency>
<!-- Mem0 所需依赖 -->
<dependency>
    <groupId>com.squareup.okhttp3</groupId>
    <artifactId>okhttp</artifactId>
</dependency>
```

### RAG - 百炼知识库

使用阿里云[百炼](https://bailian.console.aliyun.com/)知识库实现 RAG：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.0</version>
</dependency>
<!-- 百炼 RAG 所需依赖 -->
<dependency>
    <groupId>com.aliyun</groupId>
    <artifactId>bailian20231229</artifactId>
</dependency>
```

### RAG - Simple（Qdrant）

使用 [Qdrant](https://qdrant.tech/) 向量数据库实现 RAG，支持本地文档处理：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.0</version>
</dependency>
<!-- Qdrant RAG 所需依赖 -->
<dependency>
    <groupId>io.qdrant</groupId>
    <artifactId>client</artifactId>
</dependency>
<!-- 可选：PDF 文档处理 -->
<dependency>
    <groupId>org.apache.pdfbox</groupId>
    <artifactId>pdfbox</artifactId>
</dependency>
<!-- 可选：Word 文档处理 -->
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
</dependency>
```

### Studio 集成

集成 [AgentScope Studio](https://github.com/modelscope/agentscope) 实现可视化和调试：

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.0</version>
</dependency>
<!-- Studio 所需依赖 -->
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

## 从源代码构建

从源代码构建 AgentScope Java，需要克隆仓库并在本地安装：

```bash
git clone https://github.com/agentscope-ai/agentscope-java
cd agentscope-java
mvn clean install
```
