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
package io.agentscope.harness.agent.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.middleware.ModelRequestPreparer.Purpose;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Shape contract for provider-usage calibration: the delegate's estimate is corrected to the
 * anchor {@code providerInputTokens + increment} only on a matching conversation prefix, the
 * same model instance, and unchanged message content. The anchor is committed only from a
 * verifiably paired usage message. Without any anchor the behavior is byte-identical to the
 * delegate. Interleaved
 * conversations calibrate independently through their own slot, and the bounded slot set
 * drops the oldest tracked conversation.
 */
class CalibratedTokenEstimatorTest {
    private final Agent agent = mock(Agent.class);
    private final Model primary = mock(Model.class);
    private final Model otherModel = mock(Model.class);
    private final AgentState state = AgentState.builder().build();
    private final RuntimeContext rc = RuntimeContext.builder().agentState(state).build();
    private final List<ContextManifest> manifests = new ArrayList<>();

    /** Deliberately biased: 200 fixed + 100 per message + 50 per tool. */
    private final ContextTokenEstimator biased =
            request ->
                    new ContextTokenEstimator.Estimate(
                            200 + 100L * request.messages().size() + 50L * request.tools().size(),
                            "biased",
                            false);

    @BeforeEach
    void setUp() {
        when(primary.getModelName()).thenReturn("primary");
        when(otherModel.getModelName()).thenReturn("other");
        when(agent.getName()).thenReturn("test");
    }

    @Test
    void fallsBackToDelegateBeforeAnyAnchor() {
        CalibratedTokenEstimator estimator = new CalibratedTokenEstimator(biased);
        var estimate = estimator.estimate(input(List.of(msg("a"), msg("b")), List.of(), primary));

        assertEquals(400, estimate.tokens());
        assertEquals("biased", estimate.method());
    }

    @Test
    void followsProviderTruthOnMatchingPrefix() {
        CalibratedTokenEstimator estimator = new CalibratedTokenEstimator(biased);
        Msg first = msg("a");
        Msg second = msg("b");
        estimator.recordPreparedRequest(input(List.of(first, second), List.of(), primary));
        Msg reply = assistantWithUsage(777);
        Msg next = msg("c");
        estimator.calibrateFromHistory(List.of(first, second, reply, next));

        var estimate =
                estimator.estimate(input(List.of(first, second, reply, next), List.of(), primary));

        // Delegate says 600 for the candidate and said 400 for the anchored request:
        // 777 + (600 - 400) = 977.
        assertEquals(977, estimate.tokens());
        assertEquals("biased+provider-anchor", estimate.method());
    }

    @Test
    void negativeIncrementClampsAtZero() {
        CalibratedTokenEstimator estimator = new CalibratedTokenEstimator(biased);
        Msg first = msg("a");
        Msg second = msg("b");
        // Anchored request carried four tools (delegate frame 600); the candidate drops them.
        estimator.recordPreparedRequest(input(List.of(first, second), tools(4), primary));
        estimator.calibrateFromHistory(List.of(first, second, assistantWithUsage(50)));

        var estimate = estimator.estimate(input(List.of(first, second), List.of(), primary));

        assertEquals(0, estimate.tokens());
    }

    @Test
    void fallsBackWhenHistoryPrefixWasRewritten() {
        CalibratedTokenEstimator estimator = new CalibratedTokenEstimator(biased);
        Msg first = msg("a");
        Msg second = msg("b");
        estimator.recordPreparedRequest(input(List.of(first, second), List.of(), primary));
        estimator.calibrateFromHistory(List.of(first, second, assistantWithUsage(777)));
        Msg summary = msg("compacted summary");

        var estimate = estimator.estimate(input(List.of(summary, msg("tail")), List.of(), primary));

        assertEquals(400, estimate.tokens());
        assertEquals("biased", estimate.method());
    }

    @Test
    void fallsBackOnDifferentModelInstanceWithSameName() {
        CalibratedTokenEstimator estimator = new CalibratedTokenEstimator(biased);
        when(otherModel.getModelName()).thenReturn("primary");
        Msg first = msg("a");
        Msg second = msg("b");
        estimator.recordPreparedRequest(input(List.of(first, second), List.of(), primary));
        estimator.calibrateFromHistory(List.of(first, second, assistantWithUsage(777)));

        var estimate =
                estimator.estimate(input(List.of(first, second, msg("c")), List.of(), otherModel));

        assertEquals(500, estimate.tokens());
        assertEquals("biased", estimate.method());
    }

    @Test
    void fallsBackWhenExistingMessageContentChanges() {
        CalibratedTokenEstimator estimator = new CalibratedTokenEstimator(biased);
        Msg first = msg("a long message");
        Msg second = msg("b");
        estimator.recordPreparedRequest(input(List.of(first, second), List.of(), primary));
        estimator.calibrateFromHistory(List.of(first, second, assistantWithUsage(777)));

        Msg shortenedFirst = first.withContent(List.of(TextBlock.builder().text("a").build()));
        var estimate =
                estimator.estimate(
                        input(List.of(shortenedFirst, second, msg("c")), List.of(), primary));

        assertEquals(first.getId(), shortenedFirst.getId());
        assertEquals(500, estimate.tokens());
        assertEquals("biased", estimate.method());
    }

    @Test
    void doesNotCommitUsageAfterExistingContentChanges() {
        CalibratedTokenEstimator estimator = new CalibratedTokenEstimator(biased);
        Msg first = msg("a long message");
        estimator.recordPreparedRequest(input(List.of(first), List.of(), primary));

        Msg shortenedFirst = first.withContent(List.of(TextBlock.builder().text("a").build()));
        estimator.calibrateFromHistory(List.of(shortenedFirst, assistantWithUsage(777)));
        var estimate = estimator.estimate(input(List.of(first), List.of(), primary));

        assertEquals(300, estimate.tokens());
        assertEquals("biased", estimate.method());
    }

    @Test
    void usageMessageMustDirectlyFollowTheAnchoredPrefix() {
        CalibratedTokenEstimator estimator = new CalibratedTokenEstimator(biased);
        Msg first = msg("a");
        Msg second = msg("b");
        estimator.recordPreparedRequest(input(List.of(first, second), List.of(), primary));
        // A non-usage message sits between the anchored prefix and the usage message, so the
        // pairing cannot be verified — an interrupted or reordered call must not commit.
        estimator.calibrateFromHistory(
                List.of(first, second, msg("unpaired"), assistantWithUsage(777)));

        var estimate = estimator.estimate(input(List.of(first, second), List.of(), primary));

        assertEquals(400, estimate.tokens());
        assertEquals("biased", estimate.method());
    }

    @Test
    void strictAlternationStillCalibratesEachSession() {
        CalibratedTokenEstimator estimator = new CalibratedTokenEstimator(biased);

        // Round 1: sessions A and B both prepare; neither has an anchor yet.
        Msg a1 = msg("a1");
        Msg a2 = msg("a2");
        estimator.recordPreparedRequest(input(List.of(a1, a2), List.of(), primary));
        Msg b1 = msg("b1");
        estimator.recordPreparedRequest(input(List.of(b1), List.of(), primary));

        // Round 2: strict alternation — each session's usage meets the other session's
        // pending in between, yet must still pair with its own conversation slot.
        Msg aReply = assistantWithUsage(777);
        estimator.calibrateFromHistory(List.of(a1, a2, aReply));
        // B is still un-anchored: A's fresh anchor must not leak into B's different prefix.
        var unAnchoredB = estimator.estimate(input(List.of(b1, msg("b2")), List.of(), primary));
        assertEquals(400, unAnchoredB.tokens());
        assertEquals("biased", unAnchoredB.method());
        estimator.recordPreparedRequest(input(List.of(a1, a2, aReply), List.of(), primary));
        Msg bReply = assistantWithUsage(333);
        estimator.calibrateFromHistory(List.of(b1, bReply));
        estimator.recordPreparedRequest(input(List.of(b1, bReply), List.of(), primary));

        // Both sessions end up calibrated despite never getting two consecutive prepares.
        var sessionA =
                estimator.estimate(input(List.of(a1, a2, aReply, msg("a3")), List.of(), primary));
        // The anchor frame is A's measured request (raw 400), not the latest pending.
        assertEquals(977, sessionA.tokens());
        assertEquals("biased+provider-anchor", sessionA.method());

        var sessionB =
                estimator.estimate(input(List.of(b1, bReply, msg("b2")), List.of(), primary));
        // 333 + (500 - 300): B's anchor frame is its own measured request.
        assertEquals(533, sessionB.tokens());
        assertEquals("biased+provider-anchor", sessionB.method());
    }

    @Test
    void slotSetIsBoundedAndDropsTheOldestTrackedConversation() {
        CalibratedTokenEstimator estimator = new CalibratedTokenEstimator(biased);
        Msg first = msg("first");
        estimator.recordPreparedRequest(input(List.of(first), List.of(), primary));
        estimator.calibrateFromHistory(List.of(first, assistantWithUsage(777)));
        var anchored = estimator.estimate(input(List.of(first, msg("x")), List.of(), primary));
        assertEquals(877, anchored.tokens());

        // More conversations than the bound push the oldest one out of the slot set.
        for (int i = 0; i < 20; i++) {
            estimator.recordPreparedRequest(input(List.of(msg("c" + i)), List.of(), primary));
        }
        var evicted = estimator.estimate(input(List.of(first, msg("x")), List.of(), primary));
        assertEquals(400, evicted.tokens());
        assertEquals("biased", evicted.method());
    }

    @Test
    void evictedConversationReAnchorsOnItsNextUsage() {
        CalibratedTokenEstimator estimator = new CalibratedTokenEstimator(biased);
        Msg m1 = msg("m1");
        estimator.recordPreparedRequest(input(List.of(m1), List.of(), primary));
        // Overflow evicts this conversation's slot before its usage returns.
        for (int i = 0; i < 20; i++) {
            estimator.recordPreparedRequest(input(List.of(msg("c" + i)), List.of(), primary));
        }
        // The late usage finds no slot: no anchor, delegate estimates this round.
        Msg lateReply = assistantWithUsage(777);
        estimator.calibrateFromHistory(List.of(m1, lateReply));
        var unCalibrated = estimator.estimate(input(List.of(m1, msg("x")), List.of(), primary));
        assertEquals(400, unCalibrated.tokens());
        assertEquals("biased", unCalibrated.method());

        // The next prepare re-tracks the conversation, and the usage after that re-anchors it.
        estimator.recordPreparedRequest(input(List.of(m1, lateReply), List.of(), primary));
        estimator.calibrateFromHistory(List.of(m1, lateReply, assistantWithUsage(700)));
        var recovered =
                estimator.estimate(input(List.of(m1, lateReply, msg("x2")), List.of(), primary));
        // Anchor frame raw 400 ([m1, lateReply]), candidate raw 500: 700 + 100.
        assertEquals(800, recovered.tokens());
        assertEquals("biased+provider-anchor", recovered.method());
    }

    @Test
    void syntheticAndSystemMessagesAreInvisibleToPairing() {
        CalibratedTokenEstimator estimator = new CalibratedTokenEstimator(biased);
        Msg first = msg("a");
        Msg second = msg("b");
        Msg system = Msg.builder().role(MsgRole.SYSTEM).textContent("rules").build();
        Msg projection = synthetic("task state");
        estimator.recordPreparedRequest(
                input(List.of(system, projection, first, second), List.of(), primary));
        estimator.calibrateFromHistory(List.of(first, second, assistantWithUsage(777)));

        var estimate =
                estimator.estimate(
                        input(
                                List.of(
                                        Msg.builder()
                                                .role(MsgRole.SYSTEM)
                                                .textContent("changed rules")
                                                .build(),
                                        first,
                                        second,
                                        assistantWithUsage(777),
                                        msg("c")),
                                List.of(),
                                primary));

        // System/projection content changed between the two requests; the conversation prefix
        // is unchanged, so the delta flows through the increment: 777 + (700 - 600).
        assertEquals(877, estimate.tokens());
    }

    @Test
    void secondPrepareCarriesProviderTruthInItsManifest() {
        CalibratedTokenEstimator calibrated = new CalibratedTokenEstimator(biased);
        HarnessContextBuilder builder =
                new HarnessContextBuilder(
                        new ContextPolicy(1_000_000, 1, 1, calibrated, manifests::add), null, null);
        List<Msg> history = new ArrayList<>(List.of(msg("a"), msg("b")));
        prepare(builder, history, "call-1");

        assertEquals(400, manifests.get(0).estimatedInputTokens());
        assertEquals("biased", manifests.get(0).countingMethod());

        history.add(assistantWithUsage(777));
        history.add(msg("c"));
        prepare(builder, history, "call-2");

        assertEquals(977, manifests.get(1).estimatedInputTokens());
        assertEquals("biased+provider-anchor", manifests.get(1).countingMethod());
    }

    @Test
    void withoutProviderUsageNumbersMatchTheDelegateExactly() {
        CalibratedTokenEstimator calibrated = new CalibratedTokenEstimator(biased);
        HarnessContextBuilder builder =
                new HarnessContextBuilder(
                        new ContextPolicy(1_000_000, 1, 1, calibrated, manifests::add), null, null);
        List<Msg> history = new ArrayList<>(List.of(msg("a"), msg("b")));
        prepare(builder, history, "call-1");
        history.add(
                Msg.builder().role(MsgRole.ASSISTANT).textContent("reply without usage").build());
        history.add(msg("c"));
        prepare(builder, history, "call-2");

        assertEquals(600, manifests.get(1).estimatedInputTokens());
        assertEquals("biased", manifests.get(1).countingMethod());
    }

    private void prepare(HarnessContextBuilder builder, List<Msg> history, String callId) {
        state.contextMutable().clear();
        state.contextMutable().addAll(history);
        builder.prepare(agent, rc, input(history, List.of(), primary), callId, Purpose.REASONING)
                .block();
    }

    private ModelCallInput input(List<Msg> messages, List<ToolSchema> tools, Model model) {
        return new ModelCallInput(messages, tools, null, model);
    }

    private static List<ToolSchema> tools(int count) {
        List<ToolSchema> tools = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tools.add(
                    ToolSchema.builder()
                            .name("tool" + i)
                            .description("d")
                            .parameters(Map.of())
                            .build());
        }
        return tools;
    }

    private static Msg msg(String text) {
        return Msg.builder().role(MsgRole.USER).textContent(text).build();
    }

    private static Msg synthetic(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .metadata(Map.of(Msg.METADATA_SYNTHETIC, true))
                .build();
    }

    private static Msg assistantWithUsage(int inputTokens) {
        return Msg.builder()
                .role(MsgRole.ASSISTANT)
                .textContent("reply")
                .usage(new ChatUsage(inputTokens, 1, 0.1))
                .build();
    }
}
