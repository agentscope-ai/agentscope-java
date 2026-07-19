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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.converter.AguiMessageConverter;
import io.agentscope.core.agui.converter.AguiToolConverter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.model.ToolMergeMode;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.TextBlockEndEvent;
import io.agentscope.core.event.TextBlockStartEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockEndEvent;
import io.agentscope.core.event.ThinkingBlockStartEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultDataDeltaEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultStartEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
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
 * invokes the agent via the 2.0 fine-grained event stream {@link
 * ReActAgent#streamEvents(List, RuntimeContext)}, and converts the resulting
 * {@link AgentEvent}s back to AG-UI events.
 *
 * <p><b>Event Mapping:</b>
 * <ul>
 *   <li>TextBlockStart/Delta/EndEvent → AG-UI TEXT_MESSAGE_START/CONTENT/END events</li>
 *   <li>ThinkingBlockStart/Delta/EndEvent → AG-UI REASONING_MESSAGE_START/CONTENT/END events
 *       (when reasoning is enabled)</li>
 *   <li>ToolCallStart/Delta/EndEvent → AG-UI TOOL_CALL_START/ARGS/END events</li>
 *   <li>ToolResultStart/TextDelta/DataDelta/EndEvent → buffered AG-UI TOOL_CALL_RESULT event
 *       (with a defensive TOOL_CALL_START/END when needed)</li>
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

    private final ReActAgent agent;
    private final AguiAdapterConfig config;
    private final AguiMessageConverter messageConverter;
    private final AguiToolConverter toolConverter;

    /**
     * Creates a new AguiAgentAdapter.
     *
     * @param agent The agent to adapt (must be a {@link ReActAgent} exposing the 2.0 event stream)
     * @param config The adapter configuration
     */
    public AguiAgentAdapter(ReActAgent agent, AguiAdapterConfig config) {
        this.agent = Objects.requireNonNull(agent, "agent cannot be null");
        this.config = Objects.requireNonNull(config, "config cannot be null");
        this.messageConverter = new AguiMessageConverter();
        this.toolConverter = new AguiToolConverter();
    }

    /**
     * Run the agent with AG-UI protocol input.
     *
     * <p>This method converts the input messages, invokes the agent's fine-grained event stream
     * API, and emits AG-UI protocol events.
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

                    // Track state for event conversion
                    EventConversionState state = new EventConversionState(threadId, runId);
                    RuntimeContext runtimeContext = buildRuntimeContext(input);
                    ToolInjection toolInjection = ToolInjection.empty();
                    Flux<AgentEvent> agentEvents;
                    try {
                        toolInjection = injectFrontendTools(input);
                        agentEvents =
                                Objects.requireNonNull(
                                        agent.streamEvents(msgs, runtimeContext),
                                        "agent stream is null");
                    } catch (Throwable error) {
                        toolInjection.close();
                        return Flux.concat(
                                Flux.just(new AguiEvent.RunStarted(threadId, runId, null, input)),
                                errorEvents(threadId, runId, error));
                    }

                    ToolInjection activeToolInjection = toolInjection;

                    return Flux.concat(
                                    // Emit RUN_STARTED
                                    Flux.just(
                                            new AguiEvent.RunStarted(threadId, runId, null, input)),
                                    // Stream agent events and convert to AG-UI events
                                    // Use concatMapIterable to preserve strict event ordering
                                    agentEvents.concatMapIterable(
                                            event -> convertAgentEvent(event, state)),
                                    // Emit any pending end events and RUN_FINISHED
                                    Flux.defer(() -> finishRun(state)))
                            .doFinally(signalType -> activeToolInjection.close())
                            .onErrorResume(error -> errorEvents(threadId, runId, error));
                });
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
     * Convert a 2.0 AgentEvent to AG-UI events.
     *
     * <p>The fine-grained event stream already carries start/delta/end markers, so this method
     * maps each event type directly without relying on an {@code isLast} flag.
     *
     * @param event The AgentScope event
     * @param state The conversion state
     * @return List of AG-UI events
     */
    private List<AguiEvent> convertAgentEvent(AgentEvent event, EventConversionState state) {
        List<AguiEvent> events = new ArrayList<>();
        String tid = state.threadId;
        String rid = state.runId;

        if (event instanceof TextBlockStartEvent e) {
            String messageId = e.getReplyId();
            if (!state.hasStartedMessage(messageId)) {
                events.add(new AguiEvent.TextMessageStart(tid, rid, messageId, "assistant"));
                state.startMessage(messageId);
            }
        } else if (event instanceof TextBlockDeltaEvent e) {
            String delta = e.getDelta();
            if (delta != null && !delta.isEmpty()) {
                events.add(new AguiEvent.TextMessageContent(tid, rid, e.getReplyId(), delta));
            }
        } else if (event instanceof TextBlockEndEvent e) {
            String messageId = e.getReplyId();
            if (!state.hasEndedMessage(messageId)) {
                events.add(new AguiEvent.TextMessageEnd(tid, rid, messageId));
                state.endMessage(messageId);
            }
        } else if (event instanceof ThinkingBlockStartEvent e) {
            if (config.isEnableReasoning()) {
                String messageId = e.getReplyId();
                if (!state.hasStartedReasoningMessage(messageId)) {
                    events.add(
                            new AguiEvent.ReasoningMessageStart(tid, rid, messageId, "reasoning"));
                    state.startReasoningMessage(messageId);
                }
            }
        } else if (event instanceof ThinkingBlockDeltaEvent e) {
            if (config.isEnableReasoning()) {
                String delta = e.getDelta();
                if (delta != null && !delta.isEmpty()) {
                    events.add(
                            new AguiEvent.ReasoningMessageContent(tid, rid, e.getReplyId(), delta));
                }
            }
        } else if (event instanceof ThinkingBlockEndEvent e) {
            if (config.isEnableReasoning()) {
                String messageId = e.getReplyId();
                if (!state.hasEndedReasoningMessage(messageId)) {
                    events.add(new AguiEvent.ReasoningMessageEnd(tid, rid, messageId));
                    state.endReasoningMessage(messageId);
                }
            }
        } else if (event instanceof ToolCallStartEvent e) {
            // Close any active text/reasoning message before starting a tool call
            closeActiveMessages(state, events);
            String toolCallId = e.getToolCallId();
            if (toolCallId == null) {
                toolCallId = UUID.randomUUID().toString();
            }
            if (!state.hasStartedToolCall(toolCallId)) {
                events.add(new AguiEvent.ToolCallStart(tid, rid, toolCallId, e.getToolCallName()));
                state.startToolCall(toolCallId);
            }
        } else if (event instanceof ToolCallDeltaEvent e) {
            if (config.isEmitToolCallArgs()) {
                String delta = e.getDelta();
                if (delta != null && !delta.isEmpty()) {
                    events.add(new AguiEvent.ToolCallArgs(tid, rid, e.getToolCallId(), delta));
                }
            }
        } else if (event instanceof ToolCallEndEvent e) {
            String toolCallId = e.getToolCallId();
            if (!state.hasEndedToolCall(toolCallId)) {
                events.add(new AguiEvent.ToolCallEnd(tid, rid, toolCallId));
                state.endToolCall(toolCallId);
            }
        } else if (event instanceof ToolResultStartEvent e) {
            // Close any active text/reasoning message before the tool result phase
            closeActiveMessages(state, events);
            String toolCallId = e.getToolCallId();
            if (!state.hasStartedToolCall(toolCallId)) {
                String toolName = e.getToolCallName();
                if (toolName == null || toolName.isBlank()) {
                    toolName = "unknown";
                }
                events.add(new AguiEvent.ToolCallStart(tid, rid, toolCallId, toolName));
                state.startToolCall(toolCallId);
            }
            state.getToolResultBuffer(toolCallId);
        } else if (event instanceof ToolResultTextDeltaEvent e) {
            String delta = e.getDelta();
            if (delta != null && !delta.isEmpty()) {
                state.appendToolResult(e.getToolCallId(), delta);
            }
        } else if (event instanceof ToolResultDataDeltaEvent e) {
            String text = serializeContentBlock(e.getData());
            if (text != null && !text.isEmpty()) {
                state.appendToolResult(e.getToolCallId(), text);
            }
        } else if (event instanceof ToolResultEndEvent e) {
            String toolCallId = e.getToolCallId();
            // Ensure ToolCallEnd is emitted to close the arguments phase
            if (!state.hasEndedToolCall(toolCallId)) {
                events.add(new AguiEvent.ToolCallEnd(tid, rid, toolCallId));
                state.endToolCall(toolCallId);
            }
            String result = state.consumeToolResult(toolCallId);
            events.add(
                    new AguiEvent.ToolCallResult(
                            tid, rid, toolCallId, result, "tool", e.getReplyId()));
        }
        // AgentStartEvent/AgentEndEvent/AgentResultEvent/ModelCallStart/End/Custom/SubagentExposed
        // and other lifecycle events are intentionally ignored: the adapter emits RUN_STARTED /
        // RUN_FINISHED itself.

        return events;
    }

    /**
     * Close any still-active text or reasoning message, ensuring AG-UI ordering constraints are
     * respected before a tool call or tool result phase begins.
     */
    private void closeActiveMessages(EventConversionState state, List<AguiEvent> events) {
        if (state.hasActiveTextMessage()) {
            String messageId = state.getCurrentTextMessageId();
            events.add(new AguiEvent.TextMessageEnd(state.threadId, state.runId, messageId));
            state.endMessage(messageId);
        }
        if (state.hasActiveReasoningMessage()) {
            String messageId = state.getCurrentReasoningMessageId();
            events.add(new AguiEvent.ReasoningMessageEnd(state.threadId, state.runId, messageId));
            state.endReasoningMessage(messageId);
        }
    }

    /**
     * Finish the run by emitting any pending end events and RUN_FINISHED.
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

        // End any reasoning messages that weren't properly ended
        for (String messageId : state.getStartedReasoningMessages()) {
            if (!state.hasEndedReasoningMessage(messageId)) {
                events.add(
                        new AguiEvent.ReasoningMessageEnd(state.threadId, state.runId, messageId));
            }
        }

        // End any tool calls that weren't properly ended
        for (String toolCallId : state.getStartedToolCalls()) {
            if (!state.hasEndedToolCall(toolCallId)) {
                events.add(new AguiEvent.ToolCallEnd(state.threadId, state.runId, toolCallId));
            }
        }

        // Emit RUN_FINISHED
        events.add(new AguiEvent.RunFinished(state.threadId, state.runId));

        return Flux.fromIterable(events);
    }

    /**
     * Serialize a content block to a textual representation for tool result emission.
     *
     * @param data The content block
     * @return The text content, or null if not present
     */
    private String serializeContentBlock(ContentBlock data) {
        if (data == null) {
            return null;
        }
        if (data instanceof TextBlock textBlock) {
            return textBlock.getText();
        }
        try {
            return JsonUtils.getJsonCodec().toJson(data);
        } catch (JsonException e) {
            return null;
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
        private final Map<String, StringBuilder> toolResultBuffers = new HashMap<>();
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

        /**
         * Returns the (lazily created) string buffer accumulating tool result text for the given
         * tool call id.
         */
        StringBuilder getToolResultBuffer(String toolCallId) {
            return toolResultBuffers.computeIfAbsent(toolCallId, k -> new StringBuilder());
        }

        /** Appends text to the tool result buffer for the given tool call id. */
        void appendToolResult(String toolCallId, String text) {
            getToolResultBuffer(toolCallId).append(text);
        }

        /**
         * Returns the accumulated tool result text for the given tool call id and removes the
         * buffer. Returns {@code null} if no text was accumulated.
         */
        String consumeToolResult(String toolCallId) {
            StringBuilder buffer = toolResultBuffers.remove(toolCallId);
            if (buffer == null || buffer.isEmpty()) {
                return null;
            }
            return buffer.toString();
        }
    }
}
