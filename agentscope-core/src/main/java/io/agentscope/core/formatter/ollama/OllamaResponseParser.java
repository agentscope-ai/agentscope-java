/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.formatter.ollama;

import io.agentscope.core.formatter.ollama.dto.OllamaFunction;
import io.agentscope.core.formatter.ollama.dto.OllamaMessage;
import io.agentscope.core.formatter.ollama.dto.OllamaResponse;
import io.agentscope.core.formatter.ollama.dto.OllamaToolCall;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.util.JsonUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Parser for converting OllamaResponse objects to AgentScope ChatResponse objects.
 *
 */
public class OllamaResponseParser {

    /**
     * Parses an OllamaResponse object into a ChatResponse object.
     *
     * @param response The OllamaResponse to parse.
     * @return The parsed ChatResponse.
     */
    public ChatResponse parseResponse(OllamaResponse response) {
        OllamaMessage msg = response.getMessage();

        List<ContentBlock> contentBlocks = new ArrayList<>();

        // 1. Handle Text Content
        if (msg != null && msg.getContent() != null && !msg.getContent().isEmpty()) {
            contentBlocks.add(TextBlock.builder().text(msg.getContent()).build());
        }

        // 2. Handle Tool Calls
        if (msg != null) {
            List<OllamaToolCall> toolCalls = msg.getToolCalls();
            if (toolCalls != null && !toolCalls.isEmpty()) {
                for (OllamaToolCall toolCall : toolCalls) {
                    OllamaFunction fn = toolCall.getFunction();
                    if (fn != null) {
                        Map<String, Object> input = fn.getArguments();
                        // ToolUseBlock needs an ID. Ollama might not return ID in non-streaming?
                        // Or we generate one. OpenAI generates IDs.
                        // If Ollama doesn't provide ID, we generate a random UUID to satisfy
                        // AgentScope requirement.
                        String callId = UUID.randomUUID().toString();

                        // Convert input to JSON string for validation in ToolExecutor
                        // For tools with no parameters, input will be null or an empty map {}
                        String argumentsJson;
                        if (input == null || input.isEmpty()) {
                            argumentsJson = "{}";
                        } else {
                            argumentsJson = JsonUtils.getJsonCodec().toJson(input);
                        }
                        contentBlocks.add(
                                new ToolUseBlock(callId, fn.getName(), input, argumentsJson, null));
                    }
                }
            }
        }

        // 3. Map Usage
        int inputTokens = response.getPromptEvalCount() != null ? response.getPromptEvalCount() : 0;
        int outputTokens = response.getEvalCount() != null ? response.getEvalCount() : 0;
        // Ollama durations are in nanoseconds, convert to seconds
        double time = response.getTotalDuration() != null ? response.getTotalDuration() / 1e9 : 0.0;

        ChatUsage usage =
                ChatUsage.builder()
                        .inputTokens(inputTokens)
                        .outputTokens(outputTokens)
                        .time(time)
                        .build();

        // 4. Map Metadata
        Map<String, Object> metadata = new HashMap<>();
        if (response.getModel() != null) metadata.put("model", response.getModel());
        if (response.getCreatedAt() != null) metadata.put("created_at", response.getCreatedAt());
        if (response.getTotalDuration() != null)
            metadata.put("total_duration", response.getTotalDuration());
        if (response.getLoadDuration() != null)
            metadata.put("load_duration", response.getLoadDuration());
        if (response.getPromptEvalCount() != null)
            metadata.put("prompt_eval_count", response.getPromptEvalCount());
        if (response.getPromptEvalDuration() != null)
            metadata.put("prompt_eval_duration", response.getPromptEvalDuration());
        if (response.getEvalCount() != null) metadata.put("eval_count", response.getEvalCount());
        if (response.getEvalDuration() != null)
            metadata.put("eval_duration", response.getEvalDuration());
        if (response.getDone() != null) metadata.put("done", response.getDone());

        // Create ChatResponse using Builder
        ChatResponse.Builder builder =
                ChatResponse.builder().content(contentBlocks).usage(usage).metadata(metadata);

        if (response.getDoneReason() != null) {
            builder.finishReason(response.getDoneReason());
        } else if (response.getDone() != null && response.getDone()) {
            builder.finishReason("stop"); // Fallback if doneReason is missing but done is true
        }

        return builder.build();
    }
}
