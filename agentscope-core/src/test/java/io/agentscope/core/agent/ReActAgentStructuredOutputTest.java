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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PostReasoningEvent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.memory.Memory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.ToolChoice;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class ReActAgentStructuredOutputTest {

    private Toolkit toolkit;

    static class WeatherResponse {
        public String location;
        public String temperature;
        public String condition;
    }

    static class WeatherTools {
        @Tool(description = "Look up the current weather for a city")
        public String lookupWeather(@ToolParam(name = "city", description = "city") String city) {
            return "Sunny, 72°F";
        }
    }

    @BeforeEach
    void setUp() {
        toolkit = new Toolkit();
    }

    @Test
    void testStructuredOutputToolBased() {
        Memory memory = new InMemoryMemory();

        // Create a mock model that returns:
        // 1. First call: tool call for generate_response
        // 2. Second call (after tool execution): simple text response (finished)
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
                            // Check if we have any TOOL role messages (tool execution results)
                            boolean hasToolResults =
                                    msgs.stream().anyMatch(m -> m.getRole() == MsgRole.TOOL);

                            if (!hasToolResults) {
                                // First call: return tool use for generate_response
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_1")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("call_123")
                                                                        .name("generate_response")
                                                                        .input(toolInput)
                                                                        .content(
                                                                                JsonUtils
                                                                                        .getJsonCodec()
                                                                                        .toJson(
                                                                                                toolInput))
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            } else {
                                // Second call (after tool execution): return simple text
                                // (no more tool calls, indicating we're done)
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_2")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Response generated")
                                                                        .build()))
                                                .usage(new ChatUsage(5, 10, 15))
                                                .build());
                            }
                        });

        // Create agent with TOOL_BASED strategy
        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .build();

        // Execute structured output call
        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("What's the weather in San Francisco?")
                                        .build())
                        .build();

        // Call agent and extract structured data from response message
        Msg responseMsg = agent.call(inputMsg, WeatherResponse.class).block();
        assertNotNull(responseMsg);
        assertNotNull(responseMsg.getMetadata());

        // Extract structured data from metadata
        WeatherResponse result = responseMsg.getStructuredData(WeatherResponse.class);

        // Verify
        assertNotNull(result);
        assertEquals("San Francisco", result.location);
        assertEquals("72°F", result.temperature);
        assertEquals("Sunny", result.condition);
    }

    @Test
    void testStructuredOutputPreservesChatUsage() {
        Memory memory = new InMemoryMemory();

        // Create a mock model that returns tool call with ChatUsage
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
                                // First call: return tool use with usage
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_1")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("call_123")
                                                                        .name("generate_response")
                                                                        .input(toolInput)
                                                                        .content(
                                                                                JsonUtils
                                                                                        .getJsonCodec()
                                                                                        .toJson(
                                                                                                toolInput))
                                                                        .build()))
                                                .usage(new ChatUsage(100, 50, 1.5))
                                                .build());
                            } else {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_2")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Done")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 5, 0.1))
                                                .build());
                            }
                        });

        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .build();

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("What's the weather in San Francisco?")
                                        .build())
                        .build();

        Msg responseMsg = agent.call(inputMsg, WeatherResponse.class).block();
        assertNotNull(responseMsg);

        // Verify structured output
        WeatherResponse result = responseMsg.getStructuredData(WeatherResponse.class);
        assertNotNull(result);
        assertEquals("San Francisco", result.location);

        // Verify ChatUsage is preserved after memory compression
        ChatUsage usage = responseMsg.getChatUsage();
        assertNotNull(usage, "ChatUsage should be preserved after structured output compression");
        assertEquals(100, usage.getInputTokens(), "Input tokens should be preserved");
        assertEquals(50, usage.getOutputTokens(), "Output tokens should be preserved");
        assertEquals(1.5, usage.getTime(), 0.01, "Time should be preserved");

        ChatUsage canonicalUsage = responseMsg.getUsage();
        assertNotNull(canonicalUsage, "Canonical usage field should be preserved");
        assertEquals(100, canonicalUsage.getInputTokens());
        assertEquals(50, canonicalUsage.getOutputTokens());
        assertEquals(1.5, canonicalUsage.getTime(), 0.01);
    }

    @Test
    void testStructuredOutputPreservesThinkingBlock() {
        Memory memory = new InMemoryMemory();

        // Create a mock model that returns tool call with ThinkingBlock
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
                                // First call: return ThinkingBlock + tool use
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_1")
                                                .content(
                                                        List.of(
                                                                ThinkingBlock.builder()
                                                                        .thinking(
                                                                                "Let me analyze the"
                                                                                    + " weather"
                                                                                    + " data for"
                                                                                    + " San Francisco...")
                                                                        .build(),
                                                                ToolUseBlock.builder()
                                                                        .id("call_123")
                                                                        .name("generate_response")
                                                                        .input(toolInput)
                                                                        .content(
                                                                                JsonUtils
                                                                                        .getJsonCodec()
                                                                                        .toJson(
                                                                                                toolInput))
                                                                        .build()))
                                                .usage(new ChatUsage(100, 50, 1.5))
                                                .build());
                            } else {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_2")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Done")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 5, 0.1))
                                                .build());
                            }
                        });

        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .build();

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("What's the weather in San Francisco?")
                                        .build())
                        .build();

        Msg responseMsg = agent.call(inputMsg, WeatherResponse.class).block();
        assertNotNull(responseMsg);

        // Verify structured output
        WeatherResponse result = responseMsg.getStructuredData(WeatherResponse.class);
        assertNotNull(result);
        assertEquals("San Francisco", result.location);

        // Verify ThinkingBlock is preserved after memory compression
        ThinkingBlock thinking = responseMsg.getFirstContentBlock(ThinkingBlock.class);
        assertNotNull(
                thinking, "ThinkingBlock should be preserved after structured output compression");
        assertEquals(
                "Let me analyze the weather data for San Francisco...",
                thinking.getThinking(),
                "Thinking content should be preserved");
    }

    @Test
    void testConcurrencyConflictStructuredOutput() {
        runSubscribeOnThenSequentialSecondCallStructuredOutputScenario(toolkit);
    }

    /**
     * Reproduces the subscribeOn(elastic) vs delayed {@code doFinally} race: run many times until
     * failure or increase confidence after a fix.
     */
    @EnabledIfSystemProperty(named = "agentscope.runStructuredOutputRaceTest", matches = "true")
    @RepeatedTest(25000)
    @DisplayName("Structured output race: 25000 repetitions (subscribeOn + immediate second call)")
    void testConcurrencyConflictStructuredOutput_repeated() {
        runSubscribeOnThenSequentialSecondCallStructuredOutputScenario(toolkit);
    }

    /**
     * First call on {@link Schedulers#boundedElastic()}, second call immediately on the calling
     * thread — same agent and toolkit. Flaky when structured-output cleanup races the second
     * registration.
     */
    private void runSubscribeOnThenSequentialSecondCallStructuredOutputScenario(
            Toolkit agentToolkit) {
        Memory memory = new InMemoryMemory();
        Map<String, Object> toolInput =
                Map.of(
                        "response",
                        Map.of(
                                "location", "San Francisco",
                                "temperature", "72°F",
                                "condition", "Sunny"));

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            boolean hasToolResults =
                                    msgs.stream().anyMatch(m -> m.getRole() == MsgRole.TOOL);
                            if (!hasToolResults) {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_1")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("call_123")
                                                                        .name("generate_response")
                                                                        .input(toolInput)
                                                                        .content(
                                                                                JsonUtils
                                                                                        .getJsonCodec()
                                                                                        .toJson(
                                                                                                toolInput))
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            } else {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_2")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Done")
                                                                        .build()))
                                                .usage(new ChatUsage(5, 10, 15))
                                                .build());
                            }
                        });

        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(mockModel)
                        .toolkit(agentToolkit)
                        .build();

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("What's the weather in San Francisco?")
                                        .build())
                        .build();

        Msg responseMsg =
                agent.call(inputMsg, WeatherResponse.class)
                        .subscribeOn(Schedulers.boundedElastic())
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS * 10000));

        Msg response2 =
                agent.call(inputMsg, WeatherResponse.class)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        assertNotNull(responseMsg);
        WeatherResponse result = responseMsg.getStructuredData(WeatherResponse.class);
        assertNotNull(result);
        assertEquals("San Francisco", result.location);
        assertEquals("72°F", result.temperature);
        assertEquals("Sunny", result.condition);

        assertNotNull(response2);
        // no IllegalStateException throw
        WeatherResponse result2 = response2.getStructuredData(WeatherResponse.class);
        assertNotNull(result2);
    }

    @Test
    @DisplayName("Should not throw NPE when PostReasoning hook nulls out the reasoning message")
    void testStructuredOutputNullReasoningMessage() {
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
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_1")
                                                .content(
                                                        List.of(
                                                                ToolUseBlock.builder()
                                                                        .id("call_123")
                                                                        .name("generate_response")
                                                                        .input(toolInput)
                                                                        .content(
                                                                                JsonUtils
                                                                                        .getJsonCodec()
                                                                                        .toJson(
                                                                                                toolInput))
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            } else {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_2")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("Done")
                                                                        .build()))
                                                .usage(new ChatUsage(5, 10, 15))
                                                .build());
                            }
                        });

        @SuppressWarnings("deprecation")
        Hook nullMessageHook =
                new Hook() {
                    @Override
                    @SuppressWarnings("unchecked")
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof PostReasoningEvent pre) {
                            pre.setReasoningMessage(null);
                        }
                        return Mono.just(event);
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .hook(nullMessageHook)
                        .build();

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(
                                TextBlock.builder()
                                        .text("What's the weather in San Francisco?")
                                        .build())
                        .build();

        // Before the fix, this threw NullPointerException: value at MonoJust.<init>
        Msg responseMsg =
                agent.call(inputMsg, WeatherResponse.class)
                        .block(Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS));

        // The agent should handle null eventMsg gracefully — either by returning
        // empty or by continuing the loop. No NPE should be thrown.
    }

    /** Model emits free text twice, then complies when forced via {@code ToolChoice.Specific}. */
    @Test
    @DisplayName("Forces generate_response via tool_choice when the model skips the tool")
    void testStructuredOutputForcesToolChoiceWhenModelSkipsTool() {
        Map<String, Object> toolInput = weatherToolInput();
        AtomicInteger calls = new AtomicInteger();

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            int n = calls.getAndIncrement();
                            if (n < 2) {
                                // Rounds 0 and 1: the model ignores generate_response and emits
                                // free text, triggering the forced-retry path.
                                return List.of(textResponse("msg_" + n, "Plain text answer."));
                            }
                            // Round 2: forced by tool_choice, the model finally complies.
                            return List.of(structuredToolResponse("msg_" + n, toolInput));
                        });

        ReActAgent agent = buildWeatherAgent(mockModel);

        Msg responseMsg = agent.call(weatherInput(), WeatherResponse.class).block();
        assertNotNull(responseMsg);

        WeatherResponse result = responseMsg.getStructuredData(WeatherResponse.class);
        assertNotNull(result);
        assertEquals("San Francisco", result.location);

        // 1 initial free-text round + 2 free-text forced retries = 3 reasoning calls total,
        // with generate_response called on the final round.
        assertEquals(3, mockModel.getCallCount());

        // The final round was forced via ToolChoice.Specific.
        assertTrue(
                mockModel.getLastOptions().getToolChoice() instanceof ToolChoice.Specific,
                "Expected a ToolChoice.Specific on the forced round");
        assertEquals(
                "generate_response",
                ((ToolChoice.Specific) mockModel.getLastOptions().getToolChoice()).toolName());
    }

    /** Gives up after 3 forced retries when the model never calls {@code generate_response}. */
    @Test
    @DisplayName("Gives up after 3 forced retries when the model never calls generate_response")
    void testStructuredOutputGivesUpAfterThreeForcedRetries() {
        AtomicInteger calls = new AtomicInteger();

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            // The model never calls generate_response.
                            return List.of(
                                    textResponse(
                                            "msg_" + calls.getAndIncrement(),
                                            "I'll just answer in plain text."));
                        });

        ReActAgent agent = buildWeatherAgent(mockModel);

        Msg responseMsg = agent.call(weatherInput(), WeatherResponse.class).block();
        assertNotNull(responseMsg);

        // No structured data was produced; the loop gave up rather than deadlocking.
        assertFalse(responseMsg.hasStructuredData());
        // 1 initial round + 3 forced retries = 4 reasoning calls, then give up.
        assertEquals(4, mockModel.getCallCount());

        // The final (give-up) round was still forced via tool_choice before finishing.
        assertTrue(mockModel.getLastOptions().getToolChoice() instanceof ToolChoice.Specific);
    }

    /** Sends a prompt reminder on the first fallback call when {@code ToolChoice.Specific} is unsupported. */
    @Test
    @DisplayName(
            "Sends prompt reminder on first fallback call when ToolChoice.Specific unsupported")
    void testStructuredOutputPromptReminderWhenToolChoiceUnsupported() {
        Map<String, Object> toolInput = weatherToolInput();
        AtomicInteger calls = new AtomicInteger();

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            int n = calls.getAndIncrement();
                            boolean reminderPresent =
                                    msgs.stream()
                                            .anyMatch(
                                                    m ->
                                                            m.getTextContent() != null
                                                                    && m.getTextContent()
                                                                            .contains(
                                                                                    "You MUST call"
                                                                                        + " the `generate_response`"
                                                                                        + " tool"));
                            if (reminderPresent) {
                                // The model complies after seeing the prompt reminder.
                                return List.of(structuredToolResponse("msg_" + n, toolInput));
                            }
                            return List.of(textResponse("msg_" + n, "Plain text answer."));
                        });
        mockModel.setSupportsToolChoiceSpecific(false);

        ReActAgent agent = buildWeatherAgent(mockModel);

        Msg responseMsg = agent.call(weatherInput(), WeatherResponse.class).block();
        assertNotNull(responseMsg);

        WeatherResponse result = responseMsg.getStructuredData(WeatherResponse.class);
        assertNotNull(result);
        assertEquals("San Francisco", result.location);

        // The always-on reminder is visible from the first fallback round.
        assertEquals(1, mockModel.getCallCount());

        // The prompt strategy must NOT set tool_choice.
        assertNull(mockModel.getLastOptions().getToolChoice());
    }

    /** The request-local reminder is present once on every fallback round. */
    @Test
    @DisplayName("Sends one request-local reminder on every fallback round")
    void testStructuredOutputPromptReminderPresentOnEveryFallbackRound() {
        Map<String, Object> toolInput = weatherToolInput();
        AtomicInteger calls = new AtomicInteger();
        List<Integer> reminderCounts = new CopyOnWriteArrayList<>();

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            int n = calls.getAndIncrement();
                            reminderCounts.add(
                                    (int)
                                            countTextOccurrences(
                                                    msgs,
                                                    "You MUST call the `generate_response`"
                                                            + " tool"));
                            if (n < 2) {
                                return List.of(textResponse("msg_" + n, "Plain text answer."));
                            }
                            return List.of(structuredToolResponse("msg_" + n, toolInput));
                        });
        mockModel.setSupportsToolChoiceSpecific(false);

        ReActAgent agent = buildWeatherAgent(mockModel);

        Msg responseMsg = agent.call(weatherInput(), WeatherResponse.class).block();
        assertNotNull(responseMsg);
        assertNotNull(responseMsg.getStructuredData(WeatherResponse.class));

        // Every round sees exactly one reminder, including retries after free-text output.
        assertEquals(3, mockModel.getCallCount());
        assertEquals(List.of(1, 1, 1), reminderCounts);
    }

    /** After give-up, a later normal call does not receive the structured-output reminder. */
    @Test
    @DisplayName("Does not send structured-output reminder on a later normal call")
    void testStructuredOutputGiveUpLeavesNoReminderForLaterNormalCall() {
        AtomicInteger calls = new AtomicInteger();

        MockModel mockModel =
                new MockModel(
                        msgs ->
                                List.of(
                                        textResponse(
                                                "msg_" + calls.getAndIncrement(),
                                                "I'll just answer in plain text.")));
        mockModel.setSupportsToolChoiceSpecific(false);

        ReActAgent agent = buildWeatherAgent(mockModel);

        Msg responseMsg = agent.call(weatherInput(), WeatherResponse.class).block();
        assertNotNull(responseMsg);
        // No structured data; the loop gave up rather than deadlocking.
        assertFalse(responseMsg.hasStructuredData());
        // 1 initial round + 3 forced retries = 4 reasoning calls, then give up.
        assertEquals(4, mockModel.getCallCount());

        // Follow-up normal call: its input contains no request-local structured-output reminder.
        Msg followUp = agent.call(weatherInput()).block();
        assertNotNull(followUp);
        assertEquals(
                0,
                countTextOccurrences(
                        mockModel.getLastMessages(), "You MUST call the `generate_response` tool"));
    }

    /** Merges the transient reminder into the last user message without changing durable state. */
    @Test
    @DisplayName("Merges reminder into last user message without changing durable state")
    void testStructuredOutputReminderMergesIntoLastUserMessage() {
        Map<String, Object> toolInput = weatherToolInput();

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            boolean reminderPresent =
                                    msgs.stream()
                                            .anyMatch(
                                                    m ->
                                                            m.getTextContent() != null
                                                                    && m.getTextContent()
                                                                            .contains(
                                                                                    "You MUST call"
                                                                                        + " the `generate_response`"
                                                                                        + " tool"));
                            if (reminderPresent) {
                                return List.of(structuredToolResponse("msg_so", toolInput));
                            }
                            return List.of(textResponse("msg_text", "ok"));
                        });

        ReActAgent agent = buildWeatherAgent(mockModel);

        Msg responseMsg = agent.call(weatherInput(), WeatherResponse.class).block();
        assertNotNull(responseMsg);
        assertNotNull(responseMsg.getStructuredData(WeatherResponse.class));

        List<Msg> requestMessages = mockModel.getLastMessages();
        assertNotNull(requestMessages);
        Msg lastMessage = requestMessages.get(requestMessages.size() - 1);
        assertEquals(MsgRole.USER, lastMessage.getRole());
        assertTrue(lastMessage.getTextContent().contains("What's the weather in San Francisco?"));
        assertTrue(lastMessage.getTextContent().contains("You MUST call the `generate_response`"));

        List<Msg> durableContext = agent.getAgentState().getContext();
        assertEquals(
                0,
                countTextOccurrences(durableContext, "You MUST call the `generate_response` tool"));
        durableContext.stream()
                .filter(msg -> msg.getRole() == MsgRole.USER)
                .filter(msg -> msg.getTextContent().contains("What's the weather in San"))
                .findFirst()
                .ifPresent(
                        msg ->
                                assertFalse(
                                        msg.getTextContent()
                                                .contains(
                                                        "You MUST call the"
                                                                + " `generate_response`")));
    }

    /** Adds a separate user reminder when the final model input ends with a tool result. */
    @Test
    @DisplayName("Adds separate user reminder after a tool result")
    void testStructuredOutputReminderAfterToolResultUsesSeparateUserMessage() {
        Map<String, Object> toolInput = weatherToolInput();
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> businessToolInput = Map.of("city", "San Francisco");

        MockModel mockModel =
                new MockModel(
                        msgs -> {
                            if (calls.getAndIncrement() == 0) {
                                return List.of(
                                        businessToolResponse(
                                                "business_1", "lookupWeather", businessToolInput));
                            }
                            return List.of(structuredToolResponse("so_1", toolInput));
                        });

        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WeatherTools());
        ReActAgent agent =
                ReActAgent.builder()
                        .name("weather-agent")
                        .sysPrompt("You are a weather assistant")
                        .model(mockModel)
                        .toolkit(toolkit)
                        .build();

        Msg responseMsg = agent.call(weatherInput(), WeatherResponse.class).block();
        assertNotNull(responseMsg);
        assertNotNull(responseMsg.getStructuredData(WeatherResponse.class));
        assertEquals(2, mockModel.getCallCount());

        List<Msg> requestMessages = mockModel.getLastMessages();
        assertNotNull(requestMessages);
        assertTrue(requestMessages.size() >= 2);
        assertEquals(MsgRole.TOOL, requestMessages.get(requestMessages.size() - 2).getRole());
        Msg reminderMessage = requestMessages.get(requestMessages.size() - 1);
        assertEquals(MsgRole.USER, reminderMessage.getRole());
        assertTrue(
                reminderMessage.getTextContent().contains("You MUST call the `generate_response`"));
        assertEquals(
                1,
                countTextOccurrences(
                        requestMessages, "You MUST call the `generate_response` tool"));
        assertEquals(
                0,
                countTextOccurrences(
                        agent.getAgentState().getContext(),
                        "You MUST call the `generate_response` tool"));
    }

    // ==================== Helpers ====================

    /** Builds a weather agent bound to the given mock model. */
    private static ReActAgent buildWeatherAgent(MockModel mockModel) {
        return ReActAgent.builder()
                .name("weather-agent")
                .sysPrompt("You are a weather assistant")
                .model(mockModel)
                .toolkit(new Toolkit())
                .build();
    }

    /** Builds the standard user weather-query message. */
    private static Msg weatherInput() {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text("What's the weather in San Francisco?").build())
                .build();
    }

    /** Builds the {@code generate_response} tool input payload. */
    private static Map<String, Object> weatherToolInput() {
        return Map.of(
                "response",
                Map.of("location", "San Francisco", "temperature", "72°F", "condition", "Sunny"));
    }

    /** Builds a free-text response with the given id and text. */
    private static ChatResponse textResponse(String id, String text) {
        return ChatResponse.builder()
                .id(id)
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(1, 2, 3))
                .build();
    }

    /** Builds a {@code generate_response} tool-call response with the given payload. */
    private static ChatResponse structuredToolResponse(String id, Map<String, Object> toolInput) {
        return ChatResponse.builder()
                .id(id)
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id(id)
                                        .name("generate_response")
                                        .input(toolInput)
                                        .content(JsonUtils.getJsonCodec().toJson(toolInput))
                                        .build()))
                .usage(new ChatUsage(10, 20, 30))
                .build();
    }

    /** Builds a business tool-call response with the given payload. */
    private static ChatResponse businessToolResponse(
            String id, String toolName, Map<String, Object> toolInput) {
        return ChatResponse.builder()
                .id(id)
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id(id)
                                        .name(toolName)
                                        .input(toolInput)
                                        .content(JsonUtils.getJsonCodec().toJson(toolInput))
                                        .build()))
                .usage(new ChatUsage(1, 2, 3))
                .build();
    }

    /** Counts messages whose text content contains the given substring. */
    private static long countTextOccurrences(List<Msg> msgs, String needle) {
        if (msgs == null) {
            return 0;
        }
        return msgs.stream()
                .filter(m -> m.getTextContent() != null && m.getTextContent().contains(needle))
                .count();
    }
}
