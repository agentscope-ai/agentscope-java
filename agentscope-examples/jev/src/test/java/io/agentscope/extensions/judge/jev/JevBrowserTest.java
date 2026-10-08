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

package io.agentscope.extensions.judge.jev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.examples.jev.JevBrowserExample;
import io.agentscope.extensions.judge.jev.browser.JevBrowserNavigator;
import io.agentscope.extensions.judge.jev.browser.JevBrowserReadTool;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Action;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Outcome;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Permit;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Receipt;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Scope;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Snapshot;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Source;
import io.agentscope.extensions.judge.jev.browser.JevBrowserSession.Verification;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class JevBrowserTest {
    final RuntimeContext ctx = RuntimeContext.builder().userId("u").sessionId("s").build();
    final List<JevExecution.Record> records = new CopyOnWriteArrayList<>();

    JevBrowserNavigator navigator(
            Function<SystemOneRequest, Mono<SystemOneResult>> caller,
            JevExecution.Mode mode,
            Duration budget) {
        return new JevBrowserNavigator(
                caller,
                .8,
                JevBrowserNavigator.Limits.defaults(),
                new JevExecution.Options(mode, budget, "test", (c, r) -> records.add(r)));
    }

    JevBrowserNavigator navigator(Function<SystemOneRequest, Mono<SystemOneResult>> caller) {
        return navigator(caller, JevExecution.Mode.ENFORCE, Duration.ofSeconds(3));
    }

    JevBrowserNavigator.Report run(
            Fake session, Function<SystemOneRequest, Mono<SystemOneResult>> caller) {
        return navigator(caller)
                .navigate(
                        ctx,
                        JevBrowserExample.GOAL,
                        new Source(r -> session, JevBrowserExample::verify))
                .block();
    }

    static class Fake implements JevBrowserSession {
        final Scope scope;
        final AtomicInteger actions = new AtomicInteger(),
                reads = new AtomicInteger(),
                closes = new AtomicInteger();
        boolean found, closed;
        Outcome outcome = Outcome.APPLIED;
        Function<Integer, Mono<Snapshot>> observe;

        Fake(Scope scope) {
            this.scope = scope;
            observe = n -> Mono.just(JevBrowserExample.page(scope, found));
        }

        public Scope scope() {
            return scope;
        }

        public Mono<Snapshot> observe() {
            return Mono.defer(() -> observe.apply(reads.incrementAndGet()));
        }

        public Mono<Receipt> execute(Permit permit, Snapshot expected, Action action) {
            return Mono.fromSupplier(
                    () -> {
                        if (!permit.consume() || closed)
                            return new Receipt(Outcome.REJECTED, "CLOSED_OR_USED");
                        actions.incrementAndGet();
                        if (outcome == Outcome.APPLIED) found = true;
                        return new Receipt(outcome, "SCRIPTED");
                    });
        }

        public Mono<Void> close() {
            if (!closed) {
                closed = true;
                closes.incrementAndGet();
            }
            return Mono.empty();
        }
    }

    Fake fake() {
        return new Fake(new Scope("u", "s", UUID.randomUUID().toString()));
    }

    @Test
    void allChoiceHeadsCanReadAuthorizedCandidatesFromSharedState() {
        var page = JevBrowserExample.page(fake().scope, false);
        var request =
                navigator(JevBrowserExample::syntheticAnswers)
                        .request(JevBrowserExample.GOAL, page, List.of());
        var state = (Map<?, ?>) request.state();
        assertEquals(page.links(), state.get("visible_links"));
        assertEquals(false, state.get("scroll_up_available"));
        assertEquals(false, state.get("scroll_down_available"));
        assertEquals(Set.of("operation", "click_target"), request.questions().keySet());
    }

    @Test
    void actualAgentExecutesOnceAndCloses() {
        var r = JevBrowserExample.runOffline();
        assertEquals(2, r.judgments());
        assertEquals(1, r.browserActions());
        assertEquals(1, r.closedSessions());
    }

    @Test
    void scriptedAnswerCannotConfuseUnverifiedWithVerifiedStatus() {
        var actions = new AtomicInteger();
        var closes = new AtomicInteger();
        var source =
                new Source(
                        req ->
                                new JevBrowserExample.ScriptedSession(
                                        JevBrowserExample.scope(req.context()), actions, closes),
                        (goal, page) ->
                                Mono.just(
                                        new Verification(
                                                JevBrowserSession.digest(goal),
                                                page.version(),
                                                false,
                                                List.of())));
        assertThrows(
                IllegalStateException.class,
                () ->
                        JevBrowserExample.run(
                                source, JevBrowserExample::syntheticAnswers, actions, closes));
        assertEquals(1, actions.get());
        assertEquals(1, closes.get());
    }

    @Test
    void toolOffAndShadowReturnIdenticalPageWithoutDispatch() {
        List<JevBrowserReadTool.Result> results = new ArrayList<>();
        var calls = new AtomicInteger();
        for (var mode : List.of(JevExecution.Mode.OFF, JevExecution.Mode.SHADOW)) {
            var f = fake();
            var tool =
                    new JevBrowserReadTool(
                            navigator(
                                    r -> {
                                        calls.incrementAndGet();
                                        return JevBrowserExample.syntheticAnswers(r);
                                    },
                                    mode,
                                    Duration.ofSeconds(3)));
            var context =
                    RuntimeContext.builder()
                            .userId("u")
                            .sessionId("s")
                            .put(Source.class, new Source(q -> f, JevBrowserExample::verify))
                            .build();
            results.add(tool.read(JevBrowserExample.GOAL, context).block());
            assertEquals(0, f.actions.get());
            assertEquals(1, f.closes.get());
        }
        assertEquals(results.get(0), results.get(1));
        assertEquals(1, calls.get());
    }

    @Test
    void lowConfidenceAndNoneAbstainWithoutDispatch() {
        for (boolean low : List.of(true, false)) {
            var f = fake();
            var r =
                    run(
                            f,
                            q ->
                                    Mono.just(
                                            JevBrowserExample.reply(
                                                    q,
                                                    "CLICK",
                                                    low ? "warranty" : "none",
                                                    low ? .5 : .99)));
            assertEquals("ABSTAIN", r.status());
            assertEquals(0, f.actions.get());
            assertTrue(!r.steps().get(0).proposal().answers().isEmpty());
        }
    }

    @Test
    void unusedTargetCannotCauseClickAndDoneRequiresIndependentEvidence() {
        var f = fake();
        var r = run(f, q -> Mono.just(JevBrowserExample.reply(q, "DONE", "warranty", .99)));
        assertEquals("UNVERIFIED", r.status());
        assertEquals(0, f.actions.get());
    }

    @Test
    void invalidResponseAndCallerFailureNeverDispatch() {
        for (boolean malformed : List.of(true, false)) {
            var f = fake();
            var r =
                    run(
                            f,
                            q ->
                                    malformed
                                            ? Mono.just(
                                                    new SystemOneResult(
                                                            "bad", Map.of(), new Usage(0, 0)))
                                            : Mono.error(
                                                    new IllegalStateException(
                                                            "sensitive response body")));
            assertEquals("CALL_OR_RESPONSE_ERROR", r.status());
            assertEquals(0, f.actions.get());
            assertEquals(1, f.closes.get());
            assertTrue(!records.toString().contains("sensitive"));
        }
    }

    @Test
    void unknownOutcomeAndRejectedActionsAreNeverRetried() {
        for (var outcome : List.of(Outcome.UNKNOWN, Outcome.REJECTED)) {
            var f = fake();
            f.outcome = outcome;
            var r = run(f, JevBrowserExample::syntheticAnswers);
            assertEquals(outcome.name(), r.status());
            assertEquals(1, f.actions.get());
            assertEquals(outcome, r.steps().get(0).receipt().outcome());
        }
    }

    @Test
    void staleActionsAreBoundedAndNeverCountedAsApplied() {
        var f = fake();
        f.outcome = Outcome.STALE;
        var r = run(f, JevBrowserExample::syntheticAnswers);
        assertEquals("STALE_LIMIT", r.status());
        assertEquals(3, f.actions.get());
        assertTrue(r.steps().stream().allMatch(s -> s.receipt().outcome() == Outcome.STALE));
    }

    @Test
    void postDispatchObservationFailureRetainsAppliedReceipt() {
        var f = fake();
        f.observe =
                n ->
                        n == 1
                                ? Mono.just(JevBrowserExample.page(f.scope, false))
                                : Mono.error(new IllegalStateException());
        var r = run(f, JevBrowserExample::syntheticAnswers);
        assertEquals("BROWSER_ERROR", r.status());
        assertEquals(Outcome.APPLIED, r.steps().get(0).receipt().outcome());
        assertEquals(1, f.actions.get());
    }

    @Test
    void totalBudgetIncludesInitialObservationAndClosesSession() {
        var f = fake();
        f.observe = n -> Mono.never();
        var r =
                navigator(
                                JevBrowserExample::syntheticAnswers,
                                JevExecution.Mode.ENFORCE,
                                Duration.ofMillis(50))
                        .navigate(
                                ctx,
                                JevBrowserExample.GOAL,
                                new Source(q -> f, JevBrowserExample::verify))
                        .block();
        assertEquals("TIMEOUT", r.status());
        assertEquals(1, f.closes.get());
        assertEquals(0, f.actions.get());
    }

    @Test
    void cancellationStopsLateJudgmentAndReleasesSession() {
        var f = fake();
        var pending = Sinks.<SystemOneResult>one();
        var requests = new CopyOnWriteArrayList<SystemOneRequest>();
        StepVerifier.create(
                        navigator(
                                        q -> {
                                            requests.add(q);
                                            return pending.asMono();
                                        })
                                .navigate(
                                        ctx,
                                        JevBrowserExample.GOAL,
                                        new Source(q -> f, JevBrowserExample::verify)))
                .thenCancel()
                .verify();
        if (!requests.isEmpty())
            pending.tryEmitValue(JevBrowserExample.syntheticAnswers(requests.get(0)).block());
        assertEquals(0, f.actions.get());
        assertEquals(1, f.closes.get());
        assertTrue(records.stream().anyMatch(r -> r.status() == JevExecution.Status.CANCELLED));
    }

    @Test
    void scopeMismatchCannotEvenObservePage() {
        var f = new Fake(new Scope("other", "s", "tab"));
        var r = run(f, JevBrowserExample::syntheticAnswers);
        assertEquals("BROWSER_ERROR", r.status());
        assertEquals(0, f.reads.get());
        assertEquals(1, f.closes.get());
    }

    @Test
    void mismatchedVerificationOrChangedPageNeverReportsSuccess() {
        for (int kind = 0; kind < 3; kind++) {
            var f = fake();
            f.found = true;
            int k = kind;
            if (k == 2) f.observe = n -> Mono.just(JevBrowserExample.page(f.scope, n < 3));
            var source =
                    new Source(
                            q -> f,
                            (g, p) ->
                                    Mono.just(
                                            new Verification(
                                                    k == 0 ? "wrong" : JevBrowserSession.digest(g),
                                                    k == 1 ? "wrong" : p.version(),
                                                    true,
                                                    List.of("evidence"))));
            var r =
                    navigator(JevBrowserExample::syntheticAnswers)
                            .navigate(ctx, JevBrowserExample.GOAL, source)
                            .block();
            assertEquals("VERIFICATION_STALE", r.status());
        }
    }

    @Test
    void sameContextConcurrentSubscriptionsUseSeparateSessions() {
        var sessions = new CopyOnWriteArrayList<Fake>();
        var source =
                new Source(
                        q -> {
                            var f = fake();
                            sessions.add(f);
                            return f;
                        },
                        JevBrowserExample::verify);
        var nav =
                navigator(
                        q ->
                                JevBrowserExample.syntheticAnswers(q)
                                        .delayElement(Duration.ofMillis(10)));
        var results =
                Mono.zip(
                                nav.navigate(ctx, JevBrowserExample.GOAL, source),
                                nav.navigate(ctx, JevBrowserExample.GOAL, source))
                        .block();
        assertEquals("VERIFIED", results.getT1().status());
        assertEquals("VERIFIED", results.getT2().status());
        assertEquals(2, sessions.size());
        for (var s : sessions) {
            assertEquals(1, s.actions.get());
            assertEquals(1, s.closes.get());
        }
    }

    @Test
    void observerFailureCannotBlockCompletion() {
        var f = fake();
        var nav =
                new JevBrowserNavigator(
                        JevBrowserExample::syntheticAnswers,
                        .8,
                        JevBrowserNavigator.Limits.defaults(),
                        new JevExecution.Options(
                                JevExecution.Mode.ENFORCE,
                                Duration.ofSeconds(3),
                                "v1",
                                (c, r) -> {
                                    throw new IllegalStateException();
                                }));
        assertEquals(
                "VERIFIED",
                nav.navigate(
                                ctx,
                                JevBrowserExample.GOAL,
                                new Source(q -> f, JevBrowserExample::verify))
                        .block()
                        .status());
    }

    @Test
    void noProgressAndWaitHaveIndependentBounds() {
        for (boolean wait : List.of(false, true)) {
            var f = fake();
            f.observe = n -> Mono.just(JevBrowserExample.page(f.scope, false));
            var r =
                    run(
                            f,
                            q ->
                                    Mono.just(
                                            JevBrowserExample.reply(
                                                    q,
                                                    wait ? "WAIT" : "CLICK",
                                                    wait ? "none" : "warranty",
                                                    .99)));
            assertEquals(wait ? "STEP_LIMIT" : "NO_PROGRESS", r.status());
            assertEquals(wait ? 8 : 3, f.actions.get());
        }
    }

    @Test
    void missingSourceAndStateOverflowCannotCallBackend() {
        var tool = new JevBrowserReadTool(navigator(JevBrowserExample::syntheticAnswers));
        assertThrows(IllegalStateException.class, () -> tool.read("goal", ctx).block());
        var nav =
                new JevBrowserNavigator(
                        q -> {
                            throw new AssertionError();
                        },
                        .8,
                        new JevBrowserNavigator.Limits(8, 2, 3, 100),
                        new JevExecution.Options(
                                JevExecution.Mode.ENFORCE,
                                Duration.ofSeconds(3),
                                "v1",
                                (c, r) -> {}));
        var f = fake();
        assertEquals(
                "BROWSER_ERROR",
                nav.navigate(
                                ctx,
                                JevBrowserExample.GOAL,
                                new Source(q -> f, JevBrowserExample::verify))
                        .block()
                        .status());
    }

    @Test
    void permitCanOnlyBeConsumedOnce() {
        var permit = new Permit();
        assertTrue(permit.consume());
        assertTrue(!permit.consume());
    }
}
