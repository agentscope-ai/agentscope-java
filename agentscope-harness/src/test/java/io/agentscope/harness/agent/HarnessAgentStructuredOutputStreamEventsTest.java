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
package io.agentscope.harness.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.util.JsonUtils;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

/**
 * Verifies that the {@link HarnessAgent#streamEvents(List, Class, RuntimeContext)} and
 * {@link HarnessAgent#streamEvents(List, JsonNode, RuntimeContext)} overloads delegate structured
 * output to the wrapped {@code ReActAgent} (mirroring the non-streaming {@code call(...)} family),
 * with the same sandbox-lifecycle semantics as the non-structured {@code streamEvents} overload.
 */
class HarnessAgentStructuredOutputStreamEventsTest {

    @TempDir Path workspace;

    static class WeatherResponse {
        public String location;
        public String temperature;
        public String condition;
    }

    private static Msg userMsg() {
        return Msg.builder().role(MsgRole.USER).textContent("What's the weather?").build();
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
     * Model driving the fallback (synthetic {@code generate_response} tool) path: first call emits
     * a {@code generate_response} tool use, second call emits a terminating text response.
     */
    private static Model fallbackModel() {
        Map<String, Object> toolInput =
                Map.of(
                        "response",
                        Map.of(
                                "location", "San Francisco",
                                "temperature", "72°F",
                                "condition", "Sunny"));
        ToolUseBlock tc =
                ToolUseBlock.builder()
                        .id("tc-1")
                        .name("generate_response")
                        .input(toolInput)
                        .content(JsonUtils.getJsonCodec().toJson(toolInput))
                        .build();

        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        when(model.stream(anyList(), any(), any()))
                .thenReturn(
                        Flux.just(new ChatResponse("c1", List.of(tc), null, Map.of(), "tool_use")))
                .thenReturn(
                        Flux.just(
                                new ChatResponse(
                                        "c2",
                                        List.of(TextBlock.builder().text("Done").build()),
                                        null,
                                        Map.of(),
                                        "stop")));
        return model;
    }

    private static AgentResultEvent lastResult(List<AgentEvent> events) {
        return events.stream()
                .filter(AgentResultEvent.class::isInstance)
                .map(AgentResultEvent.class::cast)
                .reduce((first, second) -> second)
                .orElseThrow(() -> new AssertionError("No AgentResultEvent emitted"));
    }

    @Test
    void classOverload_delegatesStructuredOutput() {
        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("weather-agent")
                        .model(fallbackModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build()) {

            List<AgentEvent> events =
                    agent.streamEvents(
                                    List.of(userMsg()),
                                    WeatherResponse.class,
                                    RuntimeContext.empty())
                            .collectList()
                            .block(Duration.ofSeconds(10));

            assertNotNull(events);
            Msg result = lastResult(events).getResult();
            assertTrue(result.hasStructuredData(), "final result should carry structured data");
            WeatherResponse weather = result.getStructuredData(WeatherResponse.class);
            assertEquals("San Francisco", weather.location);
            assertEquals("72°F", weather.temperature);
            assertEquals("Sunny", weather.condition);
        }
    }

    @Test
    void jsonNodeOverload_delegatesStructuredOutput() {
        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("weather-agent")
                        .model(fallbackModel())
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build()) {

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
    }
}
