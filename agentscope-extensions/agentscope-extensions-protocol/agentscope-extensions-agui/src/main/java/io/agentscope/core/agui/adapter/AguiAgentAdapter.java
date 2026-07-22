/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui.adapter;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverterRegistry;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.converter.AguiMessageConverter;
import io.agentscope.core.agui.converter.AguiToolConverter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.model.ToolMergeMode;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.SchemaOnlyTool;
import io.agentscope.core.tool.Toolkit;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import reactor.core.publisher.Flux;

/**
 * Adapter that bridges AgentScope agents to the AG-UI protocol.
 *
 * <p>This adapter converts AG-UI protocol inputs to AgentScope messages,
 * invokes the agent, and converts the streaming events back to AG-UI events.
 *
 * <p><b>Event Mapping:</b>
 * <ul>
 *   <li>AgentScope REASONING/SUMMARY events → AG-UI TEXT_MESSAGE_* events (for TextBlock)</li>
 *   <li>AgentScope REASONING/SUMMARY events → AG-UI REASONING_* events (for
 *       ThinkingBlock, when enabled)</li>
 *   <li>AgentScope TOOL_RESULT events → AG-UI TOOL_CALL_END events</li>
 *   <li>ToolUseBlock content → AG-UI TOOL_CALL_START events</li>
 * </ul>
 *
 * <p><b>Reasoning Support:</b>
 * <ul>
 *   <li>ThinkingBlock content is converted to REASONING_* events according to AG-UI Reasoning draft</li>
 *   <li>Reasoning output is disabled by default (enableReasoning=false) for backward compatibility</li>
 *   <li>Set enableReasoning=true in AguiAdapterConfig to enable reasoning events</li>
 * </ul>
 */
public class AguiAgentAdapter {

    public static final String RUNTIME_CONTEXT_THREAD_ID_KEY = "agui.threadId";
    public static final String RUNTIME_CONTEXT_RUN_ID_KEY = "agui.runId";
    public static final String RUNTIME_CONTEXT_MESSAGES_KEY = "agui.messages";
    public static final String RUNTIME_CONTEXT_TOOLS_KEY = "agui.tools";
    public static final String RUNTIME_CONTEXT_CONTEXT_KEY = "agui.context";
    public static final String RUNTIME_CONTEXT_STATE_KEY = "agui.state";
    public static final String RUNTIME_CONTEXT_FORWARDED_PROPS_KEY = "agui.forwardedProps";

    private final Agent agent;
    private final AguiAdapterConfig config;
    private final AguiMessageConverter messageConverter;
    private final AguiToolConverter toolConverter;
    private final AgentEventConverterRegistry agentEventConverterRegistry;

    /**
     * Creates a new AguiAgentAdapter.
     *
     * @param agent The agent to adapt
     * @param config The adapter configuration
     */
    public AguiAgentAdapter(Agent agent, AguiAdapterConfig config) {
        this.agent = Objects.requireNonNull(agent, "agent cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.messageConverter = new AguiMessageConverter();
        this.toolConverter = new AguiToolConverter();
        this.agentEventConverterRegistry =
                new AgentEventConverterRegistry(
                        config.getEventConverters(), config.getEventEnrichers());
    }

    /**
     * Run the agent with AG-UI protocol input.
     *
     * <p>This method converts the input messages, invokes the agent's streaming API,
     * and emits AG-UI protocol events.
     *
     * @param input The AG-UI run input
     * @return A Flux of AG-UI events
     */
    public Flux<AguiEvent> run(RunAgentInput input) {
        return Flux.defer(
                () -> {
                    String threadId = input.getThreadId();
                    String runId = input.getRunId();

                    // Convert AG-UI messages to AgentScope messages
                    List<Msg> msgs = messageConverter.toMsgList(input.getMessages());

                    // Create stream options - use incremental mode for true streaming
                    StreamOptions options =
                            StreamOptions.builder()
                                    .eventTypes(EventType.ALL)
                                    .incremental(true)
                                    .build();

                    RuntimeContext runtimeContext = buildRuntimeContext(input);
                    ToolInjection toolInjection = ToolInjection.empty();
                    AgentStream agentStream;
                    try {
                        toolInjection = injectFrontendTools(input);
                        agentStream =
                                streamWithRuntimeContext(msgs, options, runtimeContext, input);
                    } catch (Throwable error) {
                        toolInjection.close();
                        return Flux.concat(
                                Flux.just(new AguiEvent.RunStarted(threadId, runId, null, input)),
                                errorEvents(threadId, runId, error));
                    }

                    ToolInjection activeToolInjection = toolInjection;

                    return Flux.concat(
                                    agentStream.events(),
                                    Flux.defer(() -> agentStream.finish().get()))
                            .doFinally(signalType -> activeToolInjection.close())
                            .onErrorResume(error -> errorEvents(threadId, runId, error));
                });
    }

    private AgentStream streamWithRuntimeContext(
            List<Msg> msgs,
            StreamOptions options,
            RuntimeContext runtimeContext,
            RunAgentInput input) {
        String threadId = input.getThreadId();
        String runId = input.getRunId();

        if (agent instanceof ReActAgent reAct) {
            AguiStreamContext context = new AguiStreamContext(threadId, runId, config, input);
            Flux<AgentEvent> events =
                    Objects.requireNonNull(
                            reAct.streamEvents(msgs, runtimeContext), "agent stream is null");
            return new AgentStream(
                    convertAgentEvents(events, context),
                    () -> Flux.fromIterable(context.finishPendingEvents()));
        }
        if (isHarnessAgent(agent)) {
            AguiStreamContext context = new AguiStreamContext(threadId, runId, config, input);
            Flux<AgentEvent> events =
                    Objects.requireNonNull(
                            invokeHarnessStreamEvents(agent, msgs, runtimeContext),
                            "agent stream is null");
            return new AgentStream(
                    convertAgentEvents(events, context),
                    () -> Flux.fromIterable(context.finishPendingEvents()));
        }

        // fallback 1.x
        EventConversionState state = new EventConversionState(threadId, runId);
        Flux<Event> events = agent.stream(msgs, options, runtimeContext);
        if (events == null) {
            events = agent.stream(msgs, options);
        }
        Objects.requireNonNull(events, "agent stream is null");
        return new AgentStream(
                Flux.concat(
                        Flux.just(new AguiEvent.RunStarted(threadId, runId)),
                        events.concatMapIterable(event -> convertEvent(event, state))),
                () -> finishRun(state));
    }

    private Flux<AguiEvent> convertAgentEvents(Flux<AgentEvent> events, AguiStreamContext context) {
        return events
                // Use concatMapIterable to preserve strict event ordering
                .concatMapIterable(event -> agentEventConverterRegistry.convert(event, context))
                .onErrorResume(
                        error ->
                                Flux.concat(
                                        Flux.fromIterable(context.finishPendingEvents()),
                                        Flux.error(error)));
    }

    private record AgentStream(Flux<AguiEvent> events, Supplier<Flux<AguiEvent>> finish) {}

    @SuppressWarnings("unchecked")
    private Flux<AgentEvent> invokeHarnessStreamEvents(
            Agent harnessAgent, List<Msg> msgs, RuntimeContext runtimeContext) {
        try {
            Method method =
                    harnessAgent
                            .getClass()
                            .getMethod("streamEvents", List.class, RuntimeContext.class);
            Object result = method.invoke(harnessAgent, msgs, runtimeContext);
            if (!(result instanceof Flux<?> flux)) {
                throw new IllegalStateException("HarnessAgent streamEvents did not return Flux");
            }
            return (Flux<AgentEvent>) flux;
        } catch (InvocationTargetException e) {
            Throwable target = e.getTargetException();
            if (target instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (target instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("HarnessAgent streamEvents failed", target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "HarnessAgent does not expose streamEvents(List, RuntimeContext)", e);
        }
    }

    private static boolean isHarnessAgent(Agent agent) {
        Class<?> type = agent.getClass();
        while (type != null) {
            if ("io.agentscope.harness.agent.HarnessAgent".equals(type.getName())) {
                return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private RuntimeContext buildRuntimeContext(RunAgentInput input) {
        return RuntimeContext.builder()
                .sessionId(input.getThreadId())
                .put(RunAgentInput.class, input)
                .put(RUNTIME_CONTEXT_THREAD_ID_KEY, input.getThreadId())
                .put(RUNTIME_CONTEXT_RUN_ID_KEY, input.getRunId())
                .put(RUNTIME_CONTEXT_MESSAGES_KEY, input.getMessages())
                .put(RUNTIME_CONTEXT_TOOLS_KEY, input.getTools())
                .put(RUNTIME_CONTEXT_CONTEXT_KEY, input.getContext())
                .put(RUNTIME_CONTEXT_STATE_KEY, input.getState())
                .put(RUNTIME_CONTEXT_FORWARDED_PROPS_KEY, input.getForwardedProps())
                .build();
    }

    private ToolInjection injectFrontendTools(RunAgentInput input) {
        if (!input.hasTools()) {
            return ToolInjection.empty();
        }

        ToolMergeMode mergeMode =
                config.getToolMergeMode() != null
                        ? config.getToolMergeMode()
                        : ToolMergeMode.MERGE_FRONTEND_PRIORITY;
        if (mergeMode == ToolMergeMode.AGENT_ONLY) {
            return ToolInjection.empty();
        }

        Toolkit toolkit = agent.getToolkit();
        if (toolkit == null) {
            return ToolInjection.empty();
        }

        Map<String, AgentTool> previousTools = new LinkedHashMap<>();
        if (mergeMode == ToolMergeMode.FRONTEND_ONLY) {
            for (String toolName : toolkit.getToolNames()) {
                AgentTool previousTool = toolkit.getTool(toolName);
                if (previousTool != null) {
                    previousTools.put(toolName, previousTool);
                    toolkit.removeTool(toolName);
                }
            }
        }

        List<SchemaOnlyTool> registeredTools = new ArrayList<>();
        for (ToolSchema schema : toolConverter.toToolSchemaList(input.getTools())) {
            AgentTool previousTool = toolkit.getTool(schema.getName());
            if (previousTool != null) {
                previousTools.putIfAbsent(schema.getName(), previousTool);
            }

            SchemaOnlyTool frontendTool = new SchemaOnlyTool(schema);
            toolkit.registerAgentTool(frontendTool);
            registeredTools.add(frontendTool);
        }

        return new ToolInjection(toolkit, registeredTools, previousTools);
    }

    private Flux<AguiEvent> errorEvents(String threadId, String runId, Throwable error) {
        String errorMessage =
                error.getMessage() != null ? error.getMessage() : error.getClass().getSimpleName();
        return Flux.just(
                new AguiEvent.RunError(
                        threadId,
                        runId,
                        errorMessage,
                        mapErrorCode(error),
                        System.currentTimeMillis(),
                        null),
                new AguiEvent.RunFinished(threadId, runId));
    }

    private static String mapErrorCode(Throwable error) {
        if (error instanceof java.util.concurrent.TimeoutException) {
            return "TIMEOUT_ERROR";
        }
        if (error instanceof java.lang.InterruptedException) {
            return "INTERRUPTED_ERROR";
        }
        if (error instanceof IllegalArgumentException || error instanceof IllegalStateException) {
            return "INVALID_INPUT_ERROR";
        }
        return "INTERNAL_ERROR";
    }

    // ========================================================================
    // Legacy v1 stream() conversion methods below. Kept for generic Agent fallback.
    // ========================================================================

    /**
     * Convert an AgentScope event to AG-UI events.
     *
     * @param event The AgentScope event
     * @param state The conversion state
     * @return List of AG-UI events
     * @deprecated since 2.0.0, use streamEvents() conversion strategies instead.
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    private List<AguiEvent> convertEvent(Event event, EventConversionState state) {
        List<AguiEvent> events = new ArrayList<>();
        Msg msg = event.getMessage();
        EventType type = event.getType();

        if (type == EventType.REASONING || type == EventType.SUMMARY) {
            // Handle reasoning/summary events - convert to text messages and tool calls
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof TextBlock textBlock) {
                    String text = textBlock.getText();
                    if (text != null && !text.isEmpty()) {
                        String messageId = msg.getId();

                        // Start message if not started
                        if (!state.hasStartedMessage(messageId)) {
                            events.add(
                                    new AguiEvent.TextMessageStart(
                                            state.threadId, state.runId, messageId, "assistant"));
                            state.startMessage(messageId);
                        }

                        if (!event.isLast()) {
                            // In incremental mode, text is already the delta
                            events.add(
                                    new AguiEvent.TextMessageContent(
                                            state.threadId, state.runId, messageId, text));
                        } else {
                            // End message if this is the last event
                            if (!state.hasEndedMessage(messageId)) {
                                events.add(
                                        new AguiEvent.TextMessageEnd(
                                                state.threadId, state.runId, messageId));
                                state.endMessage(messageId);
                            }
                        }
                    }
                } else if (block instanceof ThinkingBlock thinkingBlock) {
                    // Handle thinking blocks - convert to REASONING_* events (only if enabled)
                    // According to AG-UI Reasoning draft: https://docs.ag-ui.com/drafts/reasoning
                    if (config.isEnableReasoning()) {
                        String thinking = thinkingBlock.getThinking();
                        if (thinking != null && !thinking.isEmpty()) {
                            String messageId = msg.getId();

                            // Start reasoning message if not started
                            if (!state.hasStartedReasoningMessage(messageId)) {
                                events.add(
                                        new AguiEvent.ReasoningMessageStart(
                                                state.threadId,
                                                state.runId,
                                                messageId,
                                                "reasoning"));
                                state.startReasoningMessage(messageId);
                            }

                            if (!event.isLast()) {
                                // In incremental mode, thinking is already the delta
                                events.add(
                                        new AguiEvent.ReasoningMessageContent(
                                                state.threadId, state.runId, messageId, thinking));
                            } else {
                                // End reasoning message if this is the last event
                                events.add(
                                        new AguiEvent.ReasoningMessageEnd(
                                                state.threadId, state.runId, messageId));
                                state.endReasoningMessage(messageId);
                            }
                        }
                    }
                    // If reasoning is disabled, ThinkingBlock content is ignored (backward
                    // compatibility)
                } else if (block instanceof ToolUseBlock toolUse) {
                    // End any active text message before starting tool call
                    if (state.hasActiveTextMessage()) {
                        String activeMessageId = state.getCurrentTextMessageId();
                        events.add(
                                new AguiEvent.TextMessageEnd(
                                        state.threadId, state.runId, activeMessageId));
                        state.endMessage(activeMessageId);
                    }

                    // End any active reasoning message before starting tool call
                    if (state.hasActiveReasoningMessage()) {
                        String activeReasoningMessageId = state.getCurrentReasoningMessageId();
                        events.add(
                                new AguiEvent.ReasoningMessageEnd(
                                        state.threadId, state.runId, activeReasoningMessageId));
                        state.endReasoningMessage(activeReasoningMessageId);
                    }

                    // Emit tool call start
                    String toolCallId = toolUse.getId();
                    if (toolCallId == null) {
                        toolCallId = UUID.randomUUID().toString();
                    }

                    if (!state.hasStartedToolCall(toolCallId)) {
                        events.add(
                                new AguiEvent.ToolCallStart(
                                        state.threadId,
                                        state.runId,
                                        toolCallId,
                                        toolUse.getName()));
                        state.startToolCall(toolCallId);
                    }

                    // Emit tool call args if enabled
                    if (config.isEmitToolCallArgs() && !event.isLast()) {
                        String args = toolUse.getContent();
                        if (args != null && !args.isEmpty()) {
                            events.add(
                                    new AguiEvent.ToolCallArgs(
                                            state.threadId, state.runId, toolCallId, args));
                        }
                    }
                }
            }
        } else if (type == EventType.TOOL_RESULT && event.isLast()) {
            // Handle tool results
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ToolResultBlock toolResult) {
                    String toolCallId = toolResult.getId();
                    if (toolCallId == null) {
                        toolCallId = UUID.randomUUID().toString();
                    }

                    String result = extractToolResultText(toolResult);

                    boolean hasStarted = state.hasStartedToolCall(toolCallId);
                    if (!hasStarted) {
                        String toolName = toolResult.getName();
                        if (toolName == null || toolName.isBlank()) {
                            toolName = "unknown";
                        }
                        events.add(
                                new AguiEvent.ToolCallStart(
                                        state.threadId, state.runId, toolCallId, toolName));
                        state.startToolCall(toolCallId);
                    }

                    // Ensure ToolCallEnd is emitted to close arguments phase
                    events.add(new AguiEvent.ToolCallEnd(state.threadId, state.runId, toolCallId));

                    events.add(
                            new AguiEvent.ToolCallResult(
                                    state.threadId,
                                    state.runId,
                                    toolCallId,
                                    result,
                                    "tool",
                                    msg.getId()));
                    state.endToolCall(toolCallId);
                }
            }
        }

        return events;
    }

    /**
     * Finish the run by emitting any pending end events and RUN_FINISHED.
     *
     * @param state The conversion state
     * @return Flux of final events
     * @deprecated since 2.0.0, v2 stream cleanup is handled by {@link AguiStreamContext}.
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    private Flux<AguiEvent> finishRun(EventConversionState state) {
        List<AguiEvent> events = new ArrayList<>();

        // End any messages that weren't properly ended
        for (String messageId : state.getStartedMessages()) {
            if (!state.hasEndedMessage(messageId)) {
                events.add(new AguiEvent.TextMessageEnd(state.threadId, state.runId, messageId));
            }
        }

        // End any tool calls that weren't properly ended
        for (String toolCallId : state.getStartedToolCalls()) {
            if (!state.hasEndedToolCall(toolCallId)) {
                events.add(new AguiEvent.ToolCallEnd(state.threadId, state.runId, toolCallId));
            }
        }

        // End any reasoning messages that weren't properly ended
        for (String messageId : state.getStartedReasoningMessages()) {
            if (!state.hasEndedReasoningMessage(messageId)) {
                events.add(
                        new AguiEvent.ReasoningMessageEnd(state.threadId, state.runId, messageId));
            }
        }

        // Emit RUN_FINISHED
        events.add(new AguiEvent.RunFinished(state.threadId, state.runId));

        return Flux.fromIterable(events);
    }

    /**
     * Extract text content from a tool result block.
     *
     * @param toolResult The tool result block
     * @return The text content, or null if not present
     * @deprecated since 2.0.0, tool result aggregation is handled by v2 converters.
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    private String extractToolResultText(ToolResultBlock toolResult) {
        if (toolResult.getOutput() == null || toolResult.getOutput().isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (ContentBlock output : toolResult.getOutput()) {
            if (output instanceof TextBlock textBlock) {
                if (!sb.isEmpty()) {
                    sb.append("\n");
                }
                sb.append(textBlock.getText());
            }
        }

        return !sb.isEmpty() ? sb.toString() : null;
    }

    /**
     * State tracker for event conversion.
     * Uses LinkedHashSet to preserve insertion order for proper event sequencing.
     *
     * @deprecated since 2.0.0, use {@link AguiStreamContext} for streamEvents() conversion state.
     */
    @Deprecated(since = "2.0.0", forRemoval = false)
    private static class EventConversionState {
        final String threadId;
        final String runId;
        private final Set<String> startedMessages = new LinkedHashSet<>();
        private final Set<String> endedMessages = new LinkedHashSet<>();
        private final Set<String> startedToolCalls = new LinkedHashSet<>();
        private final Set<String> endedToolCalls = new LinkedHashSet<>();
        private final Set<String> startedReasoningMessages = new LinkedHashSet<>();
        private final Set<String> endedReasoningMessages = new LinkedHashSet<>();
        private String currentTextMessageId = null;
        private String currentReasoningMessageId = null;

        EventConversionState(String threadId, String runId) {
            this.threadId = threadId;
            this.runId = runId;
        }

        boolean hasStartedMessage(String messageId) {
            return startedMessages.contains(messageId);
        }

        void startMessage(String messageId) {
            startedMessages.add(messageId);
            currentTextMessageId = messageId;
        }

        void endMessage(String messageId) {
            endedMessages.add(messageId);
            if (Objects.equals(messageId, currentTextMessageId)) {
                currentTextMessageId = null;
            }
        }

        boolean hasEndedMessage(String messageId) {
            return endedMessages.contains(messageId);
        }

        String getCurrentTextMessageId() {
            return currentTextMessageId;
        }

        boolean hasActiveTextMessage() {
            return currentTextMessageId != null && !hasEndedMessage(currentTextMessageId);
        }

        Set<String> getStartedMessages() {
            return startedMessages;
        }

        boolean hasStartedToolCall(String toolCallId) {
            return startedToolCalls.contains(toolCallId);
        }

        void startToolCall(String toolCallId) {
            startedToolCalls.add(toolCallId);
        }

        void endToolCall(String toolCallId) {
            endedToolCalls.add(toolCallId);
        }

        boolean hasEndedToolCall(String toolCallId) {
            return endedToolCalls.contains(toolCallId);
        }

        Set<String> getStartedToolCalls() {
            return startedToolCalls;
        }

        boolean hasStartedReasoningMessage(String messageId) {
            return startedReasoningMessages.contains(messageId);
        }

        void startReasoningMessage(String messageId) {
            startedReasoningMessages.add(messageId);
            currentReasoningMessageId = messageId;
        }

        void endReasoningMessage(String messageId) {
            endedReasoningMessages.add(messageId);
            if (Objects.equals(messageId, currentReasoningMessageId)) {
                currentReasoningMessageId = null;
            }
        }

        boolean hasEndedReasoningMessage(String messageId) {
            return endedReasoningMessages.contains(messageId);
        }

        String getCurrentReasoningMessageId() {
            return currentReasoningMessageId;
        }

        boolean hasActiveReasoningMessage() {
            return currentReasoningMessageId != null
                    && !hasEndedReasoningMessage(currentReasoningMessageId);
        }

        Set<String> getStartedReasoningMessages() {
            return startedReasoningMessages;
        }
    }
}
