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
package io.agentscope.extensions.model.gemini;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.agentscope.core.agent.test.TestUtils;
import io.agentscope.core.e2e.E2ETestCondition;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.gemini.formatter.GeminiChatFormatter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/** Provider-specific acceptance test for nullable Gemini tool schemas. */
@Tag("e2e")
@Tag("tools")
@ExtendWith(E2ETestCondition.class)
class GeminiNullableToolSchemaE2ETest {

    private static final String GOOGLE_API_KEY = "GOOGLE_API_KEY";
    private static final String GOOGLE_GEMINI_MODEL = "GOOGLE_GEMINI_MODEL";
    private static final String DEFAULT_MODEL = "gemini-3.6-flash";
    private static final String TOOL_NAME = "nullable_echo";
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(90);

    @Test
    @DisplayName("Gemini accepts a nullable JSON Schema tool parameter")
    void acceptsNullableToolSchema() {
        String apiKey = System.getenv(GOOGLE_API_KEY);
        assumeTrue(apiKey != null && !apiKey.isBlank(), "GOOGLE_API_KEY is required");
        String modelName = System.getenv(GOOGLE_GEMINI_MODEL);
        if (modelName == null || modelName.isBlank()) {
            modelName = DEFAULT_MODEL;
        }

        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .formatter(new GeminiChatFormatter())
                        .build();

        Map<String, Object> parameters =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("value", Map.of("type", List.of("string", "null"))));
        ToolSchema toolSchema =
                ToolSchema.builder()
                        .name(TOOL_NAME)
                        .description("Echo an optional value")
                        .parameters(parameters)
                        .build();

        Msg request = TestUtils.createUserMessage("User", "Call nullable_echo with value hello.");
        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Specific(TOOL_NAME)).build();

        List<ChatResponse> responses =
                model.stream(List.of(request), List.of(toolSchema), options)
                        .collectList()
                        .block(TEST_TIMEOUT);

        assertNotNull(responses, "Gemini should return a response stream");
        assertTrue(
                responses.stream()
                        .flatMap(response -> response.getContent().stream())
                        .filter(ToolUseBlock.class::isInstance)
                        .map(ToolUseBlock.class::cast)
                        .anyMatch(toolUse -> TOOL_NAME.equals(toolUse.getName())),
                "Gemini should return a tool-use block for the forced nullable tool");
    }

    @Test
    @DisplayName("Gemini accepts a nullable anyOf JSON Schema tool parameter")
    void acceptsNullableAnyOfToolSchema() {
        String apiKey = System.getenv(GOOGLE_API_KEY);
        assumeTrue(apiKey != null && !apiKey.isBlank(), "GOOGLE_API_KEY is required");
        String modelName = System.getenv(GOOGLE_GEMINI_MODEL);
        if (modelName == null || modelName.isBlank()) {
            modelName = DEFAULT_MODEL;
        }

        GeminiChatModel model =
                GeminiChatModel.builder()
                        .apiKey(apiKey)
                        .modelName(modelName)
                        .formatter(new GeminiChatFormatter())
                        .build();

        Map<String, Object> parameters =
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of(
                                "value",
                                Map.of(
                                        "anyOf",
                                        List.of(Map.of("type", "string"), Map.of("type", "null")))),
                        "required",
                        List.of("value"));
        ToolSchema toolSchema =
                ToolSchema.builder()
                        .name(TOOL_NAME)
                        .description("Echo an optional value")
                        .parameters(parameters)
                        .build();

        Msg request =
                TestUtils.createUserMessage(
                        "User", "Call nullable_echo with the value field set to JSON null.");
        GenerateOptions options =
                GenerateOptions.builder().toolChoice(new ToolChoice.Specific(TOOL_NAME)).build();

        List<ChatResponse> responses =
                model.stream(List.of(request), List.of(toolSchema), options)
                        .collectList()
                        .block(TEST_TIMEOUT);

        assertNotNull(responses, "Gemini should return a response stream");
        ToolUseBlock toolUse =
                responses.stream()
                        .flatMap(response -> response.getContent().stream())
                        .filter(ToolUseBlock.class::isInstance)
                        .map(ToolUseBlock.class::cast)
                        .filter(tool -> TOOL_NAME.equals(tool.getName()))
                        .findFirst()
                        .orElse(null);

        assertNotNull(toolUse, "Gemini should return the nullable anyOf tool call");
        assertTrue(toolUse.getInput().containsKey("value"));
        assertNull(toolUse.getInput().get("value"));
    }
}
