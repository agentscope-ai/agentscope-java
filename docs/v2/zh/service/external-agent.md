---
title: "External Agent：接入独立应用"
---

[English](/v2/en/service/external-agent)

External Agent 保留你的应用进程、框架和部署方式，同时接入统一目录、会话诊断与工作协作。它不是由 Service 启动的 Managed Agent，也不要求把应用改造成 Runtime Host provider。

## 先选择接入路径

| 路径 | 网络前提 | 适合的能力 |
| --- | --- | --- |
| Java HTTP contract | 应用可访问注册 API；控制面可回连应用合约地址 | 注册、合约查询及适配器实现的命令 |
| ASDP 框架接入 | 上述 HTTP 连通性，加上可达的 ASDP gRPC listener | 实时事件上报及适配器实现的 ExecutionAttempt 派发 |

标准 Service Compose/Helm 使用 standalone HTTP，不提供 ASDP listener。Python `instrument()` 的自动注册与 ASDP 连接绑定；不能通过 `start_grpc=False` 把它当作完整 HTTP 注册方案。需要这条路径时，先由管理员提供启用 ASDP 的 Kubernetes-native Aistio 部署与真实 gRPC 地址。

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

首次注册使用管理员提供的受信任 workload/bootstrap 凭据，后续身份可使用 registration credential。不要把组件内部令牌分发给浏览器或普通调用者。HTTP contract 可读到的历史与命令取决于适配器实现；需要实时事件时，在创建 Agent 阶段装入适配器 middleware，并配置 ASDP。

## Python：连接支持 ASDP 的部署

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
    control_plane=os.environ["AISTIO_CONTROL_GRPC"],
    control_plane_http=os.environ["AISTIO_CONTROL_HTTP"],
    internal_token=os.environ["AISTIO_BOOTSTRAP_TOKEN"],
    contract_http_port=18090,
    contract_http_base_url=os.environ["AGENT_CONTRACT_URL"],
    event_journal_dir="/var/lib/report-agent/events",
)
# 应用退出时调用 bridge.stop()。
```

`AISTIO_CONTROL_GRPC` 是 `host:port`，不是带 HTTP scheme 的 Gateway URL。每个副本使用不同 instance key，同一副本重启保持身份稳定。保存 event journal 以支持事件恢复。

## 接受 Issue / Team 工作

任务适配器需要实现真实的执行入口，例如 Python `FrameworkAdapter.handle_agent_task`，Java `AgentTaskStarter`。应用为每次 Attempt 建立隔离执行，读取注入的工作上下文，使用任务范围凭据发表评论和上传 Artifact，并按协议上报成功、失败或取消。

不要把“发出最终消息”代替 Attempt 完成，也不要用过期 generation 的凭据回报另一次执行。Coordinator 还需要显式完成或失败对应 Run node；详见 [Team 协作](/v2/zh/service/team-collaboration)。

## 自定义适配器与验收

Python 可实现 `FrameworkAdapter` 并通过 `adapter=` 传入，按需要扩展 context、messages、commands 和 task execution；只声明实际实现的能力。Java 使用对应 `FrameworkAdapter` 与 `AgentScopeAdapter` 扩展点。

依次验证：注册与重启身份、应用侧一轮对话、历史读取、控制面可达的合约、一次支持的工作派发、失败和取消回报。容器 `localhost`、只有单向网络、错误 gRPC 端口和虚报 capability 是常见接入问题。

相关：[SDK 与组件选择](/v2/zh/service/integrations) · [API 参考](/v2/zh/service/api-reference)。
