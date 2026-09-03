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
- 明确 payload 仍是 JSON `String`，旧客户端可忽略未知 passthrough subtype。

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

- Core：`AgentStreamingTest.testStreamEventCount` 在 Core 回归中通过；示例通过 wrapper 显式 opt-in，未修改原始 `ReActAgent#streamEvents()`。
- AG-UI：`AguiAdapterConfigTest.testDefaultConfig` 与 `testBuilderWithDefaults` 断言默认关闭；`AguiAgentAdapterV2Test.testTextOutputDispositionRemainsDisabledWithoutChangingLegacySequenceOrMessageId` 覆盖未启用时旧序列和 message ID，AG-UI 全模块 528 个测试通过。
- Remote：`RemoteAgentEvent.payload` 类型仍为 `String`，DTO 保留 `@JsonIgnoreProperties(ignoreUnknown = true)`；`RemoteEventCodecTest.roundTripTextOutputDispositionAsAgentEventPayload` 与 `RemoteEventCodecPassthroughTest.payloadDecodesEvenWhenTheWireTypeIsUnknownToThisClient` 在 Harness 补充回归中通过。
- Final answer filter：`FinalAnswerFilterMiddlewareTest` 的 `finalRoundEmitsBufferedTextBeforeModelCallEnd`、`intermediateRoundSuppressesTextWhenToolCallIsObserved`、`nonTextEventsAreForwarded`、`stateIsolatedAcrossSubscriptions` 均在 Core 回归中通过。

## 自审

- brief Step 1：示例与协议/服务文档已覆盖要求。
- brief Step 2：Spotless 全 reactor 通过。
- brief Step 3：五条必需 Maven 命令均已原样运行；环境阻断均精确记录，并用补充命令验证下游目标模块。
- brief Step 4：前端测试通过；build 基线缺失精确记录；目标文件 TypeScript 检查通过。
- brief Step 5：四组默认兼容性均有测试或代码证据。
- brief Step 6：将使用指定提交信息 `docs(streaming): 说明文本处置与结果校准用法` 提交，仅保留本任务文件与本报告。

未发现需要新增生产代码、修改默认行为或扩大文档范围的问题。
