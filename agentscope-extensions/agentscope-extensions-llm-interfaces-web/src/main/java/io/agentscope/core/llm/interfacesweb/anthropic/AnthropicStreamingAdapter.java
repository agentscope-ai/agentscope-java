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
package io.agentscope.core.llm.interfacesweb.anthropic;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.llm.interfacesweb.common.ProtocolJsonUtils;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;

/** Converts AgentScope streams to Anthropic Messages streaming events. */
public class AnthropicStreamingAdapter {

    private final AnthropicResponseBuilder responseBuilder;

    public AnthropicStreamingAdapter(AnthropicResponseBuilder responseBuilder) {
        this.responseBuilder = responseBuilder;
    }

    public Flux<AnthropicStreamEvent> stream(
            ReActAgent agent,
            List<Msg> messages,
            AnthropicMessagesRequest request,
            String messageId) {
        StreamOptions options =
                StreamOptions.builder()
                        .eventTypes(EventType.REASONING, EventType.TOOL_RESULT)
                        .incremental(true)
                        .build();

        AnthropicStreamEvent start = new AnthropicStreamEvent("message_start");
        start.setMessage(responseBuilder.baseResponse(request, messageId));

        AtomicBoolean hasTextBlock = new AtomicBoolean(false);
        AtomicInteger nextIndex = new AtomicInteger(0);
        AtomicBoolean hasSeenIncrementalReasoning = new AtomicBoolean(false);

        Flux<AnthropicStreamEvent> body =
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
                                                messageId,
                                                hasTextBlock,
                                                nextIndex,
                                                !(event.getType() == EventType.REASONING
                                                        && event.isLast()
                                                        && hasSeenIncrementalReasoning.get())));

        return Flux.concat(Flux.just(start), body);
    }

    public AnthropicStreamEvent errorEvent(Throwable error) {
        AnthropicStreamEvent event = new AnthropicStreamEvent("error");
        event.setDelta(
                Map.of(
                        "type",
                        "error",
                        "error",
                        Map.of(
                                "type",
                                "api_error",
                                "message",
                                error != null ? error.getMessage() : "Unknown error occurred")));
        return event;
    }

    private Flux<AnthropicStreamEvent> convertEvent(
            Event event,
            AnthropicMessagesRequest request,
            String messageId,
            AtomicBoolean hasTextBlock,
            AtomicInteger nextIndex,
            boolean includeText) {
        List<AnthropicStreamEvent> events = new ArrayList<>();
        Msg msg = event.getMessage();
        if (msg == null || msg.getContent() == null) {
            return Flux.empty();
        }

        if (includeText) {
            for (ContentBlock block : msg.getContent()) {
                if (block instanceof TextBlock textBlock
                        && textBlock.getText() != null
                        && !textBlock.getText().isEmpty()) {
                    int index = 0;
                    if (!hasTextBlock.getAndSet(true)) {
                        nextIndex.compareAndSet(0, 1);
                        AnthropicStreamEvent blockStart =
                                new AnthropicStreamEvent("content_block_start");
                        blockStart.setIndex(index);
                        blockStart.setContentBlock(Map.of("type", "text", "text", ""));
                        events.add(blockStart);
                    }

                    AnthropicStreamEvent delta = new AnthropicStreamEvent("content_block_delta");
                    delta.setIndex(index);
                    delta.setDelta(Map.of("type", "text_delta", "text", textBlock.getText()));
                    events.add(delta);
                }
            }
        }

        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ToolUseBlock toolUseBlock) {
                int index = nextIndex.getAndIncrement();
                AnthropicStreamEvent blockStart = new AnthropicStreamEvent("content_block_start");
                blockStart.setIndex(index);
                blockStart.setContentBlock(
                        Map.of(
                                "type",
                                "tool_use",
                                "id",
                                toolUseBlock.getId(),
                                "name",
                                toolUseBlock.getName(),
                                "input",
                                Map.of()));
                events.add(blockStart);

                AnthropicStreamEvent args = new AnthropicStreamEvent("content_block_delta");
                args.setIndex(index);
                args.setDelta(
                        Map.of(
                                "type",
                                "input_json_delta",
                                "partial_json",
                                toolUseBlock.getContent() != null
                                        ? toolUseBlock.getContent()
                                        : ProtocolJsonUtils.toJson(toolUseBlock.getInput())));
                events.add(args);

                AnthropicStreamEvent blockStop = new AnthropicStreamEvent("content_block_stop");
                blockStop.setIndex(index);
                events.add(blockStop);
            }
        }

        if (event.isLast()) {
            if (hasTextBlock.get()) {
                AnthropicStreamEvent blockStop = new AnthropicStreamEvent("content_block_stop");
                blockStop.setIndex(0);
                events.add(blockStop);
            }
            AnthropicStreamEvent delta = new AnthropicStreamEvent("message_delta");
            delta.setDelta(Map.of("stop_reason", responseBuilder.stopReason(msg)));
            delta.setUsage(new AnthropicUsage(null, 0));
            events.add(delta);

            AnthropicStreamEvent stop = new AnthropicStreamEvent("message_stop");
            stop.setMessage(responseBuilder.buildResponse(request, msg, messageId));
            events.add(stop);
        }

        return Flux.fromIterable(events);
    }
}
