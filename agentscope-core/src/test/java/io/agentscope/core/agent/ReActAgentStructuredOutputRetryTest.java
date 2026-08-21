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

package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the structured-output fallback self-healing loop: when the model answers with plain
 * text instead of calling the synthetic {@code generate_response} tool, the agent injects a
 * reminder message and retries the reasoning step with {@code tool_choice} forced to the
 * structured-output tool (parity with the 1.x {@code StructuredOutputHook}).
 */
class ReActAgentStructuredOutputRetryTest {

    private Toolkit toolkit;

    static class WeatherResponse {
        public String location;
        public String temperature;
        public String condition;
    }

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
    }

    private static boolean isReminder(Msg msg) {
        Map<String, Object> metadata = msg.getMetadata();
        return metadata != null
                && Boolean.TRUE.equals(
                        metadata.get(MessageMetadataKeys.STRUCTURED_OUTPUT_REMINDER));
    }

    private static ChatResponse textResponse(String id, String text) {
        return ChatResponse.builder()
                .id(id)
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(10, 20, 30))
                .build();
    }

    private static ChatResponse generateResponseToolCall(String id, Map<String, Object> toolInput) {
        return ChatResponse.builder()
                .id(id)
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id("call_retry")
                                        .name(ReActAgent.STRUCTURED_OUTPUT_TOOL_NAME)
                                        .input(toolInput)
                                        .content(JsonUtils.getJsonCodec().toJson(toolInput))
                                        .build()))
                .usage(new ChatUsage(10, 20, 30))
                .build();
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    @Test
    @DisplayName("Plain-text answer triggers reminder + forced tool_choice retry, then succeeds")
    void retriesWhenModelReturnsPlainTextInsteadOfCallingGenerateResponse() {
        Map<String, Object> toolInput =
                Map.of(
                        "response",
                        Map.of(
                                "location",
                                "San Francisco",
                                "temperature",
                                "72°F",
                                "condition",
                                "Sunny"));

        // Round 1 (no reminder yet): plain text, no tool call — the buggy behaviour we heal.
        // Round 2 (reminder present): the model complies and calls generate_response.
        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            boolean reminded =
                                    msgs.stream()
                                            .anyMatch(
                                                    ReActAgentStructuredOutputRetryTest
                                                            ::isReminder);
                            if (!reminded) {
                                return List.of(
                                        textResponse(
                                                "msg_text",
                                                "The weather in San Francisco is 72°F and"
                                                        + " sunny."));
                            }
                            boolean hasToolResults =
                                    msgs.stream().anyMatch(m -> m.getRole() == MsgRole.TOOL);
                            if (!hasToolResults) {
                                return List.of(generateResponseToolCall("msg_tool", toolInput));
                            }
                            return List.of(textResponse("msg_done", "Done"));
                        });

        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .build();

        Msg responseMsg =
                agent.call(userMsg("What's the weather in San Francisco?"), WeatherResponse.class)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(responseMsg);
        assertTrue(
                responseMsg.hasStructuredData(),
                "Structured data should be present after the self-healing retry");
        WeatherResponse result = responseMsg.getStructuredData(WeatherResponse.class);
        assertNotNull(result);
        assertEquals("San Francisco", result.location);
        assertEquals("72°F", result.temperature);
        assertEquals("Sunny", result.condition);

        // The retry round must force tool_choice to the structured-output tool.
        assertNotNull(mockModel.getLastOptions(), "Retry round should carry GenerateOptions");
        assertEquals(
                new ToolChoice.Specific(ReActAgent.STRUCTURED_OUTPUT_TOOL_NAME),
                mockModel.getLastOptions().getToolChoice(),
                "Retry round should force tool_choice to generate_response");
    }

    @Test
    @DisplayName("Gives up after max retries and returns the plain-text message")
    void givesUpAfterMaxRetriesAndReturnsPlainTextResult() {
        // The model never calls generate_response, no matter how often it is reminded.
        MockModel mockModel =
                new MockModel(
                        msgs ->
                                List.of(
                                        textResponse(
                                                "msg_stubborn",
                                                "I refuse to call tools and just chat.")));

        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .build();

        Msg responseMsg =
                agent.call(userMsg("What's the weather in San Francisco?"), WeatherResponse.class)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(responseMsg);
        assertFalse(
                responseMsg.hasStructuredData(),
                "No structured data when the model never calls generate_response");
        // Initial round + 3 reminder retries (parity with 1.x StructuredOutputHook MAX_RETRIES).
        assertEquals(
                4,
                mockModel.getCallCount(),
                "Should attempt the initial round plus exactly 3 retries");

        // The injected reminders are internal artifacts: a follow-up turn on the same agent
        // must not see them in its model input.
        Msg followUp =
                agent.call(userMsg("Just say hi"))
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));
        assertNotNull(followUp);
        assertFalse(
                mockModel.getLastMessages().stream()
                        .anyMatch(ReActAgentStructuredOutputRetryTest::isReminder),
                "Give-up path should strip reminder messages from the persisted context");
        assertEquals(
                1,
                mockModel.getLastMessages().stream()
                        .filter(m -> m.getRole() == MsgRole.ASSISTANT)
                        .filter(
                                m ->
                                        "I refuse to call tools and just chat."
                                                .equals(m.getTextContent()))
                        .count(),
                "Give-up path should retain only the final plain-text assistant result");
    }

    @Test
    @DisplayName("Voluntary generate_response call on the first round needs no retry")
    void noRetryWhenModelCallsGenerateResponseVoluntarily() {
        Map<String, Object> toolInput =
                Map.of(
                        "response",
                        Map.of(
                                "location",
                                "San Francisco",
                                "temperature",
                                "72°F",
                                "condition",
                                "Sunny"));

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            boolean hasToolResults =
                                    msgs.stream().anyMatch(m -> m.getRole() == MsgRole.TOOL);
                            if (!hasToolResults) {
                                return List.of(generateResponseToolCall("msg_tool", toolInput));
                            }
                            return List.of(textResponse("msg_done", "Done"));
                        });

        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .build();

        Msg responseMsg =
                agent.call(userMsg("What's the weather in San Francisco?"), WeatherResponse.class)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(responseMsg);
        WeatherResponse result = responseMsg.getStructuredData(WeatherResponse.class);
        assertNotNull(result);
        assertEquals("San Francisco", result.location);
        assertEquals(1, mockModel.getCallCount(), "No retry needed — single model call");
    }
}
