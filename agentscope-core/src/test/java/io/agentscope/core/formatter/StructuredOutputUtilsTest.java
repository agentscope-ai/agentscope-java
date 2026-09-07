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

class StructuredOutputUtilsTest {

    @Test
    void extractsBareJsonObject() throws Exception {
        JsonNode payload = StructuredOutputUtils.extractJsonObject("{\"answer\": 42}");
        assertEquals(42, payload.path("answer").asInt());
    }

    @Test
    void extractsJsonFromMarkdownFence() throws Exception {
        JsonNode payload = StructuredOutputUtils.extractJsonObject("```json\n{\"answer\": 1}\n```");
        assertEquals(1, payload.path("answer").asInt());
    }

    @Test
    void extractsJsonFromLeadingProse() throws Exception {
        JsonNode payload = StructuredOutputUtils.extractJsonObject("好的，答案是：{\"answer\": 3}");
        assertEquals(3, payload.path("answer").asInt());
    }

    @Test
    void toleratesTrailingProse() throws Exception {
        // Jackson reads the first value and ignores trailing content by default.
        JsonNode payload = StructuredOutputUtils.extractJsonObject("{\"answer\": 6} 希望有帮助");
        assertEquals(6, payload.path("answer").asInt());
    }

    @Test
    void noJsonFailsClosedWithParseException() {
        StructuredOutputParseException ex =
                assertThrows(
                        StructuredOutputParseException.class,
                        () -> StructuredOutputUtils.extractJsonObject("I am thinking... no json"));
        assertTrue(ex.getMessage().contains("not valid JSON"));
    }

    @Test
    void unbalancedBracesFailClosed() {
        assertThrows(
                StructuredOutputParseException.class,
                () -> StructuredOutputUtils.extractJsonObject("{\"answer\": 4"));
    }

    @Test
    void retryPromptContainsErrorDetails() {
        List<StructuredOutputValidator.ValidationError> errors =
                List.of(
                        new StructuredOutputValidator.ValidationError(
                                "#/answer", "required property 'answer' is missing"));
        String prompt = StructuredOutputUtils.retryPrompt(errors);
        assertTrue(prompt.contains("#/answer"));
        assertTrue(prompt.contains("answer"));
    }

    @Test
    void retryPromptEmptyWithoutErrors() {
        assertEquals("", StructuredOutputUtils.retryPrompt(List.of()));
        assertEquals("", StructuredOutputUtils.retryPrompt(null));
    }
}
