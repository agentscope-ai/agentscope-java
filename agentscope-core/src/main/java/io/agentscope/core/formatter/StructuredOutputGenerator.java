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
import java.util.function.Function;

/**
 * Generate-and-validate loop for structured outputs with
 * <em>error-feedback retry</em>.
 *
 * <p>This implements the industry-standard remediation pattern (used by
 * Instructor, Guardrails re-ask and Spring AI 2.0 self-correcting structured
 * output): when the model output fails schema validation, the validation
 * errors are fed back into the next generation attempt instead of blindly
 * retrying.
 *
 * <p>Usage:
 * <pre>{@code
 * JsonNode payload = StructuredOutputGenerator.generateWithRetry(
 *     errors -> {
 *         String prompt = errors.isEmpty()
 *             ? originalPrompt
 *             : originalPrompt + StructuredOutputGenerator.retryPrompt(errors);
 *         return model.generate(prompt);
 *     },
 *     schema,
 *     3);
 * }</pre>
 */
public final class StructuredOutputGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StructuredOutputGenerator() {}

    /**
     * Generates a model output and validates it against the schema, retrying
     * with error feedback until the output conforms or retries are exhausted.
     *
     * @param generate   generation callback; receives the validation errors of
     *                   the previous attempt (empty list on first call) and
     *                   returns the raw model output text
     * @param schema     the JSON Schema to validate against
     * @param maxRetries maximum number of generation attempts (>= 1)
     * @return the parsed, schema-conforming JSON payload
     * @throws StructuredOutputValidationException when all attempts fail
     */
    public static JsonNode generateWithRetry(
            Function<List<StructuredOutputValidator.ValidationError>, String> generate,
            JsonSchema schema,
            int maxRetries) {
        if (generate == null || schema == null || maxRetries < 1) {
            throw new IllegalArgumentException("generate, schema and maxRetries(>=1) are required");
        }
        List<StructuredOutputValidator.ValidationError> lastErrors = List.of();
        for (int attempt = 1; ; attempt++) {
            String raw = generate.apply(lastErrors);
            List<StructuredOutputValidator.ValidationError> errors;
            try {
                JsonNode payload = extractJsonObject(raw);
                errors = StructuredOutputValidator.validate(payload, schema);
                if (errors.isEmpty()) {
                    return payload;
                }
            } catch (StructuredOutputParseException parseException) {
                errors = List.of(parseErrorAsValidationError(parseException.getMessage()));
            }
            if (attempt >= maxRetries) {
                throw new StructuredOutputValidationException(schema.getName(), errors);
            }
            lastErrors = errors;
        }
    }

    /**
     * Wraps a parse failure as a validation error so the correction prompt
     * keeps the actionable {@code path: message} shape.
     */
    static StructuredOutputValidator.ValidationError parseErrorAsValidationError(
            String parseErrorMessage) {
        return new StructuredOutputValidator.ValidationError(
                "$",
                "output must be a valid JSON object (could not be parsed): " + parseErrorMessage);
    }

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
     *     found (fail-closed: never synthesizes a payload from the raw text)
     */
    static JsonNode extractJsonObject(String raw) {
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
        // P1-2（维护者评审）：fail-closed——提取不到 JSON 时抛出 ParseException 进入重试，
        // 绝不合成 {"result": raw}（合成会让宽松 schema 静默放行非结构化文本，绕过整个保证）
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
                    // a later candidate instead of giving up (review feedback)
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
