# AG-UI 协议集成

AG-UI 是一个前后端通信协议，用于将智能体暴露给 Web 前端。通过 AG-UI，你可以快速将 AgentScope 智能体接入支持该协议的前端框架。

---

## 快速开始

### 1. 添加依赖

```xml
<dependency>
    <groupId>io.agentscope</groupId>
    <artifactId>agentscope-agui-spring-boot-starter</artifactId>
</dependency>

<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

### 2. 注册智能体

```java
@Configuration
public class AgentConfiguration {

    @Autowired
    public void configureAgents(AguiAgentRegistry registry) {
        registry.registerFactory("default", this::createAgent);
    }

    private Agent createAgent() {
        return ReActAgent.builder()
                .name("Assistant")
                .sysPrompt("你是一个有帮助的助手。")
                .model(DashScopeChatModel.builder()
                        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
                        .modelName("qwen3-max")
                        .stream(true)
                        .build())
                .memory(new InMemoryMemory())
                .build();
    }
}
```

### 3. 配置

```yaml
# application.yml
agentscope:
  agui:
    path-prefix: /agui
    cors-enabled: true
    server-side-memory: true  # 启用服务端会话保持
```

### 4. 启动并测试

```bash
# 启动应用后测试
curl -N -X POST http://localhost:8080/agui/run \
  -H "Content-Type: application/json" \
  -d '{"threadId":"t1","runId":"r1","messages":[{"id":"m1","role":"user","content":"你好"}]}'
```

---

## 前端接入

### 使用 @ag-ui/client（推荐）

AG-UI 官方客户端库，适用于原生 JavaScript/TypeScript 项目：

```html
<script type="module">
import { HttpAgent } from 'https://esm.sh/@ag-ui/client';

// 创建 Agent 实例
const agent = new HttpAgent({
    url: 'http://localhost:8080/agui/run',
    threadId: 'thread-' + Date.now()
});

// 订阅消息变化
agent.subscribe({
    onMessagesChanged: () => {
        console.log('Messages:', agent.messages);
    }
});

// 添加用户消息
agent.addMessage({
    id: 'msg-1',
    role: 'user',
    content: '你好'
});

// 运行智能体
await agent.runAgent({ runId: 'run-1' });

// 中断运行
// agent.abortRun();
</script>
```

### 使用 CopilotKit

如果你使用 React，可以直接接入 [CopilotKit](https://copilotkit.ai)：

```typescript
import { CopilotKit } from "@copilotkit/react-core";

function App() {
  return (
    <CopilotKit runtimeUrl="http://localhost:8080/agui">
      <YourApp />
    </CopilotKit>
  );
}
```

---

## 示例项目

完整示例见 [agentscope-examples/agui](https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-examples/agui)：

```bash
export DASHSCOPE_API_KEY=your-key
cd agentscope-examples/agui
mvn spring-boot:run
```

访问 http://localhost:8080 查看演示。

---

## 更多资源

- [@ag-ui/client 文档](https://www.npmjs.com/package/@ag-ui/client)
- [AG-UI 协议文档](https://docs.ag-ui.com)
- [CopilotKit](https://copilotkit.ai)
