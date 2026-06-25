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

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.SubagentFactory;
import io.agentscope.harness.agent.subagent.SubagentSpecGenerator;
import io.agentscope.harness.agent.subagent.task.BackgroundTask;
import io.agentscope.harness.agent.subagent.task.TaskDelivery;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.subagent.task.TaskStatus;
import io.agentscope.harness.agent.subagent.task.WorkspaceTaskRepository;
import io.agentscope.harness.agent.tool.AgentGenerateTool;
import io.agentscope.harness.agent.tool.AgentSpawnTool;
import io.agentscope.harness.agent.tool.TaskTool;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
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
 * (orchestrated via {@code AgentBootstrap}), an external tool (typically {@code SessionsTool})
 * is injected, replacing the default {@link AgentSpawnTool}.
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
public class SubagentsMiddleware implements HarnessRuntimeMiddleware {

    private static final Logger log = LoggerFactory.getLogger(SubagentsMiddleware.class);

    private static final DateTimeFormatter ISO_SHORT =
            DateTimeFormatter.ofPattern("HH:mm'Z'").withZone(ZoneOffset.UTC);

    private static final int MAX_TASK_SUMMARY_ENTRIES = 10;

    /**
     * Cap on individual task results aggregated into a single {@code <system-reminder>} delivery
     * message. Anything beyond this gets a "... and N more — call task_list()" footer; the LLM
     * can still fetch each one explicitly.
     */
    static final int MAX_DELIVERIES_PER_REMINDER = 10;

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
            - **You rarely need this.** Completed tasks are pushed back to you automatically as a `<system-reminder>` block before your next reasoning step.
            - Use `task_output(block=false)` only when you need the full result and the pushed summary was truncated, or to inspect a specific task on demand.
            - Avoid `block=true`; it serialises the conversation behind the task.

            **`task_cancel`** — Cancel a running background task by task_id. No effect on already-completed tasks.

            **`task_list`** — List all in-flight background tasks (durable, accurate across compaction and migration). Completed tasks fall off this list after they're pushed to you.

            ### Background task flow
            1. Spawn with `timeout_seconds=0` to fire-and-forget; the response gives you a task_id.
            2. **Do not poll.** Continue with other work; when the task finishes you'll see a `<system-reminder>` containing its result.
            3. If the agent has nothing useful to do, hand control back to the user — they'll prompt again when ready and the next reasoning round will surface any completions.

            ### Timeout promotion
            When a sync spawn/send exceeds its timeout, the task is **not lost** — it is automatically promoted to a background task. You receive `status: timeout_promoted` with a `task_id`. Treat it like any async task: the result will be pushed back to you automatically as a `<system-reminder>`. Do NOT retry the same task — it is already running in the background.

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
    private volatile Object subagentTool;
    private final TaskTool taskTool;
    private final TaskRepository taskRepository;
    private final boolean isSessionMode;

    private final AbstractFilesystem filesystem;
    private final Path mainWorkspace;
    private final Function<SubagentDeclaration, SubagentFactory> factoryBuilder;
    private final DefaultAgentManager agentManager;

    /**
     * Optional {@link AgentGenerateTool} for LLM-driven subagent spec generation. Lazy because
     * the spec generator needs a {@link io.agentscope.core.model.Model} instance that callers
     * supply via {@link #enableAgentGenerateTool}; null when not enabled (OOTB default).
     */
    private volatile AgentGenerateTool agentGenerateTool;

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
        java.util.Objects.requireNonNull(taskRepository, "taskRepository");
        this.taskRepository = taskRepository;
        this.subagentTool = new AgentSpawnTool(dam, taskRepository, 0);
        this.taskTool = new TaskTool(taskRepository);
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
     * AgentStateStore mode: uses the externally provided tool (typically {@code SessionsTool}).
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
        java.util.Objects.requireNonNull(taskRepository, "taskRepository");
        this.taskRepository = taskRepository;
        this.taskTool = new TaskTool(taskRepository);
        this.filesystem = null;
        this.mainWorkspace = null;
        this.factoryBuilder = null;
    }

    /**
     * Enables the {@code agent_generate} tool, which lets the LLM author new subagent specs from
     * a description. Off by default — the generator needs a {@link io.agentscope.core.model.Model}
     * and writes to the workspace, so callers opt in explicitly.
     *
     * <p>Only effective in default (non-session) mode, where this middleware owns a
     * {@link DefaultAgentManager}. In session mode the manager is external and this call is a
     * no-op.
     *
     * @param generator a {@link SubagentSpecGenerator} backed by the model that should author specs
     * @return this middleware for chaining
     */
    public SubagentsMiddleware enableAgentGenerateTool(SubagentSpecGenerator generator) {
        if (isSessionMode || agentManager == null) {
            log.debug("enableAgentGenerateTool ignored in session mode (no internal manager)");
            return this;
        }
        this.agentGenerateTool = new AgentGenerateTool(generator, agentManager, filesystem);
        return this;
    }

    /** Returns the {@link TaskRepository} used by this middleware. */
    public TaskRepository getTaskRepository() {
        return taskRepository;
    }

    /**
     * Wires a {@link io.agentscope.harness.agent.bus.MessageBus} so that background task completions are
     * pushed to the session inbox and a wakeup signal is enqueued. This enables the
     * {@link io.agentscope.harness.agent.gateway.WakeupDispatcher} to re-trigger idle sessions
     * when subagent work finishes.
     *
     * <p>Registers a {@link WorkspaceTaskRepository.TaskCompletionCallback} on the underlying
     * repository (if it is a {@link WorkspaceTaskRepository}). Safe to call multiple times — each
     * call replaces the previous callback.
     *
     * @param messageBus the application message bus
     * @param agentId the parent agent id (for wakeup routing)
     * @return this middleware for chaining
     */
    public SubagentsMiddleware wireMessageBus(
            io.agentscope.harness.agent.bus.MessageBus messageBus, String agentId) {
        if (messageBus == null) {
            return this;
        }
        if (taskRepository instanceof WorkspaceTaskRepository wtr) {
            wtr.setCompletionCallback(
                    (rc, taskId, subAgentId, sessionId, result) -> {
                        String userId = rc != null ? rc.getUserId() : null;
                        String hintContent =
                                String.format(
                                        "<system-notification>Background subagent task '%s'"
                                                + " (agent=%s) has completed.\n\nResult:\n\n%s"
                                                + "</system-notification>",
                                        taskId,
                                        subAgentId,
                                        result != null ? result : "(no output)");
                        String hintId = java.util.UUID.randomUUID().toString().replace("-", "");
                        java.util.Map<String, Object> hintPayload =
                                java.util.Map.of(
                                        "type",
                                        "hint",
                                        "id",
                                        hintId,
                                        "hint",
                                        hintContent,
                                        "source",
                                        "subagent_task");
                        messageBus.inboxPush(sessionId, hintPayload).subscribe();
                        messageBus
                                .enqueueWakeup(
                                        userId != null ? userId : "",
                                        sessionId,
                                        agentId != null ? agentId : "")
                                .subscribe(
                                        unused -> {},
                                        err ->
                                                log.warn(
                                                        "Failed to enqueue wakeup after task {}"
                                                                + " completion: {}",
                                                        taskId,
                                                        err.getMessage()));
                        log.info(
                                "Subagent task {} completed, pushed to inbox and enqueued wakeup:"
                                        + " session={}",
                                taskId,
                                sessionId);
                    });
        }
        return this;
    }

    /**
     * Wires a gateway bridge into the internal {@link AgentSpawnTool}, enabling spawned subagents
     * to be exposed as user-addressable threads. Only effective in default (non-session) mode.
     *
     * @param bridge the bridge implementation (typically obtained from
     *     {@link io.agentscope.harness.agent.gateway.GatewayBootstrap#gatewayBridge()})
     * @return this middleware for chaining
     */
    public SubagentsMiddleware setGatewayBridge(
            io.agentscope.harness.agent.gateway.SubagentGatewayBridge bridge) {
        if (isSessionMode || agentManager == null) {
            log.debug("setGatewayBridge ignored in session mode (no internal manager)");
            return this;
        }
        // Mutate the bridge on the live tool instead of replacing it: the toolkit binds
        // agent_spawn to the AgentSpawnTool instance returned by getTools() at orchestration
        // time, so a fresh instance here would never be invoked and exposure would silently
        // never fire.
        if (this.subagentTool instanceof AgentSpawnTool ast) {
            ast.setGatewayBridge(bridge);
        } else {
            this.subagentTool = new AgentSpawnTool(agentManager, taskRepository, 0, bridge);
        }
        return this;
    }

    /**
     * Returns the internal {@link DefaultAgentManager} that can re-materialize subagents, or
     * {@code null} in session mode (external tool). Used to wire a gateway materializer for
     * cross-node exposed-subagent recovery.
     */
    public DefaultAgentManager getAgentManager() {
        return agentManager;
    }

    /**
     * Returns the tool instances this middleware contributes to the agent toolkit. The caller
     * is responsible for registering them on the toolkit at orchestration time.
     *
     * <p>When {@link #enableAgentGenerateTool} has been called, the returned list additionally
     * contains an {@link AgentGenerateTool}.
     */
    public List<Object> getTools() {
        if (entries.isEmpty()) {
            return List.of();
        }
        AgentGenerateTool gen = this.agentGenerateTool;
        if (gen != null) {
            return List.of(subagentTool, taskTool, gen);
        }
        return List.of(subagentTool, taskTool);
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext ctx,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        reloadSubagentEntries();
        return next.apply(input);
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        List<SubagentEntry> currentEntries = this.entries;
        if (currentEntries.isEmpty()) {
            return next.apply(input);
        }
        RuntimeContext rc = ctx != null ? ctx : RuntimeContext.empty();
        String sessionId = rc != null ? rc.getSessionId() : null;

        // ---- Phase B-3 push delivery -------------------------------------------------------
        // Drain newly-terminal tasks first so the SYSTEM summary built afterwards can omit them.
        // Only persist to AgentState when the agent is a ReActAgent — other Agent kinds keep
        // the legacy pull-only flow unchanged.
        List<TaskDelivery> pending = this.taskRepository.findPendingDeliveries(rc, sessionId);
        Msg deliveryMsg = null;
        if (!pending.isEmpty() && agent instanceof ReActAgent reAct) {
            deliveryMsg = buildDeliveryReminder(pending);
            try {
                RuntimeContext.resolveAgentState(rc, reAct).contextMutable().add(deliveryMsg);
            } catch (RuntimeException e) {
                log.warn(
                        "Failed to append task delivery reminder to AgentState; "
                                + "will inject for this round only: {}",
                        e.getMessage());
            }
        }

        StringBuilder addition = new StringBuilder();
        addition.append(renderSubagentSection(currentEntries, isSessionMode));
        String taskSummary = buildTaskSummary(this.taskRepository, rc);
        if (taskSummary != null) {
            addition.append(taskSummary);
        }
        List<Msg> rebuilt = prependToSystemMessage(input.messages(), addition.toString());
        if (deliveryMsg != null) {
            // Inject for this round (parallel to the AgentState write — keeps the message visible
            // in the immediate LLM call regardless of when the framework re-derives messages from
            // the state next round).
            rebuilt = new ArrayList<>(rebuilt);
            rebuilt.add(deliveryMsg);
        }

        Flux<AgentEvent> downstream =
                next.apply(new ReasoningInput(rebuilt, input.tools(), input.options()));
        if (!pending.isEmpty()) {
            // Mark delivered ONLY after the inner reasoning completes successfully. Order matters:
            // if we marked before reasoning, a crash mid-call could persist deliveredAt while the
            // AgentState write was never flushed, causing permanent message loss. The reverse
            // (crash AFTER reasoning, BEFORE markDelivered) only causes a redundant re-delivery
            // on the next round — annoying, but safe.
            final TaskRepository repoRef = this.taskRepository;
            final RuntimeContext rcRef = rc;
            final String sidRef = sessionId;
            downstream =
                    downstream.doOnComplete(
                            () -> {
                                for (TaskDelivery d : pending) {
                                    try {
                                        repoRef.markDelivered(rcRef, sidRef, d.taskId());
                                    } catch (RuntimeException e) {
                                        log.warn(
                                                "Failed to mark task {} as delivered; "
                                                        + "may re-push next round: {}",
                                                d.taskId(),
                                                e.getMessage());
                                    }
                                }
                            });
        }
        return downstream;
    }

    /**
     * Builds the single aggregated {@code <system-reminder>} message that surfaces newly-terminal
     * background tasks to the LLM. Caps at {@link #MAX_DELIVERIES_PER_REMINDER} entries; the
     * remainder is mentioned in a tail line so the LLM knows to call {@code task_list()} if it
     * wants the full set.
     *
     * <p>Role / metadata mirror {@code TaskReminderMiddleware} so downstream UI and persistence
     * layers can recognise this as system-originated synthetic content rather than user input.
     */
    static Msg buildDeliveryReminder(List<TaskDelivery> deliveries) {
        int total = deliveries.size();
        int shown = Math.min(total, MAX_DELIVERIES_PER_REMINDER);
        StringBuilder sb = new StringBuilder();
        sb.append("<system-reminder>\n");
        sb.append(total).append(" background subagent task");
        sb.append(total == 1 ? " has" : "s have");
        sb.append(" completed since your last turn. ");
        sb.append("These results are now part of your conversation history.\n\n");
        for (int i = 0; i < shown; i++) {
            TaskDelivery d = deliveries.get(i);
            String state = stateLiteral(d.status());
            sb.append("<task id=\"").append(d.taskId()).append("\" state=\"").append(state);
            sb.append("\"");
            if (d.agentId() != null) {
                sb.append(" agent=\"").append(d.agentId()).append("\"");
            }
            sb.append(">\n");
            switch (d.status()) {
                case COMPLETED -> {
                    sb.append("<task_result>\n");
                    sb.append(d.result() != null ? d.result() : "");
                    sb.append("\n</task_result>\n");
                }
                case FAILED -> {
                    sb.append("<task_error>\n");
                    sb.append(d.errorMessage() != null ? d.errorMessage() : "(no error message)");
                    sb.append("\n</task_error>\n");
                }
                case CANCELLED -> sb.append("Task was cancelled before producing a result.\n");
                default ->
                        sb.append("Task ended in non-terminal state ")
                                .append(d.status())
                                .append('\n');
            }
            sb.append("</task>\n");
        }
        if (total > shown) {
            sb.append("\n... and ")
                    .append(total - shown)
                    .append(" more — call task_list() to inspect.\n");
        }
        sb.append(
                "\nIf you need a single task's full output, call"
                        + " task_output(task_id=..., block=false).\n");
        sb.append("</system-reminder>");
        return Msg.builder()
                .role(MsgRole.USER)
                .name("system")
                .content(TextBlock.builder().text(sb.toString()).build())
                .metadata(
                        Map.of(
                                Msg.METADATA_SYNTHETIC,
                                true,
                                Msg.METADATA_REMINDER_KIND,
                                "task_delivery"))
                .build();
    }

    private static String stateLiteral(TaskStatus status) {
        return switch (status) {
            case COMPLETED -> "completed";
            case FAILED -> "error";
            case CANCELLED -> "cancelled";
            default -> status.name().toLowerCase();
        };
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
     *
     * <p>Filters out entries whose declaration sets {@code hidden=true} or {@code mode=PRIMARY}:
     * the LLM should not see internal-use subagents (hidden) and cannot spawn primary-only
     * declarations (the {@link io.agentscope.harness.agent.subagent.DefaultAgentManager} rejects
     * such spawns anyway, so advertising them would be a footgun). Programmatic registrations
     * without a declaration ({@code entry.declaration() == null}) are always shown.
     */
    public static String renderSubagentSection(List<SubagentEntry> entries, boolean isSessionMode) {
        String agentList =
                entries.stream()
                        .filter(SubagentsMiddleware::isVisibleToLlm)
                        .map(e -> String.format("- `%s`: %s", e.name(), e.description()))
                        .collect(Collectors.joining("\n"));
        String spawnName = isSessionMode ? "sessions_spawn" : "agent_spawn";
        String sendName = isSessionMode ? "sessions_send" : "agent_send";
        String listName = isSessionMode ? "sessions_list" : "agent_list";
        return String.format(SUBAGENT_SECTION_TEMPLATE, spawnName, sendName, listName, agentList);
    }

    /**
     * Whether an entry should appear in the LLM-facing list of available subagents.
     * Programmatic entries with no declaration default to visible.
     */
    private static boolean isVisibleToLlm(SubagentEntry e) {
        SubagentDeclaration decl = e.declaration();
        if (decl == null) return true;
        if (decl.isHidden()) return false;
        return decl.getMode() != SubagentDeclaration.Mode.PRIMARY;
    }

    /**
     * Builds a concise summary of in-flight tasks for the current session, or {@code null} if
     * none. Shared with {@link DynamicSubagentsMiddleware}.
     *
     * <p>Phase B-3: already-delivered tasks are filtered out — they live in the conversation
     * history as {@code <system-reminder>} blocks, so re-mentioning them here would (a) waste
     * tokens, (b) push genuinely still-running tasks past the {@link #MAX_TASK_SUMMARY_ENTRIES}
     * cap, and (c) invite the LLM to second-guess the push by re-fetching results.
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

        List<BackgroundTask> visible = new ArrayList<>();
        for (BackgroundTask task : tasks) {
            if (repo.isDelivered(ctx, sessionId, task.getTaskId())) continue;
            visible.add(task);
        }
        if (visible.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder("\n### Async tasks (current session)\n");
        int count = 0;
        for (BackgroundTask task : visible) {
            if (count >= MAX_TASK_SUMMARY_ENTRIES) {
                sb.append("- ... (")
                        .append(visible.size() - MAX_TASK_SUMMARY_ENTRIES)
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
                "(Completed/failed/cancelled tasks are pushed back to you as <system-reminder>"
                        + " blocks, not listed here — no need to call task_output unless you need"
                        + " the full payload.)\n");
        return sb.toString();
    }
}
