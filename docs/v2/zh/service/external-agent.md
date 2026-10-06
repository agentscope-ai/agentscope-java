---
title: "External Agent：概览与接入用法"
en_link: /v2/en/service/external-agent
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

External Agent 保留你的应用进程、框架和部署方式，同时接入统一目录、会话诊断与工作协作。它不是由 Service 启动的 Managed Agent，也不要求把应用改造成 Runtime Host provider。

首次使用请先按[操作指南](/v2/zh/service/register-agentscope-agent)完成创建或接入。本分类集中提供详细配置、支持能力和工作原理。

## 本章节

- [注册与连接参数](/v2/zh/service/external-agent-configuration)
- [支持框架与自定义适配](/v2/zh/service/external-agent-frameworks)
- [工作原理与任务派发](/v2/zh/service/external-agent-execution)

## 先选择接入路径

| 路径 | 网络前提 | 适合的能力 |
| --- | --- | --- |
| Java HTTP contract | 应用可访问注册 API；控制面可回连应用合约地址 | 注册、合约查询及适配器实现的命令 |
| ASDP 框架接入 | 上述 HTTP 连通性，加上可达的 ASDP gRPC listener | 实时事件上报及适配器实现的 ExecutionAttempt 派发 |

Python 现在可通过出站 HTTP 接入标准 Service：使用 `control_plane_http=base, transport="http"`（默认值），无需对外暴露 ASDP gRPC 端口或 worker 入站端口。Agent、Team 和 Workflow 可执行接入见[统一服务 API 示例](/v2/zh/service/service-api#可运行示例与客户端)。下方 Python 示例使用默认 HTTP 运行传输。

选择接入方式后，区分三个验收层次：目录可见、会话可用、可接受工作派发。只有观察能力的应用不会因为注册成功就自动拥有任务执行能力。

## Java：添加 HTTP 注册与合约

在应用 Maven 配置中添加正式发布的版本：

```xml
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-extensions-aistio</artifactId>
  <version>${agentscope.version}</version>
</dependency>
```

下面是已有应用中的接入片段；`agent` 是你已创建的 Agent。环境变量由部署者提供，`AGENT_CONTRACT_URL` 必须能从控制面访问：

```java
import io.agentscope.extensions.aistio.Aistio;
import io.agentscope.extensions.aistio.AistioConfig;
import io.agentscope.extensions.aistio.SessionBridge;

SessionBridge bridge = Aistio.instrument(agent,
    AistioConfig.builder("report-service")
        .controlPlaneHttp(System.getenv("AISTIO_CONTROL_HTTP"))
        .registrationCredential(System.getenv("AISTIO_REGISTRATION_CREDENTIAL"))
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

先按[注册指南](/v2/zh/service/register-agentscope-agent)取得 `registrationCredential`，将其设置为 `AISTIO_REGISTRATION_CREDENTIAL`。当前 Java bridge 在未配置注册凭据或 bootstrap 参数时会跳过自动注册。当前预览版本的服务端注册入口不验证调用者身份，应限制在受控网络或网关内使用。返回的 registration credential 用于后续运行连接，不能替代注册入口的访问控制。HTTP contract 可读到的历史与命令取决于适配器；Java 实时上报还需在构建 Agent 时装入适配器 middleware，并启用 ASDP 运行连接。

<span id="python连接支持-asdp-的部署" />

## Python：使用 HTTP 运行连接

在应用环境安装 `aistio-sdk` 的对应发布版本：

```bash
python -m pip install "aistio-sdk==$AISTIO_SDK_VERSION"
```

下列片段中的 `target` 是已有框架对象，支持的适配器包括 AgentScope、OpenAI Agents、LangChain、ADK 等；实际可用方法以适配器能力为准。

```python
import os
import aistio

bridge = aistio.instrument(
    target,
    agent_key="report-service",
    instance_key=os.environ["AISTIO_INSTANCE_KEY"],
    tenant=os.environ["AISTIO_TENANT"],
    namespace=os.environ["AISTIO_NAMESPACE"],
    transport="http",
    control_plane_http=os.environ["AISTIO_CONTROL_HTTP"],
    contract_http_port=18090,
    contract_http_base_url=os.environ["AGENT_CONTRACT_URL"],
    event_journal_dir="/var/lib/report-agent/events",
)
# 应用退出时调用 bridge.stop()。
```

Python 默认使用出站 HTTP exchange（`POST /api/v1/agent-runtime/exchange`），无需开启 gRPC listener。需要 gRPC 时设置 `transport="grpc"` 和 `control_plane="host:port"`。每个副本使用不同 instance key，同一副本重启保持身份稳定，并保存 event journal。自动识别的观测适配器不会执行 Issue/Team 工作；接收任务时，通过 `adapter=` 显式选择 SDK 的 `AsyncInvokeAdapter`、`AgentScopeRunnerAdapter` 或 `ExecutableAdapter`，也可以自行实现任务入口，详见[适配器选择](/v2/zh/service/external-agent-frameworks)。

## 接受 Issue / Team 工作

任务适配器需要实现真实的执行入口，例如 Python `FrameworkAdapter.handle_agent_task`，Java `AgentTaskStarter`。应用为每次 Attempt 建立隔离执行，读取注入的工作上下文，使用任务范围凭据发表评论和上传 Artifact，并按协议上报成功、失败或取消。

不要把“发出最终消息”代替 Attempt 完成，也不要用过期 generation 的凭据回报另一次执行。Coordinator 还需要显式完成或失败对应 Run node；详见 [Team 协作](/v2/zh/service/team-collaboration)。

## 自定义适配器与验收

Python 可实现 `FrameworkAdapter` 并通过 `adapter=` 传入，按需要扩展 context、messages、commands 和 task execution；只声明实际实现的能力。Java 使用对应 `FrameworkAdapter` 与 `AgentScopeAdapter` 扩展点。

依次验证：注册与重启身份、应用侧一轮对话、历史读取、控制面可达的合约、一次支持的工作派发、失败和取消回报。容器 `localhost`、只有单向网络、错误 gRPC 端口和虚报 capability 是常见接入问题。

相关：[SDK 与组件选择](/v2/zh/service/integrations) · [API 参考](/v2/zh/service/api-reference)。

## API 入口与身份

业务管理使用平台账户 Bearer，运行连接使用注册所得的实例身份；发布给业务应用的 Endpoint 则使用对应调用凭据。三者不应混用。

| 操作 | API 与关键参数 | 响应 |
| --- | --- | --- |
| 登记 Agent 与副本 | `POST /api/v1/agent-registrations`，`agentKey`、`instanceKey`、范围、`routingKey`、`capabilities` | `agent`、`binding`、`instance`、`registrationCredential` |
| 查询逻辑 Agent | `GET /api/v1/agents/{agentId}` | `agent` |
| 查询绑定与副本 | `GET /api/v1/agents/{agentId}/bindings`、`/instances` | `items` |
| 查询已上报目录 | `GET /api/v1/agents/{agentId}/runtime-inventory` | `status`、`items`；未上报时为 `not_reporting` |
| 更新生命周期 | `PATCH /api/v1/agents/{agentId}`，当前 `version` 与需要更新的 `status` 等字段 | 更新后的 `agent` |
| 轮换/撤销运行注册凭据 | `POST /api/v1/agent-registrations/{agentId}/credentials/rotate`；`DELETE /api/v1/agent-registrations/{agentId}/credentials/{credentialId}` | 轮换返回新凭据；撤销返回 204 |

完整字段和 SDK 开关见[注册与连接参数](/v2/zh/service/external-agent-configuration)。完成任务适配后，通过 [Issue API](/v2/zh/service/issues) 分派工作，或发布 [Endpoint](/v2/zh/service/endpoints)，让应用通过统一 [Agent API](/v2/zh/service/service-api) 获取结果与 SSE 事件。
