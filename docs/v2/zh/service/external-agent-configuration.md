---
title: "External 注册与连接参数"
---

<Note>
此为预览文档，正式版本尚未发布。
</Note>

External 应用保留自己的进程与框架。下面的参数连接目录身份、HTTP 合约和可选 ASDP 事件通道。可直接嵌入应用的 Java/Python 代码见[接入用法](/v2/zh/service/external-agent)。

## SDK 参数对照

| Java `AistioConfig.Builder` | Python `instrument()` | 含义 |
| --- | --- | --- |
| `builder(agentKey)` | `agent_key` | 逻辑 Agent 身份，同一应用副本共享 |
| `tenant` / `namespace` | `tenant` / `namespace` | 注册范围，默认均为 `default` |
| `instanceKey` | `instance_key` | 副本身份；不同副本不同，重启保持稳定 |
| `controlPlaneHttp` | `control_plane_http` | HTTP 注册与控制面 API 地址，包含 scheme |
| `controlPlane` | `control_plane` | ASDP 地址，格式 `host:port` |
| `publicBaseUrl` | `contract_http_base_url` | 控制面可回连的应用 HTTP 合约 URL |
| `contractHttpPort` | `contract_http_port` | 应用合约监听端口；Java 默认 18090，Python 默认 8080 |
| `internalToken` | `internal_token` | 初次注册的受信任 workload/bootstrap 凭据 |
| `registrationCredential` | `registration_credential` | 注册身份的后续凭据 |
| `registeredIdentity(agentId, bindingId, generation)` | `agent_id` / `binding_id` / `generation` | 已注册的稳定身份；保持一组值对应同次注册 |
| `eventJournalDir` | `event_journal_dir` | 事件日志持久目录，需要保留时挂载持久卷 |
| `startHttp` | `start_http` | 是否启动合约 HTTP 服务，默认 true |
| `startGrpc` | `start_grpc` | 是否启动 ASDP；Java 默认 false，Python 默认 true |
| `enableEvents` | `enable_events` | 事件上报开关；Java 未指定时跟随 `startGrpc`，Python 默认 true |
| `sessionAffinity` | `session_affinity` | 会话亲和信息，按应用路由需求配置 |

Java 的 `startHttpRegister(true)` 可以独立启动 HTTP 注册；未指定时由是否配置 `controlPlaneHttp` 决定。Python 当前的自动注册位于 ASDP 初始化路径，关闭 `start_grpc` 不会变成独立的 HTTP 注册方案。

## 网络与部署模式

| 方向 | 必要条件 |
| --- | --- |
| 应用 → Service HTTP | 注册地址可达，身份凭据有效 |
| 控制面 → 应用合约 | 使用可路由的 DNS/端口；容器中的 localhost 通常只指本容器 |
| 应用 → ASDP listener | 使用启用 ASDP 的部署及其实际 gRPC 地址 |

标准 Service Compose/Helm 运行 standalone HTTP，不启动 ASDP listener。需要 Python 自动接入或 ASDP 实时通道时，先准备支持 ASDP 的 Kubernetes-native Aistio 部署。开启 SDK 开关不会让 Gateway 自动提供 gRPC listener。

## 身份与凭据的生命周期

首次注册后保存返回的身份和 registration credential。普通实例重启复用身份；扩容时分配新的 instance key。generation 属于控制面身份与派发校验，不能手动递增来绕过过期执行检查。

Bootstrap 凭据由管理员交付给受信任应用；它与浏览器登录 token、Endpoint API key、Runtime Host enrollment token 的用途不同。模型和业务工具密钥仍由 External 应用管理，不因目录注册而自动读取 Managed Vault。

## 配置后验收

先检查目录中的框架、实例和合约地址，再从应用自身执行一次对话，检查上下文和历史是否可读取。最后按适配器能力测试命令、取消和任务派发。只有观测能力的实例不应以 Issue 派发成功作为注册的默认承诺。
