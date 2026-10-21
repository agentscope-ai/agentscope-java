# Managed MQ / Redis 改造实施记录

## 目标和约定

工作目录：/Users/ken/agentscope-3/agentscope-java。
实施分支：codex/automatic-acr-release。
目标设计：[Managed Agents MQ 与 Redis 架构设计](../../docs/v2/zh/blogs/Managed%20Agents%20MQ%20与%20Redis%20架构设计.md)。

完整替换 Managed 主执行链路，目标包括：

- Portal 持久受理 Session、Turn、Input；首次执行按需初始化，去掉 bootstrap / ready 等待。
- RocketMQ LiteTopic 排队 Turn；挂起保留原 Turn 回调，只有完整终态保存后才完成原回调，独立 turn-resumes 恢复同一逻辑 Turn。
- Redis Stream 传递 steer、inject、reply、cancel；DB 保存原文、顺序和应用回执。
- Hands 消费运行事件，事务提交原生日志、检查点、公开事件、ToolTask 和输入回执。
- 工具结果经过事件提交后回到 Brain；恢复不能盲目重复未知副作用。
- API、SDK、Console、Managed 适配器、部署和中英文使用文档同步更新。
- 两个 Portal / Brain / Hands 副本验证顺序、接管、取消、工具、SSE 与数据恢复。

Service 尚无正式版本，直接破坏性改造；不保留旧 Managed 双实现、兼容 API 和数据转换层。
Hosted、Team、Workflow、External 执行路径不在本轮扩展范围。
Core / Harness 的公共接口、序列化状态和行为必须保持兼容，涉及修改时在本页单独登记。
保留本次开始前已经存在的所有无关改动，不提交或推送。

## 按用户要求暂停（2026-10-16）

用户确认核心流程完成后先停止，不继续深度集成测试。本轮按此要求暂停，总目标未标记全部
完成；不自动推进剩余故障矩阵、整套容器 / 客户端验收和最终全仓复验。

- 核心受理、MQ 顺序、Redis Input、Brain / Hands 长工具、挂起 / 同 Turn 恢复、取消、
  SSE、Webhook、历史恢复 / Fork 的阶段证据见下文；真实基础设施、显式测试修正版 MQ、
  确定性模型和本地进程 Sandbox 的覆盖边界保持不变，不声明真实 ACS / 付费模型认证。
- 最新生成交付物增量已通过 71 项专项和三服务构建，完整双副本交付验收没有开始。冻结 y
  的启动被主动中止，六个已启动服务进程均已退出，没有强杀剩余服务；
  `/private/tmp/managed-mq-replicas-20261016-artifacts-y/paused-process-audit-final.json` 记录逐 PID
  结果。数据库、冻结 JAR 和日志保留，不把此轮启动或专项测试写成完整集成验收通过。
- 待修改点：云 Sandbox 控制目前使用 `Sandbox.exec().stdout()`，E2B 实现会在 512 KiB
  截断 stdout。生成文件导出 / 大目录镜像若通过该通道返回 Base64 JSON，可能被截断，
  需改用不截断且有界的二进制传输；本轮没有实施该修改。不能用本地执行器的 64 MiB
  输出上限证明云端等价。正式 RocketMQ 首次 MIN 位点依赖方案也仍为待办。
- 代码仍在本页指定主目录 / 分支，没有提交或推送。本轮没有修改 Core / Harness 源码。

## 最新状态（2026-10-16，历史 Checkpoint 恢复 / Fork 双副本通过）

- 旧图删除后的全仓 Java 已于 02:06:37 通过，冻结 h 双副本验证通过并已清理；之后的
  MemoryStore / 回调退休增量已通过专项与冻结 k 双副本验收；相应全仓 clean verify
  已于 03:01:26 通过，10851 项、0 failures / errors、182 条件跳过。
- 新执行直接解析 ma 中的固定配置，预算通过 ManagedModelPolicy 发布原生事实；永久超限
  不再按传输错误重试，失败后下一 Turn 可继续。工具确认改为原生 reply，确认事实与 ASKING
  状态同批提交并离开响应式 I/O 线程执行持久化。
- 已删除 107 个旧运行组件 / 接口、12 个专属持久类型、旧 runtime-schema 与 6 个旧 SQL 文件，
  移除旧 DB 租约、调度、bootstrap / ready RPC、转发、SQL 日志后端与旧 Worker / 同步 API。
  43 个旧契约测试退休，其中本地开发安全测试已重写保留用户 JWT / 本地身份边界。
- 数据面只初始化重写的 V0001；真实 PostgreSQL 用例证明没有旧运行表，并拒绝旧迁移集合。
  新工具确认 / 预算 / JEV / Spring / 安全接线及旧图删除后的全仓通过；冻结 h 的 Brain
  含六个未加载的旧 SQL 资源，后续 clean 构建已清除，不宣称 h 与 clean 产物完全等价。
- 模型工具与 Hands 操作的公开视图已统一：操作完成仅更新嵌套操作，原生 tool/result
  才完成模型工具；恢复重放按 Turn / tool call 去重。最新 Java 专项及前端专项、lint / build
  通过。冻结 l 暴露 action/end 的宿主挂起被误投影为失败完成，已修复并强化状态断言。
- 工具来源日志、持久采集 / 消费游标、乱序缓冲、显式缺口、最终水位和有界快照预览已实现。
  冻结 m 的真实 180 秒工具通过：两个 Brain 全程退出期间、两个 Hands 被替换后仍连续采集，
  原工具执行一次，恢复 Brain 后完成原 Turn，再完成 T2，模型工具恰好一次正确完成。
  基础设施为真实 MQ / Redis / PG；模型是显式 Fake，Sandbox 是真实本地子进程，不是 ACS。
- Go 结果桥接及快照已补齐新工具事件、嵌套操作和预览上限；真实 PG HTTP 专项及全包测试
  通过，当前冻结 m 不覆盖这批 Go 增量。m 的并行 Session、跨 Portal Input / SSE / 重连、
  挂起取消与后序 Turn 回归均已通过；七个验收进程已停止，证据保留。
- 最新 Java `mvn -B -T1 clean verify` 于 04:18:40 通过，10880 项、0 failures / errors、
  182 条件跳过，耗时 22:15；使用真实 MQ / Redis / PostgreSQL，并覆盖本批工具来源与投影增量。
- 父子回调容量已接入生产代码和部署配置：根层和 16 个子深度独立消费，共 20 个静态 MQ 绑定。
  SDK 的 16 层单槽链通过；冻结 n 的两个 Brain 根池同时满载时，子层继续推进，五组实际
  父子 Harness、五个真实本地工具及根 Session 后续 Turn 全部通过。首轮五分钟观察超时的
  失败记录保留；续查同批请求完成完整核对，未重新投递业务任务。新全仓失败于传输顺序专项，见下文新增首条消息问题。
- 总体约 90%，是按完整目标的工程估算，不按测试通过数量折算。工具日志积压与组合故障、
  自动交付物发布、正式容器 / 客户端验收和最终全仓复验仍待完成；Portal 的 Hosted / External 后端
  保留各自运行协议，不凭 bootstrap 关键字删除。
- 修正版首次位点能力、61 组资源和部署初始化已验证；冻结 q 严格验收暴露收尾恢复缺少
  公开 running。Service 修复及 65 项专项、冻结 r 的 180 秒断脑 / 换 Hands 完整验收均通过。
  官方 Broker 的初始 MAX 位点缺口仍需生产依赖方案收口，本地测试修正版不代表官方认证。
  全仓因 q 缺陷受控停止，不能记为通过；后续源码仍需最终完整验证。
- 持久输入等待已落地，冻结 s 的确认、通用外部结果、取消和双 Brain 容量均通过。两个
  Brain 全部退出后仍能跨 Portal 持久受理答复，再恢复原 Turn；等待期间活动执行令牌释放。
  最终审计 7 Session / 10 终态 Turn，无未处理输入、开放待办、未完成工具或当前待通知回调。
  Common 及其依赖测试于 07:16:34 通过，共 4687 项、0 failures / errors、45 条件跳过。
- 原生 Webhook 及三种 Managed SDK 已接入相同的公开日志 / 游标。冻结 w 的真实生产投递
  Worker 验证签名、丢失回执、等待真实 60 秒 claim 到期、双替换 Worker、同 deliveryId
  重投、后续顺序及空闲回读；3 条逻辑通知实际发送 4 次，接收端去重处理 3 次。
  独立审计确认四个 JAR 的 common 哈希一致、Portal 新迁移及 83 字节事件 ID、无旧事件镜像，
  当前未完成 Turn / Tool / 输入 / 待办 / 回调均为 0，十个验收 PID 全部停止。u / v 的
  夹具失败及退出观察超时记录保留。本批专项 / SDK / 发布和文档检查通过，尚非最终全仓。
- 历史 Checkpoint 恢复 / Fork 的冻结 x 验收及独立审计通过：4 Session、7 终态 Turn、
  三次完成操作和一次预期拒绝，真实目录、2 MiB 二进制、0555 权限及旧推理上下文均一致。
  两个 Brain / Hands 被替换后继续，Fork 独立写入不影响源，无遗留操作屏障、输入、工具或
  当前待通知回调，七个进程已停止。使用显式测试修正版 MQ / Redis / PG、确定性模型与本地
  进程 Sandbox，未验证 ACS 或付费模型。根 Session capability 增量及 Go→Portal 实际 HTTP
  联调已通过，本轮专项 66 项、0 failures / errors / skipped，尚非最终全仓。

## 实施决策

1. 当前使用 Turn、Runtime Event、Tool Event、Resume 四条逻辑通道，基础名称为
   ma_turns_v3、ma_runtime_events_v3、ma_tool_events_v3、ma_turn_resumes_v3；基础消费组为
   ma_brain_v3、ma_hands_events_v3、ma_hands_tool_events_v3、ma_brain_resumes_v3。Turn 另按固定
   子深度增加 `_d1` 至 `_d16` 父 Topic / 消费组，共 20 个物理绑定（见下文容量记录）；
   Shared、wildcard 全量订阅并配置 FIFO，每个父 Topic 下以完整作用域的 Session 为 LiteTopic。
   业务 MessageGroup 仍包含租户、命名空间和 Session 身份。四条通道已切换 LitePush；
   新原 Turn 持有 / Resume 多副本主链通过，完整故障与容量矩阵仍待验收。
2. Broker 5.5.1、独立 Cluster Proxy、Java SDK 5.2.2、Redis 7.4.11、PostgreSQL 17。
   实际联调记录镜像 digest；Lettuce 复用现有 BOM 管理，不引入 Core 依赖。
3. MQ 目标为 LitePushConsumer 的 SDK / Proxy 自动续期，Proxy 上限 3 小时；移除应用逐投递
   续期及跨进程 receipt ACK。Redis 活动执行/工具令牌 TTL 60 秒、续期周期 15 秒，挂起释放
   推理令牌。单调执行代次保存在 DB；Redis 清空或故障转移不能重置代次。
4. 发布序号在实际 MQ 发布时产生，用于前序缺失保护，不能用 API 受理时间替代 MQ 顺序。
   当前发布获得 Broker 或消费者确认前，不发布同 Session 下一条逻辑 Turn。
5. Runtime 事件经 MQ 确认后进入本次执行共享的本地日志投影，Brain 不逐批等待 DB receipt。
   Hands 事务保存完整挂起或终态 checkpoint 及可靠交接通知；挂起只完成本次 Resume，
   原 Turn 保持回调等待终态通知，SDK 执行 ACK。持久恢复只读取已提交前缀。
6. Input Stream 游标不是业务去重键；已应用需和 Checkpoint 在同一事务提交。
7. self_hosted Worker 通过 Gateway → Hands 的 HTTPS 接口连接，不暴露内部 DB / MQ / Redis。
8. 测试先使用确定性 Fake，再以独立 Compose 项目验证真实基础设施。
   基础设施启动和地址排查最多 15 分钟；失败后显式使用 Fake，并标记真实行为未验证。
   生产配置不自动切换 Fake。
9. 暂不自动把死信追加到队尾重跑；前序缺失阻塞 Session，未知副作用禁止自动关闭或重复执行。
10. 不扩展权限体系；保留现有鉴权、工具权限、预算和配额约束。
11. Broker 5.3.2 拒绝 64 字节 MessageGroup；使用 SHA-256 的 Base64URL（43 字节），保留完整作用域身份信息。
12. LitePush 开启按 LiteTopic 并行，消费组服务端 FIFO。正常 T2 由原 Turn 的终态 ACK 放行，
    不继续以 DB 前序扫描 / defer 轮询调度。历史 LiteSimple 单物理父队列隔离和跨 Session
    并行证据不能等同于新消费协议已通过；新版需重新验证。
13. Redis 活动执行/工具令牌仍独立续期；Core SessionLog 的 renew 只校验现有 Guard，
    不建立第二套续租，也不驱动 MQ 消息续期。
14. 大于 128 KiB 的日志批次写私有共享卷，MQ 仅传带哈希的引用，最大批次 64 MiB。
    Brain 与 Hands 必须共享此卷，不能把该目录用于公开 Artifact 下载。
15. 将既有 CommittedSessionEventProjector 原样移到 service-common，供 Hands 和现有消费者使用，避免两套公开事件转换。
16. 正常工具结果通过每个 ToolTask 的 Redis 信箱返回；Hands 从持久结果有界补投递。
    完成通知通过私有 Redis 逻辑信箱返回，DB outbox 做有界补投递。正常 T2 由 SDK FIFO /
    ACK 放行，不再暂持 T2 或订阅 Redis 结算提示；DB activation 仍校验逻辑前序。
    SSE 使用持久公共事件游标、PostgreSQL 通知和漏通知回读，不依赖某个 Portal 实例的内存。
17. 删除为订阅扫描 Session 目录和逐 Session 动态订阅 / 轮换。新 Session 通过父 Topic
    全量订阅直接消费。保留有限回调线程、Harness 活动并发、事件池、工具池及可靠待发布
    意图；挂起回调占用消费线程，这项容量不能计为已经释放。
18. Managed 任务工具使用私有 Attempt 凭据，签名绑定 Task / Attempt / 代次 / Session。
    凭据只证明逻辑任务身份，每次 MCP 调用还检查当前状态、Agent / Binding、资源授权及
    原生执行；不替代 MQ / Redis 的物理 Guard，也不作为通用 API Key 或 Hosted 租约。
    这样封存配置和 MCP 静态 Header 不需要周期更新；用户与 Application 凭据的有效期不变。
19. 业务 AttemptTimeout 读取原生 Session 冻结的策略和已开始时间，到期先受理原生 cancel，
    停止证明到达后再失败，不重新派发。已经记录原生执行身份时，缺失会话元数据不能确认为已停止。

### 父子回调容量（2026-10-16，生产接线与双副本验收进行中）

- 独立 SDK 5.2.2 / Broker 5.5.1 探针将每池回调数限制为 1：根 Turn 等待子 Turn 时会占满
  同池；只新增一个子池，子 Turn 等待孙 Turn 时仍会占满子池。独立 Resume 池不解决新子 Turn
  无法开始的问题，不能据此宣称父子容量已完成。
- 根、子、孙按深度使用不同 Lite 父 Topic / 消费组，实测顺序为根开始、子开始、孙完成、
  子完成、根完成、根 Session 下一 Turn 开始。成功证据为
  `/tmp/managed-depth-sdk-probe-2.json` 和 `/tmp/managed-depth-sdk-probe-2.log`，进程退出码 0。
  首次探针虽完成行为断言，但 SDK 顺序关闭超过 100 秒，单独记录为未完整通过，未覆盖失败证据。
- 已将深度路由接入 Portal 发布、SDK 物理 Topic / 消费组、Brain 固定配置校验及显式 Fake。
  四条逻辑通道保持不变，共 20 个静态物理绑定；生产初始化脚本、验收初始化、Compose、Helm
  与中英文部署说明已同步。保持终态 ACK，不新增实例目录或逐 Session 订阅。
- 每子深度默认 4 回调，子层缓存按十六分之一分摊；通知读取容量默认 192。SDK 消费者并行
  关闭。活动 Harness 队列满时在本地等待，不消耗 MQ 重试次数；错误分道在确认发布及注册
  回调之前拒绝。Core / Harness 本轮没有改动，框架已有的更严格深度 / 叶子限制保留。
- 36 项专项通过，包含每层一个槽位的 16 层 Fake 链、根池一个槽位的实际父子 Harness、
  单工作线程 / 单排队槽的 5 Session 背压、错误分道和原有子调用 / FIFO 回归。
- 真实 SDK 生产适配器的 16 层链及失败回调 FIFO 两项通过，日志
  `/tmp/managed-depth-lanes-real-tests.log`。该命令整体失败：另一个 180 秒回归漏传了测试 DB
  地址，尚未进入协议执行；补齐参数的独立重跑见 `/tmp/managed-depth-lanes-real-protocol-fixed.log`，
  已于 04:40:03 通过真实 DB / MQ / Redis 的 180 秒恢复及原 Turn / T2 顺序回归；工具为显式 Fake。
- 有效占位 Secret / 镜像配置下 Helm lint / 渲染通过；发布脚本 37 项、中英文文档 16 项、
  严格构建与链接检查通过。最初直接渲染缺少必填迁移 Secret，已按 Chart 契约补齐测试参数，
  未弱化模板校验。
- 冻结 n：`/private/tmp/managed-mq-replicas-20261016-depth-lanes-n`，真实 MQ / Redis / PG、
  两 Portal / Brain / Hands，各 Brain 根回调 / 子深度回调 / 活动 Harness 及各 Hands 物理
  工具容量均为 1。三份 JAR 内 service-common 哈希相同，且包含新深度传输类。
- `depth-capacity-1792097188430694000.json` 在五分钟观察限时内完成四组，最后一组已推进到
  waiting_tool；此失败记录保留。续查 `depth-capacity-1792097564670821000.json` 使用原有
  五组请求，通过所有原断言：两个根池与两个子池同时持有回调、五个子工具各一次 start / done、
  原生检查点、最终回复与根 Session 后续 Turn。没有新建或重发业务请求。
  单槽下完整 Harness 的文件 / 记忆操作也使用 Hands，不能用工具秒数直接推导整批耗时；
  观察预算已显式记录，增加可继续观察已有记录的入口，保留每次结果文件。
- 冻结 n 的实际 AgentTask Outbox / MCP get / complete、原 Actor、一次本地执行及挂起恢复
  已通过；同一子 Session 第二 Turn 与父子取消回归也已通过。新的全仓 clean verify
  `/tmp/managed-depth-lanes-full-clean-verify.log` 使用独立 DB `managed_depth_lanes_full_20261016`，
  于 04:58:10 失败：新增 16 层 SDK 验收预期完整链，只观察到后序消息。原失败日志保留；
  目前不能证明是测试生命周期还是实际传输问题，不将此轮记为通过。
- 新增消息身份 / LiteTopic 校验、Broker 消息 ID 诊断及四次连续深度链回归，
  `/tmp/managed-depth-lanes-real-diagnostic.log` 7 项通过；这不足以解释全仓的一次异常。
  又补齐监听器断言错误上报，并等待后序监听器返回后再关闭测试订阅，避免测试退出把正在
  返回的 SUCCESS 变为 FAILURE；正在验证 `/tmp/managed-depth-lanes-real-diagnostic-2.log`。
  第二轮 7 项也已通过；它们不能抵消独立 SDK 100 对消息的真实失败。
  这批仅修改测试，没有改变冻结 n 的生产代码。
- 冻结 n 的最终审计通过：15 Session、17 终态 Turn、163 私有检查点 capture；旧表 / 旧
  Session、未提交结果 / 来源事件、未结束工具 / Turn、开放来源流和当前待收回调通知均为 0。
  七个业务进程已确认停止；日志、数据库与冻结 JAR 保留。

### Lite 初始位点：官方失败基线与显式本地修正版（2026-10-16）

- 绕过全部 Service 代码，使用原始 SDK 5.2.2、Broker 5.5.1、一个全新 LITE 父 Topic / FIFO /
  Shared / wildcard 消费组，依次向 100 个全新 LiteTopic 发送 first、next。49 个只收到 next；
  两条均获发送确认，40 秒观察结束后仍缺 first。不是已证实的测试清理问题。
- 独立证据：`/tmp/managed-lite-first-message-0ce5505c8f86/result.json`、`java.log`、
  `ManagedLiteFirstMessageProbe.java`，父 Topic `ma_first_probe_4e96234a3950`；生成器
  `/tmp/managed-lite-first-message-probe.py`。此失败保留；后续专项连续通过不能将其抵消。
- 官方 5.5.1 的 [PopLiteMessageProcessor.getPopOffset](https://github.com/apache/rocketmq/blob/rocketmq-all-5.5.1/broker/src/main/java/org/apache/rocketmq/broker/processor/PopLiteMessageProcessor.java)
  在无进度时使用 MAX；[PopMessageProcessor.getInitOffset](https://github.com/apache/rocketmq/blob/rocketmq-all-5.5.1/broker/src/main/java/org/apache/rocketmq/broker/processor/PopMessageProcessor.java)
  该路径可能取 maxOffset-1。组配置 `consumeFromMinEnable=true` 不足以证明 Lite 从最早位置消费。
  官方最新发行 API 当前仍指向 5.5.1；不能假定已有正式升级版能解决。实测 Broker 的
  `initPopOffsetByCheckMsgInMem=true` 已开启，仍复现跳过首条，不能把开启此配置当作修复。
- 后续已检查 Broker 的 `initPopOffsetByCheckMsgInMem` 配置及离线 / 重启边界，评估受支持的
  最早位点能力。仅开启热内存优化不足以证明冷积压 / 重启后的完整消费；不引入隐式 Fake
  降级，不以强制首条等待或放宽顺序断言掩盖问题。四条通道都必须满足首次消费前缀完整性，
  不能只检查 Turn。当前完整目标仍未完成；本地修正版的后续证据见下节，新全仓尚未重跑。

### 最早位点的本地修正与验收

- 官方路径的 MAX 起始策略与 Managed 协议要求的完整前缀不一致；两条均获发送确认，但没有
  物理删除的证据，不能将“本组没有消费 first”描述成存储丢失。已新增独立、可重复的 `lite-initial-offset.py` /
  `LiteInitialOffsetProbe.java`，记录每条发送确认并校验各 LiteTopic 的完整消费顺序。
- `tests/managed-mq-redis/rocketmq-lite-min-offset.patch` 让 Lite 初次位点遵循已有消费组的
  `consumeFromMinEnable`；已有位点、显式 reset 及关闭该标志的 MAX 策略不变。仅修改依赖
  的 Lite 初始化路径，不修改 Core / Harness 或增加 Service 的 Session bootstrap / 动态订阅。
- 显式本地镜像 `managed-test/rocketmq:5.5.1-min-offset`，最终镜像
  `sha256:3a9e744687b031169a4cd2c10ef0b793db4675476b1a2e283b22a7b7c13afad0`。
  构建校验官方源文件 SHA-256 `f15c65b3ae8ab33fb2da98884de91596aa5f316e67c8c3254dac981ef0f94817`，
  使用独立 Compose 项目 / 数据卷 / 19982 端口；原官方负例保留在 19981。该镜像是测试修正版，
  不是上游发行或生产环境自动降级。源类及其内部类一起更新，其他 Broker 类保持原样。
- 关闭热内存位点捷径后，100 对新 LiteTopic 即时消费全部通过：
  `/tmp/managed-lite-min-offset-first-message-19f86f0168f9/result.json`。
- 100 对消息在消费者完全不存在时获 Broker 确认，随后重启 Broker / Proxy，首次启动消费者
  完整读取 200 条，自动流程通过：
  `/tmp/managed-lite-min-offset-offline-semantic-ready-20261016/result.json`。
- MAX 对照组关闭 consume-from-min，积压后按原策略只读每个 LiteTopic 的 next，100 组通过：
  `/tmp/managed-lite-min-offset-last-control-20261016/result.json`，其中记录实际镜像 ID 和
  `in_memory_shortcut=false`。这些通过范围不能用于认证原官方镜像或任何未测试的托管 MQ。
- 两次早期重启观察因 Proxy 启动准备失败保留为失败；恢复基础设施后，仅观察原有积压的
  `continued-result.json` 均通过，没有再次发送消息。定位发现 mqadmin 在未注册 NameServer
  时打印错误仍退出 0；新增 `init-lite-proxy.sh` 校验真实注册行及系统 Topic 创建成功，完整
  第三次自动流程通过，不以延时重发业务消息规避。
- `replicas.py --mq-profile min-offset` 显式选择该基础设施；manifest 固定 profile / endpoint /
  修正版身份，重启沿用同一选择。默认仍为 official，不做隐式切换。下一步初始化各通道，
  在 MIN 能力满足后重跑完整 Service 验收与全仓；正式依赖要求为通过同等能力验证的 Broker。

### 初始化可靠性与修正版全仓复验（2026-10-16）

- 修正版 MQ 上的适配器 / 深度路由 / Pipeline 专项 15 项通过，日志
  `/tmp/managed-lite-min-offset-adapter-tests.log`；四次连续 16 层回调链包含在内。
- 冻结 o 在 Portal 启动时明确失败，缺失根 Turn 家族的 `_d2` Topic；此前 mqadmin 打印
  超时却返回 0。生产初始化和测试初始化现在校验成功结果、拒绝混合错误输出并最多重试
  三次；`replicas.py` 直接复用生产初始化脚本，不再维护另一套未校验的循环。
  `release/tests/test_managed_mq_init.py` 三项通过，覆盖退出 0 的失败、有限重试、部分
  Cluster 成功掩盖错误及全部 20 个静态绑定。原 o 日志 / 冻结产物保留，唯一存活的 Control 已停止。
- 发现本地低内存 NameServer 继承镜像的 `-Xmn2g`，总堆只有 128 MiB，jstat 实测老年代
  64 KiB、Full GC 超过 2,500 次；新增 `-Xmn32m` 后老年代 96 MiB、观测窗口 Full GC 为 0。
  证据为 `/tmp/managed-lite-min-offset-namesrv-gc-{before,after}.log`。只重建测试修正版的
  NameServer，未重启官方负例或用户开发服务；不把此修正视为 Lite 初始 MAX 策略的修复。
- 冻结 p 在上述基础设施修正期间于初始化阶段非零退出，未启动业务进程；保留失败记录。
  新冻结 q 使用新库 `ma_replicas_03b3a9d63588` 和相同冻结生产 JAR，正在重新初始化。
- 初次 61 组资源审计发现 `ma_brain_v3_d2` 消费组也缺失；三次修复请求均超时的原日志
  保留。修正 NameServer 后创建成功，独立读取 Broker 实际配置确认 61 组全部存在，均为
  LITE / FIFO / consume-from-min / Shared / wildcard，见
  `/tmp/managed-min-offset-full-resources-audit-fixed.json`；原失败审计未覆盖。
- 全仓 `mvn -B -T1 clean verify` 已启动于
  `/tmp/managed-lite-min-offset-full-clean-verify.log`，新库
  `managed_lite_min_offset_full_20261016`、修正版 MQ 19982、真实 Redis / PG，额外开启
  70 秒 held-callback 与 180 秒恢复协议测试。尚无最终结果，不宣称全仓通过。
- 本批没有修改 Core / Harness。文档 16 项、严格构建和链接校验已通过，日志
  `/tmp/managed-lite-min-offset-doc-{tests,validate,links}.log`。

### 冻结 q 的严格验收：恢复成功，但收尾状态转换缺失

- 新初始化脚本创建 20 个绑定并启动七个业务进程成功，启动记录
  `/tmp/managed-min-offset-q-start.log`。发布 / 部署脚本全量 40 项通过，日志
  `/tmp/managed-lite-min-offset-release-tests.log`。
- `tool-stream-1792102355826662000.json` 在两个 Brain 被杀、两个 Hands 替换期间验证了
  原 180 秒工具、三个完整输出片段、来源哈希和最终水位；恢复 Brain 后的 120 秒观察超时。
  同一请求随后完成，两个 Turn 均成功，执行迭代仍为 [0,1] / [0]，业务工具一次 start / done，
  未完成 Turn / Tool、当前待通知回调均为 0，完整检查点对象审计通过。
- 原失败文件保留；新增 `tool-stream.py --continue-record` 仅观察和重新校验已有请求，
  不创建 Session、不发送任务或重新启动执行。续查仍按原严格规则失败：最后一次恢复只需
  做收尾，未产生 Core 的 turn/resumed，公开事件出现 waiting_tool → completed，缺少
  running 转换。不能只因两个 Turn 完成就把完整验收记为通过，也不放宽生命周期断言。
- `recovery-audit.json` 分别记录内部恢复检查通过与完整验收失败。七个 q 进程均已优雅退出，
  `stopped.json` 有逐 PID 证明；首次 20 秒停止观察超时记录保留，之后确认退出，未强杀。
- 修复限定 Service：Hands 对没有原生 resumed 事件的收尾 / 再挂起补齐执行状态转换，
  保持同一原生日志和 Checkpoint，不伪造 Core run。全仓运行期间未修改 Java / 资源；
  待本次全仓退出后增加 H2 / PG 回归并修正，再冻结新的完整部署验收。
- 真实 MQ 全仓已通过 70 秒 held-callback、四次连续 16 层链、180 秒协议恢复；整个构建
  尚未结束。修正版与官方负例仍明确区分，不能据此声明上游发行或所有场景通过。
- 中英文聊天示例、API 索引、Session 诊断和 SSE 文档已按实际 Controller 同步：使用
  Turn Inputs、Session SSE、原生分页及生命周期回执，去掉不存在的显式 resume / trace
  和 Turn events 路由。原生 Webhook 尚未接线，旧后端的 Webhook 不再冒充原生支持；
  该接线与验收仍需完成。文档 16 项、严格构建和链接检查通过，见
  `/tmp/managed-native-entry-audit-doc-*-final.log` 及 `tests-fixed.log`。

### 收尾恢复状态修复与新冻结 r（2026-10-16）

- 因 q 的新缺陷停止尚未完成的全仓构建，未记为通过；
  `/tmp/managed-lite-min-offset-full-interrupted.json` 证明 Maven 及其两个子进程均退出，
  之后才开始修改 Java。已通过的 MQ 深度链 / 续期及 180 秒专项保留其窄范围证据。
- `ManagedJournalStore` 在没有原生 resumed 事实、仍处于 preparing 的执行尝试提交挂起或
  终态时，先原子发布 turn.running，再发布对应结论；本批存在原生 start / resumed 时
  不重复发布。原生日志、Core run 身份与状态序列化保持原样，没有伪造框架事件。
- 新回归通过真实 Resume 激活，再分别执行只有 checkpoint 的终态收尾或再次挂起，
  同时覆盖有 / 无原生 resumed、事务重放不重复及既有回滚保护。H2 与真实 PG 执行相同断言。
  `/tmp/managed-handoff-running-tests.log` 于 06:34:44 BUILD SUCCESS，65 项、0 failures /
  errors / skipped；包含原生日志、回调、Pipeline 和五种实际 Harness 恢复情形。
- Spotless 通过，Portal / Brain / Hands 新 JAR 构建于 06:35:45 通过；本批没有修改 Core /
  Harness。新冻结 `/private/tmp/managed-mq-replicas-20261016-handoff-r`，新库
  `ma_replicas_8baefb39d663`，`artifact-audit.json` 校验三份完整 JAR 哈希及其相同的修复后
  common / journal 类，不覆盖 q 的冻结产物。
- r 正在重新部署；启动完成后同一控制器会运行 180 秒断脑 / 换 Hands 的严格验收，日志
  `/tmp/managed-handoff-r-{start,tool-stream}.log`。业务工具时长与恢复完成观察分开记录，
  后者显式为 600 秒，用于包括 Workspace / Memory 的后续 Hands 收尾；不改变生命周期、
  来源完整性、模型结果、检查点或一次副作用断言。旧 q 的失败和失败续查均保留。
- 中英文调用文档最终校验均通过，日志 `/tmp/managed-native-entry-audit-doc-*-final2.log`；
  两种语言各九个聊天 Bash 片段另通过语法检查。原生 Webhook、持久等待输入、其余故障
  矩阵、正式完整部署 / 客户端及最终全仓仍未完成，不能据此标记总目标达成。

### 持久等待输入与双副本验收（2026-10-16）

- `awaitReplies` 已改为读取已受理答复或发布完整挂起，不再占用活动 Harness / Guard 循环
  等待。私有挂起记录增加 pending_actions；APPLY_INPUTS 与 APPLY_TOOL_RESULTS 互斥，
  校验全部当前待办、检查点关联及没有未结算 Hands 操作。公开状态仍是 waiting。
- Portal 受理 reply / cancel、Hands 保存挂起和生命周期取消使用相同 Session 事务锁检查
  恢复条件。答复先到或后到均创建一个 Resume；原 Turn 回调保持未完成，T2 不提前放行。
  恢复保留原 Actor / Turn / 推理位置，使用新的执行代次，输入应用仍与检查点提交。
- 新 PG / H2 测试发现“先挂起、后归档”漏唤醒：生命周期加锁查询缺少 current_turn_id；
  已补齐该投影，未放宽断言。首轮 61 项中的两个失败记录保留；修正后的 56 项专项通过，
  包含真实 PG、八种边界和实际 Harness 外部结果 / 取消。日志
  `/tmp/managed-input-wait-tests-3.log`，06:51:58 BUILD SUCCESS。
- 联合回归的 Common 56 项通过；Portal 旧测试只预期 completed 首事件，未包含上一批
  running 修复。现改为严格核对 running → completed，并增加 waiting 查询 / 快照用例；
  `/tmp/managed-input-wait-portal-tests.log` 的 PG / H2 16 项及 Portal 10 项全部通过。
  生产 JAR 于 06:59:43 构建成功，尚非最终全仓通过。
- 冻结 r 的上一批状态修复完整通过：180 秒本地工具在双 Brain 全灭、双 Hands 替换后保持
  一次副作用、完整输出、原生恢复、严格生命周期及 T1 / T2 顺序。checkpoint-audit.json
  确认未完成 Turn / Tool / 当前待通知回调均为 0，七个进程已停止。模型为确定性 Fake，
  Sandbox 是本地进程，真实 MQ 使用显式 MIN 修正版，不能作为 ACS / 官方 Broker 认证。
- 新冻结 s：`/private/tmp/managed-mq-replicas-20261016-input-wait-s`，新库
  `ma_replicas_add77938b2a3`，两 Portal / Brain / Hands，活动 Harness / 工具并发 1、
  根回调 8、子层回调 1。三份 JAR 的 common 哈希一致，包含新 schema / 等待协议。
  外部结果专用 ask_user schema 仅由测试模型初始化器注入，不是生产新增工具或隐式降级。
- 确认答复通过：两个 Brain 退出后跨 Portal 受理 / 幂等答复，一个持久 Resume，恢复后
  原 Turn 执行 45 秒实际工具并继续推理，后序 Turn 按序完成；同一个业务工具一次 start / done。
  `input-wait-confirmation-1792105451043667000.json`。
- 通用外部结果通过：双 Brain 退出后持久受理真实内容，恢复后的模型校验该内容并继续原
  Turn，随后 T2 完成；`input-wait-external-1792105611503491000.json`。等待中取消通过，
  待办清理、无业务工具副作用、T2 不继承 ASKING；`input-wait-cancel-1792105762015340000.json`。
- 容量首轮两个等待请求落在同一消费者，无法证明双实例，保留失败
  `input-wait-capacity-1792105788669633000.json`。续查保留原两个请求，增加一个有界
  等待探针，确认三个等待回调覆盖两个 Brain、Redis 执行令牌均释放，再让独立 Session
  完成，最后取消三个等待轮次。原请求没有重发，双实例条件未放宽；续查记录
  `input-wait-capacity-continuation-1792105931666931000.json` 通过。
- `final-input-audit.json` 独立核对私有 APPLY_INPUTS 检查点的 actionId / 空任务集合及
  三份 JAR 哈希；7 Session、10 终态 Turn，未完成 Turn / Tool、accepted Input、开放待办、
  容量场景业务工具、当前待通知回调均为 0。七个 s 进程已优雅退出，stopped.json 保留逐 PID 证据。
- Service 未发布协议直接更新私有 DTO 与 schema，不保留旧三字段挂起或数据转换。
  迁移 checksum 会拒绝旧库；已使用新库 / 冻结产物验收。本轮没有修改 Core / Harness。
- 中英文原生 API / 会话日志与主设计已同步；文档 16 项、严格构建、链接检查通过，见
  `/tmp/managed-input-wait-doc-*.log`。全 Common / 依赖测试
  `/tmp/managed-input-wait-common-all-tests.log` 于 07:16:34 BUILD SUCCESS，共 4687 项、
  0 failures / errors、45 条件跳过；未配置的 MQ / Redis 专项跳过，不计真实通过。

### 外部结果错误状态补齐（2026-10-16，双副本通过）

- 审查发现外部 reply 的 is_error 仅映射 metadata.error，Core 判断结果状态使用
  ToolResultState，普通错误正文会被误记为成功。Service 现在显式设置 ERROR / SUCCESS，
  不修改 Core / Harness；错误工具结果可交回模型继续推理，不自动把整个 Turn 判为失败。
- 实际 Harness 回归扩展为成功答复、错误答复和取消；真实双副本脚本增加 external-error，
  同时校验恢复后的模型实际收到错误状态、公开工具 failed、原 Turn 仍可完成及 T2 顺序。
  前两次专项分别因新增测试排版和参数 / lambda 同名编译失败而停止，保留原日志，不能
  将尚未执行生产行为的结果记为协议失败。
- `/tmp/managed-external-result-state-tests-3.log` 于 07:25:53 BUILD SUCCESS，25 项、
  0 failures / errors / skipped；包含 H2 / 真实 PG 的输入竞态和实际 Harness 三种结果。
  Spotless 通过，三个生产 JAR 于 07:26:56 构建成功。中英文文档 16 项、严格构建及链接
  检查通过，日志 `/tmp/managed-external-result-state-doc-*.log`。
- 新冻结 `/private/tmp/managed-mq-replicas-20261016-external-error-t`，数据库
  `ma_replicas_997e3c9c4a43`，模型仍为显式确定性夹具，基础设施为真实 Redis / PG 和
  MIN 测试修正版 MQ。成功 / 错误两场景均在双 Brain 退出时跨 Portal 持久受理及去重，
  检查一个 Resume，再重建 Brain 完成原 Turn 和 T2。两份 passed=true 记录分别为
  `input-wait-external-1792107188328978000.json` 与
  `input-wait-external-error-1792107036277072000.json`；错误场景的公开工具确为 failed，
  模型确认收到 ERROR 状态，逻辑 Turn 仍正常完成，没有把工具失败误记为整个 Turn 失败。
- `final-external-result-audit.json` 独立核对三份冻结 JAR / 相同 common 哈希、私有
  APPLY_INPUTS 检查点哈希和真实模型结果，2 Session / 4 终态 Turn，未完成 Turn / Tool、
  accepted Input、开放待办和当前待通知回调均为 0。日志
  `/tmp/managed-external-error-t-{start,error,success,audit}.log`。本轮没有修改 Core / Harness，
  不用该专项成功替代最终全仓或未验收的故障矩阵。七个 t 验收进程均已优雅退出，
  stopped.json 已逐 PID 证明；日志、隔离数据库及冻结产物保留。


### 原生 Webhook 接线（2026-10-16，专项与真实恢复验收通过）

- 复用 Portal 订阅 / 回执及 Scheduler 网络投递 Worker；新增 `/v1/sessions/{id}/webhooks`
  路由。Hosted / External 仍用原事件源，Managed 直接读取 ma 的已提交公开日志，不读取
  旧 Portal 运行日志，也不建立事件镜像。共享控制器保留其他后端原入口，没有数据转换层。
- 注册固定当前公开 head；通知和投递回执使用与原生 REST / SSE 相同的作用域游标。
  event_url 指向 after=前序游标、limit=1 的公开事件页，现有原生 API 可以直接读取。
  新元数据查询只加载 sequence / source_id / type / created_at，不加载事件的大正文。
- Hands 不访问 Portal 的订阅表或 RPC。空闲的原生订阅每五秒重新具备回读资格，Scheduler
  的有界 claim 查询包含这些订阅，丢失瞬时通知不会永久停留 idle。其余后端保留原事务唤醒。
  订阅仍只有一个待投递事件，2xx 回执推进游标；重试复用 deliveryId，新 claim 递增 fence。
- Portal V0008 将两处事件 ID 列扩至 256，适配原生带前缀的稳定 ID；事件类型过滤允许多段
  名称，如 tool.operation.completed。没有新增执行租约、MQ 通道或 Core / Harness 改动。
- Java、TypeScript、Python 的 Managed 客户端补齐 Webhook CRUD / 重试 / 投递回执。
  `/tmp/managed-native-webhook-tests-3.log` 于 07:48:35 BUILD SUCCESS，21 项、0 failures /
  errors / skipped，包含实际 H2 / JPA 原生路由、两方抢占、通知缺失回读、长 ID、重试与
  旧 Hosted 回归、Java 客户端及生产网络 Worker。原有失败日志保留：先是格式文件选择
  表达式错误，随后新 H2 夹具缺少 PostgreSQL mode、事件夹具缺少资源 ID；均已修正，
  没有放宽路由、数据源、状态或重复投递断言。
- Python 全包 117 项通过；TypeScript 全包与构建通过，见
  `/tmp/managed-native-webhook-{python-tests,ts-tests,ts-build}.log`。中英文 API / Webhook
  文档已同步，尚需文档检查、新冻结实际服务验收、最终全仓与完整部署，不能提前计全部完成。
- 新验收显式开启本地 HTTP 接收端，测试 main 只装配生产 PortalWebhookWorker，不装配
  无关 Channel / Cron 调度，也不进入生产 JAR。接收端验证原始字节的 HMAC，业务去重用
  稳定 deliveryId；跨 JVM 的 JSON 键顺序不作为幂等契约。
- 真实 PostgreSQL 的 Portal 迁移 / 只读校验及两个 256 长度字段检查通过；日志
  `/tmp/managed-native-webhook-schema-tests.log` 共 16 项、0 failures / errors / skipped，
  含原生日志与原生 Webhook 回归。文档 16 项、严格构建和链接检查均通过。
- 冻结 u 使用新库 `ma_replicas_a6a6b1bd9f37`；新迁移和七个业务进程启动成功，但验收
  代理夹具将 body 函数名遮蔽为局部变量，关闭了请求而未转发。已保留原验证脚本及其
  SHA、fixture-failure-analysis.json、未通过记录和日志；核对仍存活的验证器后主动中断，
  八个 u 进程已全部停止，stopped.json 有逐 PID 证明。没有将业务 Turn 完成计为 Webhook
  投递成功。修正夹具函数名，并把服务器异常写入结果文件、使观察及时失败；新冻结 v
  在 `/private/tmp/managed-mq-replicas-20261016-webhook-v` 使用新库重新部署验收。
- 冻结 v 收到一条验签成功的真实通知，回执也确实被阻断，但故障夹具的 Worker 命令缺少
  统一进程归属标记，受保护的信号函数拒绝终止进程。没有放宽归属校验；已在测试命令增加
  `-Dmanaged.replica.state`，启动后先用 signal 0 检查归属，再受理测试业务。
  v 的未通过记录及原脚本 SHA 保留。停止观察超过 60 秒后，复查同一批 PID 确认全部自行
  退出，未强杀、重启或重发；continued-stop-audit.json 与 stopped.json 保留证据。v 有一轮
  未结算及一个未启动工具，不把该失败夹具视为协议通过，也不将其数据混入新验收。
- 冻结 w：`/private/tmp/managed-mq-replicas-20261016-webhook-w`，新库
  `ma_replicas_f6ba5d4094cf`，真实 Redis / PG / 显式 MIN 修正版 MQ，确定性模型和本地进程
  Sandbox。`webhooks-1792109312495664000.json` passed=true：第一个实际投递 Worker 在
  接收端 204 后、回执到达 Portal 前被 SIGKILL；两个替换 Worker 分别连接两个 Portal，
  经过真实 60.016 秒后重投同一 deliveryId，新 claim 的原事件回执最终 attempts=2。
  HMAC 每次按原始字节校验，接收端按 deliveryId 只执行一次；T1、T2 完成通知保持顺序。
- 两个 Turn 已完成且订阅回到 idle 后，发布的 Artifact 事件 ID 长达 83 字节；没有给
  订阅表补写 wake，定期回读仍成功发送。三个通知、四次 HTTP 发送、三次接收端去重处理；
  每条 event_url 及游标均通过实际原生 API 读取校验，旧 claim 的 validate / finish 都被拒绝。
- `final-webhook-audit.json` 独立检查四份冻结 JAR / 相同 common 哈希、验证脚本 SHA、八个
  Portal 迁移、两列宽度 256、3 个已送达回执及总 attempts=4。1 Session / 2 终态 Turn，
  未完成工具、accepted Input、开放待办、当前待通知回调、旧 Portal 事件 / 序号及旧 CP
  Session 均为 0。原 Worker 与两个替换 Worker 合计十个 manifest PID 全部确认退出。
  日志 `/tmp/managed-native-webhook-w-{start,acceptance,audit}.log`。没有修改 Core / Harness，
  未提交 / 推送；网络 Worker 的验收不替代 Scheduler 整个 Spring 应用或正式容器部署。
- 发布脚本 40 项、最终文档 16 项 / 严格构建 / 链接检查通过，日志
  `/tmp/managed-native-webhook-release-tests.log`、`/tmp/managed-native-webhook-doc-*-final.log`。
  下一阶段继续历史资源恢复、剩余故障矩阵、完整客户端 / 部署及最终全仓复验。

### 历史 Checkpoint / Fork 完整验收（2026-10-16，双副本与独立审计通过）

- 复核现有协议：没有历史 Workspace 镜像且之后已有工具调用时，拒绝用当前目录补拍；
  已保存的镜像只读复用。恢复追加新的原生基线及公开事实，不倒退日志、Turn 顺序或业务回执。
- 先保持 capability 关闭进行功能验收。增加 test-only 确定性模型的 checkpoint write/read 场景：
  在实际 Sandbox 写入版本文件、2 MiB 二进制和 0555 目录；读回时同时检查内容与权限，
  模型检查所选旧上下文存在、后续 v2 上下文不存在，错误直接使验收失败。
- 新增 `tests/managed-mq-redis/checkpoints.py`，通过原生 Python SDK 创建、上传、提交及
  跨 Portal 幂等受理：T1 v1 → 首次 Fork 固定目录镜像 → 源 T2 v2 → 双 Brain / Hands 替换 →
  Fork 读取 v1 / 独立写入 → 历史 Fork → 源仍是 v2 → 缺失历史镜像明确拒绝 → 恢复旧检查点 →
  源继续推理并读回 v1。操作回执与每步阶段持续写入结果文件，不重发超时观察中的任务。
- 资源边界沿用现有实现并显式记录：Fork 复制所选检查点的不可变上传文件成员；恢复执行
  状态不删除之后上传的独立文件资产。Artifact 发布账本及公开事件仍归原 Session，不跨
  Session 克隆或随执行恢复回退。共享外部资源的状态也不由该操作回滚。
- `/tmp/managed-checkpoint-history-baseline-tests.log` 于 08:19:16 BUILD SUCCESS，35 项、
  0 failures / errors / skipped，包含真实 PG / H2 校验与索引、本地真实进程导入响应丢失及
  替换 Provider 恢复。测试模型扩展已编译；三个服务 JAR 于 08:21:11 构建成功。
- 新冻结 `/private/tmp/managed-mq-replicas-20261016-checkpoint-history-x`，新库
  `ma_replicas_165be7ac33f0`，显式 MIN 修正版 MQ / Redis / PG、确定性模型及本地进程 Sandbox。
  日志 `/tmp/managed-checkpoint-history-x-{start,acceptance}.log`；
  `checkpoints-1792110298998847000.json` passed=true，三次操作完成、缺少历史目录镜像的
  一次操作按预期拒绝。冻结产物仍为能力关闭版本，不宣称其覆盖后续 capability 增量。
- `final-checkpoint-history-side-effects-audit.json` 复查三份 JAR / common 哈希、四份不可变
  Workspace 镜像、三个恢复基线的完整私有状态对象，以及目标身份 / v1 上下文 / v2 缺失。
  三份实际目录分别是 source=v1、first_fork=fork、historical_fork=v1，二进制全部逐字节
  正确、权限 0555；最初写入的 tool dispatch 只在源作用域出现，没有在 Fork 中重放。
  未完成 Turn / Tool / Input / 待办 / 操作 / 当前回调 / 操作屏障均为 0，七个进程全部停止。
- 根 Session 的 restore / fork capability 已在源码开放，子 Session 保持关闭；Webhook
  也按已有根级写入口约束修正子 Session 声明。首批专项 65 项、0 failures / errors，1 条
  Go 条件跳过，前端 183 项与构建通过，lint 0 errors / 98 既有 warnings，文档 16 项 /
  严格构建 / 链接通过。开启 Go 联调后发现冷文件测试手动保存的 Contract 漏掉正式 Session
  创建必经的 Environment 冻结步骤；仅补齐夹具及能力断言，不放松生产资源校验。
- `/tmp/managed-checkpoint-capabilities-tests-4.log` 于 08:44:42 BUILD SUCCESS，66 项、
  0 failures / errors / skipped。包含真实 PG 28 项、Portal 35 项（开启 Go→Portal 真实 HTTP
  跨语言联调），以及 Hands 的本地导入 / 响应丢失 / 替换 Provider 与 self_hosted 实际 HTTP
  Workspace / 输出恢复共 3 项。Spotless 同命令通过。本批未修改 Core / Harness，没有
  提交 / 推送；最新全仓复验及正式整套容器部署仍待完成。

### Sandbox 生成交付物归档（2026-10-16，专项通过，完整验收按用户要求暂停）

- 决策：增加 Service 自有 `publish_artifact(path, name, media_type)` 模型工具，不扫描或自动
  公开整个 Workspace。工具走已有 ManagedToolRequests → `artifact.publish` ToolTask，使用
  相同的原生调用 / 操作身份、挂起及恢复协议。工具在 Harness 构建过滤之前注册，沿用
  toolsConfig 启用 / 禁用及权限规则；Core / Harness 源码和公共 API 不变。
- Sandbox 按稳定 executionId 固定普通文件的内容、字节数和 SHA-256，最大 16 MiB，拒绝
  私有执行 / 检查点目录及 Workspace 外路径。原执行查询结果保留捕获的字节，源文件随后
  改变或 Worker 替换不能改变这份结果。self_hosted 继续复用相同执行程序和 outbound 协议。
- Hands 的 Tool Event 事务将文件、Artifact、公开事件与完成结果一起提交。原始结果 outbox
  保留私有字节，工具账本 / Redis / Brain 只收到元数据和下载引用，立即通知与补投递一致。
  使用稳定 executionId 构造发布幂等键；终态结果重放不重复发布。
- 已知配额 / 校验失败在嵌套保存点回滚归档，再将工具明确完成为 failed，不留孤立文件或
  阻塞 Session；数据库 / 事件提交故障回滚整个工具事务，保留同一已捕获结果重试。
- 新增 H2 / PostgreSQL 原子性、配额、摘要、去重、即时通知 / 补投递断言，以及实际本地
  进程的 2 MiB 文件、替换执行器、私有路径和超限用例。
  `/tmp/managed-generated-artifacts-tests-2.log` 于 09:04:25 BUILD SUCCESS：71 项、
  0 failures / errors / skipped，包含 Common 46、Hands 13、Dataplane 12。首轮两项断言将
  下载 URL 的 `/content` 误判为正文，改查 JSON 字段，保留完整二进制 / 公开日志检查。
  新增 `artifacts.py` 和确定性模型场景，完整多副本交付验收待执行；中英文文档 16 项、
  严格构建及链接校验通过，日志 `/tmp/managed-generated-artifacts-doc-{tests,validate,links}.log`。
  `/tmp/managed-generated-artifacts-package.log` 于 09:05:46 BUILD SUCCESS，三服务及依赖
  打包 / Spotless 通过。冻结 y 的启动和业务验收已按用户要求停止，未运行 artifacts.py；
  源码复核发现的云端 stdout 截断问题见本页暂停记录，不继续扩展实现或深度测试。
  初次 Spotless 参数误用 glob 而失败，改用该项目的正则过滤后通过，记录
  `/tmp/managed-generated-artifacts-format{-2,}.log`。没有修改 Core / Harness 或提交 / 推送。

## 阶段与完成证据

| 阶段 | 必须交付 | 状态 / 证据 |
| --- | --- | --- |
| 1 | 共享协议、MQ / Redis 适配器、确定性 Fake、协议测试 | 协议、真实适配器和 Fake 已验证；正式 Compose / Helm / 开发脚本已切换并增加 ma 迁移，实际业务账号双副本及 Hands 容器启动通过；整套容器部署与剩余验收待完成 |
| 2 | 新 Session / Turn 受理、发布账本、Push / Guard、挂起持有原回调 / 独立 Resume | API / SDK / Console 已切换，实际双副本同 Session 顺序及挂起恢复通过；接管和投递故障矩阵待验收 |
| 3 | Hands 事件事务、MQ SessionLog、Checkpoint、公开事件 / SSE | 原子提交、实际 Harness / SessionLog 与查询专项通过；实际双副本工具、两个 SSE 订阅者和跨 Portal 重连通过；MIN 位点能力在显式测试修正版通过，生产依赖方案及积压故障矩阵仍待收口 |
| 4 | Redis 输入闭环、工具执行 / 取消 / 恢复、客户端与适配器 | Input、工具恢复、文件 / Artifact、生命周期和 Fork 协议已接入；180 秒本地工具、父子容量、持久输入等待、历史恢复 / Fork 双副本通过；自动交付物与剩余适配器验收待完成 |
| 5 | 多副本真实或明确标记的 Fake 验收、旧实现删除、部署与文档 | 旧执行图及记忆 / 通知增量的双副本与全仓已通过；新工具协议的冻结 m 断脑 / 换 Hands、并行、输入 / SSE、取消及最新全仓通过；剩余故障矩阵和正式整套容器部署未完成 |

## 完成审计

以下每项必须有对应代码入口和实际验证结果，不以窄范围单测推断整体完成。

- [x] 多 Portal 同 Session 顺序，前一轮终态提交及 SDK 回调成功后后一轮开始（新 LitePush 冻结 JAR 的 push-e 真实双副本通过；后续增量仍需回归）。
- [x] 不同 Session 并行；事件处理和长工具不争用同一执行池（checkpoint-outbox-g 每副本物理工具并发 1，长工具运行时另一 Session 的完整 Turn 已完成；原 Turn 持有与事件 / Resume 独立池通过。冻结 n 的根池 / 子层池各单槽饱和、五组父子与根后续 Turn 已通过）。
- [x] 180 秒工具在 Hands 执行；Brain 持久挂起、原节点退出后恢复同一 Turn 并继续（checkpoint-outbox-g 冻结 JAR（运行类与最终全仓构建等价）、真实本地子进程、两个 Brain 同时 SIGKILL 后重建，通过一次副作用及 T1 / T2 严格顺序；模型为显式 Fake，未使用 ACS）。
- [x] steer / inject / reply 顺序、幂等和同 Turn 推进（steer / inject 的 m 双副本回归，s 的持久输入等待、跨 Portal 幂等确认 / 通用外部结果、双 Brain 退出后恢复、取消与计算容量均已通过；仍需最终整体验证）。
- [x] Input 与关闭并发，已受理集合全部应用或明确拒绝（真实 PostgreSQL 短事务竞争及跨 Portal HTTP 有限输入负载通过）。
- [ ] Redis 重启、重复投递、断线及取消回填（实际 Redis 停机 / 离线受理 / 恢复已通过；其余组合故障待验收）。
- [ ] Brain 暂停 / 分区后接管，拒绝旧代次推进，复用 ToolTask（新挂起协议的原 Brain 退出、实际 SIGSTOP 后接管及旧实例恢复已通过；受控网络分区组合故障待补）。
- [ ] Hands 启动或结果阶段重启，查询原执行或 unknown，不盲目重跑（运行中两个 Hands 同时替换已通过；未知启动和结果阶段故障待完整验收）。
- [ ] DB 提交后 ACK 丢失，事件和终态去重。
- [ ] 首次消费前死信 / 消息过期，后序不会静默跳过。
- [ ] Hands / DB / MQ 故障不越过提交边界；积压有界。
- [x] 多副本 SSE 重连和多订阅，按持久游标完整重放。
- [ ] 文件 / Artifact、Checkpoint / fork、Subagent 与父子容量（冻结 n 的五组饱和父子、同子 Session 第二 Turn 和取消已通过，其他资源 / 恢复边界仍待完整验收）。
- [ ] API / SDK / Console / Gateway / Managed 适配器完成切换。
- [x] 删除旧 Managed 调度、bootstrap、周期续租和转发支路（107 个旧运行组件、12 个专属持久类型与六个旧 SQL 移除；删除后全仓和新部署已验证）。
- [ ] Java 模块测试、Spotless、跨模块 clean verify（04:18:40 的全仓覆盖新工具来源 / 投影增量，10880 项、0 failures / errors、182 条件跳过；本轮新增深度接线已过专项；04:58 新全仓在首条传输顺序专项失败，独立 SDK 已复现 Broker 初始位点缺口，后续重复专项通过不抵消该失败）。
- [x] Go 全包测试与构建、中英文文档单测 / 严格构建校验 / 链接检查（2026-10-15）。
- [ ] 正式部署、剩余前端 / SDK 端到端场景与整体验收。
- [ ] 真实通过、Fake 通过、未验证分别记录。

## Core / Harness 兼容性记录

本次改造涉及 Core 三处行为修复：ToolMethodInvoker 与 ReActAgent 传播 SessionLogException，
避免把持久日志失败或内部挂起转换为普通工具结果；ToolExecutor 对已有调用上下文选项增加
RuntimeContext 空值保护。公共 builder、接口、状态序列化和事件结构保持不变，相关专项 83 项已通过。
Harness 的 AgentSpawnTool 增加两处行为修复：持久 spawn 也调用已有 SubagentExecution.childSessionId
宿主钩子；宿主子执行的 SessionLogException 向上传播，不再转换成普通错误文本。没有注入宿主
或钩子返回 null 时，原有持久 ID 推导不变；已有宿主实现需为持久身份返回稳定 ID。既有方法签名、
builder、序列化状态和事件结构未变。SubagentExecution 新增默认返回 false 的 ownsChildState()；
只有显式返回 true 的宿主接管子权限 / 计划状态的初始化，本机原型不再提前读写尚未创建的子日志。
这是兼容旧实现的默认方法增量，已有宿主的本机状态传播默认保留。已同步中英文子 Agent 文档
并增加宿主回归。工作区内其他
Core / Harness 改动是任务开始前已有改动，不能归入本轮。
以下历史阶段记录保留当时的实现与测试结论；当前主链路以本页顶部决策和最新验收记录为准。

### 本轮收尾续跑接口的兼容性增量（2026-10-15）

- Core 新增可选调用级 `AgentFinalization`：宿主可恢复已产生回复的收尾阶段，并包裹
  beforeCallComplete。未提供时保留原 preCall → 推理 → postCall → finish 链；call 与
  streamEvents 仍共用生命周期和最终回复。旧 builder、已有接口方法和 AgentState 格式不变。
- Core 新增可选 `SessionModelInvocation`：宿主可提供模型调用身份或重放已完成响应；调用
  next 时仍经过 SessionRecorder 与 SessionModelPolicy。默认不提供，原记录和策略顺序不变。
- Harness 的 MemoryFlushManager 可读取 RuntimeContext 中的可选 Clock；未提供仍用系统默认
  时区与当前时间。Service 冻结收尾开始时间，避免重放时日期或写入内容时间戳漂移。
- 内联记忆回写及其读取辅助函数传播 SessionLogException，避免把挂起 / 日志失败变成成功；
  普通非日志类读取错误仍沿用原降级。后台记忆刷新保持原日志报告方式。
- Service 使用已注册的 required 私有原生日志事实保存收尾起止与模型调用关联，不改变 Core 原生事件的
  既有结构。框架请求按持久模型位置分组并复用原 ToolTask，未知参数变化拒绝，不重复启动。

- Core 增加 `SessionRecorder.checkpointedRecordNow` 方法：将恢复关键事实与当前状态放进同一提交；
  状态相同时补齐必要 checkpoint，已有待提交 checkpoint 时复用，避免大状态重复进入缓冲。
  原有 `captureState`、`checkpointNow` 和日志格式不变。Service 用于输入应用回执。
- Service 的收尾起始事实同时冻结入口 AgentState、回复和时钟；恢复先回到该入口状态，
  再按持久模型槽位重放已完成辅助响应。请求指纹忽略外层及 Harness 编译系统消息中原始
  消息的自动 id/timestamp，仍校验正文、其他 metadata、模型、工具及生成配置。

接口与专项回归已通过（见本页末尾 44 项记录）；全仓兼容性验证正在执行，尚未完成最终验收。

## 当前验证记录（阶段证据，不代表整体交付）

- 确定性基础协议 18 项、DB 受理 7 项、Hands 原子日志事务 7 项通过。
- Fake 串联 5 项通过：双发布者 / 双 Brain 顺序、跨初始 TTL 续期、发送响应丢失发布屏障、
  Core 提交等待 Hands 回执、事件 ACK 丢失去重、共享大批次和篡改检测。
  其中双 Brain 是 Java 协议对象，尚不是两个 HTTP 服务副本，也没有实际模型或 Sandbox。
- 真实 Redis 3 项通过：令牌替换、Stream 去重 / 顺序 / cancel、阻塞读取与续期连接隔离。
- 真实 RocketMQ 2 项通过：FIFO / 两消费者 / 多次续期 / 跨 Topic、失去本地收据后重投递。
  10 秒测试 TTL 下需把阻塞接收和其他调用耗时计入续期间隔；独立续期调度不可省略。
- 当前未因本轮改动修改 Core / Harness；MQ SessionLog 复用现有 SessionLog SPI。
- 未构建或部署整套新服务，旧开发容器未停止，旧 Managed 链路仍待切换和删除。

- 新增工具恢复 6 项通过：原执行查询和晚到结果、未知启动不重跑、外部幂等启动恢复、启动前取消、
  unknown 工具禁止终态、旧 Hands 不能覆盖新进程句柄。
- PostgreSQL 受理 / 日志事务 14 项通过，与 H2 使用同一组断言。
- 真实协议长工具串联通过（193.5 秒）：六个独立 MQ 连接、两个 Redis 客户端、PostgreSQL、
  180 秒 mock 工具；整轮和工具多次续期，工具结果经过 MQ / Hands / DB 返回，Brain 写入观察日志，
  首轮终态 ACK 后第二轮由另一消费者完成。尚未代表实际模型、ACS 或 HTTP 服务副本。
- 物理测试夹具说明和可复现命令见 tests/managed-mq-redis/README.md。

## 进入服务接入阶段的当前状态

- Managed Portal 已增加 /v1/sessions/{id}/turns 和 Turn 下 inputs 的创建、读取入口；
  events / events/stream 已切到 ma.public_event 持久游标。尚待新的 HTTP 行为测试和客户端切换。
- Managed 创建现在直接注册加密配置并返回 created，不发 runtime bootstrap。
  PortalSessionProvisioningTest 已改为检查这一新行为，并通过；非 Managed 的流程仍保留在原后端。
- 初始受理 / 日志内核累计 43 项确定性测试通过；新增 reply action 开启、去重、结算专项也通过。
- Turn 和 Input 保存真实 caller 身份，避免进入 MQ 后仅剩无法还原的幂等哈希。
  API 对身份 JSON 使用固定字段排序，跨 Portal 实例生成一致的幂等身份。
- Hands 结果存储独立于 Brain epoch；启动恢复读取持久 cancel，Redis 副本丢失也不能忘记取消。
- 公共 SSE 暂复用已有 PostgreSQL LISTEN / NOTIFY 通知组件，按 Scope 指纹隔离订阅；
  漏通知由持久游标回读补齐，不引入新的物理依赖。Hands 服务通知 wiring 尚待完成。
- 三个 Java 服务模块的主源码编译通过；整套部署、全量测试、Core inbox / Agent 接入、
  实际 Hands provider、配额迁移及旧链路删除仍未完成。
- RocketMQ 5.5.0 LiteTopic 是正在进行的能力验证，当前生产适配器仍为已验证的 5.3.2 FIFO。
  目的：消除长 Turn 导致的同物理队列 Session 阻塞；若采用，需要新增 Session 订阅发现流程，
  依赖仍是既有 DB / MQ / Redis。尚未把这项实验记为生产实现或已完成设计。
- 隔离 PostgreSQL 夹具因新增 actor 字段重置了 ma schema，重置前备份在
  /tmp/managed-mq-ma-pre-actor-fixture.sql；仅包含本任务构造的数据，未动用户旧开发库。

## 实际 Harness / Hands 接入进展

以下为新增阶段证据，仍不代表完整部署或迁移完成：

- 新增 ManagedBrainConfiguration 与有界消费循环，按完整 Turn 持有 MQ 消费和 Redis Guard。
  新增 ManagedHarnessTurns，包含实际 Harness 调用、交互等待、输入关闭与收尾结算。
  Consumer 启动开关尚未接入整套部署，旧 Managed 源码和适配器尚未删除。
- 实际 Harness 专项通过：注入、原生检查点、Hands RPC、持久工具结果、继续推理以及同步内存整理。
  模型和外部工具仍为明确的确定性模拟，未访问 ACS。
- 新增独立平台 Hands Spring 入口及事件消费 / 工具执行池；新增可查询的 Sandbox 稳定执行 ID 日志。
  本地真实进程模拟 5 项通过：重复启动、Worker 重建查询、响应丢失、取消确认、文件传输。
  cloud provider 实现编译通过，真实云服务、self_hosted HTTPS 新协议与整套启动尚未验证。
- Brain 接管专项 3 项通过：在新 epoch 重建内置工具结果后继续推理；没有原 ToolTask 时不创建新副作用；
  非内置外部工具保留为需要明确协调的状态。使用真实 Harness、H2 和 Fake 传输，未模拟实际进程分区。
- 增加原生工具未结算索引，Hands 事务禁止未知原生工具结果的 Turn 终态。
  Core 的 SessionRecovery 管理事件不带 Turn ID，服务 SessionLog 适配器将其绑定到当前有效 Turn。
  未改 Core / Harness 公共 API 或实现。
- 新增共享预算账本，Brain 在模型派发前预留，Hands 与 model/end 原生事实在同一事务结算。
  子 Session 通过 root_session_id 共享预算；Portal 预算读写取消 Brain HTTP 转发。
  预算 4 项通过：幂等预留、限额、事务回滚 / 重放、子 Session 共享、旧代次未知用量约束。
- 恢复按 Application / 用户作用域的队列容量、请求限速、待处理 Input 上限；预留取消槽位。
  已应用长前缀不会遮蔽后续待处理 Input。对应 2 项通过。
  SSE 增加连接和重放上限；新的 HTTP / SSE 行为与压力测试仍待补齐。
- Public Event 不写入框架内部 RPC 原始结果；普通 ToolTask 完成只暴露 ID / 状态。
  工具展示结果来自原生 Agent 事件投影，避免内部指令、内存文件、上传字节进入公开流。
- 当前新增与相关受影响专项 24 项通过；独立 Hands 进程 5 项和 Portal 创建 / 预算 2 项另有通过记录。
  未完成全量 clean verify、Go / UI、部署及全部客户端改造。

## LiteTopic 版本验证决策

- 5.5.0 的 Lite Simple Consumer 服务端不支持；官方 5.5.1 发布说明包含该能力。
- Docker Hub 暂无 5.5.1 官方标签，隔离测试镜像用官方发布二进制和官方 SHA-512 校验构建；
  Java 基础镜像为固定 digest 的 5.5.0，实际 RocketMQ 类路径全部来自 5.5.1。
- 测试 ConsumerGroup 必须设置 lite.bind.topic、lite.sub.model=Shared 与从最小 offset 消费。
- 5.5.1 启动成功后，subscribeLite 仍在服务端 ClientProcessor.syncLiteSubscription:134 报空指针。
  原因尚未确认；到 15 分钟排查上限停止这一支实验，保留可复现夹具和明确失败记录。
- 当前生产适配器继续使用已通过验证的经典 FIFO。其物理队列阻塞、父子容量问题仍为未完成项，
  不能将“MessageGroup 是 Session”直接当成跨 Session 并行已验证。

- Application 旧策略里的 maxConcurrent / tokenBudget 也进入新受理与模型预算检查；
  当前先保守合并 CP 的既有用量和 Managed 本地提交用量。后续管理统计同步必须区分
  已包含的 Managed 用量，避免重复计入；这一项仍未作为“配额迁移全部完成”交付。
- Input 拒绝原因单独持久保存，查询可见；应用 / 拒绝回执也确认 delivered。
- Sandbox 执行目录仅能保证日志存在时重复 start 不重新运行；不能将目录丢失当作未执行证明。
  实际 Sandbox 适配器不声明缺失状态下可安全重新启动，恢复必须查询原执行；NOT_FOUND 保留 unknown。
- self_hosted HTTPS 链路将由 Hands 直接调用 Control Plane 的既有 verify-key 接口。
  部署依赖图需要补充 Hands → Control Plane 这一条密钥校验依赖。

## 最新接入验证与下一阶段入口

- 新增 self_hosted Worker HTTPS 协议 / Hands 服务端：claim、renew、started、result。
  Gateway 已增加 /v1/hands/environments/*/tool-tasks/** 路由到独立 Hands 服务。
  多实例续期、旧令牌拒绝、晚到原结果、重复回执与公开内容隔离专项 1 项通过。
  这是两个服务对象 + 真实 key 校验 HTTP + H2 / Fake MQ、Redis，不是两个实际部署副本。
- 新增 ManagedEnvironmentWorker，默认 CLI 切到这一新协议；独立线程续期，结果先写本地持久文件，
  收到 Hands 的 DB 提交确认后删除。已有 Sandbox 仅连接，未知执行不重启。
  独立 Worker 主流程已编译，尚待真实或显式模拟的 HTTP / provider 端到端验证。
  原 ToolTaskHandsWorker 尚未删除，接入和验证完成后删除旧 Managed Worker 支路。
- self_hosted 借用 Sandbox 新增唯一身份占用，防止两个 Session 混用同一实例；
  定义文件初始化复用同一方法，只创建缺失文件。借用身份释放仍待生命周期迁移。
- 取消等待确认并发流程专项通过：取消会写原生 interaction/resolved、状态检查点和 turn/cancelled；
  后续 Turn 可以正常推理，不继承 ASKING。恢复 / 取消专项现在 4 项通过。
- 新 schema 的 PostgreSQL 受理 7 项、日志事务 8 项通过，另外队列 / Application 配额 3 项、
  Session / Application 预算 5 项通过。本次夹具起初没有 ma schema，没有删除任何已有数据；
  当前测试创建了新 schema。真实运行服务的数据未改动。
- Hands HTTP 1 项和本地 Sandbox 进程 5 项在最新代码下通过。
- 修正 Harness 工厂：新 Managed Session 只装配 Hands RPC filesystem，避免同时留下旧 Environment
  filesystem spec 与 abstractFilesystem，触发框架互斥配置检查。
- 原生工具未结算索引、Input 拒绝原因、Application 配额与未知用量策略已有实现；
  管理统计同步的用量去重、整个 HTTP / SSE 合约、客户端 / Console、文件 / fork / subagent、
  生命周期、完整部署、旧路径删除和全量构建均仍是未完成项。
- 当前没有因本任务修改 Core / Harness；未部署整套服务，未提交或推送。

下一阶段先验证并切换 Session HTTP 查询 / SSE、SDK 和 Console，再完成独立 Worker 场景与部署；
不能以当前专项通过替代整个改造验收。经典 FIFO 的物理队列阻塞仍需解决或明确约束。

## Session 查询、快照与 SSE 接入记录

以下为 2026-10-14 的最新阶段证据，完整目标仍未完成：

- `/v1/sessions` 增加 Turn 列表与状态过滤、Input 列表 / 应用回执、公开事件分页、
  资源快照与分页、Session / Turn 能力查询。共享预算入口切到 `/v1/sessions/{id}/budget`。
- Turn 新增 admission_sequence 与 created_at，用于稳定分页；不改变实际 Broker 发布排序。
  游标分别绑定完整 Scope、资源种类、Turn、排序与过滤条件，不能跨查询使用。
- REST / SSE 共享 schema_version、事件 ID、Session ID、类型、created_at、cursor 与 data。
  SSE 帧 ID 是游标，JSON ID 是事实标识；每 15 秒发送无 ID 的 keepalive 注释。
  Last-Event-ID 优先于 after；事件空页仍返回续传游标。
- Hands 的日志事务同时维护增量展示资源与公开用量摘要；新的 ma.public_resource /
  ma.public_resource_version / ma.snapshot 支撑固定公开游标的快照和分页。
  资源写入失败会回滚日志事务，不能先 ACK 再补展示状态。
- 决策：快照有效 10 分钟，每 Session 最多 32 个活跃快照；同一公开游标在剩余时间
  足够时复用快照。只为正在使用的快照保留变化前版本；后台每分钟有界清理 64 个作用域。
  过期返回 410 / snapshot_expired。原生日志 / Checkpoint 不进入这些公开资源。
- 决策：内核终态 succeeded 对外使用 completed / turn.completed；等待交互使用
  turn.requires_action。查询、SSE、资源投影和客户端既有展示词汇一致。
- Session 元数据的运行状态、用量和公开序号改读 ma 账本；title / metadata 等资源管理
  仍有旧 SessionApi 辅助表和读取代码，待统一 CRUD 与旧链路删除时收敛。
- 能力查询只报告当前接入范围：Input 操作和预算可用；文件、文件输入、Artifact、
  Fork 与检查点恢复仍为 false，后续实现并验证后再开启。
- 三个 Java 服务及其依赖在当前源码下编译通过；专项 80 项通过：common 64、Portal 10、
  Hands 6，其中 20 项使用真实 PostgreSQL。实际 Harness 工具 / 恢复专项包含在本次结果中。
  日志：/tmp/managed-mq-canonical-query-tests.log。
- 最后补充预算 HTTP 路由检查后，Portal 查询 / SSE 9 项及预算 1 项再次通过；
  日志：/tmp/managed-mq-final-http-query-tests.log。未把重复运行计入新的总数。
- 隔离 PostgreSQL 测试 schema 在增加分页、快照等字段后直接重建；重建前测试数据备份：
  /tmp/managed-mq-ma-before-public-resources.sql。只动本任务 Compose 夹具，未动旧开发数据库。
- 中英文 Session API 文档更新。docs 单元检查 13 项、Mintlify 页面构建和 broken-links 通过。
  npm run validate 在前置导航检查失败：5 份已有内部设计稿未列入导航；没有把它记为通过。
- 本次没有新增 Core / Harness 修改，没有提交、推送或部署整套新服务。

接下来继续统一 Session CRUD、SDK / Console 和入口适配器；随后完成生命周期、文件 / Fork /
Subagent、多副本部署、旧 Managed 代码删除和全量验收。资源分页目前限制记录数，文件和
大输出接入阶段还需补充响应字节上限。经典 FIFO 的物理队列阻塞与父子容量仍是未完成项。


## Session 资源与 Console 协议切换

- SessionApi 改为资源 CRUD；title、metadata、creation_request_digest 存放在 Portal Session
  资源行。创建请求完整摘要保护幂等重试；重复创建不会覆盖后续更新的标题或元数据。
- 删除 SessionApiJournal / SessionApiReads、三套对应实体和 Repository，以及
  builder_session_api_state / event / input 旧辅助表定义。其他运行后端的资源投影不再调用
  这套辅助投影。旧 Managed bootstrap / turn / lifecycle 等支路仍须继续清理。
- 资源创建和更新事实进入 ma 公开游标；仅允许 Session 资源事件，不绕开执行事实的
  Hands 代次与提交检查。该写入显式加入外层 JPA 事务，避免 JDBC 内层提前提交。
  真实 Portal HTTP / 认证 / JPA 专项验证：事件写入后抛错，标题与事件一起回滚。
- 决策：Session 配置保持固定；创建可选 memory_store_ids / vault_ids / resources /
  agent_overrides；后续仅编辑 title / metadata。Console 的配置表单改为固定绑定展示。
- 增加 Turn 专属快照与用量查询；快照固定于同一 Session 公开游标，并按 Turn 过滤
  当前资源和保留版本。Session、Turn 用量摘要随公开模型事实增量结算。
- 增加公开 Hands ToolTask 进度，只展示与原生工具关联的任务；不展示维护 RPC、
  原始参数、结果、Worker 令牌或实现名称。新增 native_tool_call_id 索引字段避免查询
  进度时加载大型私有参数与结果。工具原结果和未知状态仍按既有安全恢复规则处理。
- Console managedSessions / agentSessions 受理和查询路径切到 /v1/sessions；steer、inject、
  reply、cancel 均提交 Turn 下 inputs。多待办回复使用每个 action 的稳定派生键。
  注入必须指定 Turn；取消响应保持受理回执。预算显示共享账本已应用限额。
- 删除未被使用且只处理旧 Managed 数值事件的 ChatPanel；会话执行使用已有
  SessionExecution 与新资源快照。conversation 适配器改为公开事件对象。
- 前端全量 175 项通过，构建通过；完整 lint 0 errors，剩余为既有警告，已清除本次
  测试引入的 8 条未使用参数警告；改动文件单独 lint 无输出。服务客户端其他 SDK、
  Endpoint / Channel / Cron 和通用 serviceSessions 适配器尚未完成切换。
- 最新 H2 / HTTP 专项通过：日志 9、资源快照 6、实际 Harness 1、Portal 资源 HTTP 8、
  Turn / SSE 9、预算 1。日志 /tmp/managed-mq-crud-console-backend-tests.log。
- PostgreSQL 夹具再次按新字段重建，备份在 /tmp/managed-mq-ma-before-turn-pages.sql。
  验证发现 NULL 占位符类型推断问题，已经改为 COALESCE 比较；真实复跑结果另行登记。

明确未完成：SessionApi.lifecycle 仍调用旧 PortalLifecycles，created 状态及新 Turn 账本
与其检查不一致，archive/delete/restore 尚未完成。不能把当前 Console 构建通过当作这些
流程已跑通。下一阶段先替换生命周期、统一剩余 SDK 与入口适配器，随后继续文件 / Fork /
Subagent、完整部署与旧 Managed 代码删除。完整目标与所有验收项保持不变。


本阶段最终验证：

- PostgreSQL NULL 参数推断修正后的 3 个服务模块及依赖构建、专项 55 项通过：
  common 32、Portal 17、Hands 6。其中 PostgreSQL 22 项（受理 7、日志 9、资源快照 6）。
  记录 /tmp/managed-mq-latest-crud-real-db-tests.log。
- 前端改动文件 lint 无输出；最终构建通过。175 项全量测试与完整 lint 0 errors 的证据
  保留在 /tmp/managed-mq-console-all-tests.log、/tmp/managed-mq-console-lint.log。
- 当前 Java 改动文件 Spotless 通过，git diff --check 无输出。
- docs 单元 13 项、直接 Mintlify 构建验证、broken-links 通过。npm run validate 仍在
  5 份内部讨论稿未列入导航处失败，没有修改这些稿件的站点可见性来绕过检查。
- 没有因本阶段修改 Core / Harness，没有提交、推送或启动整套新服务。

## 生命周期关闭、停止与清理

- archive / delete / restore 切到 ma.lifecycle_command，不再调用旧 PortalLifecycles。
  生命周期版本、指令幂等和 Session 关闭在受理同一把持久 Session 锁下提交；Portal 资源镜像
  与 ma 事件加入同一外层事务。旧指令重试不能覆盖恢复后的新生命周期版本。
- 决策：归档和删除关闭新 Turn / Input；通过独立生命周期取消信号停止已受理任务，不向
  已关闭 Input 集合追加新输入，不改动原截止序号。Redis 丢失后从 DB 重新投递该信号。
  排队 Turn 仍按 MQ 顺序结算，不能绕开前序保护直接标为完成。
- 清理独立使用两个有界线程，Redis token TTL 60 秒 / 续期 15 秒，并检查本地单调截止时间。
  DB 每秒有界扫描 64 个行政清理指令；这是生命周期清理，不是 DB 执行调度或 DB 续租。
  Turn 和 ToolTask 全部确认终态后才清理；未知副作用不被当作已停止。
- 决策：archive 保留 Workspace 绑定，restore 在归档清理完成后启用新版本；delete 清理
  已知的服务自建 Workspace 或释放 self_hosted 借用绑定。借用实例不执行外部删除。
  未确认创建不能创建或删除替代实例。Provider 长期保留 / 暂停策略仍须后续补齐。
- Hands 获取执行器不再立即创建 Sandbox；第一次真正的 start / query / cancel 才连接，
  避免取消已受理但尚未启动的任务时创建新实例。工具轮询也重新读取持久取消状态。
- HTTP 返回 lifecycle_command_id / cleanup_status；删除后可按原作用域读 lifecycle 回执。
  stopping / cleaning 与 completed 分开。Console 查询回执显示删除清理状态，归档清理未完成
  时禁用 restore / delete，并继续刷新；不会把受理成功显示成清理完成。
- 当前专项 67 项通过，包含真实 PostgreSQL 26 项（受理 7、日志 9、资源 6、生命周期 4）；
  Portal HTTP 21 项、Hands 10 项。日志 /tmp/managed-mq-lifecycle-real-db-tests.log。
  PostgreSQL 仅重建本任务隔离 ma 测试 schema；备份 /tmp/managed-mq-ma-before-lifecycles.sql。
- Console 全量 176 项通过，构建通过，改动文件 lint 无输出；日志分别为
  /tmp/managed-mq-lifecycle-ui-all-tests.log、/tmp/managed-mq-lifecycle-ui-final-build.log、
  /tmp/managed-mq-lifecycle-ui-lint.log。没有因本阶段修改 Core / Harness。

## 文件与 Artifact 接入（阶段证据）

- 第一版使用共享数据库的 ma.file 存储不可变二进制内容和元数据，不新增对象存储组件，
  不经过 Portal → Brain 的文件转发。每文件最多 16 MiB，每 Session 最多 256 个文件、
  总内容 256 MiB；一次输入最多引用 128 个文件、总内容 32 MiB。大文件对象存储可后续替换。
- 文件 ID 绑定完整作用域、原始调用身份和幂等键；同键不同内容 / 名称 / 类型返回冲突。
  文件事实和公开事件在同一个数据库事务提交。列表只读元数据，不加载所有文件内容。
- Turn / steer / inject 保留不可变 file_id 引用；受理前验证本 Session 引用，Brain 执行时
  校验 SHA-256 并转换为原生内容块。MQ 只接收引用；恢复状态的大内容沿用私有批次 Blob。
- 删除 Workspace 成功后清除上传文件内容，保留元数据与摘要；归档保留内容。
- 显式 Artifact 发布进入 ma.artifact 和同一公开资源投影，可绑定本 Session 的 file_id 和
  可选 turn_id，或声明 HTTPS 外部 URI 元数据。外部内容从 URI 获取，不增加服务器端下载依赖。
  同身份同键必须保持输入一致；每 Session 最多 256 份发布记录。取消发布幂等，不删除原文件。
- Artifact 列表与详情支持固定 snapshot_id；删除后当前详情 / Artifact 下载返回未找到，
  固定旧快照仍能读取发布时元数据。资源单条读取只查询指定键，不加载整页所有资源。
- files / file_input / artifacts 能力已开放，Console 现有文件与交付物入口使用这些新路由。
  实际 Harness 专项同时验证初始输入和轮内 inject 文件内容，工具结果回传后继续模型推理。
- 文件专项 40 项通过，包含 PostgreSQL、HTTP 与原生 Harness。统一验证日志另行登记；
  文件 HTTP 回滚使用数据库约束拒绝事件写入，直接证明已插入文件内容随失败回滚。
  Artifact 专项验证数值类型、幂等、作用域、快照保留、删除和事件失败回滚。
- 尚未完成 Sandbox 生成交付物的自动发布、Fork / Checkpoint、Subagent、SDK / 其他入口、
  多副本整套部署、旧链路删除和全量验收。没有因本阶段修改 Core / Harness。

本阶段统一验证：

- Java 专项 89 项通过：common 55、Portal 24、Hands 10。其中 PostgreSQL 33 项，
  包含文件 4、Artifact 3、生命周期 4、受理 7、日志 9、资源快照 6。
  实际 Harness 包含文件初始输入 / inject、Hands 工具和结果后继续推理；MQ / Redis 使用
  显式 Fake。Hands 进程工具测试使用真实本地进程。没有实际 ACS Provider 验证。
  日志 /tmp/managed-mq-lifecycle-files-artifacts-verification.log；3 个 Java 服务及依赖编译通过。
- 统一运行同时执行了 Spotless；git diff --check 无输出。
- 前端全量 177 项通过，最终构建通过，改动文件 lint 无输出。日志
  /tmp/managed-mq-files-artifacts-ui-all-tests.log、/tmp/managed-mq-files-artifacts-ui-final-build.log、
  /tmp/managed-mq-files-artifacts-ui-lint.log。
- 中英文 API 文档补充生命周期、文件和交付物；文档单元 13 项、直接 Mintlify 页面构建
  与 broken-links 通过。npm run validate 的前置导航问题仍是之前 5 份内部设计稿未列入导航，
  没有据此把整条命令记为通过，也没有改这些内部文档的可见性。
- 全部修改位于 /Users/ken/agentscope-3/agentscope-java，分支 codex/automatic-acr-release。
  没有部署整套服务、提交、推送或修改 Core / Harness；完整改造目标仍未完成。

下一步优先完成 Managed 原生客户端和其入口适配，随后补齐 Fork / Checkpoint、平台
Subagent、多副本整套部署和旧 Managed 删除。保留非 Managed 的客户端与运行路径；
不能把修改所有客户端的全局路径前缀当作 Managed 迁移完成。当前单元 / 协议对象验证
仍不能代替两个实际 Portal / Brain / Hands 副本验收。

## 原生 Managed SDK 接入

- Java / TypeScript `ServiceClient.managedSessions()` 与 Python `managed_sessions()` 提供
  `/v1/sessions` 原生客户端；复用已有身份、超时、有界 JSON / SSE / 文件传输，不复制网络栈。
  非 Managed 的平铺客户端继续保留其协议，原生客户端不做旧路径回退或请求兼容翻译。
- 创建直接传 agent / environment_id 等固定定义；Turn 自动包装 input 对象；轮内操作统一
  为 steer / inject / reply / cancel Input。回执按 Turn 验证，生命周期回执按 Session 验证。
  取消 / 生命周期须传显式幂等键；SSE 校验每条事件的 Session ID，断开不发取消。
- 文件、显式 Artifact、预算、用量与固定资源页接入。Turn 快照在 Session resources
  路径翻页并保留 snapshot_id；用量查询保留 data / as_of。未添加不存在的 Fork /
  Checkpoint / 工具恢复 / Webhook 原生入口。
- Portal 测试仅以 test scope 依赖 Java client：真实 HTTP / 认证 / JPA 验证 SDK 文件上传、
  下载、交付物、快照、Turn / Input 回执、SSE、归档和关闭后拒绝新工作；执行 MQ / Redis
  仍是明确 Fake。Java client 与 Portal 专项合计 34 项通过。
  日志 /tmp/managed-mq-native-sdk-portal-tests.log。
- TypeScript SDK 全量 25 项与构建通过；Python SDK 全量 113 项通过，新增模块语法兼容
  Python 3.9。SDK 子目录未选中根目录 pyenv，测试固定使用
  /Users/ken/.pyenv/versions/3.14.5/bin/python3；没有修改用户的 Python 安装或环境选择。
  日志 /tmp/managed-mq-typescript-sdk-tests.log、/tmp/managed-mq-typescript-sdk-final-build.log、
  /tmp/managed-mq-python-sdk-all-tests.log。
- 更新 Java / DSH 中英文 README、Python README 与中英文 Session API 页面。
  当前没有修改 Core / Harness；没有提交、推送或部署整套服务。

入口适配仍未完成：Go product.ManagedServiceRequest 使用旧内部身份头和旧 Managed
路径；其 turn、steer、cancel、actions、Snapshot / Event 读取都需要一起迁移，不能仅
替换全局 URL 前缀，否则当前公开 Portal 原始身份检查会拒绝请求。后续将复用已有可信
运行协议来承载原始 Actor 与完整作用域，不扩展权限体系。Endpoint / Channel / Cron、
通用 Console 入口、Fork / Subagent、完整多副本部署与旧 Managed 删除仍未完成。

## 原生文件内容与公开事件的边界

- 发现已解析文件的 Base64 会通过原生 message/user 投影进入公开 item 内容，2 MiB 输入
  已会超过 SDK 的 1 MiB SSE 上限。仅增加上传大小检查不能解决这个问题。
- 在 ma 原生日志事务的公开投影入口转换媒体内容：已上传文件按当前完整 Scope、
  内容 SHA-256、媒体类型和大小匹配，带 file_id 的内容还必须匹配该 ID；公开只写不可变
  文件引用及下载地址。图片等无 ID 旧媒体块可按相同内容匹配当前 Session 文件。
- 原生日志与模型上下文继续保存完整内容；未匹配的二进制只公开 content_omitted /
  binary_content_private 元数据，不泄漏其他 Session 文件或错误地绑定伪造内容。
  未修改共用 CommittedSessionEventProjector，也未修改 Core / Harness 的序列化或行为。
- 实际 Harness 增加 2 MiB 文件参数场景，初始输入和 inject 都保留完整原生字节，
  Hands 工具返回后继续推理，公开事件全部小于 1 MiB 并携带正确文件引用。
- 统一 Java 验证 102 项通过：client 9、common 58、Portal 25、Hands 10；其中 PostgreSQL
  34 项。日志 /tmp/managed-mq-native-sdk-public-files-tests.log。Spotless 同次通过，
  git diff --check 无输出；3 个 Java 服务及客户端编译通过。
- 本阶段 SDK 文档单元 13 项、直接 Mintlify 构建及 broken-links 通过；原有完整导航检查
  问题仍未改变。当前未做整套多副本部署，也没有实际 ACS Provider 验证。

仍须补齐生成二进制交付物的自动导出、超大文本 / 工具输出的公开字节上限与下载引用，
以及上述入口迁移、Fork / Subagent、完整部署、旧 Managed 删除和全量检查。
这份记录不把元数据省略标记当作交付物导出完成。


## Go 可信运行桥接与当前总体进度

2026-10-14 当前按总工作量估算约 60%，不是验收通过率。运行主链路约 85%，API / 配套
接入约 65%，最终多副本验收与旧实现清理约 15%；这些是阶段估算，没有据此勾选完整验收项。

- 新增 ManagedRuntimeController 与 Go ManagedRuntimeRequest / Provision：可信服务角色携带
  完整 RuntimeContext，原始 Actor、key_hash、Application、Tenant / Namespace、命令幂等键
  保留在请求体；Internal-User 仅绑定资源 owner。普通公共身份头不能构造该可信载体。
  Portal 复用原生业务 API，并重新检查现有策略；没有扩展权限或建立 owner 地址路由。
- Endpoint Conversation ID 作为稳定 Ma Session ID。首次调用复用 Control Plane 的固定定义
  解析和资源登记，Portal 直接保存配置，随后发布 Turn；不发 Brain bootstrap、不等待 ready。
  SourceEndpointID 记录原 Endpoint 绑定；不同 Endpoint 的凭据策略不会错误共享缓存作用域。
- Managed Invocation 的 Turn 受理、状态、事件、固定快照和轮内操作切到可信原生 RPC。
  快照翻页保留 snapshot_id；结果仅从对应 Turn 的 item 中提取。steer / reply / cancel 走
  原生 Input，使用命令幂等键；Managed resume 能力关闭，保留非 Managed 的原有行为。
- 命令收到 accepted Input 后保持 submitted；input_status / input_delivery_status 分别展示
  应用与投递。后台和查询按持久回执更新状态，同一命令锁避免查询覆盖更晚的终态。
  查询使用当前已鉴权读者，原始提交 Actor 不因读取改变。取消已应用仍需等待 Turn 终态。
- 真正 Go → Java 联调通过：Portal 真实 HTTP / 内部角色 / JPA 与 Ma H2 存储，Go product
  使用本任务 PostgreSQL；验证相同 Turn 重试、Input 提交与查询、Invocation 固定快照，
  以及实际 CP Agent / Environment 配置解析后首次登记、原生 Turn 受理与重试只生成一轮。
  执行 MQ / Redis 为明确 Fake，没有把这项记录为完整部署、两个服务副本或 ACS 验证。
- Portal 专项 28 项通过（19 + 9），含上述 Go 联调，未跳过；日志
  /tmp/managed-mq-go-bridge-portal-final-tests.log。最新 Go 读者调整的 product / httpapi 模块
  测试通过，日志 /tmp/managed-mq-go-original-reader-tests.log。
- Go 全包 go test -p 1 ./... 通过，日志 /tmp/managed-mq-go-all-package-final-tests.log。
  首次默认并行运行的一项 Qoder 进程测试超时，单项复查及串行全包复查通过；未修改该
  Hosted 部分。全包运行没有设置 PostgreSQL DSN，需数据库的专项不算全包真实数据库验收。
  make build 的 4 个 Go 命令通过，日志 /tmp/managed-mq-go-bridge-build.log。
- 改动 Java 文件 Spotless 通过；中英文命令 / 回执说明与 API reference 更新。
  docs 单元 13 项、直接 Mintlify 构建、broken-links 通过；新增链接发现错误后已修复。
  完整 npm run validate 的原有 5 份内部设计稿导航问题没有改变。
- 所有代码仍在 /Users/ken/agentscope-3/agentscope-java、codex/automatic-acr-release。
  没有新增 Core / Harness 修改，没有提交、推送或部署整套服务。

仍未完成：Go Session 文件 / 资源代理、Session 级事件 / 快照和 Fork 的旧 ManagedServiceRequest
支路，Channel / Cron / 全部 Managed 入口迁移；Fork / Checkpoint、平台 Subagent、预算统计
去重、生成交付物与大输出上限；完整两副本故障矩阵、经典 FIFO 跨 Session / 父子容量、
旧 Managed 删除、全量 clean verify 和完整部署检查。Endpoint 桥接通过不等于上述入口全部切换。

- 接入部署配置时发现：BUILDER_DATA_URL 指向 Brain，不能用于新的 Portal RPC。新增
  product.Config.SessionPortalURL，复用部署已有 BUILDER_SESSION_PORTAL_URL，并在主程序
  注入；新 RPC 只使用 Portal 地址，缺失时明确失败，不回退到旧 dataplane 地址。
  Go 传输测试将 DataURL 设为错误地址，验证请求仍发往正确 Portal；相关模块测试通过。
  这一修改不改变其他后端所使用的 DataURL。

- Portal 地址修正后的 Go → Java 冷登记 / Invocation / Input 联调再次通过，日志
  /tmp/managed-mq-go-portal-routing-live.log；真实执行仍使用明确 Fake MQ / Redis。
  文件 RPC 尚未接入，下一步仍须先登记 Ma Session，再物化文件，避免旧代码在文件上传时
  使用尚不存在的运行 Session。原生文件 metadata 字段为 file_id，保留这一精确协议。


## 可信二进制文件接入

- 新增内部 Managed 文件上传入口，使用原始二进制正文；RuntimeContext 通过最多 4 KiB
  的 Base64URL 头传入。服务角色与原始 Actor / Scope 校验后，复用既有 ManagedFilesController
  的权限检查、16 MiB 文件上限、30 秒传输超时与 16 个传输槽位，不复制上传 / 下载逻辑。
  公共接口不能通过伪造这个头构造可信身份。
- Go ManagedRuntimeUpload 与 JSON RPC 共用可信 HTTP 客户端、身份校验、响应字节上限
  和拒绝重定向策略，只使用 SessionPortalURL。读取到 16 MiB + 1 字节由服务明确拒绝，
  不以截断后的内容作为成功文件。字节不进行 Base64 正文编码，也不写入 MQ。
- 首次 Turn 先登记 Ma Session，再物化 CP 的文件记录，最后提交原生 AgentInput。
  文件上传使用每个文件的稳定幂等键，原始作用域和 Actor 保留；轮内 steer / inject 使用
  当前已鉴权用户。已经移除此物化路径对旧 ManagedSessionResource 文件转发的调用。
- 新测试验证：3 MiB 二进制完整上传 / 下载、错误租户、过期 Context、未鉴权内部请求、
  普通公共接口伪造头均不能写文件；Go → Java 使用真实 HTTP 验证文件重试及原生文件引用。
- 实际 Go CP 配置解析与 Java Portal 联调新增“第一轮就带文件”：从尚无 Ma Session 的
  CP 资源开始登记，读取 CP 临时文件存储，上传到 Portal，再提交 Turn；重复调用只产生
  一轮且复用物化 file_id。CP 使用本任务 PostgreSQL，Portal 为真实 HTTP / JPA / Ma H2，
  MQ / Redis 仍是明确 Fake；不算完整部署或真实 Sandbox 运行。
- Portal 全类专项通过（新增二进制测试和 Go 联调），日志
  /tmp/managed-mq-binary-upload-portal-final-tests.log；首次带文件联调另通过，日志
  /tmp/managed-mq-first-file-live-tests.log。Go product / httpapi 全模块测试通过，日志
  /tmp/managed-mq-binary-upload-go-all-tests.log。
- 本次没有修改 Core / Harness。代码位置、分支与整体未完成的验收范围保持不变。

仍保留待替换支路：Go 的通用 Session 资源代理、Session 级 events / snapshot、Fork；
不能将本次上传物化完成记为全部文件 / 资源代理、下载和所有入口迁移完成。


## Go Managed Session 统一游标

- Session 级 events / snapshot 改用可信原生 RPC，最多 256 条事件 / 资源记录一页。
  校验每个事件的 Session ID、事实 ID、事件类型和续传游标，拒绝不推进的分页。
- 删除 projectPublicManagedEvents 及 Managed 的 Go Session 日志镜像分支。其他后端继续
  使用自身 Go 日志；Managed REST 和 SSE 读取同一 Ma 公开游标，SSE 帧 ID 使用原生 cursor。
  15 秒注释心跳没有事件 ID，连接取消结束读取，不提交执行取消。
- Managed 快照保留原生 snapshot_id / as_of / resources 页，不再用 Go 日志坐标覆盖 as_of，
  也不改写 Native Turn / file_id。原生 Input 回执返回自身 Turn ID，不改成 Invocation ID。
- 真实 Go → Java 联调验证：人为先写不同的 Go 管理日志，快照仍使用原生游标，所有资源页
  与快照 as_of 相同；events 的续传游标与相同 Ma 公开头一致；Go 管理游标不能用于原生流。
  日志 /tmp/managed-mq-single-cursor-live-tests.log 通过。Portal / Ma H2 / CP PostgreSQL 为
  实际存储 / HTTP，MQ / Redis 为明确 Fake；不算完整多副本验收。
- 最新 Go product / httpapi 全模块测试通过，日志 /tmp/managed-mq-single-cursor-go-tests.log。
  当前仍有通用 Session 资源代理和旧 Fork 调用待替换；完整目标、未勾选验收项保持不变。
  本次未修改 Core / Harness，未提交、推送或部署整套服务。

- 本阶段串行 Go 全包测试与 make build 4 个命令通过，日志
  /tmp/managed-mq-session-cursor-all-go-tests.log、/tmp/managed-mq-single-cursor-go-build.log。
  事件 envelope 的错误 Session、缺失游标、SSE 换行注入和不推进分页另有回归测试；
  空页相同游标合法，Native Turn / item ID 保持原样。最新 product / httpapi 模块测试通过，
  日志 /tmp/managed-mq-native-session-envelope-tests.log。全包 PostgreSQL 条件专项未统一启用，
  真实数据库证据仅为明确记录的 Go → Java 联调，不能替代整套数据库 / 多副本验收。

## Go 资源操作与可信下载切换

- Go 的预算、用量、Artifact 与展示资源查询使用明确的原生操作，保留完整 RuntimeContext、
  原始 Actor、原生资源 ID 与 snapshot_id / as_of；删除通用 ManagedSessionResource HTTP 转发
  和 nativeSessionMapping 全量 ID 映射。旧 ManagedServiceRequest 目前仍有 Fork 调用，尚待替换。
- Portal 可信操作复用既有预算、文件和 Artifact 业务 API，以 Mono 组合异步结果，不在处理
  线程上 block 等待。预算更新携带 expectedRevision，Artifact 支持发布、当前 / 固定快照
  元数据与取消发布。资源首屏可创建固定快照，后续 after 必须携带相同 snapshot_id。
- 文件内容使用固定的内部 GET 路由和有界 RuntimeContext 头，复用公共文件的 16 MiB 限额、
  30 秒超时与 16 个传输槽位。Go 保留原始二进制，拒绝重定向、未知长度和超限响应；外部 URI
  Artifact 只返回元数据，不通过服务抓取。Go Artifact 内容路由复用同一文件下载通道。
- 原生 file_id 可直接用于 Input / Artifact：Go 通过 Portal 校验其当前 Session 作用域，
  不要求该文件必须存在于 Go 上传镜像中。Go 上传 / 文件列表仍有自身存储入口，完整迁移未完成。
- 联调发现并修复两个问题：预算 If-Match 曾错误地解析带反斜杠的引号；Go 的作用域中间件
  曾向 Session JSON 正文注入 tenant / namespace，导致严格预算与 Artifact 对象被改变。
  Session 协议现在保留正文，作用域仍从查询参数、请求头和已登记资源解析及授权。
  其他资源接口保留原有中间件行为；没有新增权限功能，也没有修改 Core / Harness。
- 未实现的 Checkpoint / restore、Subagent、Session 级 inject 和 export 不再转发到旧
  Dataplane 地址，仍明确列为待完成能力。上述接入不能替代其实现或多副本 / 真实 Sandbox 验收。

本阶段最终验证：

- Portal HTTP / 原始身份 / JPA / Ma H2 与 Go → Java 专项 22 项通过，无跳过，日志
  /tmp/managed-mq-typed-resources-live-tests.log。其中 Go Control Plane 使用本任务 PostgreSQL，
  公开资源路由验证标准 If-Match 冲突、原生文件发布 Artifact、完整二进制下载、取消发布后
  当前元数据不可读而旧快照仍可读。执行 MQ / Redis 使用明确 Fake，没有运行 ACS 或多副本。
- Go 全包 go test -p 1 ./... -count=1 通过，日志
  /tmp/managed-mq-typed-resources-all-go-tests.log；未在全包设置 PostgreSQL DSN，数据库证据
  限于上述明确开启的联调。make build 的 4 个命令通过，日志
  /tmp/managed-mq-typed-resources-go-build.log。
- 本阶段 Java 文件 Spotless 通过，日志 /tmp/managed-mq-typed-resources-spotless.log；
  中英文预算版本说明同步，docs 单元 13 项及直接 Mintlify validate 通过，日志
  /tmp/managed-mq-typed-resources-doc-tests.log、/tmp/managed-mq-typed-resources-doc-build.log。
  完整文档导航校验已有的 5 份内部设计稿问题仍保留，未将直接构建当作全部文档检查通过。
- 代码仍在 /Users/ken/agentscope-3/agentscope-java、codex/automatic-acr-release；没有提交、
  推送或部署整套服务，未新增 Core / Harness 改动。全目标及完成审计仍保持未完成。

下一步继续迁移 Go Session 文件入口及 Fork，补齐 Checkpoint / 平台 Subagent / 其他 Managed
入口；随后完成完整多副本故障矩阵、经典 FIFO 跨 Session 与父子容量、旧实现删除和全量验证。

## Managed Session 文件存储统一

- Go 的 Managed 文件上传、分页、元数据和内容下载直接使用 Portal 的不可变文件存储；上传
  返回原生 file_id。删除 nativePublicSessionInput、NativeID 以及提交时复制 / 改写文件的支路。
  Turn、steer / inject 和 Artifact 直接保留原生引用，由 Portal 校验归属、容量和字节上限。
  非 Managed 后端保留自身文件存储与 Issue 附件流程，不新增旧 Managed 数据迁移。
- 资源登记与 Turn 提交分开：首次文件或资源操作发现 Ma Session 不存在时，复用已登记
  Control Plane Session 的固定定义和原始 Actor 完成 Portal 登记，不创建 Turn、不发 Brain
  bootstrap、不等待 ready。Endpoint 首次 Turn 复用同一登记逻辑，失败时保留原错误而不
  回退旧地址。用于定义解析的旧物理 Session 等剩余实现仍须在总清理阶段删除。
- Managed capabilities 来自 Portal，因此文件能力不依赖 Go ArtifactProvider，尚未实现的
  Fork / Checkpoint 不再被 Go 的静态能力表宣告为可用。
- 超限上传联调发现：413 拒绝后复用上传 HTTP/1 连接可能使后续请求收到 EOF。Go 的流式
  上传现在在结束后关闭连接，避免残留未读正文返回连接池；普通 JSON RPC 继续复用连接。
  原生文件限额仍是 16 MiB，超限拒绝后未创建文件，后续正常操作已通过联调。
- 原账户专项不再模拟旧 Dataplane REST 和伪造完成事件，改用真实 Java Portal 测试服务，
  保留账户登录、固定 Agent 版本、应用凭据投影与当前角色变化检查。执行 MQ / Redis 仍为
  明确 Fake，受理 Turn / Input 不等于执行完成，没有据此宣告模型或 Sandbox 验收通过。

本阶段验证：

- /tmp/managed-mq-native-files-final-live-tests.log：22 项 Portal / 预算测试全部通过，无跳过；
  其中实际 Go → Java 同时执行 product 传输、Endpoint / 冷文件和账户 / 应用三组专项。
  Portal 为真实 HTTP / 原始身份检查 / JPA / Ma H2，Control Plane 使用本任务 PostgreSQL。
- 冷文件专项验证没有已有 Ma Session、没有 Go ArtifactProvider 时上传 3.125 MiB 文件；
  重试复用 file_id、内容变化 409、16 MiB + 1 字节上传 413，Go 存储没有文件副本。
  随后 Turn、轮内 inject、文件页 / 元数据 / 下载、Artifact 发布 / 下载 / 取消发布通过；
  旧快照保留发布元数据，原生公开游标与已有 Go 管理日志坐标保持区分。
- /tmp/managed-mq-native-files-all-go-tests.log：Go 全包串行测试通过。全包未统一启用
  PostgreSQL 条件专项，真实数据库证据限于上述明确开启的联调；不能替代完整数据库验收。
- EN / ZH service-api 更新为 Portal / Brain / Hands 部署职责与原生文件引用。
  docs 单元 13 项及直接 Mintlify validate 通过，日志
  /tmp/managed-mq-native-files-doc-tests.log、/tmp/managed-mq-native-files-doc-build.log。
  既有文档导航问题、全量 clean verify、完整部署及多副本 / Sandbox 故障矩阵仍未完成。
- 代码仍在 /Users/ken/agentscope-3/agentscope-java、codex/automatic-acr-release。
  本阶段没有修改 Core / Harness，没有提交、推送或部署整套服务。总目标保持进行中。

- 本阶段 make build 的 4 个 Go 命令通过，日志 /tmp/managed-mq-native-files-go-build.log；
  修改的 Java 测试文件 Spotless 通过，日志 /tmp/managed-mq-native-files-spotless.log。

## 原生 Checkpoint 索引与查询

- Hands 写入 state/checkpoint、migration/baseline 和 state/restored 时，在原日志事务内记录
  ma.state_checkpoint 元数据及 ma.checkpoint_file 文件集合；事件、索引、文件关联和应用
  回执一起提交或回滚。原生 stateJson 仍仅保存在私有日志中，索引只存状态摘要校验值。
- 文件集合表示该检查点持久提交时已有的不可变文件成员；后续上传不改变旧集合。
  这不是 Sandbox 可变工作目录的快照，也不表示 Fork 已能安全复制全部工作状态。
- PortalSessionCheckpoints 删除 WebClient、旧 Dataplane 地址和远端转发。新增
  /v1/sessions/{session}/checkpoints 与 /{checkpoint} 查询，复用当前原始身份与作用域检查。
  Go 通过固定可信操作查询，Java / TypeScript / Python 原生 SDK 增加对应只读方法。
- 分页直接读取索引，不扫描或加载整个私有事件 / 状态历史。独立游标绑定完整 Session
  作用域及固定 native head，limit 为 1..100；through=as_of 可重读同一前缀。
  SSE / 展示快照 / 其他 Session 或游标类型不能交换，后续提交不会进入已固定的分页。
- 决策：不回填旧日志或旧运行存储，不保留 cp1 等旧句柄兼容路径。只索引本次新提交的
  检查点。公开元数据没有 stateJson、提示词、推理内容、状态校验值或执行令牌。
- checkpoints 能力只表示元数据查询已实现；checkpoint_restore 与 fork 继续为 false。
  旧 Go Fork 转发、稳定前缀验证、独立 Sandbox 文件快照和恢复基线仍须继续实现与替换，
  不能把本次索引接入作为 Checkpoint / Fork 整体目标完成。

已完成验证：

- /tmp/managed-mq-checkpoint-index-final-java-tests.log：50 项 Java 测试通过，无跳过，包含
  client 3、common 24、Portal / 预算 23。common 显式开启真实 PostgreSQL 的日志及索引
  专项；Portal HTTP / JPA / Ma H2、Java SDK 及 Go → Java 原始账户 / 应用查询通过。
  Go Control Plane 使用本任务 PostgreSQL，执行 MQ / Redis 为明确 Fake。
- H2 与 PostgreSQL 均验证固定分页上界、文件集合不随后续上传变化、事务失败完整回滚、
  跨 Session / 游标类型拒绝和私有状态不进入元数据。Portal 与 Java SDK 读取实际已提交
  检查点，Go 验证原生空页及缺失句柄错误，未把模拟受理当作模型或 Sandbox 执行完成。
- Go 全包串行测试通过，日志 /tmp/managed-mq-checkpoint-index-all-go-tests.log；全包未
  统一开启 PostgreSQL 条件项，其数据库证据限于上述明确开启的专项。
- TypeScript 26 项测试与构建通过；Python 全包 114 项通过。日志
  /tmp/managed-mq-checkpoint-index-ts-tests.log、/tmp/managed-mq-checkpoint-index-ts-build.log、
  /tmp/managed-mq-checkpoint-index-all-python-tests.log。
- EN / ZH 查询说明已同步，docs 单元 13 项与直接 Mintlify validate 通过；日志
  /tmp/managed-mq-checkpoint-index-doc-tests.log、/tmp/managed-mq-checkpoint-index-doc-build.log。
  原有完整文档导航问题和全量 clean verify、部署、多副本故障矩阵仍未完成。
- 代码位置 / 分支仍为 /Users/ken/agentscope-3/agentscope-java、codex/automatic-acr-release。
  本次未修改 Core / Harness 的源码，没有提交、推送或部署整套服务，总目标继续进行。

- 实际 Harness / 工具恢复回归 6 项通过（2 + 4），日志
  /tmp/managed-mq-checkpoint-index-harness-tests.log。包含 2 MiB 文件输入、轮内 Input、Hands
  工具结果和后续模型步骤，并验证实际提交的 Checkpoint 进入索引及保留原生文件成员。
  模型、MQ / Redis 为明确模拟，并非真实 ACS 或完整多副本部署。
- make build 的 4 个 Go 命令通过，日志 /tmp/managed-mq-checkpoint-index-go-build.log。
- 本阶段修改的 Java 文件 Spotless 检查通过，日志
  /tmp/managed-mq-checkpoint-index-spotless.log；git diff --check 通过。

## Restore / Fork 持久校验基础

- 新增 ManagedCheckpointOperations 及 ma.checkpoint_operation / ma.checkpoint_hold。命令以
  原始 Actor 和幂等键登记，固定源 / 目标 native head 与检查点序号，按完整作用域排序锁定
  Session。要求源与目标同资源作用域、Agent 定义一致、执行已结算，Fork 目标没有原生日志。
- 校验进度保存在命令中，单次最多 128 事件、2 秒或约 32 MiB；单事件最多 16 MiB，
  运行 / 工具 / 交互集合最多 4096 项，进度 JSON 最多 256 KiB。实例替换后从已保存游标
  继续；源或目标前缀变化会拒绝，而不是继续使用已失效的校验结论。
- 检查所选检查点自身是否仍有未知工具或开放交互，并检查 Agent 状态中的 ToolUse 是否
  已有非 suspended 结果，覆盖“已生成 ToolUse、尚未写 dispatch”的空隙。当前日志最终已
  结算不能让早期不稳定检查点变得可复制；最终前缀仍有活动运行时也拒绝继续。
- 非终态命令持有持久的任务准入屏障，新增 Turn、文件 / Artifact 写入和生命周期变更在
  同一 Session 行锁下检查屏障。已有幂等回执仍可读取。这里只是持久行政操作状态，不存
  活跃租约、不新增数据库续租或 owner 实例路由；执行层继续使用原 MQ / Redis Guard。
- validated 表示只读稳定性校验通过，尚不是 completed。屏障必须保留到后续 Workspace
  快照和基线提交完成。校验尚无外部副作用时，原提交 Actor 可幂等取消并释放屏障；错误
  拒绝也释放屏障。当前实现作为内部基础组件，尚未接到公开 Restore / Fork 入口或定时器，
  不会仅因本次部署就创建或悬挂行政命令。
- 当前 Sandbox 执行器仅实现工具 start / query / cancel，没有原生不可变工作目录快照
  通道。命令回执区分是否已有 Sandbox，接下来必须实现稳定快照 / 导入及恢复基线的确认
  过程；不能只复制上下文和上传文件就宣告 Sandbox Fork 完成。旧 Go Fork 转发仍待替换。
- H2 与明确开启的本任务 PostgreSQL 共 8 项专项通过，日志
  /tmp/managed-mq-checkpoint-operations-tests.log。覆盖 260 事件分页校验、实例替换、双方
  准入屏障、幂等 / 冲突、取消后恢复准入、早期未知副作用、无 dispatch 的未结算 ToolUse、
  前缀变化与作用域不一致。没有模型 / ACS 调用，也不是完整多副本验收。

- /tmp/managed-mq-checkpoint-operations-regression-tests.log：51 项关联回归全部通过、无跳过，
  包括明确开启的 PostgreSQL 文件 / Artifact / 生命周期 / Checkpoint 专项，以及实际
  Harness / 工具恢复、任务准入和持久校验测试。行政屏障没有改变普通执行的原有路径。
  该次模块命令仍不是全量 clean verify 或完整服务 / 多副本验收。

- /tmp/managed-mq-checkpoint-operations-portal-tests.log：Portal / 预算 23 项与实际 Go → Java
  文件 / 账户 / 资源联调通过，修改的 Java 文件 Spotless 通过，git diff --check 通过。
  Portal 使用真实 HTTP / 原始身份 / JPA / Ma H2，Go Control Plane 为本任务 PostgreSQL，
  MQ / Redis 执行为明确 Fake；未部署整套服务或验证真实 Sandbox / 多副本故障矩阵。
- 实际位置 / 分支保持 /Users/ken/agentscope-3/agentscope-java、codex/automatic-acr-release。
  本轮未修改 Core / Harness 源码，未提交或推送。整体目标仍未完成，继续处理 Workspace
  快照确认、恢复基线、公开 API / SDK 和旧 Fork 转发删除。

## 恢复基线与 Workspace 快照阶段证据

- 无 Sandbox 的 Restore / Fork 可在同一事务提交独立恢复基线、检查点索引、公开事件、
  命令完成回执及准入屏障释放。Fork 只复制所选检查点捕获的不可变文件成员；源文件
  后续清理不影响目标副本。Restore 追加恢复事实，不倒退日志序号。
- 任一源 / 目标已有 Sandbox 时，基线提交要求 Workspace 确认，当前不会只复制上下文
  就标记完成。以上仍是内部组件，尚未接入完整的公开 Restore / Fork 流程。
- Hands Sandbox 执行器增加工作目录快照和导入，包含内容摘要、文件权限和目录清单，
  排除根目录下的执行日志与快照私有目录。稳定 execution_id 的重复启动返回原结果；
  导入结果标记丢失时，可按已持久化的预期清单确认结果，不重复覆盖后续工作目录。
- 控制载荷经标准输入传递，避免大文件触发操作系统命令行参数长度限制。真实本地进程
  验证包含 2 MiB 随机二进制文件；没有访问 ACS。历史 Checkpoint 与不可变 Workspace
  镜像的绑定、行政命令驱动及公开 API / SDK 接入尚待完成。
- /tmp/managed-mq-checkpoint-baseline-regression-tests.log：79 项关联测试全部通过、无跳过，
  common 68、Hands 11，包含明确开启的本任务 PostgreSQL 专项、实际 Harness 恢复和
  本地 Sandbox 进程测试；本阶段 Java 文件 Spotless 检查通过。这不是全量 clean verify
  或两套实际 Portal / Brain / Hands 服务副本的故障验收。
- 当前按整体交付工作量估算约 60%；此数字不是验收项通过率。完整 Restore / Fork、
  平台 Subagent、其余 Managed 适配器、旧路径删除、全量构建部署及多副本故障矩阵仍
  未完成。代码位置与分支不变，本阶段未修改 Core / Harness 源码，未提交或推送。

## 检查点目录关联、Hands 驱动与原生写入口

- 增加 ma.checkpoint_workspace 与 ma.checkpoint_workspace_step。目录归档、内容摘要和权限
  清单均为私有数据；检查点元数据只增加 workspace_available。第一版将最多 32 MiB
  的压缩归档作为 BYTEA 持久保存，清单最多 4096 项 / 1 MiB；后续容量与保留清理仍待
  完整验收。不使用公开文件或 Artifact 通道保存目录镜像。
- 快照不可变地关联所选检查点。已保存的历史镜像可重用；没有镜像时，原生日志中后续
  tool/dispatch 或 ToolTask 账本中的后续框架 RPC 都会阻止使用当前目录补拍。新增测试
  发现并修正了校验进度重新加载时漏读 workspace_changed 标记的问题。
- 快照 / 导入意图先持久保存，再启动唯一 execution_id。替换实例只查询已启动的意图。
  导入确认同时比对归档摘要与完整清单，未确认或失败时保留准入屏障，不重启导入。
  校验 / 快照阶段可取消；导入阶段取消返回 409。导入后基线校验失败也不能释放屏障。
- ManagedCheckpointLoop 在独立有界线程池执行行政操作，以 Redis 令牌及续期限制当前
  执行者，不建立数据库续租或 owner 实例映射。Hands 已装配该驱动；事件及长工具池
  继续独立。历史镜像也随新恢复基线复制，源内容清理不会影响目标。
- PortalCheckpointWrites 已直接使用原生操作账本，删除旧 PortalCheckpointEntity /
  Repository、数据库认领 / 重试定时器、ready / checkpointing 状态切换与 Brain HTTP
  转发。Portal 显式导入只读 ManagedCatalog，按解密后的固定 Agent 定义进行一致性
  校验，避免比较加密配置中不存在的 agentSnapshot 字段。
- 新原生 REST：checkpoints/restore、fork、checkpoint-operations/{id} 及其 cancel；
  内部可信调用增加 restore_checkpoint、fork_checkpoint、checkpoint_operation 与
  cancel_checkpoint_operation。保留原始 Actor，源与目标分别检查当前访问范围。仅
  支持同资源作用域的 Managed 根 Session；目标须预先登记且没有原生日志 / 文件。
  self_hosted 写入口当前明确拒绝，等待 outbound Worker 执行协议接入。
- Java / TypeScript / Python Managed SDK 增加上述写与回执接口。目录及原始状态不进入
  回执；回执查询检查源 Session 身份。定义文件增加首次初始化标记，重连不会补回被
  用户删除的文件；本地模拟 Sandbox 绑定使用固定 64 字符摘要，删除后释放绑定。
- 目录导入暂时为只读目录添加所有者写入权限，完成后恢复精确权限清单。验证包括
  2 MiB 随机二进制文件、0555 目录、结果标记丢失及重复调用不覆盖后续工作。

最终验证：

- /tmp/managed-mq-workspace-final-java-tests.log：72 项全部通过、无跳过，Java SDK 4、
  common 32、Portal / 预算 24、Hands 12。明确开启本任务 PostgreSQL 的 16 项校验 /
  索引专项，以及实际 Go → Portal 的传输、Endpoint / 冷文件、账户 / 应用三组联调。
  Portal 为真实 HTTP / JPA / Ma H2；执行 MQ / Redis 为明确 Fake。
- Hands 新联调使用实际本地 Sandbox 进程与 H2：注入导入启动响应丢失、替换 Hands
  驱动及 provider、查询原 execution_id 后完成 Fork；确认仅启动一次导入、源目录
  后续变化不影响目标、被删除的定义文件不因重连恢复。没有使用真实 ACS。
- TypeScript 全部 27 项测试与构建通过；Python 全部 115 项通过。日志
  /tmp/managed-mq-workspace-sdk-ts-tests.log、/tmp/managed-mq-workspace-sdk-ts-build.log、
  /tmp/managed-mq-workspace-sdk-python-tests.log。文档 13 项与直接 Mintlify 构建校验通过，
  日志 /tmp/managed-mq-workspace-doc-tests.log、/tmp/managed-mq-workspace-doc-build.log。
- 修改的 Java 文件通过 Spotless，git diff --check 通过。完整文档导航的既有问题、
  全量 clean verify、整套部署与两套实际服务副本的故障矩阵仍未完成。

下一步继续迁移 Go Fork / 目标 Session 创建与 Console，接入 self_hosted 行政意图，
补齐执行期间的原始 Actor 授权复核、工作目录历史镜像覆盖与容量 / 删除清理；随后处理
Subagent、其他 Managed 入口、旧执行路径删除及完整多副本验收。能力列表仍未宣告
完整 Restore / Fork 可用，以上证据不等同于这些剩余目标完成。本阶段未修改 Core /
Harness 源码，未提交、推送或部署整套服务；工作目录与分支不变，总目标保持进行中。

## Go Fork、操作回执与 Console 接入

- Go Fork 改为使用 ManagedRuntimeRequest / fork_checkpoint。两端已有公共 Session 都按
  固定定义登记到 Portal，原生 Session ID 与公共 ID 一致，不创建 Turn，不联系 Brain。
  删除 ManagedServiceRequest、旧 DataURL 执行请求和 translateNativeValue 递归转换。
  保留其他后端的资源管理入口，未为新 Managed 路径增加旧数据兼容。
- Go 增加 Restore、检查点操作查询与取消路由。Fork 返回 202；上游状态码原样映射，
  不再把全部失败包装成 409。请求保留原始 Actor 和幂等键，运行归属人来自固定绑定。
  运行归属人可以不同于调用人；协议测试根据固定定义读取归属人，同时检查实际 Actor。
- Fork 目标仍须由调用者先创建为空的公共 Managed 根 Session；控制面负责将这个已有
  资源登记到 Portal。没有增加自动创建目标、回滚目标或旧物理 ID 转换的另一套流程。
  Portal 同时拒绝恢复请求中的目标字段，以及 Fork 目标与调用参数不一致的请求。
- Console 使用原生检查点 ID、checkpoint-operations 回执和取消入口。删除 cp1 格式及
  必填恢复原因；重试记录改为 v2，仅保留新的协议。回执检查源 Session、目标与操作 ID，
  completed / rejected / cancelled 都释放页面待处理状态。导入过程中不展示取消入口。
- Java、TypeScript、Python 的通用 ServiceClient 同步移除 reason 参数 / 字段，增加操作
  查询与取消；它们调用控制面路径，ManagedSessionClient 继续使用 Portal 原生路径。
  这是 Service SDK 的破坏性签名变更；没有保留旧重载，Core / Harness 公共 API 未改。

本阶段证据：

- /tmp/managed-mq-fork-live-portal-tests.log：Portal / 预算 24 项通过、无跳过；实际 Go →
  Java 同时验证传输、Endpoint / 文件及账户 / 应用。新 Fork 会登记双方固定定义并进入
  原生校验，没有检查点时返回 checkpoint_not_found / 404；操作查询缺失也返回 404。
  这验证原始身份和真实路由边界，没有用空 Session 证明完整 Fork 执行成功。
- /tmp/managed-mq-fork-go-postgres-final-tests.log：CP PostgreSQL 与明确的 Portal 协议
  Fake 验证目标登记先于 Fork、重复请求、202 原生回执、Restore / 查询 / 取消和非法请求。
  假服务拒绝缺失原始 Actor 的请求，旧 DataURL 指向不可用地址；这不是 Hands 或 ACS。
- Go 全包 go test -p 1 ./... -count=1 通过，日志 /tmp/managed-mq-fork-all-go-tests.log。
  全包未统一开启 PostgreSQL 条件项，数据库证据限于上述专项。make build 四个命令
  通过，日志 /tmp/managed-mq-fork-go-build.log。
- 前端 178 项、类型检查、构建通过；lint 无错误，保留既有 98 条警告。日志
  /tmp/managed-mq-fork-ui-final-tests.log、/tmp/managed-mq-fork-ui-final-typecheck.log、
  /tmp/managed-mq-fork-ui-build.log、/tmp/managed-mq-fork-ui-lint.log。UI 产物由正式构建
  生成，未手工编辑。能力开关继续等待完整 Restore / Fork 验收，未宣告全功能可用。
- 通用及原生 Java SDK 11 项通过；TypeScript 28 项和构建、Python 116 项通过。日志
  /tmp/managed-mq-fork-sdk-java-tests.log、/tmp/managed-mq-fork-sdk-ts-tests.log、
  /tmp/managed-mq-fork-sdk-ts-build.log、/tmp/managed-mq-fork-sdk-python-tests.log。
- EN / ZH 接口说明同步；docs 13 项与直接 Mintlify 构建校验通过，日志
  /tmp/managed-mq-fork-doc-tests.log、/tmp/managed-mq-fork-doc-build.log。Spotless 与
  git diff --check 通过；完整文档导航、全量 clean verify 与整套部署仍未完成。
- 真实 HTTP 专项日志中出现一次 Netty IllegalReferenceCountException（连接关闭时），
  请求断言均通过。异常来源尚未定位，后续多副本 / 大请求验收必须复核，不能据测试
  返回成功就将连接释放问题视为不存在。

下一步继续 self_hosted 行政意图、执行期间授权复核、历史 Workspace 镜像与删除容量
清理，以及 Subagent、其他 Managed 入口和完整多副本故障矩阵。当前仍在主工作目录
/Users/ken/agentscope-3/agentscope-java、codex/automatic-acr-release；未提交、推送或
部署整套服务，本轮未编辑 Core / Harness 源码，总目标保持进行中。

## self_hosted Workspace Worker 接入

- 按 Sandbox scope 与 executionId 查询原始 Workspace 阶段；查询不推进操作，也不因
  当前阶段已变为导入而丢失旧快照回执。轻量查询与私有归档参数分开，续期不加载归档。
  H2 / PostgreSQL 原始执行查询与跨 scope 拒绝专项已通过。
- 新增 workspace-tasks 的 claim / renew / started / result HTTP 协议，Gateway 与 Hands
  安全路由同步接入。Lease 显式包含 workType=tool 或 workspace，外部结果重试也按该
  字段选择路径。Service 协议直接破坏性更新，不保留缺失 workType 的旧消息适配。
- 领取不写启动标记；领取响应丢失、令牌到期后可以重新领取。Worker 在真实启动之前
  获取一次 started 许可，DB 原子地把 prepared 改为 started；重试返回 may_launch=false。
  启动记录已经存在的任务只查询原 executionId。旧令牌不能续期或申请启动，确认过的
  原始结果可以在接管后提交。没有新增数据库活跃租约或实例地址映射。
- Portal 受理 self_hosted 操作之前，根据固定 Environment / Session sandbox_binding
  预留已有 Sandbox 的唯一身份，避免 Hands 把尚未连接的目录误判为无需复制。Fork
  两端必须使用独立实例；同一实例不能同时绑定到两个 Session。预留是资源绑定，不
  创建新实例或执行远端操作。原始 Actor 与资源作用域检查保留。
- outbound Worker 已同时领取工具和工作目录任务。结果先 fsync 本地保存，获得 Hands
  提交确认后删除；结果确认响应丢失或 Worker 替换后重传同一结果。默认仍只连接已有
  Sandbox；测试显式替换外部 provider 为本地进程，没有生产自动 Fake 回退。
- 每次 Workspace 领取最多返回一个归档；Hands 在 JSON 解码前验证 Environment key，
  并限制每副本最多两个 Workspace HTTP 请求。压缩归档保持 32 MiB 上限，HTTP codec
  上限 64 MiB；Worker 以有界 InputStream 读取响应。等待结果的行政操作保留准入屏障。

最终验证：

- /tmp/managed-mq-selfhosted-workspace-final-tests.log：57 项全部通过、无跳过，common
  28、Portal / 预算 25、Hands 4；明确开启 PostgreSQL 的 14 项，以及实际 Go → Java
  传输、Endpoint / 文件、账户 / 应用联调。Gateway 当前配置编译通过，尚不是 Gateway
  加多副本服务的完整部署验收。
- 服务专项使用真实 key 校验 HTTP、两份 Hands 服务对象、H2 与明确的 Redis Fake，
  验证丢领取响应、启动记录、旧令牌拒绝、Environment scope 拒绝和晚到原结果。
- Worker 专项使用真实 outbound HTTP 与实际本地 Sandbox 进程，含 2 MiB 随机文件。
  结果提交后注入 503，替换 Worker 后清空已确认的本地 outbox；源目录改变后，目标
  导入的仍是原快照。源 / 目标目录独立。未访问真实 ACS，也不是两个运行服务进程。
- HTTP 入口专项验证解码前鉴权、并发超过两请求返回 429、错误与取消后释放容量。
  Portal HTTP 专项验证预留借用绑定，平台驱动等待外部 Worker，不直接提交 context-only
  基线。32 MiB 上界与压力、真实外部 provider 故障仍待完整验收。
- /tmp/managed-mq-selfhosted-workspace-spotless.log 与 git diff --check 通过。EN / ZH
  文档同步，docs 13 项及直接 Mintlify 构建通过，日志
  /tmp/managed-mq-selfhosted-workspace-doc-tests.log、/tmp/managed-mq-selfhosted-workspace-doc-build.log。
- 本轮发现 Docker daemon 已停止，首次 PostgreSQL 验证因 16991 连接拒绝失败；没有
  将失败解释为业务通过。恢复 Docker 与本任务 managed-mq-redis Compose 后，PostgreSQL、
  Redis、RocketMQ 均健康，重新运行完整上述专项通过。未重置卷或旧开发服务的数据。

历史 Workspace 镜像自动覆盖、保留 / 删除容量、行政执行期间授权复核、Subagent、
剩余 Managed 入口、旧执行路径删除、全量 clean verify 及完整多副本故障矩阵仍未完成。
能力列表继续等待整体验收；没有将本轮专项视为总目标完成。本轮未修改 Core / Harness，
没有提交、推送或部署整套服务，实际目录 / 分支保持不变。


## 实际双副本部署与 180 秒工具闭环

本阶段使用两个独立 Portal JVM、两个 Brain JVM、两个 Hands JVM 和实际 Go Control Plane，
连接本任务 Compose 的 RocketMQ、Redis 与 PostgreSQL。数据库与 MQ Topic / 消费组每次
使用独立名称，不重置旧开发库或其他夹具。新夹具入口为 tests/managed-mq-redis/replicas.py，
可复现启动、短工具 / 长工具与停止命令见同目录 README。

- Brain 显式通过夹具入口注册确定性 Model，只加载包含该夹具的独立小 JAR，不加载其他
  测试配置。它不会进入生产服务包；没有真实模型或 ACS 访问。Hands 显式启用本地进程
  Sandbox；Harness、受理、MQ、Redis、日志事务和工具恢复路径仍是实际服务实现。
- 实际启动发现并修正 Portal PostgreSQL 迁移缺少 title、metadata_json、创建摘要；
  补登记已有 dataplane Environment 迁移。两项真实 PostgreSQL 迁移回归通过、无跳过，
  日志 /tmp/managed-mq-replicas-schema-package-v3.log。
- Hands 独立应用补充 Jackson 2 ObjectMapper、Actuator 与健康路由。两个 Hands 的健康
  查询返回 200，未允许的内部路径返回 401；没有扩展业务权限体系。相关 Worker / Workspace
  四项回归通过、无跳过，日志 /tmp/managed-mq-replicas-hands-regression.log。
- 最终独立启动全部通过，日志 /tmp/managed-mq-replicas-start-v7.log。实际夹具目录是
  /private/tmp/managed-mq-replicas-20261014-g；数据库 ma_replicas_ceca2d603716。前面失败
  夹具及日志保留；没有把迁移失败、模型未注册或无效工具参数的失败记为验收通过。
- 短工具：不同 Portal 提交 T1 / T2，T1 由 Brain B 推理并通过 Hands 执行，T2 随后由
  Brain A 执行。两轮 completed，40 条公开事件完整返回，执行副作用文件仅含一次
  start / done。日志 /tmp/managed-mq-replicas-smoke-v4.log，结果记录
  /private/tmp/managed-mq-replicas-20261014-g/smoke-1791964870.json。
- 长工具：真实本地进程 sleep 180 秒，首轮持续 193.745 秒，跨多个 60 秒租约周期。
  T1 的执行代次始终为 1；工具请求只对应一个用户 ToolTask / executionId。Hands 确认
  成功后，Brain 输出 brain-confirmed-hands，再写完成终态；T2 随后完成。工具副作用
  只执行一次。日志 /tmp/managed-mq-replicas-long-smoke.log，结果记录
  /private/tmp/managed-mq-replicas-20261014-g/smoke-1791964924.json。
- 对两份持久事件结果另行核对：T1 running → T1 completed → T2 running → T2 completed，
  没有缺页。HTTP 状态轮询不是唯一顺序证据；脚本也增加持久事件顺序检查。

本阶段只完成基本部署、同 Session 顺序和长工具闭环，不代表网络分区 / 接管、Redis 丢失、
ACK 丢失、死信、跨 Session 队列阻塞、SSE 重连与父子容量矩阵已经通过。历史 Workspace
覆盖及清理、Subagent、剩余入口、旧 Managed 链路删除、正式部署与 clean verify 仍未完成。
Core / Harness 源码没有因本轮改造而修改，未提交或推送，总目标保持进行中。

本阶段剩余发现：实际 Brain 后台 WorkspaceTaskRepository orphan sweep 在没有活动
Managed Turn 时会尝试访问 Hands filesystem，并记录 active Managed execution 缺失。
主请求闭环通过，但后台维护不能直接复用 Turn 的 Guard；这一项需要随 Subagent / Task
存储迁移处理，尚未修复，不能以本次基本闭环推断后台维护正确。

本阶段 scoped Spotless 检查通过，日志 /tmp/managed-mq-replicas-spotless.log；Python
夹具编译与 git diff --check 通过。没有运行全量 clean verify 或宣告整个计划完成。

补充最终夹具验证：加入持久事件顺序断言后的脚本再次通过（0 秒工具），日志
/tmp/managed-mq-replicas-final-smoke.log，结果记录
/private/tmp/managed-mq-replicas-20261014-g/smoke-1791965409.json。验收结束后停止了该夹具
的 7 个服务进程，保留数据库、MQ 诊断数据和日志；未停止用户原开发栈或删除 Docker 卷。


## LiteTopic 隔离、动态订阅与实际暂停接管

本节是当前传输实现的证据；前面的 5.3.2 FIFO / Local Proxy 记录保留为历史，不再作为
当前生产后端或回退实现。

- 新的经典 FIFO 基线使用一个物理队列，T1 保持未 ACK，第二个 Session 在 20 秒内无法
  收到消息，测试失败；日志 /tmp/managed-mq-fifo-isolation-test.log。没有通过增加队列
  数量或修改断言掩盖这一问题。
- 从已校验的 5.5.1 二进制反汇编确认：LocalServiceManager.getLiteSubscriptionService
  直接返回 null，ClientProcessor 第 134 行调用它。ClusterServiceManager 则创建实际
  LiteSubscriptionService。实验改为独立 Cluster Proxy，并在其启动前创建 heartbeat
  系统 Topic；没有修改 RocketMQ 的服务端二进制。
- /tmp/managed-mq-lite-cluster-isolation.log：单物理父队列 Lite 隔离专项通过；另一个
  Session 可以继续，而同 Session 的 T2 等待 T1 ACK。第二次基础设施验证从启动到通过
  不到 5 分钟，没有越过 15 分钟排查约定。
- Managed 的唯一 MQ 适配器改为 LiteSimpleConsumer / setLiteTopic；原生 MQ Envelope
  仍为 v2。默认父 Topic / group 改为 v3，Shared 订阅，无普通 FIFO 自动回退或旧消息
  转换。客户端仍为 5.2.2，Broker / Proxy 为 5.5.1。
- 以完整 Scope 的 43 字节指纹命名 LiteTopic。从待处理 Turn 和未确认 ACK 的 receipt
  有界 keyset 分页发现，每次 4 个；每客户端最多 256 个订阅。收到的本地 Delivery
  固定订阅，其他订阅至少保留 30 秒后才因容量轮换。没有新增 sticky owner 地址映射。
- ma.turn / ma.runtime_commit 新增 broker_acknowledged，只在实际 ACK 返回成功之后
  更新。DB 提交和 ACK 确认是两个事实；ACK 丢失或确认未完成时，Scope 保持可发现。
  标记不是实例租约；原生公共状态、Core / Harness 的序列化接口没有改变。
- /tmp/managed-mq-lite-adapter-real-v2.log：28 项通过，无跳过，含实际 Lite MQ 两项、
  订阅固定 / 轮换、容量、ACK 重试、受理与日志原子性。新增测试严格遵守终态前关闭
  输入并保存 checkpoint，以及接收后处理全部消息；修正了测试夹具丢弃预取消息的问题。
- /tmp/managed-mq-lite-postgres-v2.log：27 项通过，无跳过，其中 PostgreSQL 17 项，
  H2 日志 10 项。使用新建 managed_mq_lite_test，未改旧测试 schema。共享 PostgreSQL
  有多个既存测试 Scope，发现测试按本 Scope 是否出现 / 消失验证，不假设数据库只有
  当前一条 Session；keyset 与提交后重放的 ACK 标记保持专项也通过。

实际 JVM / HTTP 验收使用 /private/tmp/managed-mq-replicas-20261014-lite-a，独立数据库
ma_replicas_4262b76f5df5，两个 Portal、两个 Brain、两个 Hands 与实际 Control Plane：

- /tmp/managed-mq-lite-replicas-start.log：全部服务启动与健康通过。
- /tmp/managed-mq-lite-replicas-smoke.log：3 秒工具、跨 Portal 同 Session 顺序、公开事件
  与单次副作用通过；结果 smoke-1791967705.json。
- /tmp/managed-mq-lite-replicas-parallel.log：一个 Session 的 60 秒工具已在 Hands 启动时，
  第二个 Session 在其结束前完成回复；两个 Session 共享同一物理父队列，证明未依赖
  分区碰巧分开。随后长工具返回，Brain 继续并完成；结果 parallel-1791967742.json。
- /tmp/managed-mq-lite-replicas-long.log：180 秒真实本地进程跨多个续期周期，工具执行
  一次，Brain 获取已提交结果继续，T2 随后完成；结果 smoke-1791967895.json。
- /tmp/managed-mq-lite-replicas-takeover.log：240 秒工具执行期间 SIGSTOP Brain B，
  原执行 epoch=1；另一 Brain 在失去消费 / Redis 保活后接手为 epoch=2。工具仍在执行
  时恢复旧 Brain B，它因旧 Guard 失效停止推进。新 Brain 复用一个原始 ToolTask / 稳定
  executionId，最终副作用只有一次 start / done，用户收到恢复后的结果。排队 T2 在
  epoch=3 完成，两轮 ACK 都确认；结果 takeover-1791968608.json。
- 实际模型仍为显式确定性夹具、Sandbox 为独立 Hands 驱动的真实本地进程，没有 ACS
  或真实模型 provider 访问。暂停模拟长 GC / 进程停顿，不代表所有网络分区情况已覆盖。

EN / ZH API 与架构文档同步说明 LiteTopic / Cluster Proxy，夹具 README 已切换可复现
命令。正式 Compose / Helm / 开发脚本与 ma schema job 的整体迁移仍未交付；不能将测试
夹具启动等同于用户生产部署已改好。其他故障矩阵、历史 Workspace 覆盖 / 容量清理、
Subagent 与后台维护、剩余入口迁移、旧 Managed 链路删除、完整 clean verify 仍未完成。
本轮未编辑 Core / Harness 源码，没有提交或推送，总目标保持进行中。


本轮收尾验证：/tmp/managed-mq-lite-service-regression.log 中 common 26 项、Portal 24 项
与 Hands 4 项，无失败；Portal 的实际 Go 条件项本轮未启用，1 项跳过，不把它记为本轮
通过。实际独立 Go Control Plane 的 JVM 验收入口已有上述证据，但不替代全量 Go 验证。
/tmp/managed-mq-lite-spotless.log 的相关源码格式检查通过。docs 单元 13 项与直接 Mint
构建校验通过，日志 /tmp/managed-mq-lite-doc-tests.log、/tmp/managed-mq-lite-doc-validate.log；
完整导航前置检查仍存在已登记的内部稿问题，未宣告完整 npm run validate 通过。

完成审计仍保留一项差异：原生 SessionLog SPI 当前逐恢复边界等待 Hands DB 回执，
而目标设想 Brain 在 MQ 发布确认后继续、独立消费管理等待最终提交。当前保留了 Core /
Harness 的同步提交语义；这一异步化差异尚未完成。生产 ma migration job、正式部署
切换，以及 Broker ACK 已接受但 DB 确认失败后的记录清理也仍待补齐，不能缩减总目标。

设计基准变更（用户已确认）：2026-10-14 17:17 发现同名 MQ / Redis 文档被并行更新，Turn ACK 边界
改为保存挂起 checkpoint，新增独立 turn-resumes Topic，与本轮整轮终态 ACK / 两个 Lite
父 Topic 的已验收实现不同。保留该文档的新改动，没有覆盖或反向改回，已向用户请求
明确后续协议基准。当前源码和以上证据对应整轮终态 ACK；不能将这些证据宣称为挂起 /
resume 实现的完成证明。用户已回复“按更新后的文档改为挂起与 resume 协议”。后续实施采用该协议，上述真实验收保留为旧协议基线；新协议必须重跑。


## 挂起 / Resume 协议实施中

- 新增独立 TURN_RESUMES Channel 与第三个 Lite 父 Topic；Brain 恢复使用独立消费组和执行池。
- 挂起事务保留当前逻辑 Turn，不推进 settledSequence；保存 AgentState、checkpointSequence、
  suspensionId、执行代次、pending ToolTask 与 APPLY_TOOL_RESULTS 继续阶段，同时封存旧 token。
- 工具结果和挂起事务均检查等待集合；结果先到与挂起先到都能原子生成唯一 Resume outbox。
- Resume 发布失败保留原身份重试；恢复使用新执行代次与原 ToolTask / executionId。
- 目前先接入现有同步提交桥，Hands 异步 ACK 交接、正常日志不等待 DB、T2 独立暂持管理、
  短等待的可靠消息返回通道仍是未完成项，不能将基础代码编译等同新协议验收。
- 未改 Core / Harness 源码；Service 使用现有 SessionLogException 的传播边界终止本次尝试，
  需要验证不会把平台等待伪装为模型可见的工具错误或用户 external interaction。

Core 兼容性增量（挂起验收发现，2026-10-14）：ToolMethodInvoker 和 ReActAgent 批量
acting 层补齐 SessionLogException 的传播，与 ToolExecutor 原有约定一致。工具中的日志 /
恢复边界异常不再伪装为普通错误输出送给模型；普通业务工具异常仍转换为错误结果。
未更改 builder、接口、事件或状态序列化；既有工作区 Core 改动保留。

挂起协议阶段证据（仍有异步边界待完成）：
- /tmp/managed-resume-unit.log：Core ToolMethodInvoker 38 项、common 30 项通过，无跳过；
  common 包括挂起/结果两种事务顺序、恢复身份、T2 约束、实际 Harness 短等待恢复。
- /tmp/managed-resume-package.log：22 项专项通过，Portal / Brain / Hands 及其依赖打包成功。
- /tmp/managed-resume-replicas-long.log：新库 ma_replicas_59ea7e2bf986，独立三个 Lite 父 Topic，
  实际两个 Portal / Brain / Hands JVM。180 秒本地进程；waiting_tool 后停止原 Brain A，
  Brain B 恢复原 Turn 至 epoch=2，ToolTask / executionId 不变，只有一次 start/done。
  T2 随后完成。结果 smoke-1791971996.json；原 Brain 退出记录 suspended-brain-exit.json，
  路径 /private/tmp/managed-mq-replicas-20261014-resume-a。确定性模型、本地 Sandbox；非 ACS。
- 暂持 T2 有独立消费管理，没有 Agent/Mono/执行令牌；当前就绪检查仍读持久 settledSequence，
  Redis 完成通知与丢通知补偿尚待替换。运输容量分通道保留，Resume 不会被 T2 占满所有收据位。
- Brain 当前仍通过同步 Hands 回执完成挂起事务再 ACK；这一实测不代表 Hands 异步 ACK
  和正常推理不等待 DB 的目标已完成。接下来验证跨进程 ACK capability，再切换发布路径。

跨进程 ACK 验证补充：新的接收凭据可以由同组的另一 RocketMQ 连接 ACK，但多次续期
以后原 receipt handle 会过期（/tmp/managed-resume-postgres-ack.log 中主动延长的用例暴露）。
目标交接必须通过 Redis 刷新最新的私有 ACK capability；Hands 先提交持久 ACK 意图再
读取最新凭据 ACK，不能只将最初的 handle 固定写入最终事件。失去原消费节点时，新投递
凭据按已有挂起/终态事实直接确认，不重跑。当前新方法只完成 SDK 验证，尚未接入异步主链。

第二轮挂起/ACK验证：/tmp/managed-resume-postgres-ack-v2.log 共 18 项全部通过，无跳过：
真实 PostgreSQL 6、H2 6、真实 RocketMQ 3、实际 Harness 3。真实 MQ 明确验证旧 ACK
凭据多次续期后失效、最新凭据可以跨连接确认。T2 超过原不可见期限仍被暂持并续期，
恢复 T1 后才执行。前端事件还原 18 项通过（/tmp/managed-resume-ui.log），waiting_tool
保持非终态、无外部待办，并显示原 Sandbox 工具仍待结果。Portal 状态过滤支持 waiting_tool。

异步 ACK 的下一步设计决策：消费管理在 Redis 登记最新 Ack capability；相同 deliveryId
只能更换 receipt handle，不能更换 Broker/message/Topic/group 身份。Hands ACK 成功后的
完成标记不能被迟到续期覆盖。登记丢失或损坏不等于 ACK；仍依赖持久挂起/终态与新的
Broker 投递进行补偿。登记 TTL 有界，不加入实例地址 / sticky 映射。

ACK outbox 基础组件已接入 Hands wiring：ma.delivery_ack 只在完整挂起/终态事务中生成；
消息 ID、Topic/group/Scope 和执行身份均校验，投递凭据不进入公开事件。Hands 读取最新
Redis 凭据调用原组 ACK，保存确认后释放原 token；缺失登记不 ACK、失败仍保留 outbox，
有界 keyset 扫描避免某条缺失凭据永远挡住后续记录。
/tmp/managed-ack-outbox.log：21 项通过，无跳过，含真实 Redis 2、Redis + H2 + 明确 Fake MQ
的 ACK outbox 3、挂起 6、Journal 10。确认 DB 写入失败后利用 Redis 成功提示补确认，
不重复发 Broker ACK。
当前 Brain 还没有发布带 capability 的主链 handoff，也仍等待 Hands DB 回执；
因此 ACK outbox 尚不是已验证的异步主链。下一步必须切换本地 SessionLog 前缀和消费
管理生命周期，并重跑真实多副本 / ACK 故障矩阵，不能保留两个生产 Managed 协议分支。

Core 扩大回归发现并修复一个现有 call-scoped RuntimeContext 分支的空值问题：
ToolExecutor 在没有 RuntimeContext 的普通工具调用中访问 get()，造成 NPE。只增加
null 判断，保留已有 opt-in 行为；该问题与新增异常传播不同，未回滚原工作区改动。
旧构建入口对应 /tmp/managed-resume-core-pg-regression.log（67 项中 7 项失败），修复后重跑。
前端 lint 完成：0 errors / 98 个已有 warnings；docs 单元 13 项通过。完整 clean verify
与完整文档导航检查仍未通过，不把专项回归等同总体交付。

扩大回归修复后：/tmp/managed-resume-core-pg-regression-v2.log 中 Core 83 项与 common 5 项
通过，无跳过。common 的 ACK outbox 3 项使用真实 PostgreSQL + Redis、显式 Fake MQ；
真实 MQ 的跨连接 / 续期凭据另由上述独立测试验证。前端事件还原最终 18 项通过；
docs 单元 13 项通过，lint 为 0 errors / 98 warnings。没有执行完整 clean verify。

本阶段真实 JVM 的基线夹具已停止，日志、数据库和 MQ Topic 保留，用户原开发服务未动。
代码实际位置仍为 /Users/ken/agentscope-3/agentscope-java，分支 codex/automatic-acr-release，
未创建 worktree、未提交、未推送，也未部署用户的正式环境。最新 outbox / API 小改动
已编译验证，整套新主链尚未完成重新打包与真实多副本验收。

下一阶段不可省略的协议收口：
1. ManagedTurnExecution 共享本地 accepted 前缀；Core SessionLog.commit 在 MQ 确认后返回，
   不读取 batch DB receipt。模型日志、检查点和输入回执保持顺序及原子消费。
2. Brain 在 Guard 接收/续期时刷新最新私有 ACK capability；终态/挂起事件携带身份，
   发布后释放 Agent/Mono，独立消费管理保活至 Hands 完成 ACK，不中断复用的线程。
3. Hands ACK outbox 接入真实 producer 边界；消息结果/Redis 丢失和 DB 确认故障补偿，
   原投递恢复只确认既有持久交接，不能重新执行。
4. 工具正常快路径改成可靠结果消息；DB 只作启动/接管/补偿读取，避免每 100ms 查询结果。
5. T2 就绪提示替换当前 DB 就绪读取，保留有界暂持/续期和持久缺前序保护；
   Resume 的执行容量不能被 T2 或父任务填满。
6. 复核 MQ 接收顺序与当前发布账本的优先级语义，不能静默将 API 受理顺序当作 MQ 顺序。
完整的原计划（文件/历史 Workspace、恢复/权限重检、Subagent、全部适配器、故障矩阵、
旧链路删除、正式部署/schema job 和完整构建检查）继续保持进行中。

## 异步日志与 Hands ACK 主链切换（实施中）

- ManagedRuntimeWriter 删除 batch DB receipt 查询与等待；MQ 确认后返回 accepted。
- ManagedTurnExecution 共享 ManagedLocalJournal，本地 head / checkpoint / Input 回执 /
  待工具关联由同一发布前缀管理；新 SessionLog 包装器不会重新读取落后的 DB head。
  历史读取限于启动时的持久前缀，接受后的本轮记录从本地读取。
- Guard 在接收和续期时刷新私有 ACK capability；挂起/终态事件携带它，Hands 保存
  outbox 后异步 ACK，消费管理继续保活至确认。Brain 返回不再查询 Turn 终态。
- Handoff 消费管理不保留 Agent、Mono、RuntimeContext 或本地日志；迟到 guard 回调
  不中断已复用的执行线程。原投递重放按既有挂起/终态只确认，不重新调用模型/工具。
- Registry 增加 Redis 实现和明确的 in-memory 测试实现；生产固定 Redis，不自动回退。
- /tmp/managed-async-backlog-v2.log：25 项通过，无跳过，包括实际 Harness 3、挂起/
  Resume 6、Pipeline 7、Guard 5 和 MQ adapter 单测 4。积压用例让 Hands 完全不消费，
  Brain 已返回且 DB head=0，原 delivery 超过初始 TTL 后仍续期；完整提交后仍需 ACK
  才允许 T2 接收。本地前缀共享及无 DB receipt 等待有直接断言。
- 普通工具结果仍在短等待期限内查询 DB，T2 就绪仍读取 settledSequence；结果可靠通知
  和 Redis 就绪补偿仍待完成。新的真实双副本验收、ACK 故障与所有完整计划仍待交付。

异步主链进入真实副本验收：
- /tmp/managed-async-services-package.log：16 项专项通过，Portal / Brain / Hands 打包成功。
- /tmp/managed-async-postgres-regression.log：真实 PostgreSQL 挂起 6、PostgreSQL/Redis ACK
  outbox 3、Redis Registry 2，全部通过，无跳过。
- 新夹具 /private/tmp/managed-mq-replicas-20261014-async-a，数据库 ma_replicas_c88094282125；
  独立三父 Topic、2 Portal / 2 Brain / 2 Hands 和 Go Control Plane。
- /tmp/managed-async-replicas-smoke.log：普通双 Turn 通过，输出与一次本地进程副作用完整；
  smoke-1791979753.json。此夹具已运行新的无 batch DB 回执等待 / Hands ACK 主链。
- 180 秒工具验收仍在运行，确认 waiting_tool、挂起 ACK outbox=acknowledged、T2 等待，
  随后停止原 Brain；最终恢复结果尚待当前运行句柄确认，不能提前宣告通过。

真实异步长工具验收完成：/tmp/managed-async-replicas-long.log、
/private/tmp/managed-mq-replicas-20261014-async-a/smoke-1791979812.json。
- 工具执行 180 秒，本次 Brain 在短等待后发布完整挂起事件并返回，Hands 保存 checkpoint /
  waiting_tool / pending 关联 / ACK outbox 后异步 ACK；ma.delivery_ack 状态 acknowledged。
- 停止原 Brain A（async-suspended-brain-exit.json），Sandbox 进程继续；Brain B 从独立
  Resume Topic 恢复同一 T1，epoch=2，使用原 ToolTask/executionId，只有一次 start/done。
- 用户公开事件包含最终输出，T2 未越序并随后完成；原 Turn、Resume 和终态 ACK 确认。
- 夹具模型为明确的确定性 Model，Sandbox 为 Hands 的真实本地子进程；没有 ACS/provider
  调用。日志、工具、检查点和 Broker/Redis/DB/HTTP 主链均为真实服务进程。
异步 batch DB 回执等待已在本轮生产主路径移除，不保留旧同步分支。正常工具結果
通知、T2 Redis 就绪补偿、其他故障矩阵和完整原计划仍未完成，目标保持进行中。

## 工具结果与 Session 就绪信号切换（实施中）

- 正常 ManagedToolRequests 不再查询 ToolTask.result：请求前登记稳定任务身份并发布
  意图，短等待从每个 ToolTask 独立 Redis Stream 读取不可变结果；验证完整 Scope、
  taskId、executionId 与私有 envelope/hash。恢复/接管启动允许一次读取原持久结果。
- Hands 保存结果后投递信箱，多个等待者读取同一结果，不竞争消费。大结果复用私有
  blob，Redis 只保存 envelope；MAXLEN=1，TTL=5 分钟。活跃 Turn 的已确认 ToolTask
  结果是持久重投递依据，后台有界 keyset 扫描补通知与 Redis 丢失，终态不再续缓存 TTL。
- /tmp/managed-result-recovery-v2.log：12 项通过，无跳过，含 Redis 信箱 2、实际 Harness
  3、取消/工具恢复 4、Redis 基础设施 3。覆盖 2 MiB 结果、两个读取连接、Scope 隔离、
  幂等/内容冲突、只丢 Stream 及完整信箱丢失后恢复；普通工具参数/权限检查仍由框架执行。
- T2 暂持监视改为读取 Redis settledSequence，收到消息时只读取一次持久会话状态；
  Hands 终态 ACK 后发布结算提示，并按未确认 Turn/Resume 作用域补提示。提示缺失不
  代表 ready，旧序号不能覆盖新序号；持久 activate 的缺前序检查仍是最后保护。
- 本轮新代码尚待完整专项和真实副本验收；不把组件单测等同完整改造完成。

信号层专项完成：/tmp/managed-signals-regression.log 28 项全部通过，无跳过；
/tmp/managed-signal-repair-postgres.log 22 项全部通过，无跳过，包含真实 PostgreSQL
挂起 6、PostgreSQL/Redis ACK/就绪补偿 4、Redis Registry 3、Redis 结果信箱 2、受理 7。
明确验证 ACK 结束后删除 settledSequence 提示，待处理 T2 仍使持久作用域可发现，
后台不需要再次 ACK 原投递即可补回已结算序号。旧提示不覆盖新提示；缺失不放行。
修复受理 Store 的 waiting_tool 状态过滤，和 Portal 保持一致。
MQ 发布顺序审计：reservePublication 没有强制 admission_sequence 的前序；短 Redis
发布锁与未确认发送屏障串行发送同 Session，序号在发送前分配。当前后台扫描仍按
受理顺序尝试 pending；发送含糊窗口保持前序屏障。此处需加入并发到达/发送失败专项
证明最终执行序号与 Broker 到达顺序一致，不用代码注释替代验收。

信号层真实副本验收完成：新数据库 ma_replicas_82a09eab877d，夹具
/private/tmp/managed-mq-replicas-20261014-signals-a。
- /tmp/managed-signals-replicas-smoke.log：普通双 Turn 通过，smoke-1791986627.json。
- /tmp/managed-signals-replicas-long.log：180 秒工具、waiting_tool/独立 Resume、最终模型
  结果和 T2 顺序全部通过，Sandbox start/done 只各出现一次；smoke-1791986855.json。
- 长工具挂起期间，只删除当前 Session 的 Redis 结果信箱和 settledSequence 提示，
  不动持久任务、不清空其他 Session/租约；redis-signal-loss.json 记录注入动作。
  Hands 补偿后原 T1 完成，T2 正常执行，无重复工具执行和未确认告警。
- 当前生产工具快路径已删除 ToolTask.result 轮询，T2 暂持已删除数据库就绪轮询。
  DB 启动/接管读取、结果/就绪补偿扫描及最终 activation 前序校验仍保留。
- Source 使用确定性 Model 和真实本地进程 Sandbox，不代表真实 ACS/provider 验收；
  全部原计划的其余适配器/存储/容量/故障/部署/旧链路删除仍待完成。

## 运行输入、SSE 与真实进程故障验收

2026-10-14：新隔离数据库 `ma_replicas_7e5b33a54990`，夹具目录
`/private/tmp/managed-mq-replicas-20261014-controls-a`。两个 Portal、两个 Brain、两个 Hands
及 Control Plane 均为独立服务进程，依赖真实 PostgreSQL / Redis / RocketMQ LiteTopic。
Model 为明确的确定性测试模型；Sandbox 为真实本地进程，没有访问 ACS 或模型提供商。

- `controls-1791988181.json` / `/tmp/managed-controls-replicas-controls.log`：挂起期间通过
  不同 Portal 受理 inject / steer，每个键在另一 Portal 重试仍返回相同 Input ID 和序号。
  两条 Input 各应用一次，模型输出证明读取顺序和原工具结果。两个实际 HTTP SSE
  订阅者、断线后向另一 Portal 提交 Last-Event-ID 的重放，逐条等于 REST 持久事件。
- `cancellation-1791988232.json` / `/tmp/managed-controls-replicas-cancel.log`：挂起期间
  cancel 跨 Portal 重试幂等，Input 最终 applied，原 subprocess 只出现 start、没有 done；
  T1 cancelled 后 T2 completed，不继承上一轮的取消。
- `hands-restart-1791988432.json` / `/tmp/managed-controls-replicas-hands-restart.log`：
  120 秒工具运行中同时 SIGKILL 两个 Hands，启动替换进程。恢复后的 ToolTask、executionId、
  process handle 完全等于原记录，只有一次 start / done；Brain 取得原结果，T1 / T2 完成。
  这覆盖运行中进程替换，不等同未知启动或结果提交瞬间故障。
- `redis-restart-1791988721.json` / `/tmp/managed-controls-replicas-redis-restart.log`：
  停止本任务专用 Redis 容器，在离线期间由 Portal 持久受理 steer（delivery 未完成），
  保留 Redis 数据后启动容器。Input 最终 applied，模型读取它，原工具只有一次 start / done，
  T1 / T2 完成。未清空其他项目或共享数据库。
- `/tmp/managed-common-broad-current.log`：108 项执行成功，75 项显式基础设施测试跳过。
  随后用新 PostgreSQL 数据库 `managed_mq_comprehensive_v3` 和真实 Redis / RocketMQ 运行
  `/tmp/managed-common-real-comprehensive.log`：183 项执行成功，1 项旧长协议测试未启用。
  后者已重写为挂起 / 独立 Resume / 跨 Brain 恢复，另行显式验收，不能把跳过算作通过。
- `/tmp/managed-controls-package.log`：Portal / Brain / Hands 打包成功；测试模型仅在小型
  test-class fixture JAR 中，生产 JAR 不包含模拟 Model。

验收脚本新增 `controls`、`cancellation`、`hands-restart`、`redis-restart`；信号仅作用于
携带当前目录标记的进程。`parallel` 接受长工具的 waiting_tool 状态。历史 `takeover` 不再
列入新协议验收命令，后续需补齐当前协议的分区场景。

仍有明确未完成项：reply、Input 关闭并发的实际 HTTP 验收、ACK 不确定与双存储故障、
死信 / 过期、积压压力、Workspace 自动快照 / 保留策略、Restore / Fork 再校验、Subagent
持久任务和父子容量、剩余适配器、旧链路删除、正式部署及全量检查。后台 WorkspaceTaskRepository
仍复现 `Hands request requires active Managed execution`，需要在 Service 编排元数据层修复，
不能给后台线程借用已结束 Turn 的执行令牌。完整目标保持进行中。

本轮收尾证据：
- `/tmp/managed-suspended-real-protocol.log`：改写后的 180 秒长协议测试实际执行通过，无跳过。
  三个独立 Lite 父 Topic、六个传输连接；原 Brain 关闭后 T1 由另一 Brain 从 Resume 恢复，
  T2 预先收到但暂持至 T1 终态，最终执行 epoch 分别为 2 和 3。确认一份工具执行和所有 ACK
  outbox 结算，旧 `runtime` Topic 拼写已修正为初始化夹具实际创建的 `events` Topic。
- `parallel-1791988958.json` / `/tmp/managed-controls-replicas-parallel.log`：当前信号层和
  挂起协议下，两 Session 共用同一物理父队列，长工具尚未完成时另一 Session 已完成。
- `/tmp/managed-controlplane-full-test.log`：`make test` 全包执行成功（含 Go 缓存命中）。
  `/tmp/managed-frontend-full-test.log`：36 个测试文件、179 项全部通过；前端 lint 0 错误、
  98 个现有警告，`npm run build` 成功。不能据此宣告正式部署或所有 SDK / 适配器完成。
- 双副本验收进程全部停止，仅保留本任务数据库、基础设施与证据；未停止用户旧开发服务。
- `/tmp/managed-controls-final-spotless.log`：本轮两个 Java 测试文件及依赖模块的 scoped
  Spotless 检查通过；Python 夹具语法检查、git diff --check 通过。
  `/tmp/managed-controlplane-full-build.log`：`make build` 四个 Go 可执行文件全部成功。

## 正式部署入口与 ma 初始化任务

本轮将部署模板和开发入口切到当前挂起 / Resume 协议，不代表已部署用户的正式环境。

- ServiceSchemaMain 新增 component=managed，独立初始化固定 ma schema。使用已有
  ServiceSchema 的数据库互斥、版本及 SHA-256 校验，不为普通副本开启 DDL。共享基础设施
  配置在 Portal / Brain / Hands 启动时只读校验 ma；校验不依赖当前 search_path。
- 拒绝为已有无版本 ma 表生成“已升级”账本，保留其原数据；部署选择新运行数据空间。
  这是 Service 未正式发布时的破坏性改造决策，不提供兼容转换。不修改 Core / Harness。
- Compose 与源代码 Compose 都新增 schema-managed，授权依赖四个迁移任务全部成功。
  新增 Hands 业务账号，Portal / Brain / Hands 共用 ma 的 DML，迁移账本只读。平台 Hands
  默认启动 --platform-service、内部 HTTP 8085，不再是可选的单 Environment Worker。
- Helm 使用七个 Deployment，Hands 有标准探针和内部 Service；新增私有 managedBlobs
  RWX PVC，Portal / Brain / Hands 共享。Dataplane 启用 MQ 消费并禁用旧 DB dispatch。
  Gateway 指向平台 Hands；self_hosted Worker 仍是客户侧独立进程。
- 正式部署连接由运维提供的 PostgreSQL / Redis / RocketMQ；模板不额外捆绑单 Broker
  生产集群，不自动启动 Fake。managed-mq-init.sh 初始化三父 Topic、Shared 消费组与
  Proxy 心跳 Topic，默认八队列、十四天闲置保留；脚本随发布包分发。Native dev-up/down
  也切到平台 Hands、四个迁移任务和基础设施配置必填。
- Dockerfile.service / Dockerfile.plane 预建私有 blob 目录并赋予非 root 用户权限，确保
  新命名卷可写。它不作为 Artifact 下载目录。部署及中英文指南已更新；历史安装记录
  标记为旧协议，不声称当前源代码仍使用旧 Worker。

验收证据：
- /tmp/managed-schema-migration-test.log：真实 PostgreSQL 两项通过，无跳过。并发重复
  初始化、public search_path 下只读校验、无 DDL/账本写权限、缺版本/未来版本/校验和
  不匹配，以及拒绝接管旧表均有断言。
- /tmp/managed-deployment-schema-grants-smoke.log：实际四个迁移入口和六账号授权通过；
  验证业务表写权限、迁移账本只读、跨 schema 隔离、拒绝所有者作为业务账号。只清理
  该脚本独占的新数据库和角色，未改现有 Service 数据。
- /tmp/managed-deployment-services-package.log：Portal / Brain / Hands 打包成功；
  /tmp/managed-deployment-final-spotless.log：本轮 Java scoped Spotless 通过。
- 业务账号双副本：ma_replicas_705b81a23f3a / business-a，smoke-1791990489.json；
  生产 jdbc profile 再验收：ma_replicas_62d95d8376a8 / business-jdbc-a，
  smoke-1791990788.json。后者用六个业务角色和四个迁移任务，两个 Portal / Brain / Hands
  均启动并完成 waiting_tool / resume / T1 / T2 及一次本地工具执行。
- /tmp/managed-deployment-docker-hands-smoke.log：实际 Hands 镜像容器通过 jdbc profile /
  Hands 业务账号连接真实 DB、Redis、Cluster Proxy，内部 8085 健康；UID=65532 可写新
  私有命名卷。证据 docker-hands-smoke.json / docker-hands.log 位于 business-jdbc-a
  夹具目录。测试容器已删除，日志与独占卷保留；不是全套 Compose 部署验收。
- /tmp/managed-deployment-mq-init-1.log 与 -2.log：初始化脚本在真实 5.5.1 Broker 连续
  执行两次成功，返回 LITE、8 队列、Shared 绑定、最小 offset 起始及 20160 分钟保留配置。
- /tmp/managed-deployment-release-final-tests.log：37 项发布/Helm/包测试通过；
  Helm lint 通过，源码和发布 Compose 都实际渲染为 13 个服务（含四迁移、授权和 DB），
  校验平台 Hands、三组件共享卷、消费开关和仅 Gateway 暴露端口。渲染结果含临时凭据，
  私有文件保留在 managed-compose-config-n57m72oj，不发布其内容。
- 文档单测 13 项通过。npm run validate 仍被五篇现有内部 Managed 草稿未列入公开导航
  拦截；没有为通过校验而将内部讨论稿发布到导航。完整文档 gate 尚未通过。
- /tmp/managed-deployment-mint-validate.log：单独运行 Mint 严格构建校验成功，确认本轮
  中英文部署页可编译；这不覆盖前一条导航缺口。业务账号 jdbc 双副本的七个验收进程
  均已确认停止，用户旧部署未改；Docker Hands 测试容器也已删除。

仍待完整计划：所有旧 Managed 续租/调度/转发分支清理、所有入口切换、子任务及父子容量、
Workspace 自动快照与保留策略、Restore / Fork 再校验、剩余故障矩阵、整套部署与全量
clean verify。当前部署模板验收不替代这些要求。

## 旧执行循环与 Worker 删除（2026-10-15）

- 删除 RuntimeTurnDispatcher：旧 DB command 拉取、worker lease 心跳、过期恢复和
  执行池不再作为 Spring 服务存在。删除它独占的 runtimeInboxScheduler /
  runtimeClaimScheduler 及 dispatch/inbox/claim/max-active-turns 旧设置。
- 删除 ToolTaskHandsWorker、SandboxHandsToolExecutor 和 hands-tools.py 同步桥；
  当前入口只启动 ManagedEnvironmentWorker 或平台 ManagedHandsApplication。
- 删除旧 Dataplane RuntimeBootstrapController / RuntimeCommandsController，避免
  继续暴露会写入已退役队列的 HTTP 接口。其他后端的 Go 编排协议未改。
- 删除 PortalToolTasksController 的工具状态 HTTP 转发；当前 /v1/sessions 工具查询
  直接读取 ma 的持久状态。旧公共 API / 适配器的全部清理尚未完成。
- MQ Brain / Resume 消费默认开启；显式关闭只暂停 MQ 消费，不启用旧 DB 路径。
  验收夹具不再传启用参数，接下来以真实启动和 Turn 验收验证默认值。
- 旧 Hands 的子进程停止回归迁移到新 ManagedSandboxToolExecutorTest；新增跨工作目录
  路径 / symlink 读取拒绝断言。9 项真实本地进程专项通过，无跳过：
  /tmp/managed-legacy-worker-regression.log。
- /tmp/managed-legacy-removal-clean-package.log：Dataplane / Hands 与依赖 clean package
  通过。删除的运行类在生成的 JAR 中不存在；最终完整验证仍在进行。
- Spring 装配检查首次因没有 Redis URI 失败，测试此前仍沿用旧执行链。现显式替换
  Redis / MQ / 两个消费循环，enforceOverride 要求必须存在生产 Bean，不能因默认
  消费 Bean 未创建而虚假通过。/tmp/managed-brain-default-context-v2-test.log 1 项通过；
  这是装配替身，不是传输验收，生产没有自动 Fake 分支。
- /tmp/managed-legacy-removal-release-tests.log：37 项发布 / Helm / 配置回归通过。
  当前正执行三个服务及其依赖的 clean verify；不能在其结束前宣告通过。

仍保留并待迁移的旧依赖包括 Portal 非原生入口与转发、Runtime 配置/工具/取消/事实表和
Channel/Cron/Subagent 适配器引用。只删除已确认被新链替代的执行组件；不以本轮删掉
几份文件代表整个旧实现清理完成。不改 Core / Harness。

本轮回归修复与结果：
- 第一次全量 reactor 检查在 Portal 停止：37 个错误均来自旧测试缺少新 MQ / Redis
  依赖。Core 522 项、Harness 1139 项执行通过（Harness 另有 18 项跳过），Common
  执行 146 项、跳过 80 项；不能把该次整体命令记为成功。
- 新增 PortalInfrastructureTestSupport，仅测试使用显式 Mockito override 和隔离
  H2 ma 初始化。原断言全部保留；查询 fixture 的三处 readyView 调用改为直接验证
  view 投影，因为手工记录没有完整密封配置，不能触发 Managed 注册或生成兼容数据。
  /tmp/managed-portal-registry-corrected-v2.log 的 6 项查询回归通过。
- /tmp/managed-legacy-removal-services-verify.log：三个服务及依赖的 clean verify
  成功，筛选服务相关测试。Common 110 执行 / 71 跳过，Portal 87 / 2，Hands 22 / 0，
  Dataplane 99 / 5。该筛选检查不等于全仓库不筛选的 clean verify。
- 实际 ZIP/JAR 内容校验确认 RuntimeTurnDispatcher、RuntimeBootstrapController、
  RuntimeCommandsController、ToolTaskHandsWorker、SandboxHandsToolExecutor、
  hands-tools.py、PortalToolTasksController 均不在各自可执行 JAR 中。
- 最终默认消费者的真实双副本验收正在新数据库/目录执行，完成前不记通过。

最终默认消费者验收完成：/tmp/managed-retired-replicas-smoke.log，
/private/tmp/managed-mq-replicas-20261015-retired-a/smoke-1791999715.json，
数据库 ma_replicas_3791fc97e1f1。使用当前 clean verify 生成的三个 JAR、jdbc profile、
独立业务账号，启动两个 Portal / Brain / Hands 和真实依赖。没有传消费者 enable 参数，
没有旧 DB dispatch。T1 原投递挂起 ACK、独立 Resume、epoch=2、结果后继续推理以及
T2 顺序均通过，原进程工具只有一次 start / done。

/tmp/managed-retired-http-check.log / retired-http-endpoints.json：向实际 Brain 提交
带有效内部 token 的旧 bootstrap 和 DB turn 命令请求，两个入口均为 404；不是因为
缺少认证而拒绝。此夹具进程已停止，日志/独占数据库保留，用户旧开发服务未改。
/tmp/managed-legacy-removal-release-final-tests.log：37 项发布回归通过，Helm lint、
Python/Bash 语法和 git diff --check 通过。仍未完成不筛选的全仓库 clean verify，
也未将全部 Portal / Runtime 旧依赖迁移或删除；完整目标保持进行中。


## Channel / Deployment / Cron 原生受理改造（2026-10-15）

本轮决策与边界：

- 控制面提供受内部令牌保护的 Managed trigger 入口，回调现有资源目录与 Portal 原生接口。
  资源 owner 和原始 Actor 分开保留；共享资源的定时触发默认以其 Namespace owner 执行，
  手动执行保留实际用户。Portal 继续校验当前调用作用域，不扩展权限功能。
- Channel 用会话键定位 Session、provider message ID 定位 Turn；缺少稳定消息 ID 时明确拒绝，
  不生成随机 ID 掩盖重复消息。回复按持久 cursor 分页，只读取目标 Turn 的 assistant 文本。
  读取 Turn 状态后再读事件，终态且当前页无后续页才结束，避免漏掉同事务提交的最终文本。
- Deployment 手动 / Webhook 支持已有 Idempotency-Key；不提供时表示一次新触发。
  Cron 使用 deployment ID + 应触发时间作为键，失败跨分钟重试仍使用原计划时间，
  不以重试时钟生成新键。原 Cron 触发协调仍在 Scheduler；本轮不把它扩展成 Turn 租约。
- 原生 Session 直接根据冻结定义、选定 Environment、memory / vault / resources 解析配置，
  不调用 legacy find-or-create，不创建额外 cp.sessions 或 EndpointConversation。
  Environment 在受理时确定；空 agentOverrides 保留为空值，不能编码成字符串 "null"。
- 并发验收发现 Portal 首次注册使用 JPA merge 可能覆盖已有随机密文，导致 ma 配置冲突。
  已改为 insert-only；冲突后读取并验证胜出记录，后续状态更新继续使用现有版本约束。
- Core / Harness 本轮无修改；Service 内部构造器及协议可破坏性修改，不加旧协议 fallback。

已验证：

- Portal 强制并发首次注册与原有幂等注册两项 H2 回归通过；
  日志 `/tmp/managed-trigger-scheduler-portal-tests.log`（Portal 两项通过，之后 Scheduler 编译失败已单独修复）。
- Scheduler 五项专项及打包通过：Channel 原生分页回复、provider 身份保留、原有回执行为、
  Cron 延迟重试坐标；日志 `/tmp/managed-trigger-scheduler-tests.log`。
- Go product / httpapi 完整包测试通过；真实 PostgreSQL 原生冻结配置专项通过，
  检查无 cp.sessions 写入、空覆盖值及非法冻结定义拒绝。
  日志 `/tmp/managed-trigger-go-full-tests-final.log`、`/tmp/managed-trigger-native-config-postgres.log`。
- 真实 Redis / RocketMQ 5.5.1 / PostgreSQL、单控制面 + 两个 Portal / Brain / Hands 副本通过：
  并发同消息只生成一轮、后续消息复用同 Session、一个实际工具副作用、原始 Actor、
  同计划键重复 Cron fire 只生成一轮并完成、两个新 Session 在 cp.sessions 中均为零行。
  证据 `/private/tmp/managed-mq-replicas-20261015-trigger-c/triggers-1792023853.json`，
  日志 `/tmp/managed-trigger-replicas-acceptance-c-final.log`，数据库 ma_replicas_1452dbee7bca。
- 真实账号 / 凭据 / 原生文件 / 冻结定义专项通过：
  `/tmp/managed-trigger-native-account-integration-final.log`。
  此项只验证 API 行为，确定性模型不支持它的普通输入，不能据此声明该输入的推理完成。
- 此前 trigger-a / trigger-b 是失败诊断夹具，保留记录并停止进程；
  trigger-c 第一份记录也是公共事件字段核对失败，只有上述 passed=true 记录代表通过。

剩余边界：AgentTask / Team / Workflow 桥、旧 find-or-create 调用方及其存储仍待单独处理；
本轮不把 Channel / Cron 的切换表述为所有入口迁移完成。未连接真实 IM / ACS / 商业模型。


额外端到端核对：实际公共事件的 Turn 坐标位于 data.turn_id，assistant role 保留既有
大写枚举格式。Channel 按真实坐标筛选，并接受既有 role 大小写；没有修改公共事件协议。
Java 桥通过真实控制面 / Portal / MQ / Brain / Hands 读取最终回复及重复 provider message
的回归已通过；未连接真实 IM provider。

最终补充检查：

- `/tmp/managed-trigger-java-live-channel-final.log`：六项通过、零跳过，含一项真实副本桥接。
  Scheduler 打包通过；Portal 两项注册回归与打包已在本轮前序检查通过。
- `/tmp/managed-trigger-spotless-check.log`：本轮 Java 文件的格式检查通过。
- 文档单测 13 项通过，broken-links 通过；`npm run validate` 仍因五篇既有内部讨论文档
  不在导航中失败。没有把内部讨论稿发布进公共导航来消除检查失败。
- 测试进程由 fixture stop 清理，数据库与证据保留。正式整套部署、全仓 clean verify、
  其他适配器与完整故障矩阵仍未完成；不得按本节验收推断总计划完成。


最后配置核对补充：原生 Endpoint 未指定 mounts 时也继承冻结定义中的 memory / vault 默认值；
显式空数组可以清除默认挂载。真实 PostgreSQL 专项验证缺失默认 Vault 会拒绝、显式清空后
正常解析，而不是悄悄丢弃默认依赖。该修复后 Go product / httpapi 包测试和构建重新执行，
以 `/tmp/managed-trigger-native-config-postgres.log`、`/tmp/managed-trigger-go-full-tests-final.log`、
`/tmp/managed-trigger-control-build-final.log` 的最后结果为准。

诊断遗留：账号 API 集成的普通文本 / 文件输入不在 fixture 模型支持范围，模型抛出
IllegalArgumentException 后可观察到未 ACK 的恢复重试。这不是本轮真实推理成功证据；
后续故障矩阵需要明确永久构建 / 模型输入错误的收尾与有界重试行为。本轮未把这类错误
伪造为工具结果或成功终态，相关诊断数据随 fixture 保留。


## 失败收尾、ACK 顺序与输入边界（2026-10-15）

本轮实现与决策：

- Core 的 failed 运行记录也可能来自内部工具挂起，不能直接作为逻辑 Turn 失败。
  Service 在有效执行权和完整检查点下，对明确 Agent 异常或失败结果发布自己的失败决定，
  与原生状态一起经过 MQ / Hands 存储；终态发布中断后的恢复先完成这项决定，不再次推理。
- SessionLog / MQ 故障、工具等待、取消中断与失去执行权保持原恢复路径。
  未知外部工具结果不伪造、不关闭；可确认的长工具继续用原 ToolTask，必要时挂起同一 Turn。
  尚未开始 Agent 运行的确定性构建错误分类仍待处理；本轮不宣称所有错误都能自动收尾。
- 失败时关闭输入门，所有已受理输入均随状态被应用或明确拒绝。异常文本不写进公开事件。
  未修改 Core / Harness 的 builder、接口、状态格式或事件 schema。
- 核对发现原调度把数据库终态序号直接当作 Redis 就绪信号，不能证明上一轮 ACK 已确认。
  已区分“逻辑终态已提交”与“可以执行的已确认序号”。后者扣除原始 / resume 的待确认 ACK，
  同时用于 Brain 准入短事务、延后投递和 Redis 进度补偿；不是新增租约或调度组件。
  ACK 模糊且 Redis 副本缺失时不能猜测确认；双丢失的实际 MQ 故障矩阵仍待验证。

验证范围与证据：

- `/tmp/managed-failure-services-package.log`：46 项相关测试通过，Portal / Brain / Hands 打包。
  新增五项实际 Harness 测试：Agent 错误后下一轮、终态发布失败重投递、迭代上限失败、
  SessionLog 故障重试、未知外部工具拒绝终态。MQ / Redis 为显式 Fake，数据库为 H2。
- `/tmp/managed-failure-postgres-redis-tests-final.log`：26 项实际 PostgreSQL / Redis 专项通过。
  原测试假设一个全局扫描就修复丢失提示，在共享数据库多页时不成立；改为有界页面迭代，
  仍要求相同 ACK / 单调进度结果，没有放宽最终断言。此组 MQ 仍是显式 Fake。
- `/tmp/managed-ack-gate-tests.log`：48 项通过，新增数据库终态不能提前准入下一轮的回归；
  ACK 确认前就绪序号为零、准入拒绝，确认后序号与准入推进。覆盖真实 PostgreSQL / Redis。
- `/tmp/managed-failure-ack-services-package.log`：包含 ACK 屏障的三个服务重建通过；
  `/tmp/managed-failure-ack-spotless-check.log`：本轮 Java 文件格式检查通过。
- 真实中间件 / 双 Portal / Brain / Hands 的 failure-a 夹具：
  数据库 ma_replicas_d1739a7c39a6，目录 /private/tmp/managed-mq-replicas-20261015-failure-a。
  `failures-1792026093.json`：模型报错 → 完整失败 / ACK → 后续 Turn 成功，只有一次失败决定。
  `reply-1792026334.json`：使用既有确认待办，跨 Portal 幂等 reply 应用到同一 Turn、待办清除、
  后续 Turn 完成，一个本地工具副作用。本轮没有新增权限机制；通用外部执行 reply 待补专项。
  `input-close-1792029075.json`：65 条已受理输入，32 applied、33 rejected；终态后重试身份保留，
  新输入明确 input_closed。有限输入会延长工作，测试不再额外要求两秒内关门。
  `takeover-1792028541.json`：先完成 workspace warmup，再 SIGSTOP 原 Brain 的 240 秒工具；
  epoch 2 → 3 接管，恢复旧实例后已提交前缀的代次不倒退，业务 ToolTask / 副作用各一次。
- 首次 takeover 观测在工具启动前超时，同时记录到 MQ 接收连接错误；原运行被保留，
  随后完成，只有一个工具副作用。它不是受控暂停通过证据，不能和上述 passed=true 混用。
- 包含 ACK 屏障的最新制品已在 failure-b 双副本重跑通过：
  数据库 ma_replicas_0a292da85f10，目录 /private/tmp/managed-mq-replicas-20261015-failure-b。
  `failures-1792029608.json` 验证失败收尾与下一轮，`smoke-1792029608.json` 验证 8 秒工具、
  完整挂起 / 独立 resume / 同一 Turn 继续与下一轮顺序，`reply-1792029783.json` 验证跨 Portal
  的 reply 及后续 Turn。三份记录均 passed=true；模型为显式确定性夹具，工具为实际本地进程。
- 两套夹具的七个服务进程均已停止，数据库与证据保留，未停止用户的原有开发服务。
  文档 13 项单测及 broken-links 通过；完整导航检查仍有前文登记的五篇内部讨论稿问题。

本节不代表总计划完成：实际 MQ ACK 模糊与双丢失、受控网络分区、未知工具启动 / 结果阶段、
DLQ / 过期与积压、通用 reply、Subagent、快照 / Fork、旧适配器、整套正式部署及全量检查仍待完成。

## Console Chat 与会话入口迁移（2026-10-15）

本轮决策：

- Managed Chat / Agent conversation 创建只注册原生资源，固定实际选中的 Binding、Agent
  版本和 Environment，不调用旧 Dataplane bootstrap，也不创建第二份 cp.sessions。
  保留控制面 Session 描述符供个人 Chat 与诊断入口关联；运行状态以原生 Session 为准。
- Managed Chat 的首次发送、后续发送与 Session 诊断页使用原生 `/v1/sessions` 协议。
  首次发送响应不确定时，保留草稿、原始幂等键与相同内容；手动重试复用这些值。
  开发 Vite 代理同步支持 `/v1`，不把原生请求交给 SPA 路由。
- 控制面的 Chat / user-message 入口也受理原生 Turn，保留原始人类 Actor 与调用键。
  原生 409、403、429 等受理错误保留 HTTP 状态，不统一包装成可重试的 503。
  Chat 受理回执为 accepted，事件链接指向原生 Session，避免返回已无 Managed 事件的旧地址。
- Chat 的 runtimeKind 从关联 Session 的 framework 读取，不再保存一份重复的运行类型。
  删除前查询原生运行状态，排队或运行时拒绝删除；完成后只删除个人列表项，保留执行证据。
  归档只读、置顶、Issue 链接与诊断入口保留；其他类型的现有 UI 路径保持原行为。
- Endpoint conversation 的物理 Session 使用其稳定 conversation UUID，并在原生注册时
  固定实际选中的 Managed candidate。本轮尚未完成该分支的独立真实 HTTP 验收。
- 本轮未改 Core / Harness。AgentTask 的旧 fence / wake / attempt-abort 仍待单独迁移，
  不以人类 Chat 的身份替代任务的原始 Actor 或委托关系。

验证结果：

- `/tmp/managed-conversation-go-final-all-tests.log`：Go `go test ./...` 通过；
  `/tmp/managed-conversation-go-make-test.log`：`make test`（含覆盖率）通过；需要外部显式测试环境的
  测试按其既有条件跳过，不能据此声明全部 PostgreSQL 集成通过。
- `/tmp/managed-conversation-ui-final2-tests.log`：36 个文件、179 项单测通过。
  `/tmp/managed-conversation-ui-e2e-final10.log`：5 项浏览器检查通过，覆盖新建 Chat 的首次发送
  响应不确定后复用原请求、原生快照、后续发送、
  诊断页、归档只读、Chat 删除 / 恢复，以及原幂等键与内容的超时重试；后端为 HTTP 夹具。
  前端构建通过，lint 零错误、98 条警告；日志为 `managed-conversation-ui-final2-*`。
- 真实 Redis / RocketMQ 5.5.1 / PostgreSQL，单控制面和各两个 Portal / Brain / Hands：
  最新 `/private/tmp/managed-mq-replicas-20261015-conversation-d/conversations-1792033785.json`
  为 passed=true，数据库 ma_replicas_b73712e4b1c1，含原生事件回执链接；前一份
  conversation-c/conversations-1792032568.json 也通过。首次消息前零 Turn；同键并发只一轮，
  改内容返回 409；后续 user-message 同 Session 排队；8 秒本地工具一次副作用，
  一次持久挂起与一次独立 Resume，同一 Turn 完成后才完成下一轮；Actor 保留且
  cp.sessions 零行；运行中删除返回 409，完成后删除仍可读原生 Session。
  这不是外部模型或 ACS 实测，也不是浏览器与真实后端的联合验收。
- conversation-a / b 及 conversation-c 的较早记录是诊断失败，不算通过；四套夹具所属
  进程均已停止，数据库与记录保留，用户原有开发服务未改。
- 文档 13 项单测和 broken-links 通过，git diff --check 通过。
  全仓不筛选 `mvn -B -T1 clean verify` 已启动，结果见
  首次 `/tmp/managed-mq-full-clean-verify-20261015.log` 中 Core 522 项、Harness 1157 项
  （18 跳过）通过；Service Common 的取消恢复夹具仍手动 ACK、未确认持久账本而失败。
  夹具已改为实际 ACK 分发器并断言可推进水位；恢复 / 失败 / 管线 16 项回归通过，
  见 `/tmp/managed-conversation-ack-fixture-tests.log`。
  第二次完整检查 `/tmp/managed-mq-full-clean-verify-20261015-final.log` 通过 Common、Portal、
  Hands、Gateway、Dataplane，在 Scheduler 启动测试发现其误扫描 Managed 运行依赖。
  已将 Scheduler 扫描范围缩到 outbound / webhook 并显式装配自身 Gateway，排除原生目录、
  ma 状态、MQ / Redis 基础设施；未修改运行角色的必选依赖，也未加入自动 Fake。
  `/tmp/managed-scheduler-role-tests-final.log`：恢复 / Channel / Cron / Scheduler 共 8 项、
  1 项需显式真实环境的 Channel 桥跳过，其余通过；启动测试明确断言没有 Managed 运行 Bean。
  完整检查最终于 2026-10-15 11:32 通过，日志
  `/tmp/managed-mq-full-clean-verify-20261015-final2.log`：不筛选的 `mvn -B -T1 clean verify`
  返回 0、全部 reactor 模块 SUCCESS，总耗时 16 分 9 秒。按测试自身条件跳过的外部环境用例
  仍不能计作真实外部环境验收。
  文档 validate 仍因五篇既有内部讨论稿未加入导航失败，见
  `/tmp/managed-conversation-doc-validate.log`，未把内部讨论稿发布进公共导航。

本轮不代表总计划完成。旧 AgentTask 适配器及存储清理、剩余故障矩阵、Subagent / Fork
完整验收、正式整套部署与文档导航检查仍是后续工作。

## AgentTask 原生受理回执基础（2026-10-15）

- 增加 `BindManagedTurn`，在现有 Task 与 ExecutionAttempt 上关联原生 Portal 返回的 Turn ID。
  只接受当前 Task 的 Managed Attempt、相同 Session 和派发代次；取消或被替换的
  Attempt 拒绝迟到回执。当前 Attempt 已成功 / 失败时仍可补记其真实回执，
  不改变终态。同一回执重试不改变版本，不生成租约、心跳或运行状态。
- 两个不同回执并发时只允许一个胜出；PostgreSQL 按 Task → Attempt 顺序加锁，
  与已有完成 / 重试事务保持一致。内存实现遵循同一行为契约。
- 不在控制面重新实现 Portal 的 Turn ID 算法。后续原生派发应复用同一 Attempt 的原始
  输入、身份与幂等键，在响应不确定时重试受理，再关联真实回执；不据此创建新的执行。
  当前仅完成存储基础，旧 AgentTask 的 fence / wake / 续租路径还没有切换。
- `/tmp/managed-task-receipt-postgres-tests.log`：memory、postgres、runtimebinding 三个包通过。
  PostgreSQL 使用显式本地测试 DSN 与独立临时 schema，运行完整 Store 行为套件；
  新增的并发回执、取消 / 替换防护、其他后端拒绝检查在两种 Store 上均通过。
  本节没有修改 Core / Harness。

## AgentTask 原生派发、取消与读模型（2026-10-15）

本轮已接入生产代码的行为：

- Managed Resolver 在短事务中认领 Task，固定 Attempt UUID、独立原生 Session 和发送输入；
  复用认领事务已有的 `agent-task.dispatched.v1` Outbox。派发请求本身不调用旧 cp Session
  创建、fence claim、wake，也不虚构 Turn ID。
- Outbox 取其载荷中的原始 Attempt ID，向原生 Portal 受理固定意图。受理 / 传输失败只重试
  同一 Attempt，不把不确定结果改为新 Attempt。当前 Attempt、代次、Session 与取消检查
  阻止迟到事件重新受理。Portal 真实回执在控制面返回前关联，Resolver 的重复关联保持幂等。
- 资源控制面承担原生 Session 配置冻结；私有 Session 保存 Attempt 引用，公开 View 不返回
  引用或 Task token。私有配置才包含任务执行上下文。人类身份不替换成资源 owner；Endpoint
  来源沿 Issue 祖先读取原始 Invocation 及 Application 凭据。委托关系仍保存在 Task 中。
- 独立 Go 编排运行时通过内部的、只接收 Attempt ID 与固定操作的控制面接口调用同一逻辑，
  不能通过此接口自带用户身份、任意输入或配置。控制面配置读取与 Portal 受理保持原有边界。
- Managed Attempt 不再进入控制面的 startup / heartbeat 超时重排和 cancel grace 强制结束。
  有界扫描读取原生 Turn，实际推理开始后推进任务读模型；StartAgentTask 不为 Managed 创建
  心跳。首个 MCP 调用可即时读取原生 Turn，关联先于 HTTP 回执开始执行的真实 Turn，
  再推进 Task / Attempt 状态，快速 `task.complete` 不依赖旧 running 回调或周期续租。
- 取消通过原生 cancel Input 受理，复用固定取消键。受理与取消共用一个短控制面互斥范围，
  防止取消确认后继续创建原生执行。取消前尚无 Session / Turn 可在互斥范围内确认未执行；
  已开始的执行要等原生 Turn 终态，不把 Input accepted 或时间经过当作停止证明。
  每个 Attempt 的独立 Session 保证旧 Attempt 的取消不作用于新 Attempt。
- 原生 Turn 已结束却没有 `task.complete` / `task.fail` 时，任务明确失败；普通模型最终文本
  不自动成为任务交付。其他后端仍沿自己的协议运行。本轮未修改 Core / Harness。

验证与边界：

- `/tmp/managed-task-native-tests3.log`：Resolver、HTTP API、Controller、runtimeportal、invocation
  五包定向回归通过。新增 Outbox 原始 Attempt 重试与 Managed 不受旧超时重排 / 强制取消检查。
- `/tmp/managed-task-native-store-postgres.log`：内存与真实 PostgreSQL 的 Store 专项通过，
  包括并发回执、取消 / 替换防护和先提交失败终态再补回执。PostgreSQL 使用独立临时 schema。
- `/tmp/managed-task-native-http-postgres-final.log`：真实 Product PostgreSQL 目录配合显式
  Portal HTTP 夹具通过。测试自行创建并删除独立数据库；原生 Session 预注册，未验证首次
  Harness 装配。覆盖首个响应丢失后的原键 / 原输入重试、原始人类 Actor、真实回执关联、
  24 小时读模型超时仍不重排、cancel Input 尚未停止时不结束、原生取消终态后结束、
  旧取消不作用于新 Session、受理前取消，以及首个 MCP 查询早于回执后快速 task.complete。
- 后续 `/tmp/managed-task-native-creation-http-postgres.log` 也通过：在同一组明确的 HTTP 夹具中
  新增首次 Task Session 创建，实际经过 Product 目录、Environment 选择和私有配置投影，
  固定定义版本 7 与 self_hosted Environment；重试只注册一次配置，Task token 仅出现在
  私有配置中，公开 View 不含 token，cp.sessions 零行。仍没有启动真实 Harness / Sandbox。
- `/tmp/managed-task-native-integration-final.log` 补充内存与真实 PostgreSQL 的 Managed 启动检查：
  推进 running 状态和 startedAt，不生成心跳、租约或 LeaseToken；其余回执契约及 HTTP 专项通过。
- `/tmp/managed-task-native-go-make-test-final.log`：`make test` 含全包覆盖率通过；显式外部
  环境用例按自身条件跳过，不能据此声明这些集成已验收。构建日志见
  `/tmp/managed-task-native-build.log`，四个 Go 命令构建通过。
- 最终源码再次通过 `make test` 与四命令 `make build`，分别见
  `/tmp/managed-task-native-go-make-test-final2.log` 与 `/tmp/managed-task-native-build-final2.log`。
  默认 Store PostgreSQL 集成仍因未设置 DSN 而跳过；真实专项以上述显式 DSN 记录为准。
- 文档 13 项单测与 broken-links 通过；validate 仍只有前文列出的五篇内部讨论稿未进导航，
  没有为通过校验而把这些讨论稿发布到公共导航。日志前缀为 `managed-task-native-doc-*`。

本轮没有完成 AgentTask 整体迁移验收：还需用真实 Portal / Brain / Hands 验证首次 Session
创建、实际 Harness 和 MCP 工具闭环，补齐 Application / 委托来源的授权投影与失败分类；
当前封存配置中的私有 Task token 仍有一小时有效期，必须完成长周期凭据刷新，不能据此
宣布跨小时任务可用。旧 Managed 报告 / 续租接口及对应数据库标记仍待删除，旧工具确认
适配器也需继续迁移。原有 Attempt 业务超时需要映射到原生 cancel 并等待停止证明，
不能重新接回旧 startup / heartbeat 重排。通用故障矩阵、Subagent / Fork 与完整部署目标继续保留。

## AgentTask 实际 Harness / MCP 与长工具验收（2026-10-15）

本轮实现和决策：

- 去掉新原生 Task 私有配置中的一小时凭据限制，改用签名绑定逻辑 Attempt 的私有工作负载
  身份。每次调用检查当前 Task / Attempt / Session、代次、Agent 和 Binding 状态、当前资源
  授权与原生 Turn。取消、替换或终态会拒绝继续执行；不能只验签就放行。
  普通过期 Task token、Hosted 物理租约和后端报告凭据的格式及有效期保留。
- 通用旧验签便捷方法拒绝直接授权此类凭据；Artifact 上传 / 下载也走完整任务校验。
  凭据只存在于私有封存执行配置，不进入模型提示、公开 View 或公开事件。
  当前策略与凭据没有周期更新，物理 Brain / Hands 的 Guard 和 fencing 仍独立生效。
- 实际 MCP 接入发现原始 Agent 的 built-in 默认关闭会误拒绝平台注入的 task.get。
  ToolConfirmationMiddleware 现在识别私有 Task 上下文提供的平台动作，并与装配时一致；
  显式 per-tool deny / ask 仍优先，普通工具不因 Task 上下文而获得权限。缺少私有凭据或
  无法读取策略仍拒绝执行。本轮没有修改 Core / Harness。
- 业务执行超时读取冻结策略和 startedAt：到期先写 cancel_requested 并发送原生 Input，
  原生停止确认前不失败或重排。停止后将仍活动的 Task / Attempt 记为 attempt_timeout；
  人类已取消的 Task 保留取消结论。只有排队等待不消耗执行时限。
- 已记录 Turn ID 或开始时间时，缺少私有会话元数据返回未确认停止，不能强制取消。
  没有把数据库读取失败或时间经过包装成物理停止证明。

真实运行证据：

- 隔离 task-b 目录 `/private/tmp/managed-mq-replicas-20261015-task-b`，数据库
  ma_replicas_dd09102b8f4f，真实 Redis / RocketMQ 5.5.1 / PostgreSQL，两个 Portal、两个
  Brain、两个 Hands 与一个启用真实 AgentTask Outbox / observer 的控制面。
  模型是显式确定性夹具，Sandbox 是实际本地子进程；没有使用 ACS 或外部模型。
- `task-pipeline-1792038772.json`（8 秒）和 `task-pipeline-1792038868.json`（180 秒）均
  passed=true。实际链路为 Issue → Outbox → 原生受理 → MCP task.get → Sandbox 工具 →
  同 Turn 挂起 / Resume → MCP task.complete → 最终公开结果。两次都只有一个 Attempt、
  一个 Turn、一次挂起、一次 Resume 和一组 start / done 副作用，cp.sessions 零行，
  Attempt 没有 HeartbeatAt、LeaseToken 或 LeaseExpiresAt；原始人类 Actor 保留。
  公开事件包含最终结果，不含 Task token。8 秒和 180 秒的日志分别为
  `/tmp/managed-task-pipeline-b.log`、`/tmp/managed-task-pipeline-long-b.log`。
- `task-cancellation-1792038939.json` 为 passed=true：通过 Task 取消 API 受理一个原生
  cancel Input，Input 被持久应用，实际子进程停止且只有 start 副作用，之后读到原生取消
  终态并结算物理 Attempt。日志 `/tmp/managed-task-cancellation-b.log`。
- task-a 初次验收因平台 MCP 工具被原始默认策略误拒绝而失败，记录没有改成通过；修复
  来自上述策略装配一致性调整。a / b 两套进程均已按 manifest 的真实 PID 与命令确认停止，
  数据库与证据保留，未停止用户原有服务。

检查和边界：

- `/tmp/managed-task-tool-policy-tests.log`：策略中间件和装配身份 / 缓存共 21 项通过，覆盖
  平台 Task 动作、普通工具拒绝、显式拒绝和缺少私有凭据。三个服务打包与限定格式检查通过，
  见 `managed-task-capability-java-package.log`、`managed-task-tool-policy-package.log` 和对应 format 日志。
- `/tmp/managed-task-capability-authority-postgres.log`：真实 Product PostgreSQL 目录和显式
  Portal HTTP 夹具通过。覆盖跨 72 小时验签、Session 绑定、Binding 禁用撤权、取消撤权。
  这不是实际持续 72 小时的运行测试。
- `/tmp/managed-task-capability-timeout-postgres.log`：上述 HTTP 专项扩展通过，冻结的一秒
  业务时限先请求取消而不提前失败，模拟原生停止确认后才结算超时；已记录执行身份但缺少
  元数据时保留取消中。同时验证截止时间边界和未开始的排队等待。
  超时专项的 Portal 仍是显式 HTTP 夹具，不能记为真实 Sandbox 超时验收。
- Go 全包测试与构建重新执行，最终日志为 `managed-task-capability-go-make-test-final.log`
  和 `managed-task-capability-build-final2.log`；未设置显式 DSN 的外部环境测试仍按条件跳过。
- Java 全仓 `mvn -B -T1 clean verify` 再次启动，日志
  `/tmp/managed-task-capability-full-clean-verify.log`；2026-10-15 12:54:59 返回 0，
  全部 reactor 模块 SUCCESS，耗时 15 分 52 秒。按自身条件跳过的外部环境用例不计为真实验收。

剩余目标仍保留：Application / 委托来源授权投影、MCP 副作用和终态回执重试的完整故障验收、
实际业务超时取消、旧 Managed 报告 / 续租 / 工具确认接口及状态清理、通用故障矩阵、
Subagent / Fork、整套部署和文档导航检查。本节不代表总计划完成。

## 终态 MCP 回执丢失与实际业务超时（2026-10-15）

- 非 Leader Managed Task 提交 task.complete / task.fail 后，普通工作权限仍然失效；只在
  原始原生 Turn 尚活动且输入门未关闭时，允许受限的只读终态回执。原 Task / Attempt / 代次 /
  Session、当前资源与 Binding 仍须校验。只返回已保存的同一结论，忽略重试中试图改写的
  结果；其他写工具拒绝，不重新增加评论或改变 Task 版本。Leader 保留原来的最终节点协议。
- 新增显式测试代理，只在隔离夹具的 Brain → 控制面链路丢弃一次已成功提交的
  task.complete 响应，然后用完全相同的请求重试；默认部署不启用，不是生产依赖。
  代理只记录 Task ID 和成功 / 重试标记，不保存凭据或工具请求 / 响应正文。
- `/tmp/managed-task-terminal-receipt-final-postgres.log`：真实 Product PostgreSQL 目录与
  Portal HTTP 夹具验证回执丢失后的路由重试、结果不可改写、普通工作拒绝、评论不增加，
  输入门关闭后收据权限撤销。`managed-task-receipt-go-make-test.log` 全 Go 包覆盖率检查
  通过，`managed-task-receipt-build.log` 四个命令构建通过。
- 实际 task-c 夹具目录 `/private/tmp/managed-mq-replicas-20261015-task-c`，数据库
  ma_replicas_ca171675920c，两个 Portal / Brain / Hands、一个控制面与显式本地故障代理。
  `task-pipeline-1792040274.json` 为 passed=true：8 秒实际子进程、一次挂起 / Resume，
  MCP 终态先提交，代理丢弃首个响应后实际重试返回 replayed=true，原生 Turn 正常完成。
  只有一个 Attempt、一个 Turn、一条结果评论和一组 start / done 副作用；cp.sessions 零行。
  代理证据 `mcp-reply-retries.json`；日志 `/tmp/managed-task-terminal-reply-loss-c.log`。
  这是显式代理执行的上游重试，不是宣称所有 MCP SDK 都会自动重试任意失败。
- `task-timeout-1792040385.json` 为 passed=true：通过实际策略 API 配置 30 秒业务时限，
  在 60 秒实际子进程挂起期间受理原生 cancel，子进程只有 start 副作用；原生取消终态后
  Task / Attempt 才记录 attempt_timeout。后续 SQL 审计确认只一个 Attempt，Task 与
  Attempt 的 completedAt 均不早于原生 turn.cancelled 事件时间。日志
  `/tmp/managed-task-business-timeout-c.log`。这补齐上一节标记待验收的实际业务超时路径。
- a / b / c 的模型均为明确的确定性夹具，本地子进程不代表 ACS 验收；本轮 Core / Harness
  未修改。task-c 全部服务与代理按 manifest 停止，证据保留；未操作用户原有开发服务。
- 文档 13 项单测与 broken-links 通过，validate 仍只有五篇内部讨论稿未进公共导航；
  日志前缀为 `managed-task-capability-doc-*`。git diff --check 通过。
- 最终补充 Managed Task 的运行描述符 framework=managed，供诊断页识别原生会话。
  最终 Go 检查 / 构建日志为 `managed-task-receipt-go-make-test-final.log` 和
  `managed-task-receipt-build-final.log`，均返回 0；文档 13 项及 broken-links 再次通过，
  validate 仍为上述五篇内部稿导航问题，日志前缀 `managed-task-receipt-doc-*`。

仍未完成：Application / 委托来源授权投影、任意 MCP 副作用的未知结果 / 重试与旧 Brain
并存故障验证、Leader 终态回执、旧接口和状态删除、通用 MQ / Redis 故障矩阵、Subagent /
Fork、整套部署与文档导航检查。不能将只读 task.complete 回执推断为任意外部工具 exactly-once。

## 删除旧控制面 Managed 执行协议（2026-10-15）

本轮完成的切换：

- 删除 `/api/internal/runtime-sessions/{id}/start`、`heartbeat`、`events`，及其重复的
  AgentTask 启动、心跳、消息和 Endpoint 结果投影实现。原生 Task、Session 和 Invocation
  的回归继续保留；只删除针对已退役回调协议的测试，并增加退役路由 404 检查。
- 删除控制面主动 fence claim、wake、旧工具确认推送、Attempt abort HTTP、整 Session
  interrupt 和未调用的旧产品 Session 辅助 API。原生 Invocation 取消查不到受理回执时
  返回错误，不能回退成无原生 Turn 身份的旧 interrupt。
- 删除产品 Session `PATCH .../runtime`、对应物理 fence 校验器和重复运行代次投影。
  保持已发布迁移校验和不变，增加破坏性迁移 `0006_remove_managed_runtime_markers`，
  删除 runtime_agent_task_id、runtime_attempt_id、runtime_dispatch_generation、runtime_turn_id。
  当前 Session 扫描器不再读取这些字段。
- Managed 私有 Task 上下文必须有匹配的原生 Session / Attempt 标记；删除缺少原生标记时
  签发旧定时 Task token 的分支。普通用户、Hosted / External 的凭据协议保持各自边界。
- 共享工具确认仅保留 Hosted / External 的拉取协议：决定写入不自动恢复，Runtime 读取
  该决定才恢复，随后 ACK 记录投递。两种后端均有实际 HTTP 回归。原生 Managed 确认仍由
  Session 的 Required Action / Input 处理，不再使用旧控制面回调或租约。
- Job 发布终态不再查询旧 DataPlane event-mirror-status，改为等待每个 Managed Attempt
  的原生 Turn 持久终态。查询失败、身份不匹配、仍活动时不能据 Task 终态提前关闭。
  未执行的缺失检查与受理使用相同短互斥范围，已知执行身份缺失不能当作停止证明。
  独立 Go 编排运行时通过内部 `settled` 查询同一判断。
- 新增私有 `ManagedAttemptReceipt` 的版本校验；专项发现此前未版本化的 admit / observe /
  authorize 回执被共享解码器拒绝。控制面和客户端现已使用同一类型，实际跨 HTTP 测试
  验证上述操作与 settled 回执，不通过修改通用解码器绕过协议校验。

验证证据与范围：

- `/tmp/managed-retire-native-postgres.log`：真实 Product PostgreSQL 目录配合显式 Portal
  HTTP 夹具通过。覆盖原生受理重试、运行 / 终态完成判断、503 不提前关闭、异 Turn 拒绝、
  私有跨服务回执、无原生标记不签发凭据；Hosted / External 工具确认分别通过。
  Product schema 测试在独立测试数据库重建旧字段和旧标记行后升级，验证字段删除、
  新 Session 扫描及只读启动。没有在用户开发数据库执行这些破坏性测试。
- `/tmp/managed-retire-go-make-test.log`：Go 全包 `make test` 含覆盖率返回 0；
  `/tmp/managed-retire-go-build.log`：四个命令 `make build` 返回 0。
  未设置 DSN 的默认测试仍按自身条件跳过，真实 PostgreSQL 以上述显式专项为准。
  私有回执客户端的 `go test -race` 返回 0，见 managed-retire-courier-race.log。
- 实际隔离目录 `/private/tmp/managed-mq-replicas-20261015-retire-a`，数据库
  ma_replicas_be5d2030c9eb，两个 Portal / Brain / Hands 和一个控制面，真实 Redis /
  RocketMQ / PostgreSQL。模型为显式确定性夹具，工具为实际本地子进程，未访问 ACS。
  `task-pipeline-1792042344.json` 与 `task-cancellation-1792042406.json` 均 passed=true。
  前者一个 Attempt、一个 Turn、一次挂起 / Resume 和一次实际工具执行，MCP 完成及最终
  公开结果通过；后者通过原生 cancel Input 停止子进程，原生停止后才结束 Attempt。
  日志分别为 managed-retire-task-pipeline.log、managed-retire-task-cancellation.log。
  `retired-state-facts.json` 确认旧字段零个、旧 cp.sessions 零行、新迁移应用一次。
- 本轮七个测试服务按 manifest PID 与命令标记停止，数据库和证据保留，用户原有服务及
  Docker 基础设施未停止。本轮无 Java / Core / Harness 修改；此前全仓 Java clean verify
  的通过记录仍对应当前 Java 源码。
- 文档 13 项单测及 broken-links 通过；validate 仍仅因五篇内部讨论稿不在公共导航失败，
  日志前缀 managed-retire-doc-*；git diff --check 通过。

后续目标保持完整：Java 的 SessionTurnRunner / 旧事件镜像、工具确认协调与存储仍需删除或
迁移；原生 AgentTask 到 Job 的完整公共事件 / Required Action 投影、Application / 委托
来源、任意外部工具未知副作用、MQ / Redis 组合故障、Subagent / Fork、整套部署和文档
导航仍待验收。不能把本轮控制面清理和 Task 基本闭环作为整体迁移完成。

## Java 回调、镜像存储与原生 Task 事件（2026-10-15）

实现与自主决策：

- Java 删除控制面 start / heartbeat / runtime status patch / event report 客户端、
  ManagedEventOutbox、ControlPlaneSessionEventMirror、event-mirror-status Controller、
  旧 Attempt abort Controller 及对应 JPA 实体 / Repository。删除旧手工建表脚本，新增
  Dataplane V0006 清理三张镜像表；历史迁移校验和保留。
- SessionNativeLogService 不再维护镜像 admission、周期续期或控制面投递。旧本地
  SessionTurnRunner 只调用本地 Runtime admission，不再捕获控制面 Attempt 或建立其
  启动 / 心跳；未知所有权的状态更新不回退到产品 Session patch。
  其本地取消、旧 Runtime 与子会话依赖仍在，整类和旧 Runtime 图尚未删除。MQ Brain
  继续通过 ManagedBrainTurns；本轮没有改 Core / Harness 公共接口或实现。
- 共享执行投影中的 Managed Task 改为按原 Attempt 读取原生公开事件，独立持久游标。
  消息、工具及 Required Action 的身份保留执行作用域，其他 Turn 的事件跳过。
  回复走原生 reply Input；受理回执丢失复用原命令键，后续读取原 Input 的应用状态。
  要求 Attempt 属于原 Invocation 的 Run 范围且关联同一个私有 Session；取消或替换后
  的决定拒绝投递。复用共享执行投影，不重新开放旧 Endpoint invoke 路由。
- 独立 Go 编排 Runtime 不运行公开 Invocation / Session Webhook 后台 worker，避免
  其没有资源目录却竞争认领公开投影工作；保留私有执行 / Outbox 职责。

已有验证与准确边界：

- managed-retire-java-tests2.log：Java 定向 35 项，34 通过，1 项需 PostgreSQL 条件而跳过。
  随后显式 PostgreSQL 的 native writer 专项通过，日志 managed-retire-java-postgres.log。
- managed-retire-java-full-clean-verify.log 在 14:06:02 返回 0，全 reactor SUCCESS，17 分
  30 秒。它对应迁移清单漏注册修复前的源码，不能作为当前最终版本的构建结论。
- 实际 a 目录 `/private/tmp/managed-mq-replicas-20261015-java-retire-a`，数据库
  ma_replicas_b05b64802cd3，两个 Portal / Brain / Hands 与一个控制面，真实 MQ / Redis /
  PostgreSQL；模型为确定性夹具，工具为实际本地子进程，未访问 ACS。
  180 秒 Task 已得到实际最终结果、一个 Turn、一次挂起 / Resume，但新增存储断言发现
  三张旧镜像表仍在，因此 task-pipeline-1792044506.json 保留 passed=false，整体验收失败。
  原因是 ServiceSchema 使用显式迁移清单，新 SQL 已打包却未注册，未通过其生产入口执行。
- 已在 ServiceSchema 注册 V0006，并在 RuntimeSchemaMigrationTest 中通过实际生产迁移
  入口断言旧表为零。managed-retire-registered-schema-postgres.log：此专项和 native
  writer 两项均通过。修复后的全仓复查见 managed-retire-java-full-clean-verify-final.log；
  尚需以该进程的实际最终状态和重新部署验证为准，不能复用上一轮通过或手工删表。
- a 的并行 task-cancellation-1792044545.json 为 passed=true。取消完成时，180 秒 Task
  仍 running / waiting_tool；cancel-while-long-waiting.json 保存这个同时刻的实际状态。
  这验证了两个 Session 的推进，不代表执行池饱和或完整父子容量验收。
  a 的七个服务已按 manifest 停止，数据库及失败证据保留，未停止用户服务或基础设施。
- managed-native-task-events-final-postgres.log：真实 Product PostgreSQL 目录加显式 Portal
  HTTP 夹具验证分页、原生公开事实重放、跨 Invocation 拒绝、回复响应丢失后原键重试、
  Input 从 accepted 到 applied、取消后拒绝决定；该专项不是实际 Harness 的确认流程。
  同组 `go test -race` 通过，见 managed-native-task-events-race-postgres.log。
  Go 全包 make test 与四命令 make build 返回 0，日志前缀 managed-native-task-events-go-*。

文档发布范围修正：

- 五篇 Managed Agents 内部讨论稿加入既有 .mintignore，保留原路径，不加入公共导航。
  仓库 checker 现在用与 Mintlify 相同的 ignore 规则语义确定发布页，并继续拒绝导航或
  公开链接指向被排除的文件；普通未入导航页面仍失败，不能任意隐藏漏配页面。
- 使用 Mintlify 已有的 ignore 7.0.5，增加直接开发依赖声明；没有引入新运行组件或更换
  其包版本 / 完整性 / 公共 registry。三个新回归覆盖排除、否定规则、公开链接及导航拒绝。
  managed-publishing-doc-tests.log：16 通过；managed-publishing-doc-validate.log：仓库校验
  和 Mintlify 严格构建通过；managed-publishing-doc-links.log：无坏链。此前五项导航问题
  已解决，内部架构稿内容没有为通过校验而改写或发布。

仍保留 Java 旧 Runtime 图 / 工具确认清理、Application / 委托来源、通用外部工具未知
副作用与旧 Brain 并存、MQ / Redis 组合故障、Subagent / Fork、完整容器部署和端到端
迁移验收。后续实际新部署结果另行追加；本节不表示总计划完成。

## 迁移清单修复后的最终复查与新部署（2026-10-15）

- managed-retire-java-full-clean-verify-final.log 在 14:31:43 返回 0，全部 reactor SUCCESS，
  耗时 16 分 13 秒；此次源码包含 ServiceSchema 的 V0006 注册和旧表断言。此前 a 的失败
  记录保持不变。没有通过手工删表、忽略存储断言或重写失败结果替代生产迁移修复。
- 全新隔离 b 目录 `/private/tmp/managed-mq-replicas-20261015-java-retire-b`，数据库
  ma_replicas_5e733b14d6f9，用上述新包执行生产迁移入口并启动双 Portal / Brain / Hands
  和一个控制面。retired-storage-before-acceptance.json 在业务验收前已确认旧镜像表为零、
  旧控制面运行字段为零，原生配置表存在。
- task-pipeline-1792046061.json 为 passed=true：180 秒实际本地工具、一个 Attempt、
  一个 Turn、一次挂起和一次 Resume、一个任务结果评论、最终公开结果与原始 Actor。
  实际副作用只有一组 start / done；没有控制面租约、心跳或旧产品 Session。最终持久事实
  再次确认三张镜像表和四个旧运行字段为零。日志 managed-java-retire-task-long-b.log。
- task-cancellation-1792046105.json 为 passed=true：另一个 Session 的原生 cancel Input
  被应用、实际子进程停止，原生停止证明后物理 Attempt 才结束。其执行时间区间完整落在
  长任务的执行区间内，见 concurrent-cancellation-timestamps.json。b 使用持久时间戳证明
  执行并行，不复用 a 的瞬时状态采样，也不将此记为父子容量或饱和并发验收。
- a / b 均为真实 RocketMQ / Redis / PostgreSQL、显式确定性模型和本地进程 Sandbox，
  没有访问 ACS / 外部模型。b 的七个测试服务已按 manifest 停止，失败 a、通过 b 的数据库
  和证据保留，用户原有服务及基础设施未停止。
- 文档 matcher 最终还对齐 Mintlify 对规则行去首尾空白和 CRLF 的处理。最终 16 项单测、
  432 个发布页 / 584 个重定向的仓库检查、Mintlify 严格构建和 broken-links 均通过，
  日志 managed-publishing-doc-{tests,validate,links}-final.log。公开 registry 与依赖完整性
  保持原值，仅将已存在的 ignore 7.0.5 声明为直接开发依赖。
- 最终 Go 来源仍对应此前 make test / make build 与真实 PostgreSQL 的 race 通过记录。
  本轮没有 Core / Harness 实现或公共协议改动。git diff --check 通过，未提交或推送，
  未把测试部署当作用户原有开发服务或正式整套容器部署已更新。

Java 旧 Runtime 整体删除、原生 Subagent / Fork 完整迁移、Application / 委托来源与其他
故障矩阵、正式部署等目标仍未完成；本次修复与验收不缩减总目标。

## 原生 Subagent 受理、Hands 调度与容量边界（2026-10-15）

本节是原生子执行的阶段实现，尚未完成 Harness 的父子推理、恢复与完整迁移验收。

- `ManagedSubagents` 将原始父 ToolTask、冻结调用说明、子 Session 和首个子 Turn 在同一
  PostgreSQL 事务中关联。重复派发返回同一个子 Session / Turn；子 Turn 从普通发布账本
  投递，不新增协调服务或另一套执行租约。失败会回滚子 Session 和父调用关联。
- `managed-subagents.sql` 已注册到生产 `ServiceSchema` 迁移清单和测试初始化入口，新增
  子调用关联、根树互斥行及工具扫描公平性字段。生产迁移专项检查子调用表确实存在。
- 子调用保留原始 Actor、根 Session / Turn 和共享预算身份；默认每棵根树最多 256 个
  非终态调用、嵌套深度最多 16。子调用共享已受理根任务的应用并发额度，不重复消耗公共
  请求速率额度，但仍串行检查应用预算与队列容量。内部子 Session 的新 Turn 必须来自
  持久父调用，普通受理入口不能借此绕过应用并发限制。
- 根树使用独立互斥行，Session 锁保持子到根的预算提交顺序。两个不同根并发创建子
  Session 的真实 PostgreSQL 测试发现了 quota 外键 `KEY SHARE` 升级 `FOR UPDATE` 的
  死锁；现已在插入子 Session 前持有根应用 quota 锁。没有用重试、放宽容量断言或跳过
  PostgreSQL 替代修复。失败日志 `managed-subagents-acceptance-tests.log` 保留。
- `ManagedSubagentExecutor` 已接入平台 Hands：幂等创建、查询原始子 Turn、读取该 Turn
  持久输出、原始取消 Input，以及终态 / checkpoint / 工具停止事实校验。取消受理后仍返回
  running，直到子 Turn 实际终态。根取消在嵌套调用启动和查询时均被检查。
- 子调用等待时释放 Hands 物理执行槽和 Redis 工具令牌。`pendingTools` 按最近实际派发时间
  轮转，使单槽位下等待父调用不会反复占据扫描首位。无本机执行器的 self_hosted 工具只
  延后扫描，不消费首次启动事实；self_hosted Worker 不领取平台子调用调度任务。
- 父调用结果有专用回复格式；启动前取消也返回该格式，避免与普通 Shell 结果混淆。
  成功子 Turn 若缺少持久输出会明确失败，不能凭终态状态捏造消息。

验证：`managed-subagents-fk-lock-tests.log` 对应 Common 59 项通过、Portal 23 项通过 / 1 项
因未提供 `MANAGED_TEST_GO_BIN` 跳过、Hands 5 项通过，总计 **87 项通过、1 项跳过**，
reactor `BUILD SUCCESS`；Dataplane 同批编译通过。子调用 15 个行为用例分别运行于 H2
和每用例独占的真实 PostgreSQL 数据库，包含双 Hands 重放、根取消、跨根队列竞争、失败
回滚、原始输出恢复，以及只有一个 Hands 槽位的父调用 → 子工具 → 父结果调度流程。
该调度流程使用真实调度器 / 数据库和明确的内存 MQ / Redis、工具替身，没有真实 Harness
模型推理或 ACS 调用。`managed-subagents-final-spotless.log` 范围格式检查通过。

仍需完成：Brain 的 `SubagentExecution` 注入、冻结子定义与资源边界的可信物化、原始
agent_spawn / agent_send 的恢复、持久子会话多 Turn、子 Session 生命周期联动，以及实际
Harness 父子推理 / 挂起 / Resume / 取消和多副本容量验收。当前 Catalog 会拒绝将根的
密封定义直接当作子 Session 定义；尚未放宽这一隔离检查。旧 Runtime 子调用实现继续
等待完整原生迁移后删除，不能将本节测试当作其替代已经完成。

本阶段没有修改 Core / Harness，代码仍在用户指定的 agentscope-3 主目录和当前分支，
未提交、推送或更新用户已有运行服务。此前全仓 clean verify 通过记录不覆盖本节新增
Java 代码；原总计划、剩余故障矩阵、正式部署和最终全仓检查保持未完成。

## Brain 子执行桥接与实际父子 Harness 闭环（2026-10-15）

- Brain 已向每次原生执行注入 `ManagedSubagentExecution`。Harness 的本机子实例只提供
  有效配置，实际推理仍由普通子 Session / Turn 驱动；不执行传入的本机 invocation。
  非持久子身份来自原始父 Turn / tool-call，持久子身份来自父 Scope / Agent / label。
  `Specification` 显式携带子 Session ID，持久子会话追加新 Turn，按具体子 Turn 追溯父委派。
- `ManagedSubagentDefinition` 冻结有效系统提示、声明模型引用、生成选项、工具 / Skill
  集合及原始父权限 / 计划状态，不序列化 Model、工具回调或连接。内容限制为每个 prompt /
  定义最多 1 MiB，避免错误地套用 1024 字符标识符限制；MQ 大载荷仍通过私有 blob 交付。
  无可序列化引用的自定义子模型会明确拒绝，不回退到另一个模型。
- Catalog 通过持久委派链解析子配置，逐层验证根密封定义与子定义来源；复制密文但没有
  委派关联的新 Session 仍被拒绝。子 Session 保留原始资源和 Owner，移除父 AgentTask 的
  executionContext / 外部任务标记，不继承父业务任务的私有终态凭证。物化后工具集合
  与冻结集合核对；缺少原工具时拒绝执行，不静默换成另一套能力。
- `ManagedToolRecovery` 直接回读 agent_spawn / agent_send 的原始 ToolTask 和子结果，
  重建原始 header 与工具结果，不重新 spawn / send。原结果未确认时继续持久挂起。
  首个子 Turn 与后续持久子 Turn 的关联、不同定义冒用同一子 ID 的拒绝均已测试。
- Service 使用 `ManagedTaskRepository` 替代本机 WorkspaceTaskRepository，避免另起本机
  后台执行和 orphan sweep。普通 task_list / task_output / task_cancel / wait_async_results
  工具从 Managed Toolkit 移除；业务 MCP 的 task.* 不属于这一组。子执行始终由原生
  Turn / ToolTask / Resume 负责。模型给出的异步或较短等待提示由 Service 的 force-sync
  桥接覆盖，原生短等待后转为挂起；Service inline wait 配置限制为 1..30 秒，默认 5 秒。
- 实际父子测试发现框架前置中间件可能在 Core 打开首个 recorder 之前请求 Hands。
  `ManagedHarnessTurns` 现在先提交完整初始 checkpoint；已有日志却没有 checkpoint 时
  继续拒绝，不用空状态覆盖原历史。子权限 / 计划种子先进入该初始 checkpoint，再由 Core
  的原生状态恢复机制接管。没有放宽工具意图必须有恢复基线的约束。
- Harness 默认宿主 SPI 和行为增量见本页兼容性记录；本轮没有新增 Core 实现改动。

证据：`managed-subagents-native-combined-tests.log` reactor SUCCESS，Harness 36、Common 74、
Portal 23、Hands 5 项通过，共 **138 项通过、1 项 Go HTTP 条件用例跳过**。Common 包含
每用例独占 PostgreSQL 的 17 个子调用测试、真实生产迁移专项、Catalog 隔离测试、原始
spawn / send 结果恢复，以及实际父子 Harness 用例。该 Harness 用例只有一个 Brain 调用
线程和一个 Hands 槽位：父与子均恰好执行两次 epoch / 两次模型调用，子工具仅启动一次，
原始 Actor 和父拒绝规则进入子日志，最终公开流包含父结果。模型、消息传输和 Sandbox
工具是明确替身；它不等同于真实 RocketMQ / Redis 多副本子执行或 ACS 验收。

普通 Harness 工具 / 挂起恢复、现有 AgentSpawnTool 的权限 / 计划 / 取消 / 持久会话回归也在
同批通过。`managed-subagents-native-spotless.log` 范围格式检查通过；本轮文档 16 项单测、
严格构建和 broken-links 均通过，日志 `managed-subagents-doc-{tests,validate,links}.log`。
`managed-subagents-full-clean-verify.log` 的全仓检查已经启动，尚未记录最终结果；在该进程
结束前不修改 Java 或资源文件，不把此前全仓通过记录当作本轮通过。

仍待完成：正式服务双副本子执行、父 / 子进程退出与取消、完整持久 agent_send 推理、
子生命周期联动、workspaceMode 的完整共享 / 隔离映射、子原型资源释放，以及完整 Fork /
快照 / 故障矩阵和旧 Runtime 删除。主设计文档、整体目标和未勾选整体验收项保持不变。
代码仍在 agentscope-3 主目录的 codex/automatic-acr-release，未提交、推送或部署用户原有服务。

## 子执行真实双副本验收与取消缺陷（2026-10-15）

- 全仓 `managed-subagents-full-clean-verify.log` 首次失败于 Portal 测试的默认 H2 方言；
  该测试已显式使用 PostgreSQL 模式。修复后的全仓日志
  `/tmp/managed-subagents-full-clean-verify-fixed.log` 于 17:12:01 返回成功，耗时 17:28。
  这次全仓通过不覆盖下述后续取消修复。
- 第一套实际部署保留在 `/private/tmp/managed-mq-replicas-20261015-subagents-a`。
  `subagents-1792054050744816000.json` 保持失败：冻结的子工具包含本机后台工具
  `wait_async_results`，与生产 Managed 物化后的集合不一致。冻结定义现在也排除这组
  本机工具，物化继续严格核对工具集合；相关 49 项专项及服务打包通过。
- 第二套实际部署保留在 `/private/tmp/managed-mq-replicas-20261015-subagents-b`。
  每副本 Brain 通道和 Hands 物理工具并发均为 1；部署 JAR 已复制到专属目录并记录哈希，
  后续主目录构建不会替换正在运行的二进制。模型是显式替身，工具是实际本地子进程。
  `subagents-1792054537875594000.json` 通过：一个父 Turn、同一子 Session 的两个 Turn、
  4 次挂起 / 恢复、46 个 SSE 事件和各一次工具启动 / 完成。
  `subagents-1792055179664382000.json` 通过：挂起并 ACK 后同时替换两个 Brain，
  新进程恢复同一父 Turn 和持久子 Session，原工具副作用各一次，无旧 Session 镜像。
- 该部署的取消验收 `subagents-1792054757658156000.json` 保持失败。父子逻辑 Turn
  均取消，但实际子工具自然结束，出现 start / done；不能将逻辑状态当作物理停止证明。
  已定位到远端占用任务扫描饥饿，以及父子控制任务与长工具共用容量的问题。
- 当前修复将同一 Hands 内的子调用控制与物理工具分成两个有界调度池；任务类型先过滤再
  应用扫描上限。未取得远端任务令牌也更新调度顺序，不消耗首次启动状态。工具和 Brain
  通过持久的具体父子 Turn 链直接读取祖先取消，避免依赖中间控制任务获得槽位。
  专项回归正在执行，尚不记为通过；实际取消必须重新验证进程停止且没有 done 副作用。
- 首轮取消修复专项 `/tmp/managed-child-cancel-focused.log` 有两项失败：H2 和 PostgreSQL
  同一测试发现子取消 Input 被祖先可见性检查跳过。已挂起子 Turn 仍需本轮 Input 触发
  Resume，因此保留独立持久取消投递，只在子 Turn 终态时跳过；原父调用幂等键去重。
  单槽位父子实际 Harness 完成 / 取消两种用例均在该轮通过，完整专项正在重跑。

两套专属部署的进程均已停止；数据库、日志和失败证据保留。基础设施容器和用户原有
180xx 服务未停止或替换。完整旧 Runtime 删除、快照 / Fork、故障矩阵和正式整套部署
继续保持未完成。

修复后证据：`/tmp/managed-child-cancel-focused-fixed.log` 的 **64 项专项全部通过**，
包括 H2 / 真实 PostgreSQL 各 21 项子调用测试、单物理槽位的父子实际 Harness 完成与取消、
恢复 / 迁移 / 生命周期 / Worker / Portal 及模型替身回归；三个服务打包成功。
`/tmp/managed-child-cancel-focused.log` 的两项失败记录继续保留。

新部署 `/private/tmp/managed-mq-replicas-20261015-subagents-c` 的三个部署 JAR 哈希已核对：

- `subagents-1792056747739861000.json` 通过实际取消。父子各一个原 Turn、两个挂起 / Resume，
  原子工具和父子协调任务均明确 cancelled；副作用文件只有一次 start，没有 done。
- `subagents-1792056802395978000.json` 通过持久子会话的两个 Turn，并在首次工具执行期间
  同时替换两个 Brain（96500 / 96699 → 1925 / 2278）。父原 Turn 不变、四次挂起 / Resume，
  原始 Actor、SSE 游标和一次性副作用均通过，无旧 Session 镜像。
- 七个专属进程已停止并逐 PID 检查；数据库、日志、失败和通过记录全部保留。这些结果
  使用先前的 LiteSimple / 挂起 ACK 协议，只是新传输改造的业务回归基线。

## 当前设计重新确认与 LitePush 迁移入口（2026-10-15）

用户明确选择以当前主设计文档为准：统一 LitePushConsumer、父 Topic 全量 wildcard / FIFO
订阅、独立工具事件 Topic、挂起保留原 Turn 回调、Hands 终态可靠通知后由 SDK ACK，
Resume 在本次交接事务后完成自身回调；SDK / Proxy 自动续期上限 3 小时。
本页顶部目标及决策已据此更新。之前的通过记录保留，不能等同于新目标已交付；正常
T2 的 DB defer 扫描、Session 目录订阅轮换、逐投递 MQ 续期及跨进程 receipt ACK 都需删除。

先验证所选真实 Broker / Proxy / SDK 的原生能力，不通过动态订阅或退回 SimpleConsumer
掩盖失败。新增 `RocketMqLitePushIntegrationTest`：消息先于消费者启动、新 Session 后创建、
同一 Session 的后续消息保持阻塞、其他 Session 在原回调持有期间仍能消费。Proxy 夹具
显式设置 `enableProxyAutoRenew=true`、`renewMaxTimeMillis=10800000`，已通过实际 5.5.1
ProxyConfig 的公开访问器核对字段。SDK 5.2.2 随附源码确认 Push 请求自动续期及按 LiteTopic
分组的 FIFO 加速器；长持有专项尚待执行，不以源码阅读替代真实通过。

真实能力门槛已通过：`/tmp/managed-lite-push-probe-a.log` 于 17:45:49 返回成功，实际执行
`RocketMqLitePushIntegrationTest` 1 项、无跳过。使用独占的单物理队列父 Topic
`ma_push_probe_20261015a` 与 FIFO / Shared / wildcard 消费组
`ma_push_probe_group_20261015a`，配置成功记录分别保存在
`/tmp/managed-lite-push-{topic,group}-a.log`。消息早于消费者启动和新 Session 自动交付均通过，
原回调持有 70 秒期间同 Session 后继不执行、其他 Session 可推进，返回 SUCCESS 后才
执行后继。没有 subscribeLite、DB 目录、显式 receipt ACK 或应用 MQ 续期循环。
Proxy 实际启动日志确认 `defaultInvisibleTimeMills=60000`、自动续期已开启及上限 10800000。
本次没有实测整整 3 小时的到期 / 接管，也没有将生产适配器切成 Push；这些继续待实现。

## 独立工具事件 Topic 与生产角色边界（2026-10-15）

生产配置、运行组件及专属多副本夹具已接入第四条 `ma_tool_events_v3` 通道。
`ManagedRuntimeWriter` 只写 Brain Runtime；`ManagedToolExecutions` 和 self_hosted
`ManagedWorkerService` 的最终结果只写 Tool。Hands 使用独立的 Runtime / Tool 消费组和
有界事件池，复用原子日志 / 结果提交与 Redis 结果信箱。当前这一步只接通最终结果；
工具 delta / 进度的来源序号、存储及投影仍待补齐，不能按 Topic 存在推断流式工具已完成。

`RocketMqManagedMessageBus.Settings` 要求四个父 Topic 名称互异，生产者显式限制发布职责：
Portal → Turns，Brain → Runtime，Hands → Tool / Resume。错职责发布在 SDK send 前拒绝；
Hands 在进入事务前检查通道与 Envelope kind，拒绝 Runtime Topic 上的原始工具结果，以及
Tool Topic 上的 Brain 批次。Runtime 边界也不再接受任何事件通道的消费交接身份。
这些是 Service 内部协议调整，没有修改 Core / Harness。

Compose、Helm、MQ 初始化脚本、实际副本启动参数和中英文 Kubernetes / API 文档同步
增加工具父 Topic / 消费组；Helm 独立暴露工具事件池和子控制池容量。生产消费传输仍是
旧 LiteSimple，不以本节结果声称原 Turn 持有 / LitePush 完成通知主链已经改造。

证据：

- `/tmp/managed-tool-topic-tests-package.log` reactor SUCCESS；310 项执行记录中 **213 项通过、
  97 项条件跳过**。其中跨通道拒绝、发布职责 / Topic 名称隔离、父子 Harness、结果恢复、
  self_hosted Worker 等均通过，三个服务打包完成。本轮未开启外部条件的用例不计真实验收。
- 范围 Spotless、脚本语法 / Python AST、Helm lint 和 `git diff --check` 通过。中英文文档
  16 项单测、严格构建及 broken-links 通过，日志 `/tmp/managed-tool-topic-doc-*.log`。
- 新真实部署 `/private/tmp/managed-mq-replicas-20261015-tool-topics-d` 使用四个独立父 Topic，
  每副本物理工具并发 1。三个部署 JAR 哈希已核对；同时运行持久子会话发送和长工具取消。
  `subagents-1792058505862596000.json` 通过同一子 Session 两个 Turn、4 次挂起 / Resume、
  SSE 和单次副作用；`subagents-1792058511798939000.json` 通过父子及实际工具取消、
  2 次挂起 / Resume、只有启动没有完成副作用。两者均无旧 Session 镜像。
- 传统 `mqadmin consumerProgress` 对该 Lite 消费组查找 `%RETRY%` 路由失败，即使进程返回
  0 也不能作为进度证据；加父 Topic 的重试同样失败。日志
  `/tmp/managed-tool-topic-broker-progress-d{,-parent}.log` 保留，不算通过。上述业务通过依据
  HTTP / SSE、持久事实和真实子进程副作用，不依赖该传统统计命令。
- 七个专属进程均已停止并逐 PID 检查。数据库、日志及证据保留，基础设施容器与用户原有
  服务未停止。最新增量没有执行全仓 clean verify，最终统一复验仍待完成。

下一步是同一轮替换 SDK 消费回调、可靠完成通知、活动 Guard 与原 Turn 持有关系，删除
Simple receive / 逐投递 MQ 续期 / 跨进程 receipt ACK / 订阅目录轮换 / 正常 T2 defer。
不保留旧协议作为生产降级或双实现；现有通过记录只作为业务回归基线。

## LitePush 主链切换与验证中的缺口（2026-10-15）

生产消息接口已切为 Push 订阅与回调，删除 Simple receive、应用 MQ 续期、导出 SDK receipt、
远程 ACK 分发和 Session 目录订阅轮换。Brain 的 Redis 执行租约在本次 Harness 交接时释放，
原 Turn 回调继续等待；Resume 使用独立回调，在自身交接持久化后返回。Hands 在日志、
检查点及状态的同一事务内写入回调完成 outbox，通过 Redis 私有逻辑信箱通知当前消费者。
回调身份仅标识业务投递，不包含 SDK receipt；完成通知回执不宣称 Broker ACK 已落地。
新增回调表、删除旧 ACK 表及列，采用新数据库与 MQ 命名空间验收，不做旧数据兼容。

- `/tmp/managed-push-adapter-integration-tests.log`：切主链前的适配器与完成信箱共 25 项通过，
  无条件跳过；真实 Push 回调持有 70 秒、同 Session 阻塞与其他 Session 推进通过。
- `/tmp/managed-push-cutover-main-compile.log` 主源码编译成功。
- `/tmp/managed-push-cutover-main-package.log` 首次打包因测试源码格式检查失败，未算通过；
  修复后 `/tmp/managed-push-cutover-main-package-fixed.log` 于 19:03:15 reactor SUCCESS，
  三个服务已打包。此命令显式跳过测试编译和执行，不代表当前测试迁移已完成。
- 旧 Simple 协议测试仍需迁移；新原 Turn 持有、独立 Resume 与工具事件的多副本闭环待实测。
  回调容量饱和、尚未启动 Turn 的取消、过期回调恢复等场景也尚未验收。


### 新 Push 主链的真实多副本证据（2026-10-15）

新部署 `/private/tmp/managed-mq-replicas-20261015-push-e` 使用独立数据库
`ma_replicas_28119e4ef45f`、四个独立父 Topic 和 FIFO / Shared / wildcard 消费组。
部署的是本节主链切换后打包的冻结 JAR，哈希保存在该目录 manifest；每副本活动 Harness /
物理工具并发为 1，Turn 与 Resume 回调使用独立池。模型为显式确定性测试模型，Sandbox 为
真实本地子进程，没有使用真实 ACS 或模型供应商。

- `subagents-1792062489643555000.json`：同一子 Session 两个 Turn、父 Turn 多次挂起 /
  Resume、原始 Actor、SSE 游标和工具单次副作用通过。
- `subagents-1792062655241154000.json`：父子长工具取消通过；子进程只有一次 start，没有 done，
  父子与 ToolTask 均 cancelled，取消 Input 为 applied。
- `smoke-1792062655.json`：同 Session 两个 Turn 的完整生命周期顺序、长工具挂起 / Resume、
  模型消费工具结果及一次性副作用通过。前一次 smoke 最后查询误用已删除的 ACK 字段，
  `/tmp/managed-push-smoke-e.log` 保留失败；修正为持久交接事实后重新执行，
  `/tmp/managed-push-smoke-fixed-e.log` 通过。没有把通知回执当成 Broker ACK 事实。
- `subagents-1792062785639174000.json`：父子均挂起且工具正在运行时同时 SIGKILL 两个 Brain，
  重启后恢复同一父 Turn / 子 Session，继续子会话第二个 Turn、SSE 和一次性副作用通过。
- 七个测试专属进程均已停止并按 manifest PID 核对。首次 10 秒退出检查过早失败，实际
  graceful shutdown 用时约 40 秒；后续逐 PID 检查通过。基础设施与用户原服务保留。

这些证据覆盖新原 Turn 持有 / Resume 主链，不覆盖 3 小时到期、回调饱和或所有故障矩阵。
之后源码增加精确回调重放查询，避免旧通知积压遮蔽本次完成，以及保留 Harness 原始异常类型；
这两项尚待新测试覆盖，不以冻结 JAR 的上述通过替代它们的验证。旧测试正在改为 Push 回调接线，
当前全量测试编译与最终统一回归尚未通过。


### Push 测试迁移及框架操作恢复缺口（2026-10-15）

删除了已退役的远程 ACK 分发 / receipt 注册实现对应测试，替换为回调完成事务与 Redis 信箱
测试。其他测试已改用 Push 订阅；测试专用 Probe 仅控制真实回调的成功或失败返回，不恢复
生产 receive / renew / ACK API。新增 Pipeline 验证终态积压时释放 Harness 槽位、原回调继续
持有顺序位置、独立 Resume 完成后放行 T2、MQ 接收后的本地原生日志投影及事件重放幂等。

- `/tmp/managed-push-test-migration-compile-3.log`：Portal、Dataplane、Hands、Scheduler 及依赖
  的主源码 / 测试源码编译均成功。此前编译失败记录保留，不算测试通过。
- `/tmp/managed-push-migrated-behavior-tests.log`：104 项执行，101 通过、3 项 Harness 失败、
  无跳过。通知 H2 / PostgreSQL 各 10 项、真实 Redis 4 项、Pipeline 5 项、父子 Harness
  完成 / 取消、工具恢复及失败决定恢复均通过。
- 测试计时改为包含异步执行时间，但保留原整项测试 30 秒上限；诊断保留调用次数、待处理
  任务及最近事件类型，不输出大检查点正文。维护任务改按测试命令 `do-work` 与真实业务
  参数区分，工具驱动改为生产 ManagedToolLoop，不再依赖内部框架调用 ID 的命名。
- `/tmp/managed-push-harness-production-loop-tests.log`：3 项中普通等待的两个用例通过，
  150ms 短等待用例仍失败。它在模型调用前反复创建 framework 操作、挂起与恢复，原生事件
  反复出现 run/start → input/received → turn/failed → run/end，模型调用次数为 0。
  不是已通过的两个用例证明整体成功，也不通过延长等待、关闭维护钩子或跳过用例掩盖失败。
- 当前判断：framework 操作用随机 RuntimeContext runId 生成身份，恢复会重新进入尚未完成的
  初始化阶段，产生新的操作身份；框架 pre/post hook 的 continuation 必须完善。稳定业务
  ToolUseBlock 的工具恢复已通过。对非确定性写入不能简单用参数哈希或重跑整个 hook 代替恢复。
  这是后续首要修复项，尚未改动 Core / Harness 的公共 API 或状态格式。
- 取消检查增加根 Turn 快速查询；有精确 child Scope / Turn 关联时才递归祖先。H2 的根 / 子
  取消语义回归继续通过；该优化不能替代上述框架 continuation 的修复。
- 范围 Spotless、Python AST、脚本语法、Helm lint、git diff --check 通过；文档 16 项单测与
  严格构建通过。最新真实 MQ 消费失败重试 / 180 秒 Resume 协议测试正在执行，未先记为通过。


### 新协议 180 秒真实基础设施回归（2026-10-15）

`/tmp/managed-push-real-transport-protocol-tests.log` 于 19:46:06 reactor SUCCESS，2 项全部
通过、无跳过，使用真实 RocketMQ 5.5.1 Cluster Proxy、Redis 和每次新建的 PostgreSQL 数据库：

- RocketMqManagedMessageBusIntegrationTest（33.87 秒）：实际生产消息总线的 Push 回调失败后
  重试同一个 Session 队首，其他 Session 可推进；回调成功后才消费同 Session 后继。
- ManagedRealProtocolIntegrationTest（225.3 秒）：180 秒显式 mock 外部工具，原 Brain 在
  waiting_tool 后关闭、未成功返回原回调；替代 Brain 持有原投递，通过独立 Resume 取得
  新代次，提交 T1 终态并接收私有通知，SDK 正常放行 T2。工具 starts=1，执行序列严格为
  T1:1 → T1:2 → T2:3。当前原回调完成通知被接收；旧消费者的 REPLACED 待投递记录不被
  当成 Broker ACK，也不要求已退出的消费者回收不存在的 SDK receipt。

测试资源已随 try-with-resources 关闭，专属临时数据库已不存在。它是协议 runner / mock 工具
验证，不等同于实际模型或 ACS；实际本地子进程 / 多副本证据见 push-e。短等待框架操作恢复
仍保留失败回归。最新 Hands / Dataplane 测试接线验证正在执行，最终全仓构建尚未复验。


最新接线回归 `/tmp/managed-push-service-wiring-tests.log` 于 19:47:28 reactor SUCCESS：
ManagedWorkerServiceTest、ManagedWorkspaceWorkersTest、DataAppContextLoadTest 共 3 项通过，
无跳过，包含 self_hosted 结果提交的实际 Push 订阅接线和 Dataplane 完成信箱 Bean 替换。
最终部署说明补齐四 Topic、FIFO / Shared / wildcard、Proxy 自动续期参数和独立容量；
`/tmp/managed-push-doc-final-validate.log` 对更新后的中英文文档再次严格构建通过。
本轮新增内容仅在 Service 与文档；Core / Harness 的既有兼容性记录不变。


### 框架恢复、收尾与输入回执修复（2026-10-15）

- Service 不再以随机 runId 给 framework 操作分组，改用持久模型位置及固定收尾身份。
  新增 ma.turn.model_anchor；使用新 schema / 新数据库验证，不兼容旧测试数据库。
- 收尾冻结入口状态及时间，恢复跳过主推理；已完成的辅助模型响应从原生日志重放。
  Harness 编译系统消息保留的原始消息也会重新生成 id/timestamp，指纹只归一化这些封套身份，
  不忽略正文、业务 metadata 或生成配置变化。暂存调试输出已经删除，生产代码不输出提示词。
- 输入应用先过滤本次执行已结算集合，避免 Hands 滞后时重复回执。Core 的新增原子记录方法
  覆盖 unchanged / changed state，两者均只携带一个 checkpoint，不通过加大缓冲绕过大状态问题。
- Brain 结束时不再循环查询 toolsUnsettled；仍有本地未决工具则挂起，Hands 的结果提交触发
  Resume。终态提交端继续独立校验真实工具已结算。父子用例校验 epoch = 1 + 持久 Resume 数，
  并保留父 / 子各 2 次模型调用及一次外部执行断言，不假定只有一次挂起。
- 前三次验证的失败日志保留：entry / canonical 定位封套身份漂移，input-handoff 定位重复
  大 checkpoint 超出缓冲。新增测试初次因 SessionRecorder 非 AutoCloseable 编译失败，也不算通过。
- `/tmp/managed-finalization-atomic-tests-fixed.log` 于 21:23:26 reactor SUCCESS：Core 7、
  Harness 19、Service 18，共 44 项通过、无跳过。Service 包含四个真实 Harness 回归：普通 / 2 MiB
  输入 / 150ms 短等待 / 每次恢复重建 Harness；均 3 次模型调用、一次业务执行、一次记忆写入，
  且正文与冻结时间一致，收尾起止各一次。transport、模型和外部工具为显式 Fake，不冒充真实 ACS。
- 全 Managed / Redis / RocketMQ、Portal / Dataplane / Hands 的扩大真实基础设施回归正在执行，
  入口库为新建 managed_push_20261015_finalization。最终全仓 clean verify 和剩余验收未完成。


扩大回归 `/tmp/managed-push-finalization-broad-real-tests.log` 于 21:32:30 reactor SUCCESS，
71 个测试类共 349 项：345 执行通过、4 项旧 ManagedRuntimePostgresAdmissionTest 因未提供其
独立环境变量而跳过。Native Managed 的真实 PostgreSQL / Redis / RocketMQ 用例实际执行，
包括 244.1 秒的 180 秒 mock 工具恢复、4 个实际 Harness 用例及 Portal / Dataplane / Hands 接线。
入口数据库 managed_push_20261015_finalization 为本次新建，不修改旧验证库。
前端 `/tmp/managed-push-frontend-{tests,lint,build}.log` 分别为 179 项通过、0 错误 / 98 警告、
构建成功；UI 产物由正常 Vite 构建生成到既有忽略目录，没有手工修改生成文件。
文档 `/tmp/managed-finalization-doc-{tests,validate,links}.log` 为 16 项、严格构建、无坏链通过。
全仓 `/tmp/managed-push-finalization-full-clean-verify.log` 已启动；完成前不修改 Java 或资源文件。


### 最新全仓、SDK 与浏览器验证（2026-10-15）

- `/tmp/managed-push-finalization-full-clean-verify.log` 于 21:51:39 BUILD SUCCESS，耗时 18:03，
  覆盖最新 Core / Harness / Service 源码、测试、Spotless 和打包。外部条件跳过仍按实际跳过记录，
  不由全仓成功推断故障或供应商验收完成。
- Go make build / make test 均通过，Python SDK 116 项全部通过；日志为
  `/tmp/managed-push-finalization-go-{build,tests}.log` 及 `...-python-sdk-tests.log`。
- 浏览器首次 7 项全部失败：Native Session 夹具使用旧路径、旧 snake/camel 字段及快照 / 回执结构。
  迁移后 4 项通过、3 项失败；其中事件导出实际生产代码遗漏，仍访问旧路径。现已将 Native
  导出修正为 /v1/sessions，单测同步验证；另外两项修正控件范围和当前 checkpoint operation 合约。
  没有为通过测试给生产 API 增加旧协议兼容层，Hosted / External 的 ServiceClient 路径保持原样。
- `/tmp/managed-push-finalization-frontend-e2e-final.log` 7 项全部通过，15.8 秒。它们是浏览器与
  HTTP 夹具的验收，不能等同真实后端闭环。最终前端 179 项、lint 0 错误 / 98 警告、构建均通过；
  `...-frontend-{tests,lint,build}-final.log` 保留。更新后 UI 已重新嵌入控制面构建。
- 新隔离部署 /private/tmp/managed-mq-replicas-20261015-push-finalization-f 使用新库
  ma_replicas_9a5da963c73e、四个新 Lite 父 Topic、六个普通业务 DB 账号及冻结最新 JAR。
  180 秒实际本地工具与同时替换两个 Brain 的复验正在运行；启动 READY 不记为验收通过。


最新 HTTP / MQ / Redis / PG / Hands 实际子进程验证：
`push-finalization-f/smoke-1792072900.json` 已通过 180 秒工具、T1 waiting_tool → Resume → completed、
T2 随后 completed、模型消费工具结果及一次 start / done 副作用。运行中同时 SIGKILL 两个 Brain，
按冻结 JAR 重建；`smoke-brain-replacement.json` 保存前后 PID、相同 ToolTask / executionId /
进程句柄。T1 epoch=2、T2 epoch=3；当前原回调及 Resume 完成信号均 received。
死亡消费者的一条旧 COMPLETED 通知仍 pending，但已非 current_delivery，不能把它当成
未确认的新回调或 Broker ACK 证明；其通知退休 / 保留策略仍待完善。

`push-finalization-f/parallel-1792073148.json` 也已通过：同一物理父队列下，长工具实际启动且
仍运行时，另一 Session 的完整短 Turn 提前完成；长工具随后正常完成。每副本物理工具并发 1，
事件处理与实际工具分别使用配置池。该结果不代表父子回调总容量饱和已验证。

`push-finalization-f/controls-1792073349.json` 通过：挂起期间跨 Portal 提交 inject / steer，
同幂等键跨副本重试复用输入；两条输入均 applied，模型看到原业务顺序；两个实际 SSE
订阅者及从另一 Portal 携带 Last-Event-ID 的重连，与全部持久事件逐项一致。
当前只把通用外部结果 reply 的独立用例继续留为未验收，不由 inject / steer 代替。

`push-finalization-f/cancellation-1792073432.json` 通过：挂起期间取消实际长工具，一次启动、
进程真实停止且没有完成副作用，原 Turn cancelled，队列中下一 Turn 正常完成。
本轮未触碰用户原有服务，隔离测试专用的七进程均已正常退出并按 manifest PID 核对；
`push-finalization-f/stopped.json` 保存检查结果，数据库、冻结 JAR 与日志保留。
后续继续实现本页“最新状态”和“完成审计”列出的剩余内容，不将本轮收尾当成总目标完成。


### 完整检查点对象与引用改造（2026-10-15，实施中）

- 新增 ManagedCheckpointObjects：以作用域及捕获事件身份固定对象 ID，先写入并持久确认完整
  私有文件，再生成携带 schema / scope / Turn / run / epoch / 源位置 / 字节数 / 摘要的 manifest。
  文件以原子 hard-link 创建，禁止覆盖已有对象；文件与父目录 fsync，路径禁止穿过内部符号链接。
  首版复用既有私有 RWX 卷，后端需支持这些文件操作，不增加独立存储服务或生产 Fake 降级。
- 私有对象保存原始 native payload（含完整 AgentState）和 continuation，恢复可以返回相同载荷字节。
  MQ Runtime 编码拒绝 stateJson，Hands 只接收引用；ma.native_event、ma.session 与 suspension
  改存引用。Core 日志适配器读取时才还原原生事件，Core / Harness 接口及状态格式本轮未改。
- continuation 包括固定 Session 配置 / 摘要、生命周期版本、模型位置、原工具参数及稳定任务身份、
  部分结果及 applied 标记、输入应用水位和集合。挂起引用标记其完整活动状态的 capturedFrom，
  不再从异常清理后的 SessionProjection.stateJson 拼出状态。
- Hands 在行锁之前验证对象完整性；挂起事务核对 manifest pending 与已登记 ToolTask。
  fork / 恢复基线也先写完整对象，再在锁内核对原生前缀及执行代次；验证扫描的文件 I/O 移到锁外。
- 发布身份缓存保存首次准备的不可变 manifest，避免原批次重放被后续内存状态改变。对象写入或
  MQ 发布失败使活动 Guard 失效，不能再用旧检查点当作安全挂起。原失败决策仍按持久事实恢复。
- managed SQL 的版本指纹已改变，以拒绝旧 inline 数据；所有夹具显式传入自己的检查点存储，
  不保留旧生产构造器或旧数据转换层。
- /tmp/managed-checkpoint-reference-compile.log 四服务及依赖主 / 测试源码编译通过。
  第一轮 52 项为 51 通过 / 1 个夹具 ID 冲突；后续 61 项为 60 通过 / 1 个故障注入预期异常类型
  需更新为失效 Guard 的 transport failure。对象损坏 / 缺失、并发写入、跨 Scope / run 误用、
  原生字节恢复及实际 Harness 已执行通过；最新增加锁外 I/O 和 DB 不含 stateJson 断言后正在复验。
  最终全仓、真实 PostgreSQL、多副本新格式验收均尚未完成，不将代码接通写成总目标完成。


检查点引用扩大回归 `/tmp/managed-checkpoint-broad-real-tests.log` 于 23:16:25 通过：
335 项中 331 项执行通过、4 项旧 PostgreSQL 条件跳过。使用新库
`managed_checkpoint_refs_20261015`、真实 Redis / RocketMQ / PostgreSQL，包含 180 秒工具协议；
对象流式摘要校验、终态新捕获、跨多次挂起保存已应用的工具结果均通过。
当前还未基于此版本重新部署双副本或执行全仓 clean verify。

### 迭代预算恢复补充（2026-10-15，验证中）

- 发现 Core ReAct 的普通 reasoning / acting 入口都从 0 开始；序列化 AgentState.curIter
  不代表当前循环真正恢复了迭代位置。同一 Managed Turn 多次重建 Harness 会重置 maxIters。
- Core 新增可选 AgentExecutionPosition 记录，由本次 RuntimeContext 提供 reasoning / acting
  入口位置；无绑定保持旧行为，不改变已有 builder、状态字段和事件载荷。这是本轮额外的
  Core 兼容性登记，不修改 Harness API，也不承诺重放任意中间件副作用或 Java 栈。
- Managed 从已提交的 step/start、message/assistant 维护迭代位置，和完整 checkpoint 一同
  捕获；启动重放 checkpoint 后的原生 manifest，避免为位置查询装载完整状态。已完成推理的
  工具返回后进入下一迭代，未产生回复的模型尝试仍使用原迭代；新的逻辑 Turn 从 0 开始。
- 增加 call / stream 的入口位置与达到上限测试；实际 Harness 的短等待 / 重建测试断言
  step 序列连续，并新增 maxIters=1 跨挂起后应失败的场景。结果以专项日志为准。


迭代预算专项最终结果：`/tmp/managed-iteration-continuation-verified.log` 于 23:33:04
BUILD SUCCESS，45 项通过、无跳过。含 Core 5 个执行位置用例、4 个收尾用例，以及 Service
36 个用例；实际 Harness 的 5 个参数场景包含大状态、短等待、重建和 maxIters=1。
新上限场景只发生一次业务工具副作用，返回后 summary 标记 MAX_ITERATIONS，Turn failed，
不执行成功路径的记忆刷新。已有正常场景的模型调用 / 记忆写入次数保持严格断言。
首轮测试的 Iterable.stream 编译错误和新失败场景沿用成功收尾断言已修正，未修改旧成功场景
的预期。新增可选 Core 接口只由 Managed 绑定，通用状态与事件 schema 不变。

当前 `/tmp/managed-checkpoint-iteration-full-clean-verify.log` 正在执行包含真实基础设施配置的
全仓 clean verify；最终结果尚未产生，不用此前 21:51 的构建替代。最新代码尚未冻结部署。
工作仍位于 /Users/ken/agentscope-3/agentscope-java，codex/automatic-acr-release，无新 worktree、
无提交 / 推送。本轮也同步修正主设计文档 6.6 的源码状态说明，保留尚未完成的发布窗口、
工具进度、旧代码清理、容量和故障矩阵等边界。


### 工具结果发布前 outbox：下一步实施决策

- 与终态消费后的 Redis 结果收件箱修复、Turn 完成通知 outbox 分开。Hands 获得真实终态后，
  先在 DB 按 scope / taskId / executionId 保存不可变完整结果和摘要，再立即发 Tool Topic。
  大载荷的 MQ 私有文件可从持久结果重新生成；不把传输临时文件当作唯一结果证据。
- outbox 不以 Broker send 返回成功作为业务已提交。Tool Event 消费在同一事务提交 ToolTask、
  原生 / 公开投影和 Resume，同时结清原 outbox。发送响应丢失、进程重启或消息尚未落库时，
  后台有界重发相同稳定结果身份，禁止再启动或查询原外部执行来重建已经暂存的结果。
- 独立发布重试只扫描到期 outbox，带重试间隔与公平排序，网络调用不持有 DB 行锁。
  正常路径是暂存后立即发送；不依赖此修复周期才能启动 Brain 的下一步。
- self_hosted Worker 回传同样先暂存，再发 MQ；相同身份但不同结果必须拒绝。已有 Worker
  等待提交的有界响应契约保留，不能在尚未投影时宣称 committed。
- 待验证：MQ 发送前失败、Broker 已接收但 send 响应丢失、暂存后重建发布器、重复 / 冲突结果、
  大结果传输文件丢失重建，以及 DB 结果提交与 outbox 完成标记的事务一致性。

双副本 smoke 脚本新增部署状态核对（尚待新冻结 JAR 执行）：DB 原生事件不得内联 stateJson；
逐项检查引用对应的共享私有文件、字节数 / 摘要、作用域 / Turn / run / sequence、完整原生状态、
宿主资源与迭代位置，并核对挂起和终态均有独立有效捕获。Python 语法检查已通过。


### 工具结果 outbox 实施与首轮验证（2026-10-15）

- 已增加 ma.tool_result_outbox：完整真实结果、稳定执行身份、摘要与到期重试位置持久保存。
  暂存事务锁定作用域并将 ToolTask 置为 result_pending；迟到 started / claim 不能重新打开执行。
  相同任务的不同结果拒绝，未经暂存的 Tool Event 也拒绝。
- ManagedToolResultPublisher 在暂存后立即发送；有界后台修复只重发原持久结果，网络与文件 I/O
  不在 DB 行锁内。Broker send 成功仍保留 outbox，Tool 消费的日志 / 结果 / 投影 / Resume
  事务同时写 committed_at。大结果传输文件丢失时从 DB 原结果重新生成。
- 平台工具执行和 self_hosted Worker 回传均已切换；已暂存的结果优先于任何外部查询 / 再执行。
  不引入新的 Runtime 租约，也不改变本轮之前的 Core / Harness 接口。
- /tmp/managed-tool-outbox-second-tests.log 于 23:56:26 BUILD SUCCESS，
  37 项中 35 项执行通过、2 项条件跳过。
  包括发送前失败、Broker 接收后响应丢失、发布器重建不查询原执行、结果冲突 / 未暂存拒绝、
  DB 回滚保持 outbox 未结清、大结果文件重建，以及 Worker / Workspace 接线。
  未配置真实 Redis 的两个条件用例没有计为真实验收，后续在全仓配置和冻结部署中复验。
- 第一轮 outbox 测试仅一个旧取消夹具仍预期 starting，已改为真实暂存状态 result_pending；
  外部启动次数仍严格断言为 0。最新代码待全仓与部署验收。

上一轮全仓 /tmp/managed-checkpoint-iteration-full-clean-verify.log 于 23:46:55 停在 Portal：
3 个旧契约夹具绕过对象暂存，违反引用协议。已显式 prepare 并以合法 AgentState 替换伪状态；
/tmp/managed-portal-checkpoint-contract.log 于 23:49:15 通过，24 项中 23 项执行通过、1 项外部
Go 夹具跳过。此前全仓的 Core / Harness / Common 通过，包括真实 180 秒协议 243.6 秒通过，
但本页仍不将该中途失败的全仓运行计为 clean verify 成功。


旧链路只读复核：当前仍有 Runtime* 组件及 Scheduled 任务注册。外部引用集中在
ControlPlaneClient、DataPlaneConfig、SessionNativeLogService、SessionTurnRunner、SessionBudgetService
和旧内部文件 / trace / budget / checkpoint 控制器。ManagedRoutingSessionLogStore 仍保留
RuntimeSqlSessionLogStore 后端，且后者关联 builder_runtime_configuration 旧表；不能只删除
调度 bean 就宣称已完成破坏性清理。下一步需要逐调用方核对并删除旧 Managed 分支、表和测试，
其他执行路径的共享用途应明确处理，不凭类名前缀判断可保留。


2026-10-16 增量：真实配置全仓 /tmp/managed-tool-outbox-full-clean-verify.log 在 Common
因 1 个管线夹具直接发 Tool Event、未暂存而失败（311 项：1 fail / 4 skip）；其中真实长工具
协议已以新 outbox 通过 256.6 秒测试，两个 Redis 结果投递用例实际执行通过。
已将该夹具改为 ManagedToolResultPublisher，保留 FIFO / 挂起 / Resume 的原严格断言，
不削弱“未暂存禁止提交”的生产守卫。

/tmp/managed-tool-outbox-wiring-tests.log 于 00:13:22 BUILD SUCCESS，39 项中
38 项执行通过、1 项外部条件跳过；覆盖实际 Harness（含大状态与上限）、Subagent、
管线 6 场景、Portal 完整契约、Worker 与 Dataplane Spring 接线。当前正在打包三个服务；
待隔离双副本复验和再次完整 clean verify，不把中途失败的全仓运行算作通过。


### 最新冻结部署检查点 / outbox 验收（2026-10-16）

/private/tmp/managed-mq-replicas-20261016-checkpoint-outbox-g 已启动，使用新库
ma_replicas_d14d18cddefb、四个新 Lite 父 Topic、六个普通业务 DB 角色及 00:15 打包的冻结 JAR。
全仓重新复验使用另一个新库 managed_outbox_20261016_v2，日志
/tmp/managed-tool-outbox-final-clean-verify.log，当前仍在运行，最终结果未产生。

smoke-1792081097.json 已通过真实 180 秒本地子进程、同 Session T1 → T2、模型拿到原工具结果、
一次 start / done 副作用；smoke-brain-replacement.json 证明运行期间 SIGKILL 两个 Brain 后按
冻结 JAR 重建，原业务 ToolTask / executionId / 进程句柄始终相同。模型为显式 Fake，并非 ACS。
新增部署检查验证 13 个实际私有捕获、2 个独立终态捕获、共享文件的字节数 / 摘要、
作用域 / Turn / run / nativeSequence / epoch、完整原生状态及宿主 continuation，DB 不内联
AgentState，挂起与 Session 头引用有效，结果 outbox 未结清数量为 0。

首次故障注入观测器错误地将框架初始化 sandbox.exec 也计入业务工具数量，未执行注入就报错；
没有重启原工具或另起一次 smoke。改按原 native business call 定位后，在同一次仍运行的实际
工具上完成双 Brain 替换。/tmp/managed-checkpoint-outbox-g-replacement.log 与最终两个 passed
JSON 是通过证据，首次观测器进程的非零退出不改写为成功。

并行 Session、跨 Portal 控制 / SSE、取消的最新复验正在继续；七个测试专用进程尚未清理。


最新冻结 g 部署补充验收均通过：parallel-1792081454.json 证明长工具占用物理并发 1 时另一
Session 仍可完整完成；controls-1792081588.json 证明跨 Portal inject / steer 幂等、按序应用、
两个 SSE 订阅者和跨副本 Last-Event-ID 重连；cancellation-1792081611.json 证明真实进程停止、
无 done 副作用、原 Turn cancelled 后队列下一 Turn 成功。

checkpoint-audit.json 对五个实际 Session 的全部 52 个私有捕获与 8 个终态捕获逐个校验通过，
包括共享文件 / 源身份 / 原生状态 / continuation / 挂起和 Session 头引用。所有工具结果 outbox
均已结清，当前回调待完成通知为 0；一条 obsolete 回调通知仍 pending，其退休策略仍未实现，
不能把它混同为当前 SDK ACK 或已完成全目标。

全仓最新运行的真实长工具协议再次通过 256.7 秒，Common 已进入打包阶段，仍待后续模块和
最终 BUILD SUCCESS。正在按 manifest 停止本轮七个专用进程，保留 DB、冻结 JAR、私有对象
和日志；未停止 Redis / MQ / PostgreSQL，也未触碰用户原有服务。


冻结 g 部署已停止并按 manifest 逐 PID 核对，stopped.json 证明七个专用进程均退出。
checkpoint-iteration-audit.json 另从实际原生日志证明：经过双 Brain 替换的 T1 为 step [0,1]，
随后 T2 从 [0] 重新开始。smoke 工具中增加对应持久断言，Python 语法检查通过。
最新全仓 Service 模块均通过，当前仍在其余扩展模块验证（进程 26939 / exec session 89252，
/tmp/managed-tool-outbox-final-clean-verify.log）；没有 BUILD SUCCESS 前不记整仓通过。
总目标继续保持未完成：下一阶段仍包括工具 delta / progress 与统一投影、旧 Runtime 主图删除、
过期回调通知退休、父子容量、剩余故障矩阵与正式整套容器部署 / 客户端验收。


### 本阶段全仓最终结果与运行产物等价证明（2026-10-16 00:41:45）

/tmp/managed-tool-outbox-final-clean-verify.log BUILD SUCCESS，用时 24:33。
按各模块测试汇总统计 11003 项、0 failures、0 errors、186 条件跳过；
不能把条件跳过计为真实通过。Service、Core / Harness、全部其余 Java 模块的检查均完成，
本阶段 Java 完整检查门已通过，后续源码改造仍需重新验证。

冻结 g 的 verified-build-equivalence.json 核对所有主业务类 / 资源和内部依赖的类 / SQL /
YAML / JSON，与最终 clean verify 产物完全一致：Portal 1098、Brain 1687、Hands 992 项。
只排除构建时间元数据等非运行条目，不以整 JAR 时间戳差异判断源码不同。
因此本轮 HTTP / MQ / Redis / PostgreSQL / 本地实际工具验收对应当前已验证的运行代码。

/tmp/managed-tool-outbox-final-doc-{tests,validate,links}.log 证明文档 16 项、严格构建和链接检查
再次通过。source 位于 /Users/ken/agentscope-3/agentscope-java、codex/automatic-acr-release；
没有创建 worktree、提交或推送。本阶段完成不等于总计划完成，剩余项保持本页最新状态所列。


### 旧运行图删除与新边界（2026-10-16，复验中）

工作目录 /Users/ken/agentscope-3/agentscope-java，codex/automatic-acr-release；未创建副本、
worktree、提交或推送。/tmp/managed-legacy-removal-manifest.json 记录精确删除清单。
新增 ManagedModelPolicy 和永久预算拒绝异常；实际 Harness 回归证明达到预算时模型调用次数
不增加、budget.exceeded 经 MQ 提交、Turn failed，更新预算后下一 Turn succeeded。
首次回归夹具误用禁止的零预算，已改为先耗尽一个正预算，不放宽配置校验。

ToolConfirmationMiddleware 直接使用 ManagedCatalog，去掉 DataSessionService / 旧协调器依赖。
全部待确认调用和完整 ASKING 状态在一次日志批次中提交，恢复立即得到 ASKING；通过原生输入
继续，不丢失 JEV 确认跟踪。保留拒绝、无配置时拒绝、任务专属工具与允许调用的混合处理。

ControlPlaneClient 仅保留原生固定配置和资源请求，不再建立物理 Scope / Attempt 租约、登记
同步 Agent、bootstrap 或跨实例路由。Harness 构建不再装配环境 Sandbox / 外部执行 schemas；
只保留 MemoryStore route，实际 FS / 工具仍由新 Hands 接口执行。

107 个旧主类与控制器删除，12 个旧持久类型已证明没有剩余 main 引用后删除。旧测试 43 个
退休（本地安全测试另行重写）。Dataplane 资源 YAML / Compose 删除已失效连接池、转发池等
参数；dp schema 从初始脚本剔除所有 builder_runtime_* / 旧 Managed 会话调度表，删除 V2–V6
和单独旧初始化 / 手工迁移 SQL，ServiceSchema 只认新的 V1 校验和。

/tmp/managed-legacy-removal-second-tests.log 通过干净源码编译后的接线复验；
/tmp/managed-legacy-schema-tests.log 于 01:35:59 BUILD SUCCESS，真实 PostgreSQL 新建 / 清理
隔离数据库并证明旧运行表数量为 0、旧 V2 版本被拒绝，保留通用资源 / 状态与事件表。
Portal Hosted / External 的代码和迁移没有删除，后续仍需核对 native Managed 是否存在无用元数据。
新的全仓与冻结部署结果尚未产生，不用删除前的 g 产物宣称当前已验证。


### 旧运行图删除后复验启动（2026-10-16 01:44）

总体完成度按全部目标粗略估算为 80%，不按已通过测试数量折算完成率。主链路已落地，
剩余为工具进度 / 统一投影、过期回调通知退休、故障 / 容量矩阵和最终客户端 / 部署验收。

/tmp/managed-legacy-cutover-format.log、/tmp/managed-legacy-cutover-package.log 均 BUILD SUCCESS；
后者于 01:41:10 完成。/tmp/managed-legacy-cutover-doc-{tests,validate,links}.log 证明文档
16 项、严格构建和链接检查通过。

新冻结部署 /private/tmp/managed-mq-replicas-20261016-legacy-cutover-h 已由三个最新部署 JAR
建立，数据库 ma_replicas_c336b4392544，六个普通业务 DB 角色、四个独立 Lite 父 Topic。
两个 Portal / Brain / Hands 和 Go Control 均已 ready，单副本实际推理 / 工具并发 1。
smoke 正在验证真实 180 秒本地工具及 T1 → T2；已在业务工具运行后 SIGKILL 两个 Brain
并重建，尚未产生最终验收结果。其后的 parallel / controls / cancellation 由
/tmp/managed-legacy-cutover-h-acceptance.py 顺序执行；日志
/tmp/managed-legacy-cutover-h-acceptance.log。模型明确为 Fake，本地工具不是 ACS。

/tmp/managed-legacy-cutover-full-clean-verify.log 使用独立新库
managed_legacy_cutover_20261016_v1 和真实 Redis / MQ / PostgreSQL 配置执行全仓 clean verify，
当前 exec session 49464，尚未返回最终结果；期间不修改 Java / 资源。验收控制器 exec
session 13608。h 的七个专用进程仍运行，后续须按 manifest 停止并逐 PID 证明退出。

新增待核实的接入缺口：Go sessionMemory 当前仍直接 loadSession 读取旧 cp.sessions，
需对原生 ma 会话的冻结 MemoryStore 绑定与访问接入做专项验证 / 修复，不能把新配置解析
成功等同于记忆文件读写可用。当前只完成只读审查，未改变 MemoryStore 接口行为。


### 旧执行图阶段最终结果及下一增量（2026-10-16 02:06）

/tmp/managed-legacy-cutover-full-clean-verify.log 在 02:06:37 BUILD SUCCESS，用时 22:25。
按模块汇总 8232 项、0 failures、0 errors、173 条件跳过，不把跳过计为真实通过。
该结果覆盖旧图删除后的 Java 源码与迁移集合，不覆盖之后的 MemoryStore / 通知退休增量。
Go make build 成功；首次真实 PG 全包测试发现三个既有测试夹具与新协议不符，已修复：
Quickstart 验证原生 Portal request 并保留真实 Actor；环境迁移按版本名选择 SQL；
resourceTestServer 每个测试创建 / 删除自己的数据库，不再污染共享 cp 数据和 outbox。
/tmp/managed-native-memory-go-all-tests-final.log 全包测试通过，包括真实 PG 的 native Memory
资源接口与旧后端访问回归。最初 delete 测试误期望 200，已改为既有契约 204。

冻结 h 的 smoke / parallel / controls / cancellation 四组均通过，双 Brain 已在同一条 180 秒
业务工具中替换，Task / execution / handle 未改变，一次 start / done。最初 smoke 进程由于
把最终 epoch 写死为 2 失败：本轮有额外接管，实际最终 epoch 3。没有再发工具，改用同一
Session 完整重审：挂起 epoch < 完成 epoch、终态私有捕获绑定最终 epoch、推理 step [0,1]
及后继 [0]、一次副作用、持久生命周期严格顺序均通过。原进程失败日志保留，
/tmp/managed-legacy-cutover-h-recheck.log 是重新审计和后三组验收的通过证据。

checkpoint-audit.json 证明五个 Session 的 52 个私有捕获、8 个终态捕获完整有效，旧 DP 执行表
0、cp.sessions 0、当前待通知 0、结果 outbox 0；旧 obsolete pending 1 仍待退休。
七个专用进程已按 manifest 停止，stopped.json 逐 PID 证明退出。

严格运行产物比较发现：Portal / Hands 与全仓构建一致；Brain 的 class 与使用中资源一致，
但冻结 h 的增量 package 仍夹带六个已删除的旧 SQL 文件，全仓 clean 产物已不含这些文件。
详情保存在 build-comparison.json；不能宣称 h 和当前 clean 产物全部字节 / 资源等价。
这些 SQL 不在新初始化迁移集合中，真实 DB 证明没有执行旧初始化。下一新冻结部署使用
clean 产物，保留本次比较失败的事实；不重新包装 h 来改写历史证据。

下一源码增量：Java Memory 客户端先校验封存 mount，再走 Go 内部资源 API；新接口不建立
第二份 Session 元数据，资源归属和当前只读规则仍检查。过期通知仅退休非当前 receiver，
最大 hold 三小时后额外保留一小时；保留 retired 与 received / SDK ACK 的语义差别。
Managed 初始化 SQL 因 registered_at 与退休索引改变校验和，后续验收使用新 DB，不迁移旧夹具。
/tmp/managed-native-memory-callback-tests.log 正在验证上述 Java 增量及真实 PG 回调退休。
本轮没有改 Core / Harness。总目标未完成，仍包括工具进度、父子容量、剩余故障和正式部署。


### Memory 客户端与回调退休专项（2026-10-16 02:18）

/tmp/managed-native-memory-callback-tests.log 中 Common 的 36 项全部通过，包括 H2 和真实 PG
各 12 项通知测试，以及 Pipeline / Failure 两组。首轮新 Java 客户端测试的本地 HTTP 响应
漏填 createdAt / updatedAt，WebClient 正确拒绝缺失 primitive 字段；补齐真实契约字段后，
/tmp/managed-native-memory-client-tests-fixed.log 5 项全部通过。未修改生产反序列化严格度。
覆盖全部四类资源操作、owner / Environment Header、嵌套路径、expectedVersion、未绑定或
错误身份不出网，以及 404 / 403 / 409 透传。

Go 新增检查 Environment 已归档时不可访问；实际 PG 的 native / 旧后端两组专项再次通过，
日志 /tmp/managed-native-memory-go-archive-tests.log。Go 四个二进制构建通过。
文档 /tmp/managed-native-memory-doc-{tests,validate,links}.log 的 16 项、严格构建、链接检查
均通过。下一冻结部署的 memory.py 将通过实际 Harness 长工具挂起恢复后验证 MemoryStore
写 / 读，以及挂起后动态改只读时拒绝写入；目前尚未声称该服务组合验收通过。
新测试模型仅存在 test fixture JAR，不进入生产发行包；Core / Harness 未修改。


### 新冻结 i 暴露的组合缺陷（2026-10-16 02:22）

clean package 在 02:19:21 BUILD SUCCESS；冻结 i 的 deployment-surface-audit.json 证明
107 个旧主源码对应 class 与六个旧 SQL 均未进入 Brain JAR。真实 Memory 会话验收失败，
原 JSON / 日志保留：挂载记忆后 execute 在工具表中消失，第一轮 failed，后继 Turn completed。
没有产生这条业务长工具的副作用，不能声称 i 的组合验收通过。

原因是 Service 用 filesystemRoute 挂载记忆，Harness 对自定义 filesystem + routes 包装
CompositeFilesystem，后者明确不提供 shell。修复限定在 Service：将记忆 routes 返回到
Managed 装配点，使用已有 RoutedSandboxFilesystem(ManagedRemoteFilesystem, routes)。
保持 Hands shell 和挂载路径路由，Core / Harness 未修改；增加实际 route / execute 委派回归。

审查同时确认现有 sharedKnowledgeAccess 将 Agent 共享记忆固定为 read_only。最初新夹具
错误假设 Agent 可以写共享记忆，已修正验收目标，不放宽该策略：外部资源 API 在挂起期间
更新文档，恢复后的 Agent 读到新版本，并保持写入拒绝。动态 Environment 只读 enforcement
由真实 PG 的 Go nativeMemory 测试单独证明；不能仅凭原本就只读的 Agent 挂载推断它。
memory.py 和文档已同步这一区别，i 失败证据不改写。

/tmp/managed-memory-routing-tests.log 正在验证 Service 装配修复；i 的专用进程已发停止请求，
需核实 stopped.json。之后必须用新冻结目录和新库验证修复，不能修改 i 冻结产物。


### 记忆路由专项与模型夹具修正（2026-10-16 02:34）

/tmp/managed-memory-routing-tests.log 于 02:26:18 BUILD SUCCESS，14 项（Memory 客户端 3、
挂载 2、Service shell 路由 1、JEV 8）全部通过。i 七个专用进程退出已由 stopped.json 验证。
/tmp/managed-memory-routing-clean-package.log 于 02:28:21 BUILD SUCCESS，用时 53 秒，
冻结 j 使用新的干净 JAR 与 ma_replicas_42f1b7eebdde。

j 证明 execute 已在初次及恢复后的 RoutedSandboxFilesystem 中保留，真实工具挂起 / 恢复
后，memory_store_read 已成功读到挂起期间通过资源 API 更新的文档 v2。该 Turn 仍 failed：
测试模型把反射 String 工具携带的 JSON string 直接解码为 Map。原工具返回的内容完整保存在
原生日志中，错误只位于新测试模型的解析假设。将 fixture 按既有工具字符串契约先解码为
String，再解码结果对象；没有放宽公共序列化或改变生产接口。

j 的原失败 JSON / 日志保留，七个专用进程已全部退出，stopped.json 已验证。
/tmp/managed-memory-model-test-compile.log 于 02:33:27 成功，只有测试类变化；三个部署 JAR
沿用之前 clean 构建的同一生产源码。新冻结 k 正在启动，目录
/private/tmp/managed-mq-replicas-20261016-memory-results-k；后续必须核验新模型夹具的完整
会话结果，而不能用 j 的单步读取成功推断完整流程已通过。

/tmp/managed-memory-routing-doc-{tests,validate,links}.log 再次证明文档 16 项、严格构建和
链接检查通过。未改 Core / Harness，未提交或推送。


### 新冻结 k 记忆闭环通过与全仓验证启动（2026-10-16 02:39）

/private/tmp/managed-mq-replicas-20261016-memory-results-k、ma_replicas_f9d8cb7002a5
已全部 ready。Memory 两个完整场景通过，证据分别为
/tmp/managed-memory-results-k-read.log、/tmp/managed-memory-results-k-readonly.log 及 k 下
两份 memory-*.json：真实 30 秒本地工具挂起恢复、读取挂起期间外部更新的文档 v2、共享
挂载写入拒绝不新增版本、后继 Turn 成功、有效完整检查点、没有旧 cp Session。
Go 实际动态 Environment read_only 仍以独立 PG 测试证明，不冒充 Agent 拒绝的原因。

/tmp/managed-memory-results-k-acceptance.py 随后继续同一冻结产物的普通 180 秒工具 / 双
Brain 替换、并行、controls / SSE、取消，最终还会在测试 DB 仅将 obsolete registered_at
向前调整四小时来验证真实补偿循环退休；这是明确的时间模拟，不是四小时墙钟验收。
当前 exec session 16714，验收尚未全部完成，七个专用进程仍运行。

最终 Java 全仓 /tmp/managed-memory-results-full-clean-verify.log 已启动，使用另一独立
新库 managed_memory_results_20261016_v1、真实 Redis / MQ / PG、长协议测试。当前 exec
session 50209；未返回 BUILD SUCCESS 前不将本轮 MemoryStore / 回调退休增量记为全仓通过，
期间不修改 Java / 资源文件。k 冻结部署 JAR 与测试模型已独立复制，不受 Maven clean 影响。

新增待继续审计：Go createPublicSessionTurn 当前仅用 RuntimeOwner=dataplane 拒绝 Managed
旧入口；配置侧创建的 Managed 占位 Session 仍可能走另一受理队列。Quickstart 已改为
测试原生接口，不能因此掩盖该生产分支。后续需要根据 contract 的 Managed binding 核对
旧入口是否应一律拒绝，保留 Hosted / External 的独立语义，并同步相关适配器验证。


### k 最终组合验收与旧 Turn 入口收敛（2026-10-16 02:50）

k 的 180 秒工具、两个 Brain SIGKILL / 重建、T1 → T2、并行、controls / SSE、取消均通过。
首次 smoke 验证进程仍失败：它只允许一次挂起，实际业务工具恢复后，收尾 fs.upload 又因
超出内联等待发生一次正常挂起。两次均有完整 checkpoint / 独立 Resume，推理仍为 T1
step [0,1]、后继 T2 [0]，业务 Task / execution / process handle 不变，只有一份 start / done。
未再发工具，保留原失败日志，重审同一条已完成会话。

verify_smoke 现在逐轮验证严格 running → (waiting_tool → running)* → completed；T1 完成
前 T2 不能出现。每个 waiting 周期必须对应一个已结清的持久 suspension / Resume，代次和
checkpoint 序号严格推进，pending Task 全部确认完成；业务 Task 必须唯一且被真实挂起。
两个 Turn 的终态私有捕获均绑定最终 epoch，迭代预算与一次业务副作用断言保持。
/tmp/managed-memory-results-k-recheck.log 是同一会话及后三组场景的通过证据，原脚本退出
失败不改写为成功。后续源代码 / 文档更新之外没有修改 k 的冻结 JAR。

callback-retirement.json 证明真实 Hands 补偿循环将一个不可达 obsolete pending 退休，当前
通知仍为 0；仅在测试 DB 把该旧 registered_at 前移四小时加 1ms，不修改 receipt / ACK /
Turn / 工具状态，不把它称为四小时墙钟验证。checkpoint-audit.json 完整核对七个 Session
的 85 个私有捕获、12 个终态捕获；旧 DP 执行表 0、旧 cp.sessions 0、当前 / obsolete pending
均 0、结果 outbox 0、退休通知 1。七个专用进程已按 manifest 停止，stopped.json 逐 PID 证明
退出；基础设施及用户原有服务未动。

Go createPublicSessionTurn 已同时检查封存 Managed contract 与 RuntimeOwner，Managed 配置
占位会话也返回 409，避免另一条 Go 受理队列。Quickstart 回归显式覆盖 RuntimeOwner 为空
的 Managed 会话被拒绝，原生 Portal 操作仍成功、保留真实 Actor。
/tmp/managed-native-turn-admission-go-all-tests.log 全包通过（真实 PostgreSQL 已启用），
/tmp/managed-native-turn-admission-go-build.log 四个二进制构建通过。该 Go guard 在 k 启动后
修改，k 使用的是修改前已构建的 Control 二进制；不宣称 k 覆盖该 guard，guard 以 Go HTTP
回归和全包测试为依据。新二进制在 k 完全停止后构建，不替换运行中的测试文件。

Java 最终 /tmp/managed-memory-results-full-clean-verify.log 仍运行，exec session 50209 /
进程 8206，目前已进入包含真实基础设施的 Service Common 验证；没有最终 BUILD SUCCESS
前不记新源码整仓通过，也不在该 Maven 运行时修改 Java / 资源。后续需将全仓产物与 k 的
冻结 Java classes / 使用中资源核对，并继续剩余故障、容量、工具进度和正式客户端 / 部署。
本轮 Service 改动没有修改 Core / Harness；源码仍在 agentscope-3 主目录当前分支，未提交。

### 全仓最终结果、工具视图增量与总体进度（2026-10-16 03:12）

/tmp/managed-memory-results-full-clean-verify.log 已于 03:01:26 BUILD SUCCESS，用时 21:55。
逐模块汇总为 10851 项、0 failures、0 errors、182 条件跳过。此前统计的 8240 / 173
漏计 Core 的 2611 项 / 9 条件跳过，本次更正；跳过项不计为真实通过。该全仓覆盖记忆路由
和回调退休，尚不覆盖随后工具公开投影的增量。冻结 k 的 Java class / 使用中资源与该
clean 构建已核对，证据 verified-java-build-equivalence.json；此证明不包含 Go 二进制。

工具视图现在按原生 Turn / tool call 汇聚：Hands 的操作完成公开为 tool.operation.completed，
仅更新 details.operations；模型工具由原生 tool/result 的真实结果完成，waiting 不完成，
恢复及后续记录对同一逻辑工具只生成一次 tool.completed。Java / 前端 reducer 保持原生
工具名和状态，晚到操作不能覆盖已经完成的模型结果；框架内部操作保持私有。
/tmp/managed-tool-model-completion-tests.log 于 03:12:05 BUILD SUCCESS，63 项全部通过，
包括真实 PostgreSQL Journal / PublicResources、原生 Harness 挂起恢复和投影测试。
/tmp/managed-tool-operation-ui-tests.log 的两个前端文件共 20 项通过，lint / build 通过。
本增量未修改 Core / Harness，尚需文档同步、最终全仓和新冻结部署验证。

总体约 80%–85%，按完整交付目标粗略估算。核心执行链路已落地并通过多副本真实基础设施
验收；剩余包括完整工具日志流 / sourceSeq 协议、父子回调容量、通用外部 reply、剩余故障
矩阵、正式整套容器及客户端端到端验收、旧 API 文档审计与最终全仓复验。工具统一视图
专项通过不等于工具流式进度已经完成。源码位于 agentscope-3 主目录当前分支，未提交 / 推送。

### 工具来源日志与恢复采集实现（2026-10-16 03:33）

新增 ManagedToolEvent / ManagedToolEvents，以独立来源序号保存工具进度、原始字节片段、
显式缺口及最终水位。ma.tool_stream 保存采集 / 消费游标，ma.tool_event 保存不可变
来源与投递 / 消费前缀；冲突进入 ma.tool_event_conflict。Tool Topic 仅接受 tool_event；
tool_result 编码仍用于 Redis 最终结果信箱，未增加旧 Tool Topic 双协议分支。

工具真实结果先保存 result outbox，再采集尾部；采集未完成时不能发布最终来源事件或
唤醒 Brain。接管继续读取原 sandbox 输出文件与持久字节游标，不重启工具。Broker 结果
未知的前序形成发布屏障；接收端持久保存提前到达的来源并 ACK，待缺口补齐再按连续
前缀原子投影，避免 FIFO 头部阻塞缺失前序。最终结果 / Resume 与已消费水位一起提交。

当前 stdout / stderr 合并，每片 16 KiB、每次最多四片，单操作采集最多 16 MiB；超限或
来源丢失明确生成 output_gap。快照保留 65,536 个 UTF-16 代码单元 / 256 块的预览，完整
已采集内容留在来源日志与公开事件。内部原始字节保留，公开文本使用 UTF-8 替换解码。
self_hosted Worker 增加 HTTPS output 提交与共享游标读取；最终结果先落本机持久 outbox。
cloud provider 的转发包装补齐 streamsOutput / output 委派，避免内部执行器具备能力而
服务装配悄悄禁用。该实际装配仍需新冻结多副本验收，不用执行器单测替代。

/tmp/managed-tool-stream-tests-4.log 于 03:33:25 BUILD SUCCESS：H2 与真实 PG 各 10 项，
外发 Worker / 本地进程两项；覆盖乱序、内容冲突、旧 token、捕获后续采、发布屏障、
显式缺口、水位不匹配、回滚、预览上限以及 HTTP 成功响应丢失后的替换 Worker 续采。
上一组 tests-3 的其余工具执行 / 实际 sandbox / Worker 协议均通过，只有异步公开投影的
即时断言失败；tests-4 改为有截止时间地等待真实持久消费前缀，未放松输出或一次副作用断言。
第一轮失败是新增夹具的 schemaVersion / seq 参数写反，第二轮 PG 故障注入约束误覆盖
同测试库其他 Session；均保留原日志，约束现限定当前完整作用域。

前端全量 183 项、lint / build 通过，日志 /tmp/managed-tool-stream-ui-*-2.log；中英文
sse-events 与主设计工具章节已同步，文档 16 项、严格构建和链接检查通过，日志
/tmp/managed-tool-stream-doc-*.log。Core / Harness 未修改。

当前 /tmp/managed-tool-stream-combined-tests.log 及后续 provider-package 仍在运行，之后
将用新库 / 新冻结 JAR 验证 180 秒工具在两个 Brain 全部退出后继续保存输出、Hands 替换
后续采、Brain 恢复推理与 T1 → T2。本段不提前宣称该真实组合、最终全仓或整个目标通过。

### 冻结 l 暴露过早模型工具完成（2026-10-16，必须修复后重新冻结）

/tmp/managed-tool-stream-combined-tests.log 在 03:38:41 通过，73 项、0 failures / errors / skipped；
/tmp/managed-tool-stream-provider-package.log 在 03:39:03 通过。Go 新 UI 二进制也已构建。
冻结 l 为 /private/tmp/managed-mq-replicas-20261016-tool-stream-l，数据库
ma_replicas_53cac1a3e363、六个普通业务角色、四个独立 Lite 父 Topic，每副本推理 / 工具并发 1。

真实 180 秒工具已做到：初始 delta / 完整挂起提交后，两个 Brain SIGKILL 并保持退出；两个
Hands SIGKILL / 重建后，沿用同一 task / execution / process handle，继续保存中段与最终
输出。output-path-audit.json 独立核对三个片段、连续字节区间、来源哈希与最终水位，全部
正确。但总体验收失败，原 tool-stream JSON 和 /tmp/managed-tool-stream-l-acceptance.log
保留失败：原生 action/end 实际写 status=FAILED、errorType=ManagedToolWaiting、result=null，
旧投影把这次宿主挂起当成 tool.completed(status=failed)，发生在远端工具完成之前。

之前的“恰好一次 tool.completed”测试只检查数量，稳定完成 ID 反而会掩盖过早失败；本次
不能把它解释为新的正常分支或放松验收。修复限定 Service 投影：action/end 只投影明确的
等待，不结算模型工具；模型工具由真实 tool/result 或 external resolution 完成。
新增实际错误结构回归，并强化五种 Harness 场景必须完成为 completed，而不只是数量为 1。
Core / Harness 源码未修改。

正在运行的整仓验证因此受控停止，未记为通过；记录
/tmp/managed-tool-stream-full-verify-interrupted.json 证明 Maven 与其测试子进程均已退出。
冻结 l 的七个 manifest 进程均停止，stopped.json 有逐 PID 证明；冻结 JAR、原业务和失败
证据未改写。修复专项 / 新 JAR 构建当前在 /tmp/managed-tool-waiting-fix-{tests,package}.log，
通过后使用新目录 m 和新数据库重新执行完整故障与恢复验收。

另外已确认旧公开 session-event-log 中仍混有旧 Managed 的 actions / resume / 工具和
Workspace 修复、trace 与生命周期格式；新协议的 capabilities 目前仍声明 resume=false。
不能把这些旧例子或旧后端的实现作为当前原生 Managed 的能力证明。中英文整篇调用契约
审计仍待完成，尤其需要区分已实施但尚未开放的 checkpoint restore / fork 与正式能力声明。

### 过早完成修复专项通过，新冻结 m 启动（2026-10-16 03:49）

/tmp/managed-tool-waiting-fix-tests.log 于 03:48:31 BUILD SUCCESS，56 项、0 failures /
errors / skipped，包含真实 PG。五种 Harness 流程现在同时检查恰好一次且状态 completed；
原生 action/end 为 FAILED + ManagedToolWaiting + null result 时必须只形成 tool.waiting。
其他 action/end 不再生成模型完成，真实结果仍由 tool/result / external resolution 结算。
/tmp/managed-tool-waiting-fix-package.log 于 03:49:02 BUILD SUCCESS。新冻结目录为
/private/tmp/managed-mq-replicas-20261016-tool-stream-m，启动及验收分别记录在
/tmp/managed-tool-stream-m-{start,acceptance}.log，当前 exec session 见活动工具记录。
新验收尚未完成，不将 l 的输出链局部成功视为修复后的完整验收。

### 冻结 m 完整工具日志 / 恢复通过与 Go 结果桥接（2026-10-16）

/private/tmp/managed-mq-replicas-20261016-tool-stream-m 使用新库 ma_replicas_c786d5f8a2cf。
tool-stream-1792093854724248000.json 最终 passed=true。实际 180 秒业务工具的初段输出和
完整挂起持久化后，两个 Brain 全部 SIGKILL，保持退出直到工具确认结束；两个 Hands 也
SIGKILL / 重建。新 Hands 继续原 task / execution / handle，三片共 83 字节输出完整保留，
source_sequence=consumed_sequence=final_sequence=5、chunk_sequence=3、无 gap。

在没有 Brain 进程时已验证完整来源哈希、字节 / 片段连续性、最终水位、全部公开输出、原
Turn 仍 waiting_tool，且没有模型工具完成事件。之后恢复两个 Brain，T1 / T2 都成功，严格
生命周期、推理步数 [0,1] / [0]、完整终态检查点及一次 start / done 通过；最终只有一个
模型 tool.completed。此前 l 的失败结论、JAR 与审计保持不变。

最新 Go 审计发现 publicServiceRuntimeType 过滤掉 tool.progress / output_gap /
operation.completed / waiting 及 item.finalized；共享快照也会把操作名称和状态覆盖到模型
工具，并无界累积 progress。现已补齐精确允许类型、独立 operations 和同样的有限预览。
Managed Task 的实际 HTTP / PG 测试加入这些事件并验证重放、最终状态和关联；Go 全包
/tmp/managed-tool-stream-go-all-tests.log 通过。当前 m 使用修改前的 Go 二进制，不宣称该
部署验证了新 Go 桥接；停止 m 后再构建，不覆盖运行中的二进制。

m 的 parallel 已通过，controls / cancellation 及停止在 /tmp/managed-tool-stream-m-*.log。
Java 最新全仓 /tmp/managed-tool-stream-final-full-clean-verify.log 使用新库
managed_tool_stream_final_20261016_v2、真实基础设施和长协议测试，exec session 91626。
尚未有 BUILD SUCCESS，期间不修改 Java / 资源；先前被主动停止的验证不计为通过。

中英文 session-event-log 已按原生 API 重写，去掉旧 Managed actions / resume / 工具与
Workspace 修复、trace 和旧快照 / 预算字段混用；使用 Turn Inputs、十分钟固定快照与独立
生命周期回执，明确当前 capabilities 不开放 resume / restore / fork。保留站内被引用的
required-action、预算与 checkpoint 锚点，不宣称其他 Service 页面已经全部完成调用审计。
文档本批校验见 /tmp/managed-native-control-doc-*.log，尚待最终结果。

### m 最终审计、Go 构建与文档校验（2026-10-16）

m 的 parallel、controls 和 cancellation 全部通过。包含跨 Portal inject / steer 幂等重试、
真实两个 SSE 订阅者及 Last-Event-ID 重连，取消等待中的原工具后后继 Turn 完成。
checkpoint-audit.json 核对五个 Session、55 个私有捕获、八个终态捕获；旧 DP 表 0、旧
cp.sessions 0、待提交结果 0、待消费来源事件 0、未闭合 stream 0、当前回调待通知 0。
七个 manifest PID 全部退出，stopped.json 已验证。旧不可达 callback 的四小时退休语义
保持，当前待通知为零不等于所有历史 receiver 都已收到通知。

m 全部停止后，/tmp/managed-tool-stream-final-go-build.log 四个二进制构建成功。
/tmp/managed-tool-stream-go-all-tests.log 全包测试通过且启用真实 PostgreSQL；真实
Managed Task HTTP 事件转发和 Go 有界快照专项也通过。新 Go 源码没有用 m 的旧二进制
冒充真实组合验证，后续任务适配器实际部署仍需新冻结实例。

中英文 session-event-log 的新原生合约，及 SSE / waiting_tool 文档已通过 16 项文档测试，
严格构建与链接检查。首次检查发现中文 checkpoint 锚点写成不存在的中文标题，已改为
实际 checkpoint-元数据，/tmp/managed-native-control-doc-validate-fixed.log 与
/tmp/managed-native-control-doc-links-fixed.log 均通过。其他页面的旧 Managed 路径仍待
整站调用审计，本轮没有因此宣布所有文档 / SDK / Console 的端到端验收完成。

Java 最新全仓仍在 /tmp/managed-tool-stream-final-full-clean-verify.log（exec 91626），
没有最终结果前不作全仓通过声明。对应源码在 agentscope-3 当前主目录分支，未提交 / 推送；
本批未修改 Core / Harness。

下一容量审查已核对本地 SDK 5.2.2 原始 API：LitePushConsumerBuilder 只有 bindTopic / group
及同步 MessageListener，没有前缀订阅过滤或异步完成接口；subscribeLite 仅声明指定名字。
因此不能假定在同一 wildcard 父 Topic 下能通过 root / child 前缀保留独立容量，也不能只
把活动 Harness 并发设为 1 就宣称父子回调饱和已解决。候选方案是在同一 MQ 部署内按已
限制的委派深度建立静态 Turn 消费通道，保留原回调终态 ACK 与 FIFO，无 Session 目录或
订阅轮换；该方案尚未落地 / 验证，需结合深度上限 16、取消及重投的完整路由一起评估，
不能把单独一个 child 池当作支持任意嵌套的证明。


## 2026-10-18：Brain 直接提交与分段 MQ 回调

本轮按已确认的简化方案移除运行时的 callback registration / completion / Redis mailbox，
以及 Brain runtime-event Topic 和 Hands 代提交链路。Brain 在交出执行资源前直接提交
PostgreSQL 原生 SessionLog、checkpoint、工具意图、输入回执、公开事件投影及挂起/终态。
checkpoint 和大对象继续使用 PostgreSQL；不需要 NAS，用户文件保持关闭。
历史 callback 表及其不可变迁移保留，新流程不读写这些协作表。

原始 Turn 每次只执行一个活动片段：完成持久挂起后释放 Harness 和 Redis 令牌，
返回 SDK ConsumeResultSuspend。原消息仍占 FIFO 队首，后续 Turn 留在 MQ；独立 Resume
继续同一 Turn，下一次挂起或终态后确认 Resume。只有持久终态后才确认原始 Turn。
MQ 消费权、Redis 活动执行令牌及数据库 epoch fencing 均保留，没有加入数据库调度队列。

ManagedPipelineTest 覆盖重复等待、多次 Suspend、快速/重复 Resume、容量等待、持久化失败，
验证等待不增加执行 epoch、不消耗失败重试，其他 Session 仍可使用回调容量。
真实 PostgreSQL/Redis/LitePush 的 180 秒工具测试已验证替代 Brain 通过 Resume 完成 T1，
随后确认原始 T1，再执行 T2。该用例可通过 MANAGED_PROTOCOL_MQ_PREFIX 指定本轮独立资源，
避免已销毁测试数据库对应的旧 MQ 消息干扰后续测试。

本轮测试与发布证据保存在
`/Users/ken/.codex/outputs/agentscope-managed-refactor-20261018`。
本地 min-offset Broker 是既有的显式测试派生镜像，不是生产版本。
已定位的 RocketMQ 5.5.1 DLQ 缺陷按用户要求延后，本轮不修改 Broker/SDK。
