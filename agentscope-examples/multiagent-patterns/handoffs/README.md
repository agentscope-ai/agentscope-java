# Handoffs Multi-Agent Example

This module implements the **handoffs** pattern with **AgentScope**: sales and support agents as separate graph nodes, using **AgentScopeAgent** and handoff tools to transfer control. Distinct sales and support agents exist as separate nodes in a StateGraph. Handoff tools navigate between agent nodes by updating `active_agent`, which the parent graph's conditional edges use for routing.

## Architecture

- **Separate agents as graph nodes**  
  Sales and support agents are separate nodes. Both use AgentScope ReActAgent via `AgentScopeAgent` with Toolkit. The parent StateGraph routes between them based on `active_agent`.

- **Handoff tools**  
  - `TransferToSupportTool`: Sales agent (AgentScopeAgent) uses it to hand off to support. Registered via AgentScope Toolkit; uses `ToolContextHelper.getStateForUpdate()` to update `active_agent`.
  - `TransferToSalesTool`: Support agent (AgentScopeAgent) uses it to hand off to sales. Registered via AgentScope Toolkit; uses `ToolContextHelper.getStateForUpdate()` to update `active_agent`.

- **Conditional routing**  
  - `route_initial`: START → sales_agent or support_agent (default: sales).
  - `route_after_sales`: If `active_agent` is support_agent → support_agent; else → END.
  - `route_after_support`: If `active_agent` is sales_agent → sales_agent; else → END.

## Modifying state in AgentScope tools

AgentScope tools receive `ToolContext` (auto-injected) and can read/update graph state:

```java
@Tool(name = "transfer_to_sales", description = "...")
public String transferToSales(
        @ToolParam(name = "reason", description = "...") String reason,
        ToolContext toolContext) {
    // Update state: put keys into the map; merged when node completes
    ToolContextHelper.getStateForUpdate(toolContext).ifPresent(update ->
            update.put("active_agent", "sales_agent"));
    return "Transferred to sales agent.";
}
```

**Read state:**
```java
OverAllState state = ToolContextHelper.getState(toolContext).orElse(null);
```

**Update state:**  
Put keys into `getStateForUpdate(toolContext)`; the graph must declare those keys in its key strategies (e.g. `ReplaceStrategy` for `active_agent` or `extraState`).

See `UpdateExtraStateTool` for a full example that reads state and updates `extraState`.

## Design choices

1. **AgentScope Toolkit for both agents**  
   Sales and support agents both use `io.agentscope.core.tool.Toolkit` and `ReActAgent.builder().toolkit(toolkit)`. Handoff tools use `io.agentscope.core.tool.Tool`; `ToolContext` is auto-injected for state updates.

2. **State update**  
   Tools use `ToolContextHelper.getStateForUpdate(toolContext)` to set `active_agent` (or other keys). The update is merged into the graph state when the agent node completes.

3. **ToolContext in tools**  
   Tools use `io.agentscope.core.tool.Tool` and optional `@ToolParam`; `ToolContext` is auto-injected for reading/updating graph state.

## Project layout

```
agentscope-examples/multiagent-patterns/handoffs/
├── README.md
├── pom.xml
└── src/main/
    ├── java/.../handoffs/multiagent/
    │   ├── AgentScopeApplication.java
    │   ├── AgentScopeHandoffsConfig.java   # StateGraph, agents, routing
    │   ├── AgentScopeHandoffsService.java  # invokes graph
    │   ├── AgentScopeHandoffsRunner.java   # optional demo (agentscope.runner.enabled)
    │   ├── route/
    │   │   ├── RouteInitialAction.java     # START → sales or support
    │   │   ├── RouteAfterSalesAction.java  # sales → support or END
    │   │   └── RouteAfterSupportAction.java # support → sales or END
    │   ├── state/
    │   │   └── AgentScopeStateConstants.java
    │   └── tools/
    │       ├── TransferToSalesTool.java    # support → sales (AgentScope Toolkit)
    │       ├── TransferToSupportTool.java  # sales → support (AgentScope Toolkit)
    │       └── UpdateExtraStateTool.java   # read state + update extraState
    └── resources/
        └── application.yml
```

## How to run

### Prerequisites

- JDK 17+
- Maven 3.6+
- **DashScope API key**: `export AI_DASHSCOPE_API_KEY=your-key`

### Build

From the repo root:

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/handoffs -am -B package -DskipTests
```

Or from this directory:

```bash
cd agentscope-examples/multiagent-patterns/handoffs
mvn -B package -DskipTests
```

### Run the demo on startup

Set `agentscope.runner.enabled=true` in `application.yml`, then start the app:

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/handoffs spring-boot:run
```

Or run without the demo:

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/handoffs spring-boot:run
```

Default port is 8089. Open the chat UI at `http://localhost:8089/chatui/index.html` (if available) to interact with Sales/Support and see handoffs.

### Using in your own code

```java
@Autowired
AgentScopeHandoffsService service;

var result = service.run("Hi, I'm having trouble with my account login. Can you help?");
result.messages().forEach(msg -> System.out.println(msg.getText()));
```

## Configuration

- **`spring.ai.dashscope.api-key`**  
  Required. Defaults to `AI_DASHSCOPE_API_KEY` env var.

- **`agentscope.runner.enabled`**  
  If `true`, runs the AgentScope multi-agent handoffs demo on startup. Default: `false`.

## Related

- [AgentScope Java](https://java.agentscope.io/) - AgentScope framework documentation
