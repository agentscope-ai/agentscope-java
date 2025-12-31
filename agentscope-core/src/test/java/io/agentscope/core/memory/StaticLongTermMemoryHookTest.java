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
package io.agentscope.core.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.hook.PostCallEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.interruption.InterruptContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Unit tests for {@link StaticLongTermMemoryHook}. */
class StaticLongTermMemoryHookTest {

    private LongTermMemory mockLongTermMemory;
    private Memory mockMemory;
    private StaticLongTermMemoryHook hook;
    private Agent mockAgent;

    @BeforeEach
    void setUp() {
        mockLongTermMemory = mock(LongTermMemory.class);
        mockMemory = mock(Memory.class);
        hook = new StaticLongTermMemoryHook(mockLongTermMemory, mockMemory);
        mockAgent = createMockAgent("TestAgent");
    }

    private Agent createMockAgent(String name) {
        return new AgentBase(name) {

            @Override
            protected Mono<Msg> doCall(List<Msg> msgs) {
                return Mono.just(msgs.get(0));
            }

            @Override
            protected Mono<Void> doObserve(Msg msg) {
                return Mono.empty();
            }

            @Override
            protected Mono<Msg> handleInterrupt(InterruptContext context, Msg... originalArgs) {
                return Mono.just(
                        Msg.builder()
                                .name(getName())
                                .role(MsgRole.ASSISTANT)
                                .content(TextBlock.builder().text("Interrupted").build())
                                .build());
            }
        };
    }

    @Test
    void testConstructorWithNullLongTermMemory() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new StaticLongTermMemoryHook(null, mockMemory));

        assertEquals("Long-term memory cannot be null", exception.getMessage());
    }

    @Test
    void testConstructorWithNullMemory() {
        IllegalArgumentException exception =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> new StaticLongTermMemoryHook(mockLongTermMemory, null));

        assertEquals("Memory cannot be null", exception.getMessage());
    }

    @Test
    void testConstructorWithValidParameters() {
        StaticLongTermMemoryHook hook =
                new StaticLongTermMemoryHook(mockLongTermMemory, mockMemory);
        assertNotNull(hook);
        assertEquals(mockLongTermMemory, hook.getLongTermMemory());
        assertEquals(mockMemory, hook.getMemory());
    }

    @Test
    void testPriority() {
        assertEquals(50, hook.priority());
    }

    @Test
    void testOnEventWithPreReasoningEventAndRetrievedMemories() {
        List<Msg> inputMessages = new ArrayList<>();
        inputMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("What do you know about me?").build())
                        .build());

        PreCallEvent event = new PreCallEvent(mockAgent, inputMessages);

        when(mockLongTermMemory.retrieve(any(Msg.class)))
                .thenReturn(Mono.just("User prefers dark mode"));

        StepVerifier.create(hook.onEvent(event))
                .assertNext(
                        resultEvent -> {
                            List<Msg> messages = resultEvent.getInputMessages();
                            assertEquals(2, messages.size());
                            assertEquals(MsgRole.SYSTEM, messages.get(1).getRole());
                            assertTrue(
                                    messages.get(1)
                                            .getTextContent()
                                            .contains("<long_term_memory>"));
                            assertTrue(
                                    messages.get(1)
                                            .getTextContent()
                                            .contains("User prefers dark mode"));
                        })
                .verifyComplete();
    }

    @Test
    void testOnEventWithPreReasoningEventEmptyRetrieval() {
        List<Msg> inputMessages = new ArrayList<>();
        inputMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Hello").build())
                        .build());

        PreReasoningEvent event =
                new PreReasoningEvent(mockAgent, "test-model", null, inputMessages);

        when(mockLongTermMemory.retrieve(any(Msg.class))).thenReturn(Mono.just(""));

        StepVerifier.create(hook.onEvent(event))
                .assertNext(
                        resultEvent -> {
                            assertEquals(1, resultEvent.getInputMessages().size());
                        })
                .verifyComplete();
    }

    @Test
    void testOnEventWithPreReasoningEventRetrievalError() {
        List<Msg> inputMessages = new ArrayList<>();
        inputMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("Test").build())
                        .build());

        PreReasoningEvent event =
                new PreReasoningEvent(mockAgent, "test-model", null, inputMessages);

        when(mockLongTermMemory.retrieve(any(Msg.class)))
                .thenReturn(Mono.error(new RuntimeException("Retrieval error")));

        StepVerifier.create(hook.onEvent(event))
                .assertNext(
                        resultEvent -> {
                            assertEquals(1, resultEvent.getInputMessages().size());
                        })
                .verifyComplete();
    }

    @Test
    void testOnEventWithPostCallEvent() {
        List<Msg> allMessages = new ArrayList<>();
        allMessages.add(
                Msg.builder()
                        .role(MsgRole.USER)
                        .content(TextBlock.builder().text("User message").build())
                        .build());
        allMessages.add(
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Assistant reply").build())
                        .build());

        when(mockMemory.getMessages()).thenReturn(allMessages);
        when(mockLongTermMemory.record(anyList())).thenReturn(Mono.empty());

        Msg replyMsg =
                Msg.builder()
                        .role(MsgRole.ASSISTANT)
                        .content(TextBlock.builder().text("Reply").build())
                        .build();
        PostCallEvent event = new PostCallEvent(mockAgent, replyMsg);

        StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();

        ArgumentCaptor<List<Msg>> captor = ArgumentCaptor.forClass(List.class);
        verify(mockLongTermMemory, times(1)).record(captor.capture());
        assertEquals(2, captor.getValue().size());
    }

    @Test
    void testOnEventWithPostCallEventEmptyMemory() {
        when(mockMemory.getMessages()).thenReturn(new ArrayList<>());

        Msg replyMsg = Msg.builder().role(MsgRole.ASSISTANT).build();
        PostCallEvent event = new PostCallEvent(mockAgent, replyMsg);

        StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();

        verify(mockLongTermMemory, never()).record(anyList());
    }

    @Test
    void testOnEventWithPostCallEventRecordError() {
        List<Msg> messages = List.of(Msg.builder().role(MsgRole.USER).build());
        when(mockMemory.getMessages()).thenReturn(messages);
        when(mockLongTermMemory.record(anyList()))
                .thenReturn(Mono.error(new RuntimeException("Record error")));

        Msg replyMsg = Msg.builder().role(MsgRole.ASSISTANT).build();
        PostCallEvent event = new PostCallEvent(mockAgent, replyMsg);

        StepVerifier.create(hook.onEvent(event)).expectNext(event).verifyComplete();
    }
}
