/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
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
import java.util.List;
import org.junit.jupiter.api.Test;

class StructuredOutputGeneratorTest {

    @Test
    void extractsBareJsonObject() throws Exception {
        JsonNode payload = StructuredOutputGenerator.extractJsonObject("{\"answer\": 42}");
        assertEquals(42, payload.path("answer").asInt());
    }

    @Test
    void extractsJsonFromMarkdownFence() throws Exception {
        JsonNode payload =
                StructuredOutputGenerator.extractJsonObject("```json\n{\"answer\": 1}\n```");
        assertEquals(1, payload.path("answer").asInt());
    }

    @Test
    void extractsJsonFromLeadingProse() throws Exception {
        JsonNode payload = StructuredOutputGenerator.extractJsonObject("好的，答案是：{\"answer\": 3}");
        assertEquals(3, payload.path("answer").asInt());
    }

    @Test
    void extractsJsonAfterMalformedCandidate() throws Exception {
        // first balanced {...} is not valid JSON — extraction must scan on
        JsonNode payload =
                StructuredOutputGenerator.extractJsonObject("noise { oops } tail {\"answer\": 5}");
        assertEquals(5, payload.path("answer").asInt());
    }

    @Test
    void noJsonFailsClosedWithParseException() {
        StructuredOutputParseException ex =
                assertThrows(
                        StructuredOutputParseException.class,
                        () ->
                                StructuredOutputGenerator.extractJsonObject(
                                        "I am thinking... no json"));
        assertTrue(ex.getMessage().contains("no JSON object found"));
    }

    @Test
    void unbalancedBracesFailClosed() {
        assertThrows(
                StructuredOutputParseException.class,
                () -> StructuredOutputGenerator.extractJsonObject("{\"answer\": 4"));
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
    void retryPromptEmptyWithoutErrors() {
        assertEquals("", StructuredOutputGenerator.retryPrompt(List.of()));
        assertEquals("", StructuredOutputGenerator.retryPrompt(null));
    }
}
