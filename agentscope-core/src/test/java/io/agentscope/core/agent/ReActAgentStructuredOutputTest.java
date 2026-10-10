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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.formatter.FailedAttempt;
import io.agentscope.core.formatter.JsonSchema;
import io.agentscope.core.formatter.StructuredOutputConfigurationException;
import io.agentscope.core.formatter.StructuredOutputParseException;
import io.agentscope.core.formatter.StructuredOutputRetryPolicy;
import io.agentscope.core.formatter.StructuredOutputUnknownFailureException;
import io.agentscope.core.formatter.StructuredOutputUtils;
import io.agentscope.core.formatter.StructuredOutputValidator;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
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

    @Test
    @DisplayName(
            "prose-wrapped conforming output: validation passes and structured metadata is"
                    + " populated")
    void testProseWrappedOutputYieldsStructuredData() {
        // The validation loop tolerates leading prose when extracting the payload;
        // the result wrapping must reuse that payload instead of re-parsing the raw
        // text (which would fail on prose and silently drop the structured metadata).
        MockModel nativeModel =
                new MockModel(
                        msgs ->
                                List.of(
                                        ChatResponse.builder()
                                                .id("msg_prose")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "好的，答案是：{\"answer\":"
                                                                                    + " 7}")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 0))
                                                .build())) {
                    @Override
                    public boolean supportsNativeStructuredOutput() {
                        return true;
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("math-agent")
                        .sysPrompt("You are a math assistant")
                        .model(nativeModel)
                        .toolkit(toolkit)
                        .build();

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What is 3 + 4?").build())
                        .build();

        Msg responseMsg = agent.call(inputMsg, MathAnswer.class).block();
        assertNotNull(responseMsg);

        MathAnswer result = responseMsg.getStructuredData(MathAnswer.class);
        assertNotNull(result, "structured metadata must survive prose-wrapped output");
        assertEquals(7, result.answer);
    }

    @Test
    @DisplayName("dedupe guard: message reusing a failed attempt's id is re-validated, not skipped")
    void testSameIdMessageRevalidatedAfterFailedAttempt() {
        // Attempt 1: id "msg_x", schema-violating payload (answer is a string).
        // Attempt 2: SAME id "msg_x", still violating. The dedupe guard records validated
        // ids only, so this attempt must be re-validated (and fail) instead of skipped.
        // Attempt 3: fresh id, conforming JSON. Total model calls must be 3; a guard that
        // skips the same-id second attempt would finish the call after 2 calls with an
        // unvalidated result.
        AtomicInteger calls = new AtomicInteger();
        MockModel nativeModel =
                new MockModel(
                        msgs -> {
                            int call = calls.incrementAndGet();
                            if (call == 1) {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_x")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "{\"answer\":"
                                                                                    + " \"not-a-number\"}")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 0))
                                                .build());
                            }
                            if (call == 2) {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_x")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "{\"answer\":"
                                                                                    + " \"still-bad\"}")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 0))
                                                .build());
                            }
                            return List.of(
                                    ChatResponse.builder()
                                            .id("msg_ok")
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("{\"answer\": 42}")
                                                                    .build()))
                                            .usage(new ChatUsage(5, 10, 0))
                                            .build());
                        }) {
                    @Override
                    public boolean supportsNativeStructuredOutput() {
                        return true;
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("math-agent")
                        .sysPrompt("You are a math assistant")
                        .model(nativeModel)
                        .toolkit(toolkit)
                        .structuredOutputPolicy(
                                StructuredOutputRetryPolicy.builder().maxAttempts(3).build())
                        .build();

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What is 3 + 4?").build())
                        .build();

        Msg responseMsg = agent.call(inputMsg, MathAnswer.class).block();
        assertNotNull(responseMsg);

        assertEquals(3, calls.get(), "same-id failed attempt must be re-validated, not skipped");
        MathAnswer result = responseMsg.getStructuredData(MathAnswer.class);
        assertNotNull(result);
        assertEquals(42, result.answer);
    }

    @Test
    @DisplayName("configuration error (uncompilable schema) propagates without retries or fallback")
    void testUncompilableSchemaPropagatesAsConfigurationError() {
        // A schema that fails to compile is a configuration error, not a model-output
        // problem: it must reach the caller as-is without burning the retry budget on
        // model calls (previously: maxAttempts calls, then a misleading
        // StructuredOutputValidationException) and without degrading to the synthetic
        // tool path (which reuses the same broken schema and would fail the same way).
        AtomicInteger calls = new AtomicInteger();
        MockModel nativeModel =
                new MockModel(
                        msgs -> {
                            calls.incrementAndGet();
                            return List.of(
                                    ChatResponse.builder()
                                            .id("msg_1")
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("{\"answer\": 42}")
                                                                    .build()))
                                            .usage(new ChatUsage(10, 20, 0))
                                            .build());
                        }) {
                    @Override
                    public boolean supportsNativeStructuredOutput() {
                        return true;
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("math-agent")
                        .sysPrompt("You are a math assistant")
                        .model(nativeModel)
                        .toolkit(toolkit)
                        .build();

        JsonNode brokenSchema =
                JsonUtils.getJsonCodec()
                        .fromJson(
                                "{\"type\":\"object\",\"properties\":{\"answer\":"
                                        + "{\"pattern\":\"[\"}}}",
                                JsonNode.class);

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What is 3 + 4?").build())
                        .build();

        StructuredOutputConfigurationException ex =
                assertThrows(
                        StructuredOutputConfigurationException.class,
                        () -> agent.call(List.of(inputMsg), brokenSchema).block());
        assertNotNull(ex.getCause(), "compilation failure must be preserved as the cause");
        assertEquals(
                1,
                calls.get(),
                "configuration error must not be retried and must not degrade to the tool"
                        + " path");
    }

    @Test
    @DisplayName("unknown transient failure keeps limited recovery: retried, then succeeds")
    void testUnknownTransientFailureIsRetriedAndRecovers() {
        // Attempt 1: extraction throws an unexpected NON-parse exception (injected via
        // the structuredOutputExtractionOverride seam — no model response can produce
        // this). The unknown branch must keep limited recovery instead of failing the
        // whole call. Attempt 2: the real extraction runs and succeeds.
        AtomicInteger calls = new AtomicInteger();
        MockModel flakyModel =
                new MockModel(
                        msgs ->
                                List.of(
                                        ChatResponse.builder()
                                                .id("msg_" + calls.incrementAndGet())
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("{\"answer\": 42}")
                                                                        .build()))
                                                .usage(new ChatUsage(5, 10, 0))
                                                .build())) {
                    @Override
                    public boolean supportsNativeStructuredOutput() {
                        return true;
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("math-agent")
                        .sysPrompt("You are a math assistant")
                        .model(flakyModel)
                        .toolkit(toolkit)
                        .build();

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What is 3 + 4?").build())
                        .build();

        Msg responseMsg;
        AtomicBoolean firstExtractionFails = new AtomicBoolean(true);
        // Only the first extraction fails; the retry runs the real extractor. The seam is
        // an instance field, so it applies regardless of the thread the reactive pipeline
        // runs the validation on (Mockito static mocks are thread-scoped and miss the
        // boundedElastic hop in the execution pipeline).
        setExtractionOverride(
                agent,
                text -> {
                    if (firstExtractionFails.getAndSet(false)) {
                        throw new IllegalStateException("transient registry hiccup");
                    }
                    return StructuredOutputUtils.extractJsonObject(text);
                });
        try {
            responseMsg = agent.call(inputMsg, MathAnswer.class).block();
            assertNotNull(responseMsg);
        } finally {
            setExtractionOverride(agent, null);
        }
        assertEquals(2, calls.get(), "transient failure must be retried, not fatal");
        assertEquals(42, responseMsg.getStructuredData(MathAnswer.class).answer);
    }

    @Test
    @DisplayName("persistent unknown failure exhausts retries and rethrows the original fault")
    void testPersistentUnknownFailureRethrowsOriginalException() {
        // A persistent internal fault must not masquerade as a model-output verdict:
        // extraction fails on every attempt (unknown domain), the default retry budget
        // (3 attempts) is consumed, and the caller gets the typed unknown-failure
        // wrapper — not a StructuredOutputValidationException, and with no extra
        // synthetic-tool round trip (the fallback router short-circuits the wrapper).
        AtomicInteger calls = new AtomicInteger();
        MockModel brokenModel =
                new MockModel(
                        msgs ->
                                List.of(
                                        ChatResponse.builder()
                                                .id("msg_" + calls.incrementAndGet())
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("{\"answer\": 42}")
                                                                        .build()))
                                                .usage(new ChatUsage(5, 10, 0))
                                                .build())) {
                    @Override
                    public boolean supportsNativeStructuredOutput() {
                        return true;
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("math-agent")
                        .sysPrompt("You are a math assistant")
                        .model(brokenModel)
                        .toolkit(toolkit)
                        .build();

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What is 3 + 4?").build())
                        .build();

        setExtractionOverride(
                agent,
                text -> {
                    throw new IllegalStateException("persistent internal fault");
                });
        try {
            StructuredOutputUnknownFailureException ex =
                    assertThrows(
                            StructuredOutputUnknownFailureException.class,
                            () -> agent.call(inputMsg, MathAnswer.class).block());
            assertEquals("persistent internal fault", ex.getCause().getMessage());
            assertEquals(
                    3,
                    calls.get(),
                    "the fault consumes the default retry budget (3 attempts) and is"
                            + " rethrown without a synthetic-tool round trip");
        } finally {
            setExtractionOverride(agent, null);
        }
    }

    @Test
    @DisplayName("unknown-domain correction turn sends the neutral marker instruction")
    void testUnknownDomainRetryUsesNeutralFeedback() {
        // Attempt 1 hits the unknown branch (extractJsonObject stubbed to throw a
        // non-parse exception); its correction turn must carry the NEUTRAL marker
        // instruction — not an assertion that the model's output was invalid JSON.
        // Attempt 2 recovers.
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> correctionText = new AtomicReference<>();
        MockModel neutralCheckModel =
                new MockModel(
                        msgs -> {
                            int n = calls.incrementAndGet();
                            if (n == 1) {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_1")
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("{\"answer\": 42}")
                                                                        .build()))
                                                .usage(new ChatUsage(5, 10, 0))
                                                .build());
                            }
                            msgs.stream()
                                    .flatMap(m -> m.getContent().stream())
                                    .filter(b -> b instanceof TextBlock)
                                    .map(b -> ((TextBlock) b).getText())
                                    .filter(
                                            t ->
                                                    t.contains("JSON object")
                                                            || t.contains("JSON Schema validation"))
                                    .findFirst()
                                    .ifPresent(correctionText::set);
                            return List.of(
                                    ChatResponse.builder()
                                            .id("msg_2")
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("{\"answer\": 42}")
                                                                    .build()))
                                            .usage(new ChatUsage(5, 10, 0))
                                            .build());
                        }) {
                    @Override
                    public boolean supportsNativeStructuredOutput() {
                        return true;
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("math-agent")
                        .sysPrompt("You are a math assistant")
                        .model(neutralCheckModel)
                        .toolkit(toolkit)
                        .build();

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What is 3 + 4?").build())
                        .build();

        Msg responseMsg;
        AtomicBoolean firstExtractionFails = new AtomicBoolean(true);
        setExtractionOverride(
                agent,
                text -> {
                    if (firstExtractionFails.getAndSet(false)) {
                        throw new IllegalStateException("transient registry hiccup");
                    }
                    return StructuredOutputUtils.extractJsonObject(text);
                });
        try {
            responseMsg = agent.call(inputMsg, MathAnswer.class).block();
            assertNotNull(responseMsg);
        } finally {
            setExtractionOverride(agent, null);
        }
        assertEquals(2, calls.get());
        assertEquals(42, responseMsg.getStructuredData(MathAnswer.class).answer);
        assertNotNull(
                correctionText.get(),
                "a structured-output correction turn must have been appended");
        // The unknown-domain correction must carry the neutral instruction — never the
        // "failed JSON Schema validation" wording that blames the model's output.
        assertTrue(
                correctionText.get().contains("internal validation error"),
                () -> "correction turn must be neutral, got: " + correctionText.get());
        assertFalse(correctionText.get().contains("failed JSON Schema validation"));
    }

    @Test
    @DisplayName("unknown-domain failure attaches the original exception to the attempt")
    void testUnknownDomainFailureCarriesRawExceptionOnAttempt() {
        // The unknown branch records the raw exception on the FailedAttempt (for
        // onFailedAttempt listeners) — not a synthesized parse complaint.
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<FailedAttempt> observed = new AtomicReference<>();
        MockModel rawCheckModel =
                new MockModel(
                        msgs ->
                                List.of(
                                        ChatResponse.builder()
                                                .id("msg_" + calls.incrementAndGet())
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("{\"answer\": 42}")
                                                                        .build()))
                                                .usage(new ChatUsage(5, 10, 0))
                                                .build())) {
                    @Override
                    public boolean supportsNativeStructuredOutput() {
                        return true;
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("math-agent")
                        .sysPrompt("You are a math assistant")
                        .model(rawCheckModel)
                        .toolkit(toolkit)
                        .structuredOutputPolicy(
                                StructuredOutputRetryPolicy.builder()
                                        .maxAttempts(3)
                                        .onFailedAttempt(observed::set)
                                        .build())
                        .build();

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What is 3 + 4?").build())
                        .build();

        Msg responseMsg;
        AtomicBoolean firstExtractionFails = new AtomicBoolean(true);
        setExtractionOverride(
                agent,
                text -> {
                    if (firstExtractionFails.getAndSet(false)) {
                        throw new IllegalStateException("transient registry hiccup");
                    }
                    return StructuredOutputUtils.extractJsonObject(text);
                });
        try {
            responseMsg = agent.call(inputMsg, MathAnswer.class).block();
            assertNotNull(responseMsg);
        } finally {
            setExtractionOverride(agent, null);
        }
        assertEquals(42, responseMsg.getStructuredData(MathAnswer.class).answer);

        FailedAttempt failed = observed.get();
        assertNotNull(failed, "onFailedAttempt must observe the unknown-domain attempt");
        assertEquals(FailedAttempt.Kind.UNKNOWN_FAILURE, failed.kind());
        assertEquals(StructuredOutputValidator.UNKNOWN_FAILURE_MARKER, failed.parseErrorMessage());
        assertTrue(
                failed.rawException() instanceof IllegalStateException,
                () -> "raw exception must be preserved, got: " + failed.rawException());
    }

    @Test
    @DisplayName("retryPrompt: neutral instruction vs schema-complaint vs >5-error truncation")
    void testRetryPromptBranches() {
        // Unknown-domain failures use the dedicated neutral prompt.
        String neutral = StructuredOutputUtils.unknownFailurePrompt();
        assertTrue(neutral.contains("internal validation error"));
        assertTrue(neutral.contains("respond again with a JSON object"));
        assertFalse(neutral.contains("failed JSON Schema validation"));

        // Normal schema complaint path stays unchanged.
        String complaint =
                StructuredOutputUtils.retryPrompt(
                        List.of(
                                new StructuredOutputValidator.ValidationError(
                                        "#/answer", "string found, integer required")));
        assertTrue(complaint.contains("failed JSON Schema validation"));
        assertFalse(complaint.contains("internal validation error"));

        // More than five errors are truncated with a total count.
        List<StructuredOutputValidator.ValidationError> many = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            many.add(
                    new StructuredOutputValidator.ValidationError(
                            "#/p" + i, "missing required property p" + i));
        }
        String truncated = StructuredOutputUtils.retryPrompt(many);
        assertTrue(truncated.contains("and 8 errors in total"));
        assertFalse(truncated.contains("p7:"));
    }

    @Test
    @DisplayName("retryPrompt/code-fence edge branches: empty input, null, malformed fence")
    void testRetryPromptAndFenceEdgeBranches() {
        assertEquals("", StructuredOutputUtils.retryPrompt(List.of()));
        assertEquals("", StructuredOutputUtils.retryPrompt(null));
        // Malformed fence (opening ``` without a newline) falls through to readTree and
        // reports a parse error — covers the malformed-fence branch of stripCodeFence.
        assertThrows(
                StructuredOutputParseException.class,
                () -> StructuredOutputUtils.extractJsonObject("```not json at all"));
        // Null output is reported as a validation error, not thrown.
        JsonSchema nullOutputSchema =
                JsonSchema.builder().name("null-check").schema(Map.of("type", "object")).build();
        List<StructuredOutputValidator.ValidationError> errors =
                StructuredOutputValidator.validate(null, nullOutputSchema);
        assertEquals(1, errors.size());
        assertEquals("$", errors.get(0).instanceLocation());
        assertTrue(errors.get(0).message().contains("output is null"));
    }

    @Test
    @DisplayName(
            "error-feedback retry: invalid first attempt corrected, failed-turn thinking does not"
                    + " leak")
    void testStructuredOutputErrorFeedbackRetry() {
        // Attempt 1: thinking + invalid JSON (answer is string, schema requires number)
        // Attempt 2 (after error feedback): conforming JSON
        MockModel nativeModel =
                new MockModel(
                        msgs -> {
                            boolean hasFeedback =
                                    msgs.stream()
                                            .filter(m -> m.getRole() == MsgRole.USER)
                                            .flatMap(m -> m.getContent().stream())
                                            .filter(b -> b instanceof TextBlock)
                                            .map(b -> ((TextBlock) b).getText())
                                            .anyMatch(
                                                    t ->
                                                            t.contains(
                                                                            "failed JSON Schema"
                                                                                    + " validation")
                                                                    || t.contains(
                                                                            "JSON Schema 校验"));
                            if (!hasFeedback) {
                                return List.of(
                                        ChatResponse.builder()
                                                .id("msg_bad")
                                                .content(
                                                        List.of(
                                                                ThinkingBlock.builder()
                                                                        .thinking(
                                                                                "bad attempt"
                                                                                    + " thinking")
                                                                        .build(),
                                                                TextBlock.builder()
                                                                        .text(
                                                                                "{\"answer\":"
                                                                                    + " \"not-a-number\"}")
                                                                        .build()))
                                                .usage(new ChatUsage(10, 20, 30))
                                                .build());
                            }
                            return List.of(
                                    ChatResponse.builder()
                                            .id("msg_good")
                                            .content(
                                                    List.of(
                                                            TextBlock.builder()
                                                                    .text("{\"answer\": 42}")
                                                                    .build()))
                                            .usage(new ChatUsage(5, 10, 15))
                                            .build());
                        }) {
                    @Override
                    public boolean supportsNativeStructuredOutput() {
                        return true;
                    }
                };

        ReActAgent agent =
                ReActAgent.builder()
                        .name("math-agent")
                        .sysPrompt("You are a math assistant")
                        .model(nativeModel)
                        .toolkit(toolkit)
                        .build();

        Msg inputMsg =
                Msg.builder()
                        .name("user")
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What is 6 * 7?").build())
                        .build();

        Msg responseMsg = agent.call(inputMsg, MathAnswer.class).block();
        assertNotNull(responseMsg);

        MathAnswer result = responseMsg.getStructuredData(MathAnswer.class);
        assertNotNull(result);
        assertEquals(42, result.answer);

        boolean leakedThinking =
                responseMsg.getContent().stream()
                        .anyMatch(
                                b ->
                                        b instanceof ThinkingBlock
                                                && ((ThinkingBlock) b).getThinking() != null
                                                && ((ThinkingBlock) b)
                                                        .getThinking()
                                                        .contains("bad attempt"));
        assertTrue(!leakedThinking, "failed-turn thinking leaked into final message");
    }

    /**
     * Sets the structured-output extraction seam on the agent. Uses reflection because the
     * field is private to {@code io.agentscope.core.ReActAgent} while this suite lives in
     * {@code io.agentscope.core.agent} (same pattern as ReActAgentPerSessionStateTest).
     */
    private static void setExtractionOverride(ReActAgent agent, Function<String, JsonNode> fn) {
        try {
            java.lang.reflect.Field field =
                    ReActAgent.class.getDeclaredField("structuredOutputExtractionOverride");
            field.setAccessible(true);
            field.set(agent, fn);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to set extraction override", e);
        }
    }

    /** Schema target for the error-feedback retry test. */
    static class MathAnswer {
        public int answer;
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
