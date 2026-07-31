# Agent Protocol

`agentscope-extensions-agent-protocol` 把 AgentScope 的 [Harness Agent](../../docs/harness/architecture.md) 暴露为 [Agent Protocol](https://agentprotocol.ai/) 标准 HTTP 接口，让外部系统（CI、其他 Agent 平台、自动化任务）可以用统一的方式提交"任务"，无需关心你的 Agent 实现细节。

## 何时使用

- 想让 Agent 像云函数一样被远程调度。
- 已有团队在用 Agent Protocol 客户端，想直接接进去。
- 把 AgentScope Harness Agent 嵌进 Spring Boot 服务，自动暴露 `/tasks` REST 端点。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-agent-protocol</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 启用方式

模块以 Spring Boot 自动配置形式提供，所以仅需在 Spring Boot 应用里：

1. 注入一个 `HarnessAgent` Bean 和一个 `WorkspaceManager` Bean。
2. 在 `application.yml` 里启用：

```yaml
agentscope:
  agent-protocol:
    enabled: true
```

随后会自动注册 `/tasks` 系列 REST 接口（基于 Agent Protocol 规范）。

## 并发执行

Agent 在调用之间是无状态的——单例即可服务多个并发任务。每个任务通过 `RuntimeContext` 携带独立的 `(userId, sessionId)`，状态完全隔离：

```java
@Bean
public HarnessAgent harnessAgent() {
    return HarnessAgent.builder()
            .name("protocol-agent")
            .model("dashscope:qwen-plus")
            .build();
}
```

同一 session 的并发请求会自动串行化；不同 session 完全并行。

## 配置项

| `application.yml` 键 | 类型 | 默认 | 说明 |
| --- | --- | --- | --- |
| `agentscope.agent-protocol.enabled` | boolean | `false` | 是否注册 `/tasks` REST 端点 |

> 关闭时（默认）即使引入依赖也不会暴露任何 REST 接口，可放心打包。

## 与 Workspace 配合

每个 task 都会拿到 `WorkspaceManager` 分配的隔离工作目录；任务结束后，工作区里的产物（文件、日志）会通过 Agent Protocol 的标准接口暴露出来，外部客户端可以直接拉取。
