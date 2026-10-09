---
title: "为交付监督提供可信验证证据"
---

`SupervisionEvidence` 把宿主掌握的产物版本、任务约束和验证收据交给监督器。它让“建议进入完成复核”建立在当前有效证据上，避免把 Agent 的完成声明当作测试结果。

## 示例场景：旧报告的验证通过了，新报告却还没验证

订单核查报告从 v1 更新到 v2。如果继续沿用 v1 的成功收据，模型即使认为 v2 内容完整，也不能证明当前产物已经验证。多用户、多会话环境还需要防止把其他任务的验证记录拿来使用。

宿主为每次观察读取一致快照，并把验证结果绑定到 user/session/run 和产物 revision。版本或作用域不匹配时，监督器不能给出完成复核建议。

## 1. 接入应用自己的证据读取流程

```java
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.judge.jev.supervision.JevSupervisionMiddleware;

var source = new JevSupervisionMiddleware.EvidenceSource(request ->
    evidenceRepository.readAuthorizedSnapshot(request));
var context = RuntimeContext.builder()
    .userId(userId).sessionId(sessionId)
    .put(JevSupervisionMiddleware.EvidenceSource.class, source)
    .build();
```

`evidenceRepository` 是应用的证据读取接口占位，输入 `JevSupervisionMiddleware.EvidenceRequest`，返回 `Mono<SupervisionEvidence>`。它负责 ACL、读取当前产物和核实验证记录来源，不是让模型从自然语言里猜测验证是否成功。

把 `context` 传给本次 `agent.streamEvents(messages, context)`。监督中间件注册方式见[持续监督](/v2/zh/jev/guides/supervision-api#基本-api)。证据读取与这次语义判断共用预算，读取超时也不能作为完成证据。

## 2. 将验证结果绑定到当前快照

`SupervisionEvidence` 描述 revision、repositoryStatus、diff、changedFiles、instructions、documentationRequired 和可空 Verification。字段可以表达代码变更，也可以描述报告等有版本的业务产物。

| Verification 字段 | 在报告核查中的含义 |
|---|---|
| userId / sessionId | 当前有权查看这份报告的用户及会话 |
| runId | 本次监督调用的 UUID，从 request.scope 取得 |
| revision | 实际受验证的报告版本，必须与证据版本一致 |
| source | 宿主能够验证来源的检查流程标识 |
| passed / summary | 实际检查是否成功及结果摘要 |

仅当验证成功、来源非空、作用域和版本全部匹配时，才认为当前产物已经验证。不能把旧收据的版本字段改成 v2 来“补齐”匹配；需要针对 v2 重新执行检查。

代码任务的 revision 应覆盖未提交修改，仅使用 Git HEAD 不足以标识完整当前产物。JSON 中填入 `passed=true` 本身不构成可信来源。

## 3. 消费建议，保留最终复核责任

缺少证据源、收据过期、验证失败或材料被截断时，不形成完成复核建议。应用可消费 REQUEST_VERIFICATION 或 MANUAL_REVIEW，任务仍按原流程运行。

REVIEW_COMPLETION 表示可以进入复核，不意味着监督器已替你完成发布、审批或任务状态变更。

## 运行与验证

按[案例构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP`：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevSupervisionExample
```

用例在本地记录订单工具恰好执行一次，然后由宿主提供 `order-42-v1` 的验证收据，使用当前 request.scope 中的身份与运行 UUID。预设 JEV 判断通过后输出 REVIEW_COMPLETION，`verified revision` 为 true。[完整源码](/examples/jev/source/JevSupervisionExample.java.txt)

该用例验证证据接入和运行边界；真实构建系统、产物版本生成、访问控制和跨进程恢复仍由你的应用实现。
