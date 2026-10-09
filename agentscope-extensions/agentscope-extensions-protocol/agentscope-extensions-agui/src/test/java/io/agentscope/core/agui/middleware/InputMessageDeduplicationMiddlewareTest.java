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
package io.agentscope.core.agui.middleware;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.converter.AguiMessageConverter;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.agui.model.MessageContent;
import io.agentscope.core.agui.model.TextInputContent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import io.agentscope.core.tool.Toolkit;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * Tests for {@link InputMessageDeduplicationMiddleware}: the {@code extractDelta} algorithm rules (R1–R6)
 * invoked directly, the {@code onAgentStateReady} hook behavior, and ReActAgent integration for
 * the full-history resend scenario.
 *
 * <p>Assertions compare message ids (not {@link Msg} instances) since {@link Msg} uses identity
 * equality.
 */
@DisplayName("InputMessageDeduplicationMiddleware")
class InputMessageDeduplicationMiddlewareTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Nested
    @DisplayName("extractDelta algorithm (R1–R6)")
    class ExtractDeltaAlgorithm {

        @Test
        @DisplayName("R1: empty or null context/input returns the input unchanged")
        void r1_emptyOrNullContextOrInput_returnsIncomingUnchanged() {
            List<Msg> incoming = new ArrayList<>(List.of(msg("m1"), msg("m2")));

            assertEquals(
                    List.of("m1", "m2"),
                    idsOf(InputMessageDeduplicationMiddleware.extractDelta(List.of(), incoming)));
            assertEquals(
                    List.of("m1", "m2"),
                    idsOf(InputMessageDeduplicationMiddleware.extractDelta(null, incoming)));
            assertEquals(
                    List.of(),
                    idsOf(
                            InputMessageDeduplicationMiddleware.extractDelta(
                                    List.of(msg("m1")), List.of())));
            assertEquals(
                    List.of(),
                    idsOf(
                            InputMessageDeduplicationMiddleware.extractDelta(
                                    List.of(msg("m1")), null)));
        }

        @Test
        @DisplayName("R2: anchor id hit strips the prefix at any position, any block types")
        void r2_anchorIdHit_stripsPrefix() {
            // Anchor in the middle: only the tail remains.
            assertEquals(
                    List.of("m4", "m5"),
                    idsOf(
                            InputMessageDeduplicationMiddleware.extractDelta(
                                    List.of(msg("m1"), msg("m2"), msg("m3")),
                                    new ArrayList<>(
                                            List.of(
                                                    msg("m1"), msg("m2"), msg("m3"), msg("m4"),
                                                    msg("m5"))))));
            // Anchor at position 0: everything after it remains.
            assertEquals(
                    List.of("m2"),
                    idsOf(
                            InputMessageDeduplicationMiddleware.extractDelta(
                                    List.of(msg("m1")),
                                    new ArrayList<>(List.of(msg("m1"), msg("m2"))))));
            // The id match wins without inspecting block payloads (same id, different image).
            assertEquals(
                    List.of("u2"),
                    idsOf(
                            InputMessageDeduplicationMiddleware.extractDelta(
                                    List.of(
                                            userMsg("u1", "hello"),
                                            assistantMsgWithImage("a1", "look", "img-1")),
                                    new ArrayList<>(
                                            List.of(
                                                    userMsg("u1", "hello"),
                                                    assistantMsgWithImage("a1", "look", "img-2"),
                                                    userMsg("u2", "again"))))));
        }

        @Test
        @DisplayName("R2: duplicate anchor ids resolve to the last occurrence")
        void r2_duplicateAnchorIds_takeLastOccurrence() {
            List<Msg> context = List.of(msg("m1"), msg("m2"));
            List<Msg> incoming =
                    new ArrayList<>(List.of(msg("m2"), msg("m3"), msg("m2"), msg("m4")));

            List<Msg> delta = InputMessageDeduplicationMiddleware.extractDelta(context, incoming);

            assertEquals(List.of("m4"), idsOf(delta));
        }

        @Test
        @DisplayName("R3: id miss or missing ids — role+content fallback strips the overlap")
        void r3_contentFallback_stripsOverlap() {
            // Client rewrote every id.
            assertEquals(
                    List.of("u2"),
                    idsOf(
                            InputMessageDeduplicationMiddleware.extractDelta(
                                    List.of(userMsg("u1", "hello"), assistantMsg("a1", "done")),
                                    new ArrayList<>(
                                            List.of(
                                                    userMsg("u1-new", "hello"),
                                                    assistantMsg("a1-new", "done"),
                                                    userMsg("u2", "again"))))));
            // The persisted anchor predates id preservation (null id): same strip.
            assertEquals(
                    List.of("u2"),
                    idsOf(
                            InputMessageDeduplicationMiddleware.extractDelta(
                                    List.of(userMsg(null, "hello"), assistantMsg(null, "done")),
                                    new ArrayList<>(
                                            List.of(
                                                    userMsg(null, "hello"),
                                                    assistantMsg(null, "done"),
                                                    userMsg("u2", "again"))))));
            // Same block signature with attachments still matches, regardless of payloads.
            assertEquals(
                    List.of("u2"),
                    idsOf(
                            InputMessageDeduplicationMiddleware.extractDelta(
                                    List.of(
                                            userMsg("u1", "hello"),
                                            assistantMsgWithImage(
                                                    "a1", "here is the chart", "img")),
                                    new ArrayList<>(
                                            List.of(
                                                    userMsg("u1", "hello"),
                                                    assistantMsgWithImage(
                                                            "a1-new", "here is the chart", "img-2"),
                                                    userMsg("u2", "again"))))));
        }

        @Test
        @DisplayName("R3: the fallback refuses to match on empty text, role, or block signature")
        void r3_contentFallback_guardsRefuseToMatch() {
            // Empty-text anchor (pure tool call) never content-matches, even against a
            // same-role empty-text candidate.
            assertEquals(
                    List.of("a2", "u2"),
                    idsOf(
                            InputMessageDeduplicationMiddleware.extractDelta(
                                    List.of(
                                            userMsg("u1", "hello"),
                                            assistantToolCallMsg(null, "tc-1")),
                                    new ArrayList<>(
                                            List.of(
                                                    assistantToolCallMsg("a2", "tc-2"),
                                                    userMsg("u2", "again"))))));
            // Same text but a different role: not the anchor.
            assertEquals(
                    List.of("u1", "u2"),
                    idsOf(
                            InputMessageDeduplicationMiddleware.extractDelta(
                                    List.of(assistantMsg("a1", "done")),
                                    new ArrayList<>(
                                            List.of(
                                                    userMsg("u1", "done"),
                                                    userMsg("u2", "again"))))));
            // Same text but a different block signature (attachment added or lost): a new turn.
            assertEquals(
                    List.of("u1-new", "a1-new", "u2"),
                    idsOf(
                            InputMessageDeduplicationMiddleware.extractDelta(
                                    List.of(
                                            userMsg("u1", "hello"),
                                            assistantMsgWithImage(
                                                    "a1", "here is the chart", "img")),
                                    new ArrayList<>(
                                            List.of(
                                                    userMsg("u1-new", "hello"),
                                                    assistantMsg("a1-new", "here is the chart"),
                                                    userMsg("u2", "and now?"))))));
        }

        @Test
        @DisplayName("R4: empty delta resumes a pending tool run; a trailing result is the delta")
        void r4_pendingTool_resumePaths() {
            List<Msg> context =
                    List.of(
                            userMsg("u1", "what is the weather?"),
                            assistantToolCallMsg("a1", "tc-1"));
            // No new messages yet: empty input correctly resumes the interrupted run.
            assertEquals(
                    List.of(),
                    idsOf(
                            InputMessageDeduplicationMiddleware.extractDelta(
                                    context,
                                    new ArrayList<>(
                                            List.of(
                                                    userMsg("u1", "what is the weather?"),
                                                    assistantToolCallMsg("a1", "tc-1"))))));
            // The tool result arrived in this request: it is exactly the delta (the pending
            // check is not consulted because the delta is not empty).
            assertEquals(
                    List.of("t1"),
                    idsOf(
                            InputMessageDeduplicationMiddleware.extractDelta(
                                    context,
                                    new ArrayList<>(
                                            List.of(
                                                    userMsg("u1", "what is the weather?"),
                                                    assistantToolCallMsg("a1", "tc-1"),
                                                    toolResultMsg("t1", "tc-1"))))));
        }

        @Test
        @DisplayName("R5: regenerate rebuilds the last user message; no user message stays empty")
        void r5_regenerate_restoresLastUserMessage() {
            ChatUsage usage = ChatUsage.builder().inputTokens(11).outputTokens(7).build();
            Msg persistedUser =
                    Msg.builder()
                            .id("u1")
                            .role(MsgRole.USER)
                            .name("alice")
                            .content(TextBlock.builder().text("what is the weather?").build())
                            .metadata(Map.of("source", "test"))
                            .timestamp("2026-10-05 12:00:00.000")
                            .usage(usage)
                            .build();
            List<Msg> context = List.of(persistedUser, assistantMsg("a1", "it is sunny"));

            List<Msg> delta =
                    InputMessageDeduplicationMiddleware.extractDelta(
                            context,
                            new ArrayList<>(
                                    List.of(
                                            userMsg("u1", "what is the weather?"),
                                            assistantMsg("a1", "it is sunny"))));

            assertEquals(1, delta.size());
            Msg restored = delta.get(0);
            assertEquals(MsgRole.USER, restored.getRole());
            assertEquals("what is the weather?", restored.getTextContent());
            assertNotEquals("u1", restored.getId(), "restored prompt must not reuse the id");
            assertNotSame(persistedUser, restored);
            assertTrue(restored instanceof UserMessage, "USER subtype is preserved");
            assertSame(usage, restored.getUsage());
            assertEquals("alice", restored.getName());
            assertEquals(Map.of("source", "test"), restored.getMetadata());
            // Msg's constructor copies the map entry-wise; the restored prompt must not alias
            // the persisted message's live metadata map.
            assertNotSame(persistedUser.getMetadata(), restored.getMetadata());
            assertEquals("2026-10-05 12:00:00.000", restored.getTimestamp());
            assertEquals(List.of("u1", "a1"), idsOf(context), "persisted context is read-only");

            // Without any persisted user message the delta stays empty.
            assertEquals(
                    List.of(),
                    idsOf(
                            InputMessageDeduplicationMiddleware.extractDelta(
                                    List.of(assistantMsg("a1", "done")),
                                    new ArrayList<>(List.of(assistantMsg("a1", "done"))))));
        }

        @Test
        @DisplayName("R6: anchor miss (incremental input or edited history) passes through")
        void r6_anchorMiss_passesThrough() {
            // Incremental client: only the new messages arrive.
            assertEquals(
                    List.of("m4", "m5"),
                    idsOf(
                            InputMessageDeduplicationMiddleware.extractDelta(
                                    List.of(msg("m1"), msg("m2"), msg("m3")),
                                    new ArrayList<>(List.of(msg("m4"), msg("m5"))))));
            // The client edited the resent assistant turn: no id hit (fresh ids), and the
            // edited text no longer matches the anchor content either.
            assertEquals(
                    List.of("u1-new", "a1-new", "u2"),
                    idsOf(
                            InputMessageDeduplicationMiddleware.extractDelta(
                                    List.of(userMsg("u1", "hello"), assistantMsg("a1", "done")),
                                    new ArrayList<>(
                                            List.of(
                                                    userMsg("u1-new", "hello"),
                                                    assistantMsg("a1-new", "done (edited)"),
                                                    userMsg("u2", "again"))))));
        }

        @Test
        @DisplayName("full and incremental inputs converge to the same effective messages")
        void fullVsIncremental_converge() {
            List<Msg> context = List.of(msg("m1"), msg("m2"), msg("m3"));
            List<Msg> incremental = new ArrayList<>(List.of(msg("m4"), msg("m5")));
            List<Msg> full =
                    new ArrayList<>(List.of(msg("m1"), msg("m2"), msg("m3"), msg("m4"), msg("m5")));

            List<Msg> fromIncremental =
                    InputMessageDeduplicationMiddleware.extractDelta(context, incremental);
            List<Msg> fromFull = InputMessageDeduplicationMiddleware.extractDelta(context, full);

            assertEquals(idsOf(fromIncremental), idsOf(fromFull));
        }

        @Test
        @DisplayName("repeated calls on the same input are stable (idempotent)")
        void firedTwice_isStable() {
            List<Msg> context = List.of(msg("m1"), msg("m2"), msg("m3"));
            List<Msg> incoming =
                    new ArrayList<>(List.of(msg("m1"), msg("m2"), msg("m3"), msg("m4")));

            List<Msg> first = InputMessageDeduplicationMiddleware.extractDelta(context, incoming);
            List<Msg> second = InputMessageDeduplicationMiddleware.extractDelta(context, incoming);
            // Firing on the deduplicated result again (a redelivered full resend) changes
            // nothing.
            List<Msg> third = InputMessageDeduplicationMiddleware.extractDelta(context, first);

            assertEquals(idsOf(first), idsOf(second));
            assertEquals(idsOf(first), idsOf(third));
        }

        @Test
        @DisplayName("input and context lists are never modified")
        void inputLists_notModified() {
            List<Msg> context = List.of(userMsg("u1", "hello"), assistantMsg("a1", "done"));
            List<Msg> incoming =
                    new ArrayList<>(
                            List.of(
                                    userMsg("u1", "hello"),
                                    assistantMsg("a1", "done"),
                                    userMsg("u2", "again")));
            List<String> contextIdsBefore = idsOf(context);
            List<String> incomingIdsBefore = idsOf(incoming);

            InputMessageDeduplicationMiddleware.extractDelta(context, incoming);

            assertEquals(contextIdsBefore, idsOf(context));
            assertEquals(incomingIdsBefore, idsOf(incoming));
        }

        @Test
        @DisplayName("very long lists strip in one pass")
        void longList_stripsCorrectly() {
            List<Msg> history = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                history.add(userMsg("h" + i, "turn " + i));
            }
            List<Msg> context = List.copyOf(history);
            List<Msg> incoming = new ArrayList<>(history);
            incoming.add(userMsg("new-1", "new turn 1"));
            incoming.add(userMsg("new-2", "new turn 2"));

            List<Msg> delta = InputMessageDeduplicationMiddleware.extractDelta(context, incoming);

            assertEquals(List.of("new-1", "new-2"), idsOf(delta));
        }

        @Test
        @DisplayName("null entries are skipped, not fatal")
        void nullEntries_skippedNotFatal() {
            // Null inside the incoming list: matching skips it; the pass-through copy keeps it.
            List<Msg> incoming =
                    new ArrayList<>(List.of(userMsg("u1", "hello"), userMsg("u2", "again")));
            incoming.add(1, null);
            List<Msg> delta =
                    InputMessageDeduplicationMiddleware.extractDelta(
                            List.of(userMsg("u1", "hello"), assistantMsg("a1", "done")), incoming);

            assertEquals(3, delta.size());
            assertEquals("u2", delta.get(2).getId());

            // Null tail of the persisted context: the last valid message is the anchor.
            assertEquals(
                    List.of("u2"),
                    idsOf(
                            InputMessageDeduplicationMiddleware.extractDelta(
                                    Arrays.asList(
                                            userMsg("u1", "hello"),
                                            assistantMsg("a1", "done"),
                                            null),
                                    new ArrayList<>(
                                            List.of(
                                                    userMsg("u1", "hello"),
                                                    assistantMsg("a1", "done"),
                                                    userMsg("u2", "again"))))));
            // All-null context: no anchor can be established, fail-open.
            assertEquals(
                    List.of("m4", "m5"),
                    idsOf(
                            InputMessageDeduplicationMiddleware.extractDelta(
                                    Arrays.asList(null, null),
                                    new ArrayList<>(List.of(msg("m4"), msg("m5"))))));
        }
    }

    @Nested
    @DisplayName("middleware hook")
    class MiddlewareHook {

        @Test
        @DisplayName("participates only at ON_AGENT_STATE_READY with a high order")
        void activePoints_andOrder() {
            InputMessageDeduplicationMiddleware middleware =
                    new InputMessageDeduplicationMiddleware();

            assertEquals(
                    Set.of(MiddlewareBase.ExtensionPoint.ON_AGENT_STATE_READY),
                    middleware.activePoints());
            assertEquals(InputMessageDeduplicationMiddleware.ORDER, middleware.order());
            assertTrue(middleware.order() > 1, "runs before default-order business middlewares");
        }

        @Test
        @DisplayName("no-op on empty context, strip in place on hit, untouched on miss")
        void hookBehavior_noOpStripAndMiss() {
            InputMessageDeduplicationMiddleware middleware =
                    new InputMessageDeduplicationMiddleware();

            // Empty persisted context: the full input is already correct.
            List<Msg> coldInput = new ArrayList<>(List.of(msg("m1"), msg("m2")));
            middleware.onAgentStateReady(null, ctx("s1"), AgentState.builder().build(), coldInput);
            assertEquals(List.of("m1", "m2"), idsOf(coldInput));

            // Anchor hit: the private copy is rewritten in place, the persisted state is spared.
            AgentState state = AgentState.builder().context(List.of(msg("m1"), msg("m2"))).build();
            List<Msg> hitInput = new ArrayList<>(List.of(msg("m1"), msg("m2"), msg("m3")));
            middleware.onAgentStateReady(null, ctx("s1"), state, hitInput);
            assertEquals(List.of("m3"), idsOf(hitInput));
            assertEquals(List.of("m1", "m2"), idsOf(state.getContext()));

            // Anchor miss: pass-through.
            List<Msg> missInput = new ArrayList<>(List.of(msg("m2"), msg("m3")));
            middleware.onAgentStateReady(
                    null,
                    ctx("s1"),
                    AgentState.builder().context(List.of(msg("m1"))).build(),
                    missInput);
            assertEquals(List.of("m2", "m3"), idsOf(missInput));
        }
    }

    @Nested
    @DisplayName("ReActAgent integration")
    class ReActAgentIntegration {

        @Test
        @DisplayName("two full-history resends keep the model context free of duplicates")
        void twoRounds_fullResend_noDuplicateContext() {
            CapturingModel model = new CapturingModel();
            InMemoryAgentStateStore store = new InMemoryAgentStateStore();
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("dedup")
                            .model(model)
                            .toolkit(new Toolkit())
                            .stateStore(store)
                            .middleware(new InputMessageDeduplicationMiddleware())
                            .build();

            agent.call(List.of(userMsg("u1", "hello")), rc("user", "session-1")).block(TIMEOUT);
            // Full resend of the persisted transcript plus one new turn; the ids match because
            // they are taken from the persisted context itself.
            List<Msg> secondRound =
                    new ArrayList<>(agent.getAgentState("user", "session-1").getContext());
            secondRound.add(userMsg("u2", "again"));
            List<Msg> callerList = List.copyOf(secondRound);

            agent.call(callerList, rc("user", "session-1")).block(TIMEOUT);

            assertEquals(2, model.rounds.size());
            assertEquals(
                    List.of("hello"),
                    userTexts(model.rounds.get(0)),
                    "first turn sees exactly the first message");
            assertEquals(
                    List.of("hello", "again"),
                    userTexts(model.rounds.get(1)),
                    "second turn must not re-append the resent history");
            assertEquals(3, callerList.size(), "#3370 contract: the caller's list is untouched");
        }

        @Test
        @DisplayName("incremental client (anchor miss) keeps context complete without loss")
        void anchorMiss_twoRounds_noDuplicateNoLoss() {
            CapturingModel model = new CapturingModel();
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("dedup")
                            .model(model)
                            .toolkit(new Toolkit())
                            .stateStore(new InMemoryAgentStateStore())
                            .middleware(new InputMessageDeduplicationMiddleware())
                            .build();

            agent.call(List.of(userMsg("u1", "hello")), rc("user", "session-2")).block(TIMEOUT);
            agent.call(List.of(userMsg("u2", "again")), rc("user", "session-2")).block(TIMEOUT);

            assertEquals(2, model.rounds.size());
            assertEquals(List.of("hello"), userTexts(model.rounds.get(0)));
            assertEquals(
                    List.of("hello", "again"),
                    userTexts(model.rounds.get(1)),
                    "R6 pass-through must neither duplicate nor lose turns");
        }

        @Test
        @DisplayName("a full resend keeps the same-turn system and tool pairing")
        void fullResend_preservesSameTurnToolPairing() {
            List<List<ContentBlock>> scriptedResponses =
                    List.of(
                            List.of(
                                    TextBlock.builder().text("let me check").build(),
                                    ToolUseBlock.builder()
                                            .id("call-1")
                                            .name("echo")
                                            .input(Map.of("text", "hello"))
                                            .build()),
                            List.of(TextBlock.builder().text("done").build()));
            CapturingModel model = new CapturingModel(scriptedResponses);
            Toolkit toolkit = new Toolkit();
            toolkit.registerTool(new EchoTools());
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("dedup-tools")
                            .model(model)
                            .toolkit(toolkit)
                            .stateStore(new InMemoryAgentStateStore())
                            .middleware(new InputMessageDeduplicationMiddleware())
                            .build();

            agent.call(List.of(userMsg("u1", "hello")), rc("user", "session-3")).block(TIMEOUT);
            List<List<MsgRole>> firstRunRoles = model.roleViews();
            assertEquals(
                    List.of(
                            List.of(MsgRole.USER),
                            List.of(MsgRole.USER, MsgRole.ASSISTANT, MsgRole.TOOL)),
                    firstRunRoles,
                    "first turn runs the tool call/result pairing");

            List<Msg> secondRound =
                    new ArrayList<>(agent.getAgentState("user", "session-3").getContext());
            secondRound.add(userMsg("u2", "again"));
            model.scriptMore(List.of(TextBlock.builder().text("done-2").build()));

            agent.call(secondRound, rc("user", "session-3")).block(TIMEOUT);

            List<Msg> finalView = model.rounds.get(model.rounds.size() - 1);
            assertEquals(
                    List.of(
                            MsgRole.USER,
                            MsgRole.ASSISTANT,
                            MsgRole.TOOL,
                            MsgRole.ASSISTANT,
                            MsgRole.USER),
                    rolesOf(finalView),
                    "second turn keeps the tool pairing exactly once and appends only the delta");
            assertEquals(
                    1,
                    finalView.stream()
                            .filter(m -> m.hasContentBlocks(ToolResultBlock.class))
                            .count(),
                    "tool result appears once");
        }

        @Test
        @DisplayName("without the middleware a full resend duplicates the context (default)")
        void unregistered_fullResend_duplicatesByDefault() {
            CapturingModel model = new CapturingModel();
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("no-dedup")
                            .model(model)
                            .toolkit(new Toolkit())
                            .stateStore(new InMemoryAgentStateStore())
                            .build();

            agent.call(List.of(userMsg("u1", "hello")), rc("user", "session-4")).block(TIMEOUT);
            List<Msg> secondRound =
                    new ArrayList<>(agent.getAgentState("user", "session-4").getContext());
            secondRound.add(userMsg("u2", "again"));

            agent.call(secondRound, rc("user", "session-4")).block(TIMEOUT);

            assertEquals(
                    List.of("hello", "hello", "again"),
                    userTexts(model.rounds.get(1)),
                    "without registration the resent history is appended verbatim");
        }

        @Test
        @DisplayName("stateless agent (empty context) behaves as before")
        void statelessAgent_emptyContext_unchanged() {
            CapturingModel model = new CapturingModel();
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("stateless")
                            .model(model)
                            .toolkit(new Toolkit())
                            .middleware(new InputMessageDeduplicationMiddleware())
                            .build();

            agent.call(List.of(userMsg("u1", "hello")), rc("user", "fresh-1")).block(TIMEOUT);
            agent.call(List.of(userMsg("u2", "again")), rc("user", "fresh-2")).block(TIMEOUT);

            assertEquals(List.of("hello"), userTexts(model.rounds.get(0)));
            assertEquals(List.of("again"), userTexts(model.rounds.get(1)));
        }

        @Test
        @DisplayName("the deduplication runs before lower-order business middlewares")
        void order_runsBeforeBusinessMiddleware() {
            List<String> trace = new CopyOnWriteArrayList<>();
            MiddlewareBase business =
                    new MiddlewareBase() {
                        @Override
                        public int order() {
                            return 1;
                        }

                        @Override
                        public void onAgentStateReady(
                                Agent agent,
                                RuntimeContext ctx,
                                AgentState state,
                                List<Msg> input) {
                            trace.add("business:" + input.size());
                        }
                    };
            CapturingModel model = new CapturingModel();
            InMemoryAgentStateStore store = new InMemoryAgentStateStore();
            ReActAgent agent =
                    ReActAgent.builder()
                            .name("ordered")
                            .model(model)
                            .toolkit(new Toolkit())
                            .stateStore(store)
                            .middlewares(
                                    List.of(new InputMessageDeduplicationMiddleware(), business))
                            .build();

            agent.call(List.of(userMsg("u1", "hello")), rc("user", "session-5")).block(TIMEOUT);
            List<Msg> secondRound =
                    new ArrayList<>(
                            store.get("user", "session-5", "agent_state", AgentState.class)
                                    .orElseThrow()
                                    .getContext());
            secondRound.add(userMsg("u2", "again"));

            agent.call(secondRound, rc("user", "session-5")).block(TIMEOUT);

            // The last notification shows the business middleware observing 1 message: the
            // deduplication already stripped the resent history (the first turn has 1 input
            // too).
            assertEquals("business:1", trace.get(trace.size() - 1));
        }
    }

    @Nested
    @DisplayName("converter round-trip and failure surface (full pass-through)")
    class ConverterRoundTrip {

        private final AguiMessageConverter converter = new AguiMessageConverter();

        @Test
        @DisplayName("native multi-block assistant message: id survives the round trip and anchors")
        void multiBlockAssistant_roundTrip_anchorsById() {
            Msg nativeAssistant = assistantMsgWithImage("a1", "here is the chart", "img");
            List<Msg> context = List.of(userMsg("u1", "hello"), nativeAssistant);

            Msg back = converter.toMsg(converter.toAguiMessage(nativeAssistant));
            assertEquals("a1", back.getId(), "converter must preserve the message id (R2 basis)");

            List<Msg> delta =
                    InputMessageDeduplicationMiddleware.extractDelta(
                            context, new ArrayList<>(List.of(back, userMsg("u2", "again"))));

            assertEquals(List.of("u2"), idsOf(delta));
        }

        @Test
        @DisplayName("a resent history the converter cannot map fails explicitly")
        void unmappableStructuredContent_failsExplicitly() {
            AguiMessage structuredAssistant =
                    new AguiMessage(
                            "a1",
                            "assistant",
                            new MessageContent.Blocks(List.of(new TextInputContent("hi"))),
                            null,
                            null);

            IllegalArgumentException error =
                    assertThrows(
                            IllegalArgumentException.class,
                            () -> converter.toMsg(structuredAssistant));

            assertTrue(
                    error.getMessage().contains("Structured content blocks"),
                    "the error must be locatable: " + error.getMessage());
        }
    }

    // ---------- shared fixtures ----------

    /** Records every reasoning round's message view, replaying scripted responses in order. */
    private static final class CapturingModel extends ChatModelBase {
        private final List<List<ContentBlock>> script = new ArrayList<>();
        private final List<List<Msg>> rounds = new CopyOnWriteArrayList<>();

        CapturingModel() {
            this(List.of(List.of(TextBlock.builder().text("ok").build())));
        }

        CapturingModel(List<List<ContentBlock>> initialScript) {
            script.addAll(initialScript);
        }

        void scriptMore(List<ContentBlock> response) {
            script.add(response);
        }

        @Override
        public String getModelName() {
            return "capturing";
        }

        @Override
        protected Flux<ChatResponse> doStream(
                List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            rounds.add(List.copyOf(messages));
            List<ContentBlock> response =
                    !script.isEmpty() && script.size() > rounds.size() - 1
                            ? script.get(rounds.size() - 1)
                            : List.of(TextBlock.builder().text("ok").build());
            return Flux.just(ChatResponse.builder().content(response).build());
        }

        List<List<MsgRole>> roleViews() {
            return rounds.stream().map(InputMessageDeduplicationMiddlewareTest::rolesOf).toList();
        }
    }

    /** Minimal tool set for the tool-pairing scenario. */
    static final class EchoTools {

        @Tool(name = "echo", description = "Echo the given text back")
        public ToolResultBlock echo(
                @ToolParam(name = "text", description = "The text to echo") String text) {
            return ToolResultBlock.text("echo:" + text);
        }
    }

    private static RuntimeContext rc(String userId, String sessionId) {
        return RuntimeContext.builder().userId(userId).sessionId(sessionId).build();
    }

    private static RuntimeContext ctx(String sessionId) {
        return RuntimeContext.builder().sessionId(sessionId).build();
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

    private static Msg assistantMsgWithImage(String id, String text, String imageUrl) {
        return Msg.builder()
                .id(id)
                .role(MsgRole.ASSISTANT)
                .content(
                        TextBlock.builder().text(text).build(),
                        ImageBlock.builder().source(new URLSource(imageUrl)).build())
                .build();
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
                                .build())
                .build();
    }

    private static Msg toolResultMsg(String id, String toolCallId) {
        return Msg.builder()
                .id(id)
                .role(MsgRole.TOOL)
                .content(ToolResultBlock.builder().id(toolCallId).name("get_weather").build())
                .build();
    }

    private static List<String> idsOf(List<Msg> msgs) {
        return msgs.stream().map(Msg::getId).toList();
    }

    private static List<String> userTexts(List<Msg> msgs) {
        return msgs.stream()
                .filter(m -> m.getRole() == MsgRole.USER)
                .map(Msg::getTextContent)
                .toList();
    }

    private static List<MsgRole> rolesOf(List<Msg> msgs) {
        return msgs.stream().map(Msg::getRole).toList();
    }
}
