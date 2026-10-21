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
package io.agentscope.core.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings("removal")
class LegacyHookDispatcherTest {
    @Test
    void noHooksSkipsChunkMessagesAndAccumulatedContent() {
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getSortedHooks()).thenReturn(List.of());
        Msg chunk = mock(Msg.class);
        ReasoningContext context = mock(ReasoningContext.class);
        LegacyHookDispatcher dispatcher = new LegacyHookDispatcher(agent);
        StepVerifier.create(dispatcher.fireReasoningChunk(chunk, context, "test")).verifyComplete();
        StepVerifier.create(dispatcher.fireSummaryChunk(chunk, context, null, "test"))
                .verifyComplete();
        verifyNoInteractions(chunk, context);
    }

    @Test
    void hooksStillReceiveIncrementalAndAccumulatedText() {
        ReActAgent agent = mock(ReActAgent.class);
        AtomicReference<ReasoningChunkEvent> received = new AtomicReference<>();
        Hook hook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        received.set((ReasoningChunkEvent) event);
                        return Mono.just(event);
                    }
                };
        when(agent.getSortedHooks()).thenReturn(List.of(hook));
        ReasoningContext context = mock(ReasoningContext.class);
        when(context.getAccumulatedText()).thenReturn("hello world");
        Msg chunk = Msg.builder().content(TextBlock.builder().text(" world").build()).build();
        StepVerifier.create(
                        new LegacyHookDispatcher(agent).fireReasoningChunk(chunk, context, "test"))
                .verifyComplete();
        assertEquals(" world", received.get().getIncrementalChunk().getTextContent());
        assertEquals("hello world", received.get().getAccumulated().getTextContent());
    }
}
