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
package io.agentscope.extensions.aistio.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.PlanModeContextState;
import io.agentscope.core.state.Task;
import io.agentscope.core.state.ToolContextState;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.aistio.FrameworkAdapter;
import io.agentscope.extensions.aistio.SessionBridge;
import io.agentscope.extensions.aistio.model.ContextSnapshot;
import io.agentscope.extensions.aistio.model.Inventory;
import io.agentscope.extensions.aistio.model.MessagePage;
import io.agentscope.extensions.aistio.model.SessionEvent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import reactor.core.publisher.Mono;

/**
 * Bypass-observation adapter for a self-deployed AgentScope Java agent.
 *
 * <p>This is the BYO path: the user writes and runs their own agent, and aistio observes it from
 * the side. It is unrelated to managed-agents mode, where the data plane service implements the
 * {@code /agentscope/*} contract directly because it owns the runtime.
 *
 * <p>Observation rides on {@link AistioObserverMiddleware}, which passes every input and event
 * through untouched. Context and history are read from {@link AgentState}, which is the same buffer
 * the agent will hand to its next model call, so what the console shows is what the model sees.
 *
 * <p>Middlewares are fixed when a {@code ReActAgent} is built, so the event stream requires
 * registering {@link #middleware()} at build time:
 *
 * <pre>{@code
 * AgentScopeAdapter adapter = new AgentScopeAdapter();
 * ReActAgent agent = ReActAgent.builder()
 *         .middleware(adapter.middleware())
 *         .build();
 * SessionBridge bridge = Aistio.instrument(agent, config, adapter);
 * }</pre>
 *
 * <p>An already-built agent can still be instrumented — snapshots, context, history and commands
 * all read live state — but it produces no Level-2 events.
 */
public final class AgentScopeAdapter implements FrameworkAdapter {

    private static final Logger LOG = Logger.getLogger(AgentScopeAdapter.class.getName());

    public static final String FRAMEWORK = "agentscope-java";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Name that {@code ConversationCompactor} gives the summary message it injects. Matched by
     * value rather than by importing {@code agentscope-harness}, which is optional here.
     */
    private static final String COMPACTION_SUMMARY_NAME = "__compaction_summary__";

    private final SessionCompactor compactor;
    private final AistioObserverMiddleware middleware;
    private volatile SessionHistorySource historySource;
    private volatile AgentRuntimeSource runtimeSource;
    private volatile TeamSessionStarter teamSessionStarter;

    /** Sessions seen so far, mapped to the user slot their state lives in. */
    private final Map<String, String> sessionUsers = new ConcurrentHashMap<>();

    private volatile Agent agent;
    private volatile Consumer<SessionEvent> emit;
    private volatile SessionBridge bridge;

    public AgentScopeAdapter() {
        this(null);
    }

    /**
     * @param compactor handles {@code compress}; {@code null} leaves that command unsupported
     */
    public AgentScopeAdapter(SessionCompactor compactor) {
        this.compactor = compactor;
        this.middleware = new AistioObserverMiddleware(this);
    }

    /** The middleware to register on the agent builder to enable the Level-2 event stream. */
    public AistioObserverMiddleware middleware() {
        return middleware;
    }

    /**
     * Optional full-history reader (e.g. harness {@code .log.jsonl}). Safe to set after the adapter
     * bean is created, once the {@code HarnessAgent} exists.
     */
    public void setHistorySource(SessionHistorySource historySource) {
        this.historySource = historySource;
    }

    /**
     * Optional harness-/app hooks for inventory, plan files, subagent tasks and Definition enrich.
     */
    public void setRuntimeSource(AgentRuntimeSource runtimeSource) {
        this.runtimeSource = runtimeSource;
    }

    /**
     * Optional host hook for {@code team_join} / {@code team_leave}. When set, this adapter
     * advertises {@link FrameworkAdapter#CAP_TEAM_COORDINATION}.
     */
    public void setTeamSessionStarter(TeamSessionStarter teamSessionStarter) {
        this.teamSessionStarter = teamSessionStarter;
    }

    // ─── identity ───

    @Override
    public String frameworkName() {
        return FRAMEWORK;
    }

    @Override
    public String frameworkVersion() {
        String version = Agent.class.getPackage().getImplementationVersion();
        return version == null ? "" : version;
    }

    @Override
    public boolean canHandle(Object target) {
        return target instanceof Agent;
    }

    @Override
    public Set<String> capabilities() {
        Set<String> caps = new java.util.TreeSet<>();
        caps.add(CAP_CONTEXT_QUERY);
        caps.add(CAP_MESSAGE_QUERY);
        caps.add(CAP_SESSION_COMMAND);
        caps.add(CAP_SESSION_ABORT);
        caps.add(CAP_TASK_QUERY);
        caps.add(CAP_PLAN_MODE);
        if (runtimeSource != null) {
            caps.add(CAP_SUBAGENT_INVENTORY);
            caps.add(CAP_WORKSPACE_INVENTORY);
            caps.add(CAP_SUBAGENT_TASK_QUERY);
            caps.add(CAP_SUBAGENT_TASK_COMMAND);
        }
        if (teamSessionStarter != null) {
            caps.add(CAP_TEAM_COORDINATION);
        }
        return caps;
    }

    // ─── mounting ───

    @Override
    public void attach(Object target, Consumer<SessionEvent> emit) {
        this.agent = (Agent) target;
        this.emit = emit;
    }

    @Override
    public void detach() {
        this.agent = null;
        this.emit = null;
        this.bridge = null;
        sessionUsers.clear();
    }

    @Override
    public void onBridgeAttached(SessionBridge bridge) {
        this.bridge = bridge;
    }

    // ─── called by the observer middleware ───

    Agent agent() {
        return agent;
    }

    void publish(SessionEvent event) {
        Consumer<SessionEvent> sink = emit;
        if (sink == null) {
            return;
        }
        try {
            sink.accept(event);
        } catch (RuntimeException e) {
            // Bypass principle: reporting never disturbs the conversation.
            LOG.log(Level.FINE, "aistio: event publish failed", e);
        }
    }

    /**
     * Records the session and seeds the tracker with the prompt, tools, context window and model,
     * none of which the event stream itself carries.
     */
    void rememberSession(String sessionId, String userId, Agent observed) {
        sessionUsers.put(sessionId, userId == null ? "" : userId);
        SessionBridge target = bridge;
        if (target == null) {
            return;
        }
        String systemPrompt = sysPromptOf(observed);
        int contextWindow = contextWindowOf(observed);
        String modelName = null;
        ReActAgent react = reactOf(observed);
        if (react != null && react.getModel() != null) {
            try {
                modelName = react.getModel().getModelName();
            } catch (RuntimeException e) {
                modelName = null;
            }
        }
        target.describeSession(
                sessionId, systemPrompt, toolsOf(observed), contextWindow, modelName);
    }

    /**
     * Marks whether a turn is in progress for {@code sessionId}. Called by the observer middleware
     * around each {@code call()} so session list/state can report {@code busy}. When a turn ends,
     * refreshes context-used tokens so pressure reflects the live window, not lifetime cumulative
     * usage.
     */
    void markBusy(String sessionId, boolean busy) {
        SessionBridge target = bridge;
        if (target != null) {
            target.setBusy(sessionId, busy);
        }
        if (!busy) {
            refreshContextPressure(sessionId);
        }
    }

    /** Stores the last middleware-composed system prompt for session Context. */
    void recordEffectiveSystemPrompt(String sessionId, String prompt) {
        SessionBridge target = bridge;
        if (target != null) {
            target.setEffectiveSystemPrompt(sessionId, prompt);
        }
    }

    private void refreshContextPressure(String sessionId) {
        SessionBridge target = bridge;
        if (target == null) {
            return;
        }
        try {
            Agent agent = this.agent;
            if (agent == null) {
                return;
            }
            AgentState state = resolveState(agent, sessionId);
            if (state == null) {
                return;
            }
            target.setContextUsedTokens(sessionId, windowUsedTokens(state.getContext()));
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "aistio: context pressure refresh failed for " + sessionId, e);
        }
    }

    /**
     * Estimates tokens currently occupying the model context window.
     *
     * <p>Each message's {@link ChatUsage#getInputTokens()} already includes the full prompt for that
     * turn (prior history + new tokens). Summing usages across messages therefore double-counts
     * history. The latest non-zero input size is the best available proxy for live window
     * occupancy.
     */
    static int windowUsedTokens(List<Msg> context) {
        if (context == null || context.isEmpty()) {
            return 0;
        }
        int used = 0;
        for (Msg msg : context) {
            ChatUsage usage = chatUsageOf(msg);
            if (usage == null) {
                continue;
            }
            int input = usage.getInputTokens();
            if (input > 0) {
                used = input;
            }
        }
        return used;
    }

    private static ChatUsage chatUsageOf(Msg msg) {
        if (msg == null) {
            return null;
        }
        ChatUsage usage = msg.getChatUsage();
        return usage != null ? usage : msg.getUsage();
    }

    boolean isKnownSession(String sessionId) {
        return sessionUsers.containsKey(sessionId);
    }

    // ─── Level 4: effective context ───

    @Override
    public Mono<ContextSnapshot> extractContext(String sessionId) {
        return Mono.fromCallable(
                () -> {
                    Agent target = requireAgent();
                    AgentState state = resolveState(target, sessionId);
                    List<Msg> context = state == null ? List.of() : state.getContext();

                    List<ContextSnapshot.ContextMessage> messages = new ArrayList<>(context.size());
                    String compactionSummary = "";
                    for (Msg msg : context) {
                        boolean isSummary = COMPACTION_SUMMARY_NAME.equals(msg.getName());
                        String text = textOf(msg);
                        if (isSummary) {
                            compactionSummary = text;
                        }
                        messages.add(
                                new ContextSnapshot.ContextMessage(roleOf(msg), text, isSummary));
                    }

                    // Window occupancy (not lifetime spend): see windowUsedTokens().
                    int totalTokens = windowUsedTokens(context);

                    String basePrompt = sysPromptOf(target);
                    String effectivePrompt = basePrompt;
                    SessionBridge b = bridge;
                    if (b != null) {
                        String sampled = b.effectiveSystemPrompt(sessionId);
                        if (sampled != null && !sampled.isEmpty()) {
                            effectivePrompt = sampled;
                        }
                    }

                    List<String> activatedGroups = List.of();
                    if (state != null && state.getToolContext() != null) {
                        List<String> groups = state.getToolContext().getActivatedGroups();
                        if (groups != null) {
                            activatedGroups = groups;
                        }
                    }

                    Map<String, Object> frameworkState =
                            buildFrameworkState(
                                    target, sessionId, state, basePrompt, activatedGroups);

                    return ContextSnapshot.builder(sessionId)
                            .systemPrompt(effectivePrompt)
                            .messages(messages)
                            .tools(toolsOf(target, activatedGroups))
                            .compacted(!compactionSummary.isEmpty())
                            .compactionSummary(compactionSummary)
                            .totalTokens(totalTokens)
                            .maxTokens(contextWindowOf(target))
                            .framework(FRAMEWORK)
                            .model(modelNameOf(target))
                            .frameworkState(toJsonBytes(frameworkState))
                            .build();
                });
    }

    private Map<String, Object> buildFrameworkState(
            Agent target,
            String sessionId,
            AgentState state,
            String basePrompt,
            List<String> activatedGroups) {
        Map<String, Object> fs = new LinkedHashMap<>();
        fs.put("baseSystemPrompt", basePrompt == null ? "" : basePrompt);
        fs.put("activatedGroups", activatedGroups);
        SessionBridge b = bridge;
        boolean hasEffective = b != null && !b.effectiveSystemPrompt(sessionId).isEmpty();
        fs.put("systemPromptSource", hasEffective ? "effective" : "base");

        PlanModeContextState plan = state == null ? null : state.getPlanModeContext();
        boolean planActive = plan != null && plan.isPlanActive();
        fs.put("planActive", planActive);
        String planFile = plan == null ? null : plan.getCurrentPlanFile();
        if (planFile != null && !planFile.isEmpty()) {
            fs.put("currentPlanFile", planFile);
            AgentRuntimeSource src = runtimeSource;
            if (src != null) {
                src.readPlanExcerpt(target, sessionId, sessionUsers.get(sessionId), planFile)
                        .ifPresent(excerpt -> fs.put("planExcerpt", excerpt));
            }
        }

        if (state != null && state.getToolContext() != null) {
            Map<String, ToolContextState.SpawnEntry> spawns =
                    state.getToolContext().getSpawnRegistry();
            if (spawns != null && !spawns.isEmpty()) {
                List<Map<String, Object>> spawnList = new ArrayList<>();
                for (Map.Entry<String, ToolContextState.SpawnEntry> e : spawns.entrySet()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("key", e.getKey());
                    ToolContextState.SpawnEntry entry = e.getValue();
                    if (entry != null) {
                        row.put("agentId", entry.agentId());
                        row.put("label", entry.label());
                        row.put("sessionId", entry.sessionId());
                        row.put("depth", entry.depth());
                    }
                    spawnList.add(row);
                }
                fs.put("spawns", spawnList);
            }
        }
        return fs;
    }

    private static byte[] toJsonBytes(Map<String, Object> value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (JsonProcessingException e) {
            return "{}".getBytes(StandardCharsets.UTF_8);
        }
    }

    // ─── Level 3: full history ───

    @Override
    public Mono<MessagePage> listMessages(String sessionId, int offset, int limit) {
        return Mono.fromCallable(
                () -> {
                    SessionHistorySource history = historySource;
                    if (history != null) {
                        String userId = sessionUsers.getOrDefault(sessionId, "");
                        var fromLog = history.loadMessages(sessionId, userId);
                        if (fromLog.isPresent()) {
                            return MessagePage.of(sessionId, fromLog.get(), offset, limit);
                        }
                    }
                    AgentState state = resolveState(requireAgent(), sessionId);
                    List<Msg> context = state == null ? List.<Msg>of() : state.getContext();
                    List<MessagePage.MessageItem> items = new ArrayList<>(context.size());
                    int seq = 0;
                    for (Msg msg : context) {
                        seq++;
                        ToolUseBlock use = firstBlock(msg, ToolUseBlock.class);
                        ToolResultBlock result = firstBlock(msg, ToolResultBlock.class);
                        long occurred = parseMsgTimestamp(msg.getTimestamp());
                        items.add(
                                new MessagePage.MessageItem(
                                        seq,
                                        roleOf(msg),
                                        textOf(msg),
                                        use != null
                                                ? use.getName()
                                                : (result != null ? result.getName() : ""),
                                        use != null ? use.getInput() : null,
                                        result != null ? blocksText(result.getOutput()) : "",
                                        occurred));
                    }
                    return MessagePage.of(sessionId, items, offset, limit);
                });
    }

    // ─── commands ───

    @Override
    public Mono<Void> handleCommand(String sessionId, String command, byte[] params) {
        if (COMMAND_TERMINATE.equals(command) || COMMAND_ABORT.equals(command)) {
            return Mono.fromRunnable(() -> interruptSession(sessionId));
        }
        if (COMMAND_COMPRESS.equals(command)) {
            if (compactor == null) {
                return Mono.error(
                        new UnsupportedOperationException(
                                "agentscope-java: compress requires a SessionCompactor"));
            }
            return Mono.defer(
                    () -> {
                        AgentState state = resolveState(requireAgent(), sessionId);
                        if (state == null) {
                            return Mono.error(
                                    new IllegalStateException(
                                            "agentscope-java: no state for session " + sessionId));
                        }
                        return compactor
                                .compact(sessionId, state)
                                .doOnSuccess(
                                        ignored ->
                                                publish(
                                                        SessionEvent.builder(
                                                                        sessionId,
                                                                        SessionEvent.COMPACTION)
                                                                .content(summaryOf(state))
                                                                .build()));
                    });
        }
        if (COMMAND_TEAM_JOIN.equals(command)) {
            TeamSessionStarter starter = teamSessionStarter;
            if (starter == null) {
                return Mono.error(
                        new UnsupportedOperationException(
                                "agentscope-java: team_join requires setTeamSessionStarter"));
            }
            return starter.join(sessionId, params == null ? new byte[0] : params);
        }
        if (COMMAND_TEAM_LEAVE.equals(command)) {
            TeamSessionStarter starter = teamSessionStarter;
            if (starter == null) {
                return Mono.error(
                        new UnsupportedOperationException(
                                "agentscope-java: team_leave requires setTeamSessionStarter"));
            }
            return starter.leave(sessionId);
        }
        return Mono.error(new IllegalArgumentException("unsupported command: " + command));
    }

    // ─── tasks ───

    @Override
    public Mono<List<Map<String, Object>>> listTasks(String sessionId) {
        return Mono.fromCallable(
                () -> {
                    AgentState state = resolveState(requireAgent(), sessionId);
                    if (state == null || state.getTasksContext() == null) {
                        return List.of();
                    }
                    List<Task> tasks = state.getTasksContext().getTasks();
                    List<Map<String, Object>> out = new ArrayList<>(tasks.size());
                    for (Task task : tasks) {
                        out.add(toTaskJson(task));
                    }
                    return out;
                });
    }

    private static Map<String, Object> toTaskJson(Task task) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", task.getId());
        out.put("subject", task.getSubject());
        if (task.getDescription() != null && !task.getDescription().isEmpty()) {
            out.put("description", task.getDescription());
        }
        out.put(
                "state",
                task.getState() == null ? Task.State.PENDING.getWire() : task.getState().getWire());
        if (task.getOwner() != null && !task.getOwner().isEmpty()) {
            out.put("owner", task.getOwner());
        }
        List<String> blockedBy = task.getBlockedBy();
        if (blockedBy != null && !blockedBy.isEmpty()) {
            out.put("blockedBy", List.copyOf(blockedBy));
        } else {
            out.put("blockedBy", List.of());
        }
        if (task.getCreatedAt() != null && !task.getCreatedAt().isEmpty()) {
            out.put("updatedAt", task.getCreatedAt());
        }
        out.put(
                "frameworkMeta",
                task.getMetadata() == null || task.getMetadata().isEmpty()
                        ? Map.of()
                        : task.getMetadata());
        return out;
    }

    // ─── inventory / definition / plan / subagent-tasks ───

    @Override
    public Mono<List<Inventory.SubagentInfo>> listSubagents() {
        return Mono.fromCallable(
                () -> {
                    AgentRuntimeSource src = runtimeSource;
                    if (src == null) {
                        throw new UnsupportedOperationException(
                                "subagent-inventory is not supported by this adapter");
                    }
                    return src.listSubagents(requireAgent());
                });
    }

    @Override
    public Mono<List<Inventory.WorkspaceInfo>> listWorkspaces() {
        return Mono.fromCallable(
                () -> {
                    AgentRuntimeSource src = runtimeSource;
                    if (src == null) {
                        throw new UnsupportedOperationException(
                                "workspace-inventory is not supported by this adapter");
                    }
                    return src.listWorkspaces(requireAgent());
                });
    }

    @Override
    public Mono<List<Map<String, Object>>> listSubagentTasks(String sessionId) {
        return Mono.fromCallable(
                () -> {
                    AgentRuntimeSource src = runtimeSource;
                    if (src == null) {
                        throw new UnsupportedOperationException(
                                "subagent-task-query is not supported by this adapter");
                    }
                    return src.listSubagentTasks(
                            requireAgent(), sessionId, sessionUsers.get(sessionId));
                });
    }

    @Override
    public Mono<Void> cancelSubagentTask(String sessionId, String taskId) {
        return Mono.fromCallable(
                        () -> {
                            AgentRuntimeSource src = runtimeSource;
                            if (src == null) {
                                throw new UnsupportedOperationException(
                                        "subagent-task-command is not supported by this adapter");
                            }
                            boolean ok =
                                    src.cancelSubagentTask(
                                            requireAgent(),
                                            sessionId,
                                            sessionUsers.get(sessionId),
                                            taskId);
                            if (!ok) {
                                throw new IllegalStateException(
                                        "subagent task not found or cancel failed: " + taskId);
                            }
                            return null;
                        })
                .then();
    }

    @Override
    public Mono<Void> setPlanMode(String sessionId, byte[] params) {
        return Mono.fromCallable(
                        () -> {
                            boolean active = parsePlanActive(params);
                            Agent target = requireAgent();
                            AgentRuntimeSource src = runtimeSource;
                            String userId = sessionUsers.get(sessionId);
                            if (src != null && src.setPlanMode(target, sessionId, userId, active)) {
                                return null;
                            }
                            AgentState state = resolveState(target, sessionId);
                            if (state == null) {
                                throw new IllegalStateException(
                                        "agentscope-java: no state for session " + sessionId);
                            }
                            state.getPlanModeContext().setPlanActive(active);
                            saveState(target, userId, sessionId);
                            return null;
                        })
                .then();
    }

    private static boolean parsePlanActive(byte[] params) {
        if (params == null || params.length == 0) {
            return true;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = JSON.readValue(params, Map.class);
            Object active = map.get("active");
            if (active instanceof Boolean b) {
                return b;
            }
            if (active != null) {
                return Boolean.parseBoolean(active.toString());
            }
            Object mode = map.get("mode");
            if (mode != null) {
                String m = mode.toString().toLowerCase();
                return !"exit".equals(m) && !"off".equals(m) && !"false".equals(m);
            }
        } catch (Exception e) {
            // treat as enter
        }
        return true;
    }

    private static void saveState(Agent target, String userId, String sessionId) {
        ReActAgent react = reactOf(target);
        if (react == null) {
            return;
        }
        try {
            react.saveAgentState(userId, sessionId);
        } catch (RuntimeException e) {
            LOG.log(Level.FINE, "aistio: saveAgentState failed", e);
        }
    }

    @Override
    public Map<String, Object> buildAgentConfig() {
        Agent target = agent;
        if (target == null) {
            return Map.of();
        }
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("name", target.getName() == null ? "" : target.getName());
        String description = target.getDescription();
        if (description != null && !description.isEmpty()) {
            cfg.put("description", description);
        }
        String sysPrompt = sysPromptOf(target);
        if (!sysPrompt.isEmpty()) {
            cfg.put(
                    "systemPrompt",
                    sysPrompt.length() > 8000 ? sysPrompt.substring(0, 8000) + "…" : sysPrompt);
        }
        String model = modelNameOf(target);
        if (!model.isEmpty()) {
            cfg.put("model", model);
        }
        String provider = modelProviderOf(target);
        if (!provider.isEmpty()) {
            cfg.put("modelProvider", provider);
        }
        ReActAgent react = reactOf(target);
        if (react != null) {
            cfg.put("maxIters", react.getMaxIters());
            cfg.put("maxTurns", react.getMaxIters());
        }
        List<ContextSnapshot.ToolInfo> tools = toolsOf(target);
        List<Map<String, Object>> toolRows = new ArrayList<>(tools.size());
        for (ContextSnapshot.ToolInfo t : tools) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", t.name());
            if (t.description() != null && !t.description().isEmpty()) {
                row.put("description", t.description());
            }
            toolRows.add(row);
        }
        cfg.put("tools", toolRows);
        cfg.put("sources", List.of("builder"));
        AgentRuntimeSource src = runtimeSource;
        if (src != null) {
            src.enrichAgentConfig(target, cfg);
        }
        return cfg;
    }

    // ─── state resolution ───

    private Agent requireAgent() {
        Agent target = agent;
        if (target == null) {
            throw new IllegalStateException("agentscope-java: adapter is not attached");
        }
        return target;
    }

    /**
     * Resolves the state slot for {@code sessionId}. {@code ReActAgent} keys state by {@code
     * (userId, sessionId)}, so the user recorded when the session was first seen is required to
     * reach the right slot under concurrency.
     */
    private AgentState resolveState(Agent target, String sessionId) {
        ReActAgent react = reactOf(target);
        if (react != null) {
            String userId = sessionUsers.get(sessionId);
            return react.getAgentState(userId, sessionId);
        }
        return target.getAgentState();
    }

    /**
     * Returns the {@code ReActAgent} holding the per-session state, unwrapping one level of
     * delegation. The harness agent wraps an inner {@code ReActAgent} and exposes it as {@code
     * getDelegate()}; that is resolved reflectively so this module stays independent of
     * agentscope-harness. Without the unwrap a wrapped agent falls back to the single default state
     * slot, which reads the wrong session as soon as there is more than one.
     */
    private static ReActAgent reactOf(Agent target) {
        if (target instanceof ReActAgent react) {
            return react;
        }
        if (target == null) {
            return null;
        }
        try {
            Object delegate = target.getClass().getMethod("getDelegate").invoke(target);
            return delegate instanceof ReActAgent react ? react : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.log(Level.FINE, "aistio: no ReActAgent delegate on " + target.getClass(), e);
            return null;
        }
    }

    private static String sysPromptOf(Agent target) {
        ReActAgent react = reactOf(target);
        return react == null ? "" : react.getSysPrompt();
    }

    private static String modelNameOf(Agent target) {
        ReActAgent react = reactOf(target);
        if (react != null && react.getModel() != null) {
            try {
                String name = react.getModel().getModelName();
                return name == null ? "" : name;
            } catch (RuntimeException e) {
                return "";
            }
        }
        return "";
    }

    private static String modelProviderOf(Agent target) {
        ReActAgent react = reactOf(target);
        if (react == null || react.getModel() == null) {
            return "";
        }
        try {
            Object model = react.getModel();
            try {
                Object provider = model.getClass().getMethod("getModelProvider").invoke(model);
                if (provider != null && !provider.toString().isBlank()) {
                    return provider.toString();
                }
            } catch (ReflectiveOperationException ignored) {
                // fall through
            }
            return model.getClass().getSimpleName();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * Interrupts only the turn running in {@code sessionId}. {@code Agent.interrupt()} triggers the
     * default state slot, which would abort an unrelated session whenever the agent serves more
     * than one.
     */
    private void interruptSession(String sessionId) {
        Agent target = requireAgent();
        ReActAgent react = reactOf(target);
        if (react == null) {
            target.interrupt();
            return;
        }
        react.interrupt(sessionUsers.get(sessionId), sessionId);
    }

    private String summaryOf(AgentState state) {
        for (Msg msg : state.getContext()) {
            if (COMPACTION_SUMMARY_NAME.equals(msg.getName())) {
                return textOf(msg);
            }
        }
        String summary = state.getSummary();
        return summary == null ? "" : summary;
    }

    private static int contextWindowOf(Agent target) {
        ReActAgent react = reactOf(target);
        if (react != null && react.getModel() != null) {
            try {
                return react.getModel().getContextWindowSize();
            } catch (RuntimeException e) {
                return 0;
            }
        }
        return 0;
    }

    static List<ContextSnapshot.ToolInfo> toolsOf(Agent target) {
        return toolsOf(target, null);
    }

    static List<ContextSnapshot.ToolInfo> toolsOf(Agent target, List<String> activatedGroups) {
        Toolkit toolkit = target == null ? null : target.getToolkit();
        if (toolkit == null) {
            return List.of();
        }
        List<ToolSchema> schemas;
        try {
            if (activatedGroups == null || activatedGroups.isEmpty()) {
                schemas = toolkit.getToolSchemas();
            } else {
                schemas = toolkit.getToolSchemas(activatedGroups);
            }
        } catch (RuntimeException e) {
            return List.of();
        }
        List<ContextSnapshot.ToolInfo> tools = new ArrayList<>(schemas.size());
        for (ToolSchema schema : schemas) {
            tools.add(
                    new ContextSnapshot.ToolInfo(
                            schema.getName(), schema.getDescription(), schema.getParameters()));
        }
        return tools;
    }

    // ─── Msg helpers ───

    static String roleOf(Msg msg) {
        if (firstBlock(msg, ToolResultBlock.class) != null) {
            return SessionEvent.ROLE_TOOL;
        }
        MsgRole role = msg.getRole();
        if (role == MsgRole.USER) {
            return SessionEvent.ROLE_USER;
        }
        if (role == MsgRole.SYSTEM) {
            return SessionEvent.ROLE_SYSTEM;
        }
        return SessionEvent.ROLE_ASSISTANT;
    }

    static String textOf(Msg msg) {
        if (msg == null) {
            return "";
        }
        String text = msg.getTextContent();
        if (text != null && !text.isEmpty()) {
            return text;
        }
        ToolResultBlock result = firstBlock(msg, ToolResultBlock.class);
        return result != null ? blocksText(result.getOutput()) : "";
    }

    static String blocksText(List<ContentBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : blocks) {
            if (block instanceof io.agentscope.core.message.TextBlock text) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(text.getText());
            }
        }
        return sb.toString();
    }

    private static long parseMsgTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            return 0L;
        }
        try {
            return Instant.parse(timestamp).toEpochMilli();
        } catch (RuntimeException ignored) {
            // Msg uses "yyyy-MM-dd HH:mm:ss.SSS" in some paths — leave unset rather than fail.
            return 0L;
        }
    }

    static <T extends ContentBlock> T firstBlock(Msg msg, Class<T> type) {
        if (msg == null || msg.getContent() == null) {
            return null;
        }
        for (ContentBlock block : msg.getContent()) {
            if (type.isInstance(block)) {
                return type.cast(block);
            }
        }
        return null;
    }
}
