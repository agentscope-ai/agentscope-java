# Managed Agents 的阿里云 PTS 容量测试

在仓库主目录、当前分支执行。[English](README.md)。首轮实测结果和限制见 [10 月 22 日报告](report-20261022.md)。

## 负载与通过条件

每次创建独立 Session。`short` 只调用一次真实模型；`tool --seconds 5|30|90` 调用一次真实 MCP 长工具；
`fifo` 排队提交 T2，并向 T1 注入输入。使用专用测试 Agent、无 Sandbox 环境及唯一 `load_test_batch`，
保留无关会话和历史未决会话。先做功能探测，再按 1、2、5、10 Session/s 逐级施压；每档必须对账、排空后再升档。
容量阶段至少十分钟，通过点再持续 30–60 分钟确认。真实模型容量属于当前部署端到端结果的一部分。

HTTP 受理成功与 Session 完成成功分别统计。通过持久化事实核对 Turn 终态、已提交输出、排队延迟、端到端延迟、
活跃 Session 增长和工具执行身份；观测到的峰值并发不能直接认定为稳定并发容量。业务失败率超过 0.1%、HTTP 失败率
超过 1%、监控缺失或已受理任务超过本档最大年龄时停止施压。原生 PTS 执行器已实现这些保护；失败后不能继续升档。
升档前还须检查 Pod 重启、FIFO 违规、工具重复物理执行和 outbox 增长。

## 已验证可运行的原生 PTS 引擎

`native.py` 创建有时间边界的开放负载场景，通过响应导出串联 Session 创建与 Turn 受理。`--rps` 表示目标
**Session 到达率**：两个 API 各 1 RPS，代表 1 Session/s。原生 PTS 要求每个 API 至少 1 RPS。
脚本保存场景、引擎时间线、监控快照及报告 ID。场景文件含登录令牌，输出目录应限制访问并放在版本控制之外。

VPC 测试可创建 [临时 Gateway Service](private-gateway.yaml)，按目标集群调整 VSwitch 和 release 标签；
提供 VPC、VSwitch 和专用 PTS 安全组。保持原公网 Gateway 访问白名单。VPC 施压机准备可能需要数分钟，
准备时间不能算入实测阶段；出网须覆盖 PTS 控制通信和目标。引擎停止后清理临时 Service 和安全组。

```bash
python3 managed-agents/tests/pts/native.py \
  --base-url http://YOUR_PRIVATE_GATEWAY --agent YOUR_TEST_AGENT --environment YOUR_ENVIRONMENT \
  --token-file /private/login.json --batch pts-short-unique --scenario short --rps 1 --minutes 10 \
  --vpc YOUR_VPC --vswitch YOUR_VSWITCH --security-group YOUR_PTS_GROUP \
  --observer-url http://127.0.0.1:19108 --out /private/unique-stage
python3 managed-agents/tests/pts/analyze.py --stage /private/unique-stage \
  --kubeconfig /private/kubeconfig --scenario short --rps 1
```

`analyze.py` 分页只读查询持久化事实，以 SceneId + PlanId 获取 PTS 最终报告，区分失败终态与仍活跃 Session。
PTS 成功率为 0–1。原生 short 场景检查最终提交文本含 PTS_OK；提示要求回显 Session ID 时也检查该 ID；冒烟或缺少监控覆盖不能认证容量。
原生 FIFO 在 T2 受理后立即 inject，不能保证注入发生在工具挂起期间；此项使用 JMeter 或专用功能探测验证。

## JMeter 脚本与本地回归

`generate.py OUTPUT.jmx` 将 `session.groovy` 嵌入无外部文件依赖的 JMeter 5.6.3 计划，执行闭环 Session 创建、
受理、三秒加抖动轮询、提交输出检查、FIFO 与注入检查。实际线程数受 threads 属性约束；闭环线程数不能等同于
固定 Session 到达率。`pts.py` 支持导入、运行，必须先确认 FileList 与实际样本。

本账号的 PTS JMeter 引擎在分配期间消失，未产生样本和报告；最小原生 HTTP JMX 也复现。原生 PTS 的 VPC 模式
成功运行并产生本次测量。Groovy 脚本已通过官方本地 JMeter 回归和六个真实服务 Session 功能测试，这些不属于
云端 JMeter 容量测试。

本次 JMeter 导入需要上海 OSS 对象的直接可访问 URL，签名 URL 导入后 FileList 为空。生成的 JMX 不含凭据及
目标 ID；如需 public-read，仅临时调整该对象，运行结束恢复 private，不修改 Bucket ACL。
使用 `subresults.disable_renaming=true` 保留独立的受理/完成标签。不要公开含认证头的场景、属性、请求日志或报告快照。

```bash
python3 managed-agents/tests/pts/generate.py /private/sessions.jmx
JMETER_HOME=/path/to/apache-jmeter-5.6.3 python3 -m unittest discover -s managed-agents/tests/pts -v
```

先安装 `psycopg[binary]==3.2.13`。没有 JMETER_HOME 时明确跳过 Groovy 可执行回归，其余测试仍执行。

## 监控部署

`observer.py` 在 ClusterIP 9108 发布缓存 `/snapshot`、Prometheus `/metrics` 与 `/healthz`，每十五秒采样。
数据库连接及查询有超时，只使用只读账号。授权数据库 CONNECT、ma 与 mcp_fixture 的 USAGE，以及下列表 SELECT：
ma.turn、ma.session、ma.protocol_creation、ma.turn_resume、ma.tool_command_outbox、ma.hands_tool_result_outbox、
ma.hands_execution、ma.protocol_event、ma.public_event、ma.hands_execution_event、ma.input、ma.protocol_output、
mcp_fixture.execution，另授予 pg_read_all_stats。不得使用 owner 账号。
创建含 uri 的 managed-agents-observer Secret、由 observer.py 生成的 managed-agents-observer-source ConfigMap，
然后应用 observer.yaml。Deployment 的 AGENT_IDS、SINCE_MS 限定测试范围。固定镜像提供 Python/psycopg，启动命令
只运行监控；源码更新后更新 ConfigMap 并重启监控 Deployment。

命名空间 RBAC 只允许读取 Pod 和 Pod 指标。Runtime、Agent Service、Worker 的 Actuator metrics 需要现有内部令牌，
匿名及普通 JWT 均不能访问；监控仅引用对应 Secret key。没有新增公网 Gateway metrics 接口。已加 Prometheus 抓取
注解，接入外部监控后端属于独立配置。

指标包括 Turn 状态、活跃 Session、最老 Turn 年龄、四类 outbox、工具状态、排队及端到端分位数、PG 连接/锁等待/
死锁/事务、Pod CPU/内存/重启、Hikari 活跃/等待/上限/超时/获取时间、JVM 和 HTTP 指标。采集失败显式标记，
不能作为零值；任务年龄保护必须过滤终态。scope=all 含保留的历史会话，只能用 scope=test 决定压测保护。

`record.py` 额外记录五个 RocketMQ 消费组的 lag 查询。该 LiteTopic 实例的父 Topic lag 在实测有流量时也返回零，
最后消费时间也为零，因此不能据此证明每个 Session LiteTopic 无积压。须结合持久化 outbox 和已受理任务年龄；
记录器另对最多三个最老活跃 Session 采样对应 LiteTopic 的 readyCount；逐 Session 的完整 inflight 观测仍受限。记录器本身不产生 Session 流量。

## 活跃 Turn 与 SSE 验收

主压力接口为 `POST /v1/sessions/{id}/events`，请求体 `events[0].type=user.message`、
`managed_agents.intent=new_turn`，PTS 名称为 `turn.admit`。分别统计创建 Session 的 HTTP RPS、
user.message 受理 RPS/延迟和 Turn 成功完成数/秒。`analysis.json` 包含持久化时间计算的峰值活跃 Turn、
采样非终态分布/Turn 趋势、每个 Pod 的负载分布及 `load_timeline`。Brain/Worker 分别采样 CPU、内存、
JVM live/peak 线程和 Hikari 活跃/等待连接；旧 observer 没有采到的线程数据保持缺失。
状态指标按测试 Agent 过滤，各批次间先排空；累计终态数量不计入活跃 Turn。

使用 `GET /v1/sessions/{id}/events/stream` 增加 SSE 对照，保持相同
user.message 压力、镜像和资源。当前 Session API 从连接时 head 推送，不支持 Last-Event-ID/after，
须先收到 connected 注释帧再发受理请求。统计实际并发流、建连错误/断流、首事件与终态延迟、完整性，
同时比较吞吐、尾延迟和逐 Pod 负载；不能把不支持重放的接口描述为已验证重放。

按最新用户要求，当前验收只做三轮各十分钟、10 Session/s：短请求固定标记、每个 Session 一个
30 秒 MCP 工具、相同工具场景加 SSE。低速率、其他时长/FIFO 压力与长时确认均暂缓。
`values-10sps.yaml` 将每个 Worker 的执行并发设为 256，两个副本为预期的 300 个同时等待工具留余量；
Runtime 每副本执行并发为 256，工具内联等待为 1 秒，模型流无新事件的超时为 60 秒。
模型超时使用 SDK ExecutionConfig，不限制工具执行时长；生产默认仍为 0（关闭）。
持久化取消调用 AgentRun.cancel，避免无响应的模型流阻塞取消。
两轮工具对照保持其他资源不变并保存实际部署快照，配置值本身不代表实测容量。

工具对照部署 `sse-bridge.yaml`，ConfigMap 包含 `sse_bridge.py`/`sse.py`。
临时 VPC 内准备代理转发真实 Session 创建；无 SSE 组向 `native.py --session-create-url` 传入
`/baseline/v1/sessions`，SSE 组传入 `/sse/v1/sessions`。SSE 组先收到 `:connected` 才将
新 Session ID 返回 PTS，后续 user.message 仍由 PTS 以 10/s 直接发往正常 Gateway。
订阅器在终态及随后的 idle 帧到达后关闭，因此并发流随活跃业务变化，不提前打开数千个未来 Session。
两轮工具场景均经过同一准备代理；只有 SSE 组的创建延迟包含订阅建立，须与 user.message 延迟分开报告。
`--companion-url` 检查伴随进程状态；监控缺失、断连或重复事件 ID 会停止压测。
排空后执行 `audit_sse.py`，将从 Turn 接受开始的所有推送事件 ID 和规范化内容哈希逐条对照持久化协议账本，
结果与业务分析共同保存。单独监测伴随进程，证据落盘后删除其私网 SLB/Service/Deployment。
固定摘要的 MCP fixture 镜像已提供 Python 依赖，无需修改应用镜像。


如需单独测试持久空闲监听，可使用另一方案：`sse.py` 是使用 httpx 0.28.1 的有界伴随订阅器。
`prepare_sessions.py` 在计时窗口外创建独立 Session，
写出无表头、每行一个 ID 的 CSV；两组对照使用独立批次。`native.py --session-file-url URL
--session-file-name sessions.csv` 省略 Session 创建，逐行只用一次，因此计时请求全部为 user.message
（FIFO 另有 T2/inject）。须核对实际 PTS 样本及文件是否提前耗尽；URL 必须可供 PTS 读取，CSV 不含认证信息，
导入后恢复临时对象 private ACL。

SSE 组先执行 `sse.py --base-url TARGET --sessions /private/sessions.csv --token-file /private/login.json
--out /private/sse --max-streams COUNT --duration SECONDS`，要求 `ready.json` 显示所有流连接后才启动 PTS。
订阅器靠近目标部署，独立配置及监测资源，连接保持至施压及排空结束；完整报告活跃/空闲流数量，
因为预创建 Session 全部订阅会带来持久监听负载。无 SSE 对照须保持相同预创建数量、提示词、速率及服务配置。
任何异常 EOF/错误均判为订阅失败，不以自动重连掩盖丢事件。私有事件记录仅含 ID、类型和时间，
不含消息内容或认证；排空后将事件 ID 与协议事实对账。


如需脱敏离线图表，安装 `requirements-plot.txt`，执行 `plot.py --stage /private/stage
--out /private/stage/load.png`。图中展示非终态采样趋势和各 Brain/Worker Pod 的 CPU、内存、
连接池等待；持久化时间计算的精确峰值并发与采样峰值单独呈现。图表不含认证、消息内容或 Session ID。


JMeter 的 FIFO 挂起/注入验证使用 30 秒工具（FIFO 未显式提供时长时的默认值）。5 秒工具可能在轮询
观测到 waiting_tool 前已完成，此时脚本没有发送注入，并报“未观测到窗口”；须与已发送但丢失/拒绝的
输入、模型未遵循指令分别处理。真实发布后验证使用两个并行 30 秒工具，核对四个 FIFO Turn 成功、
两条注入已应用且出现在输出中，以及两个物理 MCP 执行及其真实 call ID。


## 可控 Model 与固定业务并发

`values-concurrency-1000.yaml` 显式启用 `loadtest:short-v1`、`loadtest:tool30-v1` 和取消探针模型。
默认部署不启用这些模型；只有专用 Agent 指定该名称才使用，其他模型选择保持原有行为。
模型实现位于 common 的 `runtime/loadtest/LoadTestModel.java`，Runtime 的 `LoadTestModels` 解析只读操作员配置。
配置首包延迟、分块间隔、分块数和最小输出量，使用 Reactor 非阻塞计时与 SDK 响应调度器；遵循执行超时和取消。
工具名称、参数模板、轮数、并行调用数由操作员配置。客户端只发送：

```text
LOADTEST {"request_id":"unique-request","marker":"unique-marker"}
```

模拟器生成正常 ToolUseBlock，工具经过实际 MQ/Worker/MCP 执行，接收原有 ToolResultBlock 后输出真实结果。
恢复决策只依赖持久化消息；稳定调用 ID 由模型配置、请求 ID、轮次和序号生成，不依赖实例计数。
辅助记忆调用不会再次派发业务工具。输出块数不等于最终 SSE 事件数；usage 和指标均明确标记 synthetic，
不代表真实模型费用、真实 Token 吞吐或外部 HTTP 格式化/传输成本。

`closed_loop.py` 维持指定数量的业务名额：发送真实 user.message 后，等待只读 SQL 确认 Turn 终态，
再创建替代 Session。共用一个异步 HTTP 客户端，每 200ms 批量观察活动 scope，避免逐 Session 状态轮询产生额外压力。
前置准备 Session 不计为在途；受理结果不确定时保留原身份和名额并停止补发，不把未知请求当作失败后无限替换。
成功终态另通过正常 output API 校验 marker 和真实 MCP call_id；实际工具执行条数/状态由只读数据库核对。
逻辑客户端名额为 1000，实际持久化非终态数会因完成检测和补发间隙略低，必须报告其分布，不能仅报线程数。

`closed-loop.yaml` 在独立施压 Pod 运行，不受本机网络中断影响；这是集群内业务闭环压测，不是 PTS 原生 RPS 报告。
配置 ConfigMap 的 BASE_URL/AGENT_ID/ENVIRONMENT_ID/BATCH/CONCURRENCY/HOLD_SECONDS，令牌放入独立 Secret。
数据库只引用观测器的只读账号；结果目录只存脱敏身份、计数和时序，证据容器仅通过集群内端口导出。
主场景 5 秒起满、保持 600 秒，停止后排空，保护覆盖数据死锁、Pod 重启、最大任务年龄、监控缺失及输出错误。
结束后取回结果并删除临时施压 Pod/ConfigMap/Secret。回归命令与本节前述 Python/Java 检查相同。

工具参数使用字面量 `{{marker}}` 引用每次请求的 marker，避免与 Spring 环境变量占位符冲突。

下载结果后用 `analyze_closed_loop.py --run RUN_DIR --facts FACTS_JSON --out analysis.json` 分析。
脚本对观察器时间戳去重，要求整个已建立保持窗口中至少 99% 的唯一采样达到目标并发的 98%，并核对监控连续性、终态、输出、真实工具执行、
新增死锁、重启及数据库连接池超时。将 analysis.json 放入 RUN_DIR 后，可用 `plot.py --stage RUN_DIR --out load.png` 绘图，
其中包含模拟模型的活跃流数和分块速率。

压测覆盖配置将 Brain 和 Agent Service 的数据库连接池设为 64，普通部署默认仍为 16。
5 秒突发起量与 60 秒起量的稳态并发结果必须分开报告。HTTP 409 仅当扩展错误码为 `request_in_progress` 时
使用原请求体和原幂等键重试；日志保留 HTTP 状态、错误码、请求 ID 和重试成本。
运行中早停门槛默认为至少 100 个终态后失败率超过 1%，最终验收仍要求成功率至少 99.9%。

闭环施压 Pod 显式使用最多 6 次原幂等键重试（CLI 默认 3 次，可通过 `--http-attempts` 调整）；每次非成功 HTTP 都保留状态和重试次数。增加重试预算不会取消死锁、超龄、输出校验及监控保护。原始 `background-errors.jsonl` 记录触发停止的原因，不能仅用最终 HTTP 200 比例评估入口质量。

[本轮模拟 Model 与 1000 Session 闭环结果](report-20261022-synthetic.md)。

- [10000 客户端快速突增测试（256 连接池，未通过）](report-20261022-10000-quick.md)

使用 `--session-queue` 可通过 `session-queue-v1` 创建 `db_queue_v1` Session，驱动会检查返回模式；此开关默认关闭。采集队列指标前需执行队列迁移，并给 observer 授予 `ma.session_queue`、`ma.session_dispatch` 的 SELECT 权限。`record.py` 现在记录含 `ma_session_ready_v1` 的六组 MQ 消费关系。保持时长验收遵循配置中的 `hold`，支持 300 秒，资源和监控保护仍然生效。

[DB 队列部署与 10000 并发压测](report-20261022-10000-session-queue.md).

### 直接提交与对象存储验收

新方案使用 `closed_loop.py --direct-store --concurrency 10000 --ramp 60 --hold 300 --tool`，创建回执必须为 `db_queue_v1` / `direct`。Session Store 嵌入 Brain，投影由 Agent Service 消费；不再需要独立 Session Store 的地址、令牌或采集端口。观察器查询 `projection_source_head` / `projection_progress`，统计水位差、阻塞及对象后端请求、缓存、容量等待指标。部署时统一配置所有读写端的 backend ID、桶和新数据空间，并记录实际 MQ Topic 覆盖值。历史报告保留原有配置，仅用于对应旧版本的结果解释。

可用独立进程运行 `durable_sampler.py --batch BATCH --since-ms START --out /results/durable-independent.jsonl --summary /results/run/summary.json`，以只读 SQL 连接、0.5 秒间隔采集该批次实际非终态 Turn 数，避免 HTTP 驱动解析输出和补充业务槽位时阻塞采样。导出后通过 `analyze_closed_loop.py --durable-samples FILE` 指定权威采样文件。查询失败、采样间隔超过一秒、有效并发不足、最终事实不完整仍判定失败；驱动原始采样仍需保留，并在报告中披露数据库规格及采样进程的变化。

[对象存储 Session Store：双实例发布与万在途验收结果（未通过）](report-20261023-10000-object-store.md)。

P2 报告以 `data-plane-p2-v1` 标识严格验收规则：不裁切保持窗口、不用重复样本增加权重，且不允许意外失败/取消的 Turn。历史报告保留原有口径，不追溯改标。

当前 10000 并发压测覆盖 `values-publication-10000.yaml` 将 Brain `concurrency` 设为 `0`，关闭执行片段准入和固定工作线程上限；接收/ACK 窗口仍为 `resumeHandoffCapacity: 64`，inline 等待为 1 秒；Brain 为 4 个实例，其他业务组件保持 2 个实例。需使用支持零值的新版本镜像；本地修改覆盖文件不会自动改变集群。`admission.execution_limit=0`、`execution_available=-1` 表示无限模式，观察 `execution_active` 和真实资源压力。

四实例部署的完整结果见 [R9 压测报告](report-20261026-data-plane-r9-brain4.md)：续接改善，但整体吞吐退步，并保留了一条首次通知需要外部唤醒的未通过验收项。
