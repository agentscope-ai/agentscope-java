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
package io.agentscope.extensions.model.anthropic.formatter;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.Usage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

/**
 * Parses Anthropic API responses (both streaming and non-streaming) into AgentScope ChatResponse
 * objects.
 */
public class AnthropicResponseParser {

    private static final Logger log = LoggerFactory.getLogger(AnthropicResponseParser.class);

    /**
     * Parse non-streaming Anthropic Message to ChatResponse.
     */
    public static ChatResponse parseMessage(Message message, Instant startTime) {
        List<ContentBlock> contentBlocks = new ArrayList<>();

        // Process content blocks
        for (var block : message.content()) {
            // Text block
            block.text()
                    .ifPresent(
                            textBlock ->
                                    contentBlocks.add(
                                            TextBlock.builder().text(textBlock.text()).build()));

            // Tool use block
            block.toolUse()
                    .ifPresent(
                            toolUse -> {
                                Map<String, Object> input =
                                        parseJsonInput(toolUse._input(), toolUse.name());
                                contentBlocks.add(
                                        ToolUseBlock.builder()
                                                .id(toolUse.id())
                                                .name(toolUse.name())
                                                .input(input)
                                                .content(
                                                        toolUse._input() != null
                                                                ? toolUse._input().toString()
                                                                : "")
                                                .build());
                            });

            // Thinking block (extended thinking)
            block.thinking()
                    .ifPresent(
                            thinking ->
                                    contentBlocks.add(
                                            ThinkingBlock.builder()
                                                    .thinking(thinking.thinking())
                                                    .build()));
        }

        // Parse usage
        Usage anthropicUsage = message.usage();
        long cacheRead = anthropicUsage.cacheReadInputTokens().orElse(0L);
        long cacheCreation = anthropicUsage.cacheCreationInputTokens().orElse(0L);
        ChatUsage usage =
                ChatUsage.builder()
                        .inputTokens(
                                Math.toIntExact(
                                        anthropicUsage.inputTokens() + cacheRead + cacheCreation))
                        .outputTokens(Math.toIntExact(anthropicUsage.outputTokens()))
                        .cachedTokens(Math.toIntExact(cacheRead))
                        .cacheCreationInputTokens(Math.toIntExact(cacheCreation))
                        .time(Duration.between(startTime, Instant.now()).toMillis() / 1000.0)
                        .build();

        return ChatResponse.builder().id(message.id()).content(contentBlocks).usage(usage).build();
    }

    /**
     * Mutable holder for prompt token counts observed on the message_start event, so the final
     * usage emitted on message_delta can include input and cached token counts.
     */
    private static class StreamUsageState {
        int inputTokens;
        int cachedTokens;
    }

    /**
     * Parse streaming Anthropic events to ChatResponse Flux.
     */
    public static Flux<ChatResponse> parseStreamEvents(
            Flux<RawMessageStreamEvent> eventFlux, Instant startTime) {
        return Flux.defer(
                () -> {
                    StreamState state = new StreamState();
                    return eventFlux
                            .concatMap(
                                    event -> {
                                        try {
                                            return Flux.just(
                                                    parseStreamEvent(event, startTime, state));
                                        } catch (Exception e) {
                                            log.warn(
                                                    "Error parsing stream event: {}",
                                                    e.getMessage());
                                            return Flux.empty();
                                        }
                                    })
                            .filter(
                                    response ->
                                            response != null
                                                    && (!response.getContent().isEmpty()
                                                            || response.getUsage() != null));
                });
    }

    /**
     * Parse single stream event.
     */
    private static ChatResponse parseStreamEvent(RawMessageStreamEvent event, Instant startTime) {
        return parseStreamEvent(event, startTime, new StreamState());
    }

    private static ChatResponse parseStreamEvent(
            RawMessageStreamEvent event, Instant startTime, StreamState state) {
        List<ContentBlock> contentBlocks = new ArrayList<>();
        ChatUsage usage = null;

        // Message start - record prompt usage (input tokens and cache read/creation tokens) so
        // the final usage emitted on message_delta can include it
        if (event.isMessageStart()) {
            Message message = event.asMessageStart().message();
            state.messageId = message.id();
            Usage startUsage = message.usage();
            if (startUsage != null) {
                state.inputTokens = startUsage.inputTokens();
                state.cachedTokens = startUsage.cacheReadInputTokens().orElse(0L);
                state.cacheCreationInputTokens = startUsage.cacheCreationInputTokens().orElse(0L);
            }
        }

        // Content block delta - text
        if (event.isContentBlockDelta()) {
            var deltaEvent = event.asContentBlockDelta();

            deltaEvent
                    .delta()
                    .text()
                    .ifPresent(
                            textDelta ->
                                    contentBlocks.add(
                                            TextBlock.builder().text(textDelta.text()).build()));

            deltaEvent
                    .delta()
                    .thinking()
                    .ifPresent(
                            thinkingDelta ->
                                    contentBlocks.add(
                                            ThinkingBlock.builder()
                                                    .thinking(thinkingDelta.thinking())
                                                    .build()));

            // Input JSON delta (tool calling)
            deltaEvent
                    .delta()
                    .inputJson()
                    .ifPresent(
                            jsonDelta -> {
                                // Create fragment ToolUseBlock for accumulation
                                contentBlocks.add(
                                        ToolUseBlock.builder()
                                                .id("") // Empty ID indicates fragment
                                                .name("__fragment__") // Fragment marker
                                                .content(jsonDelta.partialJson())
                                                .input(Map.of())
                                                .build());
                            });
        }

        // Content block start - tool use
        if (event.isContentBlockStart()) {
            var startEvent = event.asContentBlockStart();

            startEvent
                    .contentBlock()
                    .toolUse()
                    .ifPresent(
                            toolUse -> {
                                contentBlocks.add(
                                        ToolUseBlock.builder()
                                                .id(toolUse.id())
                                                .name(toolUse.name())
                                                .input(Map.of())
                                                .content("")
                                                .build());
                            });
        }

        // Message delta - final usage information; combine the cumulative output tokens with
        // the prompt usage captured on message_start
        if (event.isMessageDelta()) {
            var messageDelta = event.asMessageDelta();
            messageDelta.usage().inputTokens().ifPresent(value -> state.inputTokens = value);
            messageDelta
                    .usage()
                    .cacheReadInputTokens()
                    .ifPresent(value -> state.cachedTokens = value);
            messageDelta
                    .usage()
                    .cacheCreationInputTokens()
                    .ifPresent(value -> state.cacheCreationInputTokens = value);
            state.outputTokens = messageDelta.usage().outputTokens();
            usage =
                    ChatUsage.builder()
                            .inputTokens(
                                    Math.toIntExact(
                                            state.inputTokens
                                                    + state.cachedTokens
                                                    + state.cacheCreationInputTokens))
                            .outputTokens(Math.toIntExact(state.outputTokens))
                            .cachedTokens(Math.toIntExact(state.cachedTokens))
                            .cacheCreationInputTokens(
                                    Math.toIntExact(state.cacheCreationInputTokens))
                            .time(Duration.between(startTime, Instant.now()).toMillis() / 1000.0)
                            .build();
        }

        return ChatResponse.builder()
                .id(state.messageId)
                .content(contentBlocks)
                .usage(usage)
                .build();
    }

    private static final class StreamState {
        private String messageId;
        private long inputTokens;
        private long outputTokens;
        private long cachedTokens;
        private long cacheCreationInputTokens;
    }

    /**
     * Parse JsonValue to Map for tool input.
     */
    private static Map<String, Object> parseJsonInput(JsonValue jsonValue, String toolName) {
        if (jsonValue == null) {
            return Map.of();
        }

        try {
            String jsonString = ObjectMappers.jsonMapper().writeValueAsString(jsonValue);
            @SuppressWarnings("unchecked")
            Map<String, Object> result = JsonUtils.getJsonCodec().fromJson(jsonString, Map.class);
            return result != null ? result : Map.of();
        } catch (Exception e) {
            log.warn("Failed to parse tool input JSON for tool {}: {}", toolName, e.getMessage());
            return Map.of();
        }
    }
}
