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
public final class StructuredOutputGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StructuredOutputGenerator() {}

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
     * Extracts a JSON object from raw model output: strips markdown code
     * fences and leading prose, then performs brace matching from the first
     * '{'. If a balanced candidate is not valid JSON, scanning continues with
     * the next candidate instead of giving up.
     *
     * @throws StructuredOutputParseException when no valid JSON object can be
     *     found (fail-closed: never synthesizes a payload from the raw text —
     *     synthesizing one would let a lenient schema pass unstructured text
     *     through and bypass the guarantee entirely)
     */
    public static JsonNode extractJsonObject(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```")) {
            int firstBreak = text.indexOf('\n');
            if (firstBreak > 0) {
                text = text.substring(firstBreak + 1);
            }
            int closingFence = text.lastIndexOf("```");
            if (closingFence >= 0) {
                text = text.substring(0, closingFence).trim();
            }
        }
        int start = text.indexOf('{');
        if (start < 0) {
            throw new StructuredOutputParseException("no JSON object found in model output");
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                try {
                    return MAPPER.readTree(text.substring(start, i + 1));
                } catch (Exception e) {
                    // This balanced candidate is not valid JSON — keep scanning for
                    // a later candidate instead of giving up.
                    start = text.indexOf('{', i + 1);
                    if (start < 0) {
                        throw new StructuredOutputParseException(
                                "no valid JSON object found in model output");
                    }
                    i = start - 1;
                    depth = 0;
                    inString = false;
                    escaped = false;
                }
            }
        }
        throw new StructuredOutputParseException("unbalanced braces in model output");
    }
}
