# Harness 一致性改造实施记录

实施日期：2026-10-31—2026-11-01。实际目录：`/Users/ken/agentcore/agentcore-managed-agents`，分支：`master`；直接保留并修改主目录，未创建 worktree、提交或推送。本文承接[一致性计划](consistency-plan.md)，最终集群验收证据见本文末尾。

## 计划逐项落实

| 计划项 | 实现方式 | 验证与边界 |
| --- | --- | --- |
| P0-1 实际模型请求 | 公共 model profile 显式规定模型、协议、采样、reasoning、输出上限、工具选择/并行和 180 秒超时；Java/PI/Codex 适配器读取同一封存绑定。公共网关在 SDK 序列化后检查实际请求并持久化脱敏审计。 | 违反约定会在出站前拒绝；保存实际字段、请求/系统/工具哈希、返回模型和 usage。模型仍是供应商可变别名。 |
| P0-2 Case 总预算 | Session-tree 持久化模型次数、累计 token、业务工具次数、恢复次数和绝对截止时间；所有 Turn、恢复和辅助推理共用预算。Runner 在首条输入前配置并读回核对。 | 60 模型请求、8M token、2 次异常恢复、900 秒；τ 保留官方 30 动作约束。token 在响应后结算，最多超出一个响应；未知消耗保守拒绝后续推理。 |
| P0-3 重试与恢复 | 关闭额外 HTTP/原生 PI 重试；每次实际模型请求先持久化准入；异常接管受共享恢复预算约束。429/断流/运行中重启有独立验收。 | 未知外部副作用保留租约，不自动重跑；预算拒绝保留证据，由 Runner 取消并确认终态。 |
| P0-4 顺序与副作用 | 模型 `parallel_tool_calls=false`；Rust 多调用逐个提交、执行、应用结果后才派发下一项；Java/PI 同样串行。原生调用身份用于结果接续。 | 工具审计保存调用身份、参数/结果哈希；新调用身份的相同参数是模型重复决策，不能静默去重。重启验收使用没有去重能力的写入工具。 |
| P0-5 提示词、工具、权限 | 封存业务政策、输入和定义；禁用额外内置能力；出站审计验证完整政策、工具 schema/描述和可逆 `hands_` 映射。Codex 禁止隐式项目配置、环境/实时时钟注入；PI 短模式通过官方 `before_agent_start` 扩展点完整替换 system，移除自动附加的工作目录。 | 保留框架必要的协议指令；不同框架的原生消息结构不宣称逐字相同。对齐的是业务信息和可用能力。 |
| P0-6 环境、评分、失败 | PVC 上持久化独占租约、case fencing 和请求账本；失败/进程重启阻止槽位复用；τ 官方状态及 AppWorld 基础库+运行库指纹；官方 evaluator 保持原样。 | AppWorld 每例先通过受租约保护的 API 重置数据库与模型哈希缓存，再核对完整初始指纹。保留全部尝试，区分得分失败、预算、供应商、基础设施和未知状态。 |
| P1-1 压缩与历史 | `aligned-short-context` 通过各框架公开配置关闭压缩/裁剪，网关统一 600000 字节输入边界。`native` 保留原生策略；Codex 本地摘要路径已接入持久化模型门禁，单独记录 compaction。 | 原生摘要与普通推理均记账；保存历史条目哈希/长度以观察保留和删减，原始请求/原生 checkpoint 仍由 Session Store 持久化。框架摘要算法、提示词和 token 估计不强行改成同一种。 |
| P1-2 工具结果 | 统一完整 MCP JSON 文本信封，保留嵌套字段、structuredContent、空值与 isError；统一 65536 字节传输上限，溢出返回带原始哈希/大小的显式错误。Codex 短模式取消二次原生裁剪。 | 三套适配器覆盖长 JSON、空返回和错误；网关记录原始及实际交付结果哈希。当前为文本 MCP 赛道，不声称图像/音频行为一致。 |
| P1-3 用户模拟与采样 | τ 模拟用户 qwen-plus、temperature=0、90 秒、禁用重试；记录请求哈希、响应模型、用量和时间；按 task/repeat 轮换三者顺序。 | 冒烟 1 次、正式 3 次，Runner 强制匹配 profile；不支持把重试择优覆盖，也不承诺可变模型完全确定性。 |
| P2-1 资源与性能 | 三个 namespace 的五类组件统一 2 副本，Brain/Agent Service/Tool Worker 各 4 CPU/8Gi，Gateway/Control Plane 各 2 CPU/4Gi；运行时并发 4、工具 64、工具控制 32、投影 8；显式核验 ACS 注入的 30Gi 临时存储请求；现有初始化容器统一 100m CPU/128Mi，确保实际 Pod 为 Guaranteed，计算类别均为 general-purpose/default。 | 采集 Pod 重启、cgroup v1/v2 throttling、OOM 和计数器重置；模型/工具耗时分开，服务端 Turn 时间与客户端观测分开。网关实时转发 SSE，记录供应商首字节/首文本延迟；这不等于控制台渲染时间。投影可见时间受 2 秒轮询影响。 |
| P2-2 发布与统计 | 自动预检 profile、网关策略、镜像摘要、资源、原生开关及 rollout；导出相同 Helm values。冻结源文件/依赖/数据集/镜像；输出端到端、有效评分、配对交集、置信区间、错误、预算、token/cache、重复决策和耗时。 | 缺少真实计价依据时费用为不可用；不把 SDK 的零价格视为零费用；未运行样本单独列出，历史批次不合并。 |

## 部署与运行入口

[evaluation-profile.json](evaluation-profile.json) 与 [evaluation-native-profile.json](evaluation-native-profile.json) 分离短上下文基线与原生策略评测；[公共模型配置](../../deploy/profiles/qwen38-eval.json) 单独维护。

[apply_model_profile.py](apply_model_profile.py) 从同一契约部署并导出 Helm overlay；Helm 模板支持 `evaluation.contextMode`、`evaluation.toolEnvelope` 和各组件完整镜像摘要。后续升级应复用生成的 overlay，不能只保留本次 kubectl patch。

[prepare_java_layers.py](prepare_java_layers.py) 校验 Spring Boot 入口与实际主类，明确选择 Tool Worker 的可执行 `-exec.jar`。镜像发布保留现有已验证基础镜像的 OS/架构/启动参数，叠加本次构建产物并记录新摘要；最终三种 Harness 镜像均合并历史层，避免历次打包遗留的 JAR 层影响冷启动；其他公共 Java 组件使用固定基础镜像上的已验证增量层，三个 namespace 共用相同组件摘要。发布清单记录实际方式；镜像层不包含凭据。

[deploy_environment.py](deploy_environment.py) 在杭州 `managed-agents-eval` 部署环境服务和 Runner。正式测试运行在 Pod；本机仅负责发布、预检、资源采集和需要 Kubernetes 权限的故障注入。环境及结果使用 40Gi PVC；临时 Rust 构建使用 8 CPU/16Gi Pod 和 80Gi 缓存 PVC。平台登录凭据仅挂载 Runner 容器，环境服务仅持有评测传输凭据。原生赛道通过 `deploy_environment.py --evaluation-profile` 选择网关策略，部署、预检和 Runner 必须使用同一 profile。

AppWorld 固定 commit `42b5bcf3cd334fee33f0c37c02070a9f5807add5`、数据 0.2.0；τ-bench 固定 commit `59a200c6d575d595120f1cb70fea53cef0632f6b`。两者依赖分别锁定。AppWorld 数据包 SHA256 为 `c9299e6cafe92bce4592a3c117c047c973d1554a667c21dd81537e78ab2f532e`。

官方 AppWorld MCP 实测 469 个工具、工具目录 JSON 308140 字节，因此短模式的共同输入边界冻结为 600000 字节，包含完整目录；实际 token 用量另行记录，字节上限不冒充 tokenizer 计数。

AgentScope 沿用项目当前的 Maven 二进制依赖，本轮未修改或重新打包其 SDK；这不否认此前已有的 SDK 定制。PI 沿用官方发布包，本轮没有修改 PI 包或 Codex 内核源码。Codex 仍使用既定 commit `e742db9f7ba98e63e502b310857d49d047888518` 作为 Rust 库构建，备选 durable-harness 集成方案不在本次切换。

## 恢复规则

失败保留独占租约与所有请求证据。必须核对 Session/Turn 终态、工具任务及网关未完成请求，再关闭 case、释放同一 owner 的租约。不能删除租约数据库、抢锁、选择最好一次结果或改评分。可确认安全的环境/适配器修复使用新的批次标识重跑，原尝试保留。

## 已完成的本地验证

根目录 Java `clean verify`（1583 项，失败/错误为零，387 项按条件跳过）、Spotless 通过；显式 PI HTTP 集成 53 项通过。TypeScript 构建及 83 项测试通过。Rust workspace 36 项测试通过，包含原生压缩记账、短模式禁用压缩、长/空/错误工具结果、串行恢复和跨进程接续；5650 个上游 Rust 源文件与校验过的原始归档逐字一致。Python 49 项回归和三种 Helm 渲染通过。协议检查包含 117 正例、50 反例及规范编码/身份向量。

构建中的 AgentScope 远程流测试曾出现本地 HTTP 提交确认超时，失败日志保留；专项重跑及最终完整 clean verify 均通过，未跳过测试或放宽时限。真实 Session 预检发现公共协议过滤器未放行预算 API，已补充显式 `session-budgets-v1` 扩展和带过滤器的 HTTP 回归。首次 Tool Worker 镜像误选普通 JAR，被启动检查发现，已改为可执行 classifier 并新增制品入口校验；该失败镜像不进入最终验收。Codex 自定义镜像层还修复了本机复制导致的可执行位丢失，归档显式使用 0755，并核对运行容器的二进制 SHA256 与可执行性。

真实验收还发现两个联动问题：合成 MCP/τ 后端使用 `/mcp`，AppWorld 使用 `/mcp/`；代理现按各自规范地址访问，重定向计为基础设施失败。恢复预算耗尽原先阻止取消取得执行权；现在取消清理不消耗推理/恢复预算，且已无未完成工具的 Java Turn 可在初始化 MCP/模型前结束。新增取消与预算专项回归 13 项通过，旧失败案例保留用于部署后验证。

真实请求审计进一步发现 AgentScope 的 platform 工具绕过普通 allowlist，技能中间件还会在调用时重新注册 loader。现通过 SDK 公开开关和严格 MCP allowlist 实现 `evaluation.toolSurface=mcp-only`，两次实际模型调用回归确保这些工具不会重现；网关同时与当前 case 的原始 MCP 工具名核对，不能由首个 Harness 建立错误能力基线。MCP 代理使用 HTTP/1.1 长连接及无正文 405，正确支持 SDK 初始化与可选 SSE 探测。

原生辅助摘要允许选择低于公共上限的输出 token 数，网关仅在 native 模式放行该缩减，仍拒绝超过共同上限的请求。PI 通过公开 payload hook 去掉 Responses SDK 默认的 `reasoning.summary=auto`，真实 HTTP 回归核对最终序列化的 reasoning 对象。

Codex 原生工具 schema 转换会丢弃 `minimum`、`maximum` 等约束；适配器现于实际模型请求边界恢复封存的完整 MCP schema，预算账本与供应商收到同一请求。真实 HTTP 回归覆盖数值边界、字符串 pattern/minLength/default。网关直接比较原始 MCP schema/描述哈希，避免首个 Harness 的转换结果成为错误基线。该修正在本项目适配层完成，未修改 Codex 内核。

短模式还显式调用 AgentScope 公开的 `disableCompaction()` 和 `disableToolResultEviction()`；90 KB 工具结果跨两轮仍完整到达模型的回归验证该行为。最终构建步骤还会重新应用短模式策略，防止 Agent/JEV/子配置通过后续 builder setter 再次开启压缩或结果转存；回归先主动重开再验证最终策略覆盖。

验收 Runner 对取消竞态作了明确处理：只有取消返回 409 且随后查询同一个 Turn 确认已进入终态，才记录 `already_terminal`；运行中冲突与网络错误仍保留为失败。该修复不增加模型请求、不重试业务工具、不改变预算。

官方 τ 首次 smoke 的一个模型连接在 180 秒超时后已关闭，旧网关仍将其保留为 pending 并导致错误归因。现明确区分上游模型传输失败与下游传输失败，始终保存截止时间、阶段和已收到的字节/事件信息；未知 usage 不记为零。旧记录仅允许持有原 owner 的可信恢复器在确认具体 Turn 终态后结算“已经结束的模型传输”；工具请求或仍在执行的请求不能走该路径。真实 HTTP 回归覆盖等待响应头和 SSE 中途超时，保留初始失败批次，不重放原模型调用或工具。

AppWorld 全量目录的 JavaScript 往返检查还发现 23 个 schema 存在数值拼写差异，其中 10 个含超出 JavaScript 精确整数范围的默认值。网关按十进制数值语义比较 `0`/`0.0` 等表示；仅在其余结构完全一致且属于 binary64 精度丢失时，从官方 MCP 目录恢复原始精确整数后再发给模型。记录修改路径、入站请求哈希及实际出站哈希，费用/预算继续使用实际响应 usage。真实 HTTP 回归证明供应商收到原始整数，缺失约束仍被拒绝；469 个工具往返修复后的语义差异为零。未修改官方工具、评分器或 PI 包。

τ 第二批暴露了平台执行权缺陷：旧 MQ 恢复扫描器把仍在续租的 HTTP 执行当作失联，导致 PI 任务中断。初始执行和恢复执行现都核对 HTTP 租约，并在 Session 行锁内复查后再撤销旧执行，避免续租竞态；兼容尚未初始化 HTTP 协议表的部署。40 项恢复专项回归通过，新增 75 秒模型等待验收，检查跨越 60 秒租约后仍只有一次执行尝试和模型请求。这是平台修复，不属于 PI 框架限制。

完整 469 工具目录另经集群内供应商预检，qwen3.8-max 返回 HTTP 200/completed，输入 74618 token。该诊断不执行业务工具，不计入官方任务成绩；没有为某个 Harness 裁剪工具目录。

AppWorld 首次 smoke 在供应商调用前拒绝了 AgentScope 的 125 个 schema 差异，均由 SDK Map 转换丢失显式空值造成。模型适配层现通过 SDK 公开 JSON 值类型保留完整 schema，不修改 SDK；真实 HTTP 回归验证 `default: null`、嵌套 `const: null` 与大整数。另将 AppWorld 对象及官方评分器放在独立进程，避免其模拟时钟污染 Runner 的真实截止时间、轮询和耗时。失败时停止本地控制进程，不自动清理远端任务状态。三项进程回归和集群实际时钟隔离检查通过；旧批次的负耗时保留并标记无效，不能用于性能比较。

AppWorld 官方任务按文件名导入 `evaluation.py`，会与 Runner 同名模块冲突。评分子进程现临时隔离该模块缓存，以及普通目录和 ConfigMap 文件链接对应的导入路径，评分结束后恢复；未修改官方评分器。新增模块冲突和符号链接回归。受影响的已完成任务直接以原版 `evaluate_task` 对已保存终态补评分成功，没有重放模型或工具；该记录单独保留，最终三套任务重新验证全自动评分链路。

AppWorld 共享 API 进程的官方数据库清理未清除模型哈希缓存，曾导致下一 Harness 在 Session 创建前被初始指纹校验拦截。现以官方应用工厂启动 API，增加不进入 OpenAPI/MCP 目录的私有重置入口；必须持有控制凭据和当前环境 owner，且不存在未关闭 case 或 pending 请求。重置调用官方数据库和模型哈希缓存清理方法，不改业务数据、工具或评分。四项回归覆盖错误凭据、错误/缺失租约、活动/中断 case、未完成请求及完成后的安全重置。

资源统计仅按 Linux 文档定义的累计指标计算增量和重置，ACS 扩展字段（如 `current_bw`）保存前后快照，不假定其单调递增。原统计曾误标 13 个重置，修正后使用同一批原始采样得到零个；原采样保留，未重跑任务。新增回归同时验证扩展字段下降不误报、真实累计计数下降仍报重置。依据：[cgroup v1 CPU 统计](https://docs.kernel.org/scheduler/sched-bwc.html#statistics)、[cgroup v2](https://docs.kernel.org/admin-guide/cgroup-v2.html)。

## 保留差异与适用边界

| 类型 | 仍存在的差异 | 处理方式 |
| --- | --- | --- |
| Harness 原生行为 | 规划循环、必要协议提示、原生历史结构、摘要算法及 token 估算不同。 | 短模式关闭压缩/裁剪；native 模式保留策略，所有辅助请求共用预算并审计历史变化，不声称算法相同。 |
| Codex 原生协议 | 动态工具使用 `hands_` 前缀，内部保留自己的消息/调用 ID。 | 名称映射可逆；在模型边界恢复原始 schema，并核对工具结果的业务内容与调用身份。 |
| 恢复调度 | Worker 重新接管的等待、原生异常终止和清理时机不同；部分故障需要等待 60 秒执行租约。 | 统一总时限和恢复次数，记录真实耗时，不为某个实现额外延长预算。 |
| 本轮覆盖范围 | 当前是文本 MCP 评测；未统一图像、音频或交互式桌面输入。 | 不从本轮结果推断多模态能力；这属于另一个评测范围，不是已证明无法适配。 |
| 外部不可控项 | Qwen 模型别名可变，供应商随机性、缓存及服务负载不保证一致。 | 冻结可控参数，保存实际返回模型与 token/cache 用量，轮换顺序、多次正式采样；无可信费率时不报告金额。 |
| 性能测量范围 | Control Plane 的 distroless 容器缺少可直接读取 cgroup 的命令；故障测试主动替换 Pod。 | 缺失/重置计数明确标注，不能把不可观测当成“无限流”，也不把冒烟耗时当性能排名。 |

## 集群验收证据

三个 namespace 的最终镜像已经部署；21 项真实 Session 验收全部通过。每个 Harness 均完成长调用租约续期、普通文本、工具结果、跨 Turn 共享预算、429、断流和非幂等工具执行中重启七项检查。三次重启各只产生一次写入；75 秒模型等待均只有一次执行尝试、一次模型请求、零异常恢复。运行容器的 26 个制品哈希与发布清单一致。最终批次为 AgentScope `consistency-acceptance-nullfix-agentscope-20261101-01`，PI/Codex `consistency-acceptance-leasefix-{pi,codex}-20261101-01`；PI/Codex 对应部署未改变；旧批次保留，不合并择优。

[脱敏验收证据](reports/consistency-20261101/acceptance.json)和[发布清单](reports/consistency-20261101/release.json)已导出。官方 τ-bench 和 AppWorld 各三个 smoke 用例均已在集群 Runner 完成，三套各得分 1；六个用例的初始状态、业务定义及请求/预算账本已核验，租约均已释放。完整评分、资源观测与历史失败记录见[交付报告](reports/hangzhou-consistency-20261101.md)。这些是少量验收样本，不构成能力排名或正式 360 次评测。
