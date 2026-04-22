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
package io.agentscope.core.formatter.openai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.agentscope.core.formatter.openai.dto.OpenAIMessage;
import io.agentscope.core.formatter.openai.dto.OpenAIRequest;
import io.agentscope.core.formatter.openai.dto.OpenAITool;
import io.agentscope.core.formatter.openai.dto.OpenAIToolFunction;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for GLMFormatter.
 *
 * <p>Tests verify Zhipu GLM-specific requirements:
 * <ul>
 *   <li>At least one user message is required (error 1214 otherwise)</li>
 *   <li>Only supports "auto" for tool_choice</li>
 *   <li>Does NOT support strict parameter in tool definitions</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("GLMFormatter Unit Tests")
class GLMFormatterTest {

    private GLMFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new GLMFormatter();
    }

    @Nested
    @DisplayName("supportsStrict Tests")
    class SupportsStrictTests {

        @Test
        @DisplayName("supportsStrict should return false")
        void testSupportsStrictReturnsFalse() {
            assertFalse(formatter.supportsStrict());
        }

        @Test
        @DisplayName("applyTools should not include strict parameter")
        void testApplyToolsWithoutStrict() {
            OpenAIRequest request =
                    OpenAIRequest.builder().model("glm-4").messages(List.of()).build();

            ToolSchema tool =
                    ToolSchema.builder()
                            .name("test_tool")
                            .description("Test tool")
                            .strict(true)
                            .build();

            formatter.applyTools(request, List.of(tool));

            assertNotNull(request.getTools());
            assertEquals(1, request.getTools().size());
            // Strict should not be set because GLM doesn't support it
            assertNull(request.getTools().get(0).getFunction().getStrict());
        }
    }

    @Nested
    @DisplayName("ensureUserMessage Tests")
    class EnsureUserMessageTests {

        @Test
        @DisplayName("Should return unchanged if user message exists")
        void testReturnUnchangedWithUserMessage() {
            List<OpenAIMessage> messages =
                    List.of(OpenAIMessage.builder().role("user").content("Hello").build());

            List<OpenAIMessage> result = GLMFormatter.ensureUserMessage(messages);

            assertEquals(1, result.size());
            assertEquals("Hello", result.get(0).getContentAsString());
        }

        @Test
        @DisplayName("Should add placeholder user message if no user message")
        void testAddPlaceholderIfNoUserMessage() {
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder()
                                    .role("system")
                                    .content("You are helpful")
                                    .build(),
                            OpenAIMessage.builder().role("assistant").content("Hello!").build());

            List<OpenAIMessage> result = GLMFormatter.ensureUserMessage(messages);

            assertEquals(3, result.size());
            // Last message should be the placeholder user message
            assertEquals("user", result.get(2).getRole());
            assertEquals("", result.get(2).getContentAsString());
        }

        @Test
        @DisplayName("Should add placeholder for empty message list")
        void testAddPlaceholderForEmptyList() {
            List<OpenAIMessage> messages = List.of();

            List<OpenAIMessage> result = GLMFormatter.ensureUserMessage(messages);

            assertEquals(1, result.size());
            assertEquals("user", result.get(0).getRole());
            assertEquals("", result.get(0).getContentAsString());
        }

        @Test
        @DisplayName("Should detect user message in mixed conversation")
        void testDetectUserInMixedConversation() {
            List<OpenAIMessage> messages =
                    List.of(
                            OpenAIMessage.builder().role("system").content("System prompt").build(),
                            OpenAIMessage.builder().role("user").content("User question").build(),
                            OpenAIMessage.builder()
                                    .role("assistant")
                                    .content("Assistant response")
                                    .build());

            List<OpenAIMessage> result = GLMFormatter.ensureUserMessage(messages);

            // No additional message needed
            assertEquals(3, result.size());
        }
    }

    @Nested
    @DisplayName("applyGLMToolChoice Tests")
    class ApplyGLMToolChoiceTests {

        private OpenAIRequest createRequestWithTools() {
            OpenAIToolFunction function = new OpenAIToolFunction();
            function.setName("test_tool");
            OpenAITool tool = new OpenAITool();
            tool.setFunction(function);
            tool.setType("function");

            return OpenAIRequest.builder()
                    .model("glm-4")
                    .messages(List.of())
                    .tools(List.of(tool))
                    .build();
        }

        @Test
        @DisplayName("Should set tool_choice to auto for Auto")
        void testToolChoiceAuto() {
            OpenAIRequest request = createRequestWithTools();

            GLMFormatter.applyGLMToolChoice(request, new ToolChoice.Auto());

            assertEquals("auto", request.getToolChoice());
        }

        @Test
        @DisplayName("Should degrade None to auto")
        void testToolChoiceNoneDegradesToAuto() {
            OpenAIRequest request = createRequestWithTools();

            GLMFormatter.applyGLMToolChoice(request, new ToolChoice.None());

            assertEquals("auto", request.getToolChoice());
        }

        @Test
        @DisplayName("Should degrade Required to auto")
        void testToolChoiceRequiredDegradesToAuto() {
            OpenAIRequest request = createRequestWithTools();

            GLMFormatter.applyGLMToolChoice(request, new ToolChoice.Required());

            assertEquals("auto", request.getToolChoice());
        }

        @Test
        @DisplayName("Should degrade Specific to auto")
        void testToolChoiceSpecificDegradesToAuto() {
            OpenAIRequest request = createRequestWithTools();

            GLMFormatter.applyGLMToolChoice(request, new ToolChoice.Specific("test_tool"));

            assertEquals("auto", request.getToolChoice());
        }

        @Test
        @DisplayName("Should not set tool_choice if no tools")
        void testNoToolChoiceWithoutTools() {
            OpenAIRequest request =
                    OpenAIRequest.builder().model("glm-4").messages(List.of()).build();

            GLMFormatter.applyGLMToolChoice(request, new ToolChoice.Auto());

            assertNull(request.getToolChoice());
        }

        @Test
        @DisplayName("Should not set tool_choice if tools list is empty")
        void testNoToolChoiceWithEmptyTools() {
            OpenAIRequest request =
                    OpenAIRequest.builder()
                            .model("glm-4")
                            .messages(List.of())
                            .tools(List.of())
                            .build();

            GLMFormatter.applyGLMToolChoice(request, new ToolChoice.Auto());

            assertNull(request.getToolChoice());
        }

        @Test
        @DisplayName("Should handle null toolChoice")
        void testNullToolChoice() {
            OpenAIRequest request = createRequestWithTools();

            GLMFormatter.applyGLMToolChoice(request, null);

            assertEquals("auto", request.getToolChoice());
        }
    }

    @Nested
    @DisplayName("doFormat Integration Tests")
    class DoFormatTests {

        @Test
        @DisplayName("Should ensure user message in formatted output")
        void testFormatEnsuresUserMessage() {
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.SYSTEM)
                                    .content(
                                            List.of(
                                                    TextBlock.builder()
                                                            .text("You are helpful")
                                                            .build()))
                                    .build());

            List<OpenAIMessage> result = formatter.format(messages);

            // Should have system message + placeholder user message
            assertEquals(2, result.size());
            assertEquals("system", result.get(0).getRole());
            assertEquals("user", result.get(1).getRole());
        }

        @Test
        @DisplayName("Should not add placeholder if user message exists")
        void testFormatWithExistingUserMessage() {
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.SYSTEM)
                                    .content(
                                            List.of(
                                                    TextBlock.builder()
                                                            .text("You are helpful")
                                                            .build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .content(List.of(TextBlock.builder().text("Hello").build()))
                                    .build());

            List<OpenAIMessage> result = formatter.format(messages);

            assertEquals(2, result.size());
            assertEquals("system", result.get(0).getRole());
            assertEquals("user", result.get(1).getRole());
            assertEquals("Hello", result.get(1).getContentAsString());
        }

        @Test
        @DisplayName("Should format assistant messages correctly")
        void testFormatAssistantMessages() {
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .content(List.of(TextBlock.builder().text("Hello").build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .content(
                                            List.of(
                                                    TextBlock.builder()
                                                            .text("Hello! How can I help?")
                                                            .build()))
                                    .build());

            List<OpenAIMessage> result = formatter.format(messages);

            assertEquals(2, result.size());
            assertEquals("assistant", result.get(1).getRole());
            assertEquals("Hello! How can I help?", result.get(1).getContentAsString());
        }

        @Test
        @DisplayName("Should handle empty message list")
        void testFormatEmptyList() {
            List<OpenAIMessage> result = formatter.format(List.of());

            // Should add placeholder user message
            assertEquals(1, result.size());
            assertEquals("user", result.get(0).getRole());
        }
    }

    @Nested
    @DisplayName("applyToolChoice Integration Tests")
    class ApplyToolChoiceIntegrationTests {

        @Test
        @DisplayName("Should apply tool choice through formatter")
        void testApplyToolChoiceThroughFormatter() {
            OpenAIToolFunction function = new OpenAIToolFunction();
            function.setName("get_weather");
            OpenAITool tool = new OpenAITool();
            tool.setFunction(function);
            tool.setType("function");

            OpenAIRequest request =
                    OpenAIRequest.builder()
                            .model("glm-4")
                            .messages(List.of())
                            .tools(List.of(tool))
                            .build();

            formatter.applyToolChoice(request, new ToolChoice.Specific("get_weather"));

            // GLM should degrade to auto
            assertEquals("auto", request.getToolChoice());
        }
    }
}
