---
title: "交互式助手：在业务页面中连续查询与确认"
description: "通过 Conversation 保留上下文，每轮用 Invocation 跟踪查询、人工交互和结果。"
en_link: /v2/en/service/cases/business-assistant
---

订单页面中的助手先解释延迟，再根据用户补充决定是否创建售后工单。产品保留聊天 UI、登录身份与订单权限，Service 承接连续会话及工具执行。这个案例使用一个具备会话能力的 Agent，不需要 Team。

## 1. 准备用户与业务数据

下载[首轮请求](/examples/service/business-assistant/input.json.txt)为 `input.json`，下载[虚构订单数据](/examples/service/business-assistant/orders.json.txt)为业务工具测试资料。数据不是已运行的订单接口。

| 用户 | 可读取订单 | 固定事实 |
| --- | --- | --- |
| alice | O-1001 | 待库存；无法保证明天送达 |
| bob | O-2001 | 已发货；不能据此推断保证到达时间 |

业务后端实现查询工具，以经过认证的用户身份校验订单归属；工单工具在授权确认后创建并返回真实工单 ID。用户在消息里声称自己是 alice 不能取得 alice 的权限。没有接入工单工具时，只能演示查询和建议，不能声称已创建工单。

## 2. 发布对话服务

按[Endpoint 指南](/v2/zh/service/endpoints)将会话能力单 Agent 发布为 conversation 模式 `order-assistant`。指令要求依据真实工具结果解释订单，不承诺资料不支持的送达时间，并在创建工单前确认。

业务后端保存 Application key，至少需要 invoke/read；补充输入、待办处理及取消按实际需要增加 scope。平台指定审批人的工具确认仍需相应人类身份，interact scope 本身不能代替批准。准备 Bash、curl、jq、BASE_URL 和 ENDPOINT_KEY。

## 3. 创建首轮并保存会话关联

```bash
RECEIPT=$(curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/endpoints/order-assistant/conversations" \
  -H "X-API-Key: $ENDPOINT_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: alice-order-1001-turn-1' \
  --data-binary @input.json)
CONVERSATION_ID=$(printf '%s' "$RECEIPT" | jq -er '.conversationId')
INVOCATION_ID=$(printf '%s' "$RECEIPT" | jq -er '.invocationId')
```

应用数据库保存“已登录用户 → Conversation → 每轮 Invocation”。这只是应用关联，不能替代每次请求的授权检查。共享 Application 的不同 key 仍然共享调用归属。

从 Invocation snapshot 恢复消息与待办，再订阅事件。浏览器通过业务后端代理访问；不要把长期 key 直接放进网页。

## 4. 区分下一轮与当前待办

如果首轮以普通回复结束，用户确认创建工单时，提交下一轮：

```bash
curl --fail-with-body -sS \
  "$BASE_URL/invoke/v1/conversations/$CONVERSATION_ID/turns" \
  -H "X-API-Key: $ENDPOINT_KEY" -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: alice-order-1001-turn-2' \
  --data '{"message":"请为这笔订单创建售后工单，记录无法保证明天送达。"}'
```

如果当前 Invocation 尚在等待 required action，则按返回的 request_id、expected_version 和类型答复当前 action，不能用新 turn 替代。原生确认、外部工具结果与 Workflow action 的 payload 不同，见[交互协议](/v2/zh/service/service-api#回答待办补充输入与取消)。

同一 Conversation 同时只能有一个活动 Invocation。前端禁用重复发送，或在应用队列中等待；仅按 capabilities 开放执行中输入。需要人工客服时，由业务应用创建转接记录并决定暂停或结束 Agent 工作。

## 5. 验收与中断处理

| 测试 | 合格条件 |
| --- | --- |
| alice 查询 O-1001 | 返回待库存，明确不能保证明天送达 |
| alice 请求 O-2001 | 工具与应用拒绝越权，不仅靠模型提示 |
| 首轮后继续对话 | Conversation 不变，第二轮 Invocation 不同，保留上下文 |
| 页面在待办时刷新 | 恢复同一问题，不重复提交工具操作 |
| 工单创建 | 只在获授权后执行，展示可核对的实际工单 ID |
| 网络重发 | 原 key 重放，不重复创建逻辑轮次；工单系统另做业务幂等 |
| 取消 | 先显示取消请求，等确认终态；已有业务写入仍需核对 |

固定数据可用于工具权限测试；完整效果仍需真实 Agent、工具和 UI 验证。
