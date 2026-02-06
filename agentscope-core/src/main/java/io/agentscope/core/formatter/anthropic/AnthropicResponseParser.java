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

import io.agentscope.core.formatter.anthropic.dto.AnthropicContent;
import io.agentscope.core.formatter.anthropic.dto.AnthropicResponse;
import io.agentscope.core.formatter.anthropic.dto.AnthropicStreamEvent;
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
 * Parses Anthropic API responses (both streaming and non-streaming) into
 * AgentScope ChatResponse
 * objects.
 */
public class AnthropicResponseParser {

    private static final Logger log = LoggerFactory.getLogger(AnthropicResponseParser.class);

    /**
     * Parse non-streaming Anthropic response to ChatResponse.
     */
    public static ChatResponse parseMessage(AnthropicResponse message, Instant startTime) {
        List<ContentBlock> contentBlocks = new ArrayList<>();

        // Process content blocks
        if (message.getContent() != null) {
            for (AnthropicContent block : message.getContent()) {
                String type = block.getType();
                if (type == null) continue;

                switch (type) {
                    case "text" -> {
                        if (block.getText() != null) {
                            contentBlocks.add(TextBlock.builder().text(block.getText()).build());
                        }
                    }
                    case "tool_use" -> {
                        Map<String, Object> input = parseInput(block.getInput(), block.getName());
                        contentBlocks.add(
                                ToolUseBlock.builder()
                                        .id(block.getId())
                                        .name(block.getName())
                                        .input(input)
                                        .content(
                                                block.getInput() != null
                                                        ? block.getInput().toString()
                                                        : "")
                                        .build());
                    }
                    case "thinking" -> {
                        if (block.getThinking() != null) {
                            contentBlocks.add(
                                    ThinkingBlock.builder().thinking(block.getThinking()).build());
                        }
                    }
                }
            }
        }

        // Parse usage
        ChatUsage usage = null;
        if (message.getUsage() != null) {
            usage =
                    ChatUsage.builder()
                            .inputTokens(
                                    message.getUsage().getInputTokens() != null
                                            ? message.getUsage().getInputTokens()
                                            : 0)
                            .outputTokens(
                                    message.getUsage().getOutputTokens() != null
                                            ? message.getUsage().getOutputTokens()
                                            : 0)
                            .time(Duration.between(startTime, Instant.now()).toMillis() / 1000.0)
                            .build();
        }

        return ChatResponse.builder()
                .id(message.getId())
                .content(contentBlocks)
                .usage(usage)
                .build();
    }

    /**
     * Parse streaming Anthropic events to ChatResponse Flux.
     */
    public static Flux<ChatResponse> parseStreamEvents(
            Flux<AnthropicStreamEvent> eventFlux, Instant startTime) {
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
    private static ChatResponse parseStreamEvent(AnthropicStreamEvent event, Instant startTime) {
        List<ContentBlock> contentBlocks = new ArrayList<>();
        ChatUsage usage = null;
        String messageId = null;

        String eventType = event.getType();
        if (eventType == null) {
            return ChatResponse.builder().content(contentBlocks).build();
        }

        switch (eventType) {
            case "message_start" -> {
                if (event.getMessage() != null) {
                    messageId = event.getMessage().getId();
                }
            }
            case "content_block_start" -> {
                AnthropicContent block = event.getContentBlock();
                if (block != null && "tool_use".equals(block.getType())) {
                    contentBlocks.add(
                            ToolUseBlock.builder()
                                    .id(block.getId())
                                    .name(block.getName())
                                    .input(Map.of())
                                    .content("")
                                    .build());
                }
            }
            case "content_block_delta" -> {
                AnthropicStreamEvent.Delta delta = event.getDelta();
                if (delta != null) {
                    // Text delta
                    if (delta.getText() != null) {
                        contentBlocks.add(TextBlock.builder().text(delta.getText()).build());
                    }
                    // Tool input JSON delta
                    if (delta.getPartialJson() != null) {
                        contentBlocks.add(
                                ToolUseBlock.builder()
                                        .id("") // Empty ID indicates fragment
                                        .name("__fragment__") // Fragment marker
                                        .content(delta.getPartialJson())
                                        .input(Map.of())
                                        .build());
                    }
                }
            }
            case "message_delta" -> {
                if (event.getUsage() != null) {
                    usage =
                            ChatUsage.builder()
                                    .outputTokens(
                                            event.getUsage().getOutputTokens() != null
                                                    ? event.getUsage().getOutputTokens()
                                                    : 0)
                                    .time(
                                            Duration.between(startTime, Instant.now()).toMillis()
                                                    / 1000.0)
                                    .build();
                }
            }
        }

        return ChatResponse.builder().id(messageId).content(contentBlocks).usage(usage).build();
    }

    /**
     * Parse input object to Map for tool input.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseInput(Object input, String toolName) {
        if (input == null) {
            return Map.of();
        }

        try {
            if (input instanceof Map) {
                return (Map<String, Object>) input;
            }
            // Convert to JSON string and back to Map
            String jsonString = JsonUtils.getJsonCodec().toJson(input);
            Map<String, Object> result = JsonUtils.getJsonCodec().fromJson(jsonString, Map.class);
            return result != null ? result : Map.of();
        } catch (Exception e) {
            log.warn("Failed to parse tool input for tool {}: {}", toolName, e.getMessage());
            return Map.of();
        }
    }
}
