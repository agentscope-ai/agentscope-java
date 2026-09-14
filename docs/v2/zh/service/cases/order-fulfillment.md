---
title: "企业业务：订单履约异常处理"
description: "将多个 AgentScope 应用组成 External Team，跨订单、库存、物流和售后系统协同处置。"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

客服收到“订单迟迟未发货，希望明天收到”的请求。订单、库存、物流和售后 Agent 分别查证，由履约协调 Agent 组织处置，再核对业务系统中的真实结果。这类流程能减少人工跨系统查询与转派，也能把原因、决策和执行记录留在同一项工作中。

本案例的五个 Agent 都用 **AgentScope Java** 开发并注册为 External Agent，包括 Leader。每个应用独立部署，Service 负责目录、派发、团队协作与执行观察；企业系统仍负责业务规则和数据。

## 准备业务资料与接入环境

下载[固定业务数据](/examples/service/order-fulfillment/business-data.json.txt)，保存为 `business-data.json`。它包含三张虚构订单、库存、物流和政策，用于开发工具与核对结果，不是已部署的 OMS/WMS/TMS 服务，也不包含完整 Agent 应用。

准备 Java 17+、配套版本的 AgentScope SDK 与 `agentscope-extensions-aistio`、可用模型、Service 地址，以及允许注册到目标 Namespace 的应用凭据。标准 Service 部署优先采用 [Java HTTP 注册与合约](/v2/zh/service/external-agent#java添加-http-注册与合约)。

先让应用工具从固定数据读取，完成单任务验证，再接入真实 OMS、WMS、TMS 与售后 API。所有角色收到的订单号要在业务系统按调用者授权校验；请求中的 `customerId` 本身不是身份凭据。

## 1. 开发五个业务 Agent

| 应用 / Agent key | 工具职责 | 结果要求 |
| --- | --- | --- |
| 协调 / `fulfillment-lead` | 组织调查、比较方案、按阶段委派 | 统一结论、证据索引、执行与待办状态 |
| 订单 / `order-agent` | 查询订单和版本；执行允许的订单变更 | 状态、承诺日期、变更限制 |
| 库存 / `inventory-agent` | 查询各仓可用量和调拨条件 | 可用数量、仓库、容量或有效期 |
| 物流 / `logistics-agent` | 查询运输节点和候选时效 | 已观察事件、预计时间、是否有保证 |
| 售后 / `after-sales-agent` | 查询政策、创建并查询处理单 | 政策依据、处理单号、操作结果 |

将工具实现为 AgentScope Toolkit 中的业务函数，再交给各应用的 Agent。下面是**示例应用工具契约**，不是 Service 内置 API；工具名和返回字段由你在应用中实现：

| 工具 | 输入 | 必须返回 / 校验 |
| --- | --- | --- |
| `get_order` | `orderId` | 订单状态、`version`、来源与查询时间 |
| `get_inventory` | `sku`、数量 | 各仓可用量与约束，不把查询结果当作已锁库存 |
| `get_logistics` | `orderId` | 运单或无运单事实、时效依据 |
| `get_policy` | 订单与拟执行动作 | 是否允许、是否需审批、政策版本 |
| `create_resolution` | 订单、动作、预期版本、批准记录、幂等键 | 在服务端验证授权和版本，返回真实处理单号 |
| `get_resolution` | 处理单号 | 查询执行结果，区分已受理与已完成 |

诊断阶段只使用读取工具。创建处理单或变更订单时，业务服务必须检查批准记录、订单版本和动作内容；不能把模型输出“approved”当作授权，也不能以生成的自然语言编号充当真实处理单。

## 2. 接入任务能力

每个 Agent 使用独立 `agentKey`，副本使用稳定且不同的 `instanceKey`。在现有应用的初始化代码中接入如下片段：

```java
import io.agentscope.extensions.aistio.Aistio;
import io.agentscope.extensions.aistio.SessionBridge;
import io.agentscope.extensions.aistio.adapter.AgentScopeAdapter;
import io.agentscope.extensions.aistio.adapter.HarnessAgentTaskStarter;
import io.agentscope.extensions.aistio.transport.CollaborationClient;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;

AgentScopeAdapter adapter = new AgentScopeAdapter();
adapter.setAgentTaskStarter(new HarnessAgentTaskStarter(
    taskAgentFactory,
    new CollaborationClient(new ControlPlaneHttpClient(controlUrl, serviceToken))));
SessionBridge bridge = Aistio.instrument(catalogAgent, config, adapter);
// 应用退出时关闭 bridge，并释放应用持有的 Agent 和执行资源。
```

这是接入片段：`catalogAgent` 是应用的目录 Agent；`taskAgentFactory` 是返回具备该角色模型、工具、指令和隔离上下文的 `Supplier<HarnessAgent>`；`config` 按[注册指南](/v2/zh/service/register-agentscope-agent)配置 HTTP 地址、范围和凭据。`controlUrl` 与 `serviceToken` 由管理员提供给可信应用后端，不传给 Endpoint 调用方。

`HarnessAgentTaskStarter` 负责接入任务上下文、协作动作与结果回传。业务工具仍需自行实现；每次执行不能共享可变的对话状态。Leader 还必须正确使用可用的委派与协调节点完成能力。先分别验证成功、失败、取消与并发隔离，再组 Team。仅 `Aistio.instrument()` 注册成功不代表具备这些能力，详见[External 任务派发](/v2/zh/service/external-agent-execution)。

## 3. 创建履约 Team

在 **DESIGN → Agents** 确认五个应用的 External Binding、在线实例与任务能力，再到 **DESIGN → Teams** 选择 `fulfillment-lead` 为 Leader，添加另外四个成员。

团队 Instructions：

```text
先明确订单与客户诉求，按需委派订单、库存、物流和售后成员调查。
所有事实注明业务来源、版本或查询时间；区分预计和已确认。
diagnose 阶段只提出方案，不修改订单、不创建处理单。
execute 阶段仅执行已批准的确切方案，并重新检查版本、库存与时效。
业务动作后查询真实结果。失败时报告已完成和未完成部分，不重复写入。
统一输出原因、证据、方案、审批需求、执行结果和待办，再完成团队协调。
```

在控制台新建 Issue，输入样例订单 `O-1001`，请求在 `2026-09-15` 到货，阶段为 `diagnose`。说明中要求使用固定数据，并禁止把演示日期当作今天。

## 4. 验证一次异常调查

本例正确的调查结果应包含：

- 订单 `O-1001` 为 `awaiting_stock`，原仓 `W-A` 可用库存为 0。
- `W-B` 有 8 件，但查询库存不代表已预留或已完成换仓。
- 尚无运单；候选最早到货为 `2026-09-16`，且不保证该日期。
- 客户要求的 `2026-09-15` 无法根据现有证据承诺，换仓需要审批。
- 输出方案与待确认事项；诊断运行结束后没有产生业务变更。

固定数据中的 `O-1002` 已发货，团队应检查运输进度；`O-1003` 已取消，不能沿用换仓方案。再测试未知订单、数据不一致和业务接口不可用，检查是否说明原因或转人工。

## 5. 发布给在线业务调用

在 Team 的 **Connections** 发布 Job Endpoint，slug 为 `order-triage`。设置输入 schema 接受以下业务字段；`phase` 在此诊断入口应限制为 `diagnose`，不要允许调用方自行切换到执行阶段。

```json
{
  "type": "object",
  "properties": {
    "orderId": {"type": "string"},
    "requestedArrival": {"type": "string"},
    "phase": {"type": "string", "enum": ["diagnose"]}
  },
  "required": ["orderId", "requestedArrival", "phase"],
  "additionalProperties": false
}
```

设置 `BASE_URL` 为 Gateway origin，`ENDPOINT_TOKEN` 为该 Endpoint 的 key，业务后台发起：

```bash
curl --fail-with-body "$BASE_URL/invoke/v1/endpoints/order-triage/jobs"   -H "X-API-Key: $ENDPOINT_TOKEN"   -H 'Content-Type: application/json'   -H 'Idempotency-Key: order-O-1001-triage-001'   --data '{"title":"Investigate O-1001","description":"Investigate the delay using the fixture. Propose a resolution without executing changes.","input":{"orderId":"O-1001","requestedArrival":"2026-09-15","phase":"diagnose"}}'
```

保存 `invocationId`、`eventsUrl` 和 `statusUrl`，按[SSE 指南](/v2/zh/service/sse-events)展示调查进度，结束后读取 `invocation.result`。该字段中的业务结构由输出契约约定；不要把每一条模型回复直接当作最终客户答复。Endpoint key 放在业务后端，由业务系统补充并校验客户授权上下文。

## 6. 审批后执行并核对结果

完整闭环可用 Workflow 连接 **诊断 Team → approval → 执行 Team**。两个 Team 节点可以使用同一团队，但输入阶段和工具授权不同：

1. 诊断输出具体方案，包括订单版本、拟执行动作及依据；先约定结构化输出，再在设计器映射实际字段。
2. approval 展示该方案，指定审批人。拒绝时结束本次处置并保留原因，不执行变更。
3. 执行阶段读取批准记录并重新查询业务状态。订单版本或关键条件改变时返回重新诊断，不能执行过期方案。
4. 用稳定业务幂等键创建处理单，查询真实处理状态，再汇总结果。创建成功但执行失败时明确部分完成。

按[Workflow 指南](/v2/zh/service/workflows)校验并发布具体 revision，再单独发布完整流程 Endpoint。Endpoint Job 的自动完成不等于业务审批，SSE 断线也不应导致新的业务写入。

## 验收与接入真实系统

| 验收项 | 证据 |
| --- | --- |
| 调查正确 | 订单、库存、物流和政策来源可追溯 |
| 批准与动作一致 | 动作、对象、版本与批准记录匹配 |
| 幂等有效 | 重传同一请求不重复创建处理单 |
| 状态真实 | 业务查询确认处理单与订单状态，不能只看 Agent 文字 |
| 故障可恢复 | 超时后先查询已有动作；保留错误和部分结果 |
| 跨请求隔离 | 不同客户/订单不共享对话状态或越权读取 |

对接真实系统时保留这些工具契约和验收条件，更换数据源、认证及业务校验。结束演练后禁用练习 Endpoint，处理未完成运行并清理模拟业务记录。样例数据和 SDK 接入片段不构成已完成真实企业系统联调的证明。
