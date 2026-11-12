# Codex 发布版 app-server 集成备选方案

[English](codex-app-server-alternative.en.md)

记录日期：2026-10-31。状态：**备选，待未来验证；当前不实施、不切换、不部署。**

## 决策与背景

保留当前直接链接固定版本 `codex-core` 的 Rust Brain。本文单独记录 durable-harness 所采用的“官方 Codex 发布二进制 + app-server + 外部持久化适配”路线，供未来验证与选型。

本次仅增加文档入口，不更改现有依赖、镜像、配置、Session 绑定、检查点或集群部署。未来实验默认使用新 Session 和独立实验部署，不迁移或接管当前运行中的 Session。

需要纠正的判断边界：旧 TypeScript app-server probe 曾将工具持久化屏障和待完成回调恢复标记为“未验证”，并据此禁止接单。这是该适配器的验证缺口，不是官方发布版不具备相关能力的证明。该历史探针已于 2026-11-02 删除；未来验证需独立实现，不能把旧探针测试计入验收。当前没有完成等价对照实验，不能认定源码链接是唯一可行方案。

## 已核实的参考实现

参考 durable-harness 提交 `ea8aa4060cf4fb8cd39737ce8ab21c4e0f40c741`；2026-10-31 核对时，`main` 的 `harness-sandbox/src/clis.ts` 和 `harness-codex/src/harness.ts` 与该提交一致。以下是该版本的实现事实，未来启动验证时应重新确认版本与接口。

- **发布产物**：下载官方 npm 的 `@openai/codex@0.160.0-linux-x64`，按固定 SHA-512 校验后安装到 Sandbox。该路径未自行构建或修改 Codex 内核。[安装配置](https://github.com/vercel-labs/durable-harness/blob/ea8aa4060cf4fb8cd39737ce8ab21c4e0f40c741/packages/harness-sandbox/src/clis.ts#L32)
- **日志与执行屏障**：将原生 rollout 镜像到 World 日志；对于 `replay: never` 工具，确认相应调用记录已持久化，再记录意图并执行。异步日志未及时出现时，有限等待后拒绝执行。[屏障代码](https://github.com/vercel-labs/durable-harness/blob/ea8aa4060cf4fb8cd39737ce8ab21c4e0f40c741/packages/harness-codex/src/harness.ts#L627)
- **恢复**：重建原生日志并 `thread/resume`，通过 `thread/inject_items` 补入原 `call_id` 的结果；需要继续中断任务时，加入恢复说明并通过 `turn/start` 启动新的原生 Turn。[恢复代码](https://github.com/vercel-labs/durable-harness/blob/ea8aa4060cf4fb8cd39737ce8ab21c4e0f40c741/packages/harness-codex/src/harness.ts#L1061)
- **不确定结果**：已完成调用使用已记录结果；未执行调用明确标记未执行；状态不确定的调用可能返回“中断、可能已生效”的结果。不能把这种策略等同于继续等待一个独立远程任务完成。[结果修补](https://github.com/vercel-labs/durable-harness/blob/ea8aa4060cf4fb8cd39737ce8ab21c4e0f40c741/packages/harness-codex/src/harness.ts#L1250)

上述事实证明发布版路线值得验证，不证明 durable-harness 已经满足本项目全部执行契约，也不证明其所有测试已经在我们的环境通过。

## 候选接入方式

未来先区分两个可选范围，不能把“采用它的集成方式”自动理解为“引入它的全部基础设施”。

| 范围 | 做法 | 需评估的问题 |
|---|---|---|
| **A：借鉴机制，自有 app-server 适配器** | 使用官方发布版，借鉴 rollout 屏障、结果修补和 RPC 恢复机制；继续由现有 Session Service、Runtime 和 Tool Worker 承担平台职责。 | 要维护协议与日志格式适配，但能避免两套权威状态与调度机制。建议作为首个验证范围。 |
| **B：复用 durable-harness 适配包** | 评估其 Codex/process 适配层是否可以接入本平台。 | 需分析 World、Sandbox、relay 和生命周期依赖；确认是否需要实现 World 或调整部署拓扑。不能预设其现有 Vercel Sandbox 路径可直接在 Kubernetes 替换使用。 |

建议的 A 路线逻辑结构：

```text
Session API → Agent Service / Session Service
                         ↕
             自有 Harness Runtime / Codex RPC 适配器
                  ↕                    ↕
         官方 codex app-server       Tool Worker
                  ↕                    ↕
            受控模型出口             外部工具执行
```

这是逻辑职责图，进程与 Pod 放置方式在验证时确定，不是本轮部署设计。

- Session Service 继续负责租约、fencing、检查点提交、工具 Outbox、预算和结果接收。原生 rollout 是恢复材料，不自行成为另一套平台权威账本。
- 适配器在派发工具前持久化调用关联和恢复状态，收到平台提交确认后才允许执行；保留原 `call_id`、task/execution/result 身份与参数校验。
- Tool Worker 已接受的长任务按现有契约独立运行。Brain 消失后，新的适配实例应查询、等待原任务并注入结果，不能直接照搬“Brain 中断即工具 interrupted”的处理。
- 模型请求走受控出口，实施相同的准入、预算、参数、凭据与计量规则。不能把日志记录等同于可精确重放的模型调用；断流与未知用量应显式记录。
- 禁用未声明的内置 shell、文件、网络搜索、技能及用户配置发现；需要启用的能力通过显式契约和工具权限提供。

## 与当前 Rust 路线的差异

| 项目 | 当前 Rust Brain | app-server 候选路线 |
|---|---|---|
| 依赖 | 固定 `codex-core` commit，源码参与编译；当前无内核补丁 | 固定官方二进制版本、平台及摘要，无需编译内核 |
| 状态访问 | 内核 history 接口与自有 `ThreadStore` | rollout 文件同步与 RPC；需处理异步落盘、截断、轮转及损坏 |
| 原生恢复 | 结果注入后 `recover_turn_if_idle`，保持原生 Turn，不增加用户恢复消息 | durable-harness 示例使用恢复说明加新原生 Turn；是否有满足要求的其他发布接口需验证 |
| 平台 Turn | 与已提交恢复状态绑定 | 可维护平台 Turn 到多个原生 Turn 的映射，但必须定义输入、取消、输出和预算语义 |
| 长工具 | 已实现与原 Tool Worker 任务的关联、等待、结果应用路径 | 必须重新验证等价行为，不能直接以未知结果关闭仍在执行的原任务 |
| 升级成本 | Rust 编译、内核 API 和状态格式兼容 | 二进制分发、app-server 协议和 rollout 格式兼容；仍需锁版本 |

保持同一原生 Turn 是当前方案的一个明确优势，但其是否为不可放宽的产品要求尚待决策。平台 Turn 身份与原生 Turn 身份应分开讨论。独立 Tool Worker 和外部模型预算本身不构成必须源码链接的证据。

## 未来验证清单

以下全部为待执行项，参考实现的测试不计作本项目通过。

| 阶段 | 验证内容 | 通过条件 |
|---|---|---|
| V0 版本与隔离 | 锁定发布版、平台摘要、RPC 与 rollout 格式；建立独立实验目标 | 干净环境可重复安装；无内核源码构建/隐式补丁；实验与现有 Session 隔离 |
| V1 基本能力 | 文本、多 Turn、动态工具、结果注入、流式事件、取消；使用当前 DashScope 模型配置 | 公共 API 行为及有效模型/工具配置可对照；不依赖旧进程内回调恢复 |
| V2 工具前持久化 | 日志延迟、提交丢回执、平台拒绝、租约丢失、调用参数冲突 | 未确认持久化和授权前不执行工具；不确定提交按原身份查证；旧 owner 不能继续派发 |
| V3 长工具接管 | 工具完成前后 SIGKILL Brain；A/B 部分完成；结果重复投递 | 原工具继续、原任务身份不变；已接受结果可回填；同一受管调用不重复产生副作用 |
| V4 恢复与生命周期 | 输入尚未交付、恢复说明、新原生 Turn、取消竞态、日志损坏与版本不兼容 | 不丢输入、不重复公开输出；平台 Turn 映射明确；无法判定时停止执行，不盲目重放 |
| V5 预算与效果 | 模型断流/429、原生重试、压缩、辅助请求、恢复成本；同任务配对评测 | 全部尝试纳入公共预算；恢复提示变化可观察；使用一致性计划下相同样本和预先约定指标 |
| V6 运维与回退 | 冷启动、内存、镜像体积、升级、隔离及凭据处理 | 指标可复现；新旧实现路由清晰；失败可停止实验且不影响现有 Rust Session |

不能只凭“能回答、能恢复 thread”决定切换。最终证据应包括真实发布二进制、真实 Session Service 和 Tool Worker 的跨进程故障验收，而不只是假 app-server 或内存协议替身测试。Pod 故障、重复投递和并发 owner 场景需要单独覆盖。

## 未来决策与回退条件

只有完成关键正确性验证，确认平台 Turn 与原生 Turn 映射可接受，并评估恢复提示对效果的影响后，才比较维护成本、性能和成功率。实验前定义阈值和失败分类；保留所有结果，不以重跑覆盖失败。

- 若发布版能够满足契约，可另行提出切换方案；本文件不授权切换。
- 若遇到缺口，记录具体 RPC、状态边界、版本和可复现案例。先评估适配层或上游接口升级，再决定是否继续保留源码方案。
- 默认只让新 Session 使用候选实现；旧 Session 继续由原 Rust 实现运行。没有状态迁移验证时，不跨实现读取检查点，不直接修改已有 Harness 绑定。
- 回退首先停止向实验目标创建新 Session，妥善终止或完成已有实验任务。不能通过直接改镜像，假定两套实现的状态格式可互换。

相关资料：[当前 Rust 实现](../../agent-harness-runtime/rust/README.md)、[Session/Hands 协议](brain-session-hands-protocol.md)、[效果评测一致性计划](../../tests/general-agent-eval/consistency-plan.md)。
