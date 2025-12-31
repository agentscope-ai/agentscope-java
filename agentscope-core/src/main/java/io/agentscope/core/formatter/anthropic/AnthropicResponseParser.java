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
package io.agentscope.core.formatter.anthropic;

import com.anthropic.core.JsonValue;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
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

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Parse non-streaming Anthropic Message to ChatResponse.
     */
    public static ChatResponse parseMessage(Message message, Instant startTime) {
        List<ContentBlock> contentBlocks = new ArrayList<>();

        // Process content blocks
        for (com.anthropic.models.messages.ContentBlock block : message.content()) {
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
        ChatUsage usage =
                ChatUsage.builder()
                        .inputTokens((int) message.usage().inputTokens())
                        .outputTokens((int) message.usage().outputTokens())
                        .time(Duration.between(startTime, Instant.now()).toMillis() / 1000.0)
                        .build();

        return ChatResponse.builder().id(message.id()).content(contentBlocks).usage(usage).build();
    }

    /**
     * Parse streaming Anthropic events to ChatResponse Flux.
     */
    public static Flux<ChatResponse> parseStreamEvents(
            Flux<RawMessageStreamEvent> eventFlux, Instant startTime) {
        return eventFlux
                .flatMap(
                        event -> {
                            try {
                                return Flux.just(parseStreamEvent(event, startTime));
                            } catch (Exception e) {
                                log.warn("Error parsing stream event: {}", e.getMessage());
                                return Flux.empty();
                            }
                        })
                .filter(response -> response != null && !response.getContent().isEmpty());
    }

    /**
     * Parse single stream event.
     */
    private static ChatResponse parseStreamEvent(RawMessageStreamEvent event, Instant startTime) {
        List<ContentBlock> contentBlocks = new ArrayList<>();
        ChatUsage usage = null;
        String messageId = null;

        // Message start
        if (event.isMessageStart()) {
            messageId = event.asMessageStart().message().id();
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

        // Message delta - usage information
        if (event.isMessageDelta()) {
            var messageDelta = event.asMessageDelta();
            usage =
                    ChatUsage.builder()
                            .outputTokens((int) messageDelta.usage().outputTokens())
                            .time(Duration.between(startTime, Instant.now()).toMillis() / 1000.0)
                            .build();
        }

        return ChatResponse.builder().id(messageId).content(contentBlocks).usage(usage).build();
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
            Map<String, Object> result = objectMapper.readValue(jsonString, Map.class);
            return result != null ? result : Map.of();
        } catch (Exception e) {
            log.warn("Failed to parse tool input JSON for tool {}: {}", toolName, e.getMessage());
            return Map.of();
        }
    }
}
