---
title: "输入检查、输出审核与有限修订API"
---


状态：已实现`JevDraftPipeline`。初始化与离线main见[应用API](/v2/zh/jev/guides/application-api)。

```java
var pipeline = new JevDraftPipeline(judge, 16000, 2, Duration.ofSeconds(15));
pipeline.run(request, evidence, inputDefinition, outputDefinition,
    () -> generateDraft(), revision -> reviseDraft(revision))
    .subscribe(outcome -> {
        if (outcome.publishedText() != null) publish(outcome.publishedText());
    });
```

generateDraft与reviseDraft返回Flux字符串，均为应用提供的纯草稿生成函数；不能传入会重跑写工具的Agent调用。inputDefinition、outputDefinition为JevJudge.Definition，evidence为已授权业务证据；publish是业务发布函数。

先检查输入；只有PASS才生成。完整缓冲草稿后审核，只有PASS返回publishedText；正常审核拒绝的内容保留在draftText供授权复核，不可直接发布。FAIL允许有限修订；ERROR/INCONCLUSIVE不修订。相同草稿停止，最多10次修订，构造时指定业务上限。maxChars限制缓冲大小；整体预算包含输入审核、生成、输出审核和全部修订。

不支持边审核边对外流式发布；想继续保留实时工具事件的宿主应只把最终回答文本接入此组件。超长、超时、生成异常返回不可发布结果；错误时revisions为0，不用它统计失败前实际调用次数。调用方取消向生成链传播。

测试覆盖输入拒绝不生成、先审核后返回、一次修订、相同草稿停止、缓冲上限与总超时。本API仍由草稿应用明确调用；现已新增[Agent响应中间件](/v2/zh/jev/guides/answer-refinement-api)，可在Agent与Service模型调用链中完成审核和修订。

源码：[JevDraftPipeline](/examples/jev/source/agentscope-extensions/agentscope-extensions-judge/agentscope-extensions-jev/src/main/java/io/agentscope/extensions/judge/jev/application/JevDraftPipeline.java.txt)。
