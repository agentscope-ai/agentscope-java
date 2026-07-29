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

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventStreamingAgent;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agui.converter.AguiMessageConverter;
import io.agentscope.core.agui.converter.AguiToolConverter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.model.ToolMergeMode;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ConfirmResult;
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
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.SchemaOnlyTool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonException;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
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

    /**
     * Key under {@link RunAgentInput#getForwardedProps()} where the client sends back
     * human-in-the-loop confirmation results to resume a paused run. The value is a list of maps,
     * each shaped like {@code {"toolCallId": "...", "confirmed": true, "toolName": "...",
     * "input": {...}}}.
     */
    public static final String FORWARDED_PROPS_CONFIRM_RESULTS_KEY = "agentscope_confirm_results";

    /** Reason string used on {@link AguiEvent.Interrupt}s emitted for HITL tool confirmation. */
    static final String CONFIRM_INTERRUPT_REASON = "tool_confirmation";

    private final Agent agent;
    private final AguiAdapterConfig config;
    private final AguiMessageConverter messageConverter;
    private final AguiToolConverter toolConverter;

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

                    // HITL resume: if the client sent back confirmation results via forwardedProps,
                    // attach them to the latest message so ReActAgent can apply them and continue.
                    msgs = attachConfirmResults(msgs, input);

                    // Track state for event conversion
                    EventConversionState state = new EventConversionState(threadId, runId);
                    RuntimeContext runtimeContext = buildRuntimeContext(input);
                    ToolInjection toolInjection = ToolInjection.empty();
                    Flux<AguiEvent> convertedStream;
                    try {
                        toolInjection = injectFrontendTools(input);
                        convertedStream = buildConvertedStream(msgs, runtimeContext, state);
                    } catch (Throwable error) {
                        toolInjection.close();
                        return Flux.concat(
                                Flux.just(new AguiEvent.RunStarted(threadId, runId)),
                                errorEvents(threadId, runId, error));
                    }

                    ToolInjection activeToolInjection = toolInjection;

                    return Flux.concat(
                                    // Emit RUN_STARTED
                                    Flux.just(
                                            new AguiEvent.RunStarted(threadId, runId, null, input)),
                                    // Stream converted AG-UI events
                                    convertedStream,
                                    // Emit any pending end events and RUN_FINISHED
                                    Flux.defer(() -> finishRun(state)))
                            .doFinally(signalType -> activeToolInjection.close())
                            .onErrorResume(error -> errorEvents(threadId, runId, error));
                });
    }

    /**
     * Build the stream of AG-UI events converted from the agent's event stream.
     *
     * <p>When the underlying agent is an {@link EventStreamingAgent} (such as {@code ReActAgent} or
     * {@code HarnessAgent}), this consumes the fine-grained v2 {@link AgentEvent} stream via {@link
     * EventStreamingAgent#streamEvents(List, RuntimeContext)}. For any other {@link Agent}
     * implementation it falls back to the deprecated v1 {@link Event} stream so that custom agents
     * (and existing integrations) keep working unchanged.
     *
     * @param msgs the converted input messages
     * @param runtimeContext the per-run runtime context
     * @param state the conversion state tracker
     * @return a Flux of AG-UI events (without RUN_STARTED / RUN_FINISHED bookends)
     */
    private Flux<AguiEvent> buildConvertedStream(
            List<Msg> msgs, RuntimeContext runtimeContext, EventConversionState state) {
        if (agent instanceof EventStreamingAgent streamingAgent) {
            Flux<AgentEvent> agentEvents = streamingAgent.streamEvents(msgs, runtimeContext);
            agentEvents = Objects.requireNonNull(agentEvents, "agent stream is null");
            return agentEvents.concatMapIterable(event -> convertAgentEvent(event, state));
        }

        // Fallback: deprecated v1 Event stream for agents that do not support event streaming.
        StreamOptions options =
                StreamOptions.builder().eventTypes(EventType.ALL).incremental(true).build();
        Flux<Event> agentEvents = agent.stream(msgs, options, runtimeContext);
        if (agentEvents == null) {
            agentEvents = agent.stream(msgs, options);
        }
        agentEvents = Objects.requireNonNull(agentEvents, "agent stream is null");
        return agentEvents.concatMapIterable(event -> convertEvent(event, state));
    }

    /**
     * Translate human-in-the-loop confirmation results carried in {@link
     * RunAgentInput#getForwardedProps()} into a {@code List<ConfirmResult>} attached to the last
     * message under {@link Msg#METADATA_CONFIRM_RESULTS}, so a resumed {@link ReActAgent} can apply
     * them to its ASKING tool calls and continue.
     *
     * <p>The expected {@code forwardedProps["agentscope_confirm_results"]} value is a {@code List}
     * of maps, each shaped like {@code {"toolCallId": "...", "confirmed": true, "toolName": "...",
     * "input": {...}}}. Entries missing a {@code toolCallId} are ignored. When no confirmation
     * results are present the input messages are returned unchanged.
     *
     * @param msgs the converted input messages
     * @param input the AG-UI run input
     * @return the (possibly modified) message list to feed the agent
     */
    private List<Msg> attachConfirmResults(List<Msg> msgs, RunAgentInput input) {
        List<ConfirmResult> confirmResults = parseConfirmResults(input.getForwardedProps());
        if (confirmResults.isEmpty()) {
            return msgs;
        }

        List<Msg> result = new ArrayList<>(msgs);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(Msg.METADATA_CONFIRM_RESULTS, confirmResults);

        if (result.isEmpty()) {
            // No carrier message from the client; synthesise a minimal user message.
            result.add(
                    Msg.builder()
                            .name("user")
                            .role(MsgRole.USER)
                            .textContent("[confirm]")
                            .metadata(metadata)
                            .build());
            return result;
        }

        // Merge the confirm-result metadata onto the last message, preserving its existing fields.
        int lastIdx = result.size() - 1;
        Msg last = result.get(lastIdx);
        Map<String, Object> merged = new HashMap<>();
        if (last.getMetadata() != null) {
            merged.putAll(last.getMetadata());
        }
        merged.put(Msg.METADATA_CONFIRM_RESULTS, confirmResults);
        result.set(
                lastIdx,
                Msg.builder()
                        .id(last.getId())
                        .name(last.getName())
                        .role(last.getRole())
                        .content(last.getContent())
                        .metadata(merged)
                        .build());
        return result;
    }

    /**
     * Parse the raw {@code forwardedProps} confirmation payload into {@link ConfirmResult}s.
     *
     * @param forwardedProps the AG-UI forwardedProps map (may be null/empty)
     * @return the parsed confirmation results, never null
     */
    @SuppressWarnings("unchecked")
    private List<ConfirmResult> parseConfirmResults(Map<String, Object> forwardedProps) {
        if (forwardedProps == null || forwardedProps.isEmpty()) {
            return Collections.emptyList();
        }
        Object raw = forwardedProps.get(FORWARDED_PROPS_CONFIRM_RESULTS_KEY);
        if (!(raw instanceof List<?> rawList) || rawList.isEmpty()) {
            return Collections.emptyList();
        }

        List<ConfirmResult> results = new ArrayList<>();
        for (Object element : rawList) {
            if (!(element instanceof Map<?, ?> entry)) {
                continue;
            }
            Object toolCallId = entry.get("toolCallId");
            if (toolCallId == null) {
                toolCallId = entry.get("toolUseId");
            }
            if (toolCallId == null) {
                continue;
            }
            boolean confirmed = toBoolean(entry.get("confirmed"), true);
            Object toolName = entry.get("toolName");
            Object inputObj = entry.get("input");
            Map<String, Object> toolInput =
                    inputObj instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();

            // The tool executor validates a tool call against its raw-args JSON string
            // (ToolUseBlock.getContent()), not its parsed input map, and applying a
            // ConfirmResult fully replaces the stored ToolUseBlock. So we must also carry the
            // args as a JSON string, otherwise the resumed tool call fails schema validation
            // with a null "content". Prefer an explicit client-provided string, else serialize
            // the input map.
            Object rawContent = entry.get("content");
            if (rawContent == null) {
                rawContent = entry.get("argsJson");
            }
            String toolContent =
                    rawContent instanceof String s && !s.isBlank()
                            ? s
                            : serializeToolArgs(toolInput);

            ToolUseBlock toolCall =
                    ToolUseBlock.builder()
                            .id(String.valueOf(toolCallId))
                            .name(toolName != null ? String.valueOf(toolName) : "")
                            .input(toolInput)
                            .content(toolContent)
                            .build();
            results.add(new ConfirmResult(confirmed, toolCall, null));
        }
        return results;
    }

    private static boolean toBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof String s) {
            return Boolean.parseBoolean(s);
        }
        return defaultValue;
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
                new AguiEvent.RunError(threadId, runId, errorMessage, mapErrorCode(error)),
                new AguiEvent.RunFinished(threadId, runId));
    }

    /**
     * Convert an AgentScope event to AG-UI events.
     *
     * @param event The AgentScope event
     * @param state The conversion state
     * @return List of AG-UI events
     */
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
     * Convert a fine-grained v2 {@link AgentEvent} to AG-UI events.
     *
     * <p>Maps the granular streaming events emitted by {@link ReActAgent#streamEvents} onto the
     * AG-UI protocol:
     * <ul>
     *   <li>{@link TextBlockStartEvent}/{@link TextBlockDeltaEvent}/{@link TextBlockEndEvent}
     *       → TEXT_MESSAGE_START / TEXT_MESSAGE_CONTENT / TEXT_MESSAGE_END</li>
     *   <li>{@link ThinkingBlockStartEvent}/{@link ThinkingBlockDeltaEvent}/{@link
     *       ThinkingBlockEndEvent} → REASONING_MESSAGE_* (only when reasoning is enabled)</li>
     *   <li>{@link ToolCallStartEvent}/{@link ToolCallDeltaEvent}/{@link ToolCallEndEvent}
     *       → TOOL_CALL_START / TOOL_CALL_ARGS / TOOL_CALL_END</li>
     *   <li>{@link ToolResultTextDeltaEvent} accumulated and flushed at {@link ToolResultEndEvent}
     *       → TOOL_CALL_RESULT</li>
     * </ul>
     *
     * <p>Agent lifecycle events (AgentStart/Result/End, ModelCall*) are intentionally ignored here:
     * the adapter emits RUN_STARTED / RUN_FINISHED around this stream itself.
     *
     * @param event the v2 agent event
     * @param state the conversion state
     * @return list of AG-UI events
     */
    private List<AguiEvent> convertAgentEvent(AgentEvent event, EventConversionState state) {
        List<AguiEvent> events = new ArrayList<>();

        if (event instanceof TextBlockStartEvent textStart) {
            String messageId = textStart.getBlockId();
            if (!state.hasStartedMessage(messageId)) {
                events.add(
                        new AguiEvent.TextMessageStart(
                                state.threadId, state.runId, messageId, "assistant"));
                state.startMessage(messageId);
            }
        } else if (event instanceof TextBlockDeltaEvent textDelta) {
            String messageId = textDelta.getBlockId();
            String delta = textDelta.getDelta();
            if (delta != null && !delta.isEmpty()) {
                if (!state.hasStartedMessage(messageId)) {
                    events.add(
                            new AguiEvent.TextMessageStart(
                                    state.threadId, state.runId, messageId, "assistant"));
                    state.startMessage(messageId);
                }
                events.add(
                        new AguiEvent.TextMessageContent(
                                state.threadId, state.runId, messageId, delta));
            }
        } else if (event instanceof TextBlockEndEvent textEnd) {
            String messageId = textEnd.getBlockId();
            if (state.hasStartedMessage(messageId) && !state.hasEndedMessage(messageId)) {
                events.add(new AguiEvent.TextMessageEnd(state.threadId, state.runId, messageId));
                state.endMessage(messageId);
            }
        } else if (event instanceof ThinkingBlockStartEvent thinkingStart) {
            if (config.isEnableReasoning()) {
                String messageId = thinkingStart.getBlockId();
                if (!state.hasStartedReasoningMessage(messageId)) {
                    events.add(
                            new AguiEvent.ReasoningMessageStart(
                                    state.threadId, state.runId, messageId, "reasoning"));
                    state.startReasoningMessage(messageId);
                }
            }
        } else if (event instanceof ThinkingBlockDeltaEvent thinkingDelta) {
            if (config.isEnableReasoning()) {
                String messageId = thinkingDelta.getBlockId();
                String delta = thinkingDelta.getDelta();
                if (delta != null && !delta.isEmpty()) {
                    if (!state.hasStartedReasoningMessage(messageId)) {
                        events.add(
                                new AguiEvent.ReasoningMessageStart(
                                        state.threadId, state.runId, messageId, "reasoning"));
                        state.startReasoningMessage(messageId);
                    }
                    events.add(
                            new AguiEvent.ReasoningMessageContent(
                                    state.threadId, state.runId, messageId, delta));
                }
            }
        } else if (event instanceof ThinkingBlockEndEvent thinkingEnd) {
            if (config.isEnableReasoning()) {
                String messageId = thinkingEnd.getBlockId();
                if (state.hasStartedReasoningMessage(messageId)
                        && !state.hasEndedReasoningMessage(messageId)) {
                    events.add(
                            new AguiEvent.ReasoningMessageEnd(
                                    state.threadId, state.runId, messageId));
                    state.endReasoningMessage(messageId);
                }
            }
        } else if (event instanceof ToolCallStartEvent toolStart) {
            // Close any active text / reasoning message before starting a tool call.
            if (state.hasActiveTextMessage()) {
                String activeMessageId = state.getCurrentTextMessageId();
                events.add(
                        new AguiEvent.TextMessageEnd(state.threadId, state.runId, activeMessageId));
                state.endMessage(activeMessageId);
            }
            if (state.hasActiveReasoningMessage()) {
                String activeReasoningMessageId = state.getCurrentReasoningMessageId();
                events.add(
                        new AguiEvent.ReasoningMessageEnd(
                                state.threadId, state.runId, activeReasoningMessageId));
                state.endReasoningMessage(activeReasoningMessageId);
            }

            String toolCallId = toolStart.getToolCallId();
            if (toolCallId == null) {
                toolCallId = UUID.randomUUID().toString();
            }
            if (!state.hasStartedToolCall(toolCallId)) {
                events.add(
                        new AguiEvent.ToolCallStart(
                                state.threadId,
                                state.runId,
                                toolCallId,
                                toolStart.getToolCallName()));
                state.startToolCall(toolCallId);
            }
        } else if (event instanceof ToolCallDeltaEvent toolDelta) {
            if (config.isEmitToolCallArgs()) {
                String toolCallId = toolDelta.getToolCallId();
                String delta = toolDelta.getDelta();
                if (toolCallId != null && delta != null && !delta.isEmpty()) {
                    events.add(
                            new AguiEvent.ToolCallArgs(
                                    state.threadId, state.runId, toolCallId, delta));
                }
            }
        } else if (event instanceof ToolCallEndEvent toolEnd) {
            String toolCallId = toolEnd.getToolCallId();
            if (toolCallId != null && !state.hasEndedToolCall(toolCallId)) {
                if (!state.hasStartedToolCall(toolCallId)) {
                    events.add(
                            new AguiEvent.ToolCallStart(
                                    state.threadId,
                                    state.runId,
                                    toolCallId,
                                    toolEnd.getToolCallName()));
                    state.startToolCall(toolCallId);
                }
                events.add(new AguiEvent.ToolCallEnd(state.threadId, state.runId, toolCallId));
                state.endToolCall(toolCallId);
            }
        } else if (event instanceof ToolResultStartEvent toolResultStart) {
            state.beginToolResult(toolResultStart.getToolCallId());
        } else if (event instanceof ToolResultTextDeltaEvent toolResultDelta) {
            state.appendToolResultText(toolResultDelta.getToolCallId(), toolResultDelta.getDelta());
        } else if (event instanceof ToolResultEndEvent toolResultEnd) {
            String toolCallId = toolResultEnd.getToolCallId();
            if (toolCallId != null) {
                if (!state.hasStartedToolCall(toolCallId)) {
                    String toolName = toolResultEnd.getToolCallName();
                    if (toolName == null || toolName.isBlank()) {
                        toolName = "unknown";
                    }
                    events.add(
                            new AguiEvent.ToolCallStart(
                                    state.threadId, state.runId, toolCallId, toolName));
                    state.startToolCall(toolCallId);
                }
                if (!state.hasEndedToolCall(toolCallId)) {
                    events.add(new AguiEvent.ToolCallEnd(state.threadId, state.runId, toolCallId));
                    state.endToolCall(toolCallId);
                }
                String result = state.takeToolResultText(toolCallId);
                events.add(
                        new AguiEvent.ToolCallResult(
                                state.threadId,
                                state.runId,
                                toolCallId,
                                result,
                                "tool",
                                UUID.randomUUID().toString()));
            }
        } else if (event instanceof RequireUserConfirmEvent confirm) {
            // HITL: the agent paused and is asking the user to confirm these tool calls. Close any
            // dangling text/reasoning message, then record the pending tool calls so finishRun()
            // surfaces them as a RUN_FINISHED interrupt outcome.
            if (state.hasActiveTextMessage()) {
                String activeMessageId = state.getCurrentTextMessageId();
                events.add(
                        new AguiEvent.TextMessageEnd(state.threadId, state.runId, activeMessageId));
                state.endMessage(activeMessageId);
            }
            if (state.hasActiveReasoningMessage()) {
                String activeReasoningMessageId = state.getCurrentReasoningMessageId();
                events.add(
                        new AguiEvent.ReasoningMessageEnd(
                                state.threadId, state.runId, activeReasoningMessageId));
                state.endReasoningMessage(activeReasoningMessageId);
            }
            state.markPausedForConfirmation(confirm.getToolCalls());
        }

        return events;
    }

    /**
     * Finish the run by emitting any pending end events and RUN_FINISHED.
     *
     * <p>When the run paused for human-in-the-loop confirmation (a {@link
     * io.agentscope.core.event.RequireUserConfirmEvent} was observed), the RUN_FINISHED event
     * carries a {@link AguiEvent.RunFinishedInterruptOutcome} describing the pending tool calls the
     * client must confirm, instead of a bare completion.
     *
     * @param state The conversion state
     * @return Flux of final events
     */
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

        // Emit RUN_FINISHED - with an interrupt outcome if the run paused for HITL confirmation.
        if (state.isPausedForConfirmation()) {
            List<AguiEvent.Interrupt> interrupts = new ArrayList<>();
            for (ToolUseBlock pending : state.getPendingConfirmations()) {
                String toolCallId =
                        pending.getId() != null ? pending.getId() : UUID.randomUUID().toString();
                interrupts.add(
                        new AguiEvent.Interrupt(
                                toolCallId,
                                CONFIRM_INTERRUPT_REASON,
                                "Tool call '"
                                        + pending.getName()
                                        + "' requires confirmation before it can run.",
                                toolCallId,
                                null,
                                null,
                                null));
            }
            events.add(
                    new AguiEvent.RunFinished(
                            state.threadId,
                            state.runId,
                            null,
                            new AguiEvent.RunFinishedInterruptOutcome(interrupts)));
        } else {
            events.add(new AguiEvent.RunFinished(state.threadId, state.runId));
        }

        return Flux.fromIterable(events);
    }

    /**
     * Extract text content from a tool result block.
     *
     * @param toolResult The tool result block
     * @return The text content, or null if not present
     */
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
     * Serialize tool arguments to JSON string.
     *
     * @param input The tool input map
     * @return JSON string representation
     */
    private String serializeToolArgs(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return "{}";
        }
        try {
            return JsonUtils.getJsonCodec().toJson(input);
        } catch (JsonException e) {
            return "{}";
        }
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

    private static class ToolInjection {
        private static final ToolInjection EMPTY =
                new ToolInjection(null, Collections.emptyList(), Collections.emptyMap());

        private final Toolkit toolkit;
        private final List<SchemaOnlyTool> registeredTools;
        private final Map<String, AgentTool> previousTools;

        ToolInjection(
                Toolkit toolkit,
                List<SchemaOnlyTool> registeredTools,
                Map<String, AgentTool> previousTools) {
            this.toolkit = toolkit;
            this.registeredTools = registeredTools;
            this.previousTools = previousTools;
        }

        static ToolInjection empty() {
            return EMPTY;
        }

        void close() {
            if (toolkit == null) {
                return;
            }

            for (int i = registeredTools.size() - 1; i >= 0; i--) {
                SchemaOnlyTool tool = registeredTools.get(i);
                toolkit.removeToolIfSame(tool.getName(), tool);
            }

            for (Map.Entry<String, AgentTool> entry : previousTools.entrySet()) {
                if (toolkit.getTool(entry.getKey()) == null) {
                    toolkit.registerAgentTool(entry.getValue());
                }
            }
        }
    }

    /**
     * State tracker for event conversion.
     * Uses LinkedHashSet to preserve insertion order for proper event sequencing.
     */
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
        // Accumulates streamed tool-result text (v2 ToolResultTextDeltaEvent) keyed by toolCallId,
        // flushed into a single TOOL_CALL_RESULT at ToolResultEndEvent.
        private final Map<String, StringBuilder> toolResultBuffers = new LinkedHashMap<>();
        // Pending HITL tool calls captured from RequireUserConfirmEvent; when non-null the run
        // finishes with a RunFinishedInterruptOutcome instead of a bare RUN_FINISHED.
        private List<ToolUseBlock> pendingConfirmations = null;

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

        // ===== Tool-result text buffering (v2) =====

        void beginToolResult(String toolCallId) {
            if (toolCallId != null) {
                toolResultBuffers.computeIfAbsent(toolCallId, k -> new StringBuilder());
            }
        }

        void appendToolResultText(String toolCallId, String delta) {
            if (toolCallId == null || delta == null || delta.isEmpty()) {
                return;
            }
            toolResultBuffers.computeIfAbsent(toolCallId, k -> new StringBuilder()).append(delta);
        }

        /**
         * Return the accumulated tool-result text for the given tool call and clear the buffer.
         *
         * @return the buffered text, or {@code null} if nothing was accumulated
         */
        String takeToolResultText(String toolCallId) {
            StringBuilder sb = toolResultBuffers.remove(toolCallId);
            if (sb == null || sb.length() == 0) {
                return null;
            }
            return sb.toString();
        }

        // ===== HITL confirmation tracking =====

        void markPausedForConfirmation(List<ToolUseBlock> toolCalls) {
            this.pendingConfirmations =
                    toolCalls != null ? List.copyOf(toolCalls) : Collections.emptyList();
        }

        boolean isPausedForConfirmation() {
            return pendingConfirmations != null;
        }

        List<ToolUseBlock> getPendingConfirmations() {
            return pendingConfirmations != null ? pendingConfirmations : Collections.emptyList();
        }
    }
}
