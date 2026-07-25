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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallEndEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.responses.builder.ResponsesResponseBuilder;
import io.agentscope.core.responses.model.ResponsesContentPart;
import io.agentscope.core.responses.model.ResponsesError;
import io.agentscope.core.responses.model.ResponsesOutputItem;
import io.agentscope.core.responses.model.ResponsesRequest;
import io.agentscope.core.responses.model.ResponsesResponse;
import io.agentscope.core.responses.model.ResponsesStreamEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
        return stream(agent, messages, null, request, responseId, null);
    }

    /** Stream a normal text/tool request with invocation-local runtime context. */
    public Flux<ResponsesStreamEvent> stream(
            ReActAgent agent,
            List<Msg> messages,
            ResponsesRequest request,
            String responseId,
            RuntimeContext runtimeContext) {
        return stream(agent, messages, null, request, responseId, runtimeContext);
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
        return stream(agent, messages, structuredOutputSchema, request, responseId, null);
    }

    /** Stream a request with invocation-local runtime context. */
    public Flux<ResponsesStreamEvent> stream(
            ReActAgent agent,
            List<Msg> messages,
            JsonNode structuredOutputSchema,
            ResponsesRequest request,
            String responseId,
            RuntimeContext runtimeContext) {
        return Flux.defer(
                () ->
                        createStream(
                                agent,
                                messages,
                                structuredOutputSchema,
                                request,
                                responseId,
                                runtimeContext));
    }

    private Flux<ResponsesStreamEvent> createStream(
            ReActAgent agent,
            List<Msg> messages,
            JsonNode structuredOutputSchema,
            ResponsesRequest request,
            String responseId,
            RuntimeContext runtimeContext) {
        boolean structuredStream = structuredOutputSchema != null;

        ResponsesResponse created = responseBuilder.baseResponse(request, responseId, "created");
        ResponsesResponse inProgress =
                responseBuilder.baseResponse(request, responseId, "in_progress");

        StreamingState state = new StreamingState(messageId(responseId));

        Flux<ResponsesStreamEvent> body =
                agentStream(agent, messages, structuredOutputSchema, runtimeContext)
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
            AgentEvent event, StreamingState state, boolean structuredStream) {
        if (event instanceof AgentResultEvent resultEvent) {
            state.terminalMessage = resultEvent.getResult();
            if (structuredStream) {
                return Flux.fromIterable(structuredOutputEvents(resultEvent.getResult(), state));
            }
            List<ResponsesStreamEvent> events = new ArrayList<>();
            reconcileToolCalls(resultEvent.getResult(), state, events);
            for (StreamingToolCall toolCall : state.toolCalls.values()) {
                completeToolCall(toolCall, state, events);
            }
            return Flux.fromIterable(events);
        }
        if (structuredStream) {
            return Flux.empty();
        }

        List<ResponsesStreamEvent> events = new ArrayList<>();
        if (event instanceof TextBlockDeltaEvent textDeltaEvent) {
            appendTextDelta(textDeltaEvent.getDelta(), state, events);
        } else if (event instanceof ToolCallStartEvent toolCallStartEvent) {
            ensureToolCall(
                    toolCallStartEvent.getToolCallId(),
                    toolCallStartEvent.getToolCallName(),
                    state,
                    events);
        } else if (event instanceof ToolCallDeltaEvent toolCallDeltaEvent) {
            appendToolCallDelta(toolCallDeltaEvent, state, events);
        } else if (event instanceof ToolCallEndEvent toolCallEndEvent) {
            appendToolCallEnd(toolCallEndEvent, state, events);
        }
        return Flux.fromIterable(events);
    }

    private List<ResponsesStreamEvent> completionEvents(
            ResponsesRequest request, String responseId, StreamingState state) {
        List<ResponsesStreamEvent> events = new ArrayList<>();
        for (StreamingToolCall toolCall : state.toolCalls.values()) {
            completeToolCall(toolCall, state, events);
        }
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

    private void ensureTextItem(StreamingState state, List<ResponsesStreamEvent> events) {
        if (state.hasTextItem) {
            return;
        }
        state.hasTextItem = true;
        int index = state.nextOutputIndex++;
        state.textOutputIndex = index;
        ResponsesOutputItem item = ResponsesOutputItem.message(state.messageId, "", "in_progress");
        events.add(ResponsesStreamEvent.outputItemEvent("response.output_item.added", index, item));
        events.add(
                ResponsesStreamEvent.contentPartEvent(
                        "response.content_part.added",
                        index,
                        0,
                        state.messageId,
                        ResponsesContentPart.outputText("")));
    }

    private void appendTextDelta(
            String text, StreamingState state, List<ResponsesStreamEvent> events) {
        if (text == null || text.isEmpty()) {
            return;
        }
        ensureTextItem(state, events);
        state.accumulatedText.append(text);
        events.add(
                ResponsesStreamEvent.textDelta(
                        "response.output_text.delta",
                        state.textOutputIndex,
                        0,
                        state.messageId,
                        text));
    }

    private void appendToolCallDelta(
            ToolCallDeltaEvent event, StreamingState state, List<ResponsesStreamEvent> events) {
        StreamingToolCall toolCall =
                ensureToolCall(event.getToolCallId(), event.getToolCallName(), state, events);
        String delta = event.getDelta();
        if (toolCall.done || delta == null || delta.isEmpty()) {
            return;
        }
        toolCall.arguments.append(delta);
        events.add(
                ResponsesStreamEvent.argumentsDelta(toolCall.outputIndex, toolCall.itemId, delta));
    }

    private void appendToolCallEnd(
            ToolCallEndEvent event, StreamingState state, List<ResponsesStreamEvent> events) {
        StreamingToolCall toolCall =
                ensureToolCall(event.getToolCallId(), event.getToolCallName(), state, events);
        if (!toolCall.arguments.isEmpty()) {
            completeToolCall(toolCall, state, events);
        }
    }

    private void reconcileToolCalls(
            Msg terminalMessage, StreamingState state, List<ResponsesStreamEvent> events) {
        if (terminalMessage == null) {
            return;
        }
        for (ToolUseBlock block : terminalMessage.getContentBlocks(ToolUseBlock.class)) {
            StreamingToolCall toolCall =
                    ensureToolCall(block.getId(), block.getName(), state, events);
            if (toolCall.done || !toolCall.arguments.isEmpty()) {
                continue;
            }
            String arguments = argumentsJson(block);
            toolCall.arguments.append(arguments);
            events.add(
                    ResponsesStreamEvent.argumentsDelta(
                            toolCall.outputIndex, toolCall.itemId, arguments));
        }
    }

    private StreamingToolCall ensureToolCall(
            String callId, String name, StreamingState state, List<ResponsesStreamEvent> events) {
        String key = callId != null ? callId : "";
        StreamingToolCall existing = state.toolCalls.get(key);
        if (existing != null) {
            return existing;
        }

        String resolvedCallId =
                callId == null || callId.isBlank() ? "call_" + UUID.randomUUID() : callId;
        String resolvedName = name != null ? name : "";
        int index = state.nextOutputIndex++;
        String itemId = functionCallId(resolvedCallId);
        StreamingToolCall toolCall =
                new StreamingToolCall(index, itemId, resolvedCallId, resolvedName);
        state.toolCalls.put(key, toolCall);
        ResponsesOutputItem addedItem =
                ResponsesOutputItem.functionCall(
                        itemId, resolvedCallId, resolvedName, "", "in_progress");
        events.add(
                ResponsesStreamEvent.outputItemEvent(
                        "response.output_item.added", index, addedItem));
        return toolCall;
    }

    private void completeToolCall(
            StreamingToolCall toolCall, StreamingState state, List<ResponsesStreamEvent> events) {
        if (toolCall.done) {
            return;
        }
        toolCall.done = true;
        String arguments = toolCall.arguments.isEmpty() ? "{}" : toolCall.arguments.toString();
        ResponsesOutputItem completedItem =
                ResponsesOutputItem.functionCall(
                        toolCall.itemId, toolCall.callId, toolCall.name, arguments);
        ResponsesStreamEvent argumentsDone =
                ResponsesStreamEvent.argumentsDone(
                        toolCall.outputIndex, toolCall.itemId, arguments);
        argumentsDone.setCallId(toolCall.callId);
        argumentsDone.setName(toolCall.name);
        events.add(argumentsDone);
        events.add(
                ResponsesStreamEvent.outputItemEvent(
                        "response.output_item.done", toolCall.outputIndex, completedItem));
        state.completedOutput.put(toolCall.outputIndex, completedItem);
    }

    private Flux<AgentEvent> agentStream(
            ReActAgent agent,
            List<Msg> messages,
            JsonNode structuredOutputSchema,
            RuntimeContext runtimeContext) {
        if (structuredOutputSchema != null) {
            return agent.streamEvents(messages, structuredOutputSchema, runtimeContext);
        }
        return runtimeContext != null
                ? agent.streamEvents(messages, runtimeContext)
                : agent.streamEvents(messages);
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
        private final StringBuilder accumulatedText = new StringBuilder();
        private final Map<Integer, ResponsesOutputItem> completedOutput = new TreeMap<>();
        private final Map<String, StreamingToolCall> toolCalls = new LinkedHashMap<>();
        private Msg terminalMessage;

        private StreamingState(String messageId) {
            this.messageId = messageId;
        }
    }

    private static final class StreamingToolCall {

        private final int outputIndex;
        private final String itemId;
        private final String callId;
        private final String name;
        private final StringBuilder arguments = new StringBuilder();
        private boolean done;

        private StreamingToolCall(int outputIndex, String itemId, String callId, String name) {
            this.outputIndex = outputIndex;
            this.itemId = itemId;
            this.callId = callId;
            this.name = name;
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

    private String functionCallId(String seed) {
        return seed != null && seed.startsWith("fc_") ? seed : "fc_" + normalize(seed);
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
        } catch (JsonProcessingException e) {
            return json;
        }
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
