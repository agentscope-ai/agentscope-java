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
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.aistio.FrameworkAdapter;
import io.agentscope.extensions.aistio.SessionBridge;
import io.agentscope.extensions.aistio.model.ContextSnapshot;
import io.agentscope.extensions.aistio.model.MessagePage;
import io.agentscope.extensions.aistio.model.SessionEvent;
import java.util.ArrayList;
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

    /**
     * Name that {@code ConversationCompactor} gives the summary message it injects. Matched by
     * value rather than by importing {@code agentscope-harness}, which is optional here.
     */
    private static final String COMPACTION_SUMMARY_NAME = "__compaction_summary__";

    private final SessionCompactor compactor;
    private final AistioObserverMiddleware middleware;

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
        // terminate() always works via Agent.interrupt(); compress() depends on the compactor.
        caps.add(CAP_SESSION_COMMAND);
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
     * Records the session and seeds the tracker with the prompt, tools and context window, none of
     * which the event stream itself carries.
     */
    void rememberSession(String sessionId, String userId, Agent observed) {
        sessionUsers.put(sessionId, userId == null ? "" : userId);
        SessionBridge target = bridge;
        if (target == null) {
            return;
        }
        String systemPrompt = observed instanceof ReActAgent react ? react.getSysPrompt() : null;
        int contextWindow = 0;
        if (observed instanceof ReActAgent react && react.getModel() != null) {
            try {
                contextWindow = react.getModel().getContextWindowSize();
            } catch (RuntimeException e) {
                contextWindow = 0;
            }
        }
        target.describeSession(sessionId, systemPrompt, toolsOf(observed), contextWindow);
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

                    int totalTokens = 0;
                    for (Msg msg : context) {
                        ChatUsage usage = msg.getUsage();
                        if (usage != null) {
                            totalTokens += usage.getTotalTokens();
                        }
                    }

                    return ContextSnapshot.builder(sessionId)
                            .systemPrompt(
                                    target instanceof ReActAgent react ? react.getSysPrompt() : "")
                            .messages(messages)
                            .tools(toolsOf(target))
                            .compacted(!compactionSummary.isEmpty())
                            .compactionSummary(compactionSummary)
                            .totalTokens(totalTokens)
                            .maxTokens(contextWindowOf(target))
                            .framework(FRAMEWORK)
                            .build();
                });
    }

    // ─── Level 3: full history ───

    @Override
    public Mono<MessagePage> listMessages(String sessionId, int offset, int limit) {
        return Mono.fromCallable(
                () -> {
                    AgentState state = resolveState(requireAgent(), sessionId);
                    List<Msg> context = state == null ? List.<Msg>of() : state.getContext();
                    List<MessagePage.MessageItem> items = new ArrayList<>(context.size());
                    int seq = 0;
                    for (Msg msg : context) {
                        seq++;
                        ToolUseBlock use = firstBlock(msg, ToolUseBlock.class);
                        ToolResultBlock result = firstBlock(msg, ToolResultBlock.class);
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
                                        0L));
                    }
                    return MessagePage.of(sessionId, items, offset, limit);
                });
    }

    // ─── commands ───

    @Override
    public Mono<Void> handleCommand(String sessionId, String command, byte[] params) {
        if (COMMAND_TERMINATE.equals(command)) {
            return Mono.fromRunnable(() -> requireAgent().interrupt());
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
        return Mono.error(new IllegalArgumentException("unsupported command: " + command));
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
        if (target instanceof ReActAgent react) {
            String userId = sessionUsers.get(sessionId);
            return react.getAgentState(userId, sessionId);
        }
        return target.getAgentState();
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
        if (target instanceof ReActAgent react && react.getModel() != null) {
            try {
                return react.getModel().getContextWindowSize();
            } catch (RuntimeException e) {
                return 0;
            }
        }
        return 0;
    }

    static List<ContextSnapshot.ToolInfo> toolsOf(Agent target) {
        Toolkit toolkit = target == null ? null : target.getToolkit();
        if (toolkit == null) {
            return List.of();
        }
        List<ToolSchema> schemas;
        try {
            schemas = toolkit.getToolSchemas();
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
