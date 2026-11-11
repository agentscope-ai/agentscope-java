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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.extensions.judge.jev.JevExecution;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import reactor.core.publisher.Mono;

/** Explicit stage-scoped team recommendations; no dispatch or implicit stage inference. */
public final class JevTeamPlanner {
    public record Member(String id, String capabilities) {}

    private final JevCandidateSelector selector;

    public JevTeamPlanner(JevCandidateSelector selector) {
        this.selector = java.util.Objects.requireNonNull(selector);
    }

    public Mono<JevExecution.Decision<JevCandidateSelector.Selection>> recommend(
            RuntimeContext ctx,
            String stage,
            Object task,
            List<Member> members,
            Predicate<Member> eligible) {
        if (stage == null || stage.isBlank())
            throw new IllegalArgumentException("explicit stage required");
        Map<String, Object> candidates = new LinkedHashMap<>();
        for (Member m : members)
            if (eligible.test(m)) {
                if (candidates.putIfAbsent(m.id(), m.capabilities()) != null)
                    throw new IllegalArgumentException("duplicate member");
            }
        return selector.select(
                ctx,
                Map.of("stage", stage, "task", task),
                "Is this member suitable for the explicit current stage and task?",
                candidates);
    }
}
