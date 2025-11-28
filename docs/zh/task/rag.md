# RAG (检索增强生成)

AgentScope 提供了内置的检索增强生成（RAG）支持，使得 Agent 能够访问和利用外部知识库，从而提供更准确、更具信息量的响应。

## 概述

AgentScope 中的 RAG 模块由两个核心组件组成：

- **Reader（读取器）**：负责读取和分块输入文档，将其转换为可处理的单元
- **Knowledge（知识库）**：负责存储文档、生成嵌入向量以及检索相关信息

AgentScope 支持两种类型的知识库实现：

| 类型 | 实现 | 特点 | 适用场景 |
|------|------|------|---------|
| **本地知识库** | `SimpleKnowledge` | 需要本地嵌入模型和向量存储 | 开发、测试、完全控制数据 |
| **云托管知识库** | `BailianKnowledge` | 使用阿里云百炼知识库服务 | 企业级生产、免维护、高级检索功能 |

## 支持的 Reader

AgentScope 提供了多种内置 Reader 用于处理不同格式的文档：

| Reader | 描述 | 支持格式 |
|--------|------|---------|
| `TextReader` | 读取和分块纯文本文档 | text |
| `PDFReader` | 从 PDF 文件中提取文本 | pdf |
| `WordReader` | 从 Word 文档中提取文本、表格和图片 | docx |
| `ImageReader` | 读取图像文件（用于多模态 RAG） | jpg, jpeg, png, gif, bmp, tiff, webp |

每个 Reader 将文档分块为 `Document` 对象，包含以下字段：

- `metadata`：包含内容（TextBlock/ImageBlock）、doc_id、chunk_id 和 total_chunks
- `embedding`：嵌入向量（添加到知识库或从知识库检索时填充）
- `score`：相关性分数（检索时填充）

## 快速开始

### 1. 创建知识库

首先，使用嵌入模型和向量存储创建知识库：

```java
import io.agentscope.core.embedding.EmbeddingModel;
import io.agentscope.core.embedding.dashscope.DashScopeTextEmbedding;
import io.agentscope.core.rag.Knowledge;
import io.agentscope.core.rag.knowledge.SimpleKnowledge;
import io.agentscope.core.rag.store.InMemoryStore;
import io.agentscope.core.rag.store.VDBStoreBase;

// 创建嵌入模型
EmbeddingModel embeddingModel = DashScopeTextEmbedding.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("text-embedding-v3")
        .dimensions(1024)
        .build();

// 创建向量存储
VDBStoreBase vectorStore = InMemoryStore.builder()
        .dimensions(1024)
        .build();

// 创建知识库
Knowledge knowledge = SimpleKnowledge.builder()
        .embeddingModel(embeddingModel)
        .embeddingStore(vectorStore)
        .build();
```

### 2. 添加文档

使用 Reader 处理文档并将其添加到知识库：

```java
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.ReaderInput;
import io.agentscope.core.rag.reader.SplitStrategy;
import io.agentscope.core.rag.reader.TextReader;

// 创建文本 Reader
TextReader reader = new TextReader(512, SplitStrategy.PARAGRAPH, 50);

// 读取和分块文档
String text = "AgentScope 是一个多智能体框架...";
ReaderInput input = ReaderInput.fromString(text);
List<Document> documents = reader.read(input).block();

// 添加到知识库
knowledge.addDocuments(documents).block();
```

### 3. 检索知识

查询知识库以检索相关文档：

```java
import io.agentscope.core.rag.model.Document;
import io.agentscope.core.rag.model.RetrieveConfig;

// 配置检索参数
RetrieveConfig config = RetrieveConfig.builder()
    .limit(3)                    // 返回前 3 个结果
    .scoreThreshold(0.5)         // 最小相似度分数
    .build();

// 检索文档
List<Document> results = knowledge.retrieve("什么是 AgentScope?", config).block();

for (Document doc : results) {
    System.out.println("分数: " + doc.getScore());
    System.out.println("内容: " + doc.getMetadata().getContent());
}
```

## 云托管知识库（Bailian）

AgentScope 支持阿里云百炼知识库服务，提供企业级的云托管 RAG 解决方案。与本地知识库不同，Bailian 知识库无需本地嵌入模型或向量存储，所有文档处理、嵌入和检索都由云服务处理。

### 核心特性

- **零基础设施**：无需部署和维护向量数据库
- **自动处理**：文档自动解析、分块和嵌入
- **企业级检索**：支持 reranking（重排序）和 query rewriting（查询重写）
- **多轮对话**：自动利用会话历史改进检索准确性
- **结构化/非结构化数据**：支持多种知识库类型

### 快速开始

#### 1. 配置 Bailian 连接

```java
import io.agentscope.core.rag.integration.bailian.BailianConfig;
import io.agentscope.core.rag.integration.bailian.BailianKnowledge;

// 配置 Bailian 连接
BailianConfig config = BailianConfig.builder()
    .accessKeyId(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"))
    .accessKeySecret(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"))
    .workspaceId("llm-xxx")        // 您的工作空间 ID
    .indexId("mymxbdxxxx")         // 您的知识库索引 ID
    .build();

// 创建知识库实例
BailianKnowledge knowledge = BailianKnowledge.builder()
    .config(config)
    .build();
```

#### 2. 配置高级检索选项

Bailian 支持丰富的检索配置选项：

```java
import io.agentscope.core.rag.integration.bailian.RerankConfig;
import io.agentscope.core.rag.integration.bailian.RewriteConfig;

BailianConfig config = BailianConfig.builder()
    .accessKeyId(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"))
    .accessKeySecret(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"))
    .workspaceId("llm-xxx")
    .indexId("mymxbdxxxx")
    // 配置密集向量检索
    .denseSimilarityTopK(20)       // 密集检索返回 top 20
    // 配置稀疏向量检索（可选）
    .sparseSimilarityTopK(10)      // 稀疏检索返回 top 10
    // 启用 reranking
    .enableReranking(true)
    .rerankConfig(
        RerankConfig.builder()
            .modelName("gte-rerank-hybrid")
            .rerankMinScore(0.3f)   // 重排序最小分数
            .rerankTopN(5)          // 返回 top 5 结果
            .build())
    // 启用查询重写（多轮对话）
    .enableRewrite(true)
    .rewriteConfig(
        RewriteConfig.builder()
            .modelName("conv-rewrite-qwen-1.8b")
            .build())
    .build();
```

#### 3. 检索文档

```java
import io.agentscope.core.rag.model.RetrieveConfig;
import io.agentscope.core.rag.model.Document;

// 配置检索参数
RetrieveConfig retrieveConfig = RetrieveConfig.builder()
    .limit(5)                       // 最多返回 5 个文档
    .scoreThreshold(0.3)            // 最小相似度分数
    .build();

// 检索文档
List<Document> results = knowledge.retrieve("什么是 RAG?", retrieveConfig).block();

for (Document doc : results) {
    System.out.println("分数: " + doc.getScore());
    System.out.println("文档 ID: " + doc.getMetadata().getDocId());
    System.out.println("内容: " + doc.getMetadata().getContent());
}
```

#### 4. 带会话历史的多轮检索

Bailian 可以利用会话历史改进检索效果，自动根据上下文重写查询：

```java
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;

// 准备会话历史
List<Msg> conversationHistory = List.of(
    Msg.builder().textContent("AgentScope 是什么?").build(),
    Msg.builder().role(MsgRole.ASSISTANT).textContent("AgentScope 是一个多智能体框架...").build()
);

// 带历史的检索配置
RetrieveConfig config = RetrieveConfig.builder()
    .limit(5)
    .scoreThreshold(0.3)
    .conversationHistory(conversationHistory)  // 添加会话历史
    .build();

// 查询会被自动重写以考虑上下文
List<Document> results = knowledge.retrieve("它有哪些特性?", config).block();
```

### 与 ReActAgent 集成

在 Agentic 模式下，Agent 会自动从其 Memory 中提取会话历史并传递给 Bailian 进行上下文感知检索：

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.rag.RAGMode;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.rag.KnowledgeRetrievalTools;

// 创建 Bailian 知识库
BailianKnowledge knowledge = BailianKnowledge.builder()
    .config(bailianConfig)
    .build();

// 使用 Agentic 模式
ReActAgent agent = ReActAgent.builder()
    .name("智能助手")
    .sysPrompt("你是一个拥有知识检索工具的助手。需要信息时使用 retrieve_knowledge 工具。")
    .model(chatModel)
    .toolkit(new Toolkit())
    .knowledge(knowledge)
    .ragMode(RAGMode.AGENTIC)      // Agent 自主决定何时检索
    .retrieveConfig(
        RetrieveConfig.builder()
            .limit(5)
            .scoreThreshold(0.3)
            .build())
    .build();

// 多轮对话会自动利用历史上下文
agent.call(Msg.builder().textContent("AgentScope 是什么?").build());
agent.call(Msg.builder().textContent("它支持哪些模型?").build());
// 第二个查询会利用第一轮对话的上下文，提高检索准确性
```

### 文档管理

**注意**：目前文档上传和管理需要通过百炼控制台完成。API 方式的文档管理将在未来版本中支持。

1. 登录[阿里云百炼平台](https://bailian.console.aliyun.com/)
2. 创建知识库并上传文档
3. 获取 workspace ID 和 index ID
4. 在代码中使用这些 ID 进行检索

### Bailian vs SimpleKnowledge

| 特性 | SimpleKnowledge | BailianKnowledge |
|------|----------------|------------------|
| **部署** | 需要本地嵌入模型和向量存储 | 云服务，零部署 |
| **文档处理** | 需要自己编写 Reader 代码 | 控制台上传，自动处理 |
| **检索能力** | 基础向量检索 | 高级检索（reranking, rewriting） |
| **扩展性** | 受限于本地资源 | 云服务自动扩展 |
| **成本** | 计算资源成本 | 按使用量付费 |
| **数据控制** | 完全本地控制 | 托管在云端 |
| **多轮对话** | 需要手动实现 | 自动支持 |
| **适用场景** | 开发、测试、小规模 | 生产、企业级、大规模 |

### 完整示例

查看完整的 Bailian RAG 示例：
- `examples/src/main/java/io/agentscope/examples/BailianRAGExample.java`

运行示例：
```bash
cd examples
# 设置环境变量
export ALIBABA_CLOUD_ACCESS_KEY_ID="your-access-key-id"
export ALIBABA_CLOUD_ACCESS_KEY_SECRET="your-access-key-secret"
export BAILIAN_WORKSPACE_ID="your-workspace-id"
export BAILIAN_INDEX_ID="your-index-id"

mvn exec:java -Dexec.mainClass="io.agentscope.examples.BailianRAGExample"
```

## 与 ReActAgent 集成

AgentScope 支持两种将 RAG 与 ReActAgent 集成的模式：

| 模式 | 描述 | 优点 | 缺点 |
|------|------|------|------|
| **Generic 模式** | 在每个推理步骤之前自动检索和注入知识 | 简单，适用于任何 LLM | 即使不需要也会检索 |
| **Agentic 模式** | Agent 使用工具决定何时检索 | 灵活，只在需要时检索 | 需要强大的推理能力 |

### Generic 模式

在 Generic 模式下，知识会自动检索并注入到用户的消息中：

```java
import io.agentscope.core.ReActAgent;
import io.agentscope.core.rag.RAGMode;

ReActAgent agent = ReActAgent.builder()
    .name("助手")
    .sysPrompt("你是一个可以访问知识库的有用助手。")
    .model(chatModel)
    .toolkit(new Toolkit())
    // 启用 Generic RAG 模式
    .knowledge(knowledge)
    .ragMode(RAGMode.GENERIC)
    .retrieveConfig(
        RetrieveConfig.builder()
            .limit(3)
            .scoreThreshold(0.3)
            .build())
    .enableOnlyForUserQueries(true)  // 仅为用户消息检索
    .build();

// Agent 会自动为每个查询检索知识
agent.call(Msg.builder()
    .name("user")
    .textContent("什么是 AgentScope?")
    .build());
```

**工作原理：**
1. 用户发送查询
2. 知识库自动检索相关文档
3. 检索到的文档被添加到用户消息之前
4. Agent 处理增强后的消息并响应

### Agentic 模式

在 Agentic 模式下，Agent 拥有 `retrieve_knowledge` 工具并决定何时使用它：

```java
ReActAgent agent = ReActAgent.builder()
    .name("智能体")
    .sysPrompt("你是一个拥有知识检索工具的有用助手。" +
               "需要信息时使用 retrieve_knowledge 工具。")
    .model(chatModel)
    .toolkit(new Toolkit())
    // 启用 Agentic RAG 模式
    .knowledge(knowledge)
    .ragMode(RAGMode.AGENTIC)
    .retrieveConfig(
        RetrieveConfig.builder()
            .limit(3)
            .scoreThreshold(0.5)
            .build())
    .build();

// Agent 决定何时检索
agent.call(Msg.builder()
    .name("user")
    .textContent("什么是 RAG?")
    .build());
```

**工作原理：**
1. 用户发送查询
2. Agent 推理并决定是否检索知识
3. 如果需要，Agent 调用 `retrieve_knowledge(query="...")`
4. 检索到的文档作为工具结果返回
5. Agent 使用检索到的信息再次推理

## 读取不同类型的文档

### 文本文档

```java
TextReader reader = new TextReader(
    512,                      // 分块大小
    SplitStrategy.PARAGRAPH,  // 按段落分割
    50                        // 重叠大小
);

ReaderInput input = ReaderInput.fromString("你的文本内容...");
List<Document> docs = reader.read(input).block();
```

支持的分割策略：
- `SplitStrategy.CHARACTER`：按字符数分割
- `SplitStrategy.PARAGRAPH`：按段落分割（双换行符）
- `SplitStrategy.SENTENCE`：按句子分割
- `SplitStrategy.TOKEN`：按近似 token 数分割

### PDF 文档

```java
import io.agentscope.core.rag.reader.PDFReader;

PDFReader reader = new PDFReader(512, SplitStrategy.PARAGRAPH, 50);
ReaderInput input = ReaderInput.fromString("/path/to/document.pdf");
List<Document> docs = reader.read(input).block();
```

### Word 文档

```java
import io.agentscope.core.rag.reader.WordReader;
import io.agentscope.core.rag.reader.TableFormat;

WordReader reader = new WordReader(
    512,                      // 分块大小
    SplitStrategy.PARAGRAPH,  // 分割策略
    50,                       // 重叠大小
    true,                     // 包含图片
    true,                     // 将表格作为单独的块
    TableFormat.MARKDOWN      // 表格格式（MARKDOWN 或 JSON）
);

ReaderInput input = ReaderInput.fromString("/path/to/document.docx");
List<Document> docs = reader.read(input).block();
```

### 图像文档（多模态 RAG）

```java
import io.agentscope.core.rag.reader.ImageReader;
import io.agentscope.core.embedding.dashscope.DashScopeMultiModalEmbedding;
import io.agentscope.core.rag.store.InMemoryStore;
import io.agentscope.core.rag.store.VDBStoreBase;

// 创建多模态嵌入模型
EmbeddingModel embeddingModel = DashScopeMultiModalEmbedding.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("multimodal-embedding-one")
    .dimensions(1024)
    .build();

// 创建向量存储
VDBStoreBase vectorStore = InMemoryStore.builder()
    .dimensions(1024)
    .build();

// 使用多模态嵌入创建知识库
Knowledge knowledge = SimpleKnowledge.builder()
    .embeddingModel(embeddingModel)
    .embeddingStore(vectorStore)
    .build();

// 读取图像
ImageReader reader = new ImageReader(false);  // 禁用 OCR
ReaderInput input = ReaderInput.fromString("/path/to/image.jpg");
// 或从 URL
// ReaderInput input = ReaderInput.fromString("https://example.com/image.jpg");

List<Document> docs = reader.read(input).block();
knowledge.addDocuments(docs).block();
```

## 向量存储

AgentScope 支持多种向量存储后端：

### 内存存储

快速，适合开发和小型数据集：

```java
InMemoryStore store = InMemoryStore.builder()
    .dimensions(1024)
    .build();
```

### Qdrant 存储

生产就绪的向量数据库，支持持久化：

```java
import io.agentscope.core.rag.store.QdrantStore;

QdrantStore store = QdrantStore.builder()
    .location("localhost:6334")      // Qdrant 服务器位置
    .collectionName("my_collection")
    .dimensions(1024)
    .apiKey("your-api-key")          // 可选：用于云服务
    .useTransportLayerSecurity(true) // 启用 TLS
    .build();
```

Qdrant 通过 `location` 参数支持多种存储后端：
- `:memory:` - 内存存储
- `path/to/db` - 本地文件存储
- `localhost:6334` - 远程服务器（gRPC）
- `http://localhost:6333` - 远程服务器（REST）

## 自定义 RAG 组件

AgentScope 鼓励自定义 RAG 组件。你可以扩展以下基类：

| 基类 | 描述 | 抽象方法 |
|------|------|----------|
| `Reader` | 文档读取器基类 | `read()`, `getSupportedFormats()` |
| `VDBStoreBase` | 向量存储基类 | `add()`, `search()` |
| `Knowledge` | 知识库实现基类 | `addDocuments()`, `retrieve()` |

### 自定义 Reader 示例

```java
import io.agentscope.core.rag.reader.Reader;
import reactor.core.publisher.Mono;

public class CustomReader implements Reader {

    @Override
    public Mono<List<Document>> read(ReaderInput input) throws ReaderException {
        return Mono.fromCallable(() -> {
            // 你的自定义读取逻辑
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
        // 创建带有元数据的 Document 对象
        // ...
    }
}
```

## 最佳实践

1. **分块大小**：根据模型的上下文窗口和使用场景选择分块大小。典型值：256-1024 个字符。

2. **重叠**：使用 10-20% 的重叠以保持块之间的上下文连续性。

3. **分数阈值**：从 0.3-0.5 开始，根据检索质量调整。

4. **Top-K**：初始检索 3-5 个文档，根据上下文窗口限制调整。

5. **模式选择**：
   - 使用 **Generic 模式**：简单问答、一致的检索模式、较弱的 LLM
   - 使用 **Agentic 模式**：复杂任务、选择性检索、强大的 LLM

6. **向量存储选择**：
   - 使用 **InMemoryStore**：开发、测试、小型数据集（<10K 文档）
   - 使用 **QdrantStore**：生产环境、大型数据集、需要持久化

7. **嵌入模型**：
   - 对纯文本文档使用**文本嵌入**
   - 对混合内容（文本 + 图像）使用**多模态嵌入**

## 完整示例

查看完整的 RAG 示例：
- `examples/src/main/java/io/agentscope/examples/RAGExample.java`

运行示例：
```bash
cd examples
mvn exec:java -Dexec.mainClass="io.agentscope.examples.RAGExample"
```
