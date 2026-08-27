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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class StructuredOutputGeneratorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonSchema schema() {
        return JsonSchema.builder()
                .name("MathResponse")
                .schema(
                        Map.of(
                                "type",
                                "object",
                                "properties",
                                Map.of("answer", Map.of("type", "number")),
                                "required",
                                List.of("answer"),
                                "additionalProperties",
                                false))
                .strict(true)
                .build();
    }

    @Test
    void passesThroughOnFirstConformingAttempt() throws Exception {
        JsonNode payload =
                StructuredOutputGenerator.generateWithRetry(
                        errors -> "{\"answer\": 42}", schema(), 3);
        assertEquals(42, payload.path("answer").asInt());
    }

    @Test
    void retriesWithErrorFeedbackUntilConforming() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        JsonNode payload =
                StructuredOutputGenerator.generateWithRetry(
                        errors -> {
                            int attempt = calls.incrementAndGet();
                            if (attempt == 1) {
                                return "{\"wrong\": true}"; // first attempt fails validation
                            }
                            // second attempt: caller uses error feedback (industry pattern)
                            assertTrue(
                                    errors.stream().anyMatch(e -> e.message().contains("answer")));
                            return "{\"answer\": 7}";
                        },
                        schema(),
                        3);
        assertEquals(2, calls.get());
        assertEquals(7, payload.path("answer").asInt());
    }

    @Test
    void throwsAfterRetriesExhausted() {
        AtomicInteger calls = new AtomicInteger();
        StructuredOutputValidationException ex =
                assertThrows(
                        StructuredOutputValidationException.class,
                        () ->
                                StructuredOutputGenerator.generateWithRetry(
                                        errors -> {
                                            calls.incrementAndGet();
                                            return "{\"wrong\": true}";
                                        },
                                        schema(),
                                        3));
        assertEquals(3, calls.get());
        assertEquals("MathResponse", ex.getSchemaName());
    }

    @Test
    void extractsJsonFromMarkdownFence() throws Exception {
        JsonNode payload =
                StructuredOutputGenerator.generateWithRetry(
                        errors -> "```json\n{\"answer\": 1}\n```", schema(), 3);
        assertEquals(1, payload.path("answer").asInt());
    }

    @Test
    void extractsJsonFromLeadingProse() throws Exception {
        JsonNode payload =
                StructuredOutputGenerator.generateWithRetry(
                        errors -> "好的，答案是：{\"answer\": 3}", schema(), 3);
        assertEquals(3, payload.path("answer").asInt());
    }

    @Test
    void retryPromptContainsErrorDetails() {
        List<StructuredOutputValidator.ValidationError> errors =
                List.of(
                        new StructuredOutputValidator.ValidationError(
                                "#/answer", "required property 'answer' is missing"));
        String prompt = StructuredOutputGenerator.retryPrompt(errors);
        assertTrue(prompt.contains("#/answer"));
        assertTrue(prompt.contains("answer"));
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredOutputGenerator.generateWithRetry(errors -> "{}", schema(), 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> StructuredOutputGenerator.generateWithRetry(null, schema(), 3));
    }
}
