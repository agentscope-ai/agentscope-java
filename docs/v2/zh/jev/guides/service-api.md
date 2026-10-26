---
title: "在 AgentScope Service 中配置 JEV"
---

使用 session 的 `agentOverrides.jev` 为各用途设置模式、版本和预算。Service 在构建 Harness Agent 时装配对应能力；需要业务数据的能力，还要由宿主在每次运行上下文中提供数据源。

## 场景：只为一个客服会话观察退款防护

现有客服 Agent 已注册订单查询、退款与其他工具。你希望先看 JEV 会保留哪些工具、会不会拒绝尚无证据的退款调用，而不改变当前业务行为。为这个会话分别配置 `tools` 和 `guard` 的 SHADOW 模式，就能沿原流程运行并收集两类判断记录。

JEV 密钥由服务部署环境提供；本页 JSON 只配置用途。工具必须已经由该 Agent 注册，`guardedTools` 中的名称不能凭空创建工具，也不授予退款权限。

## 1. 创建或更新会话配置

```json
{
  "agent": "agt_existing",
  "environmentId": "env_existing",
  "agentOverrides": {
    "jev": {
      "tools": {
        "mode": "SHADOW", "version": "tools-v1", "budgetMillis": 2000,
        "threshold": 0.8, "rejectionThreshold": 0.2, "maxTools": 3
      },
      "guard": {
        "mode": "SHADOW", "version": "guard-v1", "budgetMillis": 2000,
        "threshold": 0.8, "guardedTools": ["refund"]
      }
    }
  }
}
```

替换为已有 Agent 和 Environment 标识，沿用服务认证提交到 `POST /api/sessions`。已有会话通过 `PATCH /api/sessions/{id}` 更新 `agentOverrides.jev`。该子树整体替换，更新时带上需要保留的用途；设为 null 删除子树。

上例的工具选择建议最多保留 3 个可选工具（必要工具另行保留），退款防护仅针对 `refund`；两项预算各为 2 秒。SHADOW 不减少实际工具，也不会阻止退款。先核对建议与业务金标，再单独切换需要接管的用途到 ENFORCE，不必同时开启所有能力。

将用途 mode 改为 OFF 可关闭能力。配置进入 Agent 构建缓存身份，变化在下一次构建时生效，不中断正在运行的调用。全部 OFF 时不构造客户端，也不要求 JEV 密钥。

## 2. 根据场景选择其他用途

| 配置键 | 装配能力 | 需要你提供的内容 |
|---|---|---|
| tools | 工具选择 | 工具目录、阈值、可选数量 |
| guard | 执行前防护 | 受保护工具名、权限策略 |
| routing | 调用级或阶段模型路由 | 模型 allowlist；阶段路由还需运行时目录 Source |
| content | 输入输出内容护栏 | 模式、预算、REVIEW 处理策略 |
| quality | 最终草稿审核与修订 | 非空 criteria 和修订上限 |
| evaluation | Agent 轨迹评估 | 指标和两个阈值；只支持 OFF/SHADOW |
| compaction | 上下文压缩与归档 | 可处理工具、保护范围和阈值 |
| supervision | 长任务观察 | 调度参数；完成复核需 EvidenceSource |
| review | 代码快照评审工具 | 授权快照 Source |
| retrieval | 检索证据工具 | 检索 Source 和 ACL |
| browser | 只读浏览器工具 | 独占会话 Source 和独立验证器 |

各用途完整配置见对应功能页：[轨迹评估](/v2/zh/jev/guides/trace-evaluation-api#service-配置和记录)、[压缩](/v2/zh/jev/guides/context-compaction-api#service-配置)、[监督](/v2/zh/jev/guides/supervision-api#service-配置与追踪)、[代码评审](/v2/zh/jev/guides/code-review-api#service-配置)、[检索](/v2/zh/jev/guides/evidence-pipeline-api#agentscope-service)、[阶段路由](/v2/zh/jev/guides/phase-routing-api#agentscope-service)、[浏览器](/v2/zh/jev/guides/browser-execution-api#agentscope-service)。监督也只支持 OFF/SHADOW。

`routing.models` 为简单模型列表；配置 `routes` 时使用阶段路由，两者不能混用。服务端 `agentscope.jev.allowed-models` 配置允许的模型 ID，未经允许的模型不能通过 session 配置引入。简单列表路径对工具能力采用保守回退；需要完整能力与配额约束时提供阶段路由目录。

## 3. 给同一客服增加回答审核

```json
{
  "jev": {
    "content": {
      "mode": "SHADOW", "version": "content-v1",
      "budgetMillis": 2000, "blockOnReview": false
    },
    "quality": {
      "mode": "SHADOW", "version": "answer-v1",
      "budgetMillis": 10000, "maxRevisions": 1,
      "criteria": [{
        "id": "grounded",
        "instructions": "Is assistant_answer supported by supporting_context?",
        "expected": true, "failThreshold": 0.2, "passThreshold": 0.8
      }]
    }
  }
}
```

上例是 `agentOverrides` 内的片段，阈值仅演示用法。两种用途装配为同一个响应中间件，先检查内容，再审核质量；修订稿再次检查安全。quality 最多 64 条条件，maxRevisions 为 0..10。ENFORCE 未通过不发布文本，SHADOW 不修订、不替换原输出。

默认每轮最多 64,000 文本字符、8,192 个事件和 120 秒。content 预算按每次检查计算，quality 预算包含评审及全部草稿修订。[回答修订](/v2/zh/jev/guides/answer-refinement-api)

## 配置范围与运行身份

密钥来自部署环境，session JSON 不接受 apiKey、baseUrl、endpoint 或任意未知字段。预算范围为 1..30,000 毫秒，每用途独立配置；更细上限见各功能页。非法配置在 Agent 构建时拒绝。

Source、模型对象、浏览器起始页和验证收据不能通过任意 session JSON 注入。按认证后的用户、会话和本次调用在 RuntimeContext 中注册。只读工具也需要通过原有权限链；JEV 不提供 ACL。

## 4. 查询这次运行的判断事件

```text
GET /api/sessions/{id}/events?types=jev.decision
GET /api/sessions/{id}/events/stream
```

事件包含 run_id、purpose、version、mode、status、reason、elapsed_ms、recommendation，沿用会话所有者鉴权。记录关联当次执行身份，不保存模型凭据或完整输入正文。

记录使用 256 项有界队列。队列满或存储失败不阻断 Agent，事件可能晚于运行结束到达，也不保证全局顺序；因此它适合判断观测，不能替代可靠交易审计。[预算与观测](/v2/zh/jev/operations)

## 先验证 API 行为，再接入服务

无需启动 Service，也能先用[JevHarnessScenarios](/v2/zh/jev/guides/agent-integration-example)与[退款防护用例](/examples/jev/tool-guard/README.md.txt)验证相同中间件的选择、拒绝与权限链行为。运行方式和模拟数据随示例提供。

接入 Service 后，再针对上面的会话提交一次“先查订单，未发货再退款”，查询同一 run 的 `tool-selection`、`tool-guard` 决策与实际工具结果（对应配置键 `tools`、`guard`）。SHADOW 记录的拒绝建议不能计作已阻止退款；配置关闭或更新后，应在新调用中检查是否生效。离线中间件测试不能代替你部署环境中的鉴权、Source 和事件存储验证。
