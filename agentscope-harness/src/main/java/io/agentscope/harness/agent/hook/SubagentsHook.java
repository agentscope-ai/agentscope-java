/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.task.DefaultTaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.tool.TaskTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import reactor.core.publisher.Mono;

/**
 * Hook that provides the managed subagent mechanism.
 *
 * <p>In <strong>default mode</strong> (standalone {@code HarnessAgent}), this hook creates an
 * {@link AgentSpawnTool} backed by a {@link DefaultAgentManager}. In <strong>session mode</strong>
 * (orchestrated via {@code AgentBootstrap}), an external tool (typically {@code SessionsTool}) is
 * injected, replacing the default {@link AgentSpawnTool}.
 *
 * <p>Responsibilities:
 *
 * <ol>
 *   <li>Registers the subagent tool and {@link TaskTool} as agent tools
 *   <li>Injects rich subagent usage guidance into the unified system message at
 *       {@link PreReasoningEvent} time via {@link PreReasoningEvent#appendSystemContent}.
 *       Because each {@link PreReasoningEvent} starts from a fresh copy of the frozen base
 *       system message, calling {@code appendSystemContent} on every iteration is safe —
 *       content never accumulates across iterations.
 * </ol>
 */
public class SubagentsHook implements Hook {

    // @formatter:off
    private static final String SUBAGENT_SECTION_TEMPLATE =
            """

            ## Subagents

            You have access to subagent tools for spawning and coordinating isolated subagents.
            Subagents are ephemeral — they live only for the duration of the task and return a single result.

            ### Agent Tools

            **`%s`** — Spawn an isolated subagent
            - `agent_id` (required): which subagent to instantiate
            - `task` (optional): initial prompt; omit to create a persistent session
            - `label` (optional): human-readable name for referencing via send
            - `timeout_seconds`: wait time; 0=fire-and-forget (returns task_id), default=30, max=600
            - Response always includes `agent_key:` (opaque handle) — save it for follow-up sends

            **`%s`** — Send a follow-up message to an existing subagent
            - `agent_key`: copy the **full value** after `agent_key:` from spawn output (starts with `agent:`). This is NOT `agent_id`, NOT `session_id`, and NOT `task_id`
            - Or use `label` if you set one at spawn (mutually exclusive with agent_key)
            - `message` (required): content to send
            - `timeout_seconds`: 0=fire-and-forget, >0=wait for reply (default: 30)

            **`%s`** — List active subagents

            ### Task Tools (for async/background operations)

            **`task_output`** — Retrieve the result of a background task by task_id. Supports blocking wait (default) or non-blocking peek (block=false).

            **`task_cancel`** — Cancel a running background task by task_id. No effect on already-completed tasks.

            **`task_list`** — List all background tasks with current statuses. Optionally filter by status (running, completed, failed, cancelled).

            ### Available agent ids
            %s

            ### When to use subagents
            - When a task is complex and multi-step, and can be fully delegated in isolation
            - When a task is independent of other tasks and can run in parallel
            - When a task requires focused reasoning or heavy context usage that would bloat the main thread
            - When sandboxing improves reliability (e.g. code analysis, structured searches, data formatting)
            - When you only care about the output, not the intermediate steps (e.g. research → synthesized report)

            ### When NOT to use subagents
            - If the task is trivial (a few tool calls or simple lookup)
            - If you need to see intermediate reasoning or steps after completion
            - If delegating does not reduce token usage, complexity, or context switching
            - If splitting would add latency without benefit

            ### Subagent lifecycle
            1. **Spawn** → Provide clear role, instructions, and expected output format
            2. **Run** → The subagent completes the task autonomously
            3. **Return** → The subagent provides a single structured result
            4. **Reconcile** → Incorporate or synthesize the result into the main thread

            ### Usage patterns
            - **Parallel execution**: Launch multiple subagents concurrently with timeout_seconds=0 when tasks are independent, then collect results with task_output
            - **Sync delegation**: Use default timeout for simple one-shot delegation
            - **Persistent session**: Spawn without a task, then use send for multi-turn interaction
            - **Cancel stale work**: Use task_cancel to stop background tasks that are no longer needed
            - Subagent results are NOT visible to the user — always summarize them in your response
            """;
    // @formatter:on

    private final List<SubagentEntry> entries;
    private final Object subagentTool;
    private final TaskTool taskTool;
    private final boolean isSessionMode;

    /**
     * Default mode: creates {@link AgentSpawnTool} + {@link DefaultAgentManager} internally.
     *
     * @param entries subagent descriptors (agent_id, description, factory)
     * @param taskRepository background task store for async operations
     * @param workspaceManager workspace accessor for session file path resolution
     */
    public SubagentsHook(
            List<SubagentEntry> entries,
            TaskRepository taskRepository,
            WorkspaceManager workspaceManager) {
        this.entries = List.copyOf(entries);
        this.isSessionMode = false;
        Map<String, SubagentFactory> factories = buildFactories(entries);
        DefaultAgentManager dam = new DefaultAgentManager(factories, workspaceManager);
        TaskRepository repo = taskRepository != null ? taskRepository : new DefaultTaskRepository();
        this.subagentTool = new AgentSpawnTool(dam, repo, 0);
        this.taskTool = new TaskTool(repo);
    }

    /**
     * Session mode: uses the externally provided tool (typically {@code SessionsTool}).
     *
     * @param entries subagent descriptors (for prompt injection — agent id listing)
     * @param externalSubagentTool the external tool instance (e.g. SessionsTool)
     * @param taskRepository background task store for async operations
     */
    public SubagentsHook(
            List<SubagentEntry> entries,
            Object externalSubagentTool,
            TaskRepository taskRepository) {
        this.entries = List.copyOf(entries);
        this.isSessionMode = true;
        this.subagentTool = externalSubagentTool;
        TaskRepository repo = taskRepository != null ? taskRepository : new DefaultTaskRepository();
        this.taskTool = new TaskTool(repo);
    }

    public SubagentsHook(List<SubagentEntry> entries) {
        this(entries, (TaskRepository) null, (WorkspaceManager) null);
    }

    @Override
    public List<Object> tools() {
        if (entries.isEmpty()) {
            return List.of();
        }
        return List.of(subagentTool, taskTool);
    }

    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreReasoningEvent preReasoning) {
            injectSubagentPrompt(preReasoning);
        }
        return Mono.just(event);
    }

    @Override
    public int priority() {
        return 80;
    }

    private void injectSubagentPrompt(PreReasoningEvent event) {
        if (entries.isEmpty()) {
            return;
        }
        String agentList =
                entries.stream()
                        .map(e -> String.format("- `%s`: %s", e.name(), e.description()))
                        .collect(Collectors.joining("\n"));

        String spawnName = isSessionMode ? "sessions_spawn" : "agent_spawn";
        String sendName = isSessionMode ? "sessions_send" : "agent_send";
        String listName = isSessionMode ? "sessions_list" : "agent_list";

        String section =
                String.format(SUBAGENT_SECTION_TEMPLATE, spawnName, sendName, listName, agentList);

        event.appendSystemContent(section);
    }

    private static Map<String, SubagentFactory> buildFactories(List<SubagentEntry> entries) {
        Map<String, SubagentFactory> factories = new HashMap<>();
        for (SubagentEntry entry : entries) {
            factories.put(entry.name(), entry.factory());
        }
        return factories;
    }

    /**
     * Descriptor for a subagent identified by agent id, with its description and {@link
     * SubagentFactory}.
     */
    public record SubagentEntry(String name, String description, SubagentFactory factory) {}
}
