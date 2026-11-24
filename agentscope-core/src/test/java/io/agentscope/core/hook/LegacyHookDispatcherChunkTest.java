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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.accumulator.ReasoningContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.GenerateOptions;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Chunk dispatch must not pay for hooks that are not there.
 *
 * <p>Every streamed chunk used to build a snapshot of the whole answer so far before asking
 * whether anything wanted it. With no hook registered that copy is pure waste, and it is
 * quadratic over a long stream: the streaming path produces one chunk per token.
 */
class LegacyHookDispatcherChunkTest {

    private static Msg textChunk(String text) {
        return Msg.builder()
                .id("chunk-1")
                .name("assistant")
                .role(MsgRole.ASSISTANT)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    private static ReActAgent agentWithHooks(List<Hook> hooks) {
        ReActAgent agent = mock(ReActAgent.class);
        when(agent.getSortedHooks()).thenReturn(hooks);
        return agent;
    }

    @Test
    @DisplayName("no accumulated text is built for a reasoning chunk when no hook is registered")
    void reasoningChunkSkipsAccumulationWithoutHooks() {
        LegacyHookDispatcher dispatcher = new LegacyHookDispatcher(agentWithHooks(List.of()));
        ReasoningContext context = mock(ReasoningContext.class);

        dispatcher.fireReasoningChunk(textChunk("a"), context, "model").block();

        verify(context, never()).getAccumulatedText();
    }

    @Test
    @DisplayName("no accumulated text is built for a summary chunk when no hook is registered")
    void summaryChunkSkipsAccumulationWithoutHooks() {
        LegacyHookDispatcher dispatcher = new LegacyHookDispatcher(agentWithHooks(List.of()));
        ReasoningContext context = mock(ReasoningContext.class);

        dispatcher
                .fireSummaryChunk(
                        textChunk("a"), context, GenerateOptions.builder().build(), "model")
                .block();

        verify(context, never()).getAccumulatedText();
    }

    @Test
    @DisplayName("a registered hook still receives the accumulated chunk")
    void registeredHookReceivesAccumulatedContent() {
        AtomicReference<Msg> accumulated = new AtomicReference<>();
        AtomicReference<Msg> incremental = new AtomicReference<>();
        Hook hook =
                new Hook() {
                    @Override
                    public <T extends HookEvent> Mono<T> onEvent(T event) {
                        if (event instanceof ReasoningChunkEvent chunk) {
                            accumulated.set(chunk.getAccumulated());
                            incremental.set(chunk.getIncrementalChunk());
                        }
                        return Mono.just(event);
                    }
                };

        LegacyHookDispatcher dispatcher = new LegacyHookDispatcher(agentWithHooks(List.of(hook)));
        ReasoningContext context = mock(ReasoningContext.class);
        when(context.getAccumulatedText()).thenReturn("abc");

        dispatcher.fireReasoningChunk(textChunk("c"), context, "model").block();

        assertNotNull(accumulated.get(), "the hook must still see the accumulated message");
        assertEquals(
                "abc", accumulated.get().getTextContent(), "accumulated text must be the snapshot");
        assertEquals("c", incremental.get().getTextContent(), "the incremental chunk is unchanged");
    }
}
