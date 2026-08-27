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
            JsonNode payload = extractJsonObject(raw);
            lastErrors = StructuredOutputValidator.validate(payload, schema);
            if (lastErrors.isEmpty()) {
                return payload;
            }
            if (attempt >= maxRetries) {
                throw new StructuredOutputValidationException(schema.getName(), lastErrors);
            }
        }
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
        StringBuilder sb = new StringBuilder("\n\n你的上一次输出未通过 JSON Schema 校验，请修正后重新输出，不要解释：");
        int shown = 0;
        for (StructuredOutputValidator.ValidationError error : errors) {
            if (shown++ >= 5) {
                sb.append("\n- ... 等").append(errors.size()).append(" 处错误");
                break;
            }
            sb.append("\n- ").append(error.instanceLocation()).append(": ").append(error.message());
        }
        return sb.toString();
    }

    /**
     * Extracts a JSON object from raw model output: strips markdown code
     * fences and leading prose, then performs brace matching from the first
     * '{'. Falls back to the raw text when no JSON object can be found.
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
        int start = text.indexOf('{');
        if (start < 0) {
            return MAPPER.createObjectNode().put("result", raw == null ? "" : raw);
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
                    return MAPPER.createObjectNode().put("result", text.substring(start, i + 1));
                }
            }
        }
        try {
            return MAPPER.readTree(text.substring(start));
        } catch (Exception e) {
            return MAPPER.createObjectNode().put("result", text.substring(start));
        }
    }
}
