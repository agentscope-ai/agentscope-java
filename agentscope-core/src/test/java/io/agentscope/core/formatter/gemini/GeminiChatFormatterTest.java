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
package io.agentscope.core.formatter.gemini;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for GeminiChatFormatter.
 */
class GeminiChatFormatterTest {

    private final GeminiChatFormatter formatter = new GeminiChatFormatter();

    @Test
    void testFormatSimpleMessage() {
        Msg msg =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        List<Content> contents = formatter.format(List.of(msg));

        assertNotNull(contents);
        assertEquals(1, contents.size());

        Content content = contents.get(0);
        assertEquals("user", content.role().get());
        assertTrue(content.parts().isPresent());
        assertEquals(1, content.parts().get().size());
    }

    @Test
    void testApplyOptions() {
        GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder();

        GenerateOptions options =
                GenerateOptions.builder()
                        .temperature(0.7)
                        .topP(0.9)
                        .maxTokens(1000)
                        .frequencyPenalty(0.5)
                        .presencePenalty(0.3)
                        .build();

        formatter.applyOptions(configBuilder, options, null);

        GenerateContentConfig config = configBuilder.build();

        assertTrue(config.temperature().isPresent());
        assertEquals(0.7f, config.temperature().get(), 0.001f);

        assertTrue(config.topP().isPresent());
        assertEquals(0.9f, config.topP().get(), 0.001f);

        assertTrue(config.maxOutputTokens().isPresent());
        assertEquals(1000, config.maxOutputTokens().get());

        assertTrue(config.frequencyPenalty().isPresent());
        assertEquals(0.5f, config.frequencyPenalty().get(), 0.001f);

        assertTrue(config.presencePenalty().isPresent());
        assertEquals(0.3f, config.presencePenalty().get(), 0.001f);
    }

    @Test
    void testApplyTools() {
        GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder();

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", Map.of("query", Map.of("type", "string")));

        ToolSchema toolSchema =
                ToolSchema.builder()
                        .name("search")
                        .description("Search for information")
                        .parameters(parameters)
                        .build();

        formatter.applyTools(configBuilder, List.of(toolSchema));

        GenerateContentConfig config = configBuilder.build();

        assertTrue(config.tools().isPresent());
        assertEquals(1, config.tools().get().size());
        assertTrue(config.tools().get().get(0).functionDeclarations().isPresent());
    }

    @Test
    void testApplyToolChoice() {
        GenerateContentConfig.Builder configBuilder = GenerateContentConfig.builder();

        formatter.applyToolChoice(configBuilder, new ToolChoice.Required());

        GenerateContentConfig config = configBuilder.build();

        assertTrue(config.toolConfig().isPresent());
        assertTrue(config.toolConfig().get().functionCallingConfig().isPresent());
    }

    @Test
    void testParseResponse() {
        // Create a simple response
        GenerateContentResponse response =
                GenerateContentResponse.builder().responseId("test-123").build();

        Instant startTime = Instant.now();
        ChatResponse chatResponse = formatter.parseResponse(response, startTime);

        assertNotNull(chatResponse);
        assertEquals("test-123", chatResponse.getId());
    }

    @Test
    void testFormatMultipleMessages() {
        Msg msg1 =
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(List.of(TextBlock.builder().text("Hello").build()))
                        .build();

        Msg msg2 =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(List.of(TextBlock.builder().text("Hi there!").build()))
                        .build();

        List<Content> contents = formatter.format(List.of(msg1, msg2));

        assertNotNull(contents);
        assertEquals(2, contents.size());

        assertEquals("user", contents.get(0).role().get());
        assertEquals("model", contents.get(1).role().get());
    }
}
