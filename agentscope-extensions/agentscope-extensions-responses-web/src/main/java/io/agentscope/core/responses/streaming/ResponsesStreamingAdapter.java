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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
        boolean structuredStream = structuredOutputSchema != null;
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(streamEventTypes(structuredStream))
                        .incremental(true)
                        .build();

        ResponsesResponse created = responseBuilder.baseResponse(request, responseId, "created");
        ResponsesResponse inProgress =
                responseBuilder.baseResponse(request, responseId, "in_progress");

        AtomicBoolean hasTextItem = new AtomicBoolean(false);
        AtomicBoolean hasTextDone = new AtomicBoolean(false);
        AtomicBoolean hasSeenIncrementalReasoning = new AtomicBoolean(false);
        AtomicInteger nextOutputIndex = new AtomicInteger(0);
        AtomicInteger textOutputIndex = new AtomicInteger(-1);
        AtomicReference<Msg> terminalMessage = new AtomicReference<>();
        Map<Integer, ResponsesOutputItem> completedOutput = new TreeMap<>();
        StringBuilder accumulatedText = new StringBuilder();
        String messageId = messageId(responseId);

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
                                        hasSeenIncrementalReasoning.set(true);
                                    }
                                })
                        .concatMap(
                                event ->
                                        convertEvent(
                                                event,
                                                nextOutputIndex,
                                                textOutputIndex,
                                                messageId,
                                                hasTextItem,
                                                hasTextDone,
                                                hasSeenIncrementalReasoning,
                                                accumulatedText,
                                                completedOutput,
                                                terminalMessage,
                                                structuredStream));

        Flux<ResponsesStreamEvent> completion =
                Flux.defer(
                        () -> {
                            List<ResponsesStreamEvent> events = new ArrayList<>();
                            // If the model ended without an explicit terminal text event, close
                            // the open content part before sending response.completed.
                            if (hasTextItem.get() && !hasTextDone.get()) {
                                events.addAll(
                                        completeTextEvents(
                                                textOutputIndex.get(),
                                                messageId,
                                                accumulatedText.toString(),
                                                completedOutput,
                                                hasTextDone));
                            }
                            ResponsesResponse completed =
                                    responseBuilder.buildStreamingCompletedResponse(
                                            request,
                                            responseId,
                                            new ArrayList<>(completedOutput.values()),
                                            accumulatedText.toString(),
                                            terminalMessage.get());
                            events.add(
                                    ResponsesStreamEvent.responseEvent(
                                            "response.completed", completed));
                            return Flux.fromIterable(events);
                        });

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
            Event event,
            AtomicInteger nextOutputIndex,
            AtomicInteger textOutputIndex,
            String messageId,
            AtomicBoolean hasTextItem,
            AtomicBoolean hasTextDone,
            AtomicBoolean hasSeenIncrementalReasoning,
            StringBuilder accumulatedText,
            Map<Integer, ResponsesOutputItem> completedOutput,
            AtomicReference<Msg> terminalMessage,
            boolean structuredStream) {
        Msg msg = event.getMessage();
        if (msg == null) {
            return Flux.empty();
        }
        if (event.isLast()) {
            terminalMessage.set(msg);
        }

        if (structuredStream && event.getType() == EventType.AGENT_RESULT) {
            // Structured output streaming does not expose partial schema objects from AgentScope;
            // emit the final structured payload as a single output_text delta.
            return Flux.fromIterable(
                    structuredOutputEvents(
                            msg,
                            nextOutputIndex,
                            textOutputIndex,
                            messageId,
                            hasTextItem,
                            accumulatedText,
                            completedOutput,
                            hasTextDone));
        }

        if (msg.getContent() == null) {
            return Flux.empty();
        }

        boolean includeText =
                !(event.getType() == EventType.REASONING
                        && event.isLast()
                        && hasSeenIncrementalReasoning.get());
        List<ResponsesStreamEvent> events = new ArrayList<>();

        String text = text(msg);
        if (includeText && !text.isEmpty()) {
            if (hasTextItem.compareAndSet(false, true)) {
                // Responses streams must announce output item and content part creation before
                // emitting text deltas for that item.
                int index = nextOutputIndex.getAndIncrement();
                textOutputIndex.set(index);
                ResponsesOutputItem item =
                        ResponsesOutputItem.message(messageId, "", "in_progress");
                events.add(
                        ResponsesStreamEvent.outputItemEvent(
                                "response.output_item.added", index, item));
                events.add(
                        ResponsesStreamEvent.contentPartEvent(
                                "response.content_part.added",
                                index,
                                0,
                                messageId,
                                ResponsesContentPart.outputText("")));
            }
            accumulatedText.append(text);
            events.add(
                    ResponsesStreamEvent.textDelta(
                            "response.output_text.delta",
                            textOutputIndex.get(),
                            0,
                            messageId,
                            text));
        }

        if (event.getType() == EventType.REASONING) {
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof ToolUseBlock toolUseBlock) {
                    // Tool-use blocks become Responses function_call output items. The actual Java
                    // tool execution is performed by the client or by AgentScope toolkit code.
                    int index = nextOutputIndex.getAndIncrement();
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
                                    itemId,
                                    toolUseBlock.getId(),
                                    toolUseBlock.getName(),
                                    arguments);
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
                    completedOutput.put(index, completedItem);
                }
            }
        }

        if (event.isLast()
                && hasTextItem.get()
                && !hasTextDone.get()
                && msg.getGenerateReason() != GenerateReason.TOOL_SUSPENDED) {
            events.addAll(
                    completeTextEvents(
                            textOutputIndex.get(),
                            messageId,
                            accumulatedText.toString(),
                            completedOutput,
                            hasTextDone));
        }

        return Flux.fromIterable(events);
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

    private List<ResponsesStreamEvent> structuredOutputEvents(
            Msg msg,
            AtomicInteger nextOutputIndex,
            AtomicInteger textOutputIndex,
            String messageId,
            AtomicBoolean hasTextItem,
            StringBuilder accumulatedText,
            Map<Integer, ResponsesOutputItem> completedOutput,
            AtomicBoolean hasTextDone) {
        String text = structuredOutputText(msg);
        if (text.isEmpty()) {
            return List.of();
        }

        List<ResponsesStreamEvent> events = new ArrayList<>();
        if (hasTextItem.compareAndSet(false, true)) {
            int index = nextOutputIndex.getAndIncrement();
            textOutputIndex.set(index);
            events.add(
                    ResponsesStreamEvent.outputItemEvent(
                            "response.output_item.added",
                            index,
                            ResponsesOutputItem.message(messageId, "", "in_progress")));
            events.add(
                    ResponsesStreamEvent.contentPartEvent(
                            "response.content_part.added",
                            index,
                            0,
                            messageId,
                            ResponsesContentPart.outputText("")));
        }
        accumulatedText.append(text);
        events.add(
                ResponsesStreamEvent.textDelta(
                        "response.output_text.delta", textOutputIndex.get(), 0, messageId, text));
        events.addAll(
                completeTextEvents(
                        textOutputIndex.get(),
                        messageId,
                        accumulatedText.toString(),
                        completedOutput,
                        hasTextDone));
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

    private List<ResponsesStreamEvent> completeTextEvents(
            int outputIndex,
            String messageId,
            String text,
            Map<Integer, ResponsesOutputItem> completedOutput,
            AtomicBoolean hasTextDone) {
        if (outputIndex < 0 || !hasTextDone.compareAndSet(false, true)) {
            return List.of();
        }

        ResponsesOutputItem item = ResponsesOutputItem.message(messageId, text);
        completedOutput.put(outputIndex, item);

        List<ResponsesStreamEvent> events = new ArrayList<>();
        events.add(
                ResponsesStreamEvent.textDone(
                        "response.output_text.done", outputIndex, 0, messageId, text));
        events.add(
                ResponsesStreamEvent.contentPartEvent(
                        "response.content_part.done",
                        outputIndex,
                        0,
                        messageId,
                        ResponsesContentPart.outputText(text)));
        events.add(
                ResponsesStreamEvent.outputItemEvent(
                        "response.output_item.done", outputIndex, item));
        return events;
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
