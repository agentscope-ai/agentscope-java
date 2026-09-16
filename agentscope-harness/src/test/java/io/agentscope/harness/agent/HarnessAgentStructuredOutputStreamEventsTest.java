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
import io.agentscope.harness.agent.example.support.InMemorySandboxClient;
import io.agentscope.harness.agent.example.support.InMemorySandboxFilesystemSpec;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.stubbing.OngoingStubbing;
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

    /**
     * Model driving the fallback path for {@code cycles} consecutive streamEvents invocations:
     * each invocation needs exactly two model calls (tool use, then terminating text).
     */
    private static Model repeatedFallbackModel(int cycles) {
        Map<String, Object> toolInput =
                Map.of(
                        "response",
                        Map.of(
                                "location", "San Francisco",
                                "temperature", "72°F",
                                "condition", "Sunny"));
        ChatResponse toolUse =
                new ChatResponse(
                        "c1",
                        List.of(
                                ToolUseBlock.builder()
                                        .id("tc-r")
                                        .name("generate_response")
                                        .input(toolInput)
                                        .content(JsonUtils.getJsonCodec().toJson(toolInput))
                                        .build()),
                        null,
                        Map.of(),
                        "tool_use");
        ChatResponse done =
                new ChatResponse(
                        "c2",
                        List.of(TextBlock.builder().text("Done").build()),
                        null,
                        Map.of(),
                        "stop");

        Model model = mock(Model.class);
        when(model.getModelName()).thenReturn("stub");
        OngoingStubbing<Flux<ChatResponse>> stubbing = when(model.stream(anyList(), any(), any()));
        for (int i = 0; i < cycles; i++) {
            stubbing = stubbing.thenReturn(Flux.just(toolUse)).thenReturn(Flux.just(done));
        }
        return model;
    }

    @Test
    void msgAndStringConvenienceOverloads_delegateStructuredOutput() {
        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("weather-agent")
                        .model(repeatedFallbackModel(4))
                        .workspace(workspace)
                        .abstractFilesystem(new LocalFilesystem(workspace))
                        .build()) {

            List<AgentEvent> msgClassEvents =
                    agent.streamEvents(userMsg(), WeatherResponse.class, RuntimeContext.empty())
                            .collectList()
                            .block(Duration.ofSeconds(10));
            assertNotNull(msgClassEvents);
            assertTrue(
                    lastResult(msgClassEvents).getResult().hasStructuredData(),
                    "Msg + Class overload should yield a structured result");

            List<AgentEvent> msgSchemaEvents =
                    agent.streamEvents(userMsg(), weatherSchema(), RuntimeContext.empty())
                            .collectList()
                            .block(Duration.ofSeconds(10));
            assertNotNull(msgSchemaEvents);
            assertTrue(
                    lastResult(msgSchemaEvents).getResult().hasStructuredData(),
                    "Msg + JsonNode overload should yield a structured result");

            List<AgentEvent> textClassEvents =
                    agent.streamEvents(
                                    "What's the weather?",
                                    WeatherResponse.class,
                                    RuntimeContext.empty())
                            .collectList()
                            .block(Duration.ofSeconds(10));
            assertNotNull(textClassEvents);
            assertTrue(
                    lastResult(textClassEvents).getResult().hasStructuredData(),
                    "String + Class overload should yield a structured result");

            List<AgentEvent> textSchemaEvents =
                    agent.streamEvents(
                                    "What's the weather?", weatherSchema(), RuntimeContext.empty())
                            .collectList()
                            .block(Duration.ofSeconds(10));
            assertNotNull(textSchemaEvents);
            assertTrue(
                    lastResult(textSchemaEvents).getResult().hasStructuredData(),
                    "String + JsonNode overload should yield a structured result");
        }
    }

    /**
     * Pins the sandbox-lifecycle promise on the structured overloads: each stream subscription must
     * go through {@code wrappedStreamEvents}' acquire/release exactly once — observable here as
     * exactly one sandbox create on the first call and exactly one resume (state saved by release)
     * on the second, against a real sandbox-backed HarnessAgent.
     */
    @Test
    void sandboxBackedStreamEvents_acquiresAndReleasesExactlyOncePerSubscription()
            throws Exception {
        InMemorySandboxClient client = new InMemorySandboxClient();
        InMemorySandboxFilesystemSpec spec = new InMemorySandboxFilesystemSpec(client);
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("AGENTS.md"), "# Test\n");

        String previousStateHome = System.getProperty("agentscope.state.home");
        System.setProperty("agentscope.state.home", workspace.resolve("state-home").toString());
        try (HarnessAgent agent =
                HarnessAgent.builder()
                        .name("weather-agent")
                        .model(repeatedFallbackModel(2))
                        .workspace(workspace)
                        .filesystem(spec)
                        .build()) {

            RuntimeContext ctx = RuntimeContext.builder().sessionId("sandbox-s1").build();

            List<AgentEvent> first =
                    agent.streamEvents(List.of(userMsg()), WeatherResponse.class, ctx)
                            .collectList()
                            .block(Duration.ofSeconds(10));
            assertNotNull(first);
            assertTrue(
                    lastResult(first).getResult().hasStructuredData(),
                    "first structured stream should complete under the sandbox");
            assertEquals(
                    1,
                    client.getCreateCount(),
                    "first subscription must acquire the sandbox exactly once (create)");

            List<AgentEvent> second =
                    agent.streamEvents(List.of(userMsg()), weatherSchema(), ctx)
                            .collectList()
                            .block(Duration.ofSeconds(10));
            assertNotNull(second);
            assertTrue(
                    lastResult(second).getResult().hasStructuredData(),
                    "second structured stream should complete under the sandbox");
            assertEquals(
                    1,
                    client.getResumeCount(),
                    "second subscription must resume the sandbox saved by the first subscription's"
                            + " release (release persisted state exactly once)");
        } finally {
            if (previousStateHome != null) {
                System.setProperty("agentscope.state.home", previousStateHome);
            } else {
                System.clearProperty("agentscope.state.home");
            }
        }
    }
}
