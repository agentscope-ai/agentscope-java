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
package io.agentscope.extensions.model.openai.compat.glm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.dto.OpenAITool;
import io.agentscope.extensions.model.openai.dto.OpenAIToolFunction;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link GLMMultiAgentFormatter}.
 *
 * <p>Tests verify GLM multi-agent specific requirements:
 * <ul>
 *   <li>Inherits multi-agent conversation merging from OpenAIMultiAgentFormatter</li>
 *   <li>At least one user message is required</li>
 *   <li>Only supports "auto" tool_choice</li>
 *   <li>Does NOT support strict parameter in tool definitions</li>
 *   <li>Does NOT support frequency_penalty / presence_penalty</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("GLMMultiAgentFormatter (compat.glm) Unit Tests")
class GLMMultiAgentFormatterTest {

    private GLMMultiAgentFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new GLMMultiAgentFormatter();
    }

    private static Msg textMsg(MsgRole role, String name, String text) {
        return Msg.builder()
                .role(role)
                .name(name)
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Custom conversation history prompt should be honored")
        void testCustomPromptConstructor() {
            GLMMultiAgentFormatter customFormatter =
                    new GLMMultiAgentFormatter("# Custom History\n");

            List<Msg> messages =
                    List.of(
                            textMsg(MsgRole.USER, "Alice", "Hello"),
                            textMsg(MsgRole.ASSISTANT, "Bob", "Hi Alice"));

            List<OpenAIMessage> result = customFormatter.format(messages);

            assertFalse(result.isEmpty());
            String content = result.get(0).getContentAsString();
            assertTrue(content.contains("# Custom History"));
        }
    }

    @Nested
    @DisplayName("Multi-agent Formatting Tests")
    class MultiAgentFormattingTests {

        @Test
        @DisplayName("Should merge agent conversation into a user message")
        void testMergesConversationIntoUserMessage() {
            List<Msg> messages =
                    List.of(
                            textMsg(MsgRole.USER, "Alice", "Hello"),
                            textMsg(MsgRole.ASSISTANT, "Bob", "Hi Alice"));

            List<OpenAIMessage> result = formatter.format(messages);

            assertFalse(result.isEmpty());
            // Merged conversation is emitted as a user message, so no placeholder is needed
            assertTrue(result.stream().anyMatch(m -> "user".equals(m.getRole())));
        }

        @Test
        @DisplayName("Should add placeholder user message for system-only conversation")
        void testEnsuresUserMessage() {
            List<Msg> messages = List.of(textMsg(MsgRole.SYSTEM, null, "You are helpful"));

            List<OpenAIMessage> result = formatter.format(messages);

            assertEquals(2, result.size());
            assertEquals("system", result.get(0).getRole());
            assertEquals("user", result.get(1).getRole());
            assertEquals("", result.get(1).getContentAsString());
        }
    }

    @Nested
    @DisplayName("GLM-specific Behavior Tests")
    class GLMSpecificBehaviorTests {

        @Test
        @DisplayName("supportsStrict should return false")
        void testSupportsStrictReturnsFalse() {
            assertFalse(formatter.supportsStrict());
        }

        @Test
        @DisplayName("applyTools should not include strict parameter")
        void testApplyToolsWithoutStrict() {
            OpenAIRequest request =
                    OpenAIRequest.builder().model("glm-4.7").messages(List.of()).build();

            ToolSchema tool =
                    ToolSchema.builder()
                            .name("test_tool")
                            .description("Test tool")
                            .strict(true)
                            .build();

            formatter.applyTools(request, List.of(tool));

            assertNotNull(request.getTools());
            assertNull(request.getTools().get(0).getFunction().getStrict());
        }

        @Test
        @DisplayName("applyToolChoice should degrade all options to auto")
        void testToolChoiceDegradesToAuto() {
            OpenAIToolFunction function = new OpenAIToolFunction();
            function.setName("test_tool");
            OpenAITool tool = new OpenAITool();
            tool.setFunction(function);
            tool.setType("function");

            OpenAIRequest request =
                    OpenAIRequest.builder()
                            .model("glm-4.7")
                            .messages(List.of())
                            .tools(List.of(tool))
                            .build();

            formatter.applyToolChoice(request, new ToolChoice.Required());

            assertEquals("auto", request.getToolChoice());
        }

        @Test
        @DisplayName("applyOptions should strip frequency_penalty and presence_penalty")
        void testStripUnsupportedPenalties() {
            OpenAIRequest request =
                    OpenAIRequest.builder().model("glm-5.2").messages(List.of()).build();

            GenerateOptions options =
                    GenerateOptions.builder()
                            .temperature(0.7)
                            .frequencyPenalty(0.5)
                            .presencePenalty(0.5)
                            .build();

            formatter.applyOptions(request, options, null);

            assertEquals(0.7, request.getTemperature());
            assertNull(request.getFrequencyPenalty());
            assertNull(request.getPresencePenalty());
        }

        @Test
        @DisplayName("applyOptions should map max_completion_tokens and clamp temperature")
        void testSharedSanitizeBehavior() {
            OpenAIRequest request =
                    OpenAIRequest.builder().model("glm-5.2").messages(List.of()).build();

            GenerateOptions options =
                    GenerateOptions.builder().temperature(1.5).maxCompletionTokens(4096).build();

            formatter.applyOptions(request, options, null);

            assertEquals(1.0, request.getTemperature());
            assertEquals(4096, request.getMaxTokens());
            assertNull(request.getMaxCompletionTokens());
        }
    }
}
