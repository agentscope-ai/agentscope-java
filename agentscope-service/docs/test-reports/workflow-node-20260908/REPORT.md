# Workflow 首节点失败修复（2026-09-08）

代码直接位于 `/Users/ken/agentscope-2/agentscope-java`，分支 `agentscope-service-v5`。保留主目录已有修改，未使用独立工作树。

## 原始故障

Task `9b3d5a9b-cb63-4abb-9e1c-355a77d0cc91` 属于 demo namespace 的 workflow1（revision `53278907-3d93-4dea-b29c-11c28ede6429`），三节点 `work → node2 → node3`，请求为“帮我写一首诗，逐步优化”。原 Run `02d63988-c67d-4e2a-940f-ec39c58e30dc` 首节点失败，后续节点因 fail_fast 取消。

原 Session 只调用了 `task.get`，随后追问主题偏好，没有诗稿、没有 task.complete/task.fail，错误为 managed_turn_incomplete。task.get 返回了总目标，却没有返回已有的 Node 字段；唤醒指令沿用了普通 standalone 任务说明。这里的终止错误如实反映未交付结果，不能直接将纯文本结束自动判为成功。

## 修复

- executionBrief.workflow 在物理唤醒和 task.get 中携带节点 key、节点已解析输入、总输入、直接上游节点的状态和输出；不会引入无关分支，也不改写显式 input mapping。
- task.get 同时返回已有的 Node 字段。后续节点可基于实际前序结果继续工作，而非每次从总目标重写。
- 补充 workflow 执行约定：可自行决定的写作偏好不应阻塞交付；缺少真正必要输入时明确失败；必须通过 task.complete 提交本步骤的实际产物，由引擎推进后续步骤。

## 重跑期间发现的 MCP 阻塞

当前 MA1 配置了 GitHub HTTP MCP，但认证 env 值为空、没有 HTTP Authorization 头。首次重跑 `49ea86f1-fde4-4ca4-8405-05a770f17881` 在模型开始前因该必需连接失败。写诗不依赖 GitHub，因此保留连接配置并将其 required 改为 false（MA1 definition version 2 → 3），没有补造凭据或修改工具权限。

第二次重跑 `6ed6f60a-360c-43c3-bfe0-497d7f35fa8d` 揭示另一个事件协议问题：Java 已跳过可选连接并继续构建 agent，但可选失败回调使用 session.error；控制平面提前将 Task/Attempt 判失败，后续事件被 410 拒收。

修复将已知的可选 MCP 回调（error.type 与 code 均为 mcp_connection_failed_error，retry_status=next_turn）映射为 session.warning，保留诊断而不中断 Task/Attempt/Endpoint。必需连接失败的 type=api_error，即使同为 next_turn 也仍然是终止错误；未知形式同样保留失败语义。不会仅凭“可重试”判断当前轮可继续。

## 自动验证

- Go 全量测试 `go test ./...`。
- 真实 PostgreSQL 和 memory 的 TestWorkflowAcceptance：覆盖首节点上下文、上游结果传递、无关分支隔离，以及已有 workflow 回归。
- 可选 MCP warning 的 HTTP 事件回归：事件保留，Task/Attempt 继续 running，后续消息可到达；终止错误仍失败。
- 必需/可选/未知 MCP 错误分类回归；`go build -o bin/aistiod ./cmd/aistiod`。

原始失败事件和各次重跑记录均保留在 evidence/。最终运行审计见 audit.json。


## 产物传递问题

第三次重跑 `8d63f95d-afd0-4f7c-b08c-05eded088056` 首节点成功调用 task.complete，但其参数为 message=完整诗稿、summary=简短说明，未设置 result。接口只将 message 作为缺少 summary 时的后备，导致实际诗稿被丢弃，后续节点仅收到“提交了第一稿”的摘要，再次因缺少诗稿而未完成。

修复 task.complete 的成功分支：没有显式 result 时，保留 message 为实际 Result；显式 result 始终优先。同步完善工具说明与 workflow 唤醒的字段说明。增加真实 MCP 接口回归，断言 task.result 和可见评论均保留全文，并验证显式结果优先。


## 未调用完成工具时的一次纠正

第四次重跑 `4491d633-62cb-413c-96bf-b069a2ca984b` 中，模型已经输出诗稿，但再次只返回普通文本。这说明提示补充本身不足以稳定保证完成协议。

对 declared/subrun 的非 Team agent 节点增加一次受限纠正：仅首个 dispatch generation 正常结束却未完成任务时，将该 attempt 记为 failed，并将同一 task 重新排队。下次唤醒带上前次返回内容与明确的提交要求。第二次仍不调用完成/失败工具时按原逻辑失败。不会把普通文本自动判为成功，不会创建独立运行，不会反复执行 Team 分解，也不会无限重试。

测试覆盖首轮重新排队、task/run/node 身份保留、前次内容传入纠正上下文，以及第二次失败时 Run 终止。


## 最终真实集群结果

最终 Run `88e41f16-8d65-4a95-afab-c2201e97f335` 使用原 workflow 的同一个不可变 revision 和原 Issue，通过正式 rerun 入口执行。

- 原 Issue `0ad981e4-11cd-5ee9-ac16-821d3a5f1a4f` 为 in_review；Run succeeded。
- 三个 Node 均 succeeded，三个 Task 均 completed，三个 Attempt 均 succeeded；本轮每个节点一次完成，未触发纠正重试。
- work 提交第一稿；node2 的 task.get 包含与 work.output 完全一致的诗稿；node3 同样收到与 node2.output 完全一致的优化稿；三个输出均为完整诗文。
- 三个 Session 均实际调用 task.get、task.complete，均无 session.error；保留 2 条 session.warning，不影响执行。Run 事件包含三组 attempt.succeeded / node.succeeded 和最终 run.succeeded。
- 自动断言核对上述状态、节点产物与上下游传递一致性通过。前面各次失败运行保留用于审计，未覆盖为成功。

最新 Go 全量测试、PostgreSQL workflow 回归、构建和部署日志位于本目录。部署使用 BUILDER_RESET_DB=0，数据保留；代码与构建产物都在主目录。本次没有修改 Java 源码。
