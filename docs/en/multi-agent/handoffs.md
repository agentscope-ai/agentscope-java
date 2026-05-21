# Handoffs

In the **handoffs** pattern, behavior changes dynamically based on state. Tools update a state variable (e.g. `active_agent`) that persists across turns; the graph reads this variable to route to different agents. This pattern is well-suited to customer support and sales flows where control transfers between specialized agents (e.g. sales vs. support) via tool calls.

The term **handoffs** is commonly used for using tool calls (such as `transfer_to_sales_agent` or `transfer_to_support`) to transfer control between agents or states (see e.g. [OpenAI Agents](https://openai.github.io/openai-agents-python/handoffs/).

## Overview

Key characteristics:

- **State-driven routing**: The graph routes to different agent nodes based on a state variable (e.g. `active_agent`).
- **Tool-based transitions**: Handoff tools update that state variable so that when the current node completes, the graph follows conditional edges to the next agent (or end).
- **Direct user interaction**: Each agent handles the user message in turn; handoff tools only change who handles the next turn.
- **Persistent state**: State survives across conversation turns within the graph.

Use the handoffs pattern when you need to enforce sequential or role-based routing (e.g. collect information before escalating, or switch between sales and support), and when the user converses with a single “front” at a time that can change based on tool calls.

## Architecture

With AgentScope and Spring AI Graph, the implementation uses:

- **Separate agents as graph nodes**: e.g. a sales agent and a support agent, each implemented as an `AgentScopeAgent` (ReActAgent + Toolkit).
- **Handoff tools**: Registered on each agent’s Toolkit. When the model calls a handoff tool, the tool uses `ToolContextHelper.getStateForUpdate(toolContext)` to set `active_agent` (or similar). The graph declares that key with a strategy (e.g. `ReplaceStrategy`) so the update is merged when the node completes.
- **Conditional edges**: After each agent node, a routing action reads `active_agent` and either goes to another agent node or to `END`.

```
┌─────────────────────────────────────────────────────────────────┐
│                    Handoffs StateGraph                          │
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

## Implementation

### Step 1: Define state keys and graph state

Define constants for state keys and agent node names, and create a `StateGraph` with key strategies so that handoff tools can update routing state (e.g. `active_agent` with `ReplaceStrategy`).

```java
// State keys and agent names
public final class AgentScopeStateConstants {
    public static final String ACTIVE_AGENT = "active_agent";
    public static final String SALES_AGENT = "sales_agent";
    public static final String SUPPORT_AGENT = "support_agent";
}

// In your config: build the graph with key strategies
StateGraph graph = new StateGraph("agent_scope_handoffs", () -> {
    Map<String, KeyStrategy> strategies = new HashMap<>();
    strategies.put("messages", new AppendStrategy(false));
    strategies.put(AgentScopeStateConstants.ACTIVE_AGENT, new ReplaceStrategy());
    return strategies;
});
```

### Step 2: Create handoff tools

Each handoff tool is an AgentScope `@Tool` with `ToolContext` auto-injected. The tool updates the graph state via `ToolContextHelper.getStateForUpdate(toolContext)` so that when the node completes, the graph’s conditional edges see the new `active_agent` and route accordingly.

**Transfer to support (used by the sales agent):**

```java
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.ai.chat.model.ToolContext;

@Tool(
    name = "transfer_to_support",
    description = "Transfer the conversation to the support agent. Use when the customer asks about technical issues, troubleshooting, or account problems.")
public String transferToSupport(ToolContext toolContext) {
    ToolContextHelper.getStateForUpdate(toolContext).ifPresent(update ->
            update.put(AgentScopeStateConstants.ACTIVE_AGENT, AgentScopeStateConstants.SUPPORT_AGENT));
    return "Transferred to support agent from sales agent.";
}
```

**Transfer to sales (used by the support agent):**

```java
@Tool(
    name = "transfer_to_sales",
    description = "Transfer the conversation to the sales agent. Use when the customer asks about pricing, purchasing, or product availability.")
public String transferToSales(
        @ToolParam(name = "reason", description = "Brief reason for the transfer") String reason,
        ToolContext toolContext) {
    ToolContextHelper.getStateForUpdate(toolContext).ifPresent(update ->
            update.put(AgentScopeStateConstants.ACTIVE_AGENT, AgentScopeStateConstants.SALES_AGENT));
    return "Transferred to sales agent from support agent. Reason: " + (reason != null ? reason : "customer needs sales");
}
```

Register each tool on the corresponding agent’s Toolkit via `toolkit.registerTool(TransferToSupportTool.create())` and `toolkit.registerTool(TransferToSalesTool.create())`.

### Step 3: Build the agents (AgentScopeAgent + Toolkit)

Create a sales and a support agent as `AgentScopeAgent`, each with its own ReActAgent, system prompt, and Toolkit that includes the appropriate handoff tool.

```java
import com.alibaba.cloud.ai.agent.agentscope.AgentScopeAgent;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.model.DashScopeChatModel;
import io.agentscope.core.tool.Toolkit;

// Sales agent: has transfer_to_support
Toolkit salesToolkit = new Toolkit();
salesToolkit.registerTool(TransferToSupportTool.create());

ReActAgent.Builder salesReActBuilder = ReActAgent.builder()
        .name(AgentScopeStateConstants.SALES_AGENT)
        .description("Sales agent for pricing, product availability, and sales inquiries")
        .sysPrompt("""
            You are a sales agent. Help with sales inquiries, pricing, and product availability.
            If the customer asks about technical issues, troubleshooting, or account problems,
            use transfer_to_support to hand off to the support agent.
            """)
        .model(model)
        .toolkit(salesToolkit)
        .memory(new InMemoryMemory());

AgentScopeAgent salesAgent = AgentScopeAgent.fromBuilder(salesReActBuilder)
        .name(AgentScopeStateConstants.SALES_AGENT)
        .description("Sales agent for pricing, product availability, and sales inquiries")
        .instruction("please assist the customer with their sales inquiry: {input}.")
        .includeContents(true)
        .returnReasoningContents(true)
        .build();

// Support agent: has transfer_to_sales
Toolkit supportToolkit = new Toolkit();
supportToolkit.registerTool(TransferToSalesTool.create());

ReActAgent.Builder supportReActBuilder = ReActAgent.builder()
        .name(AgentScopeStateConstants.SUPPORT_AGENT)
        .description("Support agent for technical issues and troubleshooting")
        .sysPrompt("""
            You are a support agent. Help with technical issues, troubleshooting, and account problems.
            If the customer asks about pricing, purchasing, or product availability,
            use transfer_to_sales to hand off to the sales agent.
            """)
        .model(model)
        .toolkit(supportToolkit)
        .memory(new InMemoryMemory());

AgentScopeAgent supportAgent = AgentScopeAgent.fromBuilder(supportReActBuilder)
        .name(AgentScopeStateConstants.SUPPORT_AGENT)
        .description("Support agent for technical issues and troubleshooting")
        .instruction("please assist the customer with their product technical inquiry: {input}.")
        .includeContents(true)
        .returnReasoningContents(true)
        .build();
```

### Step 4: Add nodes and conditional edges

Add both agents as nodes, then wire START and post-node routing based on `active_agent`:

- **Initial route**: From START, route to `sales_agent` or `support_agent` (e.g. default to `sales_agent` if `active_agent` is unset).
- **After sales**: From `sales_agent`, if `active_agent` is `support_agent` go to `support_agent`, else go to `END`.
- **After support**: From `support_agent`, if `active_agent` is `sales_agent` go to `sales_agent`, else go to `END`.

```java
graph.addNode(AgentScopeStateConstants.SALES_AGENT, salesAgent.asNode());
graph.addNode(AgentScopeStateConstants.SUPPORT_AGENT, supportAgent.asNode());

// START → sales_agent or support_agent (default: sales)
graph.addConditionalEdges(START, new RouteInitialAction(), Map.of(
        AgentScopeStateConstants.SALES_AGENT, AgentScopeStateConstants.SALES_AGENT,
        AgentScopeStateConstants.SUPPORT_AGENT, AgentScopeStateConstants.SUPPORT_AGENT));

// sales_agent → support_agent or END
graph.addConditionalEdges(AgentScopeStateConstants.SALES_AGENT, new RouteAfterSalesAction(),
        Map.of(AgentScopeStateConstants.SUPPORT_AGENT, AgentScopeStateConstants.SUPPORT_AGENT, "__end__", END));

// support_agent → sales_agent or END
graph.addConditionalEdges(AgentScopeStateConstants.SUPPORT_AGENT, new RouteAfterSupportAction(),
        Map.of(AgentScopeStateConstants.SALES_AGENT, AgentScopeStateConstants.SALES_AGENT, "__end__", END));

CompiledGraph compiledGraph = graph.compile();
```

**Routing action example (after sales):** read `active_agent` from state; if it is `support_agent`, return the support node; otherwise return `"__end__"`.

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

### Step 5: Invoke the graph

Invoke the compiled graph with the user input. The graph will start at the initial agent (e.g. sales), and each time an agent calls a handoff tool, the state update will cause the next step to route to the other agent or to end.

```java
Map<String, Object> inputs = Map.of("input", "Hi, I'm having trouble with my account login. Can you help?");
Optional<OverAllState> resultOpt = compiledGraph.invoke(inputs);

resultOpt.ifPresent(state -> {
    List<Message> messages = (List<Message>) state.value("messages").orElse(List.of());
    messages.forEach(msg -> System.out.println(msg.getText()));
});
```

## Reading and updating state in tools

AgentScope tools that participate in a graph receive `ToolContext` (auto-injected). Use it to read or update graph state:

- **Update state (for routing):** Put keys into the map returned by `ToolContextHelper.getStateForUpdate(toolContext)`. The graph merges this when the node completes. The graph must declare those keys with a key strategy (e.g. `ReplaceStrategy` for `active_agent`).

- **Read state:** Use `ToolContextHelper.getState(toolContext)` to get the current `OverAllState` (e.g. to branch inside the tool or to pass context into the handoff message).

Any key you update via `getStateForUpdate` must be declared in the graph’s key strategies; otherwise the update may not affect routing.

## Design choices

1. **AgentScope Toolkit for both agents**  
   Sales and support agents both use `io.agentscope.core.tool.Toolkit` and ReActAgent; handoff tools are standard AgentScope `@Tool` implementations with `ToolContext` for state updates.

2. **State update at node completion**  
   Tools do not redirect the graph immediately; they only update state. When the current agent node finishes, the graph’s conditional edges run and use the updated `active_agent` to choose the next node or end.

3. **ToolContext in tools**  
   Handoff tools use `io.agentscope.core.tool.Tool` and optional `@ToolParam`; `ToolContext` is auto-injected so tools can call `ToolContextHelper.getStateForUpdate(toolContext)` (and optionally `getState(toolContext)`).

## Example project

The full handoffs example (sales + support with handoff tools) is in the repository:

- **Location**: `agentscope-examples/multiagent-patterns/handoffs/`
- **Highlights**: `AgentScopeHandoffsConfig` (graph, agents, routing), `TransferToSalesTool`, `TransferToSupportTool`, `RouteInitialAction`, `RouteAfterSalesAction`, `RouteAfterSupportAction`, and `AgentScopeHandoffsService` to invoke the graph.

Build and run (from repo root):

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/handoffs -am -B package -DskipTests
./mvnw -pl agentscope-examples/multiagent-patterns/handoffs spring-boot:run
```

Set `agentscope.runner.enabled=true` in `application.yml` to run the demo on startup. Default port is 8089.

## Related Documentation

- [Pipeline](./pipeline.md) - Sequential and parallel agent execution
- [Routing](./routing.md) - Classify and route to specialist agents
- [Supervisor](./supervisor.md) - Central supervisor with specialized agents as tools
- [MsgHub](../task/msghub.md) - Message broadcasting for multi-agent conversations
- [Agent as Tool](../task/agent-as-tool.md) - Registering an agent as a tool for another agent
- [Tool System](../task/tool.md) - AgentScope tools and Toolkit
