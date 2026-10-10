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
package io.agentscope.extensions.model.gemini.formatter;

import com.google.genai.JsonSerializable;
import com.google.genai.types.Candidate;
import com.google.genai.types.CodeExecutionResult;
import com.google.genai.types.Content;
import com.google.genai.types.ExecutableCode;
import com.google.genai.types.FunctionCall;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Outcome;
import com.google.genai.types.Part;
import com.google.genai.types.ToolCall;
import com.google.genai.types.ToolResponse;
import com.google.genai.types.ToolType;
import io.agentscope.core.formatter.FormatterException;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.tool.ToolValidator;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses Gemini API responses to AgentScope ChatResponse.
 *
 * <p>This parser handles the conversion of Gemini's GenerateContentResponse to AgentScope's
 * ChatResponse format, including:
 * <ul>
 *   <li>Text blocks from text parts</li>
 *   <li>Thinking blocks from parts with thought=true flag</li>
 *   <li>Tool use blocks from function_call parts (local function calls)</li>
 *   <li>Tool use and tool result blocks from tool_call/tool_response parts (server-side
 *       built-in tools)</li>
 *   <li>Usage metadata with token counts</li>
 * </ul>
 *
 * <p><b>Important:</b> In Gemini API, thinking content is indicated by the "thought" flag
 * on Part objects.
 */
public class GeminiResponseParser {

    private static final Logger log = LoggerFactory.getLogger(GeminiResponseParser.class);

    /** Metadata key carrying Gemini grounding metadata on the final message. */
    public static final String METADATA_GROUNDING = "gemini.groundingMetadata";

    /** Metadata key carrying Gemini URL context metadata on the final message. */
    public static final String METADATA_URL_CONTEXT = "gemini.urlContextMetadata";

    /** Metadata key marking a tool block as Gemini code execution. */
    static final String METADATA_CODE_EXECUTION = "gemini.codeExecution";

    /** Metadata key carrying the Gemini code-execution outcome. */
    static final String METADATA_CODE_EXECUTION_OUTCOME = "gemini.codeExecution.outcome";

    /** Metadata key preserving the raw Gemini server tool response. */
    public static final String METADATA_SERVER_TOOL_RESPONSE = "gemini.serverToolResponse";

    /** Metadata key preserving the raw Gemini code-execution result. */
    public static final String METADATA_CODE_EXECUTION_RESULT = "gemini.codeExecutionResult";

    /**
     * Creates a new GeminiResponseParser.
     */
    public GeminiResponseParser() {}

    /**
     * Parse Gemini GenerateContentResponse to AgentScope ChatResponse.
     *
     * @param response Gemini generation response
     * @param startTime Request start time for calculating duration
     * @return AgentScope ChatResponse
     */
    public ChatResponse parseResponse(GenerateContentResponse response, Instant startTime) {
        try {
            List<ContentBlock> blocks = new ArrayList<>();
            String finishReason = null;
            Map<String, Object> responseMetadata = new LinkedHashMap<>();

            // Parse content from first candidate
            if (response.candidates().isPresent() && !response.candidates().get().isEmpty()) {
                Candidate candidate = response.candidates().get().get(0);

                if (candidate.content().isPresent()) {
                    Content content = candidate.content().get();

                    if (content.parts().isPresent()) {
                        List<Part> parts = content.parts().get();
                        parsePartsToBlocks(parts, blocks);
                    }
                }
                finishReason = candidate.finishMessage().orElse(null);

                candidate
                        .groundingMetadata()
                        .ifPresent(
                                metadata ->
                                        responseMetadata.put(METADATA_GROUNDING, toMap(metadata)));
                candidate
                        .urlContextMetadata()
                        .ifPresent(
                                metadata ->
                                        responseMetadata.put(
                                                METADATA_URL_CONTEXT, toMap(metadata)));
            }

            // Parse usage metadata
            ChatUsage usage = null;
            if (response.usageMetadata().isPresent()) {
                GenerateContentResponseUsageMetadata metadata = response.usageMetadata().get();

                int inputTokens =
                        metadata.promptTokenCount().orElse(0)
                                + metadata.toolUsePromptTokenCount().orElse(0);
                int cachedTokens = metadata.cachedContentTokenCount().orElse(0);
                int thinkingTokens = metadata.thoughtsTokenCount().orElse(0);
                int toolUsePromptTokens = metadata.toolUsePromptTokenCount().orElse(0);
                int outputTokens = metadata.candidatesTokenCount().orElse(0) + thinkingTokens;

                usage =
                        ChatUsage.builder()
                                .inputTokens(inputTokens)
                                .outputTokens(outputTokens)
                                .cachedTokens(cachedTokens)
                                .toolUsePromptTokens(toolUsePromptTokens)
                                .reasoningTokens(thinkingTokens)
                                .time(
                                        Duration.between(startTime, Instant.now()).toMillis()
                                                / 1000.0)
                                .build();
            }

            return ChatResponse.builder()
                    .id(response.responseId().orElse(null))
                    .content(blocks)
                    .usage(usage)
                    .metadata(responseMetadata.isEmpty() ? null : responseMetadata)
                    .finishReason(finishReason)
                    .build();

        } catch (Exception e) {
            log.error("Failed to parse Gemini response: {}", e.getMessage(), e);
            throw new FormatterException("Failed to parse Gemini response: " + e.getMessage(), e);
        }
    }

    /**
     * Parse Gemini Part objects to AgentScope ContentBlocks.
     * Order of block types: ThinkingBlock, TextBlock, ToolUseBlock, ToolResultBlock
     *
     * @param parts List of Gemini Part objects
     * @param blocks List to add parsed ContentBlocks to
     */
    protected void parsePartsToBlocks(List<Part> parts, List<ContentBlock> blocks) {
        String pendingCodeExecutionId = null;

        for (Part part : parts) {
            // Check for thinking content first (parts with thought=true flag)
            if (part.thought().isPresent() && part.thought().get() && part.text().isPresent()) {
                String thinkingText = part.text().get();
                if (thinkingText != null && !thinkingText.isEmpty()) {
                    blocks.add(ThinkingBlock.builder().thinking(thinkingText).build());
                }
                continue;
            }

            // Check for text content
            if (part.text().isPresent()) {
                String text = part.text().get();
                if (text != null && !text.isEmpty()) {
                    blocks.add(TextBlock.builder().text(text).build());
                }
            }

            // Gemini code execution uses dedicated parts rather than ToolCall/ToolResponse.
            if (part.executableCode().isPresent()) {
                ExecutableCode executableCode = part.executableCode().get();
                byte[] thoughtSignature = part.thoughtSignature().orElse(null);
                ToolUseBlock codeBlock =
                        parseExecutableCode(
                                executableCode, thoughtSignature, pendingCodeExecutionId);
                blocks.add(codeBlock);
                pendingCodeExecutionId = codeBlock.getId();
            }

            if (part.codeExecutionResult().isPresent()) {
                CodeExecutionResult codeExecutionResult = part.codeExecutionResult().get();
                byte[] thoughtSignature = part.thoughtSignature().orElse(null);
                blocks.add(
                        parseCodeExecutionResult(
                                codeExecutionResult, thoughtSignature, pendingCodeExecutionId));
                pendingCodeExecutionId = null;
            }

            // Check for function call (tool use)
            if (part.functionCall().isPresent()) {
                FunctionCall functionCall = part.functionCall().get();
                byte[] thoughtSignature = part.thoughtSignature().orElse(null);
                parseToolCall(functionCall, thoughtSignature, blocks);
            }

            // Check for tool call (server tool use)
            if (part.toolCall().isPresent()) {
                ToolCall toolCall = part.toolCall().get();
                byte[] thoughtSignature = part.thoughtSignature().orElse(null);
                parseServerToolCall(toolCall, thoughtSignature, blocks);
            }

            // Check for tool response (server tool result)
            if (part.toolResponse().isPresent()) {
                ToolResponse toolResponse = part.toolResponse().get();
                byte[] thoughtSignature = part.thoughtSignature().orElse(null);
                parseServerToolResponse(toolResponse, thoughtSignature, blocks);
            }
        }
    }

    /**
     * Parses a Gemini executable-code part into a server-tool use block.
     *
     * @param executableCode Gemini executable-code part
     * @param thoughtSignature Thought signature from the Part, or null
     * @param fallbackId ID of the matching executable-code call when the part has no ID
     * @return Tool-use block representing generated code
     */
    private ToolUseBlock parseExecutableCode(
            ExecutableCode executableCode, byte[] thoughtSignature, String fallbackId) {
        String id =
                executableCode
                        .id()
                        .orElseGet(
                                () ->
                                        fallbackId != null
                                                ? fallbackId
                                                : "code_execution_" + System.currentTimeMillis());

        Map<String, Object> input = new HashMap<>();
        executableCode
                .language()
                .map(language -> language.toString())
                .ifPresent(language -> input.put("language", language));
        executableCode.code().ifPresent(code -> input.put("code", code));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ToolUseBlock.METADATA_SERVER_TOOL, true);
        metadata.put(METADATA_CODE_EXECUTION, true);
        if (thoughtSignature != null) {
            metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, thoughtSignature);
        }

        return ToolUseBlock.builder()
                .id(id)
                .name("CODE_EXECUTION")
                .input(input)
                .content(input.isEmpty() ? null : JsonUtils.getJsonCodec().toJson(input))
                .metadata(metadata)
                .state(ToolCallState.FINISHED)
                .build();
    }

    /**
     * Parses a Gemini code-execution result into a server-tool result block.
     *
     * @param codeExecutionResult Gemini code-execution result part
     * @param thoughtSignature Thought signature from the Part, or null
     * @param fallbackId ID of the matching executable-code call when the result has no ID
     * @return Tool-result block representing the execution result
     */
    private ToolResultBlock parseCodeExecutionResult(
            CodeExecutionResult codeExecutionResult, byte[] thoughtSignature, String fallbackId) {
        String id =
                codeExecutionResult
                        .id()
                        .orElseGet(
                                () ->
                                        fallbackId != null
                                                ? fallbackId
                                                : "code_execution_" + System.currentTimeMillis());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ToolResultBlock.METADATA_SERVER_TOOL, true);
        metadata.put(METADATA_CODE_EXECUTION, true);
        metadata.put(METADATA_CODE_EXECUTION_RESULT, codeExecutionResult.toJson());
        codeExecutionResult
                .outcome()
                .map(outcome -> outcome.toString())
                .ifPresent(outcome -> metadata.put(METADATA_CODE_EXECUTION_OUTCOME, outcome));
        if (thoughtSignature != null) {
            metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, thoughtSignature);
        }

        ToolResultState resultState =
                codeExecutionResult
                        .outcome()
                        .map(GeminiResponseParser::codeExecutionResultState)
                        .orElse(ToolResultState.SUCCESS);

        ToolResultBlock.Builder builder =
                ToolResultBlock.builder()
                        .id(id)
                        .name("CODE_EXECUTION")
                        .state(resultState)
                        .metadata(metadata);
        codeExecutionResult
                .output()
                .ifPresent(output -> builder.output(TextBlock.builder().text(output).build()));

        return builder.build();
    }

    private static ToolResultState codeExecutionResultState(Outcome outcome) {
        return switch (outcome.knownEnum()) {
            case OUTCOME_FAILED, OUTCOME_DEADLINE_EXCEEDED -> ToolResultState.ERROR;
            default -> ToolResultState.SUCCESS;
        };
    }

    /**
     * Parse Gemini FunctionCall to ToolUseBlock.
     *
     * @param functionCall Gemini FunctionCall object
     * @param thoughtSignature Thought signature from the Part (may be null)
     * @param blocks List to add parsed ToolUseBlock to
     */
    protected void parseToolCall(
            FunctionCall functionCall, byte[] thoughtSignature, List<ContentBlock> blocks) {
        try {
            String id = functionCall.id().orElse("tool_call_" + System.currentTimeMillis());
            String name = functionCall.name().orElse("");

            if (!ToolValidator.requireNonBlank("Gemini", name, id)) {
                return;
            }

            blocks.add(
                    convertToolUseBlock(
                            id,
                            name,
                            functionCall.args().orElse(null),
                            thoughtSignature,
                            null,
                            false));
        } catch (Exception e) {
            log.warn("Failed to parse function call: {}", e.getMessage(), e);
        }
    }

    /**
     * Parse Gemini ToolCall to ToolUseBlock for server-side (built-in) tools.
     *
     * @param toolCall         Gemini ToolCall object
     * @param thoughtSignature Thought signature from the Part (may be null)
     * @param blocks           List to add parsed ToolUseBlock to
     */
    protected void parseServerToolCall(
            ToolCall toolCall, byte[] thoughtSignature, List<ContentBlock> blocks) {
        try {
            String id = toolCall.id().orElse("tool_call_" + System.currentTimeMillis());
            String name = toolCall.toolType().map(ToolType::toString).orElse("");

            if (!ToolValidator.requireNonBlank("Gemini", name, id)) {
                return;
            }
            blocks.add(
                    convertToolUseBlock(
                            id,
                            name,
                            toolCall.args().orElse(null),
                            thoughtSignature,
                            ToolCallState.FINISHED,
                            true));
        } catch (Exception e) {
            log.warn("Failed to parse tool call: {}", e.getMessage(), e);
        }
    }

    /**
     * Converts a Gemini tool invocation to a ToolUseBlock.
     *
     * @param id               Tool call ID
     * @param name             Tool name (function name or tool type)
     * @param args             Tool arguments map (may be null)
     * @param thoughtSignature Thought signature from the Part (may be null)
     * @param server           Whether the tool is executed by the model provider server-side
     * @return A new ToolUseBlock
     */
    protected ToolUseBlock convertToolUseBlock(
            String id,
            String name,
            Map<String, Object> args,
            byte[] thoughtSignature,
            ToolCallState state,
            boolean server) {
        // Parse arguments
        Map<String, Object> argsMap = new HashMap<>();
        String rawContent = null;

        if (args != null && !args.isEmpty()) {
            argsMap.putAll(args);
            // Convert to JSON string for raw content
            try {
                rawContent = JsonUtils.getJsonCodec().toJson(args);
            } catch (Exception e) {
                log.warn("Failed to serialize function call arguments: {}", e.getMessage());
            }
        }

        // Build metadata with provider flags and optional thought signature
        Map<String, Object> metadata = new HashMap<>();
        if (server) {
            metadata.put(ToolUseBlock.METADATA_SERVER_TOOL, true);
        }
        if (thoughtSignature != null) {
            metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, thoughtSignature);
        }

        return ToolUseBlock.builder()
                .id(id)
                .name(name)
                .input(argsMap)
                .content(rawContent)
                .metadata(metadata.isEmpty() ? null : metadata)
                .state(state)
                .build();
    }

    /**
     * Parse Gemini ToolResponse to ToolResultBlock for server-side (built-in) tools.
     *
     * @param toolResponse     Gemini ToolResponse object
     * @param thoughtSignature Thought signature from the Part (may be null)
     * @param blocks           List to add parsed ToolResultBlock to
     */
    protected void parseServerToolResponse(
            ToolResponse toolResponse, byte[] thoughtSignature, List<ContentBlock> blocks) {
        try {
            String id = toolResponse.id().orElse("tool_call_" + System.currentTimeMillis());
            String name = toolResponse.toolType().map(ToolType::toString).orElse("");

            if (!ToolValidator.requireNonBlank("Gemini", name, id)) {
                return;
            }

            Map<String, Object> metadata = new HashMap<>();
            metadata.put(ToolResultBlock.METADATA_SERVER_TOOL, true);
            metadata.put(METADATA_SERVER_TOOL_RESPONSE, toolResponse.toJson());
            if (thoughtSignature != null) {
                metadata.put(ToolUseBlock.METADATA_THOUGHT_SIGNATURE, thoughtSignature);
            }

            ToolResultBlock.Builder toolResultBuilder =
                    ToolResultBlock.builder()
                            .id(id)
                            .name(name)
                            .state(ToolResultState.SUCCESS)
                            .metadata(metadata);

            if (toolResponse.response().isPresent()) {
                toolResultBuilder.output(
                        TextBlock.builder()
                                .text(
                                        JsonUtils.getJsonCodec()
                                                .toJson(toolResponse.response().get()))
                                .build());
            }

            blocks.add(toolResultBuilder.build());
        } catch (Exception e) {
            log.warn("Failed to parse tool response: {}", e.getMessage(), e);
        }
    }

    private static Map<String, Object> toMap(JsonSerializable serializable) {
        try {
            Map<String, Object> result =
                    JsonUtils.getJsonCodec().fromJson(serializable.toJson(), Map.class);
            return result != null ? result : Map.of();
        } catch (Exception e) {
            log.warn("Failed to normalize Gemini metadata: {}", e.getMessage(), e);
            return Map.of();
        }
    }
}
