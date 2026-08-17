# Bailian Knowledge

`agentscope-extensions-rag-bailian` 接入阿里云百炼知识库，所有 embedding、索引、检索都由百炼托管。Agent 这边只负责把 query 抛过去、把文档拿回来。

## 何时使用

- 文档已经在百炼控制台上传/解析完成。
- 想要企业级特性：rerank、过滤、结构化/非结构化/图片三类知识库。
- 不想自维护向量库。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-rag-bailian</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 快速上手

```java
import io.agentscope.core.rag.integration.bailian.BailianConfig;
import io.agentscope.core.rag.integration.bailian.BailianKnowledge;
import io.agentscope.core.rag.model.RetrieveConfig;

BailianConfig config = BailianConfig.builder()
    .accessKeyId(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_ID"))
    .accessKeySecret(System.getenv("ALIBABA_CLOUD_ACCESS_KEY_SECRET"))
    .workspaceId("llm-xxxxxx")
    .indexId("kb-xxxxxx")
    .build();

BailianKnowledge knowledge = BailianKnowledge.builder()
    .config(config)
    .build();

List<Document> hits = knowledge.retrieve(
    "如何申请发票？",
    RetrieveConfig.builder().limit(5).scoreThreshold(0.5).build()
).block();
```

## 与 Agent 集成

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(chatModel)
    .knowledge(knowledge)
    .ragMode(RAGMode.AGENTIC)
    .build();
```

或者通过工具暴露：

```java
KnowledgeRetrievalTools tools = new KnowledgeRetrievalTools(knowledge);
Toolkit toolkit = new Toolkit();
toolkit.registerObject(tools);
```

## rerank / rewrite 配置

`BailianConfig.builder()` 可选传入 `rerankConfig(...)` 与 `rewriteConfig(...)`：

```java
BailianConfig config = BailianConfig.builder()
    .accessKeyId(ak).accessKeySecret(sk)
    .workspaceId("llm-xxx").indexId("kb-xxx")
    .rerankConfig(RerankConfig.builder().enable(true).topN(5).build())
    .rewriteConfig(RewriteConfig.builder().enable(true).build())
    .build();
```

启用后百炼侧会在原始召回上再做一遍重排或 query 改写，提升相关性，但延迟和费用也会上升，按需打开。

## 仅支持检索

`BailianKnowledge.addDocuments(...)` 不可用——文档管理请通过百炼控制台或百炼平台 SDK 完成。这是和 Dify、HayStack、RAGFlow 一致的设计：第三方 RAG 平台保留索引能力，Java 侧只做读取。

## 配置参数

| 配置 | 说明 |
| --- | --- |
| `accessKeyId / accessKeySecret` | 阿里云访问凭证（必填） |
| `workspaceId` | 百炼业务空间 ID（必填） |
| `indexId` | 知识库索引 ID（必填） |
| `rerankConfig` | rerank 开关与参数 |
| `rewriteConfig` | query rewrite 开关与参数 |
