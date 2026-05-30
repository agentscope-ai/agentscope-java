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
package io.agentscope.core.llm.interfacesweb.responses;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.llm.interfacesweb.common.ProtocolJsonUtils;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.GenerateReason;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import reactor.core.publisher.Flux;

/** Converts AgentScope streaming events to OpenAI Responses semantic stream events. */
public class ResponsesStreamingAdapter {

    private final ResponsesResponseBuilder responseBuilder;

    public ResponsesStreamingAdapter(ResponsesResponseBuilder responseBuilder) {
        this.responseBuilder = responseBuilder;
    }

    public Flux<ResponsesStreamEvent> stream(
            ReActAgent agent, List<Msg> messages, ResponsesRequest request, String responseId) {
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
                        .incremental(true)
                        .build();

        ResponsesStreamEvent created = new ResponsesStreamEvent("response.created");
        created.setResponse(responseBuilder.baseResponse(request, responseId, "in_progress"));

        AtomicBoolean hasSeenIncrementalReasoning = new AtomicBoolean(false);
        Flux<ResponsesStreamEvent> body =
                agent.stream(messages, options)
                        .filter(event -> event.getMessage() != null)
                        .doOnNext(
                                event -> {
                                    if (event.getType() == EventType.REASONING && !event.isLast()) {
                                        hasSeenIncrementalReasoning.set(true);
                                    }
                                })
                        .flatMap(
                                event ->
                                        convertEvent(
                                                event,
                                                request,
                                                responseId,
                                                !(event.getType() == EventType.REASONING
                                                        && event.isLast()
                                                        && hasSeenIncrementalReasoning.get())));

        return Flux.concat(Flux.just(created), body);
    }

    public ResponsesStreamEvent errorEvent(Throwable error, String responseId) {
        ResponsesStreamEvent event = new ResponsesStreamEvent("response.failed");
        event.setResponseId(responseId);
        event.setError(
                java.util.Map.of(
                        "type",
                        "server_error",
                        "message",
                        error != null ? error.getMessage() : "Unknown error occurred"));
        return event;
    }

    private Flux<ResponsesStreamEvent> convertEvent(
            Event event, ResponsesRequest request, String responseId, boolean includeText) {
        List<ResponsesStreamEvent> events = new ArrayList<>();
        Msg msg = event.getMessage();
        if (msg.getContent() == null) {
            return Flux.empty();
        }

        if (includeText) {
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof TextBlock textBlock
                        && textBlock.getText() != null
                        && !textBlock.getText().isEmpty()) {
                    ResponsesStreamEvent delta =
                            new ResponsesStreamEvent("response.output_text.delta");
                    delta.setResponseId(responseId);
                    delta.setOutputIndex(0);
                    delta.setContentIndex(0);
                    delta.setDelta(textBlock.getText());
                    events.add(delta);
                }
            }
        }

        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ToolUseBlock toolUseBlock) {
                ResponsesStreamEvent itemAdded =
                        new ResponsesStreamEvent("response.output_item.added");
                itemAdded.setResponseId(responseId);
                itemAdded.setOutputIndex(events.size());
                itemAdded.setItem(responseBuilder.toolUseItem(toolUseBlock));
                events.add(itemAdded);

                ResponsesStreamEvent argsDelta =
                        new ResponsesStreamEvent("response.function_call_arguments.delta");
                argsDelta.setResponseId(responseId);
                argsDelta.setItemId(toolUseBlock.getId());
                argsDelta.setDelta(
                        toolUseBlock.getContent() != null
                                ? toolUseBlock.getContent()
                                : ProtocolJsonUtils.toJson(toolUseBlock.getInput()));
                events.add(argsDelta);
            }
        }

        if (event.isLast()) {
            ResponsesStreamEvent completed =
                    new ResponsesStreamEvent(
                            msg.getGenerateReason() == GenerateReason.MAX_ITERATIONS
                                    ? "response.incomplete"
                                    : "response.completed");
            completed.setResponse(responseBuilder.buildResponse(request, msg, responseId));
            events.add(completed);
        }
        return Flux.fromIterable(events);
    }
}
