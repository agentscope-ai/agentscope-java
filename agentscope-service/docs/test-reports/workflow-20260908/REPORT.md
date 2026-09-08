# Workflow 改造与能力验收（2026-09-08）

代码位置：`/Users/ken/agentscope-2/agentscope-java`，分支 `agentscope-service-v5`。直接修改主目录，保留已有暂存及未提交变更，未创建 worktree，未提交 Git。

## 页面改造

- Workflow 列表支持创建、分页、归档筛选；详情统一为总览、流程设计、活动、版本、接入、设置。
- 总览提供已发布拓扑、节点数、进行中及失败运行、最近任务；活动按具体工作项呈现，可搜索任务标题、输入、结果和 Run ID，支持状态筛选和分页。
- 设计器支持 Agent、Team、Condition、Join、Approval、Timer、Signal、Subrun 八类节点，配置依赖、分支 CEL、输入映射、失败策略、重试与超时，保留高级 JSON。
- 草稿编辑与发布版本分离；未保存修改阻止发布、跨页签保留草稿、保存/发布进行版本冲突检测；旧版本可复制成草稿。
- 运行明确选择版本、输入及新建/已有 Issue。执行详情以结果、流程路径和节点检查器为主，展示等待原因、输入输出、Task、Attempt、Session 与子流程；底层事件放入折叠诊断区域。运行图以虚线标记 Team 派单，并使用 Worker 的 Agent 名称；长工作项标题在窄屏换行。

## 验收发现与修复

| 问题 | 处理与验证 |
|---|---|
| 未保存编辑可能发布旧草稿 | 发布按钮受草稿状态约束，后端原子校验版本；浏览器完成保存、发布 v2、启动 v2 |
| 历史运行先取全局前 100 条再过滤，可能漏记录 | 后端按 Definition 过滤与分页；前端读取所属 Workflow 各页 |
| 合法 JSON 的错误结构导致编辑器白屏 | 显式结构校验与错误提示；参数化测试及浏览器输入 `[]` 验证 |
| 纯门控流程省略空 tasks/attempts 导致执行页白屏 | API 归一化空数组；前端回归测试及真实页面复验 |
| 早到 Signal 丢失、重复 Signal 改写结果 | 持久事件消费、幂等事件及首次匹配结果；暂停/恢复/重启后验收 |
| 重复或并发启动创建重复 Issue | 启动锁、稳定 Issue ID、Run 幂等校验；8 个并发请求与 4 连接 PostgreSQL 验证 |
| 创建 Run 中断可能留下不完整节点图 | planned 阶段可恢复物化、稳定节点/边/子 Issue ID；部分物化故障场景测试 |
| CEL 映射未完整传到 Agent 上下文 | 持久化 node.input 并纳入执行上下文；Managed 与 Hosted 均读取输入并输出 42 |
| Team 重复创建已声明协调节点，PostgreSQL 唯一键冲突 | 复用协调节点、数据库冲突归一化；内存及 PostgreSQL 回归 |
| 超时/快速失败留下待审批项或子流程 | 统一关闭节点关联任务、审批及子流程；真实 HTTP 超时和 PostgreSQL 清理测试 |
| fail_fast 后仍先执行错误分支 | 在后续节点调度前处理快速失败；真实运行验证后续节点 cancelled |
| MCP 在初始化前读取工具列表 | 修正初始化顺序；5 项 Java 定向测试、数据面重建和真实工具调用复验 |
| Team 将 Workflow 后续节点当成自己的完成条件 | 完成检查按该协调节点的派生任务树隔离；既允许未开始的后续步骤，也保留活跃 Worker 屏障 |
| 归档/非法发布错误被报告成服务器故障 | 归档及无发布版本返回冲突；无效定义发布返回 422 |

运行及最终测试结果见下方验收记录；失败重现记录与修复后记录都保存在 evidence，初始失败不能当作最终状态。

## 证据与重跑

- `probe.py`：真实环境构造与读取，固定 `wf-acceptance-20260908-081653` 前缀，默认开发 admin 登录，可用 `WF_TEST_BASE`、`WF_TEST_PASSWORD` 覆盖；登录凭证不写入证据。
- `matrix.py`：Join all/any/quorum、三种失败策略、审批与子流程超时清理的 HTTP 断言。
- `evidence/http.jsonl`：已脱敏请求/响应；`evidence/state.json`：测试 Definition/Run 标识及断言；`*-graph.json`：运行结果。
- PostgreSQL 测试只创建并删除自己唯一命名的 `workflow_test_*` schema，没有重置应用数据库。
- 测试对象均标有 `wf-acceptance`，保留供复核；运行失败会产生对应待处理工作项，属于本次测试证据。

## 验收边界

这是本地开发环境的功能和恢复性验收，不等同于生产容量、跨地域故障或长期稳定性测试。External Application Agent 当前没有可用的在线实例，实际外部后端的端到端链路尚未验收；已有适配器及统一任务协议测试不能替代该项。公共 Endpoint、Automation、Webhook 等入口的全组合集成没有在本轮逐一触发，Workflow 自身的版本、HTTP 启动与执行链路已按记录验收。

## 最终验收结果

- Go `go test ./...` 全量通过。
- Workflow 恢复性及并发验收：9 个场景 × 内存/PostgreSQL 两种存储，共 18 项通过。
- 前端 26 个测试文件、114 项测试通过，定向 ESLint 通过，TypeScript/Vite 构建通过。
- Java MCP 定向 5 项测试通过；Harness 两个涉及文件的 Spotless 检查通过；包含 15 个模块的数据面构建通过。
- 本地已更新前端静态资源、Go 控制面及 Java 数据面，网关仍使用 18080；应用数据库未重置。
- 保存/发布/运行、旧版本保持不变、草稿跨页签、错误 JSON、执行检查器、接入版本选择及 Session 跳转均经过真实浏览器复核。

| 真实运行场景 | 最终结果 | Run |
|---|---|---|
| Managed：19+23=42，CEL 下游输入一致 | succeeded | [af24cc32](http://127.0.0.1:18080/work/executions/af24cc32-bde4-4c60-a9f5-07cc8ac2ef33) |
| Hosted/Codex：17+25=42 | succeeded | [35ec8755](http://127.0.0.1:18080/work/executions/35ec8755-ac0a-476a-af59-ea07b5a83664) |
| Team：Lead → Worker → Lead → 后续节点；3 个成功 Attempt | succeeded | [5ef706b3](http://127.0.0.1:18080/work/executions/5ef706b3-f33d-4ae5-b25a-9f2e8e67020a) |
| Timer → 提前 Signal → Approval → Subrun → Join | succeeded | [1392c850](http://127.0.0.1:18080/work/executions/1392c850-9bc6-483a-ae1f-82ef005891b5) |
| 真实进程重启后保留暂停及 Signal，恢复输出 42 | succeeded | [2910f89b](http://127.0.0.1:18080/work/executions/2910f89b-01ec-4b02-a2f3-4d4bc78e2bf4) |
| 浏览器发布并选择 v2，输出 43 | succeeded | [368fed9e](http://127.0.0.1:18080/work/executions/368fed9e-d41d-41e1-9e14-6fc472f4728b) |
| 重跑固定原版本；重复请求只有一个新 Run | succeeded | [c0d6b981](http://127.0.0.1:18080/work/executions/c0d6b981-e49f-4e9f-b962-4c90c074c905) |

HTTP 确定性矩阵：all/any/quorum 均符合选中路径规则；fail_fast 在错误后取消后续节点；continue 允许恢复分支但 Run 保持失败；partial_success 保留部分成功结果；审批超时取消待审批项；子流程超时取消子 Run。归档后的发布和启动返回 409；陈旧版本编辑返回 409；不完整草稿可保存但发布返回 422。

最初的 Team 唯一键冲突、MCP 初始化失败、后续节点阻塞协调器均留有失败 Run；`team-complete` 是修复全部问题后的成功复验，不以初始失败 Run 代表当前结果。

补充构建修复：当前工作区已有并行修改引起两处 Go 编译错误，修复了 Agent fork 调用处的 `agentSnapshot` 新签名适配，以及 Vault handler 缺少的 JSON import。其余已有改动保持原状。

完整 Java/Maven 全仓 `clean verify`、生产压力与 External 实例端到端未执行；请结合上面的验收边界理解通过范围。
