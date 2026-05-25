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
package io.agentscope.core.agent;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.config.ContextConfig;
import io.agentscope.core.agent.config.ModelConfig;
import io.agentscope.core.agent.config.ReactConfig;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.ExceedMaxItersEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.event.ModelCallStartEvent;
import io.agentscope.core.event.ReplyEndEvent;
import io.agentscope.core.event.ReplyStartEvent;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.event.UserConfirmResultEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.MiddlewareChain;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.middleware.ReplyInput;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionEngine;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.tool.permission.ToolBase;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Single-file ReAct agent for the AgentScope 2.0 contract.
 *
 * <p>This class is the new home for the full reply / reasoning / acting loop and is
 * intended to coexist with the legacy {@code ReActAgent} while the migration to the
 * 2.0 API surface completes. It composes:
 * <ul>
 *   <li>A {@link ChatModelBase} for LLM calls</li>
 *   <li>A {@link Toolkit} carrying registered tool implementations</li>
 *   <li>A list of {@link MiddlewareBase} hooks wrapping reply / reasoning / acting / model-call /
 *       system-prompt</li>
 *   <li>An {@link AgentState} carrying conversation context, permission context, tool context, and
 *       tasks context</li>
 *   <li>{@link ModelConfig}, {@link ContextConfig}, {@link ReactConfig} for retry / compression /
 *       loop tuning</li>
 *   <li>A {@link PermissionEngine} constructed from {@link AgentState#getPermissionContext()}</li>
 * </ul>
 *
 * <p>Stage 7d implements the reply loop, reasoning (model call), and acting (tool execution)
 * phases. Stages 7e and 7f layer on context compression and HITL re-entry respectively.
 */
public final class Agent2 implements Agent {

    /**
     * Reactor {@link reactor.util.context.Context} key under which callers register a {@link
     * Sinks.Many} that this agent will read to receive {@link ConfirmResult} responses to {@link
     * RequireUserConfirmEvent}s. The sink must accept at least one {@code ConfirmResult} for every
     * pending tool call before the agent will proceed.
     *
     * <p>When no sink is registered and at least one tool call requires confirmation, the agent
     * auto-denies all pending tool calls, mirroring the {@code DONT_ASK} permission mode behaviour.
     */
    public static final String CONFIRM_SINK_KEY = "io.agentscope.core.agent.Agent2.confirmSink";

    private final String agentId;
    private final String name;
    private final String systemPrompt;
    private final ChatModelBase model;
    private final Toolkit toolkit;
    private final List<MiddlewareBase> middlewares;
    private final AgentState state;
    private final ModelConfig modelConfig;
    private final ContextConfig contextConfig;
    private final ReactConfig reactConfig;
    private final PermissionEngine permissionEngine;
    private final ContextCompressor compressor;

    private final AtomicBoolean interruptFlag = new AtomicBoolean(false);
    private final AtomicReference<Msg> userInterruptMessage = new AtomicReference<>(null);

    public Agent2(
            String name,
            String systemPrompt,
            ChatModelBase model,
            Toolkit toolkit,
            List<MiddlewareBase> middlewares,
            AgentState state,
            ModelConfig modelConfig,
            ContextConfig contextConfig,
            ReactConfig reactConfig) {
        this.agentId = UUID.randomUUID().toString();
        this.name = Objects.requireNonNull(name, "name");
        this.systemPrompt = systemPrompt;
        this.model = Objects.requireNonNull(model, "model");
        this.toolkit = Objects.requireNonNull(toolkit, "toolkit");
        this.middlewares = middlewares == null ? List.of() : List.copyOf(middlewares);
        this.state = Objects.requireNonNull(state, "state");
        this.modelConfig = modelConfig == null ? ModelConfig.defaults() : modelConfig;
        this.contextConfig = contextConfig == null ? ContextConfig.defaults() : contextConfig;
        this.reactConfig = reactConfig == null ? ReactConfig.defaults() : reactConfig;
        this.permissionEngine = new PermissionEngine(state.getPermissionContext());
        this.compressor = new ContextCompressor(this.contextConfig, this.model);
    }

    @Override
    public String getAgentId() {
        return agentId;
    }

    @Override
    public String getName() {
        return name;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public ChatModelBase getModel() {
        return model;
    }

    public Toolkit getToolkit() {
        return toolkit;
    }

    public List<MiddlewareBase> getMiddlewares() {
        return middlewares;
    }

    public AgentState getState() {
        return state;
    }

    public ModelConfig getModelConfig() {
        return modelConfig;
    }

    public ContextConfig getContextConfig() {
        return contextConfig;
    }

    public ReactConfig getReactConfig() {
        return reactConfig;
    }

    public PermissionEngine getPermissionEngine() {
        return permissionEngine;
    }

    @Override
    public void interrupt() {
        interruptFlag.set(true);
    }

    @Override
    public void interrupt(Msg msg) {
        interruptFlag.set(true);
        if (msg != null) {
            userInterruptMessage.set(msg);
        }
    }

    /** Visible to Stage 7f for reply-loop interrupt checks. */
    AtomicBoolean interruptFlag() {
        return interruptFlag;
    }

    /** Visible to Stage 7f to surface user-supplied interrupt content. */
    AtomicReference<Msg> userInterruptMessage() {
        return userInterruptMessage;
    }

    // ==================== Public reply API ====================

    @Override
    public Mono<Msg> call(List<Msg> msgs) {
        return streamEvents(msgs)
                .filter(e -> e instanceof ReplyEndEvent || e instanceof ExceedMaxItersEvent)
                .next()
                .map(e -> lastAssistantMsg());
    }

    @Override
    public Mono<Msg> call(List<Msg> msgs, Class<?> structuredOutputClass) {
        return Mono.error(
                new UnsupportedOperationException(
                        "Agent2.call(structured): structured output not yet implemented"));
    }

    @Override
    public Mono<Msg> call(List<Msg> msgs, JsonNode schema) {
        return Mono.error(
                new UnsupportedOperationException(
                        "Agent2.call(schema): structured output not yet implemented"));
    }

    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options) {
        return streamEvents(msgs).flatMap(this::adaptToCoarseEvent);
    }

    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, Class<?> structuredModel) {
        return Flux.error(
                new UnsupportedOperationException(
                        "Agent2.stream(structured): structured output not yet implemented"));
    }

    @Override
    public Flux<Event> stream(List<Msg> msgs, StreamOptions options, JsonNode schema) {
        return Flux.error(
                new UnsupportedOperationException(
                        "Agent2.stream(schema): structured output not yet implemented"));
    }

    @Override
    public Mono<Void> observe(Msg msg) {
        if (msg == null) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> state.contextMutable().add(msg));
    }

    @Override
    public Mono<Void> observe(List<Msg> msgs) {
        if (msgs == null || msgs.isEmpty()) {
            return Mono.empty();
        }
        return Mono.fromRunnable(() -> state.contextMutable().addAll(msgs));
    }

    /**
     * Public fine-grained event stream covering the full reply lifecycle.
     *
     * <p>Emits the 27 AgentEvent types in their natural ReAct ordering:
     * {@link ReplyStartEvent} → (per iteration: {@link ModelCallStartEvent} →
     * block start/delta/end events → {@link ModelCallEndEvent} → optional
     * tool-result events) → {@link ReplyEndEvent} or {@link ExceedMaxItersEvent}.
     *
     * @param msgs the user input messages to observe before the loop runs
     * @return cold flux of fine-grained agent events
     */
    public Flux<AgentEvent> streamEvents(List<Msg> msgs) {
        return Flux.defer(
                () -> {
                    if (msgs != null && !msgs.isEmpty()) {
                        state.contextMutable().addAll(msgs);
                    }
                    String replyId = UUID.randomUUID().toString().replace("-", "");
                    state.setReplyId(replyId);
                    state.setCurIter(0);
                    ReplyContext rctx = new ReplyContext(replyId);
                    Function<ReplyInput, Flux<AgentEvent>> chain =
                            MiddlewareChain.build(
                                    middlewares,
                                    this,
                                    MiddlewareBase::onReply,
                                    in -> coreReply(in, rctx));
                    return chain.apply(new ReplyInput(msgs == null ? List.of() : msgs));
                });
    }

    // ==================== Core ReAct loop ====================

    private Flux<AgentEvent> coreReply(ReplyInput input, ReplyContext rctx) {
        return Flux.<AgentEvent>just(new ReplyStartEvent(state.getSessionId(), rctx.replyId, name))
                .concatWith(reactLoop(rctx, 0))
                .concatWith(Flux.defer(() -> Flux.just(new ReplyEndEvent(rctx.replyId))));
    }

    private Flux<AgentEvent> reactLoop(ReplyContext rctx, int iter) {
        return Flux.defer(
                () -> {
                    if (iter >= reactConfig.maxIters()) {
                        return Flux.just(
                                (AgentEvent)
                                        new ExceedMaxItersEvent(
                                                rctx.replyId, reactConfig.maxIters(), iter));
                    }
                    state.setCurIter(iter);
                    Flux<AgentEvent> reasoning =
                            compressor.maybeCompress(state).thenMany(runReasoningChain(rctx));
                    return reasoning.concatWith(
                            Flux.defer(
                                    () -> {
                                        Msg last = rctx.lastReasoningMsg.get();
                                        List<ToolUseBlock> uses =
                                                last == null
                                                        ? List.of()
                                                        : last.getContentBlocks(ToolUseBlock.class);
                                        if (uses.isEmpty()) {
                                            return Flux.empty();
                                        }
                                        return runActingChain(rctx, uses)
                                                .concatWith(reactLoop(rctx, iter + 1));
                                    }));
                });
    }

    // ==================== Reasoning (model call) ====================

    private Flux<AgentEvent> runReasoningChain(ReplyContext rctx) {
        List<Msg> messages = buildReasoningMessages();
        List<ToolSchema> tools = toolkit.getToolSchemas();
        GenerateOptions options = GenerateOptions.builder().build();
        Function<ReasoningInput, Flux<AgentEvent>> core = in -> coreReasoning(in, rctx);
        return MiddlewareChain.build(middlewares, this, MiddlewareBase::onReasoning, core)
                .apply(new ReasoningInput(messages, tools, options));
    }

    private Flux<AgentEvent> coreReasoning(ReasoningInput input, ReplyContext rctx) {
        Function<ModelCallInput, Flux<AgentEvent>> modelCallCore = mci -> coreModelCall(mci, rctx);
        return MiddlewareChain.build(middlewares, this, MiddlewareBase::onModelCall, modelCallCore)
                .apply(new ModelCallInput(input.messages(), input.tools(), input.options(), model));
    }

    private Flux<AgentEvent> coreModelCall(ModelCallInput mci, ReplyContext rctx) {
        BlockAggregator agg = new BlockAggregator(rctx.replyId);
        Flux<AgentEvent> chunkEvents =
                mci.model().stream(mci.messages(), mci.tools(), mci.options())
                        .concatMap(chunk -> Flux.fromIterable(agg.absorb(chunk)));
        Flux<AgentEvent> endEvents =
                Flux.defer(
                        () -> {
                            List<AgentEvent> tail = agg.finishAndBuild();
                            Msg assistantMsg = agg.toAssistantMsg(name);
                            rctx.lastReasoningMsg.set(assistantMsg);
                            state.contextMutable().add(assistantMsg);
                            tail.add(new ModelCallEndEvent(rctx.replyId, agg.usage));
                            return Flux.fromIterable(tail);
                        });
        return Flux.concat(
                Flux.just(new ModelCallStartEvent(rctx.replyId)), chunkEvents, endEvents);
    }

    // ==================== Acting (tool execution) ====================

    private Flux<AgentEvent> runActingChain(ReplyContext rctx, List<ToolUseBlock> toolUses) {
        Function<ActingInput, Flux<AgentEvent>> core = in -> coreActing(in, rctx);
        return MiddlewareChain.build(middlewares, this, MiddlewareBase::onActing, core)
                .apply(new ActingInput(toolUses));
    }

    private Flux<AgentEvent> coreActing(ActingInput input, ReplyContext rctx) {
        return classifyPendingConfirmation(input.toolCalls())
                .flatMapMany(
                        pending -> {
                            if (pending.isEmpty()) {
                                return Flux.fromIterable(input.toolCalls())
                                        .concatMap(use -> executeSingleTool(use, rctx));
                            }
                            return gatedExecution(input.toolCalls(), pending, rctx);
                        });
    }

    private Flux<AgentEvent> gatedExecution(
            List<ToolUseBlock> allCalls, List<ToolUseBlock> pending, ReplyContext rctx) {
        Flux<AgentEvent> requireEvent =
                Flux.just(new RequireUserConfirmEvent(rctx.replyId, pending));
        Mono<List<ConfirmResult>> awaited = awaitConfirmation(pending);
        return requireEvent.concatWith(
                awaited.flatMapMany(
                        results -> {
                            Set<String> deniedIds = collectDeniedIds(results);
                            Flux<AgentEvent> resultEvent =
                                    Flux.just(new UserConfirmResultEvent(rctx.replyId, results));
                            Flux<AgentEvent> executions =
                                    Flux.fromIterable(allCalls)
                                            .concatMap(
                                                    use ->
                                                            deniedIds.contains(use.getId())
                                                                    ? emitDeniedToolResult(
                                                                            use, rctx)
                                                                    : executeSingleTool(use, rctx));
                            return resultEvent.concatWith(executions);
                        }));
    }

    /**
     * Classifies tool calls to determine which require user confirmation. Only tools that are
     * {@link ToolBase} instances are inspected; legacy {@link AgentTool}s that do not extend {@code
     * ToolBase} are treated as auto-allowed. A tool is added to the pending list when its {@link
     * ToolBase#checkPermissions} returns {@link PermissionBehavior#ASK}.
     */
    private Mono<List<ToolUseBlock>> classifyPendingConfirmation(List<ToolUseBlock> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Mono.just(List.of());
        }
        return Flux.fromIterable(toolCalls)
                .concatMap(
                        use -> {
                            AgentTool tool = toolkit.getTool(use.getName());
                            if (!(tool instanceof ToolBase tb)) {
                                return Mono.<ToolUseBlock>empty();
                            }
                            Map<String, Object> input =
                                    use.getInput() == null ? Map.of() : use.getInput();
                            return tb.checkPermissions(input, state.getPermissionContext())
                                    .flatMap(
                                            decision -> {
                                                if (decision != null
                                                        && decision.getBehavior()
                                                                == PermissionBehavior.ASK) {
                                                    return Mono.just(use);
                                                }
                                                return Mono.<ToolUseBlock>empty();
                                            });
                        })
                .collectList();
    }

    /**
     * Reads a {@link Sinks.Many} of {@link ConfirmResult} from the subscriber's Reactor context
     * under {@link #CONFIRM_SINK_KEY}, takes one response per pending tool call, and returns the
     * collected list. When the sink is absent, auto-denies all pending calls.
     */
    private Mono<List<ConfirmResult>> awaitConfirmation(List<ToolUseBlock> pending) {
        return Mono.deferContextual(
                ctx -> {
                    if (!ctx.hasKey(CONFIRM_SINK_KEY)) {
                        List<ConfirmResult> denied = new ArrayList<>(pending.size());
                        for (ToolUseBlock t : pending) {
                            denied.add(new ConfirmResult(false, t));
                        }
                        return Mono.just(denied);
                    }
                    Object raw = ctx.get(CONFIRM_SINK_KEY);
                    if (!(raw instanceof Sinks.Many<?> rawSink)) {
                        return Mono.error(
                                new IllegalStateException(
                                        "Reactor context value for "
                                                + CONFIRM_SINK_KEY
                                                + " is not a Sinks.Many<ConfirmResult>"));
                    }
                    @SuppressWarnings("unchecked")
                    Sinks.Many<ConfirmResult> sink = (Sinks.Many<ConfirmResult>) rawSink;
                    return sink.asFlux().take(pending.size()).collectList();
                });
    }

    private static Set<String> collectDeniedIds(List<ConfirmResult> results) {
        Set<String> denied = new HashSet<>();
        if (results == null) {
            return denied;
        }
        for (ConfirmResult r : results) {
            if (r != null && !r.isConfirmed() && r.getToolCall() != null) {
                denied.add(r.getToolCall().getId());
            }
        }
        return denied;
    }

    private Flux<AgentEvent> emitDeniedToolResult(ToolUseBlock use, ReplyContext rctx) {
        ToolResultBlock denied =
                ToolResultBlock.text("Permission denied by user")
                        .withIdAndName(use.getId(), use.getName())
                        .withState(ToolResultState.DENIED);
        Msg toolMsg = Msg.builder().role(MsgRole.TOOL).name(name).content(denied).build();
        state.contextMutable().add(compressor.truncateToolResultMsg(toolMsg));
        return Flux.just(
                new ToolResultStartEvent(rctx.replyId, use.getId(), use.getName()),
                new ToolResultTextDeltaEvent(
                        rctx.replyId, use.getId(), "Permission denied by user"),
                new ToolResultEndEvent(rctx.replyId, use.getId(), ToolResultState.DENIED));
    }

    private Flux<AgentEvent> executeSingleTool(ToolUseBlock use, ReplyContext rctx) {
        ToolCallParam param =
                ToolCallParam.builder().toolUseBlock(use).input(use.getInput()).agent(this).build();
        Flux<AgentEvent> start =
                Flux.just(
                        (AgentEvent)
                                new ToolResultStartEvent(rctx.replyId, use.getId(), use.getName()));
        Mono<ToolResultBlock> exec =
                toolkit.callTool(param)
                        .onErrorResume(t -> Mono.just(ToolResultBlock.error(throwableToString(t))));
        return start.concatWith(
                exec.flatMapMany(
                        result -> {
                            List<AgentEvent> events = new ArrayList<>();
                            String text = extractText(result);
                            if (text != null && !text.isEmpty()) {
                                events.add(
                                        new ToolResultTextDeltaEvent(
                                                rctx.replyId, use.getId(), text));
                            }
                            ToolResultState resultState = determineToolResultState(result);
                            events.add(
                                    new ToolResultEndEvent(rctx.replyId, use.getId(), resultState));
                            Msg toolMsg =
                                    Msg.builder()
                                            .role(MsgRole.TOOL)
                                            .name(name)
                                            .content(
                                                    result.withIdAndName(
                                                            use.getId(), use.getName()))
                                            .build();
                            state.contextMutable().add(compressor.truncateToolResultMsg(toolMsg));
                            return Flux.fromIterable(events);
                        }));
    }

    // ==================== Helpers ====================

    private List<Msg> buildReasoningMessages() {
        List<Msg> messages = new ArrayList<>();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            messages.add(
                    Msg.builder()
                            .role(MsgRole.SYSTEM)
                            .name(name)
                            .textContent(systemPrompt)
                            .build());
        }
        messages.addAll(state.getContext());
        return messages;
    }

    private Msg lastAssistantMsg() {
        List<Msg> ctx = state.getContext();
        for (int i = ctx.size() - 1; i >= 0; i--) {
            if (ctx.get(i).getRole() == MsgRole.ASSISTANT) {
                return ctx.get(i);
            }
        }
        return null;
    }

    private Mono<Event> adaptToCoarseEvent(AgentEvent fine) {
        return Mono.empty();
    }

    private ToolResultState determineToolResultState(ToolResultBlock result) {
        if (result.isSuspended()) {
            return ToolResultState.RUNNING;
        }
        if (result.getState() != null && result.getState() != ToolResultState.RUNNING) {
            return result.getState();
        }
        if (result.getOutput() != null
                && result.getOutput().stream()
                        .anyMatch(
                                b ->
                                        b instanceof TextBlock tb
                                                && tb.getText() != null
                                                && tb.getText().startsWith("[ERROR]"))) {
            return ToolResultState.ERROR;
        }
        return ToolResultState.SUCCESS;
    }

    private static String extractText(ToolResultBlock result) {
        if (result == null || result.getOutput() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : result.getOutput()) {
            if (block instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText());
            }
        }
        return sb.toString();
    }

    private static String throwableToString(Throwable t) {
        return t.getClass().getSimpleName() + ": " + (t.getMessage() == null ? "" : t.getMessage());
    }

    // ==================== Private support types ====================

    /** Mutable state shared across one reply invocation. */
    private static final class ReplyContext {
        final String replyId;
        final AtomicReference<Msg> lastReasoningMsg = new AtomicReference<>(null);

        ReplyContext(String replyId) {
            this.replyId = replyId;
        }
    }

    /**
     * Accumulates streamed model chunks into per-block events plus a final assistant Msg.
     *
     * <p>Tracks at most one open text block and one open thinking block (both keyed
     * by the literal block ids {@code "text"} / {@code "thinking"}), plus an ordered
     * set of tool-use blocks keyed by their tool-call id.
     */
    private static final class BlockAggregator {
        private final String replyId;
        private final StringBuilder text = new StringBuilder();
        private final StringBuilder thinking = new StringBuilder();
        private final Map<String, ToolUseAccumulator> toolUses = new LinkedHashMap<>();
        private boolean textStarted;
        private boolean thinkingStarted;
        ChatUsage usage;

        BlockAggregator(String replyId) {
            this.replyId = replyId;
        }

        List<AgentEvent> absorb(ChatResponse chunk) {
            List<AgentEvent> events = new ArrayList<>();
            if (chunk.getUsage() != null) {
                usage = chunk.getUsage();
            }
            if (chunk.getContent() == null) {
                return events;
            }
            for (ContentBlock block : chunk.getContent()) {
                if (block instanceof TextBlock tb) {
                    if (!textStarted) {
                        textStarted = true;
                        events.add(new TextBlockStartEvent(replyId, "text"));
                    }
                    String t = tb.getText();
                    if (t != null && !t.isEmpty()) {
                        text.append(t);
                        events.add(new TextBlockDeltaEvent(replyId, "text", t));
                    }
                } else if (block instanceof ThinkingBlock thb) {
                    if (!thinkingStarted) {
                        thinkingStarted = true;
                        events.add(new ThinkingBlockStartEvent(replyId, "thinking"));
                    }
                    String t = thb.getThinking();
                    if (t != null && !t.isEmpty()) {
                        thinking.append(t);
                        events.add(new ThinkingBlockDeltaEvent(replyId, "thinking", t));
                    }
                } else if (block instanceof ToolUseBlock tub) {
                    String id = tub.getId() != null ? tub.getId() : "";
                    ToolUseAccumulator acc =
                            toolUses.computeIfAbsent(
                                    id,
                                    key -> {
                                        ToolUseAccumulator created = new ToolUseAccumulator(tub);
                                        if (tub.getName() != null
                                                && !tub.getName().startsWith("__")) {
                                            events.add(
                                                    new ToolCallStartEvent(
                                                            replyId, id, tub.getName()));
                                        }
                                        return created;
                                    });
                    acc.merge(tub);
                    if (tub.getContent() != null && !tub.getContent().isEmpty()) {
                        events.add(new ToolCallDeltaEvent(replyId, id, tub.getContent()));
                    }
                }
            }
            return events;
        }

        List<AgentEvent> finishAndBuild() {
            List<AgentEvent> events = new ArrayList<>();
            if (textStarted) {
                events.add(new TextBlockEndEvent(replyId, "text"));
            }
            if (thinkingStarted) {
                events.add(new ThinkingBlockEndEvent(replyId, "thinking"));
            }
            for (String id : toolUses.keySet()) {
                events.add(new ToolCallEndEvent(replyId, id));
            }
            return events;
        }

        Msg toAssistantMsg(String agentName) {
            List<ContentBlock> blocks = new ArrayList<>();
            if (thinkingStarted) {
                blocks.add(ThinkingBlock.builder().thinking(thinking.toString()).build());
            }
            if (textStarted) {
                blocks.add(TextBlock.builder().text(text.toString()).build());
            }
            for (Iterator<ToolUseAccumulator> it = toolUses.values().iterator(); it.hasNext(); ) {
                blocks.add(it.next().build());
            }
            return Msg.builder().role(MsgRole.ASSISTANT).name(agentName).content(blocks).build();
        }
    }

    private static final class ToolUseAccumulator {
        private String id;
        private String name;
        private final Map<String, Object> input = new LinkedHashMap<>();
        private final StringBuilder content = new StringBuilder();

        ToolUseAccumulator(ToolUseBlock first) {
            this.id = first.getId();
            this.name = first.getName();
        }

        void merge(ToolUseBlock chunk) {
            if (chunk.getId() != null && !chunk.getId().isEmpty()) {
                id = chunk.getId();
            }
            if (chunk.getName() != null && !chunk.getName().isEmpty()) {
                name = chunk.getName();
            }
            if (chunk.getInput() != null) {
                input.putAll(chunk.getInput());
            }
            if (chunk.getContent() != null) {
                content.append(chunk.getContent());
            }
        }

        ToolUseBlock build() {
            return ToolUseBlock.builder()
                    .id(id)
                    .name(name)
                    .input(input)
                    .content(content.length() == 0 ? null : content.toString())
                    .build();
        }
    }
}
