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
import com.anthropic.models.messages.ServerToolUseBlock;
import com.anthropic.models.messages.ServerToolUseBlockParam;
import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts Anthropic server tool blocks between the provider SDK and AgentScope content blocks.
 *
 * <p>The raw provider result block is retained in metadata so later turns can echo it back to
 * Anthropic without reconstructing provider-only fields from the generic AgentScope model.
 */
final class AnthropicServerToolHelper {

    static final String RESULT_METADATA = "anthropicServerToolResult";

    private static final Logger log = LoggerFactory.getLogger(AnthropicServerToolHelper.class);

    private static final AnthropicServerToolHelper INSTANCE = new AnthropicServerToolHelper();

    private AnthropicServerToolHelper() {}

    static AnthropicServerToolHelper instance() {
        return INSTANCE;
    }

    /**
     * Converts a server-marked AgentScope tool call back to an Anthropic request block.
     *
     * @param toolUse the AgentScope server tool call
     * @return the Anthropic server_tool_use request block
     */
    ServerToolUseBlockParam encodeUse(ToolUseBlock toolUse) {
        ServerToolUseBlockParam.Input.Builder inputBuilder =
                ServerToolUseBlockParam.Input.builder();
        if (toolUse.getInput() != null) {
            toolUse.getInput()
                    .forEach(
                            (key, value) ->
                                    inputBuilder.putAdditionalProperty(key, JsonValue.from(value)));
        }
        return ServerToolUseBlockParam.builder()
                .id(toolUse.getId())
                .name(JsonValue.from(toolUse.getName()))
                .input(inputBuilder.build())
                .build();
    }

    /**
     * Restores the original Anthropic result block stored in AgentScope metadata.
     *
     * @param toolResult the AgentScope server tool result
     * @return the original Anthropic result block, or null when it cannot be restored
     */
    ContentBlockParam encodeResult(ToolResultBlock toolResult) {
        Object raw = toolResult.getMetadata().get(RESULT_METADATA);
        if (raw instanceof String json && !json.isBlank()) {
            try {
                return ObjectMappers.jsonMapper().readValue(json, ContentBlockParam.class);
            } catch (Exception e) {
                log.warn(
                        "Failed to restore server tool result {}: {}",
                        toolResult.getId(),
                        e.getMessage());
            }
        } else {
            log.warn(
                    "Server tool result {} has no raw block JSON in metadata; skipping echo",
                    toolResult.getId());
        }
        return null;
    }

    /**
     * Converts an Anthropic server_tool_use response block to an AgentScope content block.
     *
     * @param block the Anthropic server tool use response block
     * @return the server-marked AgentScope tool call
     */
    ToolUseBlock decodeUse(ServerToolUseBlock block) {
        String name = block.name().asString();
        Map<String, Object> input = AnthropicResponseParser.parseJsonInput(block._input(), name);
        return ToolUseBlock.builder()
                .id(block.id())
                .name(name)
                .input(input)
                .content(block._input() != null ? block._input().toString() : "")
                .metadata(Map.of(ToolUseBlock.METADATA_SERVER_TOOL, true))
                .build();
    }

    /**
     * Normalizes a non-streaming Anthropic server tool result block.
     *
     * @param block the Anthropic response content block
     * @return the server tool result param, or empty for other content block types
     */
    Optional<ContentBlockParam> toResultParam(com.anthropic.models.messages.ContentBlock block) {
        return block.webSearchToolResult()
                .map(r -> ContentBlockParam.ofWebSearchToolResult(r.toParam()))
                .or(
                        () ->
                                block.webFetchToolResult()
                                        .map(
                                                r ->
                                                        ContentBlockParam.ofWebFetchToolResult(
                                                                r.toParam())))
                .or(
                        () ->
                                block.codeExecutionToolResult()
                                        .map(
                                                r ->
                                                        ContentBlockParam.ofCodeExecutionToolResult(
                                                                r.toParam())))
                .or(
                        () ->
                                block.bashCodeExecutionToolResult()
                                        .map(
                                                r ->
                                                        ContentBlockParam
                                                                .ofBashCodeExecutionToolResult(
                                                                        r.toParam())))
                .or(
                        () ->
                                block.textEditorCodeExecutionToolResult()
                                        .map(
                                                r ->
                                                        ContentBlockParam
                                                                .ofTextEditorCodeExecutionToolResult(
                                                                        r.toParam())))
                .or(
                        () ->
                                block.toolSearchToolResult()
                                        .map(
                                                r ->
                                                        ContentBlockParam.ofToolSearchToolResult(
                                                                r.toParam())));
    }

    /**
     * Normalizes a streaming content_block_start server tool result block.
     *
     * @param block the Anthropic stream start content block
     * @return the server tool result param, or empty for other content block types
     */
    Optional<ContentBlockParam> toResultParam(
            com.anthropic.models.messages.RawContentBlockStartEvent.ContentBlock block) {
        return block.webSearchToolResult()
                .map(r -> ContentBlockParam.ofWebSearchToolResult(r.toParam()))
                .or(
                        () ->
                                block.webFetchToolResult()
                                        .map(
                                                r ->
                                                        ContentBlockParam.ofWebFetchToolResult(
                                                                r.toParam())))
                .or(
                        () ->
                                block.codeExecutionToolResult()
                                        .map(
                                                r ->
                                                        ContentBlockParam.ofCodeExecutionToolResult(
                                                                r.toParam())))
                .or(
                        () ->
                                block.bashCodeExecutionToolResult()
                                        .map(
                                                r ->
                                                        ContentBlockParam
                                                                .ofBashCodeExecutionToolResult(
                                                                        r.toParam())))
                .or(
                        () ->
                                block.textEditorCodeExecutionToolResult()
                                        .map(
                                                r ->
                                                        ContentBlockParam
                                                                .ofTextEditorCodeExecutionToolResult(
                                                                        r.toParam())))
                .or(
                        () ->
                                block.toolSearchToolResult()
                                        .map(
                                                r ->
                                                        ContentBlockParam.ofToolSearchToolResult(
                                                                r.toParam())));
    }

    /**
     * Converts an Anthropic server tool result response block to an AgentScope content block.
     *
     * @param param the Anthropic server tool result response block
     * @return the server-marked AgentScope tool result
     */
    ToolResultBlock decodeResult(ContentBlockParam param) {
        String toolName = serverToolResultName(param);
        String toolUseId = serverToolResultId(param);
        List<ContentBlock> output = new ArrayList<>();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(ToolResultBlock.METADATA_SERVER_TOOL, true);
        ToolResultState state = ToolResultState.SUCCESS;

        try {
            String json = ObjectMappers.jsonMapper().writeValueAsString(param);
            metadata.put(RESULT_METADATA, json);

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
            } else if (param.isWebSearchToolResult() && content.isArray()) {
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

    private static String serverToolResultName(ContentBlockParam param) {
        if (param.isWebSearchToolResult()) {
            return "web_search";
        }
        if (param.isWebFetchToolResult()) {
            return "web_fetch";
        }
        if (param.isCodeExecutionToolResult()) {
            return "code_execution";
        }
        if (param.isBashCodeExecutionToolResult()) {
            return "bash_code_execution";
        }
        if (param.isTextEditorCodeExecutionToolResult()) {
            return "text_editor_code_execution";
        }
        if (param.isToolSearchToolResult()) {
            return "tool_search";
        }
        throw new IllegalArgumentException("ContentBlockParam is not a server tool result");
    }

    private static String serverToolResultId(ContentBlockParam param) {
        if (param.isWebSearchToolResult()) {
            return param.asWebSearchToolResult().toolUseId();
        }
        if (param.isWebFetchToolResult()) {
            return param.asWebFetchToolResult().toolUseId();
        }
        if (param.isCodeExecutionToolResult()) {
            return param.asCodeExecutionToolResult().toolUseId();
        }
        if (param.isBashCodeExecutionToolResult()) {
            return param.asBashCodeExecutionToolResult().toolUseId();
        }
        if (param.isTextEditorCodeExecutionToolResult()) {
            return param.asTextEditorCodeExecutionToolResult().toolUseId();
        }
        if (param.isToolSearchToolResult()) {
            return param.asToolSearchToolResult().toolUseId();
        }
        throw new IllegalArgumentException("ContentBlockParam is not a server tool result");
    }
}
