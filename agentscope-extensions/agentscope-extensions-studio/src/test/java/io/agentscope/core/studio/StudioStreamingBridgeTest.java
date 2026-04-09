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
package io.agentscope.core.studio;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class StudioStreamingBridgeTest {

    private Msg msg(String text) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name("Agent")
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    @Test
    void testStreamingAndCompletion() {

        StudioClient mockClient = mock(StudioClient.class);
        StudioWebSocketClient mockWs = mock(StudioWebSocketClient.class);

        when(mockClient.pushMessage(any(Msg.class))).thenReturn(Mono.empty());

        StudioStreamingBridge bridge = new StudioStreamingBridge(mockClient, mockWs);

        Event reasoning1 = new Event(EventType.REASONING, msg("thinking 1"), false);
        Event reasoning2 = new Event(EventType.REASONING, msg("thinking 2"), false);

        Event tool1 = new Event(EventType.TOOL_RESULT, msg("tool out 1"), false);

        Event answerChunk1 = new Event(EventType.AGENT_RESULT, msg("hello "), false);
        Event answerChunk2 = new Event(EventType.AGENT_RESULT, msg("world"), false);

        Msg finalMsg = msg("hello world");
        Event answerFinal = new Event(EventType.AGENT_RESULT, finalMsg, true);

        Flux<Event> flux =
                Flux.fromIterable(
                        List.of(
                                reasoning1,
                                reasoning2,
                                tool1,
                                answerChunk1,
                                answerChunk2,
                                answerFinal));

        bridge.forwardToStudio(flux).block();

        verify(mockWs, times(5)).sendStreamEvent(any(Event.class));

        verify(mockWs, atLeastOnce()).sendStreamEvent(argThat(e ->
                !e.isLast()
                        && (e.getType() == EventType.REASONING
                        || e.getType() == EventType.TOOL_RESULT
                        || e.getType() == EventType.AGENT_RESULT)));

        verify(mockWs, times(1)).sendStreamCompleted();

        verify(mockClient, times(1)).pushMessage(eq(finalMsg));
    }

    @Test
    void testFallbackWhenNoTerminalAgentResult() {
        StudioClient mockClient = mock(StudioClient.class);
        StudioWebSocketClient mockWs = mock(StudioWebSocketClient.class);

        when(mockClient.pushMessage(any(Msg.class))).thenReturn(Mono.empty());

        StudioStreamingBridge bridge = new StudioStreamingBridge(mockClient, mockWs);

        Event chunk1 = new Event(EventType.AGENT_RESULT, msg("foo"), false);
        Msg lastMsg = msg("foo bar");
        Event chunk2 = new Event(EventType.AGENT_RESULT, lastMsg, false);

        Flux<Event> flux = Flux.just(chunk1, chunk2);

        bridge.forwardToStudio(flux).block();

        verify(mockWs, times(2)).sendStreamEvent(any(Event.class));
        verify(mockWs, times(1)).sendStreamCompleted();
        verify(mockClient, times(1)).pushMessage(eq(lastMsg));
    }
}

