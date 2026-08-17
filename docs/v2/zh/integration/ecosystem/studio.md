# AgentScope Studio

`agentscope-extensions-studio` 把 Agent 接入 [AgentScope Studio](https://github.com/agentscope-ai/agentscope-studio)：每次 Agent 调用都会被推送到 Studio，用作可视化调试、链路回放、Human-in-the-Loop 输入。

## 何时使用

- 开发期想在 Studio 里看到 Agent 的事件流、推理过程、工具调用。
- 需要在 Studio 里发起 `requestUserInput`，让真人介入答题。

## 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-extensions-studio</artifactId>
    <version>${agentscope.version}</version>
</dependency>
```

## 快速上手

```java
import io.agentscope.core.studio.StudioManager;
import io.agentscope.core.studio.StudioMessageHook;

// 1) 初始化 Studio 连接（HTTP + WebSocket）
StudioManager.init()
    .studioUrl("http://localhost:8000")
    .project("MyProject")
    .runName("experiment_001")
    .initialize()
    .block();

// 2) 把 StudioMessageHook 挂到 Agent 上，自动把消息推到 Studio
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .hook(new StudioMessageHook(StudioManager.getClient()))
    .build();

// 3) 正常调用 Agent，Studio 上会同步看到对话
agent.call(msg).block();
```

## Studio 提供的能力

- **消息推送**：每条 user / assistant / tool 消息都被同步到 Studio。
- **链路追踪**：Studio 内部会按 `runName` 把整次运行编排成一棵 trace 树。
- **Human-in-the-Loop**：通过 `StudioUserAgent` 或 `requestUserInput`，让 Studio UI 弹出输入框等待真人填写后再继续。

## API 概览

| 类 | 用途 |
| --- | --- |
| `StudioManager` | 单例式入口，初始化和获取 client |
| `StudioConfig` | URL / project / runName 等配置 |
| `StudioClient` | HTTP 客户端，推送事件、消息、注册 run |
| `StudioWebSocketClient` | WebSocket 客户端，接收 Studio 侧的指令（如 user input） |
| `StudioMessageHook` | 注入到 `ReActAgent` 的 `Hook`，自动推送 `Msg` |
| `StudioUserAgent` | "真人扮演的 Agent"，调用时阻塞等待 Studio 输入 |

## 何时关闭

生产部署一般不挂这个 Hook（避免每次调用都向 Studio 写一份）。建议通过 Spring Profile 或 `@ConditionalOnProperty` 控制：

```java
@Bean
@ConditionalOnProperty("agentscope.studio.enabled")
StudioMessageHook studioHook() {
    StudioManager.init().studioUrl(url).project(project).initialize().block();
    return new StudioMessageHook(StudioManager.getClient());
}
```
