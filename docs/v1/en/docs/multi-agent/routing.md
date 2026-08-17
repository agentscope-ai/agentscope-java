# Routing

In the **router** pattern, a routing step classifies the input and directs it to specialized agents. Zero or more specialists can be invoked (e.g. in parallel), and results are synthesized into a single response. This is useful when you have distinct **verticals**—separate knowledge domains that each have their own agent (e.g. GitHub, Notion, Slack).

The router decomposes the query, specialist agents are invoked (single or parallel), and a combined answer is produced.

## Overview

Key characteristics:

- **Classification step**: The router (e.g. an LLM or rule-based classifier) determines which specialist(s) to call and what sub-query to send to each.
- **Specialist agents**: Each vertical has its own agent (e.g. GitHub expert, Notion expert, Slack expert) implemented as **AgentScopeAgent** with AgentScope tools.
- **Synthesis**: Raw results from specialists are merged into one coherent answer (e.g. by a final LLM call).

Use the router pattern when you have clear input categories, need to query multiple sources (optionally in parallel), and want a single combined response.

## Architecture

```
Query → Router (classify) → [Agent A] → Synthesize → Combined answer
                    ↘ [Agent B] ↗
                    ↘ [Agent C] ↗
```

The AgentScope example provides two variants: **Simple** (single routing agent + service-layer synthesis) and **Graph** (StateGraph wrapping preprocess / routing / postprocess). Both use AgentScope `DashScopeChatModel` and **AgentScopeAgent** for each specialist (GitHub, Notion, Slack) with stub tools (`GitHubStubTools`, `NotionStubTools`, `SlackStubTools`).

### Simple vs Graph comparison

| Aspect | Simple | Graph |
|--------|--------|--------|
| **Entry** | `RouterService.run(query)` calls `AgentScopeRoutingAgent.invoke(query)` internally | `RoutingGraphService.run(query)` calls `CompiledGraph.invoke(Map.of("input", query))` internally |
| **Flow** | One invoke does: classify → parallel specialists → framework merge; then RouterService optionally runs one LLM synthesis | Graph: START → preprocess → **routing (subgraph)** → postprocess → END; inside routing it is still classify → parallel specialists → merge |
| **State** | Managed inside `AgentScopeRoutingAgent`; RouterService reads `xxx_key`, `xxx_input`, `RoutingMergeNode.DEFAULT_MERGED_OUTPUT_KEY` from returned `OverAllState` | Explicit StateGraph keys: `input`, `query`, `messages`, `preprocess_metadata`, `merged_result`, `final_answer`, `postprocess_metadata`, and each specialist `xxx_key`; each key has a `KeyStrategy` (Replace/Append) |
| **Extensibility** | No graph; you cannot insert custom nodes before/after routing | You can add logic in preprocess/postprocess (validation, tracing, formatting); you can add nodes or conditional edges |
| **Return** | `RouterResult(query, classifications, results, finalAnswer)` with classification list and raw specialist outputs | `RoutingGraphResult(query, state, finalAnswer)` with full graph state for debugging or downstream use |

---

## Implementation (Simple variant)

### Design points

- **Single call for routing**: Callers use only `RouterService.run(query)`; inside, **AgentScopeRoutingAgent** runs one `invoke(query)` that does classify, parallel specialists, and framework merge.
- **Synthesis optional**: If the routing framework has written `RoutingMergeNode.DEFAULT_MERGED_OUTPUT_KEY`, that value is used as the final answer; otherwise **RouterService** runs one more LLM call with the same `Model` to synthesize specialist results into one reply.

### Code structure (package `io.agentscope.examples.routing.simple`)

| Class | Role |
|-------|------|
| **RoutingConfig** | Defines `Model`, three specialist **AgentScopeAgent**s (github, notion, slack), **AgentScopeRoutingAgent** (router), and **RouterService** |
| **RouterService** | `run(query)`: invokes router.invoke → collects classifications and specialist outputs from state → uses merged value or calls `synthesize()` → returns **RouterResult** |
| **Classification** | One routing decision: `source` (specialist name), `query` (sub-query sent to that specialist) |
| **AgentOutput** | One specialist output: `source`, `result` (text) |
| **RoutingRunner** | When `routing.runner.enabled=true`, runs one example query on startup and logs |

### Beans and conventions

1. **Model**: One `Model` bean (e.g. `DashScopeChatModel`) shared by the router and all specialists.
2. **Specialist AgentScopeAgent**: One per vertical, with:
   - `name` / `description`: used by the router for classification;
   - **instruction**: template with placeholder `{agentName_input}` (e.g. `{github_input}`), filled at runtime by the routing node with the sub-query;
   - **outputKey**: state key where this specialist’s result is written, e.g. `github_key`, `notion_key`, `slack_key`;
   - **Toolkit**: stub tools for that vertical (e.g. `GitHubStubTools` with `search_code`, `search_issues`).
3. **AgentScopeRoutingAgent**: `AgentScopeRoutingAgent.builder().model(...).subAgents(List.of(githubAgent, notionAgent, slackAgent)).build()`; internally it does classify + parallel specialist calls + writes to each `outputKey` and merge key.
4. **RouterService**: Holds `Model` and `AgentScopeRoutingAgent`; after `run(query)`, if synthesis is needed it formats specialist results into a prompt and calls the model once to produce `finalAnswer`.

### RouterService.run logic (summary)

1. `routerAgent.invoke(query)` → obtain `OverAllState`.
2. Iterate over `github_key`, `notion_key`, `slack_key`: if present in state, read `agentName_input` and specialist output from state and build `Classification` and `AgentOutput` lists.
3. If state contains `RoutingMergeNode.DEFAULT_MERGED_OUTPUT_KEY`, use its content as `finalAnswer`; otherwise call `synthesize(query, results)` (same Model, system prompt to merge multi-source results to answer the original question) and use its return as `finalAnswer`.
4. Return `RouterResult(query, classifications, results, finalAnswer)`.

---

## Implementation (Graph variant)

### Design points

- **Routing as embedded subgraph**: The overall flow is a **StateGraph**; the “routing” step is not a single bean call but the **compiled subgraph** returned by **AgentScopeRoutingAgent.getAndCompileGraph()** used as one node; inside that subgraph it is still classify → parallel specialists → merge.
- **Explicit pre- and post-processing**: **PreprocessNode** and **PostprocessNode** run before and after the routing node for validation, normalization, tracing, and formatting; state flows through the whole graph for easier extension and observability.

### Code structure (package `io.agentscope.examples.routing.graph`)

| Class | Role |
|-------|------|
| **RoutingGraphConfig** | Defines three specialist **AgentScopeAgent**s and **AgentScopeRoutingAgent**; builds **StateGraph** (preprocess → routing → postprocess), defines **KeyStrategy** per state key; exposes **CompiledGraph** and **RoutingGraphService** |
| **PreprocessNode** | `NodeAction`: reads `input`/`query` from state → validates non-empty, length ≥ 3 → normalizes (trim, truncate to 2000 chars) → writes `input`, `messages`, `preprocess_metadata` (traceId, timestamp) |
| **PostprocessNode** | `NodeAction`: reads `RoutingMergeNode.DEFAULT_MERGED_OUTPUT_KEY` and `preprocess_metadata` → formats into `final_answer` with traceId/timestamp → writes `postprocess_metadata` |
| **RoutingGraphService** | `run(query)`: `invoke(Map.of("input", query))` → reads `final_answer` from state (or `merged_result` if absent) → returns **RoutingGraphResult(query, state, finalAnswer)** |
| **RoutingGraphRunner** | When `routing-graph.runner.enabled=true`, runs one example query on startup and logs |

### Graph and state keys

- **StateGraph**: `new StateGraph("routing_graph", keyFactory)` with edges `START → preprocess → routing → postprocess → END`.
- **keyFactory**: Configures strategy for `input`, `query`, `messages`, `preprocess_metadata`, `merged_result`, `final_answer`, `postprocess_metadata`, and each specialist `xxx_key` (mostly `ReplaceStrategy`; `messages` uses `AppendStrategy(false)`).
- **preprocess node**: `node_async(new PreprocessNode())`, pure function node (no LLM); output is consumed by the routing subgraph.
- **routing node**: `routerAgent.getAndCompileGraph()` — the whole “classify + parallel specialists + merge” logic is attached as a subgraph to this node; subgraph output is written to `merged_result` (and each `xxx_key`).
- **postprocess node**: `node_async(new PostprocessNode())`, reads merge result and preprocess metadata and writes `final_answer` and `postprocess_metadata`.

### How PreprocessNode / PostprocessNode differ from Simple

- **Simple**: No dedicated preprocessing; if “synthesis” is needed it is a single Model call inside **RouterService**, with no shared state keys or trace metadata.
- **Graph**: Preprocess centralizes validation and normalization and writes `preprocess_metadata` (e.g. traceId); postprocess centralizes final formatting and logging and writes `postprocess_metadata`; the full pipeline shares one state, so you can extend with conditional branches or extra nodes.

---

## Example project

**Location**: `agentscope-examples/multiagent-patterns/routing/`

- **Simple**: Entry is `RouterService.run(query)`; flow is classify → parallel specialists → (framework merge) → optional RouterService synthesis. Set `routing.runner.enabled=true` to run the Simple demo on startup (see **RoutingRunner**).
- **Graph**: Entry is `RoutingGraphService.run(query)`; flow is preprocess → routing subgraph → postprocess. Set `routing-graph.runner.enabled=true` to run the Graph demo on startup (see **RoutingGraphRunner**).

**Build and run** (from repo root):

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/routing -am -B package -DskipTests
./mvnw -pl agentscope-examples/multiagent-patterns/routing spring-boot:run
```

**Run Simple demo only**:

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/routing spring-boot:run \
  -Dspring-boot.run.arguments="--routing.runner.enabled=true"
```

**Run Graph demo only**:

```bash
./mvnw -pl agentscope-examples/multiagent-patterns/routing spring-boot:run \
  -Dspring-boot.run.arguments="--routing-graph.runner.enabled=true"
```

**Configuration**: Set `AI_DASHSCOPE_API_KEY` (or `spring.ai.dashscope.api-key`) for DashScope model and specialist calls.

## Related Documentation

- [Pipeline](./pipeline.md) - Sequential and parallel agent composition
- [MsgHub](../task/msghub.md) - Message broadcasting for multi-agent conversations
- [Agent as Tool](../task/agent-as-tool.md) - Registering an agent as a tool
