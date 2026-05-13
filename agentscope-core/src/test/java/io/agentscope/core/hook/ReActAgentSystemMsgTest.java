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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Tests for the unified system-message propagation introduced by the HookEvent.systemMsg
 * refactoring.
 *
 * <p>Key scenarios verified:
 * <ul>
 *   <li>sysPrompt is seeded as systemMsg before PreCallEvent hooks run</li>
 *   <li>systemMsg modified by a PreCallEvent hook is propagated to PreReasoningEvent</li>
 *   <li>systemMsg is prepended to the model.stream() input as the first message</li>
 *   <li>PreCallEvent.inputMessages now contains memory snapshot + callArgs (full view)</li>
 *   <li>Hooks that inject SYSTEM into inputMessages.tail throw IllegalStateException</li>
 * </ul>
 */
@DisplayName("ReActAgent system-message propagation tests")
class ReActAgentSystemMsgTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private Model mockModel;
    private InMemoryMemory memory;

    @BeforeEach
    void setUp() {
        mockModel = mock(Model.class);
        memory = new InMemoryMemory();
    }

    /** Stubs the model to emit a single text response. */
    private void stubModelText(String text) {
        ChatResponse response =
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text(text).build()))
                        .build();
        when(mockModel.stream(anyList(), any(), any())).thenReturn(Flux.just(response));
        when(mockModel.getModelName()).thenReturn("stub");
    }

    private Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    // =========================================================================

    @Nested
    @DisplayName("sysPrompt seeded as systemMsg")
    class SysPromptSeeding {

        @Test
        @DisplayName("sysPrompt becomes systemMsg before PreCallEvent hooks run")
        void sysPrompt_isSeededIntoSystemMsg() {
            AtomicReference<Msg> capturedSysMsg = new AtomicReference<>();

            stubModelText("done");
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("agent")
                            .model(mockModel)
                            .memory(memory)
                            .sysPrompt("You are a helpful assistant.")
                            .maxIters(1)
                            .hook(
                                    new Hook() {
                                        @Override
                                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                                            if (event instanceof PreCallEvent) {
                                                capturedSysMsg.set(event.getSystemMessage());
                                            }
                                            return Mono.just(event);
                                        }
                                    })
                            .build();

            agent.call(List.of(userMsg("hello"))).block(TIMEOUT);

            Msg sys = capturedSysMsg.get();
            assertNotNull(sys);
            assertEquals(MsgRole.SYSTEM, sys.getRole());
            assertTrue(
                    sys.getTextContent().contains("You are a helpful assistant."),
                    "systemMsg should contain sysPrompt text");
        }

        @Test
        @DisplayName("agent with empty sysPrompt seeds null systemMsg")
        void emptySysPrompt_seedsNullSystemMsg() {
            AtomicReference<Msg> capturedSysMsg =
                    new AtomicReference<>(
                            Msg.builder()
                                    .role(MsgRole.SYSTEM)
                                    .content(TextBlock.builder().text("marker").build())
                                    .build());

            stubModelText("done");
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("agent")
                            .model(mockModel)
                            .memory(memory)
                            .maxIters(1)
                            .hook(
                                    new Hook() {
                                        @Override
                                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                                            if (event instanceof PreCallEvent) {
                                                capturedSysMsg.set(event.getSystemMessage());
                                            }
                                            return Mono.just(event);
                                        }
                                    })
                            .build();

            agent.call(List.of(userMsg("hello"))).block(TIMEOUT);

            // Without sysPrompt, the seed is null
            assertTrue(
                    capturedSysMsg.get() == null || capturedSysMsg.get().getTextContent().isBlank(),
                    "Expected null or blank systemMsg when sysPrompt is empty");
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("systemMsg propagated to reasoning")
    class SystemMsgPropagation {

        @Test
        @DisplayName("systemMsg set by PreCallEvent hook appears as first message in model input")
        void preCallHook_systemMsg_prependedToModelInput() {
            List<List<Msg>> modelInputCapture = new ArrayList<>();

            when(mockModel.getModelName()).thenReturn("stub");
            when(mockModel.stream(anyList(), any(), any()))
                    .thenAnswer(
                            invocation -> {
                                @SuppressWarnings("unchecked")
                                List<Msg> msgs = invocation.getArgument(0);
                                modelInputCapture.add(new ArrayList<>(msgs));
                                ChatResponse resp =
                                        ChatResponse.builder()
                                                .content(
                                                        List.of(
                                                                TextBlock.builder()
                                                                        .text("ok")
                                                                        .build()))
                                                .build();
                                return Flux.just(resp);
                            });

            ReActAgent agent =
                    ReActAgent.builder()
                            .name("agent")
                            .model(mockModel)
                            .memory(memory)
                            .sysPrompt("base prompt")
                            .maxIters(1)
                            .hook(
                                    new Hook() {
                                        @Override
                                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                                            if (event instanceof PreCallEvent) {
                                                event.appendSystemContent(" extra context");
                                            }
                                            return Mono.just(event);
                                        }
                                    })
                            .build();

            agent.call(List.of(userMsg("hi"))).block(TIMEOUT);

            assertEquals(1, modelInputCapture.size(), "model.stream should be called once");
            List<Msg> input = modelInputCapture.get(0);
            assertTrue(input.size() >= 1, "model should receive at least one message");

            Msg firstMsg = input.get(0);
            assertEquals(MsgRole.SYSTEM, firstMsg.getRole(), "first message must be SYSTEM");
            String sysText = firstMsg.getTextContent();
            assertTrue(sysText.contains("base prompt"), "systemMsg should contain sysPrompt");
            assertTrue(
                    sysText.contains("extra context"),
                    "systemMsg should include text appended by hook");
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("PreCallEvent full message view")
    class PreCallFullMessageView {

        @Test
        @DisplayName("PreCallEvent inputMessages contains memory snapshot + callArgs")
        void preCallEvent_containsMemoryPlusCallArgs() {
            // Seed memory with one prior message
            Msg priorMsg = userMsg("prior turn");
            memory.addMessage(priorMsg);

            AtomicReference<List<Msg>> capturedInput = new AtomicReference<>();

            stubModelText("done");
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("agent")
                            .model(mockModel)
                            .memory(memory)
                            .maxIters(1)
                            .hook(
                                    new Hook() {
                                        @Override
                                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                                            if (event instanceof PreCallEvent pre) {
                                                capturedInput.set(
                                                        new ArrayList<>(pre.getInputMessages()));
                                            }
                                            return Mono.just(event);
                                        }
                                    })
                            .build();

            Msg callArg = userMsg("new message");
            agent.call(List.of(callArg)).block(TIMEOUT);

            List<Msg> input = capturedInput.get();
            assertNotNull(input);
            assertEquals(2, input.size(), "inputMessages should be [prior, callArg]");
            assertEquals(
                    "prior turn",
                    input.get(0).getTextContent(),
                    "first message should be the memory snapshot");
            assertEquals(
                    "new message",
                    input.get(1).getTextContent(),
                    "second message should be the call argument");
        }
    }

    // =========================================================================

    @Nested
    @DisplayName("SYSTEM-in-tail guard")
    class SystemInTailGuard {

        @Test
        @DisplayName("Hook injecting SYSTEM into inputMessages tail throws IllegalStateException")
        void hook_injectingSystemIntoTail_throws() {
            stubModelText("done");
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("agent")
                            .model(mockModel)
                            .memory(memory)
                            .maxIters(1)
                            .hook(
                                    new Hook() {
                                        @Override
                                        public <T extends HookEvent> Mono<T> onEvent(T event) {
                                            if (event instanceof PreCallEvent pre) {
                                                // Forbidden: append SYSTEM to inputMessages
                                                List<Msg> msgs =
                                                        new ArrayList<>(pre.getInputMessages());
                                                msgs.add(
                                                        Msg.builder()
                                                                .role(MsgRole.SYSTEM)
                                                                .content(
                                                                        TextBlock.builder()
                                                                                .text("bad")
                                                                                .build())
                                                                .build());
                                                pre.setInputMessages(msgs);
                                            }
                                            return Mono.just(event);
                                        }
                                    })
                            .build();

            assertThrows(
                    Exception.class,
                    () -> agent.call(List.of(userMsg("hello"))).block(TIMEOUT),
                    "Should throw when SYSTEM message appears in inputMessages tail");
        }
    }
}
