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
package io.agentscope.core.agui.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@code onStateLoaded} message-merge handler registered by {@link
 * AguiAgentAdapter#buildRuntimeContext}.
 *
 * <p>These tests focus on the merge behavior triggered after AgentState is loaded: when the incoming
 * message list is a full transcript whose tail overlaps the persisted context, the overlap prefix is
 * stripped; when the list is already incremental (no overlap), it is left untouched.
 *
 * <p>Assertions compare message ids (not Msg instances) since {@link Msg} uses identity equality.
 */
@DisplayName("AguiAgentAdapter onStateLoaded message merge")
class AguiAgentAdapterMessageMergeTest {

    @Test
    @DisplayName("buildRuntimeContext registers a non-null onStateLoaded callback")
    void buildRuntimeContext_registersCallback() {
        RuntimeContext ctx = newContextWithState(null);

        assertNotNull(ctx.getOnStateLoaded());
    }

    @Test
    @DisplayName("callback receives the exact context it was registered on")
    void merge_callbackReceivesSameContext() {
        AgentState state = AgentState.builder().context(List.of(msg("m1"))).build();
        RuntimeContext ctx = newContextWithState(state);
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        BiConsumer<RuntimeContext, List<Msg>> original = ctx.getOnStateLoaded();
        ctx.setOnStateLoaded(
                (c, m) -> {
                    seen.set(c);
                    original.accept(c, m);
                });

        fireCallback(ctx, new ArrayList<>(List.of(msg("m1"))));

        assertSame(ctx, seen.get());
    }

    @Test
    @DisplayName("full transcript input: anchor hit strips the overlapping prefix")
    void merge_anchorHit_stripsOverlappingPrefix() {
        AgentState state =
                AgentState.builder().context(List.of(msg("m1"), msg("m2"), msg("m3"))).build();
        RuntimeContext ctx = newContextWithState(state);
        List<Msg> incoming =
                new ArrayList<>(List.of(msg("m1"), msg("m2"), msg("m3"), msg("m4"), msg("m5")));

        fireCallback(ctx, incoming);

        assertEquals(List.of("m4", "m5"), idsOf(incoming));
    }

    @Test
    @DisplayName("incremental input: anchor miss leaves msgs untouched")
    void merge_anchorMiss_leavesMsgsUntouched() {
        AgentState state =
                AgentState.builder().context(List.of(msg("m1"), msg("m2"), msg("m3"))).build();
        RuntimeContext ctx = newContextWithState(state);
        List<Msg> incoming = new ArrayList<>(List.of(msg("m4"), msg("m5")));

        fireCallback(ctx, incoming);

        assertEquals(List.of("m4", "m5"), idsOf(incoming));
    }

    @Test
    @DisplayName("empty persisted context: no-op, msgs untouched")
    void merge_emptyContext_leavesMsgsUntouched() {
        AgentState state = AgentState.builder().build();
        RuntimeContext ctx = newContextWithState(state);
        List<Msg> incoming = new ArrayList<>(List.of(msg("m1"), msg("m2"), msg("m3")));

        fireCallback(ctx, incoming);

        assertEquals(List.of("m1", "m2", "m3"), idsOf(incoming));
    }

    @Test
    @DisplayName("null agent state on context: no-op, msgs untouched")
    void merge_nullState_leavesMsgsUntouched() {
        RuntimeContext ctx = newContextWithState(null);
        List<Msg> incoming = new ArrayList<>(List.of(msg("m1"), msg("m2")));

        fireCallback(ctx, incoming);

        assertEquals(List.of("m1", "m2"), idsOf(incoming));
    }

    @Test
    @DisplayName("empty input msgs: no-op even when context has anchor")
    void merge_emptyIncomingMsgs_noOp() {
        AgentState state = AgentState.builder().context(List.of(msg("m1"), msg("m2"))).build();
        RuntimeContext ctx = newContextWithState(state);
        List<Msg> incoming = new ArrayList<>();

        fireCallback(ctx, incoming);

        assertEquals(List.of(), idsOf(incoming));
    }

    @Test
    @DisplayName("anchor hit at position 0 keeps only messages after it")
    void merge_anchorAtStart_clearsPrefix() {
        AgentState state = AgentState.builder().context(List.of(msg("m1"))).build();
        RuntimeContext ctx = newContextWithState(state);
        List<Msg> incoming = new ArrayList<>(List.of(msg("m1"), msg("m2")));

        fireCallback(ctx, incoming);

        assertEquals(List.of("m2"), idsOf(incoming));
    }

    @Test
    @DisplayName("anchor is the last element of incoming: all duplicates removed")
    void merge_anchorIsLastIncoming_removesAll() {
        AgentState state =
                AgentState.builder().context(List.of(msg("m1"), msg("m2"), msg("m3"))).build();
        RuntimeContext ctx = newContextWithState(state);
        List<Msg> incoming = new ArrayList<>(List.of(msg("m1"), msg("m2"), msg("m3")));

        fireCallback(ctx, incoming);

        assertEquals(List.of(), idsOf(incoming));
    }

    @Test
    @DisplayName("full and incremental inputs converge to the same effective msgs")
    void merge_fullVsIncremental_converge() {
        AgentState state =
                AgentState.builder().context(List.of(msg("m1"), msg("m2"), msg("m3"))).build();

        RuntimeContext ctxFull = newContextWithState(state);
        List<Msg> fullIncoming =
                new ArrayList<>(List.of(msg("m1"), msg("m2"), msg("m3"), msg("m4"), msg("m5")));
        fireCallback(ctxFull, fullIncoming);

        RuntimeContext ctxInc = newContextWithState(state);
        List<Msg> incIncoming = new ArrayList<>(List.of(msg("m4"), msg("m5")));
        fireCallback(ctxInc, incIncoming);

        assertEquals(idsOf(incIncoming), idsOf(fullIncoming));
    }

    @Test
    @DisplayName("firing the callback twice on the same already-merged list is stable")
    void merge_firedTwice_isStable() {
        AgentState state =
                AgentState.builder().context(List.of(msg("m1"), msg("m2"), msg("m3"))).build();
        RuntimeContext ctx = newContextWithState(state);
        List<Msg> incoming = new ArrayList<>(List.of(msg("m1"), msg("m2"), msg("m3"), msg("m4")));

        fireCallback(ctx, incoming);
        List<String> afterFirst = idsOf(incoming);
        fireCallback(ctx, incoming);

        assertEquals(afterFirst, idsOf(incoming));
    }

    // ---------- helpers ----------

    /**
     * Builds a RuntimeContext via {@link AguiAgentAdapter#buildRuntimeContext}, then sets the given
     * AgentState onto it — mirroring what {@code beforeAgentExecution} does right before firing
     * {@code onStateLoaded}.
     */
    private static RuntimeContext newContextWithState(AgentState state) {
        AguiAgentAdapter adapter =
                new AguiAgentAdapter(mock(Agent.class), AguiAdapterConfig.defaultConfig());
        RunAgentInput input = RunAgentInput.builder().threadId("t-1").runId("r-1").build();
        RuntimeContext ctx = adapter.buildRuntimeContext(input, null);
        ctx.setAgentState(state);
        return ctx;
    }

    private static void fireCallback(RuntimeContext ctx, List<Msg> msgs) {
        BiConsumer<RuntimeContext, List<Msg>> callback = ctx.getOnStateLoaded();
        assertNotNull(callback);
        callback.accept(ctx, msgs);
    }

    private static Msg msg(String id) {
        Msg template = new UserMessage(id);
        return Msg.builder().id(id).role(template.getRole()).content(template.getContent()).build();
    }

    private static List<String> idsOf(List<Msg> msgs) {
        return msgs.stream().map(Msg::getId).toList();
    }
}
