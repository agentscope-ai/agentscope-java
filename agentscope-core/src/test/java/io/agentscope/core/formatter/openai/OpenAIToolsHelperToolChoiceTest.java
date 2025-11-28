/*
 * Copyright 2024-2025 the original author or authors.
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

package io.agentscope.core.formatter.openai;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.openai.models.chat.completions.ChatCompletionCreateParams;
import io.agentscope.core.model.ToolChoice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Tests for ToolChoice support in OpenAIToolsHelper. */
class OpenAIToolsHelperToolChoiceTest {

    private OpenAIToolsHelper helper;

    @BeforeEach
    void setUp() {
        helper = new OpenAIToolsHelper();
    }

    @Test
    void testApplyToolChoiceWithNull() {
        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder().model("gpt-4o");

        // Just test that applyToolChoice doesn't throw
        assertDoesNotThrow(() -> helper.applyToolChoice(builder, null));
        assertNotNull(builder);
    }

    @Test
    void testApplyToolChoiceWithAuto() {
        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder().model("gpt-4o");

        assertDoesNotThrow(() -> helper.applyToolChoice(builder, new ToolChoice.Auto()));
        assertNotNull(builder);
    }

    @Test
    void testApplyToolChoiceWithNone() {
        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder().model("gpt-4o");

        assertDoesNotThrow(() -> helper.applyToolChoice(builder, new ToolChoice.None()));
        assertNotNull(builder);
    }

    @Test
    void testApplyToolChoiceWithRequired() {
        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder().model("gpt-4o");

        assertDoesNotThrow(() -> helper.applyToolChoice(builder, new ToolChoice.Required()));
        assertNotNull(builder);
    }

    @Test
    void testApplyToolChoiceWithSpecific() {
        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder().model("gpt-4o");

        assertDoesNotThrow(
                () -> helper.applyToolChoice(builder, new ToolChoice.Specific("my_function")));
        assertNotNull(builder);
    }

    @Test
    void testApplyToolChoiceSpecificWithGenerateResponse() {
        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder().model("gpt-4o");

        assertDoesNotThrow(
                () ->
                        helper.applyToolChoice(
                                builder, new ToolChoice.Specific("generate_response")));
        assertNotNull(builder);
    }

    @Test
    void testApplyToolChoiceMultipleTimes() {
        ChatCompletionCreateParams.Builder builder =
                ChatCompletionCreateParams.builder().model("gpt-4o");

        // Apply different tool choices sequentially
        assertDoesNotThrow(() -> helper.applyToolChoice(builder, new ToolChoice.Auto()));
        assertDoesNotThrow(() -> helper.applyToolChoice(builder, new ToolChoice.None()));
        assertDoesNotThrow(() -> helper.applyToolChoice(builder, new ToolChoice.Required()));
        assertDoesNotThrow(() -> helper.applyToolChoice(builder, new ToolChoice.Specific("tool")));
        assertNotNull(builder);
    }

    @Test
    void testApplyToolChoiceAllTypes() {
        // Test that all ToolChoice types are handled correctly
        ToolChoice[] choices =
                new ToolChoice[] {
                    new ToolChoice.Auto(),
                    new ToolChoice.None(),
                    new ToolChoice.Required(),
                    new ToolChoice.Specific("test_tool")
                };

        for (ToolChoice choice : choices) {
            ChatCompletionCreateParams.Builder builder =
                    ChatCompletionCreateParams.builder().model("gpt-4o");

            assertDoesNotThrow(() -> helper.applyToolChoice(builder, choice));
            assertNotNull(builder);
        }
    }

    @Test
    void testApplyToolChoiceSpecificWithDifferentNames() {
        String[] toolNames = {"tool1", "tool2", "generate_response", "my_custom_tool"};

        for (String toolName : toolNames) {
            ChatCompletionCreateParams.Builder builder =
                    ChatCompletionCreateParams.builder().model("gpt-4o");

            assertDoesNotThrow(
                    () -> helper.applyToolChoice(builder, new ToolChoice.Specific(toolName)));
            assertNotNull(builder);
        }
    }
}
