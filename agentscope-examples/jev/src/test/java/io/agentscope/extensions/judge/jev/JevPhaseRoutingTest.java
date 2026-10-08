/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.judge.jev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ModelCallEndEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.ModelCallInput;
import io.agentscope.core.model.ChatModelBase;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.extensions.judge.jev.application.JevStageRouter;
import io.agentscope.extensions.judge.jev.example.JevModelRouterMiddleware;
import io.agentscope.extensions.judge.jev.routing.JevPhaseRouting;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Candidate;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Effort;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Quota;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Route;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Snapshot;
import io.agentscope.extensions.judge.jev.routing.JevRouteCatalog.Source;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class JevPhaseRoutingTest {
    static final List<Route> ROUTES =
            List.of(
                    new Route("plan", "Architecture and reasoning", List.of("strong", "fast")),
                    new Route(
                            "execute", "Well specified tool execution", List.of("fast", "strong")));

    static Model model(String name) {
        return new ChatModelBase() {
            public String getModelName() {
                return name;
            }

            protected Flux<ChatResponse> doStream(
                    List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
                return Flux.empty();
            }
        };
    }

    static Candidate candidate(
            String id, long window, boolean tools, Set<Effort> effort, Quota quota) {
        return new Candidate(
                id,
                model(id),
                id,
                window,
                2000,
                tools,
                effort,
                quota,
                new JevRouteCatalog.Price("fixture", 1, 2, .1, 1.5));
    }

    static List<Candidate> candidates() {
        return List.of(
                candidate("fast", 8000, true, Set.of(Effort.LOW, Effort.HIGH), Quota.AVAILABLE),
                candidate("strong", 100000, true, Set.of(Effort.HIGH), Quota.AVAILABLE));
    }

    static Snapshot snapshot(List<Candidate> candidates) {
        return new Snapshot(candidates, 100, 100, null, null, 0);
    }

    static RuntimeContext context(Snapshot snapshot) {
        return RuntimeContext.builder()
                .userId("user")
                .sessionId("session")
                .put(Source.class, new Source((ctx, input) -> Mono.just(snapshot)))
                .build();
    }

    static ModelCallInput input(Model original) {
        return new ModelCallInput(
                List.of(new UserMessage("design a transaction boundary")),
                List.of(),
                null,
                original);
    }

    static JevExecution.Options options(JevExecution.Mode mode, Duration budget) {
        return new JevExecution.Options(mode, budget, "test", (ctx, r) -> {});
    }

    static SystemOneResult reply(SystemOneRequest req, String route, double confidence) {
        var answers = new LinkedHashMap<String, Answer>();
        for (var e : req.questions().entrySet()) {
            var criteria = ((ChoiceQuestion) e.getValue()).criteria();
            String selected =
                    e.getKey().equals("route")
                            ? route
                            : criteria.containsKey("high")
                                    ? "high"
                                    : criteria.keySet().iterator().next();
            var p = new LinkedHashMap<String, Double>();
            criteria.keySet().forEach(k -> p.put(k, k.equals(selected) ? 1d : 0d));
            answers.put(e.getKey(), new ChoiceAnswer(selected, p, confidence));
        }
        return new SystemOneResult("fixture-judge", answers, new Usage(10, 4));
    }

    static JevPhaseRouting router(
            Function<SystemOneRequest, Mono<SystemOneResult>> caller,
            JevExecution.Mode mode,
            List<JevPhaseRouting.CallRecord> records) {
        return new JevPhaseRouting(
                caller,
                ROUTES,
                Set.of("fast", "strong"),
                .8,
                true,
                options(mode, Duration.ofSeconds(1)),
                (ctx, r) -> records.add(r));
    }

    static void open(JevPhaseRouting router, RuntimeContext ctx) {
        router.onAgent(ctx, new AgentInput(List.of(new UserMessage("task"))), i -> Flux.empty())
                .blockLast();
    }

    static ModelCallInput dispatch(
            JevPhaseRouting router, RuntimeContext ctx, ModelCallInput input) {
        var actual = new AtomicReference<ModelCallInput>();
        router.onModelCall(
                        ctx,
                        input,
                        i -> {
                            actual.set(i);
                            return Flux.just(
                                    new ModelCallEndEvent("reply", new ChatUsage(100, 20, 20, 0)));
                        })
                .blockLast();
        return actual.get();
    }

    @Test
    void observationsPrecedeTerminalSignalsAndCancellationRecordsOnce() {
        var ctx = context(snapshot(candidates()));
        var records = new ArrayList<JevPhaseRouting.CallRecord>();
        var router =
                router(r -> Mono.just(reply(r, "plan", .95)), JevExecution.Mode.ENFORCE, records);
        open(router, ctx);
        var usage = new ChatUsage(100, 20, 20, 0);
        var original = input(model("original"));
        router.onModelCall(ctx, original, i -> Flux.just(new ModelCallEndEvent("reply", usage)))
                .doOnComplete(
                        () -> {
                            assertEquals(1, records.size());
                            assertEquals("COMPLETED", records.get(0).status());
                            assertSame(usage, records.get(0).usage());
                        })
                .blockLast();
        var failure = new IllegalStateException("dispatch failed");
        StepVerifier.create(
                        router.onModelCall(ctx, original, i -> Flux.error(failure))
                                .doOnError(
                                        error -> {
                                            assertEquals(2, records.size());
                                            assertEquals("ERROR", records.get(1).status());
                                        }))
                .expectErrorMatches(error -> error == failure)
                .verify();
        var subscription = router.onModelCall(ctx, original, i -> Flux.never()).subscribe();
        subscription.dispose();
        subscription.dispose();
        assertEquals(3, records.size());
        assertEquals("CANCELLED", records.get(2).status());
    }

    @Test
    void actualAgentKeepsEachPhaseStickyAndExecutesEachToolExactlyOnce() {
        var result = io.agentscope.examples.jev.JevPhaseRoutingExample.runOffline();
        assertEquals(1, result.judgeRequests());
        assertEquals(3, result.toolCalls());
        assertEquals(0, result.originalCalls());
    }

    @Test
    void choosesRouteAndEffortOnceAndRecordsDispatchedUsage() {
        var candidates = candidates();
        var ctx = context(snapshot(candidates));
        var records = new ArrayList<JevPhaseRouting.CallRecord>();
        var count = new AtomicInteger();
        var request = new AtomicReference<SystemOneRequest>();
        var router =
                router(
                        r -> {
                            count.incrementAndGet();
                            request.set(r);
                            return Mono.just(reply(r, "plan", .95));
                        },
                        JevExecution.Mode.ENFORCE,
                        records);
        open(router, ctx);
        var original = input(model("original"));
        assertSame(candidates.get(1).model(), dispatch(router, ctx, original).model());
        assertSame(candidates.get(1).model(), dispatch(router, ctx, original).model());
        assertEquals(1, count.get());
        assertEquals(Set.of("route", "effort"), request.get().questions().keySet());
        assertEquals("strong", records.get(0).dispatchedModel());
        assertEquals("high", records.get(0).requestedEffort());
        assertEquals(100, records.get(0).usage().getInputTokens());
        assertEquals(.000122, records.get(0).estimatedUsd(), .00000001);
        assertEquals(2, records.size());
        assertEquals(10, router.report(ctx).judgeUsage().inputTokens());
    }

    @Test
    void filtersCapabilityQuotaAndUnknownBeforeJudging() {
        var pool =
                List.of(
                        candidate("fast", 200, false, Set.of(Effort.LOW), Quota.EXHAUSTED),
                        candidate("strong", 10000, true, Set.of(Effort.HIGH), Quota.LOW));
        var ctx = context(new Snapshot(pool, 1000, 100, Effort.HIGH, null, 2));
        var router =
                router(
                        r -> {
                            String state = r.state().toString();
                            assertFalse(state.contains("context_window=200,"));
                            return Mono.just(reply(r, "plan", .9));
                        },
                        JevExecution.Mode.ENFORCE,
                        new ArrayList<>());
        open(router, ctx);
        assertSame(pool.get(1).model(), dispatch(router, ctx, input(model("original"))).model());
        assertEquals("QUOTA_EXHAUSTED", router.report(ctx).skipped().get(0).reason());
        var unknown =
                new Candidate(
                        "unknown",
                        model("u"),
                        "unknown",
                        null,
                        null,
                        false,
                        Set.of(),
                        Quota.UNKNOWN,
                        null);
        assertEquals(
                "CONTEXT_UNKNOWN",
                JevRouteCatalog.excluded(unknown, snapshot(pool), input(model("o"))));
        var small = candidate("s", 150, true, Set.of(Effort.LOW), Quota.AVAILABLE);
        assertEquals(
                "CONTEXT_LIMIT",
                JevRouteCatalog.excluded(small, snapshot(pool), input(model("o"))));
    }

    @Test
    void noCandidatesDoesNotAskOrChangeOriginal() {
        var ctx = context(new Snapshot(candidates(), 200000, 100, null, null, 0));
        var router =
                router(
                        r -> Mono.error(new AssertionError("must not ask")),
                        JevExecution.Mode.ENFORCE,
                        new ArrayList<>());
        open(router, ctx);
        var original = input(model("original"));
        assertSame(original, dispatch(router, ctx, original));
        assertNull(router.report(ctx).model());
        assertEquals(2, router.report(ctx).skipped().size());
    }

    @Test
    void explicitPhaseAndPinnedModelSkipJudge() {
        var pool = candidates();
        var ctx = context(snapshot(pool));
        ctx.put("jev.stage", "execute");
        var router =
                router(
                        r -> Mono.error(new AssertionError("explicit route")),
                        JevExecution.Mode.ENFORCE,
                        new ArrayList<>());
        open(router, ctx);
        assertSame(pool.get(0).model(), dispatch(router, ctx, input(model("original"))).model());
        var pinned = context(new Snapshot(pool, 100, 100, null, "strong", 0));
        pinned.put("jev.stage", "execute");
        open(router, pinned);
        assertSame(pool.get(1).model(), dispatch(router, pinned, input(model("original"))).model());
        assertEquals("pinned", router.report(pinned).route());
    }

    @Test
    void shadowPreservesInputAndOffDoesNotReadSource() {
        var ctx = context(snapshot(candidates()));
        var original = input(model("original"));
        var records = new ArrayList<JevPhaseRouting.CallRecord>();
        var shadow =
                router(r -> Mono.just(reply(r, "execute", .95)), JevExecution.Mode.SHADOW, records);
        open(shadow, ctx);
        assertSame(original, dispatch(shadow, ctx, original));
        assertEquals("fast", shadow.report(ctx).model());
        assertEquals("original", records.get(0).dispatchedModel());
        assertNull(records.get(0).estimatedUsd());
        var off =
                router(
                        r -> Mono.error(new AssertionError()),
                        JevExecution.Mode.OFF,
                        new ArrayList<>());
        var empty = RuntimeContext.empty();
        open(off, empty);
        assertSame(original, dispatch(off, empty, original));
    }

    @Test
    void noneLowConfidenceBackendFailureAndMalformedReplyFallback() {
        for (int scenario = 0; scenario < 4; scenario++) {
            int test = scenario;
            var ctx = context(snapshot(candidates()));
            var original = input(model("original"));
            var router =
                    router(
                            r ->
                                    switch (test) {
                                        case 0 -> Mono.just(reply(r, "none", .99));
                                        case 1 -> Mono.just(reply(r, "plan", .2));
                                        case 2 ->
                                                Mono.error(
                                                        new IllegalStateException("unavailable"));
                                        default ->
                                                Mono.just(
                                                        new SystemOneResult(
                                                                "bad", Map.of(), new Usage(5, 2)));
                                    },
                            JevExecution.Mode.ENFORCE,
                            new ArrayList<>());
            open(router, ctx);
            assertSame(original, dispatch(router, ctx, original));
            assertNull(router.report(ctx).model());
            if (test == 1) {
                assertEquals(.2, router.report(ctx).confidence());
                assertEquals(1d, router.report(ctx).probabilities().get("plan"));
            }
        }
    }

    @Test
    void capabilityInvalidationSticksWithoutSecondJudgment() {
        var pool = candidates();
        var state = new AtomicReference<>(snapshot(pool));
        var count = new AtomicInteger();
        var ctx =
                RuntimeContext.builder()
                        .put(Source.class, new Source((c, i) -> Mono.just(state.get())))
                        .build();
        var records = new ArrayList<JevPhaseRouting.CallRecord>();
        var router =
                router(
                        r -> {
                            count.incrementAndGet();
                            return Mono.just(reply(r, "execute", .9));
                        },
                        JevExecution.Mode.ENFORCE,
                        records);
        open(router, ctx);
        var original = input(model("original"));
        assertSame(pool.get(0).model(), dispatch(router, ctx, original).model());
        state.set(new Snapshot(pool, 10000, 100, null, null, 0));
        assertSame(original, dispatch(router, ctx, original));
        state.set(snapshot(pool));
        assertSame(original, dispatch(router, ctx, original));
        assertEquals(1, count.get());
        assertEquals("CANDIDATE_INVALIDATED", records.get(2).reason());
    }

    @Test
    void changedModelBindingCannotSneakIntoSelectedId() {
        var pool = candidates();
        var state = new AtomicReference<>(snapshot(pool));
        var ctx =
                RuntimeContext.builder()
                        .put(Source.class, new Source((c, i) -> Mono.just(state.get())))
                        .build();
        var router =
                router(
                        r -> {
                            state.set(snapshot(candidates()));
                            return Mono.just(reply(r, "plan", .9));
                        },
                        JevExecution.Mode.ENFORCE,
                        new ArrayList<>());
        open(router, ctx);
        var original = input(model("original"));
        assertSame(original, dispatch(router, ctx, original));
    }

    @Test
    void clientEffortIsPreservedAndPinnedOptionsNeverRouted() {
        var pool = candidates();
        var ctx = context(snapshot(pool));
        var router =
                router(
                        r -> {
                            assertFalse(r.questions().containsKey("effort"));
                            return Mono.just(reply(r, "plan", .9));
                        },
                        JevExecution.Mode.ENFORCE,
                        new ArrayList<>());
        open(router, ctx);
        var opts = GenerateOptions.builder().reasoningEffort("high").temperature(.1).build();
        var original =
                new ModelCallInput(input(model("o")).messages(), List.of(), opts, model("o"));
        assertSame(opts, dispatch(router, ctx, original).options());
        var pinned =
                new ModelCallInput(
                        original.messages(),
                        List.of(),
                        GenerateOptions.builder().modelName("specific-model").build(),
                        original.model());
        open(router, ctx);
        assertSame(pinned, dispatch(router, ctx, pinned));
    }

    @Test
    void timeoutAndCancellationDoNotReplayOrDispatchAfterCancel() {
        var count = new AtomicInteger();
        var ctx = context(snapshot(candidates()));
        var original = input(model("original"));
        var router =
                new JevPhaseRouting(
                        r -> Mono.never(),
                        ROUTES,
                        Set.of("fast", "strong"),
                        .8,
                        true,
                        options(JevExecution.Mode.ENFORCE, Duration.ofMillis(20)),
                        (c, r) -> {});
        open(router, ctx);
        assertSame(original, dispatch(router, ctx, original));
        var cancelled = router(r -> Mono.never(), JevExecution.Mode.ENFORCE, new ArrayList<>());
        open(cancelled, ctx);
        StepVerifier.create(
                        cancelled.onModelCall(
                                ctx,
                                original,
                                i -> {
                                    count.incrementAndGet();
                                    return Flux.empty();
                                }))
                .thenAwait(Duration.ofMillis(5))
                .thenCancel()
                .verify();
        assertEquals(0, count.get());
        StepVerifier.create(
                        cancelled.onModelCall(
                                ctx,
                                original,
                                i -> {
                                    count.incrementAndGet();
                                    return Flux.empty();
                                }))
                .expectError(CancellationException.class)
                .verify();
    }

    @Test
    void concurrentSubscriptionsUseTheirOwnRoutingStateEvenWithSharedContext() {
        var pool = candidates();
        var ctx = context(snapshot(pool));
        var routes = new CopyOnWriteArrayList<String>();
        var router =
                router(
                        r ->
                                Mono.delay(
                                                Duration.ofMillis(
                                                        r.state()
                                                                        .toString()
                                                                        .contains("execute-task")
                                                                ? 20
                                                                : 5))
                                        .map(
                                                t ->
                                                        reply(
                                                                r,
                                                                r.state()
                                                                                .toString()
                                                                                .contains(
                                                                                        "execute-task")
                                                                        ? "execute"
                                                                        : "plan",
                                                                .9)),
                        JevExecution.Mode.ENFORCE,
                        new CopyOnWriteArrayList<>());
        Function<String, Flux<AgentEvent>> run =
                task -> {
                    var i =
                            new ModelCallInput(
                                    List.of(new UserMessage(task)),
                                    List.of(),
                                    null,
                                    model("original"));
                    return router.onAgent(
                            ctx,
                            new AgentInput(i.messages()),
                            a ->
                                    router.onModelCall(
                                            ctx,
                                            i,
                                            chosen -> {
                                                routes.add(
                                                        task + ":" + chosen.model().getModelName());
                                                return Flux.empty();
                                            }));
                };
        Flux.merge(run.apply("execute-task"), run.apply("plan-task")).blockLast();
        assertTrue(routes.containsAll(List.of("execute-task:fast", "plan-task:strong")));
    }

    @Test
    void stageAdapterUsesConfiguredBoundariesAndFreshScope() {
        var pool = candidates();
        var ctx = context(snapshot(pool));
        var policy =
                router(
                        r -> Mono.error(new AssertionError("explicit")),
                        JevExecution.Mode.ENFORCE,
                        new ArrayList<>());
        var wrapper =
                JevModelRouterMiddleware.builder(r -> Mono.error(new AssertionError()))
                        .phaseRouting(policy)
                        .build();
        var stages = new JevStageRouter(wrapper);
        var plan = stages.begin(ctx, "plan", "architecture").block();
        var execute = stages.begin(ctx, "execute", "implement").block();
        var models = new ArrayList<Model>();
        for (var phase : List.of(plan, execute))
            stages.onModelCall(
                            phase,
                            input(model("original")),
                            i -> {
                                models.add(i.model());
                                return Flux.empty();
                            })
                    .blockLast();
        assertEquals(List.of(pool.get(1).model(), pool.get(0).model()), models);
        assertNull(ctx.get("jev.stage"));
    }

    @Test
    void dispatchFailureIsNotRetriedAndObserverFailureIsIgnored() {
        var count = new AtomicInteger();
        var ctx = context(snapshot(candidates()));
        var router =
                new JevPhaseRouting(
                        r -> Mono.just(reply(r, "plan", .9)),
                        ROUTES,
                        Set.of("fast", "strong"),
                        .8,
                        true,
                        options(JevExecution.Mode.ENFORCE, Duration.ofSeconds(1)),
                        (c, r) -> {
                            throw new IllegalStateException("observer");
                        });
        open(router, ctx);
        assertThrows(
                IllegalStateException.class,
                () ->
                        router.onModelCall(
                                        ctx,
                                        input(model("original")),
                                        i -> {
                                            count.incrementAndGet();
                                            return Flux.error(
                                                    new IllegalStateException("model failed"));
                                        })
                                .blockLast());
        assertEquals(1, count.get());
    }

    @Test
    void aNewEffortFloorInvalidatesTheOldEffortWithoutRejudging() {
        var pool = candidates();
        var current = new AtomicReference<>(snapshot(pool));
        var count = new AtomicInteger();
        var ctx =
                RuntimeContext.builder()
                        .put(Source.class, new Source((c, i) -> Mono.just(current.get())))
                        .build();
        var router =
                router(
                        req -> {
                            count.incrementAndGet();
                            var value = reply(req, "execute", .9);
                            var answers = new LinkedHashMap<>(value.answers());
                            answers.put(
                                    "effort",
                                    new ChoiceAnswer("low", Map.of("low", 1d, "high", 0d), .9));
                            return Mono.just(
                                    new SystemOneResult(value.model(), answers, value.usage()));
                        },
                        JevExecution.Mode.ENFORCE,
                        new ArrayList<>());
        open(router, ctx);
        var original = input(model("original"));
        assertEquals("low", dispatch(router, ctx, original).options().getReasoningEffort());
        current.set(new Snapshot(pool, 100, 100, Effort.HIGH, null, 0));
        assertSame(original, dispatch(router, ctx, original));
        assertEquals(1, count.get());
    }

    @Test
    void sourceTimeoutAndUnknownPhaseHaveNoJudgmentRequest() {
        var count = new AtomicInteger();
        var router =
                new JevPhaseRouting(
                        r -> {
                            count.incrementAndGet();
                            return Mono.just(reply(r, "plan", .9));
                        },
                        ROUTES,
                        Set.of("fast", "strong"),
                        .8,
                        true,
                        options(JevExecution.Mode.ENFORCE, Duration.ofMillis(20)),
                        (c, r) -> {});
        var ctx =
                RuntimeContext.builder()
                        .put(Source.class, new Source((c, i) -> Mono.never()))
                        .build();
        var original = input(model("original"));
        open(router, ctx);
        assertSame(original, dispatch(router, ctx, original));
        var unknown = context(snapshot(candidates()));
        unknown.put("jev.stage", "unconfigured");
        open(router, unknown);
        assertSame(original, dispatch(router, unknown, original));
        assertEquals(0, count.get());
    }

    @Test
    void priceAndLimitsRemainUnknownWithoutEvidence() {
        var p = new JevRouteCatalog.Price("v1", 1, 2, .1, 1.5);
        assertNull(p.estimateUsd(null));
        assertNull(
                new JevRouteCatalog.Price("overflow", Double.MAX_VALUE, 1, 1, 1)
                        .estimateUsd(new ChatUsage(100, 1, 0)));
        assertNull(p.estimateUsd(new ChatUsage(10, 1, 11, 0)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new JevRouteCatalog.Price("v1", Double.NaN, 1, 1, 1));
        var c = candidate("fast", 50000, true, Set.of(Effort.LOW), Quota.AVAILABLE);
        var input =
                new ModelCallInput(
                        List.of(),
                        List.of(),
                        GenerateOptions.builder().maxCompletionTokens(4000).build(),
                        model("o"));
        assertEquals("OUTPUT_LIMIT", JevRouteCatalog.excluded(c, snapshot(List.of(c)), input));
    }
}
