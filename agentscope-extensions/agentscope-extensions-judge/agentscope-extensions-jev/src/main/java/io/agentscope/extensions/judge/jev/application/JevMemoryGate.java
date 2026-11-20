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
import io.agentscope.harness.agent.memory.MemoryAdmission;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Mono;

/** Memory admission recommendation bound to an explicit owner and source. Does not persist facts. */
public final class JevMemoryGate {
    public record Fact(String owner, String text, String source) {
        public Fact {
            if (owner == null
                    || owner.isBlank()
                    || text == null
                    || text.isBlank()
                    || source == null
                    || source.isBlank())
                throw new IllegalArgumentException("fact owner/text/source required");
        }
    }

    private final JevJudge judge;

    public JevMemoryGate(JevJudge judge) {
        this.judge = java.util.Objects.requireNonNull(judge);
    }

    /** Harness admission allows sourced corrections; incomplete judgments remain retryable. */
    public MemoryAdmission admission() {
        return input ->
                assessWithPolicy(
                                input.owner(),
                                new Fact(input.owner(), input.candidate(), input.source()),
                                input.existing().stream()
                                        .map(
                                                text ->
                                                        new Fact(
                                                                input.owner(),
                                                                text,
                                                                "existing memory snapshot"))
                                        .toList(),
                                "harness-memory-admission-v1",
                                "Does the source support the candidate as a fact, preference, rule"
                                    + " or decision useful beyond the current one-off task, rather"
                                    + " than a quote, unconfirmed proposal or speculation?",
                                "Does the candidate contradict a supplied existing fact without"
                                    + " explicit source evidence of a correction or an already"
                                    + " effective change for the same subject and scope? A newer"
                                    + " statement alone is not evidence of supersession; quoted"
                                    + " claims and unconfirmed proposals do not establish a"
                                    + " change.")
                        .flatMap(
                                result -> {
                                    if (result.status() == JevJudge.Status.ERROR
                                            || result.status() == JevJudge.Status.INCONCLUSIVE)
                                        return Mono.error(
                                                new IllegalStateException(
                                                        "Memory admission "
                                                                + result.status()
                                                                + ": "
                                                                + result.error()));
                                    Map<String, Double> probabilities = new LinkedHashMap<>();
                                    result.findings()
                                            .forEach(
                                                    (key, value) ->
                                                            probabilities.put(
                                                                    key,
                                                                    value.expectedProbability()));
                                    return Mono.just(
                                            new MemoryAdmission.Decision(
                                                    result.status() == JevJudge.Status.PASS,
                                                    result.definitionVersion(),
                                                    probabilities));
                                });
    }

    /** Legacy v1 policy: a conflict may pass when the source clearly supersedes the old fact. */
    public Mono<JevJudge.Result> assess(String owner, Fact candidate, List<Fact> existing) {
        return assessWithPolicy(
                owner,
                candidate,
                existing,
                "memory-admission-v1",
                "Is the candidate a useful durable fact supported by its source rather than an"
                        + " instruction or temporary speculation?",
                "Does the candidate contradict an existing fact without clear evidence that it"
                        + " supersedes it?");
    }

    private Mono<JevJudge.Result> assessWithPolicy(
            String owner,
            Fact candidate,
            List<Fact> existing,
            String version,
            String durableQuestion,
            String conflictQuestion) {
        if (!candidate.owner().equals(owner)
                || existing.stream().anyMatch(f -> !f.owner().equals(owner)))
            throw new IllegalArgumentException("cross-owner memory input");
        return judge.judge(
                Map.of("candidate", candidate, "existing", List.copyOf(existing)),
                new JevJudge.Definition(
                        version,
                        List.of(
                                new JevJudge.Criterion(
                                        "durable",
                                        new NoulQuestion(durableQuestion, null),
                                        true,
                                        0.2,
                                        0.8),
                                new JevJudge.Criterion(
                                        "conflict",
                                        new NoulQuestion(conflictQuestion, null),
                                        false,
                                        0.2,
                                        0.8))));
    }
}
