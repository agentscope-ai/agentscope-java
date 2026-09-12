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
package io.agentscope.core.formatter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;

/**
 * Extraction and error-feedback helpers for structured outputs.
 *
 * <p>{@code ReActAgent}'s native structured-output validation loop uses these
 * to turn raw model text into a JSON payload and to build the correction
 * prompt fed back to the model on failed attempts (the industry-standard
 * remediation pattern, cf. Instructor, Guardrails re-ask and Spring AI 2.0
 * self-correcting structured output).
 */
public final class StructuredOutputUtils {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StructuredOutputUtils() {}

    /**
     * Builds a prompt fragment that feeds validation errors back to the model
     * for correction (the industry-standard remediation pattern).
     *
     * @param errors the validation errors of the previous attempt
     * @return a prompt fragment to append to the original prompt
     */
    public static String retryPrompt(List<StructuredOutputValidator.ValidationError> errors) {
        if (errors == null || errors.isEmpty()) {
            return "";
        }
        StringBuilder sb =
                new StringBuilder(
                        "\n\nYour previous output failed JSON Schema validation."
                                + " Output the corrected JSON only, without explanation:");
        int shown = 0;
        for (StructuredOutputValidator.ValidationError error : errors) {
            if (shown++ >= 5) {
                sb.append("\n- ... and ").append(errors.size()).append(" errors in total");
                break;
            }
            sb.append("\n- ").append(error.instanceLocation()).append(": ").append(error.message());
        }
        return sb.toString();
    }

    /**
     * Parses a JSON object from raw model output: strips a markdown code fence
     * (if any), then reads the text directly. Leading prose is tolerated by
     * retrying from the first '{'.
     *
     * @throws StructuredOutputParseException when the output is not valid JSON
     *     (fail-closed: never synthesizes a payload from the raw text —
     *     synthesizing one would let a lenient schema pass unstructured text
     *     through and bypass the guarantee entirely)
     */
    public static JsonNode extractJsonObject(String raw) {
        String text = stripCodeFence(raw == null ? "" : raw.trim());
        try {
            return MAPPER.readTree(text);
        } catch (JsonProcessingException e) {
            // Tolerate leading prose such as "答案是：{...}": retry from the first '{'.
            // Jackson already ignores trailing content after the first value.
            int start = text.indexOf('{');
            if (start > 0) {
                try {
                    return MAPPER.readTree(text.substring(start));
                } catch (JsonProcessingException ignored) {
                    // fall through and report the original error
                }
            }
            throw new StructuredOutputParseException(
                    "output is not valid JSON: " + e.getOriginalMessage());
        }
    }

    /**
     * Converts a parsed JSON payload to a plain Java object tree (maps / lists / values),
     * as stored in message metadata for downstream consumers.
     */
    public static Object toPlainObject(JsonNode node) {
        return MAPPER.convertValue(node, Object.class);
    }

    private static String stripCodeFence(String text) {
        if (!text.startsWith("```")) {
            return text;
        }
        int firstBreak = text.indexOf('\n');
        if (firstBreak < 0) {
            return text; // malformed fence — let readTree report it
        }
        String body = text.substring(firstBreak + 1);
        int closing = body.lastIndexOf("```");
        return (closing >= 0 ? body.substring(0, closing) : body).trim();
    }
}
