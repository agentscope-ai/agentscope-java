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

import java.util.Map;
import java.util.Objects;

/**
 * Shared interpretation of the free-form finish reason carried by {@link ChatResponse}.
 *
 * <p>Providers report termination with raw strings (for example {@code "stop"}, {@code "length"},
 * {@code "MAX_TOKENS"}), and some report no reason at all. This helper identifies the one case
 * callers must treat as incomplete output: the provider explicitly stopped at an output-token
 * limit. A missing or unrecognized reason never indicates truncation, so callers keep accepting
 * providers that do not report one.
 */
public final class FinishReasons {

    private FinishReasons() {}

    /**
     * Returns whether the reported termination means the output was cut at an output-token limit.
     *
     * @param finishReason     the last reported finish reason, or {@code null} when none was
     * @param responseMetadata response metadata accompanying that report, or {@code null}
     */
    public static boolean indicatesOutputLimit(
            String finishReason, Map<String, Object> responseMetadata) {
        // OpenAI Chat Completions and DashScope report "length"; Anthropic and Gemini report
        // "max_tokens" or "MAX_TOKENS".
        if ("length".equalsIgnoreCase(finishReason)
                || "max_tokens".equalsIgnoreCase(finishReason)) {
            return true;
        }
        // The OpenAI Responses API reports "incomplete" and names the cause in metadata; only the
        // output-token cause means truncation (for example a content filter does not).
        if (!"incomplete".equalsIgnoreCase(finishReason)) {
            return false;
        }
        String incompleteReason =
                Objects.toString(
                        responseMetadata == null
                                ? null
                                : responseMetadata.get("openai.response.incomplete_reason"),
                        null);
        return "max_output_tokens".equalsIgnoreCase(incompleteReason);
    }
}
