---
title: "配置运行模式、预算与决策观察"
---

`JevExecution.Options` 为工具选择、防护、模型路由等能力统一配置运行模式、总预算和观察器。你可以先记录建议，再按用途决定是否让判断影响 Agent 流程。

## 示例场景：先观察退款防护，再开启实际拦截

你准备给客服 Agent 增加退款前检查，但尚不知道判断会不会误拦正常请求。直接接管可能影响业务，完全关闭又无法积累样本。

SHADOW 会实际发起判断并记录建议，同时把原输入继续交给后续流程。这样可比较“建议拦截”与业务预期；验证和校准后，再把退款防护单独切到 ENFORCE。

## 1. 为这项用途设置预算和版本

```java
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.example.JevAutoModeMiddleware;
import java.time.Duration;

var options = new JevExecution.Options(
    JevExecution.Mode.SHADOW, Duration.ofSeconds(2), "refund-guard-v1",
    (ctx, record) -> System.out.println(record));
var guard = JevAutoModeMiddleware.builder(client)
    .execution(options)
    .guardedTool("refund")
    .build();
```

`client` 按[客户端接入](/v2/zh/jev/guides/client)创建，`guard` 注册到 `HarnessAgent.builder().middleware(guard)`。版本用于区分不同配置的观察结果；改了评审用途或阈值时不要沿用无法区分的版本标识。

2 秒覆盖一次逻辑判断的所有批次、客户端重试和退避，不包含之后 Agent 执行工具的时间。客户端单次 HTTP 超时不能代替这个总预算。

## 2. 区分建议与实际动作

| 模式 | 是否发起判断 | 退款例子中的实际行为 |
|---|---|---|
| OFF | 不调用 | 沿原流程，可能不产生观察记录 |
| SHADOW | 需要判断时调用 | 即使建议拒绝，退款仍进入原执行链 |
| ENFORCE | 需要判断时调用 | 按防护结果阻止或放行受保护调用 |

环境变量中存在密钥不会自动启用能力。切换模式需要应用明确更新配置并重新装配中间件；不同用途可以采用不同模式，例如工具选择保持 SHADOW，退款防护单独接管。

## 3. 记录足以比较的决策元数据

观察器收到当前 RuntimeContext 和 Record：

| 字段 | 如何使用 |
|---|---|
| `purpose / version / mode` | 区分工具选择、防护等用途及当时配置 |
| `status / reason` | 区分明确判断、不确定、调用失败和取消 |
| `elapsed` | 查看这次检查耗时，不误当成整个 Agent 时间 |
| `recommendation` | 记录推荐模型、拒绝调用 ID 等建议，与实际执行记录对照 |

Record 不包含消息全文、工具参数、密钥或原始错误。宿主可以用 RuntimeContext 关联 run/session；观察器应快速返回，数据库写入需自行排队。观察器抛出的 RuntimeException 不会阻断 Agent，但同步阻塞不会自动转成后台任务。

## 运行与验证

按[案例构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP`：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevHarnessExample
```

这个离线例子对相同的工具选择、防护与路由输入依次运行三种模式。观察输出可以看到 SHADOW 的拒绝建议仍进入模拟派发，ENFORCE 才产生拒绝工具结果。[完整源码](/examples/jev/source/JevHarnessExample.java.txt)

需要真实模型对照时，使用[退款防护用例](/v2/zh/jev/guides/tool-guard-api#退款场景对比测试)。预设概率的离线结果不能作为模型准确率或性能证据。

## 按用途理解失败策略

| 用途 | ENFORCE 判断失败时 |
|---|---|
| 工具选择 | 保留原候选工具 |
| 模型路由 | 使用原模型，不重跑 Agent |
| 执行前防护 | 拒绝受保护调用，其他工具沿原流程 |
| 内容及质量审核 | 不发布未通过的文本 |

公共状态包括 DECIDED、INCONCLUSIVE、ERROR、CANCELLED、SKIPPED；TIMEOUT 和 CALL_OR_RESPONSE_ERROR 是故障原因。取消只通知观察器，不伪造一个回退结果继续派发；底层阻塞 HTTP 是否立即终止取决于传输实现。

轨迹事后评估与长任务监督只允许 OFF/SHADOW，不能撤销已经发生的行为。直接调用 Judge、记忆评审等应用 API 会立即进入各自评审流程，不隐式读取全局模式；应用的用途开关应包围完整工作流。
