---
title: 上下文构建
en_link: /v2/en/docs/harness/context
description: 了解 Harness 如何组织 System、会话与任务状态，接入动态业务信息，配置预算并排查构建失败
---

HarnessAgent 默认在模型调用前统一构建上下文。通常只需设置 `sysPrompt`、
工作区和工具，不需要自己拼接消息或编写 XML。普通 ReActAgent 不自动安装这套 Harness 策略。

本页聚焦“实际发给模型什么”。会话状态保存恢复、RuntimeContext 与并发访问规则，
请先阅读 [上下文与 AgentState](/v2/zh/docs/building-blocks/context)。
本页的 taskContext 是投影配置，不替代状态维护和持久化。

## 模型会看到什么

最终顺序是：**一条 System → 对话历史 → 临时状态 → 参考材料**。
工具 Schema 作为模型请求的独立部分传入，也计入预算。

| 内容 | 来源 | 位置 |
| --- | --- | --- |
| Agent 指令 | `sysPrompt()` | System，保持用户写法 |
| 项目规则 | AGENTS.md | System 的 `project_rules` |
| 通用上下文约定 | 框架内置资源 | System 的 `instruction_rules` |
| 工作区使用规则、环境、模式 | 框架与当前配置 | System 的 `working_principles`、`environment`、`mode_rules` |
| 稳定业务指令 | `instruction()` | System 的 `business_instruction` |
| 当前 Todo | 会话的 TaskContextState | 最新完整工具回执，或临时 `TASK_STATE`，不重复展示 |
| 需求、验证摘要 | 调用方维护的任务状态 | 显式开启后进入 `TASK_STATE` |
| PLAN/BUILD 与计划路径 | PlanModeContext | 临时 `RUNTIME_STATE` |
| 业务状态 | `contextSource()` | 临时 `HARNESS_CONTEXT` |
| MEMORY.md、知识资料、额外文件 | 工作区 | 参考资料 `HARNESS_CONTEXT`，不进入 System |

临时状态和参考资料使用合成的 USER 角色消息，不追加进持久化历史。
System 中可以保留“如何使用记忆”的规则，但 MEMORY.md 正文是参考资料，不是更高优先级指令。

框架标签用于分隔来源和用途，块内允许 Markdown。AGENTS.md 仍写普通 Markdown，
不需要预先加标签。原始 sysPrompt 和第三方 Middleware 的提示不被自动重写。
标签和正文转义不是安全隔离机制，访问权限仍需在代码中控制。

## 接入动态业务信息

`contextSource(name, source)` 表示“每次推理请求准备时，读取这些信息给模型”。
它不是工具，不需要模型主动调用；注册和构建 Agent 时不会读取。
如果没有额外业务信息需要主动注入，可以完全不配置。

下面的片段使用已经设置 model、workspace 等参数的 `HarnessAgent.Builder builder`：

```java
import io.agentscope.harness.agent.context.ContextBlock;
import java.util.List;
import reactor.core.publisher.Mono;

builder.contextSource(
        "order-status",
        request -> Mono.just(List.of(
                ContextBlock.runtime("status", "订单 483：等待审批"))));
```

示例返回固定文字；实际可以按 `request.userId()`、`request.sessionId()` 查询授权数据。
来源必须只读，不要在这里执行发布、写库或测试命令。
复杂逻辑可实现函数式接口 `ContextSource`，其方法为
`Mono<List<ContextBlock>> load(ContextRequest request)`，无需 Extension 或 Registry。

ContextRequest 提供 agentId、userId、sessionId、callId、purpose，不暴露可修改的 AgentState。
身份可能为空，也不等于授权证明。来源实例可能跨会话、跨复制的 Agent 共享，
必须线程安全，不能用实例字段保存“当前用户”。

### 材料与稳定指令

```java
ContextBlock.runtime("status", "审批状态：等待审批");
ContextBlock.reference("policy", "退款政策参考内容");

// 可选控制：方法返回新对象。
ContextBlock.runtime("status", "审批状态：已批准")
        .withRevision("order-version-7")
        .withPriority(20)
        .required();

// 只用于可信应用配置，不要将外部业务数据提升为 System。
builder.instruction("approval-policy", "未取得有效审批前，不得发布。");
```

动态块只能是 runtime 或 reference，不能生成 SYSTEM。
默认 revision 是内容摘要；业务可指定版本，但它不自动证明新鲜度或验收通过。
默认可因预算移除，required 禁止预算移除；较低 priority 在同类材料中更早被考虑移除。

来源名称允许字母、数字、下划线、点、短横线，首字符为字母或数字；不能重复。
块 ID 在同一来源内唯一且非空。Manifest 中会出现 `source/order-status/status`
等来源标识，不要把凭证或敏感正文放进 ID。

### 超时与失败

```java
import io.agentscope.harness.agent.context.ContextBlock;
import io.agentscope.harness.agent.context.SourceFailurePolicy;
import java.time.Duration;
import java.util.List;
import reactor.core.publisher.Mono;

builder.contextSource(
        "order-status",
        request -> Mono.just(List.of(ContextBlock.runtime("status", "等待审批"))),
        options -> options
                .timeout(Duration.ofSeconds(2))
                .onFailure(SourceFailurePolicy.OMIT));
```

| 设置 | 行为 |
| --- | --- |
| 默认 | 每个来源超时 5 秒，失败则拒绝构建（FAIL） |
| OMIT | 读取失败时省略来源，不使用旧值；记录 Manifest，不自动把异常发给模型 |
| required | 成功读取后禁止预算删除；不能与 OMIT 来源组合 |

空列表表示没有材料；Mono.empty() 属于读取失败。重复块、空块等结构错误不会被 OMIT 吞掉。
需要模型知道状态未知时，来源应显式返回“状态不可用”，不要伪造事实。
来源顺序执行，总耗时可能累积；取消沿 Reactor 传播，但不保证远端或阻塞 I/O 立即停止。

## 什么时候刷新

| 信息 | 刷新时机 |
| --- | --- |
| 工作区 AGENTS.md、MEMORY.md 等 | 每次 Agent call 读取一次；同一次 call 内不自动监听文件变化 |
| 动态 contextSource | 每次 REASONING 请求准备读取一次；预算和渲染复用结果 |
| Todo、模式、需求、验证摘要 | 每次请求取当前状态快照 |
| 重试、fallback | 重新准备请求，动态来源重新读取 |

SUMMARY 不读取动态来源或注入任务投影；经同一构建器处理时保留稳定指令。
辅助摘要或 Memory Flush 若使用独立构建器，不隐式继承业务来源。
复制 Agent 保留来源配置与读取函数，不重复执行 options 配置回调。

## 可选任务信息

Todo 通过 `enableTaskList()` 开启，不附带候选需求工具。
只需进度列表时不用开启其他任务能力。

```java
import io.agentscope.harness.agent.context.TaskContextOptions;

builder.enableTaskList();
builder.taskContext(
        TaskContextOptions.builder()
                .includeRequirements()
                .includeVerificationResults()
                .build());
```

两个展示开关独立，默认关闭。需要模型提出候选要求时加
`allowRequirementProposals()`，它也会开启需求投影。taskContext 配置整体替换旧配置，不累加。

这些开关不自动运行 Verifier、不确认要求、不决定任务完成。普通用户消息和 PLAN.md
不会被自动解析为需求；计划复选框也不与 Todo 双向同步。

| TaskContextState 字段 | 谁更新 |
| --- | --- |
| tasks | 模型调用 todo_write 后由工具更新，或应用显式更新 |
| revision | 状态变更方法自动递增 |
| scope | 应用通过 beginTask 设置任务身份、目标与来源 |
| requirements | propose / 可选候选工具创建；可信调用方通过 decide 确认或拒绝 |
| contractVersion | 任务、要求决策、被校验对象版本变化时由方法递增 |
| subjectVersion | 应用用 setSubjectVersion / invalidateSubject 维护，不自动监听文件 |
| verifications | 调用方显式运行 VerificationService.verify，保存结论后更新摘要 |

状态随 AgentState 保存恢复；TASK_STATE 只是读取视图。
修改应在所属会话的受控调用中完成，多数变更方法检查 expectedRevision。
当前完整 Todo 回执足以表达进度时不重复输出列表；回执被截断或压缩后补回当前状态。
关闭投影不删除历史中已经存在的信息，也不是保密机制。

语义区分：Todo completed 是进度记录；要求 CONFIRMED 是授权认可；
验证 PASSED 只表示限定检查通过；STALE 不能作为当前验收证据。没有一个自动代表整体完成。

## 配置预算与观测

```java
import io.agentscope.harness.agent.context.ContextPolicy;
import io.agentscope.harness.agent.context.ContextTokenEstimator;

builder.contextPolicy(new ContextPolicy(
        24000, // 输入上限；0 表示由模型窗口推导
        4096,  // 输出预留；0 使用默认推导
        1024,  // 安全余量；0 使用默认推导
        ContextTokenEstimator.approximate(),
        manifest -> {
            // 可选：将不含正文的构建记录交给你的日志/观测系统。
        }));
```

默认估算包括消息、工具 Schema 和多模态占位成本，不是供应商精确 token 计数。
已知模型窗口时默认输出预留为窗口的 1/8、最多 4096，安全余量为 2%（均至少 1）。
显式请求更大的输出量时保留更多空间。未知窗口时输入回退上限为 64000，应按模型配置。
工作区的 maxContextTokens（默认 8000）只影响工作区材料准备，不替代最终请求预算。

超预算时先考虑移除可选 memory、knowledge，再考虑其他可选来源，随后按配置压缩历史。
System 和必需材料不被直接淘汰，仍超限则抛出 ContextBudgetExceededException。
高级用户可用 contextSelectionPolicy 提供省略顺序；不能返回未知/重复 ID 或必需来源。

Manifest 记录来源、版本、哈希、估算方法、预算、变换和检查结果，不含正文。
观察回调应快速返回；元数据仍可能有文件名等敏感信息。
构建也检查工具调用配对。失败时不提交压缩后的候选历史；异步期间状态或历史变化则拒绝旧快照。
这不等于外部数据库事务，也不回滚已经写入的卸载文件或归档。

## 请求内容示例

以下是按当前格式整理的示意，哈希简写、业务值虚构，不是线上抓包；
只展示相关块，省略其他规则、完整历史与工具 Schema。

System 中的项目规则：

```xml
<project_rules kind="project_rules" source="workspace:AGENTS.md" revision="…">
# 开发约定
使用明确 import，修改行为时补充测试。
</project_rules>
```

对话之后的临时 USER 消息（没有当前完整 Todo 回执时）：

```xml
<TASK_STATE revision="3">
Current todo status (agent-maintained, not independent verification):
1 open todo(s):
- [x] 定位问题
- [~] 补充测试
</TASK_STATE>
```

末尾的参考 USER 消息：

```xml
<HARNESS_CONTEXT>
Source materials, not additional authority:
<context_item kind="memory" source="workspace:MEMORY.md" revision="…">
用户偏好先运行针对性测试。
</context_item>
</HARNESS_CONTEXT>
```

## 当前边界与排查

- 数据没出现：检查来源是否注册、调用用途是否为 SUMMARY、是否因预算或 OMIT 被省略。
- 需求/验证没出现：检查 taskContext 开关及业务是否实际维护了状态，开关本身不生成数据。
- 文件修改未生效：工作区资料按 call 读取；下一次 call 或显式工具读取才能取得新内容。
- 空 Todo 提示：当前共享 revision 变化可能触发空列表提醒，即使只修改了需求；这是已知冗余。
- 旧格式：useLegacyXmlWorkspaceContext 已没有有效渲染分支，不应作为格式切换入口。
- 环境文案：标题已改为 Runtime Environment；内部仍有以 AgentStateStore ID 展示 sessionId 的历史命名，不表示存储地址。
- 标签、哈希和提示原文不是稳定协议，不要通过解析 prompt 驱动业务逻辑。

这套管线不默认开启通用业务状态机、自动事实提取、证据版本跟踪或强制完成门禁。

继续阅读：[工作区](/v2/zh/docs/harness/workspace)、
[记忆](/v2/zh/docs/harness/memory)、[压缩](/v2/zh/docs/harness/compaction)、
[计划模式](/v2/zh/docs/harness/plan-mode)。
