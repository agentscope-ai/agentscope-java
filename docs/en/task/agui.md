# AG-UI Protocol Integration

AG-UI is a frontend-backend communication protocol for exposing agents to web frontends. With AG-UI, you can quickly integrate AgentScope agents with compatible frontend frameworks.

---

## Quick Start

### 1. Add Dependencies

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

### 2. Register Agent

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
                .sysPrompt("You are a helpful assistant.")
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

### 3. Configure

```yaml
# application.yml
agentscope:
  agui:
    path-prefix: /agui
    cors-enabled: true
    server-side-memory: true  # Enable server-side session persistence
```

### 4. Start and Test

```bash
# Test after starting the application
curl -N -X POST http://localhost:8080/agui/run \
  -H "Content-Type: application/json" \
  -d '{"threadId":"t1","runId":"r1","messages":[{"id":"m1","role":"user","content":"Hello"}]}'
```

---

## Frontend Integration

### Using @ag-ui/client (Recommended)

The official AG-UI client library for vanilla JavaScript/TypeScript projects:

```html
<script type="module">
import { HttpAgent } from 'https://esm.sh/@ag-ui/client';

// Create agent instance
const agent = new HttpAgent({
    url: 'http://localhost:8080/agui/run',
    threadId: 'thread-' + Date.now()
});

// Subscribe to message changes
agent.subscribe({
    onMessagesChanged: () => {
        console.log('Messages:', agent.messages);
    }
});

// Add user message
agent.addMessage({
    id: 'msg-1',
    role: 'user',
    content: 'Hello'
});

// Run agent
await agent.runAgent({ runId: 'run-1' });

// Abort run
// agent.abortRun();
</script>
```

### Using CopilotKit

For React applications, you can use [CopilotKit](https://copilotkit.ai):

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

## Example Project

See complete example at [agentscope-examples/agui](https://github.com/agentscope-ai/agentscope-java/tree/main/agentscope-examples/agui):

```bash
export DASHSCOPE_API_KEY=your-key
cd agentscope-examples/agui
mvn spring-boot:run
```

Visit http://localhost:8080 to see the demo.

---

## More Resources

- [@ag-ui/client Documentation](https://www.npmjs.com/package/@ag-ui/client)
- [AG-UI Protocol Documentation](https://docs.ag-ui.com)
- [CopilotKit](https://copilotkit.ai)
