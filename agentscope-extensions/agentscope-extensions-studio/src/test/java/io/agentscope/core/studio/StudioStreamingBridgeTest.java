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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.EventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
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

    private Msg thinkingMsg(String thinking) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name("Agent")
                .content(ThinkingBlock.builder().thinking(thinking).build())
                .build();
    }

    private Msg toolResultMsg(String text) {
        ToolResultBlock toolResult =
                ToolResultBlock.text(text);
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .name("Agent")
                .content(toolResult)
                .build();
    }

    @Test
    void testStreamingAndCompletion() {

        StudioClient mockClient = Mockito.mock(StudioClient.class);
        StudioWebSocketClient mockWs = Mockito.mock(StudioWebSocketClient.class);

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

        verify(mockWs, Mockito.atLeastOnce())
                .sendStreamEvent(
                        argThat(
                                e ->
                                        !e.isLast()
                                                && (e.getType() == EventType.REASONING
                                                        || e.getType() == EventType.TOOL_RESULT
                                                        || e.getType() == EventType.AGENT_RESULT)));

        verify(mockWs, times(1)).sendStreamCompleted();

        verify(mockClient, times(1)).pushMessage(eq(finalMsg));
    }

    @Test
    void testFallbackWhenNoTerminalAgentResult() {
        StudioClient mockClient = Mockito.mock(StudioClient.class);
        StudioWebSocketClient mockWs = Mockito.mock(StudioWebSocketClient.class);

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

    @Test
    void testReasoningAndToolResultEventsWithThinkingAndToolResultBlocksAreStreamed() {
        StudioClient mockClient = Mockito.mock(StudioClient.class);
        StudioWebSocketClient mockWs = Mockito.mock(StudioWebSocketClient.class);

        when(mockClient.pushMessage(any(Msg.class))).thenReturn(Mono.empty());

        StudioStreamingBridge bridge = new StudioStreamingBridge(mockClient, mockWs);

        Event reasoningFinal =
                new Event(EventType.REASONING, thinkingMsg("chain-of-thought"), true);
        Event toolFinal =
                new Event(EventType.TOOL_RESULT, toolResultMsg("tool-output"), true);

        // Include a terminal AGENT_RESULT so that the stream completes normally
        Msg finalMsg = msg("answer");
        Event answerFinal = new Event(EventType.AGENT_RESULT, finalMsg, true);

        Flux<Event> flux = Flux.just(reasoningFinal, toolFinal, answerFinal);

        bridge.forwardToStudio(flux).block();

        // Both reasoning and tool result events (even with isLast == true) should be streamed
        verify(mockWs, times(1)).sendStreamEvent(reasoningFinal);
        verify(mockWs, times(1)).sendStreamEvent(toolFinal);
        // Terminal AGENT_RESULT should not be streamed as an event, only used for completion
        verify(mockWs, Mockito.never()).sendStreamEvent(answerFinal);

        verify(mockWs, times(1)).sendStreamCompleted();
        verify(mockClient, times(1)).pushMessage(eq(finalMsg));
    }
}
