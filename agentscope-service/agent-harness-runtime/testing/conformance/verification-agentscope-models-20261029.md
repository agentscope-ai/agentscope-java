# AgentScope 共同模型配置接线验证

[English](verification-agentscope-models-20261029.en.md)

日期：2026-10-29。目录 `/Users/ken/agentcore/agentcore-managed-agents`，分支 `master`；HEAD `ed482512993468e8dd68c9dac0a06f63b752fde5` 加已有未提交改动。本记录覆盖 W05 / W06 的 AgentScope 模型选择与实例接线，不能替代 W07 / W19 / W20 的完整准入和真实环境验收。

## 实现

- Service 的 prepared `model-configuration` 操作在解析前后验证当前 Turn owner、epoch、token 和进程租约；封存模型选择时固定 Session 内核绑定。PI / Codex 的通用配置使用同一模型选择存储；单次编译不重复解析 Agent 定义。
- `ModelBindingResponse` 带 Agent ID/version、source/effective/configuration 摘要，缓存按 scope、Turn 与内核复核。模型目录和 options 必须为对象，非法输入不回退。模型目录或部署默认值变化不重解释原 Turn。
- AgentScope 生产执行工厂调用 `ManagedAgentModels`，核对响应身份与实际封存定义，再按显式绑定创建实例独立的客户端。模型参数也进入 Harness GenerateOptions；请求不能修改模型路由、凭据或通过额外字段放宽输出上限。
- 已映射 OpenAI Chat Completions、OpenAI Responses 与 Anthropic Messages 的 endpoint、凭据、模型 ID、temperature、输出上限和上下文窗口。当前 Anthropic reasoning effort 尚未映射，明确拒绝。Responses 使用本地已发布的官方扩展，原生流式测试验证了 `max_output_tokens`、`reasoning.effort`、文本流与 `store=false`；未修改上游源码。
- 无显式目录/options 时，AgentScope 的 `native` 模式保留命名模型及部署默认模型。仅封存名称或默认选择，不能据此认为旧 ModelRegistry 内部的 endpoint、凭据和参数也已固定。

## 已完成的定向验证

| 检查 | 结果和覆盖 |
| --- | --- |
| `HarnessRunConfigurationTest` | 7 项通过；加密封存定义、override、权限、缓存固定、跨语言配置向量 |
| `SessionCoordinationHttpTest` | 9 项通过；新增真实 Service HTTP 模型选择，覆盖 owner/epoch/进程失效、跨内核拒绝、缓存篡改和非法目录；使用 H2 与真实加密定义 |
| `ManagedAgentModelsTest` | 3 项通过；真实 AgentScope OpenAI/Anthropic 客户端向本地 HTTP fixture 并发发送独立绑定，验证参数、凭据和输出限制；非法配置/请求覆盖被拒绝 |
| `HarnessAgentBuildServiceExecutionTest` | 2 项通过；真实工厂从 prepared HTTP fixture 取得绑定，构建和重建 HarnessAgent 并完成原生模型调用；override 和绑定进入实际请求，重新封装的错误定义身份仍被拒绝 |
| `AgentScopeRemoteFlowTest` | 2 项通过；已有真实内核、Service HTTP、工具执行和恢复回归 |
| PI HTTP 与 Java 启动装配 | 显式启用 `managed.pi.http=true` 后 PI 四项通过；AgentScope Spring 装配一项通过。首次未启用时的 PI 跳过不计入通过结果 |
| 协议 | 10 Schema、19 operation、112 正例、50 反例，以及各 4 个 JCS / identity 向量通过 |

上述 Java 定向验证共 28 个不同用例通过。Service HTTP、原生客户端 HTTP 和工厂 HTTP 分别有覆盖；工厂用例的 Service 是协议 fixture，不能拼接这些结果声称真实控制面到完整生产服务组合已经通过。

```sh
mvn -B -ntp -T1 -pl managed-agents/agent-harness-runtime/java/brain-agentscope,managed-agents/agent-service -am test -Dtest=ManagedAgentModelsTest,HarnessAgentBuildServiceExecutionTest,HarnessRunConfigurationTest,SessionCoordinationHttpTest,AgentScopeRemoteFlowTest,PiBrainHttpFlowTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -ntp -T1 -pl managed-agents/agent-harness-runtime/java/brain-agentscope,managed-agents/agent-service -am test -Dtest=PiBrainHttpFlowTest,AgentHarnessRuntimeAppContextLoadTest -Dmanaged.pi.http=true -Dsurefire.failIfNoSpecifiedTests=false
make -C managed-agents/agent-harness-runtime/protocol check PYTHON=/tmp/managed-agents-protocol-venv/bin/python
```

日志：`/tmp/managed-brain-agentscope-model-tests.log`、`/tmp/managed-brain-agentscope-model-wiring.log`、`/tmp/managed-brain-agentscope-model-protocol.log`。首次完整 `clean verify` 在公共 API 测试失败：历史 fixture 使用空 Agent 快照。已改为有效定义，并新增空定义拒绝测试；`SessionApiContractTest` 34 项通过/4 项条件跳过，`ManagedSessionTurnsApiTest` 14 项通过。

第二次完整检查仍因 `AgentServiceSessionCreationTest` 的 HTTP fixture 缺少 `agentSnapshot` 失败；已补为有效封存定义，定向 2 项全部通过。生产校验没有放宽。根目录 `spotless:check` 通过。按用户阶段性收敛要求不再启动第三次完整运行：**完整 `clean verify` 尚未取得成功结果**，第二次 reactor 中 Gateway/Brain 被跳过，不能用定向通过替代完整验收。工作已暂停，未部署、提交或推送。对应日志：`/tmp/managed-brain-agentscope-model-full-java-final.log`、`/tmp/managed-brain-agentscope-creation-regression.log`、`/tmp/managed-brain-phase-close-spotless.log`。

```sh
mvn -B -ntp -T1 -pl managed-agents/agent-service -am test -Dtest=AgentServiceSessionCreationTest -Dsurefire.failIfNoSpecifiedTests=false
mvn -B -ntp -T1 spotless:check
```

阶段结论、真实功能缺项与边界验证分别记录于[实施计划 2.6](/Users/ken/agentcore/agentcore-managed-agents/docs/managed-brain-completion-plan.md#26-阶段性收敛记录2026-10-29)。

Responses 初次 fixture 缺少 `cache_write_tokens`，导致原生 SDK 拒绝解析；已按[当前官方接口定义](https://developers.openai.com/api/reference/resources/responses/methods/retrieve)补齐后通过。兼容出口需遵守所用 SDK 的响应契约；没有把缺失用量静默改为零。补充日志：`/tmp/managed-brain-agentscope-responses-native.log`、`/tmp/managed-brain-agentscope-model-responses.log`。

## 后续范围

1. 子 Agent 的 frozen definition 仍有后置模型/GenerateOptions 配置，需与独立绑定共同编译并验证，不能把根 Agent 通过扩大解释为子 Agent 已对齐。
2. 完整字段覆盖、控制面能力准入、Vault 绑定/撤销、原生注册模型的固定语义、模型别名与平台价格身份仍未完成；辅助调用预算一致性继续纳入 W14。
3. 真实控制面、真实存储/消息设施、同定义跨内核、公共 SSE 与部署容量继续按 W19 / W20 验收。
