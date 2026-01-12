# 安装

AgentScope Java 支持多种模型、RAG 后端和扩展功能，各自需要不同的第三方 SDK。把所有依赖打包到一起会让项目变得臃肿，所以我们提供了两种引入方式：

- **All-in-one**：一个依赖搞定，默认带 DashScope SDK 和 MCP SDK，快速上手
- **Core + 扩展**：最小化核心包，按需加扩展模块，适合对依赖有严格要求的场景

大多数情况下用 all-in-one 就够了，需要精细控制依赖时再换成 core + 扩展。

**要求：JDK 17+**

## 依赖选择

| 方式 | 适用场景 | 特点 |
|-----|---------|------|
| **all-in-one** | 快速开始、大多数用户 | 单一依赖，默认带 DashScope SDK |
| **core + 扩展** | 精细控制依赖 | 按需引入，依赖最小化 |

---

## All-in-One（推荐）

**Maven：**
```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope</artifactId>
    <version>1.0.6</version>
</dependency>
```

**Gradle：**
```gradle
implementation 'io.agentscope:agentscope:1.0.6'
```

### 默认包含的依赖

All-in-one 包默认带以下依赖，不用额外配置：

- DashScope SDK（通义千问系列模型）
- MCP SDK（模型上下文协议）
- Reactor Core、Jackson、SLF4J（基础框架）

### 额外功能的依赖

用其他模型或功能时，需要手动加对应依赖：

| 功能                   | 依赖                                                                                       | Maven 坐标                         |
|----------------------|------------------------------------------------------------------------------------------|----------------------------------|
| **OpenAI 模型**        | [OpenAI Java SDK](https://central.sonatype.com/artifact/com.openai/openai-java)          | `com.openai:openai-java`         |
| **Google Gemini 模型** | [Google GenAI SDK](https://central.sonatype.com/artifact/com.google.genai/google-genai)  | `com.google.genai:google-genai`  |
| **Anthropic 模型**     | [Anthropic Java SDK](https://central.sonatype.com/artifact/com.anthropic/anthropic-java) | `com.anthropic:anthropic-java`   |
| **Mem0 长期记忆**        | [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp)              | `com.squareup.okhttp3:okhttp`    |
| **ReME 长期记忆**        | [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp)              | `com.squareup.okhttp3:okhttp`    |
| **百炼 RAG**           | [百炼 SDK](https://central.sonatype.com/artifact/com.aliyun/bailian20231229)               | `com.aliyun:bailian20231229`     |
| **Qdrant RAG**       | [Qdrant Client](https://central.sonatype.com/artifact/io.qdrant/client)                  | `io.qdrant:client`               |
| **Dify RAG**         | [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp)              | `com.squareup.okhttp3:okhttp`    |
| **RAGFlow RAG**      | [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp)              | `com.squareup.okhttp3:okhttp`    |
| **HayStack RAG**     | [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp)              | `com.squareup.okhttp3:okhttp`    |
| **MySQL Session**    | [MySQL Connector](https://central.sonatype.com/artifact/com.mysql/mysql-connector-j)     | `com.mysql:mysql-connector-j`    |
| **Redis Session**    | [Jedis](https://central.sonatype.com/artifact/redis.clients/jedis)                       | `redis.clients:jedis`            |
| **PDF 处理**           | [Apache PDFBox](https://central.sonatype.com/artifact/org.apache.pdfbox/pdfbox)          | `org.apache.pdfbox:pdfbox`       |
| **Word 处理**          | [Apache POI](https://central.sonatype.com/artifact/org.apache.poi/poi-ooxml)             | `org.apache.poi:poi-ooxml`       |
| **Nacos注册中心**        | [Nacos Client](https://central.sonatype.com/artifact/com.alibaba.nacos/nacos-client)     | `com.alibaba.nacos:nacos-client` |

#### 示例：用 OpenAI 模型

```xml
<!-- 在 agentscope 基础上加 -->
<dependency>
    <groupId>com.openai</groupId>
    <artifactId>openai-java</artifactId>
</dependency>
```

#### 示例：用 Qdrant RAG + PDF 处理

```xml
<!-- 在 agentscope 基础上加 -->
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

接入 [AgentScope Studio](https://github.com/modelscope/agentscope) 做可视化调试，需要加这些依赖：

| 依赖 | Maven 坐标 |
|-----|-----------|
| [OkHttp](https://central.sonatype.com/artifact/com.squareup.okhttp3/okhttp) | `com.squareup.okhttp3:okhttp` |
| [Socket.IO Client](https://central.sonatype.com/artifact/io.socket/socket.io-client) | `io.socket:socket.io-client` |
| [OpenTelemetry API](https://central.sonatype.com/artifact/io.opentelemetry/opentelemetry-api) | `io.opentelemetry:opentelemetry-api` |
| [OpenTelemetry OTLP Exporter](https://central.sonatype.com/artifact/io.opentelemetry/opentelemetry-exporter-otlp) | `io.opentelemetry:opentelemetry-exporter-otlp` |
| [OpenTelemetry Reactor](https://central.sonatype.com/artifact/io.opentelemetry.instrumentation/opentelemetry-reactor-3.1) | `io.opentelemetry.instrumentation:opentelemetry-reactor-3.1` |

完整配置：

```xml
<!-- 在 agentscope 基础上加 -->
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

---

## Core + 扩展

需要精细控制依赖时，用 `agentscope-core` 配合扩展模块：

**Maven：**
```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-core</artifactId>
    <version>1.0.6</version>
</dependency>
```

**Gradle：**
```gradle
implementation 'io.agentscope:agentscope-core:1.0.6'
```

### 扩展模块

#### 长期记忆

| 模块 | 功能 | Maven 坐标 |
|-----|------|-----------|
| [agentscope-extensions-mem0](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-mem0) | Mem0 长期记忆 | `io.agentscope:agentscope-extensions-mem0` |
| [agentscope-extensions-reme](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-reme) | ReME 长期记忆 | `io.agentscope:agentscope-extensions-reme` |
| [agentscope-extensions-autocontext-memory](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-autocontext-memory) | AutoContext 记忆 | `io.agentscope:agentscope-extensions-autocontext-memory` |

#### RAG

| 模块 | 功能 | Maven 坐标 |
|-----|------|-----------|
| [agentscope-extensions-rag-bailian](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-rag-bailian) | 百炼 RAG | `io.agentscope:agentscope-extensions-rag-bailian` |
| [agentscope-extensions-rag-simple](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-rag-simple) | Qdrant RAG | `io.agentscope:agentscope-extensions-rag-simple` |
| [agentscope-extensions-rag-dify](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-rag-dify) | Dify RAG | `io.agentscope:agentscope-extensions-rag-dify` |
| [agentscope-extensions-rag-ragflow](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-rag-ragflow) | RAGFlow RAG | `io.agentscope:agentscope-extensions-rag-ragflow` |
| [agentscope-extensions-rag-haystack](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-rag-haystack) | HayStack RAG | `io.agentscope:agentscope-extensions-rag-haystack` |

#### Session 存储

| 模块 | 功能 | Maven 坐标 |
|-----|------|-----------|
| [agentscope-extensions-session-mysql](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-session-mysql) | MySQL Session | `io.agentscope:agentscope-extensions-session-mysql` |
| [agentscope-extensions-session-redis](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-session-redis) | Redis Session | `io.agentscope:agentscope-extensions-session-redis` |

#### 多智能体协作

| 模块 | 功能 | Maven 坐标 |
|-----|------|-----------|
| [agentscope-extensions-a2a-client](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-a2a-client) | A2A 客户端 | `io.agentscope:agentscope-extensions-a2a-client` |
| [agentscope-extensions-a2a-server](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-a2a-server) | A2A 服务端 | `io.agentscope:agentscope-extensions-a2a-server` |

#### 调度

| 模块 | 功能 | Maven 坐标 |
|-----|------|-----------|
| [agentscope-extensions-scheduler-common](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-scheduler-common) | 调度通用模块 | `io.agentscope:agentscope-extensions-scheduler-common` |
| [agentscope-extensions-scheduler-xxl-job](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-scheduler-xxl-job) | XXL-Job 调度 | `io.agentscope:agentscope-extensions-scheduler-xxl-job` |

#### 用户界面

| 模块 | 功能 | Maven 坐标 |
|-----|------|-----------|
| [agentscope-extensions-studio](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-studio) | Studio 集成 | `io.agentscope:agentscope-extensions-studio` |
| [agentscope-extensions-agui](https://central.sonatype.com/artifact/io.agentscope/agentscope-extensions-agui) | AG-UI 协议 | `io.agentscope:agentscope-extensions-agui` |

扩展模块会自动带上所需的第三方依赖，不用手动加。

#### 示例：Core + Mem0 扩展

```xml
<!-- 在 agentscope-core 基础上加 -->
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-mem0</artifactId>
    <version>1.0.6</version>
</dependency>
```

## 框架集成

### Spring Boot

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-spring-boot-starter</artifactId>
    <version>1.0.6</version>
</dependency>
```

其他 Starter：

| Starter | 功能 | Maven 坐标 |
|---------|------|-----------|
| agentscope-a2a-spring-boot-starter | A2A 集成 | `io.agentscope:agentscope-a2a-spring-boot-starter` |
| agentscope-agui-spring-boot-starter | AG-UI 集成 | `io.agentscope:agentscope-agui-spring-boot-starter` |

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
