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
package io.agentscope.core.formatter.openai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.formatter.openai.dto.OpenAIChoice;
import io.agentscope.core.formatter.openai.dto.OpenAIError;
import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIReasoningDetail;
import io.agentscope.core.formatter.openai.dto.OpenAIResponse;
import io.agentscope.core.formatter.openai.dto.OpenAIToolCall;
import io.agentscope.core.formatter.openai.dto.OpenAIUsage;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.exception.OpenAIException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Parses OpenAI HTTP API responses to AgentScope ChatResponse.
 * Handles both non-streaming and streaming (chunk) responses using DTO classes.
 */
public class OpenAIResponseParser {

    private static final Logger log = LoggerFactory.getLogger(OpenAIResponseParser.class);

    /** Placeholder name for tool call argument fragments in streaming responses. */
    protected static final String FRAGMENT_PLACEHOLDER = "__fragment__";

    private final ObjectMapper objectMapper;

    /**
     * Safely get prompt token count from usage, returning 0 if null or invalid.
     *
     * @param usage the OpenAI usage object (may be null)
     * @return the prompt token count or 0
     */
    private long getSafePromptTokens(OpenAIUsage usage) {
        return usage != null && usage.getPromptTokens() != null ? usage.getPromptTokens() : 0;
    }

    /**
     * Safely get completion token count from usage, returning 0 if null or invalid.
     *
     * @param usage the OpenAI usage object (may be null)
     * @return the completion token count or 0
     */
    private long getSafeCompletionTokens(OpenAIUsage usage) {
        return usage != null && usage.getCompletionTokens() != null
                ? usage.getCompletionTokens()
                : 0;
    }

    public OpenAIResponseParser() {
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Parse OpenAI response DTO to AgentScope ChatResponse.
     *
     * @param response OpenAI response DTO
     * @param startTime Request start time for calculating duration
     * @return AgentScope ChatResponse
     */
    public ChatResponse parseResponse(OpenAIResponse response, Instant startTime) {
        if (response.isChunk()) {
            return parseChunkResponse(response, startTime);
        } else {
            return parseCompletionResponse(response, startTime);
        }
    }

    /**
     * Parse OpenAI non-streaming response.
     *
     * @param response OpenAI response DTO
     * @param startTime Request start time
     * @return AgentScope ChatResponse
     */
    protected ChatResponse parseCompletionResponse(OpenAIResponse response, Instant startTime) {
        List<ContentBlock> contentBlocks = new ArrayList<>();
        ChatUsage usage = null;
        String finishReason = null;

        try {
            // Parse usage information
            if (response.getUsage() != null) {
                OpenAIUsage openAIUsage = response.getUsage();
                usage =
                        ChatUsage.builder()
                                .inputTokens((int) getSafePromptTokens(openAIUsage))
                                .outputTokens((int) getSafeCompletionTokens(openAIUsage))
                                .time(
                                        Duration.between(startTime, Instant.now()).toMillis()
                                                / 1000.0)
                                .build();
            }

            // Parse response content
            OpenAIChoice choice = response.getFirstChoice();
            if (choice != null) {
                OpenAIMessage message = choice.getMessage();
                finishReason = choice.getFinishReason();

                if (message != null) {
                    // Order matters! Follow this processing order:
                    // 1. ThinkingBlock first (reasoning_content + reasoning_details)
                    // 2. Then TextBlock (content)
                    // 3. Finally ToolUseBlock (tool_calls)

                    // Parse reasoning details (OpenRouter/Gemini specific)
                    // Collect signatures and text content from reasoning_details
                    Map<String, String> reasoningSignatures = new HashMap<>();
                    Map<String, OpenAIReasoningDetail> reasoningDetailMap = new HashMap<>();
                    List<OpenAIReasoningDetail> reasoningDetails = message.getReasoningDetails();
                    StringBuilder reasoningTextBuilder = new StringBuilder();

                    if (reasoningDetails != null) {
                        for (OpenAIReasoningDetail detail : reasoningDetails) {
                            if (detail.getId() != null) {
                                reasoningDetailMap.put(detail.getId(), detail);
                            }
                            if ("reasoning.encrypted".equals(detail.getType())
                                    && detail.getData() != null) {
                                // Just collect signature, don't create ToolUseBlock
                                if (detail.getId() != null) {
                                    reasoningSignatures.put(detail.getId(), detail.getData());
                                }
                            } else if ("reasoning.text".equals(detail.getType())
                                    && detail.getText() != null) {
                                reasoningTextBuilder.append(detail.getText());
                            } else if ("reasoning.summary".equals(detail.getType())
                                    && detail.getText() != null) {
                                // reasoning.summary also uses the text field
                                reasoningTextBuilder.append(detail.getText());
                            }
                        }
                    }

                    // Parse reasoning/thinking content (for o1 models and OpenRouter)
                    // Combine reasoning_content with text from reasoning_details
                    String reasoningContent = message.getReasoningContent();
                    String reasoningText = reasoningTextBuilder.toString();
                    String thinkingContent =
                            (reasoningContent != null && !reasoningContent.isEmpty())
                                    ? reasoningContent
                                    : reasoningText;

                    // Create ThinkingBlock if we have thinking content or reasoning_details
                    if (!thinkingContent.isEmpty()
                            || (reasoningDetails != null && !reasoningDetails.isEmpty())) {
                        ThinkingBlock.Builder thinkingBuilder = ThinkingBlock.builder();
                        if (!thinkingContent.isEmpty()) {
                            thinkingBuilder.thinking(thinkingContent);
                        }
                        // Store reasoning_details in metadata for later restoration
                        if (reasoningDetails != null && !reasoningDetails.isEmpty()) {
                            Map<String, Object> metadata = new HashMap<>();
                            metadata.put(
                                    ThinkingBlock.METADATA_REASONING_DETAILS, reasoningDetails);
                            thinkingBuilder.metadata(metadata);
                        }
                        contentBlocks.add(thinkingBuilder.build());
                    }

                    // Parse text content
                    String textContent = message.getContentAsString();
                    if (textContent != null && !textContent.isEmpty()) {
                        contentBlocks.add(TextBlock.builder().text(textContent).build());
                    }

                    // Parse tool calls
                    List<OpenAIToolCall> toolCalls = message.getToolCalls();
                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        log.debug(
                                "Tool calls detected in non-stream response: {}", toolCalls.size());

                        for (OpenAIToolCall toolCall : toolCalls) {
                            if (toolCall.getFunction() != null) {
                                try {
                                    String arguments = toolCall.getFunction().getArguments();
                                    String name = toolCall.getFunction().getName();
                                    String toolCallId = toolCall.getId();
                                    String thoughtSignature = toolCall.getThoughtSignature();

                                    // Try to find signature in reasoning details if not present
                                    if (thoughtSignature == null && toolCallId != null) {
                                        thoughtSignature = reasoningSignatures.get(toolCallId);
                                    }

                                    // 防御性检查：确保必要字段不为null
                                    if (name == null) {
                                        log.warn("Tool call has null name, skipping");
                                        continue;
                                    }
                                    if (toolCallId == null) {
                                        toolCallId = "tool_call_" + System.currentTimeMillis();
                                        log.debug(
                                                "Tool call has null id, generated: {}", toolCallId);
                                    }
                                    if (arguments == null) {
                                        arguments = "";
                                    }

                                    log.debug(
                                            "Non-stream tool call: id={}, name={}, arguments={},"
                                                    + " signature={}",
                                            toolCallId,
                                            name,
                                            arguments,
                                            thoughtSignature != null ? "present" : "null");

                                    Map<String, Object> argsMap = new HashMap<>();
                                    if (!arguments.isEmpty()) {
                                        @SuppressWarnings("unchecked")
                                        Map<String, Object> parsed =
                                                objectMapper.readValue(arguments, Map.class);
                                        if (parsed != null) {
                                            argsMap.putAll(parsed);
                                        }
                                    }

                                    Map<String, Object> metadata = new HashMap<>();
                                    if (thoughtSignature != null) {
                                        metadata.put(
                                                ToolUseBlock.METADATA_THOUGHT_SIGNATURE,
                                                thoughtSignature);
                                    }
                                    // Store full reasoning detail for OpenRouter Gemini models
                                    if (toolCallId != null
                                            && reasoningDetailMap.containsKey(toolCallId)) {
                                        metadata.put(
                                                "reasoningDetail",
                                                reasoningDetailMap.get(toolCallId));
                                    }

                                    contentBlocks.add(
                                            ToolUseBlock.builder()
                                                    .id(toolCallId)
                                                    .name(name)
                                                    .input(argsMap)
                                                    .content(arguments)
                                                    .metadata(metadata)
                                                    .build());

                                    log.debug(
                                            "Parsed tool call: id={}, name={}",
                                            toolCall.getId(),
                                            name);
                                } catch (Exception ex) {
                                    if (ex instanceof JsonProcessingException) {
                                        log.warn(
                                                "Failed to parse tool call arguments due to JSON"
                                                        + " error: {}",
                                                ex.getMessage());
                                    } else {
                                        log.warn(
                                                "Failed to parse tool call arguments: {}",
                                                ex.getMessage(),
                                                ex);
                                    }
                                }
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("Failed to parse OpenAI completion response: {}", e.getMessage(), e);
            // Return fallback response with error message
            contentBlocks.add(
                    TextBlock.builder().text("Error parsing response: " + e.getMessage()).build());
        }

        return ChatResponse.builder()
                .id(response.getId())
                .content(contentBlocks)
                .usage(usage)
                .finishReason(finishReason)
                .build();
    }

    /**
     * Parse OpenAI streaming response chunk.
     *
     * @param response OpenAI chunk response DTO
     * @param startTime Request start time
     * @return AgentScope ChatResponse (or null for malformed chunks)
     */
    protected ChatResponse parseChunkResponse(OpenAIResponse response, Instant startTime) {
        // Check for error in streaming response chunk
        if (response.isError()) {
            OpenAIError error = response.getError();
            String errorMessage =
                    error != null && error.getMessage() != null
                            ? error.getMessage()
                            : "Unknown error in streaming response";
            String errorCode = error != null && error.getCode() != null ? error.getCode() : null;
            throw new OpenAIException(
                    "OpenAI API error in streaming response: " + errorMessage,
                    400,
                    errorCode,
                    null);
        }

        List<ContentBlock> contentBlocks = new ArrayList<>();
        ChatUsage usage = null;
        String finishReason = null;

        try {
            // Parse usage information (usually only in the last chunk)
            if (response.getUsage() != null) {
                OpenAIUsage openAIUsage = response.getUsage();
                usage =
                        ChatUsage.builder()
                                .inputTokens(
                                        openAIUsage.getPromptTokens() != null
                                                ? openAIUsage.getPromptTokens()
                                                : 0)
                                .outputTokens(
                                        openAIUsage.getCompletionTokens() != null
                                                ? openAIUsage.getCompletionTokens()
                                                : 0)
                                .time(
                                        Duration.between(startTime, Instant.now()).toMillis()
                                                / 1000.0)
                                .build();
            }

            // Parse chunk content
            OpenAIChoice choice = response.getFirstChoice();
            if (choice != null) {
                OpenAIMessage delta = choice.getDelta();
                finishReason = choice.getFinishReason();

                if (delta != null) {
                    // Order matters! Follow this processing order:
                    // 1. ThinkingBlock first (reasoning_content + reasoning_details)
                    // 2. Then TextBlock (content)
                    // 3. Finally ToolUseBlock (tool_calls)

                    // Parse reasoning details (OpenRouter/Gemini specific)
                    // Collect signatures for tool calls and text content for display
                    Map<String, String> reasoningSignatures = new HashMap<>();
                    Map<Integer, String> reasoningSignaturesByIndex = new HashMap<>();
                    List<OpenAIReasoningDetail> reasoningDetailsList = new ArrayList<>();
                    StringBuilder reasoningTextBuilder = new StringBuilder();

                    List<OpenAIReasoningDetail> reasoningDetails = delta.getReasoningDetails();
                    if (reasoningDetails != null) {
                        reasoningDetailsList.addAll(reasoningDetails);

                        for (OpenAIReasoningDetail detail : reasoningDetails) {
                            // Handle encrypted reasoning (thought signature)
                            if ("reasoning.encrypted".equals(detail.getType())
                                    && detail.getData() != null) {
                                String signature = detail.getData();
                                // Collect signature for linking to tool calls later
                                if (detail.getId() != null) {
                                    reasoningSignatures.put(detail.getId(), signature);
                                }
                                if (detail.getIndex() != null) {
                                    reasoningSignaturesByIndex.put(detail.getIndex(), signature);
                                }
                                // Don't create ToolUseBlock here - signatures will be linked
                                // to actual tool_calls below
                            } else if ("reasoning.text".equals(detail.getType())
                                    && detail.getText() != null) {
                                reasoningTextBuilder.append(detail.getText());
                                log.debug("Received reasoning text: {}", detail.getText());
                            } else if ("reasoning.summary".equals(detail.getType())
                                    && detail.getText() != null) {
                                // reasoning.summary also uses the text field
                                reasoningTextBuilder.append(detail.getText());
                                log.debug("Received reasoning summary: {}", detail.getText());
                            }
                        }
                    }

                    // Parse reasoning/thinking content (for o1 models and OpenRouter)
                    String reasoningContent = delta.getReasoningContent();
                    String reasoningText = reasoningTextBuilder.toString();

                    // Create ThinkingBlock for reasoning content
                    // Priority: reasoningContent > reasoning.text/summary from details
                    if ((reasoningContent != null && !reasoningContent.isEmpty())
                            || !reasoningText.isEmpty()
                            || !reasoningDetailsList.isEmpty()) {
                        String thinkingContent =
                                (reasoningContent != null && !reasoningContent.isEmpty())
                                        ? reasoningContent
                                        : reasoningText;

                        if (!thinkingContent.isEmpty() || !reasoningDetailsList.isEmpty()) {
                            ThinkingBlock.Builder thinkingBuilder = ThinkingBlock.builder();
                            if (!thinkingContent.isEmpty()) {
                                thinkingBuilder.thinking(thinkingContent);
                            }
                            // Store reasoning_details in metadata for later restoration
                            if (!reasoningDetailsList.isEmpty()) {
                                Map<String, Object> metadata = new HashMap<>();
                                metadata.put(
                                        ThinkingBlock.METADATA_REASONING_DETAILS,
                                        reasoningDetailsList);
                                thinkingBuilder.metadata(metadata);
                            }
                            contentBlocks.add(thinkingBuilder.build());
                        }
                    }

                    // Parse text content
                    String textContent = delta.getContentAsString();
                    if (textContent != null && !textContent.isEmpty()) {
                        contentBlocks.add(TextBlock.builder().text(textContent).build());
                    }

                    // Parse tool calls (in streaming, these come incrementally)
                    List<OpenAIToolCall> toolCalls = delta.getToolCalls();
                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        log.debug("Streaming tool calls detected: {}", toolCalls.size());

                        for (OpenAIToolCall toolCall : toolCalls) {
                            if (toolCall.getFunction() != null) {
                                try {
                                    String toolCallId = toolCall.getId();
                                    Integer toolIndex = toolCall.getIndex();
                                    String toolName = toolCall.getFunction().getName();
                                    String arguments = toolCall.getFunction().getArguments();
                                    String thoughtSignature = toolCall.getThoughtSignature();

                                    // Try to find signature in reasoning details if not present
                                    if (thoughtSignature == null) {
                                        if (toolCallId != null) {
                                            thoughtSignature = reasoningSignatures.get(toolCallId);
                                        }
                                        if (thoughtSignature == null && toolIndex != null) {
                                            thoughtSignature =
                                                    reasoningSignaturesByIndex.get(toolIndex);
                                        }
                                    }

                                    if (toolCallId == null) {
                                        toolCallId = "streaming_" + System.currentTimeMillis();
                                    }
                                    if (toolName == null) {
                                        toolName = "";
                                    }
                                    if (arguments == null) {
                                        arguments = "";
                                    }

                                    log.debug(
                                            "Streaming tool call chunk: id={}, name={},"
                                                    + " arguments={}, signature={}",
                                            toolCallId,
                                            toolName,
                                            arguments,
                                            thoughtSignature != null ? "present" : "null");

                                    // For streaming, we get partial tool calls that need to be
                                    // accumulated
                                    if (!toolName.isEmpty()) {
                                        // First chunk with complete metadata (has tool name)
                                        Map<String, Object> argsMap = new HashMap<>();

                                        // Try to parse arguments only if they look complete
                                        if (!arguments.isEmpty()
                                                && arguments.trim().startsWith("{")
                                                && arguments.trim().endsWith("}")) {
                                            try {
                                                @SuppressWarnings("unchecked")
                                                Map<String, Object> parsed =
                                                        objectMapper.readValue(
                                                                arguments, Map.class);
                                                if (parsed != null) {
                                                    argsMap.putAll(parsed);
                                                }
                                            } catch (Exception parseEx) {
                                                log.debug(
                                                        "Partial arguments in streaming (expected):"
                                                                + " {}",
                                                        arguments.length() > 50
                                                                ? arguments.substring(0, 50) + "..."
                                                                : arguments);
                                            }
                                        }

                                        Map<String, Object> metadata = new HashMap<>();
                                        if (thoughtSignature != null) {
                                            metadata.put(
                                                    ToolUseBlock.METADATA_THOUGHT_SIGNATURE,
                                                    thoughtSignature);
                                        }

                                        contentBlocks.add(
                                                ToolUseBlock.builder()
                                                        .id(toolCallId)
                                                        .name(toolName)
                                                        .input(argsMap)
                                                        .content(arguments)
                                                        .metadata(metadata)
                                                        .build());
                                        log.debug(
                                                "Added streaming tool call chunk: id={}, name={}",
                                                toolCallId,
                                                toolName);
                                    } else if (!arguments.isEmpty() || thoughtSignature != null) {
                                        // Subsequent chunks with only argument fragments or just
                                        // signature
                                        Map<String, Object> metadata = new HashMap<>();
                                        if (thoughtSignature != null) {
                                            metadata.put(
                                                    ToolUseBlock.METADATA_THOUGHT_SIGNATURE,
                                                    thoughtSignature);
                                        }

                                        contentBlocks.add(
                                                ToolUseBlock.builder()
                                                        .id("")
                                                        .name(FRAGMENT_PLACEHOLDER)
                                                        .input(new HashMap<>())
                                                        .content(arguments)
                                                        .metadata(metadata)
                                                        .build());
                                        if (!arguments.isEmpty()) {
                                            log.debug(
                                                    "Added argument fragment: {}",
                                                    arguments.substring(
                                                            0, Math.min(30, arguments.length())));
                                        }
                                        if (thoughtSignature != null) {
                                            log.debug("Added thought signature fragment");
                                        }
                                    }
                                } catch (Exception ex) {
                                    log.warn(
                                            "Failed to parse streaming tool call: {}",
                                            ex.getMessage());
                                }
                            }
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("Failed to parse OpenAI chunk response: {}", e.getMessage(), e);
            // For streaming, return null to skip malformed chunks
            return null;
        }

        return ChatResponse.builder()
                .id(response.getId())
                .content(contentBlocks)
                .usage(usage)
                .finishReason(finishReason)
                .build();
    }
}
