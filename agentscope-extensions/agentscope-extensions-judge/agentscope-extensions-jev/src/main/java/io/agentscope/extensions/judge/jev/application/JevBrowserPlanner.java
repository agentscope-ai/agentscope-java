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
import java.util.Objects;
import java.util.function.Supplier;
import reactor.core.publisher.Mono;

/** Bounded read-only browser experiment. Host executes and independently verifies outcomes. */
public final class JevBrowserPlanner {
    public enum Operation {
        READ,
        SCROLL,
        WAIT,
        DONE,
        BLOCKED
    }

    public record Action(String id, Operation operation, String visibleTarget) {
        public Action {
            if (id == null || id.isBlank() || operation == null || visibleTarget == null)
                throw new IllegalArgumentException("invalid action");
        }
    }

    public record Proposal(String pageVersion, Action action, String reason) {
        public boolean validFor(String currentVersion) {
            return action != null && Objects.equals(pageVersion, currentVersion);
        }
    }

    private final JevCandidateSelector selector;

    public JevBrowserPlanner(JevCandidateSelector selector) {
        this.selector = Objects.requireNonNull(selector);
    }

    public Mono<Proposal> propose(
            RuntimeContext ctx,
            String goal,
            String pageVersion,
            List<Action> observed,
            Supplier<String> currentVersion,
            boolean independentlyComplete) {
        if (pageVersion == null || pageVersion.isBlank())
            throw new IllegalArgumentException("page version required");
        Map<String, Object> candidates = new LinkedHashMap<>();
        for (Action a : observed)
            if (candidates.putIfAbsent(a.id(), a) != null)
                throw new IllegalArgumentException("duplicate action");
        return selector.select(
                        ctx,
                        Map.of("goal", goal, "pageVersion", pageVersion),
                        "Is this observed action appropriate as the next step towards the goal?",
                        candidates)
                .map(
                        d -> {
                            if (!pageVersion.equals(currentVersion.get()))
                                return new Proposal(pageVersion, null, "STALE_PAGE");
                            if (d.status() != JevExecution.Status.DECIDED
                                    || d.value().selected().isEmpty()
                                    || !d.value().uncertain().isEmpty())
                                return new Proposal(pageVersion, null, "ABSTAIN");
                            Action action = (Action) candidates.get(d.value().selected().get(0));
                            if (action.operation() == Operation.DONE && !independentlyComplete)
                                return new Proposal(pageVersion, null, "COMPLETION_NOT_VERIFIED");
                            return new Proposal(pageVersion, action, "PROPOSAL_ONLY");
                        });
    }
}
