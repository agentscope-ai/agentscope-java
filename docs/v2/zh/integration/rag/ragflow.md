# RAGFlow Knowledge

`agentscope-extensions-rag-ragflow` 接入 [RAGFlow](https://ragflow.io/)。RAGFlow 在文档解析侧做得比较深（OCR、表格识别、知识图谱增强），适合非结构化文档比例高的场景。

## 何时使用

- 知识库以扫描件、复杂排版 PDF、含图片表格为主。
- 希望使用 RAGFlow 的 chunk 切分策略与重排能力。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-rag-ragflow</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 快速上手

```java
import io.agentscope.core.rag.integration.ragflow.RAGFlowConfig;
import io.agentscope.core.rag.integration.ragflow.RAGFlowKnowledge;
import io.agentscope.core.rag.model.RetrieveConfig;

RAGFlowConfig config = RAGFlowConfig.builder()
    .apiKey("ragflow-xxxxxxxx")
    .baseUrl("http://localhost:9380")
    .knowledgeBaseId("kb-xxxxx")
    .topK(10)
    .similarityThreshold(0.5)
    .enableRerank(true)
    .build();

RAGFlowKnowledge knowledge = RAGFlowKnowledge.builder()
    .config(config)
    .build();

List<Document> hits = knowledge.retrieve(
    "AI 是什么？",
    RetrieveConfig.builder().limit(5).build()
).block();
```

## 工作机制

底层调用 RAGFlow 的 `POST /api/v1/datasets/{dataset_id}/retrieve-chunks` 接口：

- 向量相似度搜索 + 可配置 `topK`
- 服务端 `similarityThreshold` 过滤
- 通过 `RAGFlowConfig` 中的 metadata 字段做过滤
- 可选启用 RAGFlow 的 rerank

> 注意：当前 RAGFlow 的 retrieve-chunks API 不支持把对话历史传过去做上下文感知检索；如果你需要这种能力，请自己在 query 拼接前置指令。

## 文档管理走 RAGFlow 控制台

`addDocuments(...)` 同样不可用——上传与索引请到 RAGFlow 控制台或 RAGFlow 自身的 API 完成。

## 关键参数

| 字段 | 说明 |
| --- | --- |
| `apiKey` | RAGFlow API Key（必填） |
| `baseUrl` | RAGFlow 服务地址（必填） |
| `knowledgeBaseId` | 数据集 / 知识库 ID（必填） |
| `topK` | 服务端 TopK |
| `similarityThreshold` | 服务端最低相似度阈值 |
| `enableRerank` | 是否启用 rerank |
