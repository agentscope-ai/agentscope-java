---
title: "参考项目逐项深化与验收"
---

本轮从已合入的 `be4a9643` 出发，在 `/Users/ken/agentscope-3/agentscope-java` 当前 `harness-context-redesign` 分支持续改造，保留已有修改。七个方向逐项完成；前一项未通过下述验收，不开始后一项实现。

## 顺序与状态

R1–R7 均完成本轮限定范围的研发验收。下文各阶段记录保留当时的测试数量和推进顺序；当前状态以上表及最后的 R7 验收为准。

| 顺序 | 方向 | 实现参考 | 当前状态 |
| --- | --- | --- | --- |
| R1 | Agent 轨迹评估与定义复用 | [jevals](https://github.com/openlayer-ai/jevals) | 研发验收通过：[API 与集成验证](/v2/zh/jev/guides/trace-evaluation-api)、[真实模型对照](/v2/zh/jev/guides/trace-benchmark)；部署验证另行跟踪 |
| R2 | 上下文治理与恢复 | [fast-jev-compaction](https://github.com/tamaratran/fast-jev-compaction) | 研发验收通过：[Harness/Service 接入与恢复](/v2/zh/jev/guides/context-compaction-api)、[真实模型对照](/v2/zh/jev/guides/context-benchmark)；生产保持影子验证 |
| R3 | 长任务进展与交付监督 | [Foreman](https://github.com/thruwire/foreman) | 研发验收通过：[持续观察与 Service API](/v2/zh/jev/guides/supervision-api)、[真实模型对照](/v2/zh/jev/guides/supervision-benchmark)；生产效果待验证 |
| R4 | 代码审查与交付证据 | [jev-review](https://github.com/devagrawal09/jev-review) | 研发验收通过：[API 与 Service 工具](/v2/zh/jev/guides/code-review-api)、[真实模型对照](/v2/zh/jev/guides/code-review-benchmark)；生产仓库与阈值校准待验证 |
| R5 | 检索、证据与回答质量 | [spring-ai-typesafe](https://github.com/spring-ai-community/spring-ai-typesafe) | 研发验收通过：[分类/重排、Agent 与 Service API](/v2/zh/jev/guides/evidence-pipeline-api)、[固定输入对照](/v2/zh/jev/guides/evidence-benchmark)；生产检索源、生成闭环与校准待验证 |
| R6 | 模型及阶段路由 | [Jevonian](https://github.com/xinyao27/jevonian) | 研发验收通过：[阶段路由与 Service API](/v2/zh/jev/guides/phase-routing-api)、[固定路由判断对照](/v2/zh/jev/guides/phase-routing-benchmark)；生成任务质量与生产成本待验证 |
| R7 | 浏览器动作与完成判断 | [jev-ultrafast](https://github.com/browser-use/jev-ultrafast) | 研发验收通过：[执行/守卫与 Service API](/v2/zh/jev/guides/browser-execution-api)、[固定动作与真实本地浏览器对照](/v2/zh/jev/guides/browser-benchmark)；生产网站与校准待验证 |

## 每项交付门槛

1. 固定来源 commit、许可证、读取的源码入口及测试，逐项标出借鉴机制。README 宣传数字不作为证据。
2. 对照 AgentScope 当前实现，记录保留、补齐、替换的具体行为。推导出的适配选择必须明确标注，不能称为参考项目已有实现。
3. 完成实际扩展点接入及默认离线运行案例，不能以孤立建议 API 代替集成。复用现有客户端、权限、运行上下文和事件，不新增独立决策服务。
4. 覆盖成功、失败、弃权、不适用、超时、取消及跨调用隔离；有副作用路径必须验证执行次数和权限边界。
5. 在正式文档发布基本 API、案例、源码映射、差异及验证记录，并配置左侧导航；`docs/jev` 保留对应专题记录。
6. 完成相关模块构建与行为测试，明确真实模型及生产验证范围。真实 JEV/Qwen 效果需固定输入、版本、场景、延迟与用量对照，不能用模拟响应冒充。

本轮完整目标包括全部七项。源码适配完成、离线验证完成、真实模型效果验证和生产部署分别记账；不因为已有 API 或单项测试通过就标记整个方向完成。无运行条件的外部效果验收应保留为明确未完成项。

## R1 已确认的差距与实施范围

已有 `JevEvaluator` 评审问题、证据、答案，并不代表已有轨迹评估。参考 jevals 的 `_sample.py`、`_eval.py`、`_runner.py`、`agent/_evals.py`，本项补齐：

- 从 AgentScope 消息和工具定义构造有工具调用 ID 的轨迹样本，保留调用与结果对应关系。
- 复用 state/questions/reduce 定义；支持 Noul、Choice、Score，不能强制把分类结果当 PASS/FAIL。
- 同值 state 合并、冲突 state 拆批、题目命名空间、确定性预检查、不适用项跳过。
- 工具选择、工具结果使用、事实依据、范围、相关性、完整性、注入及确定性轨迹对照等指标。
- 批量场景报告与调用用量；错误、弃权和跳过保留在分母；完整请求耗时，避免把失败计为零耗时。
- 同一定义用于离线轨迹与 Agent 在线影子观察；不自动重新执行 Agent，不以评估通过代替授权。

R1 已完成本节列出的源码适配、行为测试、实际 Agent 与 Service 构建链验证和真实后端对照。Service 完整部署及生产业务效果继续单列，不以替身测试冒充。R2–R7 已继续完成研发验收。

## R1 本轮推进证据（2026-09-25）

已新增轨迹定义、评估器、批量 runner、在线影子中间件、文本模型 HTTP 适配和 Service evaluation 配置。JEV 130 项、Service 相关 23 项测试通过，相关 19 个模块 verify 通过。固定 12 条轨迹已分别跑过 JEV 与 Qwen：29 个有金标语义项中正确数为 27 / 25，弃权为 2 / 1，Qwen 一次响应契约失败影响 3 个金标项。详见[轨迹评估专题](/v2/zh/jev/guides/trace-evaluation-api)和[对照记录](/v2/zh/jev/guides/trace-benchmark)。未部署 Service，不宣称生产效果已验证。

## R3 本轮推进证据（2026-09-26）

基于 Foreman ba91849 固定源码完成独立职责检查、确定性建议仲裁、按调用隔离的事件观察、去抖与周期检查、当前版本验证收据和 Service 影子配置。JEV 168 项、Harness 26 项、Service 30 项通过，21 个相关模块本地安装成功。真实固定输入中 JEV/Qwen 的标注项正确数为 34/38 和 38/38，建议匹配为 7/12 和 11/12；不把小样本准确率或完成建议当作生产验收。详细来源、差异、初始策略记录及限制均在专题页。

## R4 本轮推进证据（2026-09-26）

基于 jev-review 31f8960 固定源码完成变更/完整源码两种评审、五维筛选、文件画像、证据定位、机制/严重度/角色建议，以及真实 Agent 只读工具和 Service 构建前装配。新增 23 项扩展测试、5 项 Service 配置/构建/权限验证；合并后累计 JEV 191 项、Harness 26 项、Service 35 项通过，21 个相关模块 install 成功，离线 CLI 运行通过。正式站点 461 页、479 个重定向的链接/语法检查和 build validation 通过。

固定 12 个样本中，JEV/Qwen 筛选标注正确数为 11/16 与 16/16，弃权 5/0；8 个预期问题形成位置匹配发现为 3/8 与 2/8。两后端各有一次后续阶段错误，只有 1/12 报告完整，不能据此作为自动合并门禁。API、源码映射、原始记录、用量缺失和置信度差异已发布。代码位于上述主目录当前分支，尚未提交本轮改动，未部署 Service；不宣称生产代码评审效果已验证。

## R5 源码复核与适配范围

参考 spring-ai-typesafe 固定提交 [5c469ce10ee45175f56a8f5882cc93d29f72f591](https://github.com/spring-ai-community/spring-ai-typesafe/tree/5c469ce10ee45175f56a8f5882cc93d29f72f591)，Apache-2.0。已读取 DocumentFilter、DocumentReranker、SelfRefineAdvisor、GuardrailAdvisor、Evaluator、ToolIndex 及相关测试；以下为已完成的实现及明确适配边界。

- 现有 JevRag / JevKnowledge 的候选选择主要是单一相关性 Noul，不能等同于参考过滤器。参考实现每段独立询问相关性、答案证据、前提冲突和注入，先排除注入，再保留并标记冲突证据，最后判断相关与可回答性。
- 参考重排另问 answers_query；有分数的段落稳定降序，未评分排在其后，topK 不允许未知项挤掉已评分项，不修改原文档元数据。已补齐独立阶段及逐段结果。
- 原项目过滤失败会放行未分类段落。AgentScope 明确区分确定性 ACL、语义弃权、后端错误和回退策略；SHADOW 的实际证据保持原有授权输入，建议单列；ENFORCE 中的注入检查不因错误而自动视为通过。这是适配选择，不能描述成参考项目原有行为。
- 复用已有 Judge 状态契约、Evaluator 和 ResponseMiddleware，验证证据独立输入、发布前审核及只修订草稿。原项目可选择 BEFORE_TOOLS_ORDER 重跑工具，本项不引入此路径；生产阈值未校准时继续影子运行。
- 已完成实际 Agent / Service 构建接入、只读检索工具、ACL 与跨会话隔离、检索召回与固定草稿审核对照和正式文档。这里的答案质量使用固定草稿及固定证据，不是生产生成闭环。工具选择的 none/单候选/多候选沿用 H1 已验收接口与测试；不新增独立索引服务。

## R5 本轮推进证据（2026-09-26）

合并后累计 JEV 210 项、Harness 26 项、Service 38 项通过，21 个相关模块 install 成功。离线真实 Agent 案例通过：一次检索、三次生成模型调用、五次判断，修订不重跑工具；命名问题字段与配对工具证据分离，孤立结果及超限阻止 ENFORCE 发布。正式站点 463 页、479 个重定向的检查和 build validation 通过。

固定 12 个场景的 21 个段落标签中，JEV/Qwen 正确数为 16/18，错误 1/0、弃权 4/3；检索建议召回 10/11 与 11/11。24 个固定草稿标签正确数 17/21；组合 P50 为 1,733.093 / 4,586.990 ms。JEV 首批 33 次调用全部错误，原因未确证，故障报告与原样复跑均保留；没有根据结果调参。金标中切题与事实正确性的边界、失败用量未知和固定草稿限制均在对照页说明。

代码和文档仍在本页指定主目录当前分支，未提交本轮改动，未部署 Service。R5 研发验收完成，下一项为 R6 Jevonian 模型及阶段路由，先固定源码及测试依据；R7 继续排在其后。

## R6 源码复核与适配边界

参考 Jevonian [75e980a8b05cf931b3f7f0de364fea905ab4ca01](https://github.com/xinyao27/jevonian/tree/75e980a8b05cf931b3f7f0de364fea905ab4ca01) 的 routing.ts、brain.ts、capabilities.ts、ledger.ts 和 routing/capability-routing/quota-routing/effort-ledger 测试。源码为 AGPL-3.0；本项目参考可观察行为和机制，使用 AgentScope 接口独立实现，不移植代理源码。

固定版本实际选择的是带有有序模型列表的 routing，显式阶段与固定模型跳过 JEV；自动路径同次请求选择 routing 和 effort。上下文/思考能力/配额先过滤，后记录实际请求用量。源码中所有 brain 故障已出现启发式回退，与同版本 README 的 502 描述不同；低置信度仍接受选择，也不同于本项目既有回退契约。

本项在既有 JevModelRouterMiddleware builder 增加显式配置，支持调用/显式阶段范围的候选计划、预筛与再核验、单次 route/effort 判断和分离的判断/执行用量。继续沿用原模型回退、SHADOW 原输入、调用内粘性和不重放工具。能力目录、动态配额及定价由宿主提供，缺失不会凭模型名称猜测；不扩展为独立代理或凭据管理服务。实现、实际 Agent / Service 构建链验收及固定路由判断对照已完成；实际供应商 wire 参数、生产目录与任务质量/节省成本继续单列验证。

## R6 本轮推进证据（2026-09-26）

在既有中间件 builder 下接入 JevPhaseRouting 与宿主 JevRouteCatalog，完成显式阶段/固定模型优先、能力与配额预筛、同次 route/effort、候选派发前复核、失效持续回退和分离的 SDK 用量记录。真实 Agent 案例运行 auto/plan/execute 三次，每次只读工具恰好执行一次；只有 auto 发起一次判断。Service 配置、allowlist、构建缓存、元数据和关闭恢复已验证。

累计 JEV 227 项、Harness 26 项、Service 41 项通过，21 个模块 install 成功；随后概率保留及剩余预算调整的 17 项专项回归和模块 install 通过。CLI 通过，正式文档 465 页、479 个重定向及 build validation 通过。固定 12 个路由标签正确数 JEV/Qwen 为 11/8，弃权 1/4，均无错误选择；P50 485.214 / 1,515.032 ms。没有调用候选生成模型，不把路由标签分数或合成价格解释为实际任务收益。

R6 研发验收完成，下一项 R7 jev-ultrafast 浏览器只读操作与完成判断。所有本轮代码仍位于本页指定主目录与分支，未提交、未部署 Service。

## R7 源码复核与首个交付边界

参考 jev-ultrafast [1231850a0bf1a0c0341fe408ef1668dbbfdfac46](https://github.com/browser-use/jev-ultrafast/tree/1231850a0bf1a0c0341fe408ef1668dbbfdfac46)，MIT。已读取 agent.py、model.py、browser.py、snapshot.js、questions.py、tests/test_agent.py、独立 Flights 验证及真实控件守卫脚本。

核心依据是可见节点索引、一次请求中的 operation/匹配 target、派发前页面与目标守卫、先消费决策再执行、先记录执行再观察、有限无进展停止，以及 DONE 的独立应用验证。当前 JevBrowserPlanner 只是扁平候选建议，不能等同于这套实际浏览器执行循环。

首个交付限定宿主授权的只读信息查询：可见链接导航、滚动和等待；保留未使用目标头不得执行、页面版本复核和完成验证。实际浏览器案例使用隔离浏览器上下文与本地无写入页面，默认案例仍不需要浏览器或模型密钥。生产网站只读性与 URL 权限由宿主确定，不由 JEV 赋权。通用输入、选择器生成、表单提交、文件上传和购买不在本轮边界。实现、Agent/Service 接入、真实浏览器验证与对照已完成，详见下述验收。

## R7 本轮推进证据（2026-09-26）

补齐共享可见候选的 operation/target 共判、一次性派发收据、原子页面/目标守卫、总预算、有限过期及无进展停止、绑定目标/页面版本的独立完成验证。只读工具进入真实 Agent 与 Service 构建链，权限拒绝、配置缓存、关闭恢复和跨调用独占会话均经过行为测试。Playwright 可选适配使用隔离上下文、宿主 URL allowlist；本机真实 DOM 守卫和本地网页导航通过。

完整相关回归：JEV 247 项、Harness 26 项、Service 45 项通过，21 个模块 install 成功；之后禁用祖先节点过滤的 20 项专项及模块 install 通过。正式文档 467 页、479 个重定向及 build validation 通过，本地新专题 URL 返回 200。

固定 12 个动作标签中，最终 JEV/Qwen 均正确 12/12，P50 为 301.527 / 1,443.692 ms。首轮遗漏共享候选导致的原始结果也已公开；按上游共享 elements 结构修正后两后端统一复跑，不将它作为盲测。真实本地浏览器分别以脚本、JEV、Qwen 判断，都只导航一次、判断两次、严格独立验证通过并关闭上下文；生成模型仍为脚本，不声称真实生成质量或生产网页效果。

## 本轮完成与剩余生产工作

七个方向均已落实固定来源、扩展点接入、离线案例、失败/取消与隔离行为测试、真实后端对照、正式文档及菜单。代码均在 `/Users/ken/agentscope-3/agentscope-java` 的 `harness-context-redesign` 主目录中；`be4a9643` 已合入，但本轮增量改造尚未提交，未部署 Service。

下一阶段是生产接线与独立校准：提供真实业务检索源、版本化源码/轨迹、模型目录与配额、浏览器授权目录和独立验证器；在 Service 测试环境回放金标和影子流量，再按用途决定接管。源码中的阈值和小样本成绩不自动转化为生产开启许可。表单操作、通用网页任务、自动阶段识别、团队派发和自动终止长任务均不属于本轮已交付范围。
