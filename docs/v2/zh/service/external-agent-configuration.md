---
title: "External 注册与连接参数"
en_link: /v2/en/service/external-agent-configuration
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

本文说明 External 的注册 API、实例身份和 SDK 连接参数。第一次接入可先跟随[注册已有 Agent](/v2/zh/service/register-agentscope-agent)，Java/Python 片段见[接入用法](/v2/zh/service/external-agent)。

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

以下管理请求使用有权访问目标 namespace 的平台账户 Bearer token，不使用 Endpoint key。

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

| Java `AistioConfig.Builder` | Python `instrument()` | 含义 |
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

## 网络与执行能力

HTTP 注册是应用向 Service 发请求；HTTP contract 查询是控制面回连应用。Python HTTP 运行通道使用出站 `POST /api/v1/agent-runtime/exchange`，gRPC 则需要部署实际开启 ASDP listener。标准 standalone HTTP 地址不能因为 SDK 设置 `startGrpc(true)` 就变成 gRPC 地址。

Java `AgentTaskStarter` 或 Python `handle_agent_task` 是任务入口。只连接目录和查询合约不自动获得 `agent-task`；工作能力、失败回报和取消须有实际实现。模型、工具连接和业务凭据继续由 External 应用维护。

每个副本保存自己的身份及事件日志；不要手动递增 generation 来规避旧执行校验。验收时分别检查目录、运行连接、会话查询和一次真实任务。业务调用与 SSE 使用[统一 Agent API](/v2/zh/service/service-api)，其 cursor 与应用原生事件日志不能混用。
