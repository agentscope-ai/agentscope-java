# Studio（可视化调试）

Studio 提供 Web 界面实时可视化 Agent 执行过程，支持交互式调试和消息追踪。

---

## 核心特性

- **实时可视化**：Web 界面展示 Agent 推理和执行过程
- **交互式输入**：通过 Web UI 与 Agent 对话
- **消息追踪**：查看完整的消息流和Trace
- **多 Run 管理**：支持多个实验运行的组织和比较

---

## 快速开始

### 1. 启动 Studio Server

从源码启动
```bash
git clone https://github.com/agentscope-ai/agentscope-studio
cd agentscope-studio
npm install
npm run dev
```
npm安装
```bash
npm install -g @agentscope/studio  # or npm install @agentscope/studio
as_studio
```
Studio 将运行在 http://localhost:5173
![Studio Server 页面](../../imgs/studioServer.png)


### 2. Java 应用集成

```java
import io.agentscope.core.studio.StudioManager;
import io.agentscope.core.studio.StudioMessageHook;
import io.agentscope.core.studio.StudioUserAgent;

// 初始化 Studio 连接
StudioManager.init()
    .studioUrl("http://localhost:3000")
    .project("MyProject")
    .runName("demo_" + System.currentTimeMillis())
    .initialize()
    .block();

// 创建带 Hook 的 Agent
ReActAgent agent = ReActAgent.builder()
    .name("Assistant")
    .model(model)
    .hook(new StudioMessageHook(StudioManager.getClient()))
    .build();

// Agent 消息自动发送到 Studio
agent.call(msg).block();

// 清理资源
StudioManager.shutdown();
```

### 3. 在AgentScope Studio 查看Trace信息

![Trace信息](../../imgs/studioServer-trace.png)



## StudioUserAgent

通过 Web UI 接收用户输入。

```java
import io.agentscope.core.studio.StudioUserAgent;

// 创建用户 Agent
StudioUserAgent user = StudioUserAgent.builder()
    .name("User")
    .studioClient(StudioManager.getClient())
    .webSocketClient(StudioManager.getWebSocketClient())
    .build();

// 等待 Web UI 用户输入
Msg userInput = user.call(null).block();
```
您可以在Studio中的Projects中找到该Project，通过WebUI的方式进行调试
![Studio Server Web UI 输入界面](../../imgs/studioServer-webUI.png)

### 对话循环

```java
Msg msg = null;
while (true) {
    // 从 Web UI 获取用户输入
    msg = user.call(msg).block();
    
    if (msg == null || "exit".equalsIgnoreCase(msg.getTextContent())) {
        break;
    }
    
    // Agent 处理
    msg = agent.call(msg).block();
}
```

---

## 完整示例

```java
package io.agentscope.examples;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.studio.StudioManager;
import io.agentscope.core.studio.StudioMessageHook;
import io.agentscope.core.studio.StudioUserAgent;

public class StudioExample {
    
    public static void main(String[] args) throws Exception {
        String apiKey = System.getenv("DASHSCOPE_API_KEY");
        
        System.out.println("Connecting to Studio at http://localhost:3000...");
        
        // 初始化 Studio
        StudioManager.init()
            .studioUrl("http://localhost:3000")
            .project("JavaExamples")
            .runName("studio_demo_" + System.currentTimeMillis())
            .initialize()
            .block();
        System.out.println("Connected to Studio\n");
        
        try {
            // 创建 Agent（带 Studio Hook）
            ReActAgent agent = ReActAgent.builder()
                .name("Assistant")
                .sysPrompt("You are a helpful AI assistant.")
                .model(DashScopeChatModel.builder()
                    .apiKey(apiKey)
                    .modelName("qwen3-max")
                    .build())
                .hook(new StudioMessageHook(StudioManager.getClient()))
                .build();
            
            // 创建用户 Agent
            StudioUserAgent user = StudioUserAgent.builder()
                .name("User")
                .studioClient(StudioManager.getClient())
                .webSocketClient(StudioManager.getWebSocketClient())
                .build();
            
            // 对话循环
            System.out.println("Starting conversation (type 'exit' to quit)");
            System.out.println("Open http://localhost:3000 to interact\n");
            
            Msg msg = null;
            int turn = 1;
            while (true) {
                System.out.println("[Turn " + turn + "] Waiting for user input...");
                msg = user.call(msg).block();
                
                if (msg == null || "exit".equalsIgnoreCase(msg.getTextContent())) {
                    System.out.println("\nConversation ended");
                    break;
                }
                
                System.out.println("[Turn " + turn + "] User: " + msg.getTextContent());
                msg = agent.call(msg).block();
                
                if (msg != null) {
                    System.out.println("[Turn " + turn + "] Agent: " 
                        + msg.getTextContent() + "\n");
                }
                turn++;
            }
            
        } finally {
            System.out.println("\nShutting down...");
            StudioManager.shutdown();
            System.out.println("Done\n");
        }
    }
}
```

---

## 高级用法

### 手动推送消息

```java
StudioClient client = StudioManager.getClient();

Msg customMsg = Msg.builder()
    .role(MsgRole.ASSISTANT)
    .content(TextBlock.builder().text("自定义消息").build())
    .build();

client.pushMessage(customMsg).block();
```


### 多 Agent 可视化

```java
// 为每个 Agent 添加 Hook
ReActAgent agent1 = ReActAgent.builder()
    .name("Agent1")
    .hook(new StudioMessageHook(client))
    .build();

ReActAgent agent2 = ReActAgent.builder()
    .name("Agent2")
    .hook(new StudioMessageHook(client))
    .build();

// Studio 将分别显示两个 Agent 的消息
```
---

## 更多资源

- **完整示例**: [StudioExample.java](https://github.com/agentscope-ai/agentscope-java/blob/main/agentscope-examples/advanced/src/main/java/io/agentscope/examples/advanced/StudioExample.java)
- **Studio 仓库**: https://github.com/agentscope-ai/agentscope-studio
- **Hook 文档**: [hook.md](./hook.md)
