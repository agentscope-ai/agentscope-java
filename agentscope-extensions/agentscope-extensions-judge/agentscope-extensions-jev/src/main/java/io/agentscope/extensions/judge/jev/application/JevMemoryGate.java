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

    public Mono<JevJudge.Result> assess(String owner, Fact candidate, List<Fact> existing) {
        if (!candidate.owner().equals(owner)
                || existing.stream().anyMatch(f -> !f.owner().equals(owner)))
            throw new IllegalArgumentException("cross-owner memory input");
        return judge.judge(
                Map.of("candidate", candidate, "existing", List.copyOf(existing)),
                new JevJudge.Definition(
                        "memory-admission-v1",
                        List.of(
                                new JevJudge.Criterion(
                                        "durable",
                                        new NoulQuestion(
                                                "Is the candidate a useful durable fact supported"
                                                    + " by its source rather than an instruction or"
                                                    + " temporary speculation?",
                                                null),
                                        true,
                                        0.2,
                                        0.8),
                                new JevJudge.Criterion(
                                        "conflict",
                                        new NoulQuestion(
                                                "Does the candidate contradict an existing fact"
                                                    + " without clear evidence that it supersedes"
                                                    + " it?",
                                                null),
                                        false,
                                        0.2,
                                        0.8))));
    }
}
