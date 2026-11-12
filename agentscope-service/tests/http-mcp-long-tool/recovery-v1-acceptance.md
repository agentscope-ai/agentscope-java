# MCP 恢复扩展 v1 集群验收

[English](recovery-v1-acceptance.en.md)

2026-10-19 北京时间 22:39:33 验收通过。此前 Worker 故障造成在途结果无法找回的问题，已通过
[执行恢复协议](../../docs/mcp-execution-recovery.md)及 MCP 服务端共享持久化实现补齐。
[首次失败记录](dual-replica-recovery.md)保留作对照，没有改写历史结果。

## 部署

- 代码位置：`/Users/ken/agentcore/agentcore-managed-agents`，`master` 分支。
- 本次按用户确认，包含工作区最新 Runtime/MQ 改造；没有修改 AgentScope Java 原项目或二进制依赖。
- 业务镜像：`2.1.0-build.20261019-213410-7a94c5bc`，ACR 命名空间 `agentcore`，均包含 amd64/arm64。
- 集群命名空间 `agencore-ma`，Helm release `ma` revision 9；五个组件各两个副本。
- HTTP MCP 同样两个副本，镜像固定为 `sha256:a66b99e8cf06b862e67d0a87cdf364ad39b787e6e13a2fbe35f54478dd00be3d`。
- 测试 MCP 使用独立的 `mcp_fixture.execution` 表及 DML 账号；平台 SQL 基线、表名、MQ 组名保持不变，没有引入 Sandbox 或 NAS。

## 验收结果

| 场景 | 结果 | 物理工具开始/完成 | 最终状态 |
| --- | --- | --- | --- |
| 真实 90 秒工具、Suspend/Resume、注入、T2 排队 | 通过 | 1 / 1 | T1、T2 completed |
| 删除实际发起调用的 Worker Pod | 通过 | 1 / 1 | 接管查询原结果后，T1、T2 completed |
| 删除原 Runtime Pod | 通过 | 1 / 1 | 其他实例恢复 T1，T2 随后 completed |
| interrupt + follow-up，保留排队 T2 | 通过 | 不在该脚本的物理计数断言范围 | T1 cancelled；T2、T3 completed |

Worker 故障 Session 为 `bdd31f70-49f2-502b-ab3e-b89df30a3c82`。
删除 `ma-managed-agents-tool-worker-5b4cdf6f7d-fndq9` 后，
`ma-managed-agents-tool-worker-5b4cdf6f7d-jd57x` 通过查询取得原执行的 SUCCEEDED 结果。
同一 task_id / execution_id 被保留，原始 call_id 为 `cc8493a2-295d-4128-bee7-d6d636ed91d6`。
数据库核对到一次 Suspension、一次 resumed、一次 Resume 通知，注入输入最终 applied；
两轮最终输出引用同一真实 call_id，T1 完成事件早于 T2 运行事件。

Runtime 故障 Session 为 `a03431d2-8b84-5951-8e6a-d1616cbfb978`，
由 `ma-managed-agents-agent-harness-runtime-fdbc6df77-8m5l4` 恢复原 T1。
故障注入均在真实 MCP 已启动、T1 waiting_tool / T2 accepted 时进行，删除的是实际任务发起 Pod，使用一秒终止宽限期。

## 检查与证据

- Maven clean verify：724 项，567 通过、157 按环境条件跳过，零失败/错误；含恢复、错误标识、查询失败、失败/取消结果及键顺序回归。
- Go 测试通过；控制台 189 项、发布工具 43 项、双进程共享 PostgreSQL 的真实 HTTP 检查 7 项均通过。
- [汇总与镜像摘要](../../release/dist/mcp-recovery-deploy/summary.json)。
- [基线](../../release/dist/mcp-recovery-deploy/baseline/report.json)、[Worker 故障](../../release/dist/mcp-recovery-deploy/worker-restart/report.json)、[Runtime 故障](../../release/dist/mcp-recovery-deploy/runtime-restart/report.json)、[中断](../../release/dist/mcp-recovery-deploy/interrupt/report.json)。
- 证据在本地忽略目录内，含 API 输出、MCP 物理记录、原 Pod 标识、接管日志、数据库事实和发布清单。
- 结束时五个组件均 2/2 Ready，各 Service 有两个就绪后端；独立 MCP 为两副本，公网健康检查通过。
  基础设施及其他命名空间的 Deployment/StatefulSet 配置未改动，手工准备的空 Session 仍为空。

## 范围

恢复能力需要 MCP 服务明确声明并实现持久化、稳定标识幂等受理和结果查询协议。
未支持该协议的普通 MCP，以及此前没有携带恢复标识的旧执行，继续保留不确定结果保护，不自动重放。
本轮验证 Worker/Runtime Pod 故障，不覆盖 MCP 执行进程或 PostgreSQL 自身故障。
