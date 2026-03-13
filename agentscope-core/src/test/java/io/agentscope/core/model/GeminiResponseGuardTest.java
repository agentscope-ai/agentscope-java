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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.TextBlock;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

@Tag("unit")
@DisplayName("GeminiResponseGuard Unit Tests")
class GeminiResponseGuardTest {

    @Test
    @DisplayName("Should keep response unchanged when text content exists")
    void testKeepResponseWhenHasText() {
        GeminiResponseGuard guard =
                new GeminiResponseGuard("gemini-2.5-flash", LoggerFactory.getLogger(getClass()));
        ChatResponse response =
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("hello").build()))
                        .finishReason("STOP")
                        .build();

        ChatResponse result = guard.ensureMeaningfulContent(response);
        assertSame(response, result);
    }

    @Test
    @DisplayName("Should keep response unchanged for expected finish reason")
    void testKeepResponseForExpectedFinishReason() {
        GeminiResponseGuard guard =
                new GeminiResponseGuard("gemini-2.5-flash", LoggerFactory.getLogger(getClass()));
        ChatResponse response =
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("").build()))
                        .finishReason("STOP")
                        .build();

        ChatResponse result = guard.ensureMeaningfulContent(response);
        assertSame(response, result);
    }

    @Test
    @DisplayName("Should throw model exception on Gemini 3 malformed function call")
    void testThrowOnGemini3MalformedFunctionCall() {
        GeminiResponseGuard guard =
                new GeminiResponseGuard("gemini-3-pro", LoggerFactory.getLogger(getClass()));
        ChatResponse response =
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("").build()))
                        .finishReason("MALFORMED_FUNCTION_CALL")
                        .build();

        ModelException exception =
                assertThrows(ModelException.class, () -> guard.ensureMeaningfulContent(response));
        assertTrue(exception.getMessage().contains("MALFORMED_FUNCTION_CALL"));
    }

    @Test
    @DisplayName("Should append fallback text on unexpected finish reason")
    void testAppendFallbackForUnexpectedFinishReason() {
        GeminiResponseGuard guard =
                new GeminiResponseGuard("gemini-2.0-flash", LoggerFactory.getLogger(getClass()));
        ChatResponse response =
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text("").build()))
                        .finishReason("UNKNOWN_REASON")
                        .build();

        ChatResponse result = guard.ensureMeaningfulContent(response);

        assertEquals(2, result.getContent().size());
        TextBlock fallback = (TextBlock) result.getContent().get(1);
        assertTrue(fallback.getText().contains("UNKNOWN_REASON"));
    }
}
