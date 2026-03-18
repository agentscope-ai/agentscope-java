# Handoffs（交接）

在 **Handoffs** 模式中，行为根据状态动态变化。工具会更新一个在轮次间持久存在的状态变量（如 `active_agent`），图根据该变量将请求路由到不同智能体。该模式适合客服、销售等场景：通过工具调用在专职智能体（如销售与支持）之间转移控制权。

**Handoffs** 一词常用来描述通过工具调用（如 `transfer_to_sales_agent`、`transfer_to_support`）在智能体或状态之间转移控制（可参考 [OpenAI Agents](https://openai.github.io/openai-agents-python/handoffs/)）。

## 概述

核心特点：

- **状态驱动路由**：流程运行过程中根据状态变量（如 `active_agent`）将请求路由到不同智能体节点。
- **基于工具的转移**：交接工具更新该状态变量，当前节点完成后，图沿条件边进入下一智能体或结束。
- **直接与用户交互**：每个智能体依次处理用户消息；交接工具只决定下一轮由谁处理。
- **状态持久**：在流程的执行过程中，状态在多次对话轮次间保持。

当需要强制顺序或按角色路由（例如先收集信息再升级、或在销售与支持间切换），且用户每次只与一个“前台”对话、该前台可随工具调用切换时，适合使用 Handoffs 模式。

## 架构

在 AgentScope 与 Spring AI Alibaba Graph 下，实现包含：

- **独立智能体作为图节点**：例如销售智能体与支持智能体，各自实现为 `AgentScopeAgent`（ReActAgent + Toolkit）。
- **交接工具**：注册在各智能体的 Toolkit 上。模型调用交接工具时，工具通过 `ToolContextHelper.getStateForUpdate(toolContext)` 设置 `active_agent`（或类似键）。图需为该键声明策略（如 `ReplaceStrategy`），以便在节点完成时合并更新。
- **条件边**：每个智能体节点执行后，由路由动作读取 `active_agent`，决定进入另一智能体节点或 `END`。

```
┌─────────────────────────────────────────────────────────────────┐
│                    Handoffs 状态图                                │
│                                                                  │
│   START ──route_initial──► sales_agent ──route_after_sales──┐   │
│        \\                      │                             │   │
│         \\                     │ (transfer_to_support)        │   │
│          \\                    ▼                             ▼   │
│           └────────────► support_agent ──route_after_support  END  │
│                                │                             ▲   │
│                                │ (transfer_to_sales)         │   │
│                                └─────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

## 实现

### 步骤 1：定义状态键与图状态

定义状态键和智能体节点名称常量，并创建带键策略的 `StateGraph`，以便交接工具能更新路由状态（如对 `active_agent` 使用 `ReplaceStrategy`）。

```java
// 状态键与智能体节点名
public final class AgentScopeStateConstants {
    public static final String ACTIVE_AGENT = "active_agent";
    public static final String SALES_AGENT = "sales_agent";
    public static final String SUPPORT_AGENT = "support_agent";
}

// 在配置中：构建带键策略的图
StateGraph graph = new StateGraph("agent_scope_handoffs", () -> {
    Map<String, KeyStrategy> strategies = new HashMap<>();
    strategies.put("messages", new AppendStrategy(false));
    strategies.put(AgentScopeStateConstants.ACTIVE_AGENT, new ReplaceStrategy());
    return strategies;
});
```

### 步骤 2：创建交接工具

每个交接工具是一个 AgentScope `@Tool`，并自动注入 `ToolContext`。工具通过 `ToolContextHelper.getStateForUpdate(toolContext)` 更新图状态，节点完成后，图的条件边会根据新的 `active_agent` 选择下一节点或结束。

**转接支持（供销售智能体使用）：**

```java
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.ai.chat.model.ToolContext;

@Tool(
    name = "transfer_to_support",
    description = "将对话转接给支持智能体。当客户询问技术问题、故障排查或账户问题时使用。")
public String transferToSupport(ToolContext toolContext) {
    ToolContextHelper.getStateForUpdate(toolContext).ifPresent(update ->
            update.put(AgentScopeStateConstants.ACTIVE_AGENT, AgentScopeStateConstants.SUPPORT_AGENT));
    return "已从销售智能体转接至支持智能体。";
}
```

**转接销售（供支持智能体使用）：**

```java
@Tool(
    name = "transfer_to_sales",
    description = "将对话转接给销售智能体。当客户询问价格、购买或产品库存时使用。")
public String transferToSales(
        @ToolParam(name = "reason", description = "转接的简要原因") String reason,
        ToolContext toolContext) {
    ToolContextHelper.getStateForUpdate(toolContext).ifPresent(update ->
            update.put(AgentScopeStateConstants.ACTIVE_AGENT, AgentScopeStateConstants.SALES_AGENT));
    return "已从支持智能体转接至销售智能体。原因：" + (reason != null ? reason : "客户需要销售");
}
```

在对应智能体的 Toolkit 上通过 `toolkit.registerTool(TransferToSupportTool.create())` 和 `toolkit.registerTool(TransferToSalesTool.create())` 注册上述工具。

### 步骤 3：构建智能体（AgentScopeAgent + Toolkit）

创建销售与支持两个 `AgentScopeAgent`，各自使用 ReActAgent、系统提示和包含对应交接工具的 Toolkit。

```java
import com.alibaba.cloud.ai.graph.agent.agentscope.AgentScopeAgent;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;

// 销售智能体：具备 transfer_to_support
Toolkit salesToolkit = new Toolkit();
salesToolkit.registerTool(TransferToSupportTool.create());

ReActAgent.Builder salesReActBuilder = ReActAgent.builder()
        .name(AgentScopeStateConstants.SALES_AGENT)
        .description("负责价格、产品库存与销售咨询的销售智能体")
        .sysPrompt("""
            你是销售智能体。负责销售咨询、价格与产品库存。
            若客户询问技术问题、故障排查或账户问题，
            请使用 transfer_to_support 转接给支持智能体。
            """)
        .model(model)
        .toolkit(salesToolkit)
        .memory(new InMemoryMemory());

AgentScopeAgent salesAgent = AgentScopeAgent.fromBuilder(salesReActBuilder)
        .name(AgentScopeStateConstants.SALES_AGENT)
        .instruction("请协助客户处理销售相关咨询：{input}。")
        .includeContents(true)
        .returnReasoningContents(true)
        .build();

// 支持智能体：具备 transfer_to_sales
Toolkit supportToolkit = new Toolkit();
supportToolkit.registerTool(TransferToSalesTool.create());

ReActAgent.Builder supportReActBuilder = ReActAgent.builder()
        .name(AgentScopeStateConstants.SUPPORT_AGENT)
        .description("负责技术问题与故障排查的支持智能体")
        .sysPrompt("""
            你是支持智能体。负责技术问题、故障排查与账户问题。
            若客户询问价格、购买或产品库存，
            请使用 transfer_to_sales 转接给销售智能体。
            """)
        .model(model)
        .toolkit(supportToolkit)
        .memory(new InMemoryMemory());

AgentScopeAgent supportAgent = AgentScopeAgent.fromBuilder(supportReActBuilder)
        .name(AgentScopeStateConstants.SUPPORT_AGENT)
        .instruction("请协助客户处理产品技术相关咨询：{input}。")
        .includeContents(true)
        .returnReasoningContents(true)
        .build();
```

### 步骤 4：添加节点与条件边

将两个智能体加入为节点，再根据 `active_agent` 配置 START 与节点后路由：

- **初始路由**：从 START 路由到 `sales_agent` 或 `support_agent`（如未设置 `active_agent` 则默认 `sales_agent`）。
- **销售之后**：从 `sales_agent` 出来时，若 `active_agent` 为 `support_agent` 则进入 `support_agent`，否则进入 `END`。
- **支持之后**：从 `support_agent` 出来时，若 `active_agent` 为 `sales_agent` 则进入 `sales_agent`，否则进入 `END`。

```java
graph.addNode(AgentScopeStateConstants.SALES_AGENT, salesAgent.asNode());
graph.addNode(AgentScopeStateConstants.SUPPORT_AGENT, supportAgent.asNode());

// START → sales_agent 或 support_agent（默认 sales）
graph.addConditionalEdges(START, new RouteInitialAction(), Map.of(
        AgentScopeStateConstants.SALES_AGENT, AgentScopeStateConstants.SALES_AGENT,
        AgentScopeStateConstants.SUPPORT_AGENT, AgentScopeStateConstants.SUPPORT_AGENT));

// sales_agent → support_agent 或 END
graph.addConditionalEdges(AgentScopeStateConstants.SALES_AGENT, new RouteAfterSalesAction(),
        Map.of(AgentScopeStateConstants.SUPPORT_AGENT, AgentScopeStateConstants.SUPPORT_AGENT, "__end__", END));

// support_agent → sales_agent 或 END
graph.addConditionalEdges(AgentScopeStateConstants.SUPPORT_AGENT, new RouteAfterSupportAction(),
        Map.of(AgentScopeStateConstants.SALES_AGENT, AgentScopeStateConstants.SALES_AGENT, "__end__", END));

CompiledGraph compiledGraph = graph.compile();
```

**路由动作示例（销售之后）**：从状态读取 `active_agent`；若为 `support_agent` 则返回支持节点，否则返回 `"__end__"`。

```java
@Override
public CompletableFuture<Command> apply(OverAllState state, RunnableConfig config) {
    String active = state.value(AgentScopeStateConstants.ACTIVE_AGENT)
            .map(Object::toString)
            .orElse("");
    String target = AgentScopeStateConstants.SUPPORT_AGENT.equals(active)
            ? AgentScopeStateConstants.SUPPORT_AGENT
            : "__end__";
    return CompletableFuture.completedFuture(new Command(target));
}
```

### 步骤 5：调用图

使用用户输入调用编译后的图。图会从初始智能体（如销售）开始执行；每当某智能体调用交接工具时，状态更新会使下一步路由到另一智能体或结束。

```java
Map<String, Object> inputs = Map.of("input", "你好，我登录账户遇到问题，能帮忙吗？");
Optional<OverAllState> resultOpt = compiledGraph.invoke(inputs);

resultOpt.ifPresent(state -> {
    List<Message> messages = (List<Message>) state.value("messages").orElse(List.of());
    messages.forEach(msg -> System.out.println(msg.getText()));
});
```

## 在工具中读取与更新状态

参与图的 AgentScope 工具会（自动注入）收到 `ToolContext`，用于读取或更新图状态：

- **更新状态（用于路由）**：将键放入 `ToolContextHelper.getStateForUpdate(toolContext)` 返回的 Map。节点完成时图会合并这些更新。图必须为这些键声明键策略（如对 `active_agent` 使用 `ReplaceStrategy`）。
- **读取状态**：使用 `ToolContextHelper.getState(toolContext)` 获取当前 `OverAllState`（例如在工具内分支或向交接消息中传入上下文）。

通过 `getStateForUpdate` 更新的任何键都必须在图的键策略中声明，否则更新可能不会影响路由。

## 设计要点

1. **两个智能体均使用 AgentScope Toolkit**  
   销售与支持智能体都使用 `io.agentscope.core.tool.Toolkit` 和 ReActAgent；交接工具是标准的 AgentScope `@Tool` 实现，通过 `ToolContext` 更新状态。

2. **在节点完成时合并状态**  
   工具不会立即改变图的走向，只更新状态。当前智能体节点结束后，图的条件边会执行，根据更新后的 `active_agent` 选择下一节点或结束。

3. **工具中的 ToolContext**  
   交接工具使用 `io.agentscope.core.tool.Tool` 和可选的 `@ToolParam`；`ToolContext` 自动注入，便于调用 `ToolContextHelper.getStateForUpdate(toolContext)`（以及可选的 `getState(toolContext)`）。

## 示例项目

完整的 Handoffs 示例（销售 + 支持与交接工具）位于仓库中：

- **路径**：`agentscope-examples/multiagent-patterns/handoffs/`
- **要点**：`AgentScopeHandoffsConfig`（图、智能体、路由），`TransferToSalesTool`、`TransferToSupportTool`，`RouteInitialAction`、`RouteAfterSalesAction`、`RouteAfterSupportAction`，以及用于调用图的 `AgentScopeHandoffsService`。

在仓库根目录构建并运行：

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/handoffs -am -B package -DskipTests
./mvnw -pl agentscope-examples/multiagent-patterns/handoffs spring-boot:run
```

在 `application.yml` 中设置 `agentscope.runner.enabled=true` 可在启动时运行演示。默认端口为 8089。

## 相关文档

- [Pipeline](./pipeline.md) - 顺序与并行智能体执行
- [Routing](./routing.md) - 分类并路由到专家智能体
- [Supervisor](./supervisor.md) - 中心监督者与专职智能体即工具
- [MsgHub](../task/msghub.md) - 多智能体对话的消息广播
- [Agent as Tool](../task/agent-as-tool.md) - 将智能体注册为工具供其他智能体调用
- [工具系统](../task/tool.md) - AgentScope 工具与 Toolkit
