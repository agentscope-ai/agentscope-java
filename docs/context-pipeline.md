# HarnessAgent Context 构建管线

## 模型调用边界

HarnessAgent 默认安装 `HarnessContextBuilder`，通过 core 的 `ModelRequestPreparer`
在所有 `onModelCall` Middleware 之后准备实际请求。普通推理与最大轮数总结均经过此边界；
每次重新订阅、重试或更换模型都会重新准备。普通 ReActAgent 不自动安装 Harness 策略。

顺序：材料快照与当前模式规则 → 大工具结果卸载候选 → 预算选择 → 历史压缩候选
→ 当前状态投影与去重 → 稳定布局 → 工具配对与最终预算校验 → 历史快照检查与提交
→ 构建结果事件 / Manifest → 模型。

最终布局固定为：System → 对话（含摘要及完整工具交互）→ 临时状态 → 参考资料。
不论是否发生压缩，都使用同一布局；消息角色和对话内部顺序不变。
最终 System 合并为单条，适配只从第一条消息提取系统指令的模型转换器；内部元数据
保留原始 System 输入，重试或 fallback 时先恢复来源再渲染，避免重复拼接框架规则。

构建器不调用模型来改写 AGENTS.md 或生成任务状态。压缩历史是单独的、最多一次的
摘要操作；摘要失败保留原历史，最终请求仍须满足预算。摘要和 Memory Flush 使用
不递归压缩的辅助调用预算入口。

## 来源与呈现

| 内容 | 来源 | 呈现 |
| --- | --- | --- |
| Agent 指令 | `sysPrompt` | System |
| 通用 Context 约定 | `context-conventions.md` 内置资源 | System / instruction_rules |
| 项目规则 | 普通 Markdown AGENTS.md | System / project_rules，正文分隔符转义 |
| 工作区能力约定 | Workspace Middleware 内置规则 | System / working_principles |
| 环境与文件系统边界 | WorkspaceManager 与运行配置 | System / environment |
| PLAN / BUILD 操作规则 | PlanModeMiddleware + 当前状态 | System / mode_rules，每次模型请求刷新 |
| Memory / Knowledge / 额外文件 | 本次调用的获授权 Workspace 读取 | 临时 HARNESS_CONTEXT |
| Todo | `AgentState.tasksContext` | 最新完整工具回执或 TASK_STATE，二者不重复 |
| 模式与计划位置 | `AgentState.planModeContext` | 临时 RUNTIME_STATE |
| 近期交互 | 活动消息历史 | 保持消息角色及工具调用配对 |

工作区资料每次 Agent 调用读取一次，缓存在该调用的 RuntimeContext 中；不是每次推理
重读整个工作区，也没有跨用户共享内容缓存。同一次调用内修改文件后，可通过工具读取
获取新版本；当前没有文件变更订阅或自动重新加载机制。

这些内置 Middleware 只收集材料，不再把正文直接拼进原始 system prompt。
`ContextItem` 包含 kind、sourceId、revision、placement、required、priority、content；
文件/内置资源的默认 revision 是已加载内容的 SHA-256 指纹，状态提供方也可显式传入版本。
`WorkspaceContextMaterials.register` 按来源 ID 替换，防止重试和模式刷新产生重复条目。
内置 Middleware 中最先安装的 ContextConventionsMiddleware 在每次 Agent 调用开始时
重建材料集合，避免复用 RuntimeContext 或派生子 Agent 时继承已禁用来源的旧资料。
内置工作区来源随后注册；扩展如需提供结构化材料，应在 onModelCall 中注册，避免在
初始化之前写入集合。原有扩展 onSystemPrompt 的字符串输出仍保留为不透明 System 输入。
`ContextRenderer` 按优先级和来源 ID 稳定排序、转义正文并生成标签。
required 防止必选材料被预算淘汰；priority 当前控制渲染顺序，尚不是相关性评分器。

内置标签由框架生成，不需要用户在 AGENTS.md 中写标签。SYSTEM / RUNTIME / REFERENCE
是受信任代码指定的呈现位置，不从文件正文推断，也不代替文件访问权限检查。
Agent 的原始 sysPrompt 和未迁移 Middleware 的输出保持原样，作为不透明 System 输入。
Todo 状态仍由 core 的 TaskContextProjection 渲染，不依赖 harness 模块。

AGENTS.md 不要求 XML 格式。TASK_STATE 不单独持久化，不自动解析 PLAN.md，不自动
同步 Markdown 复选框。Todo completed 是 Agent 维护的进度状态，不等于独立验证通过。
当前没有自动从任意工具文本提取完整目标、审批、外部副作用和证据图谱。

## 状态去重与持久化

`todo_write` 每次成功更新后递增 revision，工具结果携带 state_key、state_revision 和
representation。只有内容完整且与当前状态一致的最新结果才能替代状态提醒；旧结果在
模型视图中缩为回执，原始历史不改写。清空列表也递增 revision，避免旧任务被误认作当前任务。
删除、截断或卸载了完整回执时，构建器从当前状态补回提醒。

压缩和工具结果卸载先返回模型视图及候选历史；最终请求校验通过后才替换活动历史。
压缩只在输入历史对应 canonical history 时产生可提交的历史替换；自定义 Hook 改写的
阅读视图不会直接覆盖 canonical history。提交前再次比较原始历史快照，发现变化则拒绝
本次构建，不覆盖新消息。这仍依赖单会话调用串行执行的既有约定，不是对任意外部直接
修改 contextMutable 的通用并发事务保证。

最终校验失败时活动历史不变。为生成候选结果，卸载文件、会话归档及配置启用的 Memory
Flush 可能已经执行；这些操作不随历史提交回滚。工具结果卸载仍须写入成功才能生成替换。

## 预算与配置

```java
HarnessAgent.builder()
    .name("assistant")
    .model(model)
    .workspace(workspace)
    .contextPolicy(new ContextPolicy(
        24000, // 输入上限，0 使用模型窗口推导
        4096,  // 输出预留，0 使用默认值
        1024,  // 安全余量，0 使用默认值
        ContextTokenEstimator.approximate(),
        manifest -> auditSink.accept(manifest)))
    .build();
```

默认估算包含消息、工具 Schema 和多模态占位成本，不是供应商精确 Token 数。
多模态按每块 2048 Token 估算，无法保证长音频、高分辨率图片等场景的准确性；这些应用
应传入模型适配的 estimator。默认输出预留为窗口的 1/8（最多 4096），安全余量为窗口的
2%；请求显式指定更大输出量时取较大者。模型不提供窗口时使用 64000 输入 Token 的
回退上限，应用应配置符合其模型的上限。

输入超限时，可选 Memory、Knowledge 优先让出空间；显式额外 Context、System 和
当前交互不会被任意删除。仍无法满足时抛出 `ContextBudgetExceededException`。
摘要模型的窗口只决定摘要请求是否可发送，不决定主推理模型的预算。

服务侧 Session overrides 支持：

```json
{"contextPolicy":{"maxInputTokens":24000,"reservedOutputTokens":4096,"safetyMarginTokens":1024}}
```

数字必须是非负整数；未知策略字段拒绝。策略随 Harness 派生子 Agent 传递。
本次没有新增前端配置表单，可通过现有 overrides 通道设置。

## 观测

`ModelCallStartEvent.metadata.contextManifest` 关联真实 replyId；服务侧保存在
`span.model_request_start` 的 `model_call_id` 与 `context_manifest` 字段。
Manifest 包含模型、预算、计量方法、状态版本、来源 ID、内容哈希和变换记录，不含正文。
内置材料另有逐来源记录，包含内容版本、位置、必选属性与优先级；不记录正文。
原始 System 仍作为整体输入记录，不声称能还原所有第三方 Middleware 的内部来源。

独立的 `CustomEvent("context_build")` 在普通推理、总结及 fallback 准备边界发出，
服务侧持久化为 `span.context_build`。字段包含 model_call_id、purpose、status、
context_manifest；失败只记录 error_type，不把异常正文写入事件。
配对失败、预算超限和历史冲突均有失败记录，不伪造 model_request_start。
成功的构建事件与后续模型调用使用同一 call ID。辅助摘要/Memory Flush 的预算入口
不独立发布此核心 Agent 事件。

Manifest 仍可能暴露文件名、工具名等元数据，应沿用会话访问控制和保留策略。
observer 应快速返回；需要外部持久化时由应用可靠投递，不应阻塞模型调用线程。
observer 抛出的运行时异常被隔离，不导致已通过校验的模型请求失败。

## 验证

```shell
mvn -pl agentscope-harness -am test
mvn -pl agentscope-core,agentscope-harness spotless:check
```

新增测试覆盖最终请求入口、模型替换、拒绝后不调用模型、Todo 回执去重、截断后恢复、
清空与修订号持久化、预算、Schema 成本、会话隔离、资料分隔符、工具配对和 Manifest。

第二版还覆盖：摘要成功但最终超限时不提交历史、卸载成功但请求拒绝时保留历史、
历史变化冲突、压缩前后布局、重复构建稳定性、完整请求正文快照、实际模型入口的
Workspace 材料分层、同一调用内模式刷新、失败事件落库和 observer 异常隔离。

本轮未实现精细相关性预算、完整任务证据系统、文件自动失效刷新或新的前端配置页面。
