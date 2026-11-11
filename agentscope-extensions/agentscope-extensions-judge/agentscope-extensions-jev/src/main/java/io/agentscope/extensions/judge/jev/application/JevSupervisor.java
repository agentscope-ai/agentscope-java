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

package io.agentscope.extensions.judge.jev.application;

import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.NoulQuestion;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/** Read-only supervision: emits advice, never interrupts or finishes a worker. */
public final class JevSupervisor {
    public enum Advice {
        CONTINUE,
        REQUEST_VERIFICATION,
        REVIEW_COMPLETION,
        MANUAL_REVIEW
    }

    public record Report(Advice advice, JevJudge.Result assessment) {}

    private final JevJudge judge;

    public JevSupervisor(JevJudge judge) {
        this.judge = java.util.Objects.requireNonNull(judge);
    }

    public Mono<Report> assess(Object task, Object boundedEvidence, boolean verificationSucceeded) {
        var definition =
                new JevJudge.Definition(
                        "supervision-v1",
                        List.of(
                                new JevJudge.Criterion(
                                        "complete",
                                        new NoulQuestion(
                                                "Does the supplied evidence establish all task"
                                                    + " requirements are implemented? Do not treat"
                                                    + " a worker's unsupported completion claim as"
                                                    + " evidence.",
                                                null),
                                        true,
                                        0.2,
                                        0.8),
                                new JevJudge.Criterion(
                                        "off_track",
                                        new NoulQuestion(
                                                "Is the worker stuck or materially off the"
                                                    + " requested task? Normal investigation alone"
                                                    + " is not being stuck.",
                                                null),
                                        false,
                                        0.2,
                                        0.8)));
        return judge.judge(
                        Map.of(
                                "task",
                                task,
                                "evidence",
                                boundedEvidence,
                                "verificationSucceeded",
                                verificationSucceeded),
                        definition)
                .map(
                        r -> {
                            if (r.status() == JevJudge.Status.ERROR
                                    || r.status() == JevJudge.Status.INCONCLUSIVE)
                                return new Report(Advice.MANUAL_REVIEW, r);
                            if (r.findings().get("off_track").status() != JevJudge.Status.PASS)
                                return new Report(Advice.MANUAL_REVIEW, r);
                            if (r.findings().get("complete").status() != JevJudge.Status.PASS)
                                return new Report(Advice.CONTINUE, r);
                            return new Report(
                                    verificationSucceeded
                                            ? Advice.REVIEW_COMPLETION
                                            : Advice.REQUEST_VERIFICATION,
                                    r);
                        });
    }
}
