---
title: "RAG过滤重排与引用复核API"
---

# RAG过滤重排与引用复核API

状态：已实现应用组件`JevRag`。初始化和可运行main见[应用API](application-api.md)。

```java
var rag = new JevRag(selector, judge);
rag.retrieve(ctx, query, documents, doc -> acl.canRead(doc.id()), 5)
    .flatMap(result -> rag.verify(query, answer, result.evidence()));
```

Document包含id、text、version；query、answer由应用提供，acl是宿主访问控制。ACL先过滤，私有候选不会送入JEV；可见文档ID不得重复。筛选与排序采用独立相关性概率，保留通过项按得分排序并限制数量。

Retrieval区分有效证据、unscored与故障状态；uncertain不会当成不相关。故障时没有证据，全部可见ID列入unscored；无证据应弃权，不能生成无来源答案。空证据时verify确定性返回FAIL，不发起模型请求；有证据时verify检验回答声明是否有给定材料支持，具体引用格式和链接存在性仍由应用确定性校验。

测试确认私有文档不出现在请求、明确无关与不确定分开、非法答案不产生证据。本应用API不接管旧Knowledge接口；现有实现可使用新增[Knowledge兼容适配与检索工具](knowledge-adapter-api.md)接入。调用方应在拿到证据后生成答案，再执行verify，上例只展示接口组合。

源码：[JevRag](../../../agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/application/JevRag.java)。

## 四项分类与独立重排

本页保留原有相关性筛选 API。需要区分提示注入、前提冲突、相关性和答案证据，并接入只读工具、Service 和发布前审核时，使用[检索证据管线](/v2/zh/jev/guides/evidence-pipeline-api)。两者的判断定义和模式契约分别见各自文档。
