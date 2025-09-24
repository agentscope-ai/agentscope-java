# Create ReAct Agent
AgentScope Java provides out-of-the-box ReAct agent `ReActAgent` under `io.agentscope.core.agent` that can be used directly.

It supports the following features at the same time:

+ **✨**** Basic features**
    - Support **hooks** around `reply`, `observe`, `reasoning` and `acting` functions
    - Support structured output
+ **🛠️**** Tools**
    - Support Function as tool
    - Support Method as tool
+ **💾**** Memory**
    - Support shor-term memory.
+ **More features coming ...**

## Creating ReAct Agent
To improve the flexibility, the `ReActAgent` class exposes the following parameters in its constructor:

| Parameter | Further Reading | Description |
| --- | --- | --- |
| `name` (required) |  | The name of the agent |
| `sysPrompt` (required) |  | The system prompt of the agent |
| `model` (required) | <font style="color:rgb(39, 87, 221);">Model</font> | The model used by the agent to generate responses |
| `formatter` (required) | <font style="color:rgb(39, 87, 221);">Prompt Formatter</font> | The prompt construction strategy, should be consisted with the model |
| `toolkit` | <font style="color:rgb(39, 87, 221);">Tool</font> | The toolkit to register/call tool functions. |
| `maxIters` |  | The maximum number of iterations for the agent to generate a response |
| `memory` | <font style="color:rgb(39, 87, 221);">Memory</font> | The short-term memory used to store the conversation history |


Taking DashScope API as example, we create an agent object as follows:

```java
public static void main(String[] args) {
    Model model = DashScopeChatModel.builder()
        .apiKey(System.getenv("DASHSCOPE_API_KEY"))
        .modelName("qwen-max")
        .baseUrl("https://dashscope.aliyuncs.com/v1")
        .build();

    ReActAgent agent = ReActAgent.builder()
        .name("hello-world-agent")
        .sysPrompt("You are a helpful AI assistant. Be concise and friendly. " +
                   "When thinking through problems, use <thinking>...</thinking> tags to show your reasoning.")
        .model(model)
        .memory(new InMemoryMemory())
        .formatter(new DashScopeChatFormatter())
        .build();

    Msg userMessage = Msg.builder()
        .role(MsgRole.USER)
        .textContent("Hello, please introduce yourself.")
        .build();
    
    Msg response = agent.reply(userMessage).block();

    System.out.println("Agent Response: " + response.getTextContent());
}
```

## Creating From Scratch
You may want to create an agent from scratch, AgentScope provides two base classes for you to inherit from:

| Class | Abstract Methods | Description |
| --- | --- | --- |
| `AgentBase` | `reply`<br/>`observe`<br/>`handleInterrupt`<br/> | + The base class for all agents, supporting pre- and post- hooks around `reply` and `observe` functions.<br/> |
| `ReActAgentBase` | `reply`<br/>`observe`<br/>`reasoning`<br/>`acting` | Add two abstract functions `reasoning` and `acting` on the basis of `AgentBase`, as well as their hooks. |




## <font style="color:rgb(0, 0, 0);">Further Reading</font>
+ <font style="color:rgb(39, 87, 221);">Agent</font>
+ <font style="color:rgb(39, 87, 221);">Model</font>
+ <font style="color:rgb(39, 87, 221);">Prompt Formatter</font>
+ <font style="color:rgb(39, 87, 221);">Tool</font>

<font style="color:rgb(0, 0, 0);">  
</font>

