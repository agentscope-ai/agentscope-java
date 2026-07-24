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
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.model.openai.dto.OpenAIMessage;
import io.agentscope.extensions.model.openai.dto.OpenAIRequest;
import io.agentscope.extensions.model.openai.dto.OpenAITool;
import io.agentscope.extensions.model.openai.dto.OpenAIToolFunction;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link KimiFormatter}.
 *
 * <p>Tests verify Kimi (Moonshot AI)-specific requirements per the latest official API
 * reference:
 * <ul>
 *   <li>Fixed sampling params (temperature / top_p / penalties) are stripped on kimi-* models</li>
 *   <li>reasoning_effort is kimi-k3 only</li>
 *   <li>tool_choice: required is degraded on K2.x, specific is degraded on always-thinking
 *       models</li>
 *   <li>Does NOT send the strict parameter in tool definitions</li>
 *   <li>reasoning_content is preserved in assistant history (Preserved Thinking)</li>
 * </ul>
 */
@Tag("unit")
@DisplayName("KimiFormatter (compat.kimi) Unit Tests")
class KimiFormatterTest {

    private KimiFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new KimiFormatter();
    }

    private static OpenAIRequest requestFor(String model) {
        return OpenAIRequest.builder().model(model).messages(List.of()).build();
    }

    private static OpenAIRequest requestWithTools(String model) {
        OpenAIToolFunction function = new OpenAIToolFunction();
        function.setName("get_weather");
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
            OpenAIRequest request = requestFor("kimi-k3");

            ToolSchema tool =
                    ToolSchema.builder()
                            .name("test_tool")
                            .description("Test tool")
                            .strict(true)
                            .build();

            formatter.applyTools(request, List.of(tool));

            assertNotNull(request.getTools());
            assertEquals(1, request.getTools().size());
            // Strict should not be set because Kimi doesn't document it
            assertNull(request.getTools().get(0).getFunction().getStrict());
        }
    }

    @Nested
    @DisplayName("Model Classification Tests")
    class ModelClassificationTests {

        @Test
        @DisplayName("kimi-* models have fixed sampling params")
        void testFixedSamplingParams() {
            assertTrue(KimiFormatter.hasFixedSamplingParams("kimi-k3"));
            assertTrue(KimiFormatter.hasFixedSamplingParams("kimi-k2.7-code"));
            assertTrue(KimiFormatter.hasFixedSamplingParams("kimi-k2.6"));
            assertFalse(KimiFormatter.hasFixedSamplingParams("moonshot-v1-8k"));
            assertFalse(KimiFormatter.hasFixedSamplingParams(null));
        }

        @Test
        @DisplayName("kimi-k3 and kimi-k2.7-code are always-thinking models")
        void testAlwaysThinkingModels() {
            assertTrue(KimiFormatter.isAlwaysThinkingModel("kimi-k3"));
            assertTrue(KimiFormatter.isAlwaysThinkingModel("kimi-k2.7-code"));
            assertTrue(KimiFormatter.isAlwaysThinkingModel("kimi-k2.7-code-highspeed"));
            // Only the documented kimi-k2.7-code series is always-thinking; a plain
            // kimi-k2.7* model must not have its tool_choice degraded
            assertFalse(KimiFormatter.isAlwaysThinkingModel("kimi-k2.7"));
            assertFalse(KimiFormatter.isAlwaysThinkingModel("kimi-k2.6"));
            assertFalse(KimiFormatter.isAlwaysThinkingModel("moonshot-v1-8k"));
            assertFalse(KimiFormatter.isAlwaysThinkingModel(null));
        }

        @Test
        @DisplayName("Only kimi-k3 supports reasoning_effort")
        void testSupportsReasoningEffort() {
            assertTrue(KimiFormatter.supportsReasoningEffort("kimi-k3"));
            assertFalse(KimiFormatter.supportsReasoningEffort("kimi-k2.6"));
            assertFalse(KimiFormatter.supportsReasoningEffort("kimi-k2.7-code"));
            assertFalse(KimiFormatter.supportsReasoningEffort("moonshot-v1-8k"));
            assertFalse(KimiFormatter.supportsReasoningEffort(null));
        }

        @Test
        @DisplayName("K2.x series does not support tool_choice=required")
        void testSupportsRequiredToolChoice() {
            assertTrue(KimiFormatter.supportsRequiredToolChoice("kimi-k3"));
            assertFalse(KimiFormatter.supportsRequiredToolChoice("kimi-k2.6"));
            assertFalse(KimiFormatter.supportsRequiredToolChoice("kimi-k2.7-code"));
            assertTrue(KimiFormatter.supportsRequiredToolChoice("moonshot-v1-8k"));
            // Unknown model: keep the caller-requested value rather than degrade it
            assertTrue(KimiFormatter.supportsRequiredToolChoice(null));
        }
    }

    @Nested
    @DisplayName("applyOptions Tests")
    class ApplyOptionsTests {

        @Test
        @DisplayName("Should strip fixed sampling params on kimi-* models")
        void testStripFixedSamplingParamsOnKimiModels() {
            OpenAIRequest request = requestFor("kimi-k3");

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
        @DisplayName("Should keep sampling params on moonshot-v1 models")
        void testKeepSamplingParamsOnMoonshotModels() {
            OpenAIRequest request = requestFor("moonshot-v1-8k");

            GenerateOptions options =
                    GenerateOptions.builder()
                            .temperature(0.3)
                            .topP(0.9)
                            .frequencyPenalty(0.5)
                            .presencePenalty(0.5)
                            .build();

            formatter.applyOptions(request, options, null);

            assertEquals(0.3, request.getTemperature());
            assertEquals(0.9, request.getTopP());
            assertEquals(0.5, request.getFrequencyPenalty());
            assertEquals(0.5, request.getPresencePenalty());
        }

        @Test
        @DisplayName("Should keep reasoning_effort on kimi-k3")
        void testKeepReasoningEffortOnK3() {
            OpenAIRequest request = requestFor("kimi-k3");

            GenerateOptions options = GenerateOptions.builder().reasoningEffort("high").build();

            formatter.applyOptions(request, options, null);

            assertEquals("high", request.getReasoningEffort());
        }

        @Test
        @DisplayName("Should strip reasoning_effort on K2.x models")
        void testStripReasoningEffortOnK2() {
            OpenAIRequest request = requestFor("kimi-k2.6");

            GenerateOptions options = GenerateOptions.builder().reasoningEffort("high").build();

            formatter.applyOptions(request, options, null);

            assertNull(request.getReasoningEffort());
        }

        @Test
        @DisplayName("Should strip thinking_budget")
        void testStripThinkingBudget() {
            OpenAIRequest request = requestFor("kimi-k2.6");

            GenerateOptions options = GenerateOptions.builder().thinkingBudget(2048).build();

            formatter.applyOptions(request, options, null);

            assertNull(request.getThinkingBudget());
        }

        @Test
        @DisplayName("Should map max_completion_tokens to max_tokens")
        void testMapMaxCompletionTokensToMaxTokens() {
            OpenAIRequest request = requestFor("kimi-k3");

            GenerateOptions options = GenerateOptions.builder().maxCompletionTokens(32768).build();

            formatter.applyOptions(request, options, null);

            assertEquals(32768, request.getMaxTokens());
            assertNull(request.getMaxCompletionTokens());
        }

        @Test
        @DisplayName("max_tokens should take precedence over max_completion_tokens")
        void testMaxTokensTakesPrecedence() {
            OpenAIRequest request = requestFor("kimi-k3");

            GenerateOptions options =
                    GenerateOptions.builder().maxTokens(16000).maxCompletionTokens(32768).build();

            formatter.applyOptions(request, options, null);

            assertEquals(16000, request.getMaxTokens());
            assertNull(request.getMaxCompletionTokens());
        }

        @Test
        @DisplayName("Should pass through the thinking body param for K2.x models")
        void testThinkingBodyParamPassThrough() {
            OpenAIRequest request = requestFor("kimi-k2.6");

            GenerateOptions options =
                    GenerateOptions.builder()
                            .additionalBodyParam("thinking", Map.of("type", "disabled"))
                            .build();

            formatter.applyOptions(request, options, null);

            assertNotNull(request.getExtraParams());
            assertEquals(Map.of("type", "disabled"), request.getExtraParams().get("thinking"));
        }
    }

    @Nested
    @DisplayName("applyKimiToolChoice Tests")
    class ApplyKimiToolChoiceTests {

        @Test
        @DisplayName("Should set tool_choice to auto for Auto")
        void testToolChoiceAuto() {
            OpenAIRequest request = requestWithTools("kimi-k3");

            KimiFormatter.applyKimiToolChoice(request, new ToolChoice.Auto());

            assertEquals("auto", request.getToolChoice());
        }

        @Test
        @DisplayName("Should set tool_choice to none for None")
        void testToolChoiceNone() {
            OpenAIRequest request = requestWithTools("kimi-k2.6");

            KimiFormatter.applyKimiToolChoice(request, new ToolChoice.None());

            assertEquals("none", request.getToolChoice());
        }

        @Test
        @DisplayName("Should keep required on kimi-k3")
        void testToolChoiceRequiredOnK3() {
            OpenAIRequest request = requestWithTools("kimi-k3");

            KimiFormatter.applyKimiToolChoice(request, new ToolChoice.Required());

            assertEquals("required", request.getToolChoice());
        }

        @Test
        @DisplayName("Should degrade required to auto on kimi-k2.6")
        void testToolChoiceRequiredDegradesOnK26() {
            OpenAIRequest request = requestWithTools("kimi-k2.6");

            KimiFormatter.applyKimiToolChoice(request, new ToolChoice.Required());

            assertEquals("auto", request.getToolChoice());
        }

        @Test
        @DisplayName("Should degrade required to auto on kimi-k2.7-code")
        void testToolChoiceRequiredDegradesOnK27Code() {
            OpenAIRequest request = requestWithTools("kimi-k2.7-code");

            KimiFormatter.applyKimiToolChoice(request, new ToolChoice.Required());

            assertEquals("auto", request.getToolChoice());
        }

        @Test
        @DisplayName("Should degrade specific to auto on always-thinking models")
        void testToolChoiceSpecificDegradesOnAlwaysThinkingModels() {
            OpenAIRequest request = requestWithTools("kimi-k3");

            KimiFormatter.applyKimiToolChoice(request, new ToolChoice.Specific("get_weather"));

            assertEquals("auto", request.getToolChoice());
        }

        @Test
        @DisplayName("Should keep specific tool_choice on kimi-k2.6")
        @SuppressWarnings("unchecked")
        void testToolChoiceSpecificKeptOnK26() {
            OpenAIRequest request = requestWithTools("kimi-k2.6");

            KimiFormatter.applyKimiToolChoice(request, new ToolChoice.Specific("get_weather"));

            assertTrue(request.getToolChoice() instanceof Map);
            Map<String, Object> choice = (Map<String, Object>) request.getToolChoice();
            assertEquals("function", choice.get("type"));
            assertEquals("get_weather", ((Map<String, Object>) choice.get("function")).get("name"));
        }

        @Test
        @DisplayName("Should not set tool_choice if no tools")
        void testNoToolChoiceWithoutTools() {
            OpenAIRequest request = requestFor("kimi-k3");

            KimiFormatter.applyKimiToolChoice(request, new ToolChoice.Auto());

            assertNull(request.getToolChoice());
        }

        @Test
        @DisplayName("Should not set tool_choice if tools list is empty")
        void testNoToolChoiceWithEmptyTools() {
            OpenAIRequest request =
                    OpenAIRequest.builder()
                            .model("kimi-k3")
                            .messages(List.of())
                            .tools(List.of())
                            .build();

            KimiFormatter.applyKimiToolChoice(request, new ToolChoice.Auto());

            assertNull(request.getToolChoice());
        }

        @Test
        @DisplayName("Should handle null toolChoice")
        void testNullToolChoice() {
            OpenAIRequest request = requestWithTools("kimi-k3");

            KimiFormatter.applyKimiToolChoice(request, null);

            assertEquals("auto", request.getToolChoice());
        }
    }

    @Nested
    @DisplayName("doFormat / Preserved Thinking Tests")
    class DoFormatTests {

        @Test
        @DisplayName("Should format basic conversation")
        void testFormatBasicConversation() {
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.SYSTEM)
                                    .content(
                                            List.of(
                                                    TextBlock.builder()
                                                            .text("You are Kimi")
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
        }

        @Test
        @DisplayName("Should preserve reasoning_content in assistant history")
        void testPreservesReasoningContent() {
            List<Msg> messages =
                    List.of(
                            Msg.builder()
                                    .role(MsgRole.USER)
                                    .content(
                                            List.of(
                                                    TextBlock.builder()
                                                            .text("First question")
                                                            .build()))
                                    .build(),
                            Msg.builder()
                                    .role(MsgRole.ASSISTANT)
                                    .content(
                                            List.of(
                                                    ThinkingBlock.builder()
                                                            .thinking("Let me think...")
                                                            .build(),
                                                    TextBlock.builder().text("The answer").build()))
                                    .build());

            List<OpenAIMessage> result = formatter.format(messages);

            assertEquals(2, result.size());
            OpenAIMessage assistantMsg = result.get(1);
            assertEquals("assistant", assistantMsg.getRole());
            // Preserved Thinking: kimi-k3 / kimi-k2.7-code require reasoning_content to be
            // passed back in the message history
            assertEquals("Let me think...", assistantMsg.getReasoningContent());
        }
    }

    @Nested
    @DisplayName("applyToolChoice Integration Tests")
    class ApplyToolChoiceIntegrationTests {

        @Test
        @DisplayName("Should apply tool choice through formatter")
        void testApplyToolChoiceThroughFormatter() {
            OpenAIRequest request = requestWithTools("kimi-k2.6");

            formatter.applyToolChoice(request, new ToolChoice.Required());

            // kimi-k2.6 does not support required, degraded to auto
            assertEquals("auto", request.getToolChoice());
        }
    }
}
