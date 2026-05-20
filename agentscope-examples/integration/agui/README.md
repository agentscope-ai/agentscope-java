# Quick Start

本案例展示了如何基于 Spring Boot 构建支持 [AgentScope](https://github.com/agentscope-ai/agentscope-java) AG-UI 协议的 Web 服务。通过实现标准的交互协议，本项目提供了包括多智能体路由、智能体推理展示（需在 yml 中开启）和工具调用进度反馈（需在 yml 中开启）在内的前后端交互体验。

## 环境要求

* JDK 17 及以上
* Maven 3.8+
* 有效的 DashScope API Key（本项目默认使用 `qwen-plus` 模型）

## 示例运行

### 1. AG-UI 服务端参数配置

项目的核心配置位于 `application.yml` 中：

```yaml
agentscope:
  agui:
    path-prefix: /agui                # AG-UI 接口统一前缀
    cors-enabled: true                # 开启跨域支持
    default-agent-id: default         # 默认处理请求的 Agent ID
    agent-id-header: X-Agent-Id       # 通过 HTTP Header 进行 Agent 路由
    enable-path-routing: true         # 允许通过 URL 路径进行 Agent 路由
    server-side-memory: true          # 是否按 threadId 在后端管理会话内存
    session-timeout-minutes: 30       # 会话超时时间
    enable-reasoning: false           # 是否启用推理/思考内容输出（模型也需要支持并开启）
    emit-tool-call-args: true         # 是否发出工具调用参数事件
    enable-acting-chunk: false        # 是否发出工具调用进度信息
```

### 2. 运行应用

本项目基于 Spring Boot，使用以下 Maven 命令启动应用：

```bash
mvn spring-boot:run
```

### 3. 开始体验

应用启动后，将在本机的 `8080` 端口提供服务。本项目提供了两种体验方式：

#### 方式一：可视化 Web 界面

直接在浏览器中打开内置的交互式页面：
**http://localhost:8080**
该页面原生对接了 AG-UI 协议，支持实时显示流式打字效果、工具调用状态以及内部推理过程。

#### 方式二：API / cURL 调用体验多智能体路由

基于 `AgentConfiguration.java` 的注册逻辑，本项目实现了三种不同的 Agent。你可以通过不同的调用策略来体验路由机制：

**1. 访问默认 Agent（带工具，支持天气和计算）：**

```shell
curl -N -X POST http://localhost:8080/agui/run \
  -H "Content-Type: application/json" \
  -d '{"threadId":"test-1","runId":"1","messages":[{"id":"m1","role":"user","content":"北京今天天气如何？"}]}'
```

**2. 通过 URL 路径路由访问纯聊天 Agent（`chat`）：**

```shell
curl -N -X POST http://localhost:8080/agui/run/chat \
  -H "Content-Type: application/json" \
  -d '{"threadId":"test-2","runId":"1","messages":[{"id":"m1","role":"user","content":"你好，我们来聊聊天吧。"}]}'
```

**3. 通过 HTTP Header 路由访问计算器 Agent（`calculator`）：**

```shell
curl -N -X POST http://localhost:8080/agui/run \
  -H "Content-Type: application/json" \
  -H "X-Agent-Id: calculator" \
  -d '{"threadId":"test-3","runId":"1","messages":[{"id":"m1","role":"user","content":"计算 12.5 乘以 4"}]}'
```

---

## 核心代码架构说明

为便于二次开发，可重点关注以下核心类：

* **`AgentConfiguration.java`**：演示了如何通过 `AguiAgentRegistryCustomizer` 注册多个独立 Agent 工厂（Factory 模式），确保并发场景下每个请求获取纯净的 Agent 实例。
* **`CustomToolResultBlockConverter.java`**：高阶特性演示。通过继承并重写框架底层的 Event 转换逻辑（基于 @Component 注册生效），允许将带有进度 Metadata 的工具（如 get_weather）封装为 AguiEvent.ToolCallResult 事件，从而向前端实时推送工具执行的中间状态。(注：需在 application.yml 中开启 enable-acting-chunk: true)
* **`ExampleTools.java`**：Agent 可用的工具集合。展示了 `ToolEmitter` 的高级用法，实现了执行过程的多阶段状态通知。
* **`index.html`**：实现了一个完整的 AG-UI 客户端界面，处理流式文本 (`onTextContent`)、推理过程 (`onReasoningContent`) 以及工具调用 (`onToolCallStart`) 的事件分发。

