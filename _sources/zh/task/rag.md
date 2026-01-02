# RAG (检索增强生成)

AgentScope 提供内置 RAG 支持，使 Agent 能够访问外部知识库。

## 概述
### 核心组件
AgentScope 中的 RAG 模块由两个核心组件组成：

- **Reader（读取器）**：负责读取和分块输入文档，将其转换为可处理的单元
- **Knowledge（知识库）**：负责存储文档、生成嵌入向量以及检索相关信息

### 支持范围

AgentScope 支持多种类型的知识库实现：

| 类型 | 实现 | 支持功能 | 文档管理 | 适用场景 |
|------|------|---------|---------|---------|
| **本地知识库** | `SimpleKnowledge` | 完整的文档管理和检索 | 通过代码管理（使用 Reader） | 开发、测试、完全控制数据 |
| **云托管知识库** | `BailianKnowledge` | 仅检索 | [百炼控制台](https://bailian.console.aliyun.com/) | 企业级、多轮对话、查询重写 |
| **Dify 知识库** | `DifyKnowledge` | 仅检索 | Dify 控制台 | 多种检索模式、Reranking |
| **RAGFlow 知识库** | `RAGFlowKnowledge` | 仅检索 | RAGFlow 控制台 | 强大OCR、知识图谱、多数据集 |


###  集成模式

AgentScope 支持两种 RAG 集成模式：

| 模式 | 描述 | 优点 | 缺点 |
|------|------|------|------|
| **Generic 模式** | 在每个推理步骤之前自动检索和注入知识 | 简单，适用于任何 LLM | 即使不需要也会检索 |
| **Agentic 模式** | Agent 使用工具决定何时检索 | 灵活，只在需要时检索 | 需要强大的推理能力 |

#### Generic 模式

在 Generic 模式下，知识会自动检索并注入到用户的消息中：

```java
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
```

工作原理：
1. 用户发送查询
2. 知识库自动检索相关文档
3. 检索到的文档被添加到用户消息之前
4. Agent 处理增强后的消息并响应

#### Agentic 模式

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
```

**工作原理：**
1. 用户发送查询
2. Agent 推理并决定是否检索知识
3. 如果需要，Agent 调用 `retrieve_knowledge(query="...")`
4. 检索到的文档作为工具结果返回
5. Agent 使用检索到的信息再次推理


## 本地知识库（SimpleKnowledge）



### 快速开始

```java
// 1. 创建知识库
EmbeddingModel embeddingModel = DashScopeTextEmbedding.builder()
    .apiKey(System.getenv("DASHSCOPE_API_KEY"))
    .modelName("text-embedding-v3")
    .dimensions(1024)
    .build();

Knowledge knowledge = SimpleKnowledge.builder()
    .embeddingModel(embeddingModel)
    .embeddingStore(InMemoryStore.builder().dimensions(1024).build())
    .build();

// 2. 添加文档
TextReader reader = new TextReader(512, SplitStrategy.PARAGRAPH, 50);
List<Document> docs = reader.read(ReaderInput.fromString("文本内容...")).block();
knowledge.addDocuments(docs).block();

// 3. 检索
List<Document> results = knowledge.retrieve("查询内容",
    RetrieveConfig.builder().limit(3).scoreThreshold(0.5).build()).block();
```

### Reader 配置
AgentScope 为 SimpleKnowledge 提供了多种内置 Reader：

分割策略：`CHARACTER`、`PARAGRAPH`、`SENTENCE`、`TOKEN`

```java
// 文本
new TextReader(512, SplitStrategy.PARAGRAPH, 50);

// PDF
new PDFReader(512, SplitStrategy.PARAGRAPH, 50);

// Word（支持图片和表格）
new WordReader(512, SplitStrategy.PARAGRAPH, 50, true, true, TableFormat.MARKDOWN);

// 图像（需配合多模态嵌入模型）
new ImageReader(false);
```

### 向量存储

```java
// 内存存储（开发测试）
InMemoryStore.builder().dimensions(1024).build();

// Qdrant（生产环境）
QdrantStore.builder()
    .location("localhost:6334")
    .collectionName("my_collection")
    .dimensions(1024)
    .build();
```


## 云托管知识库（Bailian）

阿里云百炼知识库，支持 reranking、查询重写、多轮对话。通过 [百炼控制台](https://bailian.console.aliyun.com/) 管理文档。

### 快速开始

```java
// 创建知识库
BailianConfig config = BailianConfig.builder()
    .accessKeyId(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"))
    .accessKeySecret(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"))
    .workspaceId("llm-xxx")
    .indexId("mymxbdxxxx")
    .build();

BailianKnowledge knowledge = BailianKnowledge.builder().config(config).build();

// 检索
List<Document> results = knowledge.retrieve("查询内容",
    RetrieveConfig.builder().limit(5).scoreThreshold(0.3).build()).block();
```

### 高级配置

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

### 多轮对话检索

```java
RetrieveConfig config = RetrieveConfig.builder()
    .limit(5).scoreThreshold(0.3)
    .conversationHistory(conversationHistory)  // 自动重写查询
    .build();
```

### 完整配置示例

```java
BailianConfig config = BailianConfig.builder()
    // === 连接配置（必填）===
    .accessKeyId(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"))
    .accessKeySecret(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"))
    .workspaceId("llm-xxx")                    // 百炼工作空间 ID
    .indexId("mymxbdxxxx")                     // 知识库索引 ID
    
    // === 端点配置（可选）===
    .endpoint("bailian.cn-beijing.aliyuncs.com")  // 默认值
    // 其他可用端点：
    // - bailian.cn-shanghai-finance-1.aliyuncs.com (金融云)
    // - bailian-vpc.cn-beijing.aliyuncs.com (VPC)
    
    // === 检索配置（可选）===
    .denseSimilarityTopK(100)                  // 向量检索 Top K，范围 0-100，默认 100
    .sparseSimilarityTopK(100)                 // 关键词检索 Top K，范围 0-100，默认 100
    // 注意：denseSimilarityTopK + sparseSimilarityTopK <= 200
    
    // === 重排序配置（可选）===
    .enableReranking(true)                     // 启用重排序，默认 true
    .rerankConfig(RerankConfig.builder()
        .modelName("gte-rerank-hybrid")        // 重排序模型
        .rerankMinScore(0.3f)                  // 最小分数阈值
        .rerankTopN(5)                         // 返回结果数量
        .build())
    
    // === 查询重写配置（可选，用于多轮对话）===
    .enableRewrite(true)                       // 启用查询重写，默认 false
    .rewriteConfig(RewriteConfig.builder()
        .modelName("conv-rewrite-qwen-1.8b")   // 重写模型
        .build())
    
    // === 其他配置（可选）===
    .searchFilters(List.of(Map.of("tag", "value")))  // 搜索过滤条件
    .saveRetrieverHistory(false)               // 保存检索历史，默认 false
    
    .build();
```

## Dify 知识库集成

支持云服务和自托管，提供关键词、语义、混合、全文四种检索模式。通过 [Dify 控制台](https://cloud.dify.ai) 管理文档。

### 快速开始

```java
DifyRAGConfig config = DifyRAGConfig.builder()
    .apiKey(System.getenv("DIFY_RAG_API_KEY"))
    .datasetId("your-dataset-id")
    .retrievalMode(RetrievalMode.HYBRID_SEARCH)
    .topK(10).scoreThreshold(0.5)
    .build();

DifyKnowledge knowledge = DifyKnowledge.builder().config(config).build();

List<Document> results = knowledge.retrieve("查询内容",
    RetrieveConfig.builder().limit(5).build()).block();
```

### 检索模式

```java
.retrievalMode(RetrievalMode.KEYWORD)         // 关键词搜索
.retrievalMode(RetrievalMode.SEMANTIC_SEARCH) // 语义搜索
.retrievalMode(RetrievalMode.HYBRID_SEARCH)   // 混合搜索（推荐）
.retrievalMode(RetrievalMode.FULLTEXT)        // 全文搜索
```

### 高级配置

```java
DifyRAGConfig config = DifyRAGConfig.builder()
    .apiKey(apiKey).datasetId(datasetId)
    .retrievalMode(RetrievalMode.HYBRID_SEARCH)
    .weights(0.6)  // 混合搜索语义权重
    // Reranking
    .enableRerank(true)
    .rerankConfig(RerankConfig.builder()
        .providerName("cohere").modelName("rerank-english-v2.0").build())
    // 元数据过滤
    .metadataFilter(MetadataFilter.builder()
        .logicalOperator("AND")
        .addCondition(MetadataFilterCondition.builder()
            .name("category").comparisonOperator("=").value("AI").build())
        .build())
    .build();
```

### 完整配置示例

```java
DifyRAGConfig config = DifyRAGConfig.builder()
    // === 连接配置（必填）===
    .apiKey(System.getenv("DIFY_RAG_API_KEY"))  // Dataset API Key
    .datasetId("your-dataset-uuid")             // 数据集 ID（UUID 格式）
    
    // === 端点配置（可选）===
    .apiBaseUrl("https://api.dify.ai/v1")       // Dify Cloud（默认）
    // .apiBaseUrl("https://your-dify.com/v1")  // 自托管实例
    
    // === 检索配置（可选）===
    .retrievalMode(RetrievalMode.HYBRID_SEARCH) // 检索模式，默认 HYBRID_SEARCH
    // 可选模式：KEYWORD（关键词）、SEMANTIC_SEARCH（语义）、HYBRID_SEARCH（混合）、FULLTEXT（全文）
    .topK(10)                                   // 检索返回数量，范围 1-100，默认 10
    .scoreThreshold(0.5)                        // 相似度阈值，范围 0.0-1.0，默认 0.0
    .weights(0.6)                               // 混合搜索语义权重，范围 0.0-1.0
    
    // === 重排序配置（可选）===
    .enableRerank(true)                         // 启用重排序，默认 false
    .rerankConfig(RerankConfig.builder()
        .providerName("cohere")                 // Rerank 模型提供商
        .modelName("rerank-english-v2.0")       // Rerank 模型名称
        .topN(5)                                // 重排序后返回数量
        .build())
    
    // === 元数据过滤（可选）===
    .metadataFilter(MetadataFilter.builder()
        .logicalOperator("AND")                 // 逻辑运算符：AND 或 OR
        .addCondition(MetadataFilterCondition.builder()
            .name("category")                   // 元数据字段名
            .comparisonOperator("=")            // 比较运算符
            .value("documentation")             // 过滤值
            .build())
        .build())
    
    // === HTTP 配置（可选）===
    .connectTimeout(Duration.ofSeconds(30))     // 连接超时，默认 30s
    .readTimeout(Duration.ofSeconds(60))        // 读取超时，默认 60s
    .maxRetries(3)                              // 最大重试次数，默认 3
    .addCustomHeader("X-Custom-Header", "value") // 自定义请求头
    
    .build();
```

## RAGFlow 知识库集成

开源 RAG 引擎，支持 Docker 部署、强大 OCR、知识图谱、多数据集检索。

### 部署

```bash
git clone https://github.com/infiniflow/ragflow.git && cd ragflow
docker compose up -d  
```

### 快速开始

```java
RAGFlowConfig config = RAGFlowConfig.builder()
    .apiKey("ragflow-your-api-key")             // 必填：API Key
    .baseUrl("http://address")           // 必填：RAGFlow 服务地址
    .addDatasetId("dataset-id")                 // 必填：至少设置 dataset_ids 或 document_ids
    .topK(10).similarityThreshold(0.3)
    .build();

RAGFlowKnowledge knowledge = RAGFlowKnowledge.builder().config(config).build();

List<Document> results = knowledge.retrieve("查询内容",
    RetrieveConfig.builder().limit(5).build()).block();
```

### 多数据集和文档过滤

> **注意**：`dataset_ids` 和 `document_ids` **至少要设置一个**。如果只设置 `document_ids`，确保所有文档使用相同的嵌入模型。

```java
// 方式1：只设置数据集（搜索整个数据集）
RAGFlowConfig config1 = RAGFlowConfig.builder()
    .apiKey("ragflow-your-api-key")
    .baseUrl("http://localhost:9380")
    .addDatasetId("dataset-1")
    .addDatasetId("dataset-2")
    .build();

// 方式2：只设置文档（直接搜索指定文档，需使用相同嵌入模型）
RAGFlowConfig config2 = RAGFlowConfig.builder()
    .apiKey("ragflow-your-api-key")
    .baseUrl("http://localhost:9380")
    .addDocumentId("doc-id-1")
    .addDocumentId("doc-id-2")
    .build();

// 方式3：同时设置（在数据集中搜索指定文档）
RAGFlowConfig config3 = RAGFlowConfig.builder()
    .apiKey("ragflow-your-api-key")
    .baseUrl("http://localhost:9380")
    .addDatasetId("dataset-1")
    .addDocumentId("doc-id-1")  // 限定在该数据集中的指定文档
    .build();
```

### 元数据筛选

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

### 完整配置示例

```java
RAGFlowConfig config = RAGFlowConfig.builder()
    // === 连接配置（必填）===
    .apiKey("ragflow-your-api-key")             // RAGFlow API Key
    .baseUrl("http://address")           // RAGFlow 服务地址（必填）


    // === 数据集/文档配置（至少设置一个）===
    .addDatasetId("datasetId1")
    .addDatasetId("datasetId2")  // 支持多数据集
    // 或批量设置：.datasetIds(List.of("id1", "id2"))
    
    // === 文档过滤（可选，限定检索范围）===
    .addDocumentId("documentId1")
    .addDocumentId("documentId2")
    // 或批量设置：.documentIds(List.of("doc1", "doc2"))
    // 注意：如果只设置 document_ids，确保所有文档使用相同的嵌入模型
    
    // === 检索配置（可选）===
    .topK(1024)                                 // 参与向量计算的 chunk 数量，默认 1024
    .similarityThreshold(0.2)                   // 相似度阈值，范围 0.0-1.0，默认 0.2
    .vectorSimilarityWeight(0.3)                // 向量相似度权重，范围 0.0-1.0，默认 0.3
                                                // (1 - weight) 为词项相似度权重
    //=== 分页参数 ===
    .page(1)                                    // 页码，默认 1
    .pageSize(30)                               // 每页数量，默认 30
    
    // === 高级检索功能（可选）===
    .useKg(false)                               // 知识图谱多跳查询，默认 false
    .tocEnhance(false)                          // 目录增强检索，默认 false
    .rerankId(1)                                // 重排序模型 ID
    .keyword(false)                             // 关键词匹配，默认 false
    .highlight(false)                           // 高亮匹配结果，默认 false
    .addCrossLanguage("en")                     // 添加目标语言
    // 或批量设置：.crossLanguages(List.of("en", "zh", "ja"))
    
    // === 元数据过滤（可选）===
    .metadataCondition(Map.of(
        "logic", "and",                         // 逻辑运算符：and 或 or
        "conditions", List.of(
            Map.of(
                "name", "author",               // 元数据字段名
                "comparison_operator", "=",     // 比较运算符
                "value", "Toby"                 // 过滤值
            ),
            Map.of(
                "name", "date",
                "comparison_operator", ">=",
                "value", "2024-01-01"
            )
        )
    ))
    
    // === HTTP 配置（可选）===
    .timeout(Duration.ofSeconds(30))            // HTTP 超时，默认 30s
    .maxRetries(3)                              // 最大重试次数，默认 3
    .addCustomHeader("X-Custom-Header", "value") // 自定义请求头
    
    .build();
```

**支持的比较操作符：**
- `=` - 等于
- `≠`  - 不等于
- `>`, `<`, `≥`, `≤` - 数值比较
- `contains` - 包含
- `not contains` - 不包含
- `start with` - 以...开头
- `empty` - 为空
- `not empty` - 不为空




## 自定义 RAG 组件

AgentScope 鼓励自定义 RAG 组件。你可以扩展以下基类：

| 基类 | 描述 | 抽象方法 |
|------|------|----------|
| `Reader` | 文档读取器基类 | `read()`, `getSupportedFormats()` |
| `VDBStoreBase` | 向量存储基类 | `add()`, `search()` |
| `Knowledge` | 知识库实现基类 | `addDocuments()`, `retrieve()` |

### 自定义 Reader 示例

```java
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


## 完整示例

- **本地知识库示例**: [RAGExample.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/advanced/src/main/java/io/agentscope/examples/advanced/RAGExample.java)
- **百炼知识库示例**: [BailianRAGExample.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/advanced/src/main/java/io/agentscope/examples/advanced/BailianRAGExample.java)
- **Dify 知识库示例**: [DifyRAGExample.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/DifyRAGExample.java)
- **RAGFlow 知识库示例**: [RAGFlowRAGExample.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/quickstart/src/main/java/io/agentscope/examples/quickstart/RAGFlowRAGExample.java)
