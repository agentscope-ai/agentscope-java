# Managed Agents 通用 Agent 评测实施计划

更新：2026-10-26。状态：实施计划，适配器尚未实现；上海资源状态以 2026-10-24 验收记录为基础，实施前需复检。[English](general-agent-evaluation-plan.en.md)

## 1. 目标与首版范围

评测 Managed Agents Harness 的任务规划、工具使用、多轮交互、状态保持和执行可靠性，coding 不作为首版重点。先固定模型与工具环境，再比较 Harness 配置或版本，避免把模型差异归因于 Harness。

| 优先级 | 评测集 | 主要能力 | 首版建议 |
| --- | --- | --- | --- |
| P0 | [AppWorld](https://github.com/StonyBrookNLP/appworld) | 跨应用、多步骤、有状态任务 | 从 dev 中固定 20 个任务跑通，再扩展 |
| P0 | [τ-bench](https://github.com/sierra-research/tau-bench) | 多轮用户交互、业务工具、规则遵循 | retail 固定 20 题，之后增加 airline |
| P1 | [GAIA](https://huggingface.co/datasets/gaia-benchmark/GAIA) | 搜索、文件理解、跨工具综合推理 | 从公开答案的验证子集中选 20 题，记录等级与所需模态 |
| P2 | [WebArena](https://github.com/web-arena-x/webarena) | 浏览器操作、网站业务流程 | 环境复位稳定后再接入 |
| 补充 | [BFCL](https://gorilla.cs.berkeley.edu/leaderboard.html) | 工具选择、参数及多轮工具调用 | 用作工具协议回归，不代替完整任务评测 |

上述数量是内部试运行规模，不是官方完整榜单成绩。SWE-bench 暂不进入首版。
AppWorld 同时有函数调用和交互式代码执行方式；首版优先函数调用/MCP，明确记录所选 track，不能将不同执行方式混报。
τ-bench 固定原仓库 commit；τ²/τ³ 等后续版本作为独立评测集，不随上游 main 自动升级。

## 2. 总体接入方式

```text
Eval Runner ──Session API──> Gateway / Agent Service ──> Agent Harness Runtime
     │                                                        │
     │ 创建、复位、评分                              经服务端工具执行路径调用
     ▼                                                        ▼
Benchmark Adapter <────────────────────────────────── Tool Worker / MCP
     │
     └── 每个 trial 独立的应用数据库、文件、浏览器状态

官方 Evaluator ──读取最终环境状态与输出──> 评分、轨迹和产物归档至 OSS
```

- 一次 trial = 一个 task 的一次独立执行，使用新 Session 和干净环境；同一 τ-bench 对话的多轮消息复用该 Session。
- Runner 管任务调度、模拟用户、超时、评分和清理；Agent 的推理与工具决策必须经过被测 Harness。
- Adapter 保留官方工具名称、参数、返回值、错误语义与环境状态转移。优先官方 MCP，缺少时实现薄封装。
- Environment 在 Session 创建前配置。环境服务地址和 trial 绑定由服务端校验，不能仅依赖模型传入 task_id 路由，防止串题。
- 标准答案、隐藏任务信息、评测代码和模拟用户私有指令只提供给 Runner/Evaluator，不挂载给被测 Agent。

## 3. 各评测集的工作环境

| 评测集 | 需要部署/定制的环境 | 隔离与评分 |
| --- | --- | --- |
| AppWorld | 固定版本 Python/官方依赖、任务数据、每题独立 AppWorld 实例；接入官方 MCP/函数工具。若另测代码 track，使用其有状态执行环境，不能用普通 shell 替代 | 每次恢复任务初始数据库与执行状态；官方 evaluator 检查任务完成情况及状态约束，保留原始评分 |
| τ-bench | Python 环境、retail/airline 数据及业务 policy、工具服务、独立 LLM user simulator；将 policy 注入 Agent，私有用户目标交给 simulator | 工具执行和 simulator 必须连接同一 trial 环境；每轮助手回复反馈给 simulator，由官方环境决定交互与终止；按官方 evaluator 评分 |
| GAIA | 搜索服务、浏览器、文件读取、Python；按选题补齐 PDF/表格、图像或音频工具；附件预装到隔离工作目录 | 只对选定且具备对应模态支持的题集报告成绩，列出未覆盖范围；按官方答案规则评分，不用主观 LLM 打分替代 |
| WebArena | 官方自托管网站及数据库、登录状态、Playwright/Chromium 工具服务；正确配置站点 URL | 独立浏览器 context 不能隔离共享数据库写入；每个并发 worker 使用独立站点栈或按组串行复位，使用官方 evaluator |
| BFCL | 固定版本任务、工具 schema 和需要的执行环境；采集实际工具调用轨迹 | 按对应类别使用官方判定；记录适配差异，不能仅检查最终自然语言回答 |

AppWorld、τ-bench 的核心业务数据应保持在各自官方环境中，不迁入平台 PostgreSQL。
GAIA 按数据集访问条件获取，数据与答案私有保存；联网搜索和网页变化会影响复现，应记录访问时间、工具版本和可保存的证据。
镜像提前同步到可访问仓库并固定 digest。ACS 若不支持某个官方环境要求的特权容器、嵌套 Docker 或系统能力，将该环境放到同 VPC 的独立 ECS；先做兼容性验证，再决定新增资源。

## 4. Session API 执行流程

以下按仓库当前 [Session API 协议](../../docs/v2/en/service/claude-session-api.md) 设计，先核对实际部署版本和 capabilities；下述流程不是现成可执行脚本。

1. `adapter.prepare(task, trial)`：恢复环境、准备附件和 policy，返回环境绑定及任务输入。
2. `POST /v1/sessions`：指定固定 Agent 版本及 `environment_id`；metadata 记录 run/task/trial 标识。
3. `POST /v1/sessions/{session}/events`：提交 `user.message`，显式使用 `new_turn` 和持久保存的 `Idempotency-Key`。
4. 轮询 Turn 状态，完成后分页读取 `/turns/{turn}/output`。HTTP 200 仅表示输入受理；SSE 仅辅助观察，不能把断流当作任务结束。
5. τ-bench 将助手输出交给官方交互流程中的 user simulator，再把下一条用户消息提交到同一 Session；一个 Turn 完成不等于整道题结束。
6. 官方交互终止或达到预设预算后，停止后续动作并确认在途工具已结束；读取最终环境状态，运行官方评分器。
7. 保存输出、工具轨迹、评分、错误和产物；释放 trial 环境。不能假设存在 Session 删除或文件上传 API。

当前协议不支持客户端工具结果回填，因此不能在 Runner 截获 tool call 后通过 Session API 回传结果。评测工具必须接入服务端工具/MCP 执行路径，具体支持项实施前验证。
超时通过 `user.interrupt` 指定 turn_id 取消，并等待实际终态及环境静止；取消受理不代表执行已停止。响应不确定时使用原请求体与原幂等键重试，不能盲目重建 Turn。

## 5. 上海部署与待办

已有资源记录：ACS、PostgreSQL、Redis、非 Serverless RocketMQ（Lite 标签及 6 组 Topic/消费组）、OSS、NAT、SLS。
平台命名空间为 `managed-agents`，评测命名空间为 `managed-agents-eval`。评测应用状态与平台数据库分离。

- [ ] 复检云资源、配额、凭据和公网/内网连通性；Kubernetes API Server 端点用于集群管理，**不是 Session API 地址**。
- [ ] 完成已有 ACR 的独立命名空间和拉取凭据。已决定复用 `sca-registry.cn-hangzhou.cr.aliyuncs.com`，计划命名空间 `managed-agents-eval-shanghai`；此前因缺少所属账号管理权限尚未创建。
- [ ] 选定平台代码/镜像版本，部署 Gateway、Control Plane、Agent Service、Agent Harness Runtime、Tool Worker；完成版本对应的表迁移及业务账号权限。
- [ ] 配置模型、工具/MCP、应用 API key、Session API 地址及访问范围；平台密钥不暴露给 benchmark 环境。
- [ ] 验证 RocketMQ Lite 实际收发，以及一条 Session 的创建、工具调用、输出、取消流程。
- [ ] 为 AppWorld/τ-bench 构建版本固定的环境镜像，配置环境服务、临时目录和每题复位；并发先从 1 开始，再提升到 2–4。
- [ ] Runner 使用独立 Job/进程；产物写入评测 OSS Bucket，包含 run/task/trial 路径。对环境设置 TTL 和失败后的兜底清理。

不能只通过修改平台业务容器镜像来适配所有 benchmark；重点是独立的环境服务、工具适配及生命周期管理。

## 6. 指标、交付物和实施顺序

**评分规则：** 每个数据集独立报告官方指标；另外记录任务成功率、耗时、Agent token/费用、工具调用次数、重试与恢复次数。τ-bench 的 simulator token/费用单列；多次重复按官方 `pass^k` 统计“一题多次均成功”的可靠性，不与 `pass@k` 混用。

**失败分类：** Agent/任务失败、平台执行失败、环境初始化失败、评分器失败分别统计。既报告完整任务数与端到端完成率，也报告有效执行中的任务成绩，不能静默删除失败样本。

**比较方式：** 相同模型版本、参数、工具、任务集合、初始状态和预算下，比较官方参考 Agent（可适配时）与 Managed Agents，或比较两个 Harness 版本；另设独立故障恢复实验，不混入标准 benchmark 成绩。先固定对照组合，再跑测试集。

**最小交付物：** `runner`、Session 客户端、AppWorld/τ-bench adapter、环境镜像及部署配置、官方 evaluator 包装、运行清单、逐题 JSONL 和汇总 Markdown。结果至少记录 benchmark commit/split、task/trial、seed、模型和 Harness 版本、镜像 digest、Session/Turn ID、评分、失败原因、耗时/token/费用及产物路径；配置快照剔除密钥。

| 阶段 | 实施内容 | 验收条件 |
| --- | --- | --- |
| 1 | 平台部署与统一 Runner 骨架 | 单个测试 Session 可通过服务端工具改变测试状态，输出、超时和清理可验证 |
| 2 | AppWorld、τ-bench 各 5 题串行冒烟 | 真实工具执行、官方评分、轨迹归档、逐题复位全部通过；Agent 答错也能正确归类 |
| 3 | 各固定 20 题，2–4 并发，重复 3 次 | 无跨题状态污染，失败不漏记；得到可对照的质量、可靠性和成本报告 |
| 4 | 扩大覆盖，接入 GAIA，再评估 WebArena/BFCL | 保留抽样边界；完整官方设置未满足前，不宣称官方榜单可比成绩 |

首版完成标准：使用真实 Session API 连续跑完 AppWorld 与 τ-bench 的固定题集，能解释每题的成功/失败，并能在同配置下重新运行和比较 Harness 版本。
