---
title: "快速开始"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

本教程用“整理会议待办”串起云端 Agent、API 发布、代码应用注册和多 Agent 编排。

开始前，你需要一个可登录的 Service 控制台，以及可以创建 Agent 和发布 Endpoint 的账号。管理员需已配置可用模型与 Environment；尚未安装时先按[本地安装](/v2/zh/service/quickstart)或[生产安装](/v2/zh/service/kubernetes) 完成部署。下面的云端 Agent 运行在该 Service 部署中。

## 1. 创建一个云端 Agent

1. 打开 **DESIGN → Agents**，创建“资料助手”。
2. Runtime 选择 **AgentScope Managed**。Model 留空以使用管理员配置的默认模型。
3. 在 Instructions 中填写以下职责，在 Advanced settings 选择可用 Environment，然后保存。

```text
根据用户提供的材料整理待办清单，列出任务、负责人、期限与待确认事项。
区分事实和推测；缺少资料时明确说明，不虚构来源或时间。
```

首次使用只处理文本，完成后再按 [Managed Agent 指南](/v2/zh/service/managed-agent)添加知识、技能和外部工具。

### 快速测试：在控制台发起 Chat 会话

打开 **WORK → Chat → New chat**，选择“资料助手”，发送：

```text
请将以下会议记录整理为待办清单：
周五前完成安装说明，负责人小李。下周一评审，具体时间待确认。
```

检查回复是否包含负责人、期限及“评审时间待确认”，继续追问“还缺少哪些信息？”。刷新后重新打开这个 Chat，确认两轮历史保留。

<Frame caption="Chat 界面示例，使用固定演示数据；实际 Agent 名称和消息以本教程输入为准。">
  <img src="/imgs/service/chat.png" alt="在控制台选择 Agent 并进行多轮对话" />
</Frame>

## 2. 将 Agent 作为 API 服务发布

回到“资料助手”详情，打开 **Connections → Published APIs**，在 **Publish as API** 中点击 **New Endpoint**：

1. Name 填“资料助手 API”，Slug 填 `notes-assistant`，Mode 选择 **Conversation**。
2. 点击 **Create & publish**，确认状态为 `published`，保存生成的 API key。
3. 点击 **Test API** 验证调用；**API integration examples** 提供该发布版本的请求、状态查询与事件订阅示例。

在终端中设置 `BASE_URL` 为 Gateway 地址（末尾不带 `/`），`ENDPOINT_TOKEN` 为刚生成的 API key，再提交一轮对话：

```bash
export BASE_URL='https://YOUR_SERVICE_HOST'
export ENDPOINT_TOKEN='YOUR_ENDPOINT_API_KEY'

curl --fail-with-body "$BASE_URL/invoke/v1/endpoints/notes-assistant/conversations" \
  -H "X-API-Key: $ENDPOINT_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: notes-chat-001' \
  --data '{"message":"整理会议待办：小李周五完成安装说明，下周一评审，时间待确认。"}'
```

接口先返回 `202 Accepted` 和调用标识。下面是响应字段示例，实际 ID 与状态以返回值为准：

```json
{
  "invocationId": "INVOCATION_ID",
  "conversationId": "CONVERSATION_ID",
  "status": "running",
  "statusUrl": "/invoke/v1/conversations/CONVERSATION_ID",
  "eventsUrl": "/invoke/v1/conversations/CONVERSATION_ID/events?invocationId=INVOCATION_ID"
}
```

将响应中的 `eventsUrl` 和 `statusUrl` 分别填入以下变量。提交请求和订阅 SSE 是两个步骤：

```bash
export EVENTS_PATH='PASTE_RETURNED_EVENTS_URL'
export STATUS_PATH='PASTE_RETURNED_STATUS_URL'

curl -N --fail-with-body "$BASE_URL$EVENTS_PATH" \
  -H "X-API-Key: $ENDPOINT_TOKEN" \
  -H 'Accept: text/event-stream'

curl --fail-with-body "$BASE_URL$STATUS_PATH" \
  -H "X-API-Key: $ENDPOINT_TOKEN"
```

这里按返回相对 URL 的形式演示；若返回绝对 URL，直接使用该 URL。订阅时保留 `invocationId` 查询参数。继续多轮对话时向 `/invoke/v1/conversations/{conversationId}/turns` 发送新的 `message`，并使用新的 Idempotency-Key；重传同一请求则复用原 key 和内容。

此时，你已经把控制台中的 Agent 发布成应用可调用的服务。完整契约、认证和错误处理见 [Endpoint](/v2/zh/service/endpoints)。

## 3. 注册 AgentScope 开发的 Agent

已有 AgentScope Java 应用时，可以保留自己的进程并注册为 **External Agent**。以下演示适用于标准 Service 部署的 HTTP 注册路径。

在应用中添加扩展依赖，`agentscope.version` 使用与你的应用一致、已发布的 SDK 版本：

```xml
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-extensions-aistio</artifactId>
  <version>${agentscope.version}</version>
</dependency>
```

准备以下部署环境变量：

| 变量 | 填写内容 |
| --- | --- |
| `AISTIO_CONTROL_HTTP` | 应用可访问的 Service HTTP 注册地址 |
| `AISTIO_BOOTSTRAP_TOKEN` | 管理员提供的受信任 workload/bootstrap 凭据，区别于上一步的 Endpoint API key |
| `AISTIO_TENANT` / `AISTIO_NAMESPACE` | 该应用应注册到的范围 |
| `AISTIO_INSTANCE_KEY` | 当前应用副本的稳定标识；多个副本使用不同值 |
| `AGENT_CONTRACT_URL` | 控制面可回连的应用地址，例如 `http://report-agent:18090` |

把下面片段放到已有 Agent 初始化之后；`agent` 是你已经创建的 Agent 对象：

```java
import io.agentscope.extensions.aistio.Aistio;
import io.agentscope.extensions.aistio.AistioConfig;
import io.agentscope.extensions.aistio.SessionBridge;

SessionBridge bridge = Aistio.instrument(agent,
    AistioConfig.builder("report-service")
        .controlPlaneHttp(System.getenv("AISTIO_CONTROL_HTTP"))
        .internalToken(System.getenv("AISTIO_BOOTSTRAP_TOKEN"))
        .tenant(System.getenv("AISTIO_TENANT"))
        .namespace(System.getenv("AISTIO_NAMESPACE"))
        .instanceKey(System.getenv("AISTIO_INSTANCE_KEY"))
        .contractHttpPort(18090)
        .publicBaseUrl(System.getenv("AGENT_CONTRACT_URL"))
        .startHttpRegister(true)
        .startGrpc(false)
        .build());
// 应用退出时调用 bridge.close()。
```

启动应用，确认 **DESIGN → Agents** 出现 `report-service`，检查实例及合约地址。从应用自身运行一轮对话，再检查平台能否读取适配器提供的会话信息。应用到 Service、控制面到应用的两个方向都必须可达。

这一步完成注册和会话合约接入。接受 Issue/Team 派发还需接入 `AgentTaskStarter` 等任务执行入口；该片段不自动获得任务派发或实时事件流能力。Python 接入及 ASDP 的部署要求见 [External Agent](/v2/zh/service/external-agent)。下一步先用已验证的 Managed Agent 完成编排，再逐个加入具备任务能力的 External 或 Hosted 成员。

## 4. 将 Agent 编排在一起

先用 Team 完成一次动态协作：复用“资料助手”，再按第 1 步创建一个 Managed“复核助手”，其 Instructions 为“检查材料是否支持每项结论，指出缺失的负责人、期限和待确认信息”。

在 **DESIGN → Teams** 新建“会议整理团队”，Leader Agent 选择“资料助手”，Additional members 添加“复核助手”。在协作指令中填写：

```text
Leader 先整理待办，再委派复核助手检查事实与缺失项。
复核助手返回需要修正的内容；Leader 修订后交付一份统一清单。
没有依据的内容标为待确认，不补造事实。成员完成各自任务后，由 Leader 汇总并完成团队工作。
```

保存并检查 Team 就绪信息。需要固定“整理 → 审批 → 汇总”等步骤时，改用 [Workflow](/v2/zh/service/workflows)：配置节点和依赖，校验并发布 revision，再进行下面的发布或运行。

### 发布为标准 Agent 服务：使用相同的 SSE 订阅方式

在 Team 的 **Connections → Publish as API → New Endpoint** 创建 `meeting-team`，点击 **Create & publish** 并保存这个 Endpoint 自己的 API key。Team 使用 **Job** 模式；Workflow 需先发布 revision，再从该版本发布 Job Endpoint。

```bash
export ENDPOINT_TOKEN='YOUR_TEAM_ENDPOINT_API_KEY'

curl --fail-with-body "$BASE_URL/invoke/v1/endpoints/meeting-team/jobs" \
  -H "X-API-Key: $ENDPOINT_TOKEN" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: meeting-team-001' \
  --data '{"title":"整理会议待办","description":"小李周五完成安装说明，下周一评审，时间待确认。请整理并复核待办，交付统一清单。","input":{}}'
```

与单 Agent 一样，调用方先提交请求，再使用响应中的 `eventsUrl` 订阅 `text/event-stream`，使用 `statusUrl` 查询状态。复用第 2 步的 SSE 命令，替换本次 URL 和 Team API key 即可。

**相同的是 Endpoint 的认证、提交后订阅 SSE 的接入方式。** Conversation 提供一轮会话的事件，Job 提供编排运行事件；二者的请求体、事件内容和结果语义不同。若希望单 Agent 与 Team/Workflow 都采用相同的 Job 契约，也可以将单 Agent 发布为 Job Endpoint。

Job 达到 `completed` 后，从状态响应的 `invocation.result` 读取结果；`failed`、`cancelled` 或 `timed_out` 按失败处理。`202 Accepted` 仅表示请求被接受，SSE 断开后可继续通过状态接口确认执行情况。

### 在控制台通过 Issue 处理

1. 打开 **WORK → Issues** 创建“整理会议待办”，在说明中写入上述材料和验收要求：清单包含任务、负责人、期限及待确认事项。
2. 负责人选择“会议整理团队”，检查共享范围后提交工作。也可以从 Chat 的 **Create issue** 开始，再选择 Team。
3. 查看讨论、Task map 和 Executions，确认 Leader 委派复核、成员提交结果，并最终汇总。读取评论和 Artifact 中的实际交付物。
4. 使用人工验收策略时，在工作进入 **In review** 后打开 **WORK → Inbox → Review result**。满足要求选择 **Accept result**；需要补充则选择 **Request changes** 并说明缺失项。

若没有开始执行，先检查 Team 就绪信息、Leader 和成员的运行时；若成员已完成但团队仍未结束，查看 Leader 的汇总与协调状态。更多用法见 [Team 协作](/v2/zh/service/team-collaboration)与 [Issue 指南](/v2/zh/service/issues)。
