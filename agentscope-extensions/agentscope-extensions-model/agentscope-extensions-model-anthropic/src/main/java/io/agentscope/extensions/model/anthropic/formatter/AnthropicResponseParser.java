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
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.RawMessageStreamEvent;
import com.anthropic.models.messages.ServerToolUseBlock;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
     * Metadata key on server tool {@link ToolResultBlock}s holding the raw result block as
     * serialized {@link ContentBlockParam} JSON. Required to echo the result back to Anthropic
     * verbatim in multi-turn conversations.
     */
    public static final String METADATA_SERVER_TOOL_RESULT = "anthropicServerToolResult";

    /** Tool name of the Anthropic web search server tool. */
    public static final String WEB_SEARCH_TOOL_NAME = "web_search";

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

            // Server tool use block (e.g. web_search executed on Anthropic's side)
            block.serverToolUse()
                    .ifPresent(
                            serverToolUse ->
                                    contentBlocks.add(convertServerToolUse(serverToolUse)));

            // Server tool result blocks (results of tools executed on Anthropic's side)
            collectServerToolResults(block, contentBlocks);
        }

        // Parse usage
        long baseInput = message.usage().inputTokens();
        long cacheRead = message.usage().cacheReadInputTokens().orElse(0L);
        long cacheCreate = message.usage().cacheCreationInputTokens().orElse(0L);
        ChatUsage usage =
                ChatUsage.builder()
                        .inputTokens((int) (baseInput + cacheRead + cacheCreate))
                        .outputTokens((int) message.usage().outputTokens())
                        .cachedTokens((int) cacheRead)
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
                    StreamUsageState usageState = new StreamUsageState();
                    return eventFlux
                            .flatMap(
                                    event -> {
                                        try {
                                            return Flux.just(
                                                    parseStreamEvent(event, startTime, usageState));
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
    private static ChatResponse parseStreamEvent(
            RawMessageStreamEvent event, Instant startTime, StreamUsageState usageState) {
        List<ContentBlock> contentBlocks = new ArrayList<>();
        ChatUsage usage = null;
        String messageId = null;

        // Message start - record prompt usage (input tokens and cache read/creation tokens) so
        // the final usage emitted on message_delta can include it
        if (event.isMessageStart()) {
            var startMessage = event.asMessageStart().message();
            messageId = startMessage.id();

            var startUsage = startMessage.usage();
            long cacheReadTokens = startUsage.cacheReadInputTokens().orElse(0L);
            long cacheCreationTokens = startUsage.cacheCreationInputTokens().orElse(0L);
            // Anthropic reports input_tokens excluding cached tokens; add them back so
            // cachedTokens stays a subset of inputTokens (ChatUsage invariant).
            usageState.inputTokens =
                    (int) (startUsage.inputTokens() + cacheReadTokens + cacheCreationTokens);
            usageState.cachedTokens = (int) cacheReadTokens;
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

            // Server tool use start (input arrives via subsequent input_json_delta events
            // which are accumulated through the regular fragment mechanism)
            startEvent
                    .contentBlock()
                    .serverToolUse()
                    .ifPresent(
                            serverToolUse -> {
                                contentBlocks.add(
                                        ToolUseBlock.builder()
                                                .id(serverToolUse.id())
                                                .name(serverToolName(serverToolUse))
                                                .input(Map.of())
                                                .content("")
                                                .metadata(
                                                        Map.of(
                                                                ToolUseBlock.METADATA_SERVER_TOOL,
                                                                true))
                                                .build());
                            });

            // Server tool results arrive complete in the start event
            collectServerToolResults(startEvent.contentBlock(), contentBlocks);
        }

        // Message delta - final usage information; combine the cumulative output tokens with
        // the prompt usage captured on message_start
        if (event.isMessageDelta()) {
            var deltaUsage = event.asMessageDelta().usage();
            long cacheReadTokens =
                    deltaUsage.cacheReadInputTokens().orElse((long) usageState.cachedTokens);
            long inputTokens =
                    deltaUsage.inputTokens().isPresent()
                            ? deltaUsage.inputTokens().get()
                                    + cacheReadTokens
                                    + deltaUsage.cacheCreationInputTokens().orElse(0L)
                            : usageState.inputTokens;
            usage =
                    ChatUsage.builder()
                            .inputTokens((int) inputTokens)
                            .cachedTokens((int) cacheReadTokens)
                            .outputTokens((int) deltaUsage.outputTokens())
                            .time(Duration.between(startTime, Instant.now()).toMillis() / 1000.0)
                            .build();
        }

        return ChatResponse.builder().id(messageId).content(contentBlocks).usage(usage).build();
    }

    /**
     * Convert an Anthropic server_tool_use block to a server-marked ToolUseBlock.
     */
    private static ToolUseBlock convertServerToolUse(ServerToolUseBlock block) {
        String name = serverToolName(block);
        Map<String, Object> input = parseJsonInput(block._input(), name);
        return ToolUseBlock.builder()
                .id(block.id())
                .name(name)
                .input(input)
                .content(block._input() != null ? block._input().toString() : "")
                .metadata(Map.of(ToolUseBlock.METADATA_SERVER_TOOL, true))
                .build();
    }

    private static String serverToolName(ServerToolUseBlock block) {
        try {
            String name = block.name().asString();
            if (name != null && !name.isBlank()) {
                return name;
            }
        } catch (Exception e) {
            log.debug("Failed to read server tool name: {}", e.getMessage());
        }
        return WEB_SEARCH_TOOL_NAME;
    }

    /**
     * Collect server tool result blocks from a non-streaming message content block.
     */
    private static void collectServerToolResults(
            com.anthropic.models.messages.ContentBlock block, List<ContentBlock> out) {
        block.webSearchToolResult()
                .ifPresent(
                        r ->
                                out.add(
                                        convertServerToolResult(
                                                WEB_SEARCH_TOOL_NAME,
                                                r.toolUseId(),
                                                ContentBlockParam.ofWebSearchToolResult(
                                                        r.toParam()))));
        block.webFetchToolResult()
                .ifPresent(
                        r ->
                                out.add(
                                        convertServerToolResult(
                                                "web_fetch",
                                                r.toolUseId(),
                                                ContentBlockParam.ofWebFetchToolResult(
                                                        r.toParam()))));
        block.codeExecutionToolResult()
                .ifPresent(
                        r ->
                                out.add(
                                        convertServerToolResult(
                                                "code_execution",
                                                r.toolUseId(),
                                                ContentBlockParam.ofCodeExecutionToolResult(
                                                        r.toParam()))));
        block.bashCodeExecutionToolResult()
                .ifPresent(
                        r ->
                                out.add(
                                        convertServerToolResult(
                                                "bash_code_execution",
                                                r.toolUseId(),
                                                ContentBlockParam.ofBashCodeExecutionToolResult(
                                                        r.toParam()))));
        block.textEditorCodeExecutionToolResult()
                .ifPresent(
                        r ->
                                out.add(
                                        convertServerToolResult(
                                                "text_editor_code_execution",
                                                r.toolUseId(),
                                                ContentBlockParam
                                                        .ofTextEditorCodeExecutionToolResult(
                                                                r.toParam()))));
        block.toolSearchToolResult()
                .ifPresent(
                        r ->
                                out.add(
                                        convertServerToolResult(
                                                "tool_search",
                                                r.toolUseId(),
                                                ContentBlockParam.ofToolSearchToolResult(
                                                        r.toParam()))));
    }

    /**
     * Collect server tool result blocks from a streaming content_block_start event block.
     */
    private static void collectServerToolResults(
            com.anthropic.models.messages.RawContentBlockStartEvent.ContentBlock block,
            List<ContentBlock> out) {
        block.webSearchToolResult()
                .ifPresent(
                        r ->
                                out.add(
                                        convertServerToolResult(
                                                WEB_SEARCH_TOOL_NAME,
                                                r.toolUseId(),
                                                ContentBlockParam.ofWebSearchToolResult(
                                                        r.toParam()))));
        block.webFetchToolResult()
                .ifPresent(
                        r ->
                                out.add(
                                        convertServerToolResult(
                                                "web_fetch",
                                                r.toolUseId(),
                                                ContentBlockParam.ofWebFetchToolResult(
                                                        r.toParam()))));
        block.codeExecutionToolResult()
                .ifPresent(
                        r ->
                                out.add(
                                        convertServerToolResult(
                                                "code_execution",
                                                r.toolUseId(),
                                                ContentBlockParam.ofCodeExecutionToolResult(
                                                        r.toParam()))));
        block.bashCodeExecutionToolResult()
                .ifPresent(
                        r ->
                                out.add(
                                        convertServerToolResult(
                                                "bash_code_execution",
                                                r.toolUseId(),
                                                ContentBlockParam.ofBashCodeExecutionToolResult(
                                                        r.toParam()))));
        block.textEditorCodeExecutionToolResult()
                .ifPresent(
                        r ->
                                out.add(
                                        convertServerToolResult(
                                                "text_editor_code_execution",
                                                r.toolUseId(),
                                                ContentBlockParam
                                                        .ofTextEditorCodeExecutionToolResult(
                                                                r.toParam()))));
        block.toolSearchToolResult()
                .ifPresent(
                        r ->
                                out.add(
                                        convertServerToolResult(
                                                "tool_search",
                                                r.toolUseId(),
                                                ContentBlockParam.ofToolSearchToolResult(
                                                        r.toParam()))));
    }

    /**
     * Convert an Anthropic server tool result block to a server-marked ToolResultBlock.
     *
     * <p>The raw block is kept in metadata under {@link #METADATA_SERVER_TOOL_RESULT} as
     * serialized {@link ContentBlockParam} JSON so it can be echoed back verbatim on multi-turn
     * requests. The human-readable output is a compact summary: {@code title (url)} entries for
     * web search, the content JSON for other tools, or the error code on failure.
     */
    private static ToolResultBlock convertServerToolResult(
            String toolName, String toolUseId, ContentBlockParam param) {
        List<ContentBlock> output = new ArrayList<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ToolResultBlock.METADATA_SERVER_TOOL, true);
        ToolResultState state = ToolResultState.SUCCESS;

        try {
            String json = ObjectMappers.jsonMapper().writeValueAsString(param);
            metadata.put(METADATA_SERVER_TOOL_RESULT, json);

            JsonNode content = ObjectMappers.jsonMapper().readTree(json).path("content");
            if (content.isObject() && content.hasNonNull("error_code")) {
                state = ToolResultState.ERROR;
                output.add(
                        TextBlock.builder()
                                .text(
                                        "[ERROR] "
                                                + toolName
                                                + " failed: "
                                                + content.get("error_code").asText())
                                .build());
            } else if (WEB_SEARCH_TOOL_NAME.equals(toolName) && content.isArray()) {
                for (JsonNode result : content) {
                    output.add(
                            TextBlock.builder()
                                    .text(
                                            result.path("title").asText()
                                                    + " ("
                                                    + result.path("url").asText()
                                                    + ")")
                                    .build());
                }
            } else {
                output.add(TextBlock.builder().text(content.toString()).build());
            }
        } catch (Exception e) {
            log.warn(
                    "Failed to capture server tool result {} ({}): {}",
                    toolUseId,
                    toolName,
                    e.getMessage());
            state = ToolResultState.ERROR;
            output.add(
                    TextBlock.builder()
                            .text("[ERROR] failed to capture " + toolName + " result")
                            .build());
        }

        return ToolResultBlock.builder()
                .id(toolUseId)
                .name(toolName)
                .output(output)
                .metadata(metadata)
                .state(state)
                .build();
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
