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
package io.agentscope.core.llm.interfacesweb.responses;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

@DisplayName("ResponsesStreamingAdapter Tests")
class ResponsesStreamingAdapterTest {

    private final ResponsesStreamingAdapter adapter =
            new ResponsesStreamingAdapter(new ResponsesResponseBuilder());

    @Test
    @DisplayName("Should emit semantic Responses stream event order")
    void shouldEmitSemanticResponsesEvents() {
        ReActAgent agent = mock(ReActAgent.class);
        Msg delta =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Hel").build())
                        .build();
        Msg finalReply =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Hello").build())
                        .build();

        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(
                        Flux.just(
                                new Event(EventType.REASONING, delta, false),
                                new Event(EventType.REASONING, finalReply, true)));

        ResponsesRequest request = new ResponsesRequest();
        request.setModel("test-model");

        StepVerifier.create(adapter.stream(agent, List.of(delta), request, "resp_1"))
                .expectNextMatches(event -> "response.created".equals(event.getType()))
                .expectNextMatches(
                        event ->
                                "response.output_text.delta".equals(event.getType())
                                        && "Hel".equals(event.getDelta()))
                .expectNextMatches(
                        event ->
                                "response.completed".equals(event.getType())
                                        && "Hello".equals(event.getResponse().getOutputText()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should emit function-call stream events")
    void shouldEmitFunctionCallStreamEvents() {
        ReActAgent agent = mock(ReActAgent.class);
        Msg toolUse =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("call_1")
                                        .name("lookup")
                                        .input(Map.of("city", "Paris"))
                                        .build())
                        .build();

        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(new Event(EventType.REASONING, toolUse, true)));

        ResponsesRequest request = new ResponsesRequest();
        request.setModel("test-model");

        StepVerifier.create(adapter.stream(agent, List.of(toolUse), request, "resp_1"))
                .expectNextMatches(event -> "response.created".equals(event.getType()))
                .expectNextMatches(
                        event ->
                                "response.output_item.added".equals(event.getType())
                                        && "function_call".equals(event.getItem().getType()))
                .expectNextMatches(
                        event ->
                                "response.function_call_arguments.delta".equals(event.getType())
                                        && "{\"city\":\"Paris\"}".equals(event.getDelta()))
                .expectNextMatches(event -> "response.completed".equals(event.getType()))
                .verifyComplete();
    }
}
