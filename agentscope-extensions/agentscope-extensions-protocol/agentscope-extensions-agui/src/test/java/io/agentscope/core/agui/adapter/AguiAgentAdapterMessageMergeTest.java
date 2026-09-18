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
package io.agentscope.core.agui.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the {@code onAgentStateBound} message-merge handler registered by {@link
 * AguiAgentAdapter#buildRuntimeContext}.
 *
 * <p>These tests focus on the merge behavior triggered after the call-scoped AgentState is bound:
 * when the incoming message list is a full transcript whose tail overlaps the persisted context,
 * the overlap prefix is stripped; when the list is already incremental (no overlap), it is left
 * untouched. When the id anchor misses, a role+content fallback performs the same strip, and a
 * regenerate request (anchor is the last incoming element) keeps the last persisted user message
 * as the prompt unless tool calls are pending.
 *
 * <p>Assertions compare message ids (not Msg instances) since {@link Msg} uses identity equality.
 */
@DisplayName("AguiAgentAdapter onAgentStateBound message merge")
class AguiAgentAdapterMessageMergeTest {

    @Test
    @DisplayName("buildRuntimeContext registers a non-null onAgentStateBound callback")
    void buildRuntimeContext_registersCallback() {
        RuntimeContext ctx = newContextWithState(null);

        assertNotNull(ctx.getOnAgentStateBound());
    }

    @Test
    @DisplayName("callback receives the exact context it was registered on")
    void merge_callbackReceivesSameContext() {
        AgentState state = AgentState.builder().context(List.of(msg("m1"))).build();
        RuntimeContext ctx = newContextWithState(state);
        AtomicReference<RuntimeContext> seen = new AtomicReference<>();
        BiConsumer<RuntimeContext, List<Msg>> original = ctx.getOnAgentStateBound();
        ctx.setOnAgentStateBound(
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
    @DisplayName("regenerate: anchor is the last incoming element, last user message restored")
    void merge_regenerate_restoresLastUserMessage() {
        AgentState state =
                AgentState.builder()
                        .context(
                                List.of(
                                        userMsg("u1", "what is the weather?"),
                                        assistantMsg("a1", "it is sunny")))
                        .build();
        RuntimeContext ctx = newContextWithState(state);
        List<Msg> incoming =
                new ArrayList<>(
                        List.of(
                                userMsg("u1", "what is the weather?"),
                                assistantMsg("a1", "it is sunny")));

        fireCallback(ctx, incoming);

        // The full-transcript strip emptied the list; the last persisted user turn is restored
        // as the prompt so the call re-answers the last question (regenerate semantics).
        assertEquals(List.of("u1"), idsOf(incoming));
    }

    @Test
    @DisplayName("regenerate with user-tail context: last user message restored")
    void merge_anchorIsLastIncomingWithUserTail_restoresLastUser() {
        AgentState state = AgentState.builder().context(List.of(msg("m1"), msg("m2"))).build();
        RuntimeContext ctx = newContextWithState(state);
        List<Msg> incoming = new ArrayList<>(List.of(msg("m1"), msg("m2")));

        fireCallback(ctx, incoming);

        assertEquals(List.of("m2"), idsOf(incoming));
    }

    @Test
    @DisplayName("regenerate with pending tool use: input stays empty (resume path)")
    void merge_regenerateWithPendingTool_staysEmpty() {
        AgentState state =
                AgentState.builder()
                        .context(
                                List.of(
                                        userMsg("u1", "what is the weather?"),
                                        assistantToolCallMsg("a1", "tc-1")))
                        .build();
        RuntimeContext ctx = newContextWithState(state);
        List<Msg> incoming =
                new ArrayList<>(
                        List.of(
                                userMsg("u1", "what is the weather?"),
                                assistantToolCallMsg("a1", "tc-1")));

        fireCallback(ctx, incoming);

        // Pending tool calls + empty input is the resume path — no user prompt is injected.
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

    @Test
    @DisplayName("client rewrote message ids: role+content fallback strips the overlap")
    void merge_rewrittenIds_contentFallback_stripsOverlap() {
        AgentState state =
                AgentState.builder()
                        .context(List.of(userMsg("u1", "hello"), assistantMsg("a1", "done")))
                        .build();
        RuntimeContext ctx = newContextWithState(state);
        // Same transcript with freshly generated client-side ids, plus one new user message.
        List<Msg> incoming =
                new ArrayList<>(
                        List.of(
                                userMsg("u1-new", "hello"),
                                assistantMsg("a1-new", "done"),
                                userMsg("u2", "again")));

        fireCallback(ctx, incoming);

        // The id scan missed, but the assistant anchor matched by role+content, so the overlap
        // prefix was stripped instead of duplicating the persisted history.
        assertEquals(List.of("u2"), idsOf(incoming));
    }

    @Test
    @DisplayName("anchor without id: content fallback still deduplicates")
    void merge_nullAnchorId_contentFallback_stripsOverlap() {
        AgentState state =
                AgentState.builder()
                        .context(List.of(userMsg(null, "hello"), assistantMsg(null, "done")))
                        .build();
        RuntimeContext ctx = newContextWithState(state);
        List<Msg> incoming =
                new ArrayList<>(
                        List.of(
                                userMsg(null, "hello"),
                                assistantMsg(null, "done"),
                                userMsg("u2", "again")));

        fireCallback(ctx, incoming);

        assertEquals(List.of("u2"), idsOf(incoming));
    }

    @Test
    @DisplayName("anchor without id or text (pure tool call): no content match, msgs untouched")
    void merge_emptyTextAnchor_noContentMatch() {
        AgentState state =
                AgentState.builder()
                        .context(
                                List.of(userMsg("u1", "hello"), assistantToolCallMsg(null, "tc-1")))
                        .build();
        RuntimeContext ctx = newContextWithState(state);
        // Same-role empty-text candidate first (would collide on "".equals("") without the
        // empty-text guard), then a new user message — nothing may be stripped.
        List<Msg> incoming =
                new ArrayList<>(
                        List.of(assistantToolCallMsg("a2", "tc-2"), userMsg("u2", "again")));

        fireCallback(ctx, incoming);

        assertEquals(List.of("a2", "u2"), idsOf(incoming));
    }

    // ---------- helpers ----------

    /**
     * Builds a RuntimeContext via {@link AguiAgentAdapter#buildRuntimeContext}, then sets the given
     * AgentState onto it — mirroring what {@code beforeAgentExecution} does right before firing
     * {@code onAgentStateBound}.
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
        BiConsumer<RuntimeContext, List<Msg>> callback = ctx.getOnAgentStateBound();
        assertNotNull(callback);
        callback.accept(ctx, msgs);
    }

    /** User message whose text equals its id (distinctive content for anchor matching). */
    private static Msg msg(String id) {
        return userMsg(id, id);
    }

    private static Msg userMsg(String id, String text) {
        return textMsg(id, MsgRole.USER, text);
    }

    private static Msg assistantMsg(String id, String text) {
        return textMsg(id, MsgRole.ASSISTANT, text);
    }

    private static Msg textMsg(String id, MsgRole role, String text) {
        return Msg.builder()
                .id(id)
                .role(role)
                .content(TextBlock.builder().text(text).build())
                .build();
    }

    /** Assistant message containing only a tool call (no text), mirroring a pending HITL turn. */
    private static Msg assistantToolCallMsg(String id, String toolCallId) {
        return Msg.builder()
                .id(id)
                .role(MsgRole.ASSISTANT)
                .content(
                        ToolUseBlock.builder()
                                .id(toolCallId)
                                .name("get_weather")
                                .input(Map.of("city", "Beijing"))
                                .content("{\"city\":\"Beijing\"}")
                                .build())
                .build();
    }

    private static List<String> idsOf(List<Msg> msgs) {
        return msgs.stream().map(Msg::getId).toList();
    }
}
