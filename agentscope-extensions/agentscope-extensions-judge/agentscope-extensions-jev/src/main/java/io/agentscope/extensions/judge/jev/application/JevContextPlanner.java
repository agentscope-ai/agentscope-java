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
import reactor.core.publisher.Mono;

/** Proposes reversible pair-level offloading. Does not mutate AgentState or long-term memory. */
public final class JevContextPlanner {
    public record Exchange(String id, Object call, Object result, boolean pinned) {
        public Exchange {
            if (id == null || id.isBlank() || call == null || result == null)
                throw new IllegalArgumentException("complete exchange required");
        }
    }

    public record Plan(
            List<Exchange> retained,
            Map<String, Exchange> archive,
            List<Exchange> original,
            String reason) {
        public Plan {
            retained = List.copyOf(retained);
            archive = Map.copyOf(archive);
            original = List.copyOf(original);
        }

        public List<Exchange> restore() {
            return original;
        }
    }

    private final JevCandidateSelector selector;

    public JevContextPlanner(JevCandidateSelector selector) {
        this.selector = java.util.Objects.requireNonNull(selector);
    }

    public Mono<Plan> plan(RuntimeContext ctx, Object task, List<Exchange> exchanges) {
        List<Exchange> original = List.copyOf(exchanges);
        Map<String, Object> candidates = new LinkedHashMap<>();
        if (original.stream().map(Exchange::id).distinct().count() != original.size())
            throw new IllegalArgumentException("duplicate exchange id");
        original.stream().filter(e -> !e.pinned()).forEach(e -> candidates.put(e.id(), e));
        return selector.select(
                        ctx,
                        task,
                        "Must this tool call and its result remain available in active context to"
                                + " complete the task?",
                        candidates)
                .map(
                        d -> {
                            if (d.status() != JevExecution.Status.DECIDED)
                                return new Plan(original, Map.of(), original, d.reason());
                            Map<String, Exchange> archive = new LinkedHashMap<>();
                            original.stream()
                                    .filter(
                                            e ->
                                                    !e.pinned()
                                                            && d.value()
                                                                    .rejected()
                                                                    .contains(e.id()))
                                    .forEach(e -> archive.put(e.id(), e));
                            return new Plan(
                                    original.stream()
                                            .filter(e -> !archive.containsKey(e.id()))
                                            .toList(),
                                    archive,
                                    original,
                                    "PROPOSAL_ONLY");
                        });
    }
}
