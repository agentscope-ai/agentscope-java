---
title: "External SDK 与运行协议"
en_link: /v2/en/service/external-agent
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

External Agent 保留你的应用进程、框架和部署方式，同时接入统一目录、会话诊断与工作协作。它不是由 Service 启动的 Managed Agent，也不要求把应用改造成 Runtime Host provider。

先按[注册指南](/v2/zh/service/register-agentscope-agent)跑通接入。本页按 SDK、参数和执行协议集中说明配置，供实现或排查适配器时查询。


<span id="本章节"></span>
<span id="接受-issue--team-工作"></span>
<span id="自定义适配器与验收"></span>
<span id="api-入口与身份"></span>

## 先选择接入路径

| 路径 | 网络前提 | 适合的能力 |
| --- | --- | --- |
| Java HTTP contract | 应用可访问注册 API；控制面可回连应用合约地址 | 注册、合约查询及适配器实现的命令 |
| ASDP 框架接入 | 上述 HTTP 连通性，加上可达的 ASDP gRPC listener | 实时事件上报及适配器实现的 ExecutionAttempt 派发 |

Python 现在可通过出站 HTTP 接入标准 Service：使用 `control_plane_http=base, transport="http"`（默认值），无需对外暴露 ASDP gRPC 端口或 worker 入站端口。Agent、Team 和 Workflow 可执行接入见[统一服务 API 示例](/v2/zh/service/service-api#使用-sdk-与可运行示例)。下方 Python 示例使用默认 HTTP 运行传输。

选择接入方式后，区分三个验收层次：目录可见、会话可用、可接受工作派发。只有观察能力的应用不会因为注册成功就自动拥有任务执行能力。

## Java：添加 HTTP 注册与合约

在应用 Maven 配置中添加正式发布的版本：

```xml
<dependency>
  <groupId>io.agentscope</groupId>
  <artifactId>agentscope-extensions-controlplane</artifactId>
  <version>${agentscope.version}</version>
</dependency>
```

下面是已有应用中的接入片段；`agent` 是你已创建的 Agent。环境变量由部署者提供，`AGENT_CONTRACT_URL` 必须能从控制面访问：

```java
import io.agentscope.extensions.controlplane.ControlPlane;
import io.agentscope.extensions.controlplane.ControlPlaneConfig;
import io.agentscope.extensions.controlplane.SessionBridge;

SessionBridge bridge = ControlPlane.instrument(agent,
    ControlPlaneConfig.builder("report-service")
        .controlPlaneHttp(System.getenv("CONTROL_PLANE_HTTP"))
        .registrationCredential(System.getenv("CONTROL_PLANE_REGISTRATION_CREDENTIAL"))
        .tenant(System.getenv("CONTROL_PLANE_TENANT"))
        .namespace(System.getenv("CONTROL_PLANE_NAMESPACE"))
        .instanceKey(System.getenv("CONTROL_PLANE_INSTANCE_KEY"))
        .contractHttpPort(18090)
        .publicBaseUrl(System.getenv("AGENT_CONTRACT_URL"))
        .startHttpRegister(true)
        .startGrpc(false)
        .build());
// 应用退出时调用 bridge.close()。
```

先按[注册指南](/v2/zh/service/register-agentscope-agent)取得 `registrationCredential`，将其设置为 `CONTROL_PLANE_REGISTRATION_CREDENTIAL`。当前 Java bridge 在未配置注册凭据或 bootstrap 参数时会跳过自动注册。当前预览版本的服务端注册入口不验证调用者身份，应限制在受控网络或网关内使用。返回的 registration credential 用于后续运行连接，不能替代注册入口的访问控制。HTTP contract 可读到的历史与命令取决于适配器；Java 实时上报还需在构建 Agent 时装入适配器 middleware，并启用 ASDP 运行连接。

<span id="python连接支持-asdp-的部署" />

## Python：使用 HTTP 运行连接

在应用环境安装 `agentscope-service-sdk` 的对应发布版本：

```bash
python -m pip install "agentscope-service-sdk==$CONTROL_PLANE_SDK_VERSION"
```

下列片段中的 `target` 是已有框架对象，支持的适配器包括 AgentScope、OpenAI Agents、LangChain、ADK 等；实际可用方法以适配器能力为准。

```python
import os
import agentscope_service

bridge = agentscope_service.instrument(
    target,
    agent_key="report-service",
    instance_key=os.environ["CONTROL_PLANE_INSTANCE_KEY"],
    tenant=os.environ["CONTROL_PLANE_TENANT"],
    namespace=os.environ["CONTROL_PLANE_NAMESPACE"],
    transport="http",
    control_plane_http=os.environ["CONTROL_PLANE_HTTP"],
    contract_http_port=18090,
    contract_http_base_url=os.environ["AGENT_CONTRACT_URL"],
    event_journal_dir="/var/lib/report-agent/events",
)
# 应用退出时调用 bridge.stop()。
```

Python 默认使用出站 HTTP exchange（`POST /api/v1/agent-runtime/exchange`），无需开启 gRPC listener。需要 gRPC 时设置 `transport="grpc"` 和 `control_plane="host:port"`。每个副本使用不同 instance key，同一副本重启保持身份稳定，并保存 event journal。自动识别的观测适配器不会执行 Issue/Team 工作；接收任务时，通过 `adapter=` 显式选择 SDK 的 `AsyncInvokeAdapter`、`AgentScopeRunnerAdapter` 或 `ExecutableAdapter`，也可以自行实现任务入口，详见[适配器选择](/v2/zh/service/external-agent#external-agent-frameworks)。

<span id="external-agent-configuration"></span>
<span id="网络与执行能力"></span>

## 注册 API

`POST /api/v1/agent-registrations` 接受 JSON，创建或重新登记一个逻辑 Agent 下的应用副本。

| 请求字段 | 说明 |
| --- | --- |
| `agentKey`、`instanceKey` | 必填；分别为应用逻辑名称和当前副本的稳定名称 |
| `tenant`、`namespace` | 注册范围，省略时均为 `default` |
| `displayName`、`description` | 可选的展示名称和描述 |
| `framework`、`frameworkVersion`、`sdkVersion` | 框架与 SDK 版本信息 |
| `routingKey` | 控制面可访问的应用 HTTP 合约地址 |
| `capacity` | 当前实例声明的执行容量 |
| `capabilities` | 字符串数组，例如 `context-query`、`agent-task`；只声明实际实现的能力 |
| `labels` | 实例标签对象，用于选择和管理副本 |
| `credentialTtlSeconds` | 新发注册凭据的有效时长；正值按秒设置，未设置正值时没有显式到期时间 |
| `ownerType`、`ownerRef` | 可选归属信息；不构成经过验证的调用身份或授权 |

成功返回 201，包含 `agent`、`binding`、`instance`、`credential` 和 `registrationCredential`。业务资源引用 `agent.id`；运行连接需要匹配的 Agent/Binding/Instance 身份及 `instance.generation`。`credential` 是凭据元信息，`registrationCredential` 是明文值，应只保存到受保护的服务端配置。

当前注册入口不校验调用者身份或 Header 中的 token。限制注册入口的网络访问；后续运行连接的凭据校验不能替代注册入口保护。不要把 `ownerRef`、scope 参数或 capability 声明当作身份验证。

## 查询身份与维护凭据

以下管理请求使用有权访问目标 namespace 的平台账户 Bearer token，不使用 应用凭据。

| 方法与路径 | 参数 | 响应 |
| --- | --- | --- |
| `GET /api/v1/agents` | `tenant`、`namespace`、可选 `status`、`includeArchived`、`limit` | `items` |
| `GET /api/v1/agents/{agentId}` | Agent UUID | `agent` |
| `GET /api/v1/agents/{agentId}/bindings` | 可选 `includeDisabled=true` | `items` |
| `GET /api/v1/agents/{agentId}/instances` | Agent UUID | `items`，含实例能力与 generation |
| `GET /api/v1/agents/{agentId}/runtime-inventory` | Agent UUID | `status`、`items`，每项含上报时间、健康、Subagent 和 Workspace 信息 |
| `PATCH /api/v1/agents/{agentId}` | 当前 `version`，可选 `displayName`、`description`、`status`、`labels`、`capabilities` 等 | `agent`；过期版本更新冲突 |
| `POST /api/v1/agent-registrations/{agentId}/credentials/rotate` | 可选 `ttlSeconds` | 201；`credential`、新 `registrationCredential` |
| `DELETE /api/v1/agent-registrations/{agentId}/credentials/{credentialId}` | Agent 与 credential UUID | 204 |

目录状态 `disabled` 用于停止后续调度，`archived` 用于归档逻辑资源。正在执行的任务应通过[任务取消 API](/v2/zh/service/issues)处理，不把修改目录状态当作进程已停止的证明。

## SDK 参数对照

| Java `ControlPlaneConfig.Builder` | Python `instrument()` | 含义 |
| --- | --- | --- |
| `builder(agentKey)` | `agent_key` | 逻辑 Agent 名称，同一应用副本共享 |
| `tenant` / `namespace` | `tenant` / `namespace` | 范围，默认均为 `default` |
| `instanceKey` | `instance_key` | 副本名称；默认根据主机名确定，部署多个副本时显式配置 |
| `controlPlaneHttp` | `control_plane_http` | Service HTTP 地址，包含 scheme |
| `controlPlane` | `control_plane` | 选择 gRPC 时使用的 `host:port` |
| `publicBaseUrl` | `contract_http_base_url` | 控制面可回连的合约 URL |
| `contractHttpPort` | `contract_http_port` | 合约监听端口；Java 默认 18090，Python 默认 8080；0 使用临时端口 |
| `registrationCredential` | `registration_credential` | 注册返回的凭据，用于后续运行连接 |
| `internalToken` | `internal_token` | 由部署维护者提供的可选运行/bootstrap 参数；当前注册 HTTP API 不校验它 |
| `registeredIdentity(agentId, bindingId, generation)` | `agent_id` / `binding_id` / `generation` | 已注册身份；必须保持同一次身份的一组值匹配 |
| `eventJournalDir` | `event_journal_dir` | 事件 outbox 持久目录；不替代业务 Session store |
| `startHttp` | `start_http` | 启动应用合约 HTTP 服务，默认 true |
| `startHttpRegister` | 自动注册 | Java 未指定时按是否配置 `controlPlaneHttp` 决定；Python 根据是否缺少已注册身份和 HTTP 地址决定 |
| `startGrpc` | `start_grpc` | Java 默认 false，启用 gRPC；Python 默认 true，启用所选运行传输 |
| 无对应参数 | `transport` | Python 默认 `http`，可显式选 `grpc` |
| `enableEvents` | `enable_events` | Java 未指定时跟随 `startGrpc`，Python 默认 true；实际事件还依赖框架 hook |
| `sessionAffinity` | `session_affinity` | 会话路由亲和信息 |

当前 Java bridge 即使开启 HTTP 注册，未提供 `registrationCredential` 或 `internalToken` 时也会跳过注册。可以先调用注册 API 取得凭据，再启动 bridge；这项 SDK 启动条件不代表服务端已经校验首次注册身份。

Python 的 `start_grpc` 是所选运行通道的总开关：即使 `transport="http"`，设为 false 也会关闭任务派发与事件上报通道。应用需要接收平台任务时，保留默认 true。

<span id="external-agent-frameworks"></span>
<span id="任务接入的额外验收"></span>

## Java

`agentscope-extensions-controlplane` 提供 AgentScope Java `Agent` 的适配器，包含上下文、消息、会话命令、中止与任务查询等合约能力。不同 Agent 的底层实现仍决定具体命令能否执行。

可选扩展点：`SessionHistorySource` 提供历史，`AgentRuntimeSource` 提供 Workspace/Subagent 目录与运行信息，`AgentTaskStarter` 启动派发任务。`HarnessAgentTaskStarter` 可以结合 Workspace 工厂消费平台定义。只有配置任务 starter 才声明 `agent-task`，不能仅创建 bridge 就视为工作执行器。

## Python 内置适配器

| 框架 | 典型接入对象/方式 | 已实现的适配重点 |
| --- | --- | --- |
| AgentScope | Agent 实例与 hooks | 上下文、消息、命令、中止、任务查询 |
| OpenAI Agents SDK | Session 或含 Session 的对象 | 观察 Session items、上下文与消息；命令依赖后端方法 |
| LangChain / LangGraph | 对应框架对象与 callbacks/state | 模型/工具事件、上下文与消息 |
| Google ADK | SessionService 等框架对象 | Session 事件、上下文、消息与对应命令 |
| Claude Agent SDK | Client / Session store 对象 | 会话存储、上下文、消息与对应命令 |
| OpenClaw | Gateway RPC 客户端/连接入口 | 上下文、消息、Subagent 与 Workspace 目录 |

自动识别使用 `can_handle(target)`，并按注册顺序选择首个匹配项。遇到包装对象无法识别或多个适配器可能匹配时，通过 `adapter=` 显式指定目标适配器。

上表中自动识别的适配器负责观测及各自实现的会话能力，不执行 Issue/Team 派发的任务。需要接收任务时，可通过 `adapter=` 显式传入 SDK 已提供的执行适配器：

| 执行适配器 | 适用对象与参数 |
| --- | --- |
| `AsyncInvokeAdapter` | 提供异步 `ainvoke(input)` 的框架对象；传入 `control_plane_http`、`factory`、`input_builder`，可选 `result_mapper` |
| `AgentScopeRunnerAdapter` | 异步可调用的 AgentScope Agent，并挂载原生观测 hook；参数同上 |
| `ExecutableAdapter` | 自定义异步 `runner(TaskContext)`；传入 `control_plane_http` 与 `runner`，可选 `framework` |

`factory` 为每个任务创建新的 Agent 或框架实例，`input_builder` 将平台任务上下文映射为框架接受的输入；自定义 runner 也应隔离各任务的会话状态。返回值必须能序列化为 JSON，特殊类型可通过 `result_mapper` 转换。执行示例见[统一服务 API](/v2/zh/service/service-api#使用-sdk-与可运行示例)。

框架名相同也不代表 External 与 Hosted 的能力相同，例如 Claude Agent SDK 集成与 Runtime Host 启动 Claude Code CLI 是两条路径。

## 自定义适配器实现哪些部分

| 扩展点 | 应提供的行为 |
| --- | --- |
| `can_handle` | 判断对象类型，不启动任务 |
| `attach` / `detach` | 挂载、移除框架 hook 或观察器 |
| `extract_context` | 提取会话上下文 |
| `list_messages` | 返回可读取的历史消息 |
| `handle_command` / `abort` | 执行真实命令与取消，返回真实失败 |
| `handle_agent_task` | 为一个派发建立隔离执行，正确处理结果、失败与取消 |

继承 `FrameworkAdapter` 后通过 `agentscope_service.instrument(..., adapter=your_adapter)` 使用，或用 `register_adapter()` 加入注册表。基础类会根据覆写方法推导能力；不要用空实现换取 capability 标志。

## 从 API 检查适配能力

用平台账户 Bearer 请求 `GET /api/v1/agents/{agentId}/instances`，读取返回 `items` 中每个实例的 `capabilities`。例如 `context-query`、`message-query` 是查询能力，`session-abort` 是取消能力，`agent-task` 才是平台任务入口。实例能力可能随适配器配置变化，不能仅根据 `framework` 名称判断。

`GET /api/v1/agents/{agentId}/runtime-inventory` 返回上报的 Workspace 和子 Agent 信息。`not_reporting` 表示没有遥测，不能据此判断它从未执行过任务。业务调用使用 Session API；接入后读取 Session 和 Turn 的 capabilities，确认适配器实际支持哪些交互。

能力注册参数见[连接参数](/v2/zh/service/external-agent#external-agent-configuration)，实际执行与回报接口见[任务派发](/v2/zh/service/external-agent#external-agent-execution)。

<span id="external-agent-execution"></span>
<span id="三个层次分别验收"></span>
<span id="参与-team"></span>

## 查询合约与运行传输的分工

HTTP 注册建立目录身份；应用合约提供可查询的能力和会话操作。HTTP 请求的可达方向与应用向控制面注册的方向不同，部署时必须同时验证。

Python 默认使用 HTTP exchange 接收执行并上报事件，也可选择 ASDP gRPC。Java HTTP 注册与查询合约可独立使用；任务接收与上报需启用当前 Java SDK 的 gRPC 运行通道。实际参数与网络方向见[连接配置](/v2/zh/service/external-agent#external-agent-configuration)。

事件 journal 帮助断线恢复，但不保存所有业务状态，也不代替框架自己的 Session store。应用需保留自己的数据、工具连接和任务幂等处理。

## 一个派发的执行过程

控制面根据绑定能力选择实例并建立 Attempt。应用获得该次执行的标识、generation 和任务范围上下文，由 `AgentTaskStarter` 或 `handle_agent_task` 创建隔离执行。执行者回报进度、评论与 Artifact，维持执行所需的租约/状态，再报告成功、失败或取消。

工作结果应关联同一次 Attempt。旧执行返回时必须接受控制面的过期身份检查，不能把旧结果重新贴成另一任务的成功。收到取消后向框架传播请求，并观察最终状态；HTTP 响应成功不证明业务执行已经停止。

## 用 API 观察和控制一次执行

业务应用创建以这个 Agent 为目标的 Session，再提交 Turn。Service 根据运行绑定派发任务并保存执行记录，应用无需直接创建 ExecutionAttempt。需要人工分派和验收的工作也可以从 Issue 界面进入。

| 使用者 | 操作 | 接口与字段 |
| --- | --- | --- |
| 业务管理者 | 查询任务与物理尝试 | `GET /api/v1/agent-tasks/{taskId}`、`GET /api/v1/execution-attempts?tenant=...&namespace=...&taskId=...` |
| 业务管理者 | 请求取消或重试 | `POST /api/v1/agent-tasks/{taskId}/cancel`、`/retry`；并发版本等字段见 [Issue API](/v2/zh/service/issues) |
| 任务执行者 | 读取上下文、开始、进度、结果 | `/api/v1/agent-tasks/{taskId}/context`、`/start`、`/progress`、`/respond`、`/complete`、`/fail`，使用分配给该任务的 token |
| 应用消费者 | 读取快照并续传事件 | `GET /api/v1/agent-sessions/{sessionId}/turns/{turnId}/snapshot` 与 `/events/stream`，使用调用凭据 |

管理 token 不应替代运行协议注入的 task token；取消请求被接受后，仍需读取任务与 Attempt 的最终状态。External Agent 的 Session 当前只在实例声明 `session-abort` 时提供取消能力，其他中途交互以 capabilities 返回为准。
