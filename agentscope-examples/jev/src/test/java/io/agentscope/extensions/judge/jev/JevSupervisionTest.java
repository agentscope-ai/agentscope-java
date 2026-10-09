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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.examples.jev.JevSupervisionExample;
import io.agentscope.extensions.judge.jev.supervision.JevTaskSupervisor;
import io.agentscope.extensions.judge.jev.supervision.SupervisionEvidence;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class JevSupervisionTest {
    static final JevTaskSupervisor.Scope SCOPE = new JevTaskSupervisor.Scope("u", "s", "run");

    static JevTaskSupervisor supervisor(Function<SystemOneRequest, Mono<SystemOneResult>> caller) {
        return new JevTaskSupervisor(
                caller,
                new JevTaskSupervisor.Thresholds(.2, .8),
                JevTaskSupervisor.Limits.defaults(),
                options(JevExecution.Mode.SHADOW));
    }

    static JevExecution.Options options(JevExecution.Mode mode) {
        return new JevExecution.Options(mode, Duration.ofSeconds(1), "test-v1", (c, r) -> {});
    }

    static SupervisionEvidence evidence(String revision, SupervisionEvidence.Verification v) {
        return new SupervisionEvidence(
                revision,
                "M source",
                "Fixed the requested issue.",
                List.of("source"),
                "Keep changes scoped.",
                false,
                v);
    }

    static SupervisionEvidence.Verification verification(
            String user, String session, String run, String revision, boolean passed) {
        return new SupervisionEvidence.Verification(
                user, session, run, revision, "host-test", passed, "tests");
    }

    static JevTaskSupervisor.Snapshot snapshot(boolean active, SupervisionEvidence e) {
        return new JevTaskSupervisor.Snapshot(
                SCOPE, 1, "Fix issue and test it", "Done", List.of(), active, false, false, 100, e);
    }

    static SupervisionEvidence valid() {
        return evidence("r1", verification("u", "s", "run", "r1", true));
    }

    static Function<SystemOneRequest, Mono<SystemOneResult>> answers(Map<String, Double> override) {
        return request ->
                JevSupervisionExample.syntheticAnswers(request)
                        .map(
                                result -> {
                                    Map<String, Answer> a = new LinkedHashMap<>(result.answers());
                                    override.forEach((k, v) -> a.put(k, new NoulAnswer(v)));
                                    return new SystemOneResult(result.model(), a, result.usage());
                                });
    }

    static JevExecution.Decision<JevTaskSupervisor.Report> assess(
            JevTaskSupervisor supervisor, JevTaskSupervisor.Snapshot s) {
        return supervisor.observe(null, () -> Mono.just(s)).block();
    }

    @Test
    void groupsChecksAndRoutesDocumentationExplicitly() {
        assertEquals(10, JevTaskSupervisor.checks(false).size());
        assertEquals(11, JevTaskSupervisor.checks(true).size());
        assertEquals(
                11,
                JevTaskSupervisor.checks(true).stream()
                        .map(JevTaskSupervisor.Check::key)
                        .distinct()
                        .count());
    }

    @Test
    void actualVerificationAndIdleWorkerAreBothRequiredForCompletionReview() {
        var supervisor = supervisor(answers(Map.of()));
        assertEquals(
                JevTaskSupervisor.Advice.REVIEW_COMPLETION,
                assess(supervisor, snapshot(false, valid())).value().selected().advice());
        assertEquals(
                JevTaskSupervisor.Advice.CONTINUE,
                assess(supervisor, snapshot(true, valid())).value().selected().advice());
    }

    @Test
    void missingFailedStaleAndOtherScopeVerificationCannotAuthorizeCompletion() {
        var s = supervisor(answers(Map.of()));
        var invalid = new ArrayList<SupervisionEvidence>();
        invalid.add(evidence("r1", null));
        invalid.add(evidence("", verification("u", "s", "run", "", true)));
        invalid.add(evidence("r1", verification("u", "s", "run", "r1", false)));
        invalid.add(evidence("r2", verification("u", "s", "run", "r1", true)));
        invalid.add(evidence("r1", verification("other", "s", "run", "r1", true)));
        invalid.add(evidence("r1", verification("u", "other", "run", "r1", true)));
        invalid.add(evidence("r1", verification("u", "s", "other", "r1", true)));
        for (var evidence : invalid) {
            var report = assess(s, snapshot(false, evidence)).value();
            assertFalse(report.verifiedRevision());
            assertEquals(JevTaskSupervisor.Advice.REQUEST_VERIFICATION, report.selected().advice());
        }
    }

    @Test
    void preservesAllProposalsAndPrioritizesHumanThenDirection() {
        var report =
                assess(
                                supervisor(
                                        answers(
                                                Map.of(
                                                        "core.human-escalation__needs_human",
                                                        .99,
                                                        "core.worker-health__worker_stuck",
                                                        .99,
                                                        "repository.instructions__agents_md_drift",
                                                        .99))),
                                snapshot(true, valid()))
                        .value();
        assertEquals("core.human-escalation", report.selected().responsibility());
        assertTrue(
                report.proposals().stream()
                        .anyMatch(p -> p.advice() == JevTaskSupervisor.Advice.REVIEW_DIRECTION));
        var tie =
                assess(
                                supervisor(
                                        answers(
                                                Map.of(
                                                        "core.worker-health__worker_stuck",
                                                        .99,
                                                        "repository.instructions__agents_md_drift",
                                                        .99))),
                                snapshot(true, valid()))
                        .value();
        assertEquals("repository.instructions", tie.selected().responsibility());
    }

    @Test
    void documentationAndUncertaintyPreventCompletionAdvice() {
        var e = valid();
        e =
                new SupervisionEvidence(
                        e.revision(),
                        e.repositoryStatus(),
                        e.diff(),
                        e.changedFiles(),
                        e.instructions(),
                        true,
                        e.verification());
        assertEquals(
                JevTaskSupervisor.Advice.REVIEW_DOCUMENTATION,
                assess(
                                supervisor(
                                        answers(
                                                Map.of(
                                                        "quality.documentation__documentation_sufficient",
                                                        .01))),
                                snapshot(false, e))
                        .value()
                        .selected()
                        .advice());
        var uncertain =
                assess(
                        supervisor(answers(Map.of("core.completion__ready_to_finish", .5))),
                        snapshot(false, valid()));
        assertEquals(JevExecution.Status.INCONCLUSIVE, uncertain.status());
        assertEquals(JevTaskSupervisor.Advice.MANUAL_REVIEW, uncertain.value().selected().advice());
    }

    @Test
    void rejectsInvalidResponseWithoutInventingProbabilities() {
        for (double probability : List.of(Double.NaN, -1d, 1.1d))
            assertEquals(
                    JevExecution.Status.ERROR,
                    assess(
                                    supervisor(
                                            answers(
                                                    Map.of(
                                                            "core.completion__ready_to_finish",
                                                            probability))),
                                    snapshot(false, valid()))
                            .status());
        assertEquals(
                JevExecution.Status.ERROR,
                assess(
                                supervisor(
                                        r ->
                                                Mono.just(
                                                        new SystemOneResult(
                                                                "test",
                                                                Map.of(),
                                                                new Usage(0, 0)))),
                                snapshot(false, valid()))
                        .status());
    }

    @Test
    void offDoesNotAcquireEvidenceAndEnforcementIsRejected() {
        var off =
                new JevTaskSupervisor(
                        answers(Map.of()),
                        new JevTaskSupervisor.Thresholds(.2, .8),
                        JevTaskSupervisor.Limits.defaults(),
                        options(JevExecution.Mode.OFF));
        assertEquals(
                JevExecution.Status.SKIPPED,
                off.observe(
                                null,
                                () -> {
                                    throw new AssertionError();
                                })
                        .block()
                        .status());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new JevTaskSupervisor(
                                answers(Map.of()),
                                new JevTaskSupervisor.Thresholds(.2, .8),
                                JevTaskSupervisor.Limits.defaults(),
                                options(JevExecution.Mode.ENFORCE)));
    }

    @Test
    void evidenceTimeoutAndCancellationShareExecutionBudget() {
        StepVerifier.withVirtualTime(() -> supervisor(answers(Map.of())).observe(null, Mono::never))
                .thenAwait(Duration.ofSeconds(2))
                .assertNext(d -> assertEquals("TIMEOUT", d.reason()))
                .verifyComplete();
        List<JevExecution.Record> records = new ArrayList<>();
        var s =
                new JevTaskSupervisor(
                        answers(Map.of()),
                        new JevTaskSupervisor.Thresholds(.2, .8),
                        JevTaskSupervisor.Limits.defaults(),
                        new JevExecution.Options(
                                JevExecution.Mode.SHADOW,
                                Duration.ofSeconds(1),
                                "v1",
                                (c, r) -> records.add(r)));
        StepVerifier.create(s.observe(null, Mono::never)).thenCancel().verify();
        assertEquals(JevExecution.Status.CANCELLED, records.get(0).status());
    }

    @Test
    void truncationIsExplicitAndCannotBecomeCompletionEvidence() {
        var e = valid();
        e =
                new SupervisionEvidence(
                        e.revision(),
                        e.repositoryStatus(),
                        "x".repeat(30001),
                        e.changedFiles(),
                        e.instructions(),
                        false,
                        e.verification());
        var d = assess(supervisor(answers(Map.of())), snapshot(false, e));
        assertEquals(JevExecution.Status.INCONCLUSIVE, d.status());
        assertEquals("EVIDENCE_TRUNCATED", d.reason());
        AtomicInteger calls = new AtomicInteger();
        var s =
                supervisor(
                        r -> {
                            calls.incrementAndGet();
                            return answers(Map.of()).apply(r);
                        });
        var oversized =
                new JevTaskSupervisor.Snapshot(
                        SCOPE,
                        1,
                        "x".repeat(12001),
                        "",
                        List.of(),
                        false,
                        false,
                        false,
                        0,
                        valid());
        assertEquals("CONSTRAINT_LIMIT_OR_MISSING_TASK", assess(s, oversized).reason());
        assertEquals(0, calls.get());
    }

    @Test
    void realAgentRunsToolExactlyOnceAndOnlyReceivesAnAdvice() {
        var run = JevSupervisionExample.runOffline();
        assertEquals(1, run.executedTools());
        assertEquals("Order 42 shipped yesterday.", run.answer());
        assertEquals(JevTaskSupervisor.Advice.REVIEW_COMPLETION, run.observation().advice());
    }

    @Test
    void informationalProgressAndInactiveWorkerWarningsDoNotChangeCompletionPolicy() {
        var s =
                supervisor(
                        answers(
                                Map.of(
                                        "core.worker-health__meaningful_progress",
                                        .5,
                                        "core.worker-health__worker_stuck",
                                        .99,
                                        "repository.instructions__agents_md_drift",
                                        .99,
                                        "core.verification__needs_verification",
                                        .5)));
        var finished = assess(s, snapshot(false, valid())).value();
        assertEquals(JevTaskSupervisor.Advice.REVIEW_COMPLETION, finished.selected().advice());
        assertTrue(
                finished.proposals().stream()
                        .noneMatch(p -> p.advice() == JevTaskSupervisor.Advice.REVIEW_DIRECTION));
        var active = assess(s, snapshot(true, valid())).value();
        assertEquals(JevTaskSupervisor.Advice.REVIEW_DIRECTION, active.selected().advice());
    }
}
