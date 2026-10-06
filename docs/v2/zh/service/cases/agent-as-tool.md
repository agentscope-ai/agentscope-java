---
title: "专业 Agent 服务：供上层 Agent 按需调用"
description: "把专业任务包装成异步工具，传递 Invocation 句柄、结果与授权。"
en_link: /v2/en/service/cases/agent-as-tool
---

采购助手负责理解需求，调用一个“供应商资料核验”服务，得到事实、缺失证据和待追问事项后继续自己的工作。专业服务独立发布，可被业务应用或其他 Agent 复用。

这里的上层工具包装由应用实现；Service 提供 Endpoint 和 Invocation。它不要求服务内部先组成 Team，也不假定 Endpoint 已自动发布为公共 MCP 工具。

## 1. 固定专业服务的输入与边界

下载[请求样例](/examples/service/agent-as-tool/input.json.txt)为 `input.json`。虚构供应商 V-101 的资料包含：

| 来源 | 已知内容 | 允许得出的结论 |
| --- | --- | --- |
| profile / v1 | 自述有 12 名员工，提供网页支持 | 可引用为自述事实 |
| review / v1 | 未提供独立安全审查或交付 SLA | 标记缺失证据，不能推断已通过审查 |

目标是返回 confirmed_facts、missing_evidence、questions。字段是业务输出约定，不是平台保留结构。服务不得批准采购，也不能把“上层 Agent 说已授权”当成访问凭据。

## 2. 发布专业能力

为单 Agent 配置只读资料核验指令和必要工具，按[发布指南](/v2/zh/service/endpoints)创建 job Endpoint `supplier-review`。输入 schema 接受 request、supplier_id、sources；检查实际输出后配置 resultMapping 和 outputSchema。结果必须明确区分自述、独立证据与缺失信息。

为上层应用准备独立的调用权限、超时及预算。Bash、curl、jq、BASE_URL 与 ENDPOINT_KEY 配置后，可先独立验证一次：

```bash
RECEIPT=$(curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/endpoints/supplier-review/jobs" \
  -H "X-API-Key: $ENDPOINT_KEY" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: parent-task-301-supplier-101-call-1' \
  --data-binary @input.json)
INVOCATION_ID=$(printf '%s' "$RECEIPT" | jq -er '.invocationId')
curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/invocations/$INVOCATION_ID" \
  -H "X-API-Key: $ENDPOINT_KEY"
```

## 3. 将 HTTP 调用包装成异步工具

不要让上层工具一直阻塞到长任务完成。建议由适配器提供以下**自定义工具契约**：

| 工具 | 适配器职责 |
| --- | --- |
| start_supplier_review | 验证用户与供应商权限，提交 Job，返回 Invocation ID 作为 task_id |
| get_supplier_review | 校验调用归属，查询状态、结果与证据 |
| answer_supplier_review | 按实际 action 类型和批准人权限答复待办 |
| cancel_supplier_review | 检查 capability，提交取消并追踪实际终态 |

应用持久记录“上层任务 / 工具调用 → Invocation”，以原工具调用标识生成幂等键。JSON 示例返回 `{"task_id":"实际 Invocation ID","state":"accepted"}` 只是适配器设计，不是新的 Service 路由。

上层在 accepted、running、waiting 时安排稍后查询或由回调唤醒；在 completed 时读取结果；在 partial_succeeded 时展示缺失分支。failed、cancelled、timed_out 都要显式交给上层决策，不能变成空结果继续推理。

## 4. 传递身份与控制

后端保存 key，按已认证用户与原任务检查每次操作。不要把 key、任意下载链接或其他用户的任务句柄交给模型。需要用户批准时展示来源与影响，并使用实际批准人的身份；上层 Agent 自行生成的“approved”文本无效。

为嵌套调用限制深度、次数与累计成本。父任务取消后，由适配器按记录调用下游 cancel，再核对终态；当前公共调用不自动为任意应用建立父子 Invocation 取消与预算关系。

如果需要 MCP，应用将以上工具暴露为自己的 MCP 服务；Service 内部协作 MCP 不等于 Endpoint 自动导出。也可以先直接使用 HTTP 工具。

## 5. 验收与失败分支

| 测试 | 合格条件 |
| --- | --- |
| 同一上层工具调用重发 | 得到同一 Invocation，不重复执行 |
| 固定供应商资料 | 12 人明确为自述；审查和 SLA 标记缺失 |
| 下游需要人工输入 | 上层保留任务句柄，等待有效答复 |
| 下游失败或部分完成 | 保留错误与可用证据，不编造专业结论 |
| 其他用户提供 task_id | 应用拒绝读取和操作 |
| 父任务取消或超时 | 显式传播取消；未确认停止前不显示已取消 |
| 递归调用 | 应用限制深度、次数和预算 |

样例验证专业服务的输入与预期判断。上层工具适配器、MCP 服务及采购系统均需自行实现并实跑；输出是决策资料，采购审批仍在业务流程中。
