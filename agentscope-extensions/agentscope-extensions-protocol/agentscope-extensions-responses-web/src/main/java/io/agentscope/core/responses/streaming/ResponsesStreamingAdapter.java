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
package io.agentscope.core.responses.streaming;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.responses.builder.ResponsesResponseBuilder;
import io.agentscope.core.responses.model.ResponsesContentPart;
import io.agentscope.core.responses.model.ResponsesError;
import io.agentscope.core.responses.model.ResponsesOutputItem;
import io.agentscope.core.responses.model.ResponsesRequest;
import io.agentscope.core.responses.model.ResponsesResponse;
import io.agentscope.core.responses.model.ResponsesStreamEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import reactor.core.publisher.Flux;

/**
 * Converts AgentScope streaming events to Responses API streaming events.
 *
 * <p>This adapter is framework-agnostic. It owns the Responses API event choreography ({@code
 * response.created}, {@code response.in_progress}, output item events, text deltas, function-call
 * argument events, and the terminal {@code response.completed} or {@code response.failed} event),
 * while Spring-specific SSE serialization lives in the starter module.
 *
 * <p>When JSON Schema structured output is requested, the adapter calls the agent's structured
 * streaming path and emits the final structured payload as standard Responses output text events.
 */
public class ResponsesStreamingAdapter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final ResponsesResponseBuilder responseBuilder;

    /** Constructs a streaming adapter with the default response builder. */
    public ResponsesStreamingAdapter() {
        this(new ResponsesResponseBuilder());
    }

    /**
     * Constructs a streaming adapter.
     *
     * @param responseBuilder Builder used for terminal response payloads
     */
    public ResponsesStreamingAdapter(ResponsesResponseBuilder responseBuilder) {
        this.responseBuilder = responseBuilder;
    }

    /**
     * Stream a normal text/tool Responses request.
     *
     * @param agent The agent to stream from
     * @param messages Converted AgentScope messages
     * @param request Original Responses request
     * @param responseId Response ID shared by all stream events
     * @return Responses API stream events
     */
    public Flux<ResponsesStreamEvent> stream(
            ReActAgent agent, List<Msg> messages, ResponsesRequest request, String responseId) {
        return stream(agent, messages, null, request, responseId);
    }

    /**
     * Stream a Responses request, optionally using JSON Schema structured output.
     *
     * <p>For regular streaming, incremental reasoning events become text deltas and tool-use blocks
     * become function-call events. For structured streaming, the agent returns a final structured
     * result and this adapter emits it as a compact JSON text delta followed by the standard done
     * and completed events.
     *
     * @param agent The agent to stream from
     * @param messages Converted AgentScope messages
     * @param structuredOutputSchema Optional JSON Schema for structured output
     * @param request Original Responses request
     * @param responseId Response ID shared by all stream events
     * @return Responses API stream events
     */
    public Flux<ResponsesStreamEvent> stream(
            ReActAgent agent,
            List<Msg> messages,
            JsonNode structuredOutputSchema,
            ResponsesRequest request,
            String responseId) {
        return Flux.defer(
                () -> createStream(agent, messages, structuredOutputSchema, request, responseId));
    }

    private Flux<ResponsesStreamEvent> createStream(
            ReActAgent agent,
            List<Msg> messages,
            JsonNode structuredOutputSchema,
            ResponsesRequest request,
            String responseId) {
        boolean structuredStream = structuredOutputSchema != null;
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(streamEventTypes(structuredStream))
                        .incremental(true)
                        .build();

        ResponsesResponse created = responseBuilder.baseResponse(request, responseId, "created");
        ResponsesResponse inProgress =
                responseBuilder.baseResponse(request, responseId, "in_progress");

        StreamingState state = new StreamingState(messageId(responseId));

        // AgentScope incremental streams often include a final accumulated REASONING event. Track
        // whether we saw true deltas so the final accumulated text is not duplicated.
        Flux<ResponsesStreamEvent> body =
                agentStream(agent, messages, options, structuredOutputSchema)
                        .filter(event -> event.getMessage() != null)
                        .doOnNext(
                                event -> {
                                    if (event.getType() == EventType.REASONING
                                            && !event.isLast()
                                            && !text(event.getMessage()).isEmpty()) {
                                        state.hasSeenIncrementalReasoning = true;
                                    }
                                })
                        .concatMap(event -> convertEvent(event, state, structuredStream));

        Flux<ResponsesStreamEvent> completion =
                Flux.defer(() -> Flux.fromIterable(completionEvents(request, responseId, state)));

        Flux<ResponsesStreamEvent> events =
                Flux.concat(
                                Flux.just(
                                        ResponsesStreamEvent.responseEvent(
                                                "response.created", created),
                                        ResponsesStreamEvent.responseEvent(
                                                "response.in_progress", inProgress)),
                                body,
                                completion)
                        .onErrorResume(
                                error -> Flux.just(createFailedEvent(error, request, responseId)));

        return withStreamMetadata(events, responseId);
    }

    /**
     * Create a Responses {@code response.failed} event for errors that happen after streaming
     * begins.
     *
     * @param error Runtime error
     * @param request Original Responses request
     * @param responseId Response ID shared by the stream
     * @return Failure stream event
     */
    public ResponsesStreamEvent createFailedEvent(
            Throwable error, ResponsesRequest request, String responseId) {
        String message = error != null ? error.getMessage() : "Unknown error occurred";
        ResponsesResponse failed =
                responseBuilder.buildFailedResponse(
                        request,
                        ResponsesError.invalidRequest(message, null, "runtime_error"),
                        responseId);
        return ResponsesStreamEvent.responseEvent("response.failed", failed);
    }

    private Flux<ResponsesStreamEvent> convertEvent(
            Event event, StreamingState state, boolean structuredStream) {
        Msg msg = event.getMessage();
        if (msg == null) {
            return Flux.empty();
        }
        if (event.isLast()) {
            state.terminalMessage = msg;
        }

        if (structuredStream && event.getType() == EventType.AGENT_RESULT) {
            // Structured output streaming does not expose partial schema objects from AgentScope;
            // emit the final structured payload as a single output_text delta.
            return Flux.fromIterable(structuredOutputEvents(msg, state));
        }

        if (msg.getContent() == null) {
            return Flux.empty();
        }

        List<ResponsesStreamEvent> events = new ArrayList<>();
        appendTextEvents(event, msg, state, events);
        appendToolUseEvents(event, msg, state, events);
        appendTextCompletionEvents(event, msg, state, events);
        return Flux.fromIterable(events);
    }

    private List<ResponsesStreamEvent> completionEvents(
            ResponsesRequest request, String responseId, StreamingState state) {
        List<ResponsesStreamEvent> events = new ArrayList<>();
        // If the model ended without an explicit terminal text event, close the open content part
        // before sending response.completed.
        if (state.hasTextItem && !state.hasTextDone) {
            events.addAll(completeTextEvents(state));
        }
        ResponsesResponse completed =
                responseBuilder.buildStreamingCompletedResponse(
                        request,
                        responseId,
                        new ArrayList<>(state.completedOutput.values()),
                        state.accumulatedText.toString(),
                        state.terminalMessage);
        events.add(ResponsesStreamEvent.responseEvent("response.completed", completed));
        return events;
    }

    private void appendTextEvents(
            Event event, Msg msg, StreamingState state, List<ResponsesStreamEvent> events) {
        boolean includeText =
                !(event.getType() == EventType.REASONING
                        && event.isLast()
                        && state.hasSeenIncrementalReasoning);
        if (includeText) {
            appendTextDelta(text(msg), state, events);
        }
    }

    private void appendTextDelta(
            String text, StreamingState state, List<ResponsesStreamEvent> events) {
        if (!text.isEmpty()) {
            if (!state.hasTextItem) {
                state.hasTextItem = true;
                // Responses streams must announce output item and content part creation before
                // emitting text deltas for that item.
                int index = state.nextOutputIndex++;
                state.textOutputIndex = index;
                ResponsesOutputItem item =
                        ResponsesOutputItem.message(state.messageId, "", "in_progress");
                events.add(
                        ResponsesStreamEvent.outputItemEvent(
                                "response.output_item.added", index, item));
                events.add(
                        ResponsesStreamEvent.contentPartEvent(
                                "response.content_part.added",
                                index,
                                0,
                                state.messageId,
                                ResponsesContentPart.outputText("")));
            }
            state.accumulatedText.append(text);
            events.add(
                    ResponsesStreamEvent.textDelta(
                            "response.output_text.delta",
                            state.textOutputIndex,
                            0,
                            state.messageId,
                            text));
        }
    }

    private void appendToolUseEvents(
            Event event, Msg msg, StreamingState state, List<ResponsesStreamEvent> events) {
        if (event.getType() != EventType.REASONING) {
            return;
        }
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ToolUseBlock toolUseBlock) {
                // Tool-use blocks become Responses function_call output items. The actual Java tool
                // execution is performed by the client or by AgentScope toolkit code.
                int index = state.nextOutputIndex++;
                String itemId = functionCallId(toolUseBlock.getId());
                String arguments = argumentsJson(toolUseBlock);
                ResponsesOutputItem addedItem =
                        ResponsesOutputItem.functionCall(
                                itemId,
                                toolUseBlock.getId(),
                                toolUseBlock.getName(),
                                "",
                                "in_progress");
                ResponsesOutputItem completedItem =
                        ResponsesOutputItem.functionCall(
                                itemId, toolUseBlock.getId(), toolUseBlock.getName(), arguments);
                events.add(
                        ResponsesStreamEvent.outputItemEvent(
                                "response.output_item.added", index, addedItem));
                if (!arguments.isEmpty()) {
                    events.add(ResponsesStreamEvent.argumentsDelta(index, itemId, arguments));
                }
                ResponsesStreamEvent argumentsDone =
                        ResponsesStreamEvent.argumentsDone(index, itemId, arguments);
                argumentsDone.setCallId(toolUseBlock.getId());
                argumentsDone.setName(toolUseBlock.getName());
                events.add(argumentsDone);
                events.add(
                        ResponsesStreamEvent.outputItemEvent(
                                "response.output_item.done", index, completedItem));
                state.completedOutput.put(index, completedItem);
            }
        }
    }

    private void appendTextCompletionEvents(
            Event event, Msg msg, StreamingState state, List<ResponsesStreamEvent> events) {
        if (event.isLast()
                && state.hasTextItem
                && !state.hasTextDone
                && msg.getGenerateReason() != GenerateReason.TOOL_SUSPENDED) {
            events.addAll(completeTextEvents(state));
        }
    }

    private EventType[] streamEventTypes(boolean structuredStream) {
        return structuredStream
                ? new EventType[] {EventType.AGENT_RESULT}
                : new EventType[] {EventType.REASONING, EventType.TOOL_RESULT};
    }

    private Flux<Event> agentStream(
            ReActAgent agent,
            List<Msg> messages,
            StreamOptions options,
            JsonNode structuredOutputSchema) {
        return structuredOutputSchema != null
                ? agent.stream(messages, options, structuredOutputSchema)
                : agent.stream(messages, options);
    }

    private List<ResponsesStreamEvent> structuredOutputEvents(Msg msg, StreamingState state) {
        String text = structuredOutputText(msg);
        if (text.isEmpty()) {
            return List.of();
        }

        List<ResponsesStreamEvent> events = new ArrayList<>();
        appendTextDelta(text, state, events);
        events.addAll(completeTextEvents(state));
        return events;
    }

    private String structuredOutputText(Msg msg) {
        if (msg == null || !msg.hasStructuredData()) {
            throw new IllegalStateException(
                    "Structured output was requested but no structured data was returned");
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(msg.getStructuredData(false));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize structured output", e);
        }
    }

    private List<ResponsesStreamEvent> completeTextEvents(StreamingState state) {
        if (state.textOutputIndex < 0 || state.hasTextDone) {
            return List.of();
        }
        state.hasTextDone = true;

        String text = state.accumulatedText.toString();
        ResponsesOutputItem item = ResponsesOutputItem.message(state.messageId, text);
        state.completedOutput.put(state.textOutputIndex, item);

        List<ResponsesStreamEvent> events = new ArrayList<>();
        events.add(
                ResponsesStreamEvent.textDone(
                        "response.output_text.done",
                        state.textOutputIndex,
                        0,
                        state.messageId,
                        text));
        events.add(
                ResponsesStreamEvent.contentPartEvent(
                        "response.content_part.done",
                        state.textOutputIndex,
                        0,
                        state.messageId,
                        ResponsesContentPart.outputText(text)));
        events.add(
                ResponsesStreamEvent.outputItemEvent(
                        "response.output_item.done", state.textOutputIndex, item));
        return events;
    }

    private static final class StreamingState {

        private final String messageId;
        private int nextOutputIndex;
        private int textOutputIndex = -1;
        private boolean hasTextItem;
        private boolean hasTextDone;
        private boolean hasSeenIncrementalReasoning;
        private final StringBuilder accumulatedText = new StringBuilder();
        private final Map<Integer, ResponsesOutputItem> completedOutput = new TreeMap<>();
        private Msg terminalMessage;

        private StreamingState(String messageId) {
            this.messageId = messageId;
        }
    }

    private Flux<ResponsesStreamEvent> withStreamMetadata(
            Flux<ResponsesStreamEvent> events, String responseId) {
        return events.index()
                .map(
                        tuple -> {
                            ResponsesStreamEvent event = tuple.getT2();
                            event.setSequenceNumber(tuple.getT1() + 1);
                            if (event.getResponse() == null) {
                                event.setResponseId(responseId);
                            }
                            return event;
                        });
    }

    private String text(Msg msg) {
        if (msg == null || msg.getContent() == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof TextBlock textBlock && textBlock.getText() != null) {
                builder.append(textBlock.getText());
            }
        }
        return builder.toString();
    }

    private String argumentsJson(ToolUseBlock block) {
        if (block.getContent() != null && !block.getContent().isBlank()) {
            return compactJson(block.getContent());
        }
        Map<String, Object> input = block.getInput();
        if (input == null || input.isEmpty()) {
            return "{}";
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(input);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String compactJson(String json) {
        try {
            return OBJECT_MAPPER.writeValueAsString(OBJECT_MAPPER.readTree(json));
        } catch (Exception e) {
            return json;
        }
    }

    private String functionCallId(String seed) {
        return seed != null && seed.startsWith("fc_") ? seed : "fc_" + normalize(seed);
    }

    private String messageId(String seed) {
        if (seed != null && seed.startsWith("resp_")) {
            return "msg_" + seed.substring("resp_".length());
        }
        return seed != null && seed.startsWith("msg_") ? seed : "msg_" + normalize(seed);
    }

    private String normalize(String seed) {
        return seed == null || seed.isBlank() ? UUID.randomUUID().toString() : seed;
    }
}
