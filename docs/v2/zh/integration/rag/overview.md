# RAG 知识库

`io.agentscope.core.rag.Knowledge` 是 AgentScope 用于"接入外部知识库"的接口。Agent 在推理时通过它检索文档片段，再交给模型用于生成。`agentscope-extensions-*` 仓库下提供了多种实现：

| 扩展 | 类型 | 适合场景 |
| --- | --- | --- |
| [Simple](simple.md) | 自建：embedding + 向量库 | 对接自家向量库（PgVector / Milvus / Qdrant / Elasticsearch / 内存） |
| [Bailian](bailian.md) | 阿里云百炼知识库 | 用百炼托管的企业级知识库 |
| [Dify](dify.md) | Dify 数据集 | 已经在 Dify 上维护知识库的场景 |
| [HayStack](haystack.md) | 自托管 HayStack RAG | 已经基于 HayStack 搭建索引流水线 |
| [RAGFlow](ragflow.md) | RAGFlow 服务 | 需要 OCR、知识图谱增强的复杂文档场景 |

## 接入方式都是同一套

```java
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .knowledge(knowledge)        // 任选一种 Knowledge 实现
    .ragMode(RAGMode.AGENTIC)    // 或 STATIC、NONE
    .build();
```

也可以包装成工具供 Agent 自主选择是否检索：

```java
KnowledgeRetrievalTools tools = new KnowledgeRetrievalTools(knowledge);
Toolkit toolkit = new Toolkit();
toolkit.registerObject(tools);
```

## 选型建议

| 需求 | 建议 |
| --- | --- |
| 自己掌控全部链路（embedding + 向量库） | **Simple** |
| 阿里云生态、企业级托管 | **Bailian** |
| 团队已经在用 Dify 编排 | **Dify** |
| 需要复杂 ETL（PDF 表格、图片 OCR、知识图谱） | **RAGFlow** |
| 已基于 HayStack 落地 RAG 流水线 | **HayStack** |

> 除 Simple 外，其余实现都**只负责检索**（retrieve），文档导入/更新请走对应平台的控制台或服务侧 API；这样设计是为了让多个 Knowledge 在使用侧完全可替换。
