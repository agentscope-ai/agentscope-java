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
package io.agentscope.core.llm.interfacesweb.anthropic;

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

@DisplayName("AnthropicStreamingAdapter Tests")
class AnthropicStreamingAdapterTest {

    private final AnthropicStreamingAdapter adapter =
            new AnthropicStreamingAdapter(new AnthropicResponseBuilder());

    @Test
    @DisplayName("Should emit Anthropic text stream event order")
    void shouldEmitTextStreamEventOrder() {
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

        AnthropicMessagesRequest request = new AnthropicMessagesRequest();
        request.setModel("claude-test");

        StepVerifier.create(adapter.stream(agent, List.of(delta), request, "msg_1"))
                .expectNextMatches(event -> "message_start".equals(event.getType()))
                .expectNextMatches(
                        event ->
                                "content_block_start".equals(event.getType())
                                        && event.getIndex() == 0)
                .expectNextMatches(
                        event ->
                                "content_block_delta".equals(event.getType())
                                        && "Hel".equals(event.getDelta().get("text")))
                .expectNextMatches(
                        event ->
                                "content_block_stop".equals(event.getType())
                                        && event.getIndex() == 0)
                .expectNextMatches(
                        event ->
                                "message_delta".equals(event.getType())
                                        && "end_turn".equals(event.getDelta().get("stop_reason")))
                .expectNextMatches(event -> "message_stop".equals(event.getType()))
                .verifyComplete();
    }

    @Test
    @DisplayName("Should start first tool-use block at index zero when there is no text")
    void shouldStartToolBlockAtZeroWhenNoText() {
        ReActAgent agent = mock(ReActAgent.class);
        Msg toolUse =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(
                                ToolUseBlock.builder()
                                        .id("toolu_1")
                                        .name("lookup")
                                        .input(Map.of("city", "Paris"))
                                        .build())
                        .build();

        when(agent.stream(anyList(), any(StreamOptions.class)))
                .thenReturn(Flux.just(new Event(EventType.REASONING, toolUse, true)));

        AnthropicMessagesRequest request = new AnthropicMessagesRequest();
        request.setModel("claude-test");

        StepVerifier.create(adapter.stream(agent, List.of(toolUse), request, "msg_1"))
                .expectNextMatches(event -> "message_start".equals(event.getType()))
                .expectNextMatches(
                        event ->
                                "content_block_start".equals(event.getType())
                                        && event.getIndex() == 0
                                        && "tool_use".equals(event.getContentBlock().get("type")))
                .expectNextMatches(
                        event ->
                                "content_block_delta".equals(event.getType())
                                        && event.getIndex() == 0
                                        && "{\"city\":\"Paris\"}"
                                                .equals(event.getDelta().get("partial_json")))
                .expectNextMatches(
                        event ->
                                "content_block_stop".equals(event.getType())
                                        && event.getIndex() == 0)
                .expectNextMatches(event -> "message_delta".equals(event.getType()))
                .expectNextMatches(event -> "message_stop".equals(event.getType()))
                .verifyComplete();
    }
}
