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
package io.agentscope.core.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Regression tests for the {@code onAgentStateBound} hook contract on the agent side: the hook
 * may modify the incoming message list in place, and the agent must hand it a private mutable
 * copy so immutable caller input (e.g. {@code List.copyOf}, which {@code AguiMessageConverter}
 * produces) neither throws nor silently loses the modification.
 */
@DisplayName("ReActAgent onAgentStateBound with immutable caller input")
class OnAgentStateBoundImmutableInputTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private Model mockModel;
    private List<List<Msg>> modelInputs;

    @BeforeEach
    void setUp() {
        mockModel = mock(Model.class);
        modelInputs = new ArrayList<>();
    }

    @Test
    @DisplayName("hook strips immutable List.copyOf input in place; strip reaches the model")
    void streamEvents_immutableInput_hookStripIsSafeAndEffective() {
        stubModelText("ok");
        AtomicReference<AgentState> seenState = new AtomicReference<>();
        ReActAgent agent = ReActAgent.builder().name("agent").model(mockModel).maxIters(1).build();
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .sessionId("s-immutable")
                        .onAgentStateBound(
                                (c, m) -> {
                                    seenState.set(c.getAgentState());
                                    // In-place strip of the already-persisted prefix, mirroring
                                    // the AG-UI merge handler. Throws UnsupportedOperationException
                                    // on an immutable list if no mutable copy was made.
                                    m.subList(0, 1).clear();
                                })
                        .build();
        List<Msg> immutableInput =
                List.copyOf(List.of(userMsg("first question"), userMsg("second question")));

        agent.streamEvents(immutableInput, ctx).blockLast(TIMEOUT);

        // The hook fired after the call-scoped state was bound...
        assertNotNull(seenState.get(), "hook should observe the bound AgentState");
        // ...and its modification took effect: the model saw only the stripped suffix.
        assertEquals(1, modelInputs.size());
        assertEquals(
                List.of("second question"),
                modelInputs.get(0).stream()
                        .filter(m -> m.getRole() == MsgRole.USER)
                        .map(Msg::getTextContent)
                        .toList());
    }

    @Test
    @DisplayName("no hook registered: caller's immutable list flows through unchanged (no copy)")
    void streamEvents_noHook_unchanged() {
        stubModelText("ok");
        ReActAgent agent = ReActAgent.builder().name("agent").model(mockModel).maxIters(1).build();
        RuntimeContext ctx = RuntimeContext.builder().sessionId("s-no-hook").build();

        agent.streamEvents(List.copyOf(List.of(userMsg("hello"))), ctx).blockLast(TIMEOUT);

        assertEquals(1, modelInputs.size());
        List<String> userTexts =
                modelInputs.get(0).stream()
                        .filter(m -> m.getRole() == MsgRole.USER)
                        .map(Msg::getTextContent)
                        .toList();
        assertEquals(List.of("hello"), userTexts);
    }

    @Test
    @DisplayName("hook fires on a brand-new session whose AgentState is empty")
    void streamEvents_brandNewSession_hookStillFires() {
        stubModelText("ok");
        AtomicReference<AgentState> seenState = new AtomicReference<>();
        ReActAgent agent = ReActAgent.builder().name("agent").model(mockModel).maxIters(1).build();
        RuntimeContext ctx =
                RuntimeContext.builder()
                        .sessionId("s-new")
                        .onAgentStateBound((c, m) -> seenState.set(c.getAgentState()))
                        .build();

        agent.streamEvents(List.copyOf(List.of(userMsg("hello"))), ctx).blockLast(TIMEOUT);

        AgentState state = seenState.get();
        assertNotNull(state, "hook fires even when the session state is brand-new");
    }

    // ---------- helpers ----------

    private void stubModelText(String text) {
        ChatResponse response =
                ChatResponse.builder()
                        .content(List.of(TextBlock.builder().text(text).build()))
                        .build();
        when(mockModel.getModelName()).thenReturn("stub");
        when(mockModel.stream(anyList(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            modelInputs.add(new ArrayList<>(invocation.getArgument(0)));
                            return Flux.just(response);
                        });
    }

    private static Msg userMsg(String text) {
        return Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }
}
