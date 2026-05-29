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
package io.agentscope.harness.agent.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.DefaultTaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.tool.TaskTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Middleware that provides the managed subagent mechanism.
 *
 * <p>In <strong>default mode</strong> (standalone harness setup), this middleware creates an
 * {@link AgentSpawnTool} backed by a {@link DefaultAgentManager}. In <strong>session mode</strong>
 * (orchestrated via {@code AgentBootstrap}), an external tool (typically {@code SessionsTool}) is
 * injected, replacing the default {@link AgentSpawnTool}.
 *
 * <p>Responsibilities:
 *
 * <ol>
 *   <li>Exposes the subagent tool and {@link TaskTool} as agent tools (callers query via
 *       {@link #getTools()} and register them on the toolkit at orchestration time).
 *   <li>On every {@link #onAgent}, reloads subagent declarations from the workspace filesystem
 *       (namespace-scoped) to support per-user subagent isolation.
 *   <li>Prepends rich subagent usage guidance and current async task summary to the leading
 *       SYSTEM message of every {@link ReasoningInput}. Because the framework rebuilds the
 *       SYSTEM message from a frozen base each iteration, this is safe — content never
 *       accumulates across iterations.
 * </ol>
 */
public class SubagentsMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SubagentsMiddleware.class);

    private static final DateTimeFormatter ISO_SHORT =
            DateTimeFormatter.ofPattern("HH:mm'Z'").withZone(ZoneOffset.UTC);

    private static final int MAX_TASK_SUMMARY_ENTRIES = 10;

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

            **`task_output`** — Retrieve the result of a background task by task_id.
            - Prefer `block=false` to check status without blocking.
            - Only use `block=true` (default) when ready to wait for the result.
            - **Do NOT call immediately after launching** — the task has just started and will not be ready yet.

            **`task_cancel`** — Cancel a running background task by task_id. No effect on already-completed tasks.

            **`task_list`** — List all background tasks with their current, live statuses (reads from durable storage).
            - Always accurate even after conversation compaction or node migration.
            - Use after compaction or session resume to recover all task IDs and current state.

            ### CRITICAL async task rules
            1. **Never poll immediately** after launching a task. Return control to the user instead.
            2. **Never poll in a loop** — task_output does not short-circuit; every call blocks or waits.
            3. **Task status in conversation history is STALE** — do not report it. Always call `task_output(block=false)` or `task_list()` for the current state.
            4. After compaction or session resume, call `task_list()` first to recover all task IDs and statuses.
            5. For a single task status check, use `task_output(task_id=..., block=false)`.

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
            - **Parallel execution**: Launch multiple subagents concurrently with timeout_seconds=0 when tasks are independent, then collect results with task_output(block=false) after a delay
            - **Sync delegation**: Use default timeout for simple one-shot delegation
            - **Persistent session**: Spawn without a task, then use send for multi-turn interaction
            - **Cancel stale work**: Use task_cancel to stop background tasks that are no longer needed
            - Subagent results are NOT visible to the user — always summarize them in your response
            """;
    // @formatter:on

    private final List<SubagentEntry> baseEntries;
    private volatile List<SubagentEntry> entries;
    private final Object subagentTool;
    private final TaskTool taskTool;
    private final TaskRepository taskRepository;
    private final boolean isSessionMode;

    private final AbstractFilesystem filesystem;
    private final Path mainWorkspace;
    private final Function<SubagentDeclaration, SubagentFactory> factoryBuilder;
    private final DefaultAgentManager agentManager;

    /**
     * Default mode: creates {@link AgentSpawnTool} + {@link DefaultAgentManager} internally.
     */
    public SubagentsMiddleware(
            List<SubagentEntry> entries,
            TaskRepository taskRepository,
            WorkspaceManager workspaceManager,
            AbstractFilesystem filesystem,
            Path mainWorkspace,
            Function<SubagentDeclaration, SubagentFactory> factoryBuilder) {
        this.baseEntries = List.copyOf(entries);
        this.entries = this.baseEntries;
        this.isSessionMode = false;
        DefaultAgentManager dam = new DefaultAgentManager(entries, workspaceManager);
        this.agentManager = dam;
        TaskRepository repo = taskRepository != null ? taskRepository : new DefaultTaskRepository();
        this.taskRepository = repo;
        this.subagentTool = new AgentSpawnTool(dam, repo, 0);
        this.taskTool = new TaskTool(repo);
        this.filesystem = filesystem;
        this.mainWorkspace = mainWorkspace;
        this.factoryBuilder = factoryBuilder;
    }

    /**
     * Default mode without dynamic reload support.
     */
    public SubagentsMiddleware(
            List<SubagentEntry> entries,
            TaskRepository taskRepository,
            WorkspaceManager workspaceManager) {
        this(entries, taskRepository, workspaceManager, null, null, null);
    }

    /**
     * Session mode: uses the externally provided tool (typically {@code SessionsTool}).
     */
    public SubagentsMiddleware(
            List<SubagentEntry> entries,
            Object externalSubagentTool,
            TaskRepository taskRepository) {
        this.baseEntries = List.copyOf(entries);
        this.entries = this.baseEntries;
        this.isSessionMode = true;
        this.agentManager = null;
        this.subagentTool = externalSubagentTool;
        TaskRepository repo = taskRepository != null ? taskRepository : new DefaultTaskRepository();
        this.taskRepository = repo;
        this.taskTool = new TaskTool(repo);
        this.filesystem = null;
        this.mainWorkspace = null;
        this.factoryBuilder = null;
    }

    public SubagentsMiddleware(List<SubagentEntry> entries) {
        this(entries, (TaskRepository) null, (WorkspaceManager) null);
    }

    /**
     * Returns the tool instances this middleware contributes to the agent toolkit. The caller
     * is responsible for registering them on the toolkit at orchestration time.
     */
    public List<Object> getTools() {
        if (entries.isEmpty()) {
            return List.of();
        }
        return List.of(subagentTool, taskTool);
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent, AgentInput input, Function<AgentInput, Flux<AgentEvent>> next) {
        reloadSubagentEntries();
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, ReasoningInput input, Function<ReasoningInput, Flux<AgentEvent>> next) {
        List<SubagentEntry> currentEntries = this.entries;
        if (currentEntries.isEmpty()) {
            return next.apply(input);
        }
        StringBuilder addition = new StringBuilder();
        addition.append(renderSubagentSection(currentEntries, isSessionMode));
        RuntimeContext rc =
                agent instanceof AgentBase ab && ab.getRuntimeContext() != null
                        ? ab.getRuntimeContext()
                        : RuntimeContext.empty();
        String taskSummary = buildTaskSummary(this.taskRepository, rc);
        if (taskSummary != null) {
            addition.append(taskSummary);
        }
        List<Msg> rebuilt = prependToSystemMessage(input.messages(), addition.toString());
        return next.apply(new ReasoningInput(rebuilt, input.tools(), input.options()));
    }

    /**
     * Appends the given extra content to the leading SYSTEM message of {@code messages}.
     * If no SYSTEM message is present, a new one is inserted at index 0.
     */
    static List<Msg> prependToSystemMessage(List<Msg> messages, String extra) {
        if (extra == null || extra.isEmpty()) {
            return messages != null ? messages : List.of();
        }
        List<Msg> out = new ArrayList<>(messages != null ? messages.size() : 1);
        if (messages == null || messages.isEmpty() || messages.get(0).getRole() != MsgRole.SYSTEM) {
            out.add(
                    Msg.builder()
                            .name("system")
                            .role(MsgRole.SYSTEM)
                            .content(TextBlock.builder().text(extra).build())
                            .build());
            if (messages != null) {
                out.addAll(messages);
            }
            return out;
        }
        Msg sys = messages.get(0);
        String existing = sys.getTextContent() != null ? sys.getTextContent() : "";
        String merged = existing.isEmpty() ? extra : existing + "\n" + extra;
        Msg newSys =
                Msg.builder()
                        .id(sys.getId())
                        .name(sys.getName())
                        .role(MsgRole.SYSTEM)
                        .content(TextBlock.builder().text(merged).build())
                        .metadata(sys.getMetadata())
                        .timestamp(sys.getTimestamp())
                        .build();
        out.add(newSys);
        out.addAll(messages.subList(1, messages.size()));
        return out;
    }

    private void reloadSubagentEntries() {
        if (filesystem == null || factoryBuilder == null || isSessionMode) {
            return;
        }
        try {
            List<SubagentDeclaration> decls =
                    AgentSpecLoader.loadFromFilesystem(filesystem, mainWorkspace);

            List<SubagentEntry> newEntries = new ArrayList<>(baseEntries);
            for (SubagentDeclaration decl : decls) {
                boolean alreadyExists =
                        baseEntries.stream().anyMatch(e -> e.name().equals(decl.getName()));
                if (!alreadyExists) {
                    newEntries.add(
                            new SubagentEntry(
                                    decl.getName(),
                                    decl.getDescription(),
                                    factoryBuilder.apply(decl),
                                    decl));
                }
            }

            this.entries = List.copyOf(newEntries);
            if (agentManager != null) {
                agentManager.refreshEntries(this.entries);
            }
        } catch (Exception e) {
            log.warn("Failed to reload subagent entries from filesystem: {}", e.getMessage());
        }
    }

    /**
     * Renders the {@code ## Subagents} system-prompt section for the supplied entries. Shared
     * with {@link DynamicSubagentsMiddleware}.
     */
    public static String renderSubagentSection(List<SubagentEntry> entries, boolean isSessionMode) {
        String agentList =
                entries.stream()
                        .map(e -> String.format("- `%s`: %s", e.name(), e.description()))
                        .collect(Collectors.joining("\n"));
        String spawnName = isSessionMode ? "sessions_spawn" : "agent_spawn";
        String sendName = isSessionMode ? "sessions_send" : "agent_send";
        String listName = isSessionMode ? "sessions_list" : "agent_list";
        return String.format(SUBAGENT_SECTION_TEMPLATE, spawnName, sendName, listName, agentList);
    }

    /**
     * Builds a concise task summary string for the current session, or {@code null} if there
     * are no tasks to report. Shared with {@link DynamicSubagentsMiddleware}.
     */
    public static String buildTaskSummary(TaskRepository repo, RuntimeContext ctx) {
        if (repo == null) {
            return null;
        }
        String sessionId = ctx != null ? ctx.getSessionId() : null;
        Collection<BackgroundTask> tasks = repo.listTasks(ctx, sessionId, null);
        if (tasks.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder("\n### Async tasks (current session)\n");
        int count = 0;
        for (BackgroundTask task : tasks) {
            if (count >= MAX_TASK_SUMMARY_ENTRIES) {
                sb.append("- ... (")
                        .append(tasks.size() - MAX_TASK_SUMMARY_ENTRIES)
                        .append(" more — use task_list() to see all)\n");
                break;
            }
            sb.append("- task_id: ").append(task.getTaskId());
            if (task.getAgentId() != null) {
                sb.append("  agent: ").append(task.getAgentId());
            }
            sb.append("  status: ").append(task.getTaskStatus().name().toLowerCase());
            sb.append("  started: ").append(ISO_SHORT.format(task.getCreatedAt()));
            sb.append('\n');
            count++;
        }
        sb.append(
                "(Status above reflects current state; use task_output or task_list for"
                        + " latest.)\n");
        return sb.toString();
    }
}
