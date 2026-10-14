---
title: "在工具执行前检查受保护操作"
---

对退款、发送消息等受保护工具，使用 `JevAutoModeMiddleware` 在派发前判断操作是否适合当前任务。语义允许之后仍需通过 PermissionEngine；拒绝时返回与原调用配对的工具结果。

## 示例场景：先确认订单状态，再退款

假设你在构建一个客服 Agent，它有两个工具：

- `query_order`：查询订单和发货状态。
- `refund`：向支付系统发起退款。

用户说：“请先查一下订单 A1001，如果还没发货，就帮我退款。”模型可能直接生成 `refund(orderId="A1001")`，跳过查询。此时工具名和参数都合法，用户也具备退款权限，但对话中还没有证据表明退款条件成立。

JEV 在这里增加一次结合上下文的判断：**根据当前对话和这次工具调用的参数，这个操作是否适合自动执行？** 判断未通过时，`refund` 不会被派发，Agent 会收到拒绝结果，可以据此补充查询或请求复核。

中间件会自动把当前会话消息、受保护工具的名称和参数交给 JEV。你不需要手工拼装判断请求，但必须让前序查询结果进入 Agent 上下文。JEV 不会自行查询订单；退款资格、金额上限和幂等仍由业务代码校验。

## 1. 为退款工具配置检查

先创建客户端，并把 `refund` 加入受保护工具集合。下面使用 SHADOW 模式观察判断，暂不改变原有执行流程。

```java
import io.agentscope.extensions.judge.jev.JevClient;
import io.agentscope.extensions.judge.jev.JevExecution;
import io.agentscope.extensions.judge.jev.example.JevAutoModeMiddleware;
import java.time.Duration;
import java.util.Set;

var client = JevClient.builder().build();
var options = new JevExecution.Options(
    JevExecution.Mode.SHADOW,
    Duration.ofSeconds(2),
    "refund-guard-v1",
    (ctx, record) -> System.out.println(record));

var guard = JevAutoModeMiddleware.builder(client)
    .execution(options)
    .guardedTools(Set.of("refund"))
    .safetyThreshold(0.8)
    .denyMessage("退款操作未通过自动执行检查，请补充订单状态或请求人工复核。")
    .build();
```

客户端从环境变量读取凭据，依赖及密钥配置见[客户端接入](/v2/zh/jev/guides/client)。这里几个配置项分别对应退款场景中的具体行为：

| 配置 | 在这个例子中的作用 |
|---|---|
| `guardedTools(Set.of("refund"))` | 只检查退款调用；`query_order` 沿原流程执行。名称必须与 Toolkit 中注册的工具名一致 |
| `safetyThreshold(0.8)` | 接管时，只有安全概率不低于 0.8 的退款调用才进入后续权限与执行链；该值用于演示，应按业务样本校准 |
| `denyMessage(...)` | 未通过时返回给 Agent 的工具结果文本，引导它补充信息或请求复核；这不是自动发送给客户的回复 |
| `Duration.ofSeconds(2)` | 本次检查的总预算，包括客户端重试与退避 |
| `"refund-guard-v1"` 与观察器 | 标记这套配置，并记录建议，便于检查哪些退款调用会被拦截 |

如果还要检查对外发送消息，可以改为 `.guardedTools(Set.of("refund", "send_message"))`。工具进入这个集合后才会接受检查；集合并不授予工具执行权限。

## 2. 接入你的 Harness

将 `guard` 注册到已有 Agent。`model` 是生成模型，`toolkit` 已注册 `query_order` 和 `refund`，`permissionContext` 是应用现有的权限配置。

```java
import io.agentscope.harness.agent.HarnessAgent;

var agent = HarnessAgent.builder()
    .name("order-support")
    .model(model)
    .toolkit(toolkit)
    .permissionContext(permissionContext)
    .middleware(guard)
    .build();
```

之后仍按原方式调用 Agent。模型提出工具调用时，中间件会在执行前自动检查，你不需要额外调用 `guard`。正常的 Harness 调用链负责提供会话状态。

对于“查订单后退款”的请求，`query_order` 不在受保护集合中，可以沿原权限链执行；随后提出的 `refund` 才会接受 JEV 检查。这样，JEV 可以结合查询结果和用户的条件性要求判断退款操作。

## 3. 从观察切换到实际拦截

SHADOW 用来查看判断是否符合你的业务预期，**不会阻止退款**。如果希望真正拦截，需要在构造 `options` 时将模式改为 `JevExecution.Mode.ENFORCE`，并用新的配置重新构建中间件和 Agent。

以阈值 0.8 为例，假设两次判断分别返回以下概率：

| 模型提出的调用 | JEV 返回值（仅示意） | SHADOW | ENFORCE |
|---|---|---|---|
| 尚未查询订单，就提出退款 | 安全概率 0.15 | 记录拒绝建议，调用仍进入原执行链 | 不派发退款，返回 DENIED 工具结果 |
| 查询结果已提供，再提出退款 | 安全概率 0.93 | 记录允许建议，调用进入原执行链 | 通过语义检查，继续由 PermissionEngine 决定是否执行 |

这些概率用来说明处理方式，不代表模型一定会给出同样判断。即使 JEV 允许，业务校验仍可能拒绝退款；真正执行成功也必须以退款工具返回的业务结果为准。

如果一次提出多个工具调用，中间件分别判断受保护项。被拒绝的调用会得到与原 `toolCallId` 对应的工具结果，其余调用保持原顺序进入后续流程；中间件不会自动重试退款工具。

## 运行离线示例

[JevHarnessExample](/examples/jev/source/JevHarnessExample.java.txt)使用合成判断展示 OFF、SHADOW、ENFORCE 下的行为。按[案例构建步骤](/v2/zh/jev/guides/agent-integration-example#构建一次运行多个案例)设置 `JEV_CP` 后执行：

```bash
java -cp "$JEV_CP" io.agentscope.examples.jev.JevHarnessExample
```

示例给退款调用设置低安全概率：SHADOW 仍进入模拟派发，ENFORCE 只生成拒绝事件，不进入模拟派发。它不连接模型，也不执行真实退款。

## 异常与人工确认

- **检查失败**：ENFORCE 下，缺少会话状态、缺答案、非法概率、超时或后端失败都会拒绝受保护调用；SHADOW 保留原流程，OFF 不发起判断。取消检查后不继续派发。
- **人工已确认**：已确认的 ALLOWED 调用沿用原确认链。Service 通过 `JevConfirmedCalls` 绑定工具名和参数指纹；确认后参数变化时，ENFORCE 会以 `CONFIRMATION_CHANGED` 拒绝，必须重新确认。
- **中间件顺序**：会修改工具参数的中间件应在此检查之前完成修改，避免执行的参数与检查时不同。

运行模式和观察记录的完整契约见[运行配置](/v2/zh/jev/guides/harness-runtime)。使用 AgentScope Service 时，也可以通过 `agentOverrides.jev.guard` 装配同一能力，见[Service 配置](/v2/zh/jev/guides/service-api)。

## 退款场景对比测试

2026-09-26，使用同一组 120 个合成会话快照、128 次受保护调用，对比当前中间件中的 JEV 与 Qwen 判定。保持本页阈值 `0.8`、总预算 2 秒，每个后端运行一轮；实际返回模型为 `jev-1.13.0` 与 `qwen3.8-max`。所有退款均为本地模拟。

| 指标 | JEV | Qwen |
|---|---:|---:|
| 有效判断响应 / 请求 | 120 / 120 | 111 / 120 |
| 正确判断 / 全部受保护调用（含失败） | 60 / 128（46.88%） | 118 / 128（92.19%） |
| 实际错误派发 / 应拦截调用 | 0 / 60 | 1 / 60 |
| 正常退款受阻 / 应放行调用 | 68 / 68 | 6 / 68 |
| 检查耗时 P50 / P95 | 475 / 553 ms | 980 / 1,346 ms |

当前通用安全问题与阈值组合下，JEV 判断较快，但过于保守，全部正常退款也被拦截。Qwen 在有效判断中的准确率为 99.16%，另有 9 次响应未通过格式校验；失败兜底造成了上表中的 6 次正常退款受阻。两者均无超时。检查耗时不代表完整 Agent 任务耗时，这组结果也不代表真实支付成功率；启用接管前仍需按业务场景校准。

完整数据集、标注规则、运行命令、离线行为测试及 60 秒预算对照均放在 `agentscope-examples/jev/benchmarks/tool-guard`。可[查看测试方法](/examples/jev/tool-guard/README.md.txt)或[下载完整用例与原始结果](/examples/jev/tool-guard-benchmark.zip)。
