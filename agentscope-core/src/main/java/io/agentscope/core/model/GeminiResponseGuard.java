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
package io.agentscope.core.model;

import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;

/**
 * Guards Gemini responses by ensuring non-empty meaningful content and applying
 * finish-reason-based fallback behavior.
 */
final class GeminiResponseGuard {

    // Expected finish reasons aligned with Google GenAI SDK
    // See: java-genai/src/main/java/com/google/genai/types/GenerateContentResponse.java
    private static final Set<String> EXPECTED_FINISH_REASONS =
            Set.of(
                    "FINISH_REASON_UNSPECIFIED",
                    "STOP",
                    "MAX_TOKENS",
                    "END_TURN" // AgentScope-specific for streaming compatibility
                    );

    private final String modelName;
    private final Logger log;

    GeminiResponseGuard(String modelName, Logger log) {
        this.modelName = modelName;
        this.log = log;
    }

    ChatResponse ensureMeaningfulContent(ChatResponse response) {
        if (response == null || response.getContent() == null) {
            return response;
        }

        boolean hasText =
                response.getContent().stream()
                        .anyMatch(
                                block ->
                                        (block instanceof TextBlock textBlock
                                                        && textBlock.getText() != null
                                                        && !textBlock.getText().isBlank())
                                                || (block instanceof ThinkingBlock thinkingBlock
                                                        && thinkingBlock.getThinking() != null
                                                        && !thinkingBlock.getThinking().isBlank())
                                                || block instanceof ToolUseBlock);

        if (hasText) {
            return response;
        }

        // Check finish reason against expected values (aligned with GenAI SDK pattern)
        // Expected finish reasons indicate normal completion: STOP, MAX_TOKENS, etc.
        // Unexpected finish reasons may indicate API issues that should be retried.
        String finishReason = response.getFinishReason();
        if (finishReason == null
                || finishReason.isEmpty()
                || EXPECTED_FINISH_REASONS.contains(finishReason)) {
            // Normal completion or streaming chunk - don't add fallback text or retry.
            return response;
        }

        // Intentionally broad match for the Gemini 3 family, including gemini-3.x variants.
        // For Gemini 3 models, throw exception on problematic finish reasons
        // to trigger retry logic (workaround for API instability).
        if (modelName.toLowerCase().contains("gemini-3")) {
            // Retry on MALFORMED_FUNCTION_CALL (mainly for tool calls)
            if (finishReason.equals("MALFORMED_FUNCTION_CALL")) {
                log.warn("Gemini 3 model returned MALFORMED_FUNCTION_CALL, will trigger retry");
                throw new ModelException(
                        "Gemini returned empty content (finishReason: MALFORMED_FUNCTION_CALL)");
            }

            // Also retry on other error finish reasons that result in empty content
            // This handles cases like multi-round conversations returning empty responses.
            if (finishReason.equals("SAFETY")
                    || finishReason.equals("RECITATION")
                    || finishReason.equals("OTHER")) {
                log.warn(
                        "Gemini 3 model returned empty content with finishReason: {}, will"
                                + " trigger retry",
                        finishReason);
                throw new ModelException(
                        "Gemini returned empty content (finishReason: " + finishReason + ")");
            }
        }

        // For other unexpected finish reasons, log a warning and add fallback text.
        log.warn(
                "Gemini returned unexpected finishReason: {}. Expected one of: {}",
                finishReason,
                EXPECTED_FINISH_REASONS);
        String fallback = "Gemini returned empty content (finishReason: " + finishReason + ")";

        List<ContentBlock> newBlocks = new ArrayList<>(response.getContent());
        newBlocks.add(TextBlock.builder().text(fallback).build());

        return ChatResponse.builder()
                .id(response.getId())
                .content(newBlocks)
                .usage(response.getUsage())
                .finishReason(response.getFinishReason())
                .metadata(response.getMetadata())
                .build();
    }
}
