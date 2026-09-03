# Task 9 交付报告：文档、兼容性与全量验证

日期：2026-09-03

分支：`codex/stream-events-text-disposition`

工作树：`D:\ai-code\agentscope-java\.worktrees\stream-events-text-disposition`

## 结论

Task 9 的文档与 Javadoc 已完成，并验证了 Core 显式启用方式、`TERMINAL` 与最终答案的区别、Remote 字符串 payload 兼容语义、AG-UI/Web 启用方式及默认关闭兼容性。

必需 Maven 命令在当前 Windows 环境中会被两个无法创建符号链接的 Core 用例提前阻断；排除这两个环境用例后，Agent Protocol、AG-UI 与 Data Plane 的目标 reactor 均成功。Harness 的原始补充运行还暴露了三个依赖 Unix `sh` 的环境用例；进一步排除该类后，Harness 及下游目标模块均通过。前端测试通过；完整前端 build 仍被 HEAD 既有缺失的两个 `src/features/build/**` 页面阻断，Task 8 的三个目标文件通过独立严格 TypeScript 检查。

## 实现内容

### Core 示例

- 更新 `AgentEventStreamExample`，使用：

  ```java
  AgentEventStreams.withTextOutputDisposition(agent.streamEvents(input))
  ```

- 输出 `replyId -> disposition`。
- 明确说明 `TERMINAL` 只关闭流式文本生命周期，`AgentResultEvent` 仍是权威调用结果。
- 保留 opt-in 语义，没有修改 `ReActAgent#streamEvents()` 的默认序列。

### Managed Web 文档

- 增加 `event_update` SSE 事件说明。
- 说明 Managed Web 服务端已内部启用文本处置派生，客户端通过 `event_deltas=agent.message` 订阅。
- 说明 `INTERMEDIATE`、`TERMINAL != final answer`、权威空结果清除预览、权威结果校准及不落库语义。

### AG-UI 文档

- 新增模块 README，记录 `.textOutputDispositionEnabled(true)` 的显式启用方式。
- 明确默认值为 `false`，未启用时保持旧 message ID 与事件序列。
- 记录 `agentscope.text_output.disposition` CUSTOM 事件及标准 `MESSAGES_SNAPSHOT` 校准事件。
- 明确 `TERMINAL` 不代表最终答案。

### Remote Javadoc

- 修正 `RemoteEventCodec`、`RemoteEventType`、`RemoteStreamDetail` 的过时说明。
- 明确 `detail=full` 会包含 `TEXT_OUTPUT_DISPOSITION` 与 `AGENT_RESULT` 两种 `AGENT_EVENT` subtype；其余 passthrough subtype 仍仅在 `verbose` 下可见。
- 明确 payload 仍是 JSON `String`，`eventType` subtype 也仍是字符串；客户端可以忽略不理解的 subtype。
- 区分两种 JSON 前向兼容机制：`READ_UNKNOWN_ENUM_VALUES_AS_NULL` 处理未知 wire enum，DTO 的
  `@JsonIgnoreProperties(ignoreUnknown = true)` 只处理未知字段。

## 路径差异

brief 中 Remote 文件路径指向 Agent Protocol 扩展模块，但当前仓库实际实现位于：

`agentscope-harness/src/main/java/io/agentscope/harness/agent/subagent/protocol/`

因此本任务修改了 Harness 中的真实生产类。另一个差异是 AG-UI 模块在 HEAD 及历史中都没有 README；本任务按 brief 指定位置新增该文件，而不是覆盖既有文档。

## 验证记录

所有 Maven 成功复跑均临时使用 `C:\Program Files\Java\jdk-17`；未修改仓库或机器的持久配置。默认 `JAVA_HOME` 是 JDK 21，不满足项目 Maven Enforcer 的 JDK 17 要求。

| 命令 | 退出码 | 结果 |
| --- | ---: | --- |
| `mvn spotless:check -DskipTests` | 0 | 91 个 reactor 模块 SUCCESS，无需执行 `spotless:apply` |
| `mvn -pl agentscope-core test -DskipITs -Dtest='!DangerousPathBypassTest'`（默认 JDK 21 首次运行） | 1 | Enforcer 在测试前拒绝 JDK 21；Tests run: 0 |
| 同一 Core 命令，临时切换 JDK 17 | 0 | Tests run: 2390，Failures: 0，Errors: 0，Skipped: 8 |
| `mvn -pl agentscope-harness -am test -DskipITs` | 1 | Core：2399，Failures: 0，Errors: 2，Skipped: 8；两个 `DangerousPathBypassTest` symlink 用例因 Windows “客户端没有所需的特权”失败，Harness 被跳过 |
| `mvn -pl agentscope-harness -am test -DskipITs -Dtest='!DangerousPathBypassTest'` | 1 | Core 通过；Harness：970，Failures: 0，Errors: 3，Skipped: 7；三个 `DockerSandboxCommandTest` 因系统找不到 `sh` 失败 |
| `mvn -pl agentscope-extensions/agentscope-extensions-protocol/agentscope-extensions-agent-protocol -am test -DskipITs` | 1 | Core：2399，Failures: 0，Errors: 2，Skipped: 8；同一 symlink 环境错误，下游被跳过 |
| 同一 Agent Protocol 命令，增加 `-Dtest='!DangerousPathBypassTest,!DockerSandboxCommandTest'` | 0 | Core：2390/0/0/8；Harness：964/0/0/6；Agent Protocol：32/0/0/0；reactor 全部 SUCCESS |
| `mvn -pl agentscope-extensions/agentscope-extensions-protocol/agentscope-extensions-agui -am test -DskipITs` | 1 | Core：2399，Failures: 0，Errors: 2，Skipped: 8；同一 symlink 环境错误，AG-UI 被跳过 |
| 同一 AG-UI 命令，增加 `-Dtest='!DangerousPathBypassTest,!DockerSandboxCommandTest'` | 0 | Core：2390/0/0/8；AG-UI：528/0/0/0；reactor 全部 SUCCESS |
| `mvn -pl agentscope-service/service-dataplane -am test -DskipITs` | 1 | Core：2399，Failures: 0，Errors: 2，Skipped: 8；同一 symlink 环境错误，Data Plane 被跳过 |
| 同一 Data Plane 命令，增加 `-Dtest='!DangerousPathBypassTest,!DockerSandboxCommandTest'` | 0 | 15 个 reactor 模块 SUCCESS；Core：2390/0/0/8；Harness：964/0/0/6；Service Common：21/0/0/0；Data Plane：32/0/0/0 |
| `npm test -- --run` | 0 | 1 个测试文件通过，6/6 tests passed；npm 对多余 `--run` 给出未来版本配置警告，实际脚本为 `vitest run` |
| `npm run build` | 1 | `tsc --noEmit` 被 HEAD 既有缺失页面阻断：`DeploymentsPage` 与 `AgentsHubPage` 的 `src/features/build/**` 模块不存在 |
| 首次直接拼接 TypeScript CLI 参数的目标文件试跑 | 1 | PowerShell/TypeScript CLI 对 `--lib`、`--paths` 参数解析失败；属于验证命令写法问题，不是源文件诊断 |
| 临时 `tsconfig.task9.json` + `npx tsc --noEmit -p tsconfig.task9.json` | 0 | Task 8 目标文件 `ChatPanel.tsx`、`ChatPanel.test.tsx`、`MessageBlock.tsx` 严格类型检查通过；临时配置随后删除 |
| `mvn -pl agentscope-examples/documentation -am -DskipTests compile` | 0 | 27 个 reactor 模块 SUCCESS，Documentation 模块 50 个源文件编译成功 |
| `git diff --check`（自审前） | 0 | 无空白错误 |

### 环境失败明细

Windows 符号链接权限：

- `DangerousPathBypassTest.symlinkToDotEnvIsDetected`
- `DangerousPathBypassTest.symlinkToSshIsDetected`

缺少 Unix `sh`：

- `DockerSandboxCommandTest.execProcessDestroysTheHostProcessOnTimeout`
- `DockerSandboxCommandTest.execProcessDestroysTheHostProcessWhenWritingFails`
- `DockerSandboxCommandTest.execProcessDestroysTheHostProcessWhenInterrupted`

这些失败都发生在环境依赖处，没有观察到本任务修改引发的断言失败。

### 前端基线缺失明细

`npm run build` 的精确 TypeScript 错误：

- `src/main.tsx(54,29): TS2307`：缺少 `./features/build/deployments/DeploymentsPage`
- `src/pages/AgentsHubPage.tsx(17,25): TS2307`：缺少 `../features/build/agents/AgentsHubPage`

按 brief 要求未创建这些无关页面。

## 默认兼容性复核

- Core：`ReActAgentNewLoopReplyTest.unwrappedTextOnlyStreamPreservesLegacySequenceWithoutDisposition`
  直接调用未包装的 `ReActAgent#streamEvents()`，断言精确的 8 事件序列
  `AGENT_START → MODEL_CALL_START → TEXT_BLOCK_START → TEXT_BLOCK_DELTA → TEXT_BLOCK_END → MODEL_CALL_END → AGENT_RESULT → AGENT_END`，
  并断言不存在 `TextOutputDispositionEvent`。
- AG-UI：`AguiAdapterConfigTest.testDefaultConfig` 与 `testBuilderWithDefaults` 断言默认关闭；`AguiAgentAdapterV2Test.testTextOutputDispositionRemainsDisabledWithoutChangingLegacySequenceOrMessageId` 覆盖未启用时旧序列和 message ID，AG-UI 全模块 528 个测试通过。
- Remote：`RemoteAgentEvent.payload` 类型仍为 `String`；`AgentProtocolTaskClient` 的 JSON mapper 通过
  `READ_UNKNOWN_ENUM_VALUES_AS_NULL` 将未知 `RemoteEventType` 读为 `null`，而 `RemoteAgentEvent` 的
  `@JsonIgnoreProperties(ignoreUnknown = true)` 独立忽略未知字段。新增
  `AgentProtocolTaskClientTest.unknownWireEnumAndFieldDoNotDropStringPayload` 通过真实 SSE JSON 路径同时验证这两点及字符串 payload 保留。
- Final answer filter：`FinalAnswerFilterMiddlewareTest` 的 `finalRoundEmitsBufferedTextBeforeModelCallEnd`、`intermediateRoundSuppressesTextWhenToolCallIsObserved`、`nonTextEventsAreForwarded`、`stateIsolatedAcrossSubscriptions` 均在 Core 回归中通过。

## 自审

- brief Step 1：示例与协议/服务文档已覆盖要求。
- brief Step 2：Spotless 全 reactor 通过。
- brief Step 3：五条必需 Maven 命令均已原样运行；环境阻断均精确记录，并用补充命令验证下游目标模块。
- brief Step 4：前端测试通过；build 基线缺失精确记录；目标文件 TypeScript 检查通过。
- brief Step 5：四组默认兼容性均有测试或代码证据。
- brief Step 6：将使用指定提交信息 `docs(streaming): 说明文本处置与结果校准用法` 提交，仅保留本任务文件与本报告。

未发现需要新增生产代码、修改默认行为或扩大文档范围的问题。

## 修复轮 1（2026-09-03）

根据复审 findings 做了以下校正：

- `AgentEventStreamExample` 不再声称当前只打印 disposition 的 callback 会显示所有生命周期/工具事件；
  无工具序列补入真实 `AGENT_RESULT`，并明确 opt-in wrapper 在 `AgentEndEvent` 前派生
  `TEXT_OUTPUT_DISPOSITION(TERMINAL)`，顺序为 `AGENT_RESULT → TEXT_OUTPUT_DISPOSITION → AGENT_END`。
- `07-events.md` 明确 `authoritative=true, hasOutput=false` 的 `event_update` 只用于权威结果无输出时清空预览；
  普通非空结果不另发 authoritative update，而是由复用同一 ID 的持久化 `agent.message` 校准。
- `SubagentDeclaration#getRemoteStreamDetail()` 及 builder 的公开 Javadoc 明确 FULL 还包括
  `TEXT_OUTPUT_DISPOSITION` 与 `AGENT_RESULT`。
- 用真实 `ReActAgent#streamEvents()` characterization 测试替换不相关的旧数量证据；该测试在首次有效运行即通过，
  说明现有默认行为已经满足要求，因此没有伪造 RED 或修改生产逻辑。
- 新增真实 SSE JSON characterization 测试，准确区分未知 enum 与未知字段的处理机制。首次命令因 PowerShell
  未给带点 Maven 属性加引号而未进入构建；第二次在测试前由 Spotless 报告两处格式差异；按建议修正后首次有效行为运行通过。

本轮新增验证：

| 命令 | 退出码 | 结果 |
| --- | ---: | --- |
| `mvn -pl agentscope-core test -DskipITs "-Dtest=ReActAgentNewLoopReplyTest#unwrappedTextOnlyStreamPreservesLegacySequenceWithoutDisposition,AgentEventStreamsTest#emitsResultTerminalThenEndOnNormalCompletion"` | 0 | 2 tests，0 failures/errors；同时覆盖未包装默认序列和 wrapper 的 `AGENT_RESULT → TERMINAL → AGENT_END` 顺序 |
| `mvn -pl agentscope-harness -am test -DskipITs -Dtest=AgentProtocolTaskClientTest "-Dsurefire.failIfNoSpecifiedTests=false"` | 0 | Harness 目标测试 1/1 通过，3 模块 reactor SUCCESS |
| `mvn spotless:check -DskipTests` | 0 | 91 个 reactor 模块 SUCCESS |
| `mvn -pl agentscope-examples/documentation -am -DskipTests compile` | 0 | 27 个 reactor 模块 SUCCESS，Documentation 50 个源文件编译成功 |

本轮没有修改生产行为；只收紧兼容性测试、修正文档/Javadoc，并新增 Remote JSON 边界测试。
