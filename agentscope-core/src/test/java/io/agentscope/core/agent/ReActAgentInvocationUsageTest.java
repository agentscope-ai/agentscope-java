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
package io.agentscope.core.agent;

import static io.agentscope.core.message.MessageMetadataKeys.INVOCATION_USAGE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.TestConstants;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Tests for {@link MessageMetadataKeys#INVOCATION_USAGE}: the total usage of all model calls made
 * by one invocation, kept separate from the per-call {@link MessageMetadataKeys#CHAT_USAGE}.
 */
class ReActAgentInvocationUsageTest {

    private static final Duration TIMEOUT =
            Duration.ofMillis(TestConstants.DEFAULT_TEST_TIMEOUT_MS);

    static class WeatherTools {
        @Tool(description = "Get current weather")
        public String getWeather(@ToolParam(name = "city", description = "city") String city) {
            return "Sunny, 25°C";
        }
    }

    static class WeatherInfo {
        public String city;
        public String temperature;
    }

    /** Replays one scripted response per model call, in order. */
    static class ScriptedModel implements Model {

        private final Deque<ChatResponse> responses;

        ScriptedModel(ChatResponse... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public Flux<ChatResponse> stream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            ChatResponse next = responses.poll();
            if (next == null) {
                return Flux.error(new AssertionError("Unexpected extra model call"));
            }
            return Flux.just(next);
        }

        @Override
        public String getModelName() {
            return "scripted-mock";
        }
    }

    private static ChatResponse toolCall(String toolName, Map<String, Object> input, ChatUsage u) {
        return ChatResponse.builder()
                .id("tool_" + toolName)
                .content(
                        List.of(
                                ToolUseBlock.builder()
                                        .id("call_" + toolName)
                                        .name(toolName)
                                        .input(input)
                                        .content(JsonUtils.getJsonCodec().toJson(input))
                                        .build()))
                .usage(u)
                .build();
    }

    private static ChatResponse text(String text, ChatUsage usage) {
        return ChatResponse.builder()
                .id("text_" + text)
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(usage)
                .build();
    }

    private static ChatUsage usage(int in, int out) {
        return ChatUsage.builder().inputTokens(in).outputTokens(out).time(0.1).build();
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static ReActAgent agentWithTools(Model model) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new WeatherTools());
        return ReActAgent.builder()
                .name("usage-agent")
                .sysPrompt("test")
                .model(model)
                .toolkit(toolkit)
                .build();
    }

    @Test
    @DisplayName("Invocation usage sums every model call; CHAT_USAGE stays per-call")
    void invocationUsageSumsAllModelCallsOfOneCall() {
        ScriptedModel model =
                new ScriptedModel(
                        toolCall("getWeather", Map.of("city", "Hangzhou"), usage(10, 2)),
                        text("It is sunny.", usage(15, 3)));
        ReActAgent agent = agentWithTools(model);

        Msg reply = agent.call(userMsg("Weather?")).block(TIMEOUT);

        assertNotNull(reply);
        ChatUsage perCall = reply.getChatUsage();
        assertNotNull(perCall);
        assertEquals(15, perCall.getInputTokens(), "CHAT_USAGE must keep last-call semantics");
        assertEquals(3, perCall.getOutputTokens());

        ChatUsage total = reply.getInvocationUsage();
        assertNotNull(total, "invocation usage should be attached to the reply");
        assertEquals(25, total.getInputTokens());
        assertEquals(5, total.getOutputTokens());
        assertEquals(30, total.getTotalTokens());
    }

    @Test
    @DisplayName("The reply stays the stored final message; only it carries the total")
    void replyIsTheStoredFinalMessageAndOnlyItCarriesTheTotal() {
        ScriptedModel model =
                new ScriptedModel(
                        toolCall("getWeather", Map.of("city", "Hangzhou"), usage(10, 2)),
                        text("It is sunny.", usage(15, 3)));
        ReActAgent agent = agentWithTools(model);

        Msg reply = agent.call(userMsg("Weather?")).block(TIMEOUT);

        List<Msg> context = agent.getAgentState().getContext();
        assertSame(context.get(context.size() - 1), reply, "reply must be the stored instance");
        assertEquals(
                1,
                context.stream().filter(m -> m.getMetadata().containsKey(INVOCATION_USAGE)).count(),
                "one total per invocation");
        int perCallInputSum =
                context.stream()
                        .map(Msg::getChatUsage)
                        .filter(Objects::nonNull)
                        .mapToInt(ChatUsage::getInputTokens)
                        .sum();
        assertEquals(25, perCallInputSum, "summing CHAT_USAGE over the context is unaffected");
    }

    @Test
    @DisplayName("Each invocation starts from zero")
    void invocationUsageDoesNotCarryOverBetweenCalls() {
        ScriptedModel model =
                new ScriptedModel(
                        toolCall("getWeather", Map.of("city", "Hangzhou"), usage(10, 2)),
                        text("It is sunny.", usage(15, 3)),
                        text("You're welcome.", usage(40, 4)));
        ReActAgent agent = agentWithTools(model);

        agent.call(userMsg("Weather?")).block(TIMEOUT);
        Msg second = agent.call(userMsg("Thanks")).block(TIMEOUT);

        assertNotNull(second);
        ChatUsage total = second.getInvocationUsage();
        assertNotNull(total);
        assertEquals(40, total.getInputTokens());
        assertEquals(4, total.getOutputTokens());
    }

    @Test
    @DisplayName("No usage reported by the provider -> no invocation usage (not zero)")
    void invocationUsageAbsentWhenProviderReportsNone() {
        ScriptedModel model = new ScriptedModel(text("Hi.", null));
        ReActAgent agent =
                ReActAgent.builder().name("usage-agent").sysPrompt("test").model(model).build();

        Msg reply = agent.call(userMsg("Hello")).block(TIMEOUT);

        assertNotNull(reply);
        assertNull(reply.getInvocationUsage());
        assertFalse(reply.getMetadata().containsKey(MessageMetadataKeys.INVOCATION_USAGE));
    }

    @Test
    @DisplayName("streamEvents: AgentResultEvent carries the invocation usage")
    void streamEventsResultCarriesInvocationUsage() {
        ScriptedModel model =
                new ScriptedModel(
                        toolCall("getWeather", Map.of("city", "Hangzhou"), usage(10, 2)),
                        text("It is sunny.", usage(15, 3)));
        ReActAgent agent = agentWithTools(model);

        Msg result =
                agent.streamEvents(List.of(userMsg("Weather?")))
                        .filter(AgentResultEvent.class::isInstance)
                        .cast(AgentResultEvent.class)
                        .map(AgentResultEvent::getResult)
                        .blockLast(TIMEOUT);

        assertNotNull(result);
        ChatUsage total = result.getInvocationUsage();
        assertNotNull(total);
        assertEquals(25, total.getInputTokens());
        assertEquals(5, total.getOutputTokens());
    }

    @Test
    @DisplayName("Forwarded subagent ModelCallEndEvents (with a source) are not counted")
    void forwardedSubagentUsageIsNotCounted() {
        MiddlewareBase forwardsChildUsage =
                new MiddlewareBase() {
                    @Override
                    public Flux<AgentEvent> onReasoning(
                            Agent agent,
                            RuntimeContext ctx,
                            ReasoningInput input,
                            Function<ReasoningInput, Flux<AgentEvent>> next) {
                        return next.apply(input)
                                .concatWith(
                                        Flux.just(
                                                new ModelCallEndEvent("child", usage(100, 100))
                                                        .withSource("main/child")));
                    }
                };
        ScriptedModel model = new ScriptedModel(text("Hi.", usage(15, 3)));
        ReActAgent agent =
                ReActAgent.builder()
                        .name("usage-agent")
                        .sysPrompt("test")
                        .model(model)
                        .middleware(forwardsChildUsage)
                        .build();

        Msg reply = agent.call(userMsg("Hello")).block(TIMEOUT);

        assertNotNull(reply);
        ChatUsage total = reply.getInvocationUsage();
        assertNotNull(total);
        assertEquals(15, total.getInputTokens());
        assertEquals(3, total.getOutputTokens());
    }

    @Test
    @DisplayName(
            "Structured output (tool fallback path) counts the tool step and generate_response")
    void structuredOutputFallbackCountsEveryModelCall() {
        ScriptedModel model =
                new ScriptedModel(
                        toolCall("getWeather", Map.of("city", "Shanghai"), usage(10, 2)),
                        toolCall(
                                "generate_response",
                                Map.of(
                                        "response",
                                        Map.of("city", "Shanghai", "temperature", "32°C")),
                                usage(20, 6)));
        ReActAgent agent = agentWithTools(model);

        Msg reply = agent.call(userMsg("Weather?"), WeatherInfo.class).block(TIMEOUT);

        assertNotNull(reply);
        assertEquals("Shanghai", reply.getStructuredData(WeatherInfo.class).city);
        ChatUsage total = reply.getInvocationUsage();
        assertNotNull(total);
        assertEquals(30, total.getInputTokens());
        assertEquals(8, total.getOutputTokens());
    }

    @Test
    @DisplayName("Structured output (native path) carries the invocation usage")
    void structuredOutputNativeCarriesInvocationUsage() {
        ScriptedModel model =
                new ScriptedModel(
                        text("{\"city\":\"Shanghai\",\"temperature\":\"32°C\"}", usage(12, 4))) {
                    @Override
                    public boolean supportsNativeStructuredOutput() {
                        return true;
                    }
                };
        ReActAgent agent =
                ReActAgent.builder().name("usage-agent").sysPrompt("test").model(model).build();

        Msg reply = agent.call(userMsg("Weather?"), WeatherInfo.class).block(TIMEOUT);

        assertNotNull(reply);
        assertEquals("Shanghai", reply.getStructuredData(WeatherInfo.class).city);
        ChatUsage total = reply.getInvocationUsage();
        assertNotNull(total);
        assertEquals(12, total.getInputTokens());
        assertEquals(4, total.getOutputTokens());
    }
}
