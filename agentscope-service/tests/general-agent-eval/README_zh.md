# 通用 Agent 效果评测

通过已部署的 Managed Agents Session API 运行 AppWorld 和原版 τ-bench。Harness 负责推理与工具调度；可信 Runner 负责模拟用户、环境重置和官方评分。原始优先级表见[改造计划](consistency-plan.md)，实施与验收证据见[实施记录](consistency-implementation.md)。

2026-11-01 杭州交付：三套共 21 项工程验收通过，τ-bench / AppWorld 六个官方 smoke 用例均得分 1；详见[最终交付报告](reports/hangzhou-consistency-20261101.md)。

## 2026-11-03 原生上下文新批次

新批次使用 [native 单次配置](evaluation-native-single-profile.json) 和 [真实窗口模型配置](../../deploy/profiles/qwen38-native-eval.json)，不覆盖旧冻结配置。三种 Harness 均开启原生上下文管理，模型窗口 1,000,000、模型最大输出能力 131,072，公共单请求输出预算仍为 8,192；规格来源为[官方模型说明](https://help.aliyun.com/zh/model-studio/qwen3-8-max)。不设置统一压缩阈值，内部压缩、裁剪与历史保留仍由框架决定。

首轮每题每 Harness 一次；失败排查后最多考虑一次独立补跑，保留首轮分数。AppWorld 新批次预设 1,800 秒、120 次模型/工具调用、1,600 万累计 token 上限，用于覆盖旧 900 秒基线中的长链执行；辅助调用计入预算。先以独立旧题冒烟确认链路，正式批次启动前冻结这些预算，运行中不因失败调整。

`run_appworld.py --split dev --task-list /private/new-tasks.json` 支持显式 JSON ID 数组；保留输入顺序并拒绝空、重复、跨 split 或超出范围的题单，不能同时传入非默认 offset/count。预检严格核对被测模型绑定，同时记录完整模型目录 hash 和额外绑定数量，允许保留其他工作使用的模型别名。

## 冻结输入

| 项目 | 版本 / 契约 |
| --- | --- |
| 原版 τ-bench | `59a200c6d575d595120f1cb70fea53cef0632f6b`，retail/test |
| AppWorld | `42b5bcf3cd334fee33f0c37c02070a9f5807add5`，官方 HTTP MCP/content_only |
| AppWorld 数据 | 0.2.0/dev；SHA256 `c9299e6cafe92bce4592a3c117c047c973d1554a667c21dd81537e78ab2f532e` |
| Python / MCP | 两套独立 Python 3.11 环境；MCP 1.30.0 |
| AppWorld 兼容依赖 | Pydantic 2.12.3，FastAPI 0.119.1，Starlette 0.48.0，sse-starlette 3.0.3，SQLAlchemy 2.0.44，SQLModel 0.0.27 |
| 被测模型 | [公共模型配置](../../deploy/profiles/qwen38-eval.json)：qwen3.8-max，OpenAI Responses |
| 模拟用户 | qwen-plus，temperature 0，关闭客户端重试；记录参数、提示词哈希、返回模型名称及用量 |
| 重复采样 | 冒烟 1 次，正式 3 次；按 task/repeat 轮换目标顺序，保留全部尝试 |

requirements 锁文件和 `bootstrap_environment.py` 安装固定公开源码、校验 Git LFS 对象和数据包，并导出实际安装版本。模型别名仍可变化；返回的名称不能固定供应商权重。不承诺固定 seed 或完全确定性。

## 环境和隔离

使用一块 40 GiB ESSD PVC 保存数据、独立虚拟环境、SQLite 租约/审计及结果。环境 Deployment 包含两个容器，每个 2 CPU/4 GiB，分别运行服务和 Runner。只有 Runner 挂载平台、模拟用户凭据；服务只取得工具/控制接口密钥。临时 Linux 构建 Pod 使用 8 CPU/16 GiB 和独立 80 GiB 缓存盘，与工具环境分开。

`supervise_environment.py` 启动官方 AppWorld API（9000）、官方 MCP（10000）、τ MCP/控制器（8000/8001）、合成验收 MCP（8003）和审计网关（8002）。仅审计网关面向 Agent 暴露；每题独立 MCP URL 和持久化租约防止迟到调用进入下一题。环境重启会隔离旧 case；未知外部结果阻止槽位复用。

先在已挂载 PVC 的环境中提供 `/eval/uv` Linux 可执行文件，执行 `bootstrap_environment.py --root /eval`，再运行 `deploy_environment.py`。前置资源为 namespace、PVC 和固定摘要的初始化镜像。私有配置保存在 Git 外，权限 0600，包含：

- `targets`：名称、namespace、Session `cluster_url`、认证 headers。
- `simulator_base`、`simulator_key`、`control_key`、`tool_key`、`tau_commit`、`appworld_commit`。
- `control_url`：Runner 可访问的 8001 控制接口。
- `evaluation_gateway_url`：Runner 可访问的 8002 审计接口。
- `evaluation_gateway_cluster_base`：全部 Harness/Tool Worker 可访问的集群 Service 地址。

同 Pod 的 Runner 使用 localhost 控制/审计地址；本地 Runner 使用端口转发，AppWorld API 还需转发到本机 9000 端口，并安装同版本数据及依赖。Agent 的 MCP URL 始终使用集群 Service 和独立 trial 路径。AppWorld API 控制接口不能作为工具暴露，平台和模型凭据不能写入共享 PVC。

## 部署与预检

`apply_model_profile.py` 应用公共模型/评测契约及验证后的镜像清单，`--overlay-dir` 导出后续 Helm 升级使用的等价配置。Chart 支持各组件以 `image` 指定完整摘要引用，并支持可选 `evaluation.contextMode` / `evaluation.toolEnvelope` / `evaluation.toolSurface`。升级时保留原安装的基础设施、凭据配置。

```bash
python apply_model_profile.py --kubeconfig /private/kubeconfig \
  --profile ../../deploy/profiles/qwen38-eval.json --evaluation-profile evaluation-profile.json \
  --config /private/runner.json --images /private/release.json \
  --namespaces agencore-ma agencore-ma-pi agencore-ma-codex --overlay-dir /results/helm-values

python preflight.py --kubeconfig /private/kubeconfig --config /private/runner.json \
  --model-profile ../../deploy/profiles/qwen38-eval.json --evaluation-profile evaluation-profile.json \
  --release-manifest /private/release.json --output /results/preflight.json
```

预检拒绝缺失组件、模型目录漂移、审计策略哈希不符、原生控制项/副本/资源/镜像摘要不符、mock 模型和未就绪部署。配置变化后使用新 Agent/Session。每批必须关联最近一小时内通过、且与本批一致的预检结果。

```bash
python acceptance.py --kubeconfig /private/kubeconfig --config /private/runner.json \
  --model-profile ../../deploy/profiles/qwen38-eval.json --profile evaluation-profile.json \
  --output /results/acceptance-new-batch
```

验收使用真实 Session 和合成工具：普通文本、嵌套/长文本/空值/错误/溢出的 MCP 返回、跨 Turn 模型预算、429、断流、非幂等工具执行期间重启 Harness Pod，以及模型请求等待 75 秒期间持续持有 HTTP 执行权。最后一项要求模型调用与执行尝试均为一次、异常恢复为零，覆盖旧 MQ 恢复扫描器与 HTTP 租约续期的竞争。延迟注入只允许合成 fixture，不用于官方数据集。保留每次调用身份和尝试。重启验收需要 Kubernetes 访问，可以在本地通过端口转发运行控制脚本。

## 数据集批次

```bash
python run_tau.py --config /private/runner.json --model-profile ../../deploy/profiles/qwen38-eval.json \
  --evaluation-profile evaluation-profile.json --preflight /results/preflight.json \
  --output /results/tau-new-batch --tasks 0 1 2 3 4

python run_appworld.py --config /private/runner.json --model-profile ../../deploy/profiles/qwen38-eval.json \
  --evaluation-profile evaluation-profile.json --preflight /results/preflight.json --root /eval/appworld-root \
  --output /results/appworld-new-batch --count 5

python report.py /results/tau-new-batch
```

`--formal` 使用 profile 中冻结的正式重复次数，显式 `--repeats` 必须与所选模式一致。输出目录必须新建，重跑使用独立批次。manifest 封存题目、顺序及定义。AppWorld 校验基础 DB 和保存后的运行态，τ 使用官方初始数据哈希。只有官方 evaluator 产生 reward。异常或状态不明确的 case 会停止复用该槽位，保留租约、证据及取消回执。

已确认终态并安全释放环境后，可用 `continue_batch.py` 运行原计划尚未尝试的后缀。它验证原始源码哈希、各分片退出记录、相同 manifest、已尝试项是原顺序的唯一前缀以及没有漏记的 trial，再调用原冻结 Runner。失败项也属于已尝试项，不会重跑；续跑结果写入新的 `tau-part-NNN` / `appworld-part-NNN` 分片，保留旧文件、原始得分及独立 provenance。它不会自动取消任务、释放租约或复位未知业务状态；这些必须先确认并保存恢复证据。

`recover_appworld_case.py` 是独立编排辅助程序，仅适用于批次 `formal-business-20261101-182203`。它只识别已观测的 Codex `usage_unavailable` 情形：精确 Turn 已取消、Session 全部终态、请求均结束、供应商响应流与用量完整，且平台用量和事件差异吻合。持有续跑锁后核验冻结顺序/代码与原租约归属，保存证据及 Session 事件，关闭该 case 的审计入口，导出全部最终业务数据库，验证原结果未变，再释放原租约。它不重跑失败项、不补记用量、不复位状态、不赋分；未知情形或中途中断均停止等待核查。私有本地控制器先完成归档，再重新预检并启动未尝试后缀，仍依赖本地机器在线。这只减少恢复空档，保持本批串行条件。

用户明确授权并行切换后，`parallel_batch.py` 仅把尚未尝试的后缀分给每个 Harness 的独立通道，保留各自的题目/重复顺序。每路独享 AppWorld API/MCP、审计数据库、租约数据库和 PVC，模型目录指向自己的网关，复用冻结 Runner 和官方评分器。`drain_serial_batch.py` 监听完整结果记录，短暂冻结调度器以核验空闲边界；若已有下一条尝试则立即恢复。仅当最后一个 Session 已终态、没有漏记尝试和活动/在途请求时，才终止空闲调度器并释放租约。原结果全部保留，不取消 Session/工具；串行退出码 `-15` 记录为主动调度交接。并行分片独立留存 provenance，最终按唯一计划项合并，并披露两种执行阶段。

`deploy_environment.py --seed-url ... --seed-sha256 ...` 可通过 `seed_environment.py`，以可信依赖/数据归档初始化空的通道 PVC。归档只包含固定版本 Python 环境、源码和不可变任务数据，不含凭据、审计/租约数据库或结果；可选初始化器不会覆盖已有环境。ConfigMap 使用 server-side apply，避免客户端注解大小限制。

AppWorld 对象、远端状态导出和官方评分器运行在独立子进程；其任务模拟时钟不能影响 Runner 的真实截止时间和耗时。初始化及评分 RPC 有独立控制超时。失败时只终止本地控制进程，保留远端状态和环境租约，供确认终态后恢复。

每个 AppWorld case 初始化前，Runner 调用 `appworld_api.py` 的私有重置入口，同时清理远端数据库连接和模型哈希缓存。入口不进入 MCP 目录，必须持有控制凭据和当前租约；未关闭 case 或 pending 请求会拒绝重置。完整初始指纹继续校验，不能通过删除缓存文件指纹绕过一致性检查。

批次前后运行 `metrics.py`，指定 `--kubeconfig`、`--config`、`--output`，后一次加 `--before`。记录 cgroup CPU 限流、内存/OOM 和 Pod 重启证据。Pod 被替换或计数不可读取时，不能断言没有限流；计数也包含该时段同工作负载的其他活动。

## 结果解释

`evaluation-profile.json` 是短上下文基线：显式关闭压缩，使用共同文本 JSON 工具信封、600000 字节输入门槛和 65536 字节工具结果上限。两种模式均设置 `evaluation.toolSurface=mcp-only`，关闭原生子代理、技能和其他隐式工具。模型工具先与本 case 的 MCP 目录核对，再跨 Harness 比较 schema，首个 Harness 也不能把额外能力建立为基线。结果溢出返回可观察的工具错误，不静默截断结构化字段。触发上下文门槛的样本标记为超出该模式范围。原生工具名称可做可逆映射；审计发生在 SDK 格式化之后，检查实际 schema 和完整业务政策是否保留。

`evaluation-native-profile.json` 在同等外部预算下保留原生压缩/裁剪。Codex 原生本地摘要路径独立于推理登记和计费，历史仍写入原生 checkpoint。网关单独标记不带工具的原生辅助请求。压缩算法、提示词、保留历史和 token 估算属于框架行为，应比较其效果，不能宣称完全相同。文本信封模式不代表多模态能力等价。

报告保留全部已尝试失败、计划但未运行项、三者共同有效样本、端到端/有效评分成功率、Wilson 区间、错误率、预算/供应商/模拟用户用量、重复工具决策及耗时。网关实时转发 SSE，记录供应商首字节、首文本及完整响应时长；这不等于控制台渲染时间。服务端 Turn 生命周期时间单独记录，客户端观测间隔为 2 秒，不能精确分离投影服务耗时。供应商用量未知时保留未知，token 预算最多可能被一次响应超过。SDK 价格零值是占位，无真实计价或账单时不输出费用。

`report.py` 同时生成 `cases.md`，逐题并排展示各 Harness 按重复序号排列的官方评分。`report.json` 的 `tasks` 保留每次 trial/Session、状态、耗时和预算用量；执行失败与未运行项的官方评分为 `null`，不能当成官方 0 分。`repeat_reliability` 单独统计全部重复均有评分的任务中，全部重复都成功的比例，并列出完整评分覆盖数；它不是 `pass@k`。重复尝试和同一任务模板的变体存在相关性，试次级 Wilson 区间只作描述，不能据此直接宣称 Harness 显著优胜。

`model_usage_coverage` 按 trial 和 Harness 列出已审计模型请求、缺失用量、不完整响应流及缺失审计的试次。覆盖不完整时，已观测 token 标记为下界。保存的预算快照（包括 `in_flight_calls`）表示记录时的状态，不能当成当前在途请求数。

逐次 `failure_category` 与各 Harness 的 `failure_categories` 区分官方任务失败、供应商错误、评测等待超时及模型用量不可用。这些分类只描述已记录的结果，不推断根因；用量不可用的拒绝不表示数值预算已耗尽，执行失败仍无官方评分。

历史 qwen-plus、不同 profile、冒烟重跑和原生上下文结果不能混入同一排名。原版 τ 为历史任务，不能和 τ²/τ³ 混算。少量冒烟不能替代计划中的 360 次正式统计比较。

`merge_parallel_results.py` 对已完成的私有归档进行离线校验：封存的串行前缀、每路有序后缀、唯一 trial 标识、冻结代码与配置清单，以及分片来源记录。它要求完整的 360 项计划，保留全部未评分失败。生成的报告包含每次运行的阶段、开始时间、供应商用量、分阶段汇总，以及初始状态、定义、任务和工具目录哈希的比较，缺失证据单独列出。该程序不启动执行或调用评分器。仅在各路进程终止、请求和租约均结束后运行；私有归档控制器负责实时检查并备份官方实验输出和 SQLite。只有生成的报告文件可以公开，`results.private.jsonl` 和原始归档保留在私有目录。

`export_session_trajectories.py` 另外把已终止 Session 的完整历史与每个 Turn 输出保存到私有归档。Session 历史使用 `next_page` / `page`，Turn 输出使用 `has_more` / `next_cursor` / `after`；恢复和导出兼容这两种分页契约。遇到未终止 Turn、原记录中的 Turn 缺失或重复历史事件时停止；Session 创建前发生的失败单独标记。这些操作只读取证据，不改变评分或重放请求。

私有并行归档程序保留完整压缩包，仅解压合并所需的冻结代码、清单、分片来源及结果记录。每份完整 Session 轨迹直接从归档内计算哈希并核对导出回执；原始数据库和重复的逐次文件仍保留在压缩包中，以减少本地磁盘占用。轨迹缺失、多出、重复或内容改变都会使校验停止。

## 本地检查

```bash
python3 -m unittest discover -s managed-agents/tests/general-agent-eval -p 'test_*.py'
```

Java、TypeScript、Rust、HTTP 集成、Helm 渲染和协议验证的实际结果记录在实施文档中。公开报告不包含凭据或原始模型提示词；私有轨迹保留官方评测证据。
