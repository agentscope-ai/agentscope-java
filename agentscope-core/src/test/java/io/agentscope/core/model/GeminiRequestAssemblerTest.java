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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.StructuredOutputCapableAgent;
import io.agentscope.core.formatter.gemini.GeminiChatFormatter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.util.JsonCodec;
import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import okhttp3.Request;
import okio.Buffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Tag("unit")
@DisplayName("GeminiRequestAssembler Unit Tests")
class GeminiRequestAssemblerTest {

    private static final Logger LOG = LoggerFactory.getLogger(GeminiRequestAssemblerTest.class);

    @Test
    @DisplayName("Should assemble streaming request and append synthetic user when needed")
    void testAssembleStreamingRequestWithSyntheticUser() throws IOException {
        GeminiRequestAssembler assembler =
                newAssembler(
                        "https://example.com/v1beta/models/",
                        "gemini-2.5-flash",
                        true,
                        "mock_api_key",
                        null);

        List<Msg> messages =
                List.of(
                        Msg.builder().role(MsgRole.USER).textContent("Q1").build(),
                        Msg.builder().role(MsgRole.ASSISTANT).textContent("A1").build());

        GeminiRequestAssembler.PreparedRequest prepared =
                assembler.assemble(messages, null, GenerateOptions.builder().build());

        assertTrue(prepared.isStreamForRequest());

        Request request = prepared.getHttpRequest();
        assertTrue(
                request.url().toString().contains(":streamGenerateContent?alt=sse"),
                "Expected streaming endpoint URL");
        assertEquals("mock_api_key", request.header("x-goog-api-key"));
        assertEquals(null, request.header("Authorization"));

        Map<String, Object> requestBody = parseRequestBody(request);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contents =
                (List<Map<String, Object>>) requestBody.get("contents");
        assertNotNull(contents);
        assertEquals(3, contents.size());

        Map<String, Object> lastContent = contents.get(contents.size() - 1);
        assertEquals("user", lastContent.get("role"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> parts = (List<Map<String, Object>>) lastContent.get("parts");
        assertNotNull(parts);
        assertEquals("Please continue with your response.", parts.get(0).get("text"));
    }

    @Test
    @DisplayName(
            "Should force unary endpoint and send thinkingBudget=0 when structured output"
                    + " tool is present")
    void testForceUnaryForStructuredOutput() throws IOException {
        GeminiRequestAssembler assembler =
                newAssembler(
                        "https://example.com/v1beta/models/",
                        "gemini-3-flash-preview",
                        true,
                        "mock_api_key",
                        null);

        List<Msg> messages = List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build());
        List<ToolSchema> tools = List.of(structuredOutputTool());

        GeminiRequestAssembler.PreparedRequest prepared =
                assembler.assemble(messages, tools, GenerateOptions.builder().build());

        assertFalse(prepared.isStreamForRequest());
        assertTrue(
                prepared.getHttpRequest().url().toString().endsWith(":generateContent"),
                "Expected unary endpoint URL");
        assertFalse(prepared.getHttpRequest().url().toString().contains("alt=sse"));

        Map<String, Object> requestBody = parseRequestBody(prepared.getHttpRequest());
        @SuppressWarnings("unchecked")
        Map<String, Object> generationConfig =
                (Map<String, Object>) requestBody.get("generationConfig");
        assertNotNull(generationConfig);
        @SuppressWarnings("unchecked")
        Map<String, Object> thinkingConfig =
                (Map<String, Object>) generationConfig.get("thinkingConfig");
        assertNotNull(thinkingConfig);
        assertEquals(0, thinkingConfig.get("thinkingBudget"));
        assertFalse(thinkingConfig.containsKey("includeThoughts"));
    }

    @Test
    @DisplayName("Should prefer Authorization header when access token is configured")
    void testAccessTokenHeader() {
        GeminiRequestAssembler assembler =
                newAssembler(
                        "https://example.com/v1beta/models/",
                        "gemini-2.5-flash",
                        false,
                        null,
                        "access_token_123");

        List<Msg> messages = List.of(Msg.builder().role(MsgRole.USER).textContent("hello").build());

        GeminiRequestAssembler.PreparedRequest prepared =
                assembler.assemble(messages, null, GenerateOptions.builder().build());

        assertFalse(prepared.isStreamForRequest());
        assertEquals("Bearer access_token_123", prepared.getHttpRequest().header("Authorization"));
        assertEquals(null, prepared.getHttpRequest().header("x-goog-api-key"));
    }

    private static GeminiRequestAssembler newAssembler(
            String baseUrl, String modelName, boolean streamEnabled, String apiKey, String token) {
        JsonCodec codec = JsonUtils.getJsonCodec();
        return new GeminiRequestAssembler(
                baseUrl,
                modelName,
                streamEnabled,
                apiKey,
                token,
                GenerateOptions.builder().build(),
                new GeminiChatFormatter(),
                codec,
                new GeminiThinkingPolicy(),
                LOG);
    }

    private static ToolSchema structuredOutputTool() {
        return ToolSchema.builder()
                .name(StructuredOutputCapableAgent.STRUCTURED_OUTPUT_TOOL_NAME)
                .description("Structured output tool")
                .parameters(Map.of("type", "object", "properties", Map.of()))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseRequestBody(Request request) throws IOException {
        Buffer buffer = new Buffer();
        if (request.body() == null) {
            throw new IllegalStateException("Request body is null");
        }
        request.body().writeTo(buffer);
        String json = buffer.readUtf8();
        return JsonUtils.getJsonCodec().fromJson(json, Map.class);
    }
}
