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
package io.agentscope.extensions.model.openai.compat.kimi;

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
 * Unit tests for {@link KimiMultiAgentFormatter}.
 *
 * <p>Tests verify Kimi multi-agent specific requirements:
 * <ul>
 *   <li>Inherits multi-agent conversation merging from OpenAIMultiAgentFormatter</li>
 *   <li>Fixed sampling params are stripped on kimi-* models</li>
 *   <li>tool_choice is degraded when unsupported by the target model</li>
 *   <li>Does NOT send the strict parameter in tool definitions</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("KimiMultiAgentFormatter (compat.kimi) Unit Tests")
class KimiMultiAgentFormatterTest {

    private KimiMultiAgentFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new KimiMultiAgentFormatter();
    }

    private static Msg textMsg(MsgRole role, String name, String text) {
        return Msg.builder()
                .role(role)
                .name(name)
                .content(List.of(TextBlock.builder().text(text).build()))
                .build();
    }

    private static OpenAIRequest requestWithTools(String model) {
        OpenAIToolFunction function = new OpenAIToolFunction();
        function.setName("test_tool");
        OpenAITool tool = new OpenAITool();
        tool.setFunction(function);
        tool.setType("function");

        return OpenAIRequest.builder()
                .model(model)
                .messages(List.of())
                .tools(List.of(tool))
                .build();
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Custom conversation history prompt should be honored")
        void testCustomPromptConstructor() {
            KimiMultiAgentFormatter customFormatter =
                    new KimiMultiAgentFormatter("# Custom History\n");

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
            assertTrue(result.stream().anyMatch(m -> "user".equals(m.getRole())));
        }
    }

    @Nested
    @DisplayName("Kimi-specific Behavior Tests")
    class KimiSpecificBehaviorTests {

        @Test
        @DisplayName("supportsStrict should return false")
        void testSupportsStrictReturnsFalse() {
            assertFalse(formatter.supportsStrict());
        }

        @Test
        @DisplayName("applyTools should not include strict parameter")
        void testApplyToolsWithoutStrict() {
            OpenAIRequest request =
                    OpenAIRequest.builder().model("kimi-k3").messages(List.of()).build();

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
        @DisplayName("applyToolChoice should degrade required to auto on K2.x")
        void testToolChoiceRequiredDegradesOnK2() {
            OpenAIRequest request = requestWithTools("kimi-k2.6");

            formatter.applyToolChoice(request, new ToolChoice.Required());

            assertEquals("auto", request.getToolChoice());
        }

        @Test
        @DisplayName("applyToolChoice should keep required on kimi-k3")
        void testToolChoiceRequiredKeptOnK3() {
            OpenAIRequest request = requestWithTools("kimi-k3");

            formatter.applyToolChoice(request, new ToolChoice.Required());

            assertEquals("required", request.getToolChoice());
        }

        @Test
        @DisplayName("applyOptions should strip fixed sampling params on kimi-* models")
        void testStripFixedSamplingParams() {
            OpenAIRequest request =
                    OpenAIRequest.builder().model("kimi-k2.6").messages(List.of()).build();

            GenerateOptions options =
                    GenerateOptions.builder()
                            .temperature(0.7)
                            .topP(0.9)
                            .frequencyPenalty(0.5)
                            .presencePenalty(0.5)
                            .build();

            formatter.applyOptions(request, options, null);

            assertNull(request.getTemperature());
            assertNull(request.getTopP());
            assertNull(request.getFrequencyPenalty());
            assertNull(request.getPresencePenalty());
        }

        @Test
        @DisplayName("applyOptions should map max_completion_tokens and strip reasoning_effort")
        void testSharedSanitizeBehavior() {
            OpenAIRequest request =
                    OpenAIRequest.builder().model("kimi-k2.6").messages(List.of()).build();

            GenerateOptions options =
                    GenerateOptions.builder()
                            .reasoningEffort("high")
                            .maxCompletionTokens(32768)
                            .build();

            formatter.applyOptions(request, options, null);

            assertNull(request.getReasoningEffort());
            assertEquals(32768, request.getMaxTokens());
            assertNull(request.getMaxCompletionTokens());
        }
    }
}
