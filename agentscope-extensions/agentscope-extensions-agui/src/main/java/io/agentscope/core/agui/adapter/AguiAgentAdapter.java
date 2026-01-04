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

import com.fasterxml.jackson.core.JsonProcessingException;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.agui.AguiObjectMapper;
import io.agentscope.core.agui.converter.AguiMessageConverter;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
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
 *   <li>AgentScope REASONING events → AG-UI TEXT_MESSAGE_* events</li>
 *   <li>AgentScope TOOL_RESULT events → AG-UI TOOL_CALL_END events</li>
 *   <li>ToolUseBlock content → AG-UI TOOL_CALL_START events</li>
 * </ul>
 */
public class AguiAgentAdapter {

    private final Agent agent;
    private final AguiAdapterConfig config;
    private final AguiMessageConverter messageConverter;

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
        String threadId = input.getThreadId();
        String runId = input.getRunId();

        // Convert AG-UI messages to AgentScope messages
        List<Msg> msgs = messageConverter.toMsgList(input.getMessages());

        // Create stream options - use incremental mode for true streaming
        StreamOptions options =
                StreamOptions.builder().eventTypes(EventType.ALL).incremental(true).build();

        // Track state for event conversion
        EventConversionState state = new EventConversionState(threadId, runId);

        return Flux.concat(
                        // Emit RUN_STARTED
                        Flux.just(new AguiEvent.RunStarted(threadId, runId)),
                        // Stream agent events and convert to AG-UI events
                        // Use concatMapIterable to preserve strict event ordering
                        agent.stream(msgs, options)
                                .concatMapIterable(event -> convertEvent(event, state)),
                        // Emit any pending end events and RUN_FINISHED
                        Flux.defer(() -> finishRun(state)))
                .onErrorResume(
                        error -> {
                            // On error, emit RawEvent with error info followed by RunFinished
                            String errorMessage =
                                    error.getMessage() != null
                                            ? error.getMessage()
                                            : error.getClass().getSimpleName();
                            return Flux.just(
                                    new AguiEvent.Raw(
                                            threadId, runId, Map.of("error", errorMessage)),
                                    new AguiEvent.RunFinished(threadId, runId));
                        });
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

        if (type == EventType.REASONING) {
            // Handle reasoning events - convert to text messages and tool calls
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
                            events.add(
                                    new AguiEvent.TextMessageEnd(
                                            state.threadId, state.runId, messageId));
                            state.endMessage(messageId);
                        }
                    }
                } else if (block instanceof ToolUseBlock toolUse) {
                    // End any active text message before starting tool call
                    if (state.hasActiveTextMessage()) {
                        String activeMessageId = state.getCurrentTextMessageId();
                        events.add(
                                new AguiEvent.TextMessageEnd(
                                        state.threadId, state.runId, activeMessageId));
                        state.endMessage(activeMessageId);
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

                        // Emit tool call args if enabled
                        if (config.isEmitToolCallArgs()) {
                            String argsJson = serializeToolArgs(toolUse.getInput());
                            events.add(
                                    new AguiEvent.ToolCallArgs(
                                            state.threadId, state.runId, toolCallId, argsJson));
                        }
                    }
                }
            }
        } else if (type == EventType.TOOL_RESULT) {
            // Handle tool results
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ToolResultBlock toolResult) {
                    String toolCallId = toolResult.getId();
                    String result = extractToolResultText(toolResult);

                    events.add(
                            new AguiEvent.ToolCallEnd(
                                    state.threadId, state.runId, toolCallId, result));
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
                events.add(
                        new AguiEvent.ToolCallEnd(state.threadId, state.runId, toolCallId, null));
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
     */
    private String extractToolResultText(ToolResultBlock toolResult) {
        if (toolResult.getOutput() == null || toolResult.getOutput().isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        for (ContentBlock output : toolResult.getOutput()) {
            if (output instanceof TextBlock textBlock) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(textBlock.getText());
            }
        }

        return sb.length() > 0 ? sb.toString() : null;
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
            return AguiObjectMapper.get().writeValueAsString(input);
        } catch (JsonProcessingException e) {
            return "{}";
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
        private String currentTextMessageId = null;

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
            if (messageId.equals(currentTextMessageId)) {
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
    }
}
