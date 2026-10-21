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

package io.agentscope.extensions.judge.jev.supervision;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.extensions.judge.jev.JevExecution;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Bounded per-invocation observer loop. Never alters worker events, permissions or execution. */
public final class JevSupervisionMiddleware implements MiddlewareBase {
    public record Schedule(
            Duration minInterval,
            Duration periodicInterval,
            int maxAssessments,
            int maxConsecutiveFailures) {
        public Schedule {
            if (minInterval == null
                    || minInterval.isNegative()
                    || minInterval.isZero()
                    || periodicInterval == null
                    || periodicInterval.compareTo(minInterval) < 0
                    || maxAssessments < 1
                    || maxConsecutiveFailures < 1)
                throw new IllegalArgumentException(
                        "positive bounded supervision schedule required");
            // Validate timer range before a worker stream can start.
            minInterval.toNanos();
            periodicInterval.toNanos();
        }

        public static Schedule defaults() {
            return new Schedule(Duration.ofSeconds(5), Duration.ofSeconds(30), 60, 3);
        }
    }

    public record EvidenceRequest(
            RuntimeContext context,
            JevTaskSupervisor.Scope scope,
            long sequence,
            boolean workerActive) {}

    /** Registered by a trusted host on RuntimeContext, not through model-visible tool arguments. */
    public record EvidenceSource(Function<EvidenceRequest, Mono<SupervisionEvidence>> read) {
        public EvidenceSource {
            Objects.requireNonNull(read);
        }
    }

    public record Observation(
            JevTaskSupervisor.Scope scope,
            int assessment,
            JevExecution.Decision<JevTaskSupervisor.Report> decision,
            boolean superseded,
            int consecutiveFailures,
            JevTaskSupervisor.Advice advice) {}

    private final JevTaskSupervisor supervisor;
    private final Schedule schedule;
    private final BiConsumer<RuntimeContext, Observation> observer;

    public JevSupervisionMiddleware(
            JevTaskSupervisor supervisor,
            Schedule schedule,
            BiConsumer<RuntimeContext, Observation> observer) {
        this.supervisor = Objects.requireNonNull(supervisor);
        this.schedule = Objects.requireNonNull(schedule);
        this.observer = Objects.requireNonNull(observer);
    }

    @Override
    public int order() {
        return 310;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext context,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next) {
        return Flux.defer(
                () -> {
                    if (supervisor.mode() == JevExecution.Mode.OFF) return next.apply(input);
                    var clock = Schedulers.parallel();
                    var capture = new Capture(context, input.msgs(), clock);
                    // publish(Function) subscribes exactly once to the worker, even with two
                    // consumers.
                    return Flux.defer(() -> next.apply(input))
                            .doOnNext(capture::event)
                            .doOnComplete(capture::complete)
                            .publish(
                                    shared -> {
                                        var ticks =
                                                Flux.interval(schedule.minInterval(), clock)
                                                        .takeUntilOther(shared.ignoreElements())
                                                        .onBackpressureLatest()
                                                        .concatWith(Mono.just(-1L));
                                        Flux<AgentEvent> observations =
                                                ticks.concatMap(tick -> capture.assess(), 0)
                                                        .thenMany(Flux.empty());
                                        return Flux.merge(shared, observations);
                                    });
                });
    }

    private final class Capture {
        final RuntimeContext context;
        final JevTaskSupervisor.Scope scope;
        final Scheduler clock;
        final String task;
        final ArrayDeque<String> events = new ArrayDeque<>();
        final long started;
        String output = "";
        boolean truncated;
        boolean complete;
        boolean workerFailed;
        boolean finalAssessed;
        boolean exhausted;
        boolean limitReported;
        long sequence;
        long assessedSequence = -1;
        long lastAssessed = Long.MIN_VALUE;
        int assessments;
        int failures;

        Capture(RuntimeContext context, List<Msg> messages, Scheduler clock) {
            this.context = context;
            this.clock = clock;
            String runId = UUID.randomUUID().toString();
            this.scope =
                    new JevTaskSupervisor.Scope(
                            context == null
                                            || context.getUserId() == null
                                            || context.getUserId().isBlank()
                                            || context.getUserId().length() > 512
                                    ? "unscoped-" + runId
                                    : context.getUserId(),
                            context == null
                                            || context.getSessionId() == null
                                            || context.getSessionId().isBlank()
                                            || context.getSessionId().length() > 512
                                    ? "unscoped-" + runId
                                    : context.getSessionId(),
                            runId);
            StringBuilder original = new StringBuilder();
            int max = supervisor.limits().textChars();
            for (Msg message : messages) {
                truncated |= message.getContent().stream().anyMatch(b -> !(b instanceof TextBlock));
                String text = message.getTextContent();
                if (text == null) continue;
                int remaining = max + 1 - original.length();
                if (remaining > 0) original.append(text, 0, Math.min(text.length(), remaining));
                if (original.length() <= max) original.append('\n');
            }
            task = original.toString();
            started = now();
        }

        long now() {
            return System.nanoTime();
        }

        synchronized void event(AgentEvent event) {
            // Do not retain thinking, metadata, tool arguments or tool-result payloads.
            String type = event.getType().name();
            if (type.startsWith("THINKING_")) return;
            sequence++;
            if (type.equals("EXCEED_MAX_ITERS")
                    || type.equals("REQUEST_STOP")
                    || type.equals("ALL_TOOLS_DENIED")) workerFailed = true;
            if (events.size() == 32) events.removeFirst();
            events.addLast(type);
            if (event instanceof TextBlockDeltaEvent delta) append(delta.getDelta());
            if (type.startsWith("DATA_BLOCK_")) truncated = true;
            if (event instanceof AgentResultEvent result) {
                truncated |=
                        result.getResult().getContent().stream()
                                .anyMatch(b -> !(b instanceof TextBlock));
                output = "";
                append(result.getResult().getTextContent());
            }
        }

        void append(String value) {
            if (value == null) return;
            int max = supervisor.limits().textChars();
            if (value.length() >= max) {
                truncated |= value.length() > max || !output.isEmpty();
                output = value.substring(value.length() - max);
            } else {
                String joined = output + value;
                truncated |= joined.length() > max;
                output = joined.substring(Math.max(0, joined.length() - max));
            }
        }

        synchronized void complete() {
            complete = true;
            sequence++;
        }

        Mono<Void> assess() {
            return Mono.defer(
                    () -> {
                        final JevTaskSupervisor.Snapshot captured;
                        synchronized (this) {
                            if (finalAssessed || exhausted) return Mono.empty();
                            if (assessments >= schedule.maxAssessments()) {
                                if (!limitReported) {
                                    limitReported = true;
                                    exhausted = true;
                                    return Mono.fromRunnable(
                                            () ->
                                                    publish(
                                                            new JevExecution.Decision<>(
                                                                    JevExecution.Status.SKIPPED,
                                                                    null,
                                                                    "ASSESSMENT_LIMIT"),
                                                            false));
                                }
                                exhausted = true;
                                return Mono.empty();
                            }
                            long now = now();
                            if (!complete
                                    && lastAssessed != Long.MIN_VALUE
                                    && (now - lastAssessed < schedule.minInterval().toNanos()
                                            || (sequence == assessedSequence
                                                    && now - lastAssessed
                                                            < schedule.periodicInterval()
                                                                    .toNanos())))
                                return Mono.empty();
                            assessments++;
                            finalAssessed = complete;
                            captured =
                                    new JevTaskSupervisor.Snapshot(
                                            scope,
                                            sequence,
                                            task,
                                            output,
                                            List.copyOf(events),
                                            !complete,
                                            workerFailed,
                                            truncated,
                                            TimeUnit.NANOSECONDS.toMillis(now - started),
                                            SupervisionEvidence.missing());
                            assessedSequence = sequence;
                        }
                        return supervisor
                                .observe(
                                        context,
                                        () -> {
                                            var source =
                                                    context == null
                                                            ? null
                                                            : context.get(EvidenceSource.class);
                                            return (source == null
                                                            ? Mono.just(
                                                                    SupervisionEvidence.missing())
                                                            : Mono.defer(
                                                                    () ->
                                                                            source.read()
                                                                                    .apply(
                                                                                            new EvidenceRequest(
                                                                                                    context,
                                                                                                    scope,
                                                                                                    captured
                                                                                                            .sequence(),
                                                                                                    captured
                                                                                                            .workerActive()))))
                                                    .map(
                                                            evidence ->
                                                                    new JevTaskSupervisor.Snapshot(
                                                                            scope,
                                                                            captured.sequence(),
                                                                            task,
                                                                            captured.outputTail(),
                                                                            captured.recentEvents(),
                                                                            captured.workerActive(),
                                                                            captured.workerFailed(),
                                                                            captured.truncated(),
                                                                            captured
                                                                                    .elapsedMillis(),
                                                                            evidence));
                                        })
                                .doOnNext(
                                        decision -> {
                                            boolean superseded;
                                            synchronized (this) {
                                                lastAssessed = now();
                                                failures =
                                                        decision.status()
                                                                        == JevExecution.Status.ERROR
                                                                ? failures + 1
                                                                : 0;
                                                superseded = sequence != captured.sequence();
                                                if (failures >= schedule.maxConsecutiveFailures())
                                                    exhausted = true;
                                            }
                                            publish(decision, superseded);
                                        })
                                .then()
                                .subscribeOn(Schedulers.boundedElastic());
                    });
        }

        void publish(JevExecution.Decision<JevTaskSupervisor.Report> decision, boolean superseded) {
            var advice =
                    superseded && decision.value() != null
                            ? JevTaskSupervisor.Advice.CONTINUE
                            : decision.value() != null
                                    ? decision.value().selected().advice()
                                    : decision.status() == JevExecution.Status.ERROR
                                                    && failures < schedule.maxConsecutiveFailures()
                                            ? JevTaskSupervisor.Advice.CONTINUE
                                            : JevTaskSupervisor.Advice.MANUAL_REVIEW;
            try {
                observer.accept(
                        context,
                        new Observation(
                                scope, assessments, decision, superseded, failures, advice));
            } catch (RuntimeException ignored) {
                /* Advice persistence cannot stop the worker. */
            }
        }
    }
}
