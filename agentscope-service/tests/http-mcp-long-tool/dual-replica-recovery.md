# 双副本故障恢复验收：2026-10-19

[English](dual-replica-recovery.en.md)

后续已补齐恢复协议并完成[新版本复测](recovery-v1-acceptance.md)。以下保留首次验收时的失败事实。

结论：基线和 Runtime Pod 重启恢复通过；Tool Worker 在 HTTP MCP 调用中途退出后的自动恢复未通过。
没有观察到重复物理调用或 T2 越序，但不能据此宣称所有在途任务都能自动恢复完成。

## 环境与方法

- 项目：`/Users/ken/agentcore/agentcore-managed-agents`，分支 `master`。
- 集群命名空间：`agencore-ma`；五个业务组件均为两个副本，独立 MCP 也是两个副本。
- 镜像版本：`2.1.0-build.20261019-203221-6c6d5ae0`；本轮仅进行故障注入，没有更新镜像或 Deployment 配置。
- 每个场景单独创建 Session，使用真实模型和 HTTP MCP；不引入 Sandbox 或 NAS。
- 故障场景先确认 `T1 waiting_tool / T2 accepted` 和真实工具开始记录，再从结构化日志定位实际任务发起 Pod，以一秒终止宽限期删除该 Pod，保留健康副本。
- 交叉验证公共 API、两轮最终输出、MCP 开始/完成日志、PostgreSQL 任务与挂起/恢复记录。

## 结果

| 场景 | 结果 | MCP 开始/完成次数 | T1 / T2 最终状态 |
| --- | --- | --- | --- |
| 双副本、真实 90 秒工具基线 | 通过 | 1 / 1 | completed / completed |
| T1 挂起期间重启原 Runtime Pod | 通过 | 1 / 1 | completed / completed |
| MCP 调用中途重启原 Tool Worker Pod | 未通过 | 1 / 1 | waiting_tool / accepted |
| Worker 恢复后在独立 Session 提交新任务 | 通过 | 1 / 1 | completed / completed |

三个成功场景均验证了注入输入最终 applied、两轮引用相同真实 call_id、T1 完成事件早于 T2 运行事件，
以及数据库中的 Suspension 已 resumed。Runtime 原 Pod `ma-managed-agents-agent-harness-runtime-585f58f8f9-w5bwp`
删除后，由 `ma-managed-agents-agent-harness-runtime-585f58f8f9-ckxbz` 接管同一个 T1。

## Tool Worker 故障细节

故障 Session：`ae0f0db1-1c84-55de-8fce-8a259ea618b2`。北京时间 21:04:31 删除实际发起调用的
`ma-managed-agents-tool-worker-6dd95558b9-rfgfd`；MCP 服务于 21:05:53 正常完成唯一一次真实调用，
call_id 为 `f0cb56ca-f538-4c21-a2df-946eb5af144c`。观察到 90 + 240 秒完整验收超时后，T1/T2 仍未完成。

数据库保留原 task_id、execution_id 和两轮受理记录；工具执行为 unknown，无已捕获结果；注入输入仍为 accepted。
任务记录没有被删除，T2 没有越序，外部操作也没有被盲目重放；但原结果无法找回，原会话不能自动继续。

当前 MCP fixture 是无持久化的 HTTP 请求/响应服务，没有按稳定执行标识查询历史结果的接口。
Worker 的在途响应随进程退出丢失后，接管实例只能维持 unknown。这符合当前“不重放不确定外部操作”的实现边界，
不满足这一故障窗口内自动完成的要求。需要持久化外部执行标识与结果查询/确认能力，不能通过直接重试未知调用来修复。

## 证据与结束状态

- [汇总 JSON](../../release/dist/ha-recovery-20261019-2058/summary.json)
- [基线](../../release/dist/ha-recovery-20261019-2058/baseline/report.json)、[Runtime 重启](../../release/dist/ha-recovery-20261019-2058/runtime-restart/report.json)、[Worker 重启](../../release/dist/ha-recovery-20261019-2058/tool-worker-restart/report.json)、[重启后新任务](../../release/dist/ha-recovery-20261019-2058/post-worker-restart/report.json)
- 证据目录为本地忽略目录，含私有 API 状态、物理调用与数据库事实；测试脚本见 [verify_recovery.py](verify_recovery.py)，使用方法见 [README](README.md#two-replica-owner-pod-restart-acceptance)。
- 21:09:50 结束核查：五个组件恢复 2/2 Ready，公网健康检查通过，Deployment/StatefulSet 配置与镜像不变，其他命名空间未改动，手工准备的空 Session 仍为空。
- 故障 Session 保留用于排查，没有伪造、补写或删除业务结果。
