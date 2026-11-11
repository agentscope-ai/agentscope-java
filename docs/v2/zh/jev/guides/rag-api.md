---
title: "筛选相关文档并检查回答依据"
---

`JevRag` 从已授权文档中筛选和排序相关证据，再检查回答中的事实是否得到这些证据支持。适合由应用自行管理检索、生成和发布步骤的知识问答。

## 示例场景：回答退款规则，不能混入私有补偿政策

客户问“没发货可以退款吗？”。检索返回公开退款条款、物流查询说明和内部补偿政策。召回成功不代表这些材料都可以交给模型，也不代表回答可以省略条款中的“需审核”。

宿主先过滤无权访问的文档，JEV 再从可见材料中筛选相关证据。答案生成后，再单独检查它是否仍保留证据支持的条件。

## 1. 提供带版本的文档与访问控制

```java
import io.agentscope.extensions.judge.jev.application.JevRag;
import java.util.List;

var documents = List.of(
    new JevRag.Document("policy", "未发货订单可以申请退款，需审核", "v1"),
    new JevRag.Document("delivery", "物流轨迹查询方法", "v1"),
    new JevRag.Document("private", "内部补偿政策", "v1"));
var rag = new JevRag(selector, judge);
var query = "没发货可以退款吗？";
var pending = rag.retrieve(ctx, query, documents,
    document -> !document.id().equals("private"), 2);
```

`selector`、`judge` 使用[应用组件初始化](/v2/zh/jev/guides/application-api#初始化)。示例 predicate 模拟访问控制；生产要用当前用户的真实 ACL。过滤发生在调用 JEV 之前，私有文档不会因“很相关”而被发给判断模型。

最后的 2 表示最多保留两份明确相关文档；不是要求强行凑够数量。可见文档 ID 不得重复，版本用于宿主关联实际引用。

## 2. 有证据后再生成草稿并审核

```java
var checked = pending.flatMap(retrieval -> {
    System.out.println("检索判断：" + retrieval.status());
    System.out.println("尚未明确评分：" + retrieval.unscored());
    // 演示草稿；真实流程应先用 retrieval.evidence() 生成草稿。
    String draft = "未发货订单可以申请退款，需审核。";
    return rag.verify(query, draft, retrieval.evidence());
});
```

相关性判断使用各文档独立概率，选中项按得分排序。`unscored()` 表示尚未明确判断的候选，不能当作已知不相关。应用先检查检索状态和证据数量，再生成答案；例子用固定草稿展示后一阶段 API。

若没有有效证据，`verify` 会直接返回 FAIL 且不请求模型。应用应弃权或继续检索，不能发布一段缺少来源的答案。即使有证据，JEV PASS 也不替代引用 ID、版本、链接存在性等确定性校验。

## 运行与验证

按[案例构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP`：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevApplicationScenarios rag
```

离线夹具保留 `policy`、排除物流说明；私有材料在判断前过滤。有依据草稿预设为 PASS，空证据路径确定性 FAIL。它验证 ACL、证据选择和无证据处理，不代表真实检索召回率或答案质量。[完整源码](/examples/jev/source/JevApplicationScenarios.java.txt)

## 模式、失败与更完整的检索链

组件返回数据，不自动改变 Agent 的上下文。用 SHADOW 时宿主只记录建议；采用筛选结果需要应用明确接管。关闭或判断失败时没有有效证据，所有可见 ID 进入 `unscored`；保留状态，避免将失败误记为“确定没有相关资料”。取消沿原订阅传播。

需要分别识别提示注入、前提冲突、相关性和答案证据，以及接入只读 Agent 工具或发布前修订时，使用[检索证据管线](/v2/zh/jev/guides/evidence-pipeline-api)。
