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
package io.agentscope.core.responses.streaming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.MessageMetadataKeys;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.responses.model.ResponsesRequest;
import io.agentscope.core.responses.model.ResponsesStreamEvent;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ResponsesStreamingAdapterTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ResponsesStreamingAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ResponsesStreamingAdapter();
    }

    @Test
    void shouldConvertIncrementalTextEventsToResponsesStreamEvents() {
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(
                        Flux.just(
                                new Event(EventType.REASONING, assistantText("Hel"), false),
                                new Event(EventType.REASONING, assistantText("lo"), false),
                                new Event(EventType.REASONING, assistantText("Hello"), true)));

        List<ResponsesStreamEvent> events =
                adapter.stream(agent, List.of(userText("Hello")), request(), "resp_123")
                        .collectList()
                        .block();

        assertNotNull(events);
        assertEquals("response.created", events.get(0).getType());
        assertEquals("response.in_progress", events.get(1).getType());
        assertEquals("response.output_item.added", events.get(2).getType());
        assertEquals("response.content_part.added", events.get(3).getType());
        assertEquals("response.output_text.delta", events.get(4).getType());
        assertEquals("Hel", events.get(4).getDelta());
        assertEquals(5, events.get(4).getSequenceNumber());
        assertEquals("resp_123", events.get(4).getResponseId());
        assertEquals("response.output_text.delta", events.get(5).getType());
        assertEquals("lo", events.get(5).getDelta());
        assertTrue(
                events.stream()
                        .anyMatch(event -> "response.output_text.done".equals(event.getType())));
        assertEquals("response.completed", events.get(events.size() - 1).getType());
        assertEquals("Hello", events.get(events.size() - 1).getResponse().getOutputText());
        assertEquals(1, events.get(events.size() - 1).getResponse().getOutput().size());
        assertEquals(
                "Hello",
                events.get(events.size() - 1)
                        .getResponse()
                        .getOutput()
                        .get(0)
                        .getContent()
                        .get(0)
                        .getText());
    }

    @Test
    void shouldPassRuntimeContextToAgentStream() {
        ReActAgent agent = mock(ReActAgent.class);
        RuntimeContext context = RuntimeContext.builder().sessionId("resp_context").build();
        when(agent.stream(anyList(), any(StreamOptions.class), same(context)))
                .thenReturn(
                        Flux.just(
                                new Event(
                                        EventType.REASONING,
                                        assistantText("context response"),
                                        true)));

        List<ResponsesStreamEvent> events =
                adapter.stream(
                                agent,
                                List.of(userText("Hello")),
                                request(),
                                "resp_context",
                                context)
                        .collectList()
                        .block();

        assertNotNull(events);
        assertEquals("response.completed", events.get(events.size() - 1).getType());
        verify(agent).stream(anyList(), any(StreamOptions.class), same(context));
    }

    @Test
    void shouldKeepStreamingStateIndependentForEachSubscription() {
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(
                        Flux.just(
                                new Event(EventType.REASONING, assistantText("Hel"), false),
                                new Event(EventType.REASONING, assistantText("lo"), false),
                                new Event(EventType.REASONING, assistantText("Hello"), true)));

        Flux<ResponsesStreamEvent> stream =
                adapter.stream(agent, List.of(userText("Hello")), request(), "resp_repeat");

        List<ResponsesStreamEvent> first = stream.collectList().block();
        List<ResponsesStreamEvent> second = stream.collectList().block();

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(
                first.stream().map(ResponsesStreamEvent::getType).toList(),
                second.stream().map(ResponsesStreamEvent::getType).toList());
        assertEquals("Hello", first.get(first.size() - 1).getResponse().getOutputText());
        assertEquals("Hello", second.get(second.size() - 1).getResponse().getOutputText());
    }

    @Test
    void shouldConvertToolUseEventsToFunctionCallOutputItems() {
        ReActAgent agent = mock(ReActAgent.class);
        Msg toolCall =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("call_123")
                                        .name("get_weather")
                                        .input(Map.of("city", "Hangzhou"))
                                        .build())
                        .build();
        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(new Event(EventType.REASONING, toolCall, true)));

        List<ResponsesStreamEvent> events =
                adapter.stream(agent, List.of(userText("Weather?")), request(), "resp_456")
                        .collectList()
                        .block();

        assertNotNull(events);
        ResponsesStreamEvent added =
                events.stream()
                        .filter(event -> "response.output_item.added".equals(event.getType()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("function_call", added.getItem().getType());
        assertEquals("call_123", added.getItem().getCallId());
        assertEquals("get_weather", added.getItem().getName());

        ResponsesStreamEvent argumentsDone =
                events.stream()
                        .filter(
                                event ->
                                        "response.function_call_arguments.done"
                                                .equals(event.getType()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("{\"city\":\"Hangzhou\"}", argumentsDone.getArguments());
        assertEquals("call_123", argumentsDone.getCallId());
        assertEquals("get_weather", argumentsDone.getName());

        ResponsesStreamEvent argumentsDelta =
                events.stream()
                        .filter(
                                event ->
                                        "response.function_call_arguments.delta"
                                                .equals(event.getType()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("{\"city\":\"Hangzhou\"}", argumentsDelta.getDelta());
        assertEquals("resp_456", argumentsDelta.getResponseId());
    }

    @Test
    void shouldEmitTextBeforeToolUseFromTheSameEvent() {
        ReActAgent agent = mock(ReActAgent.class);
        Msg reply =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                TextBlock.builder().text("Checking").build(),
                                ToolUseBlock.builder()
                                        .id("call_mixed")
                                        .name("lookup")
                                        .input(Map.of("query", "AgentScope"))
                                        .build())
                        .build();
        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(new Event(EventType.REASONING, reply, true)));

        List<ResponsesStreamEvent> events =
                adapter.stream(agent, List.of(userText("Lookup")), request(), "resp_mixed")
                        .collectList()
                        .block();

        assertNotNull(events);
        List<String> eventTypes = events.stream().map(ResponsesStreamEvent::getType).toList();
        assertTrue(
                eventTypes.indexOf("response.output_text.delta")
                        < eventTypes.indexOf("response.function_call_arguments.done"));
        assertEquals(2, events.get(events.size() - 1).getResponse().getOutput().size());
    }

    @Test
    void shouldKeepDistinctOutputIndexesWhenFunctionCallPrecedesText() {
        ReActAgent agent = mock(ReActAgent.class);
        Msg toolCall =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("call_first")
                                        .name("lookup")
                                        .input(Map.of("query", "AgentScope"))
                                        .build())
                        .build();
        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(
                        Flux.just(
                                new Event(EventType.REASONING, toolCall, false),
                                new Event(EventType.REASONING, assistantText("Done"), true)));

        List<ResponsesStreamEvent> events =
                adapter.stream(agent, List.of(userText("Lookup")), request(), "resp_order")
                        .collectList()
                        .block();

        assertNotNull(events);
        ResponsesStreamEvent toolAdded =
                events.stream()
                        .filter(
                                event ->
                                        "response.output_item.added".equals(event.getType())
                                                && "function_call"
                                                        .equals(event.getItem().getType()))
                        .findFirst()
                        .orElseThrow();
        ResponsesStreamEvent textAdded =
                events.stream()
                        .filter(
                                event ->
                                        "response.output_item.added".equals(event.getType())
                                                && "message".equals(event.getItem().getType()))
                        .findFirst()
                        .orElseThrow();

        assertEquals(0, toolAdded.getOutputIndex());
        assertEquals(1, textAdded.getOutputIndex());
        assertEquals(2, events.get(events.size() - 1).getResponse().getOutput().size());
    }

    @Test
    void shouldSerializeOutputItemWithOfficialResponsesFieldName() throws Exception {
        ResponsesStreamEvent event =
                ResponsesStreamEvent.outputItemEvent(
                        "response.output_item.added",
                        0,
                        io.agentscope.core.responses.model.ResponsesOutputItem.message(
                                "msg_1", ""));

        String json = OBJECT_MAPPER.writeValueAsString(event);

        assertTrue(json.contains("\"item\""));
        assertFalse(json.contains("\"output_item\""));
        assertFalse(json.contains("\"outputItem\""));
    }

    @Test
    void shouldStreamStructuredOutputAsResponsesTextEvents() throws Exception {
        ReActAgent agent = mock(ReActAgent.class);
        Map<String, Object> structuredOutput = new LinkedHashMap<>();
        structuredOutput.put("answer", "42");
        structuredOutput.put("ok", true);
        Msg reply = structuredAssistant(structuredOutput);
        JsonNode schema =
                OBJECT_MAPPER.readTree(
                        """
                        {
                          "type": "object",
                          "properties": {
                            "answer": { "type": "string" },
                            "ok": { "type": "boolean" }
                          },
                          "required": ["answer", "ok"]
                        }
                        """);
        when(agent.stream(anyList(), any(StreamOptions.class), any(JsonNode.class)))
                .thenReturn(Flux.just(new Event(EventType.AGENT_RESULT, reply, true)));

        List<ResponsesStreamEvent> events =
                adapter.stream(
                                agent,
                                List.of(userText("Return JSON")),
                                schema,
                                request(),
                                "resp_json")
                        .collectList()
                        .block();

        assertNotNull(events);
        assertEquals("response.created", events.get(0).getType());
        assertEquals("response.in_progress", events.get(1).getType());
        ResponsesStreamEvent delta =
                events.stream()
                        .filter(event -> "response.output_text.delta".equals(event.getType()))
                        .findFirst()
                        .orElseThrow();
        assertEquals("{\"answer\":\"42\",\"ok\":true}", delta.getDelta());
        assertEquals("resp_json", delta.getResponseId());
        assertTrue(
                events.stream()
                        .anyMatch(event -> "response.output_text.done".equals(event.getType())));
        ResponsesStreamEvent completed = events.get(events.size() - 1);
        assertEquals("response.completed", completed.getType());
        assertEquals("{\"answer\":\"42\",\"ok\":true}", completed.getResponse().getOutputText());
        assertEquals(1, completed.getResponse().getOutput().size());
        verify(agent).stream(anyList(), any(StreamOptions.class), any(JsonNode.class));
    }

    @Test
    void shouldFailStructuredStreamWhenAgentReturnsNoStructuredData() throws Exception {
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.stream(anyList(), any(StreamOptions.class), any(JsonNode.class)))
                .thenReturn(
                        Flux.just(
                                new Event(
                                        EventType.AGENT_RESULT,
                                        assistantText("plain text"),
                                        true)));

        List<ResponsesStreamEvent> events =
                adapter.stream(
                                agent,
                                List.of(userText("Return JSON")),
                                OBJECT_MAPPER.readTree("{\"type\":\"object\"}"),
                                request(),
                                "resp_bad_json")
                        .collectList()
                        .block();

        assertNotNull(events);
        ResponsesStreamEvent failed = events.get(events.size() - 1);
        assertEquals("response.failed", failed.getType());
        assertEquals("failed", failed.getResponse().getStatus());
        assertEquals("runtime_error", failed.getResponse().getError().getCode());
    }

    @Test
    void shouldCreateFailedEvent() {
        ResponsesStreamEvent event =
                adapter.createFailedEvent(
                        new IllegalStateException("boom"), request(), "resp_fail");

        assertEquals("response.failed", event.getType());
        assertEquals("failed", event.getResponse().getStatus());
        assertEquals("runtime_error", event.getResponse().getError().getCode());
    }

    private ResponsesRequest request() {
        ResponsesRequest request = new ResponsesRequest();
        request.setModel("gpt-test");
        return request;
    }

    private Msg userText(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private Msg assistantText(String text) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private Msg structuredAssistant(Map<String, Object> data) {
        Map<String, Object> structured = new LinkedHashMap<>(data);
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .metadata(Map.of(MessageMetadataKeys.STRUCTURED_OUTPUT, structured))
                .build();
    }
}
