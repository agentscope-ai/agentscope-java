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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.test.MockModel;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ToolCallDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.core.util.JsonUtils;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies that the fine-grained {@code streamEvents(...)} API supports structured output —
 * mirroring the non-streaming {@code call(msgs, schema/class, ctx)} semantics — across both the
 * native {@code response_format} path and the synthetic {@code generate_response} fallback path.
 */
class ReActAgentStreamEventsStructuredOutputTest {

    private static final String NATIVE_JSON =
            "{\"location\":\"San Francisco\",\"temperature\":\"72°F\",\"condition\":\"Sunny\"}";

    private static final Map<String, Object> FALLBACK_TOOL_INPUT =
            Map.of(
                    "response",
                    Map.of(
                            "location", "San Francisco",
                            "temperature", "72°F",
                            "condition", "Sunny"));

    static class WeatherResponse {
        public String location;
        public String temperature;
        public String condition;
    }

    private static Msg userMsg() {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text("What's the weather in San Francisco?").build())
                .build();
    }

    private static JsonNode weatherSchema() {
        String json =
                """
                {
                  "type": "object",
                  "properties": {
                    "location": {"type": "string"},
                    "temperature": {"type": "string"},
                    "condition": {"type": "string"}
                  },
                  "required": ["location", "temperature", "condition"],
                  "additionalProperties": false
                }
                """;
        return JsonUtils.getJsonCodec().fromJson(json, JsonNode.class);
    }

    /**
     * A model that drives the fallback (synthetic {@code generate_response} tool) path: first call
     * emits a {@code generate_response} tool use, second call emits a terminating text response.
     */
    private static MockModel fallbackModel() {
        return new MockModel(
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
                                                                .input(FALLBACK_TOOL_INPUT)
                                                                .content(
                                                                        JsonUtils.getJsonCodec()
                                                                                .toJson(
                                                                                        FALLBACK_TOOL_INPUT))
                                                                .build()))
                                        .usage(new ChatUsage(10, 20, 0.5))
                                        .build());
                    }
                    return List.of(
                            ChatResponse.builder()
                                    .id("msg_2")
                                    .content(List.of(TextBlock.builder().text("Done").build()))
                                    .usage(new ChatUsage(5, 10, 0.1))
                                    .build());
                });
    }

    /**
     * A model that advertises native structured output support and returns the structured JSON as
     * plain text content.
     */
    private static MockModel nativeModel() {
        return new MockModel(
                msgs ->
                        List.of(
                                ChatResponse.builder()
                                        .id("msg_native")
                                        .content(
                                                List.of(
                                                        TextBlock.builder()
                                                                .text(NATIVE_JSON)
                                                                .build()))
                                        .usage(new ChatUsage(10, 20, 0.5))
                                        .build())) {
            @Override
            public boolean supportsNativeStructuredOutput() {
                return true;
            }
        };
    }

    private static ReActAgent agent(MockModel model) {
        return ReActAgent.builder()
                .name("weather-agent")
                .sysPrompt("You are a weather assistant")
                .model(model)
                .toolkit(new Toolkit())
                .build();
    }

    private static AgentResultEvent lastResult(List<AgentEvent> events) {
        return events.stream()
                .filter(AgentResultEvent.class::isInstance)
                .map(AgentResultEvent.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("No AgentResultEvent emitted"));
    }

    private static void assertWeather(Msg result) {
        assertTrue(result.hasStructuredData(), "final result should carry structured data");
        WeatherResponse weather = result.getStructuredData(WeatherResponse.class);
        assertNotNull(weather);
        assertEquals("San Francisco", weather.location);
        assertEquals("72°F", weather.temperature);
        assertEquals("Sunny", weather.condition);
    }

    @Test
    void classOverloadFallbackPathEmitsToolEventsAndStructuredResult() {
        ReActAgent agent = agent(fallbackModel());

        List<AgentEvent> events =
                agent.streamEvents(
                                List.of(userMsg()), WeatherResponse.class, RuntimeContext.empty())
                        .collectList()
                        .block(Duration.ofSeconds(10));

        assertNotNull(events);
        assertTrue(
                events.stream()
                        .anyMatch(
                                e ->
                                        e instanceof ToolCallStartEvent start
                                                && "generate_response"
                                                        .equals(start.getToolCallName())),
                "fallback path should emit ToolCallStartEvent for generate_response");
        assertTrue(
                events.stream()
                        .anyMatch(
                                e ->
                                        e instanceof ToolCallDeltaEvent delta
                                                && "generate_response"
                                                        .equals(delta.getToolCallName())),
                "fallback path should emit ToolCallDeltaEvent for generate_response");

        Msg result = lastResult(events).getResult();
        assertWeather(result);
        assertNotNull(result.getChatUsage(), "final result should expose aggregated token usage");
    }

    @Test
    void jsonNodeOverloadFallbackPathEmitsStructuredResult() {
        ReActAgent agent = agent(fallbackModel());

        List<AgentEvent> events =
                agent.streamEvents(List.of(userMsg()), weatherSchema(), RuntimeContext.empty())
                        .collectList()
                        .block(Duration.ofSeconds(10));

        assertNotNull(events);
        Msg result = lastResult(events).getResult();
        assertTrue(result.hasStructuredData(), "final result should carry structured data");
        Map<String, Object> data = result.getStructuredData(false);
        assertEquals("San Francisco", data.get("location"));
        assertEquals("72°F", data.get("temperature"));
        assertEquals("Sunny", data.get("condition"));
    }

    @Test
    void classOverloadNativePathEmitsTextDeltaAndStructuredResult() {
        ReActAgent agent = agent(nativeModel());

        List<AgentEvent> events =
                agent.streamEvents(
                                List.of(userMsg()), WeatherResponse.class, RuntimeContext.empty())
                        .collectList()
                        .block(Duration.ofSeconds(10));

        assertNotNull(events);
        boolean sawJsonDelta =
                events.stream()
                        .filter(TextBlockDeltaEvent.class::isInstance)
                        .map(TextBlockDeltaEvent.class::cast)
                        .anyMatch(delta -> NATIVE_JSON.equals(delta.getDelta()));
        assertTrue(
                sawJsonDelta,
                "native path should emit TextBlockDeltaEvent carrying the structured JSON text");

        Msg result = lastResult(events).getResult();
        assertWeather(result);
    }

    @Test
    void streamEventsStructuredResultMatchesCallResult() {
        ReActAgent streamingAgent = agent(fallbackModel());
        ReActAgent callAgent = agent(fallbackModel());

        Msg streamed =
                lastResult(
                                streamingAgent
                                        .streamEvents(
                                                List.of(userMsg()),
                                                WeatherResponse.class,
                                                RuntimeContext.empty())
                                        .collectList()
                                        .block(Duration.ofSeconds(10)))
                        .getResult();

        Msg called =
                callAgent
                        .call(List.of(userMsg()), WeatherResponse.class, RuntimeContext.empty())
                        .block(Duration.ofSeconds(10));

        assertNotNull(streamed);
        assertNotNull(called);
        assertWeather(streamed);
        assertWeather(called);
        assertEquals(
                streamed.getStructuredData(false),
                called.getStructuredData(false),
                "streamEvents(...) should yield the same structured result as call(...)");
    }
}
