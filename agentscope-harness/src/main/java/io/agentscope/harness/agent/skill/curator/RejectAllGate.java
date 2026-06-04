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
package io.agentscope.harness.agent.skill.curator;

import io.agentscope.core.agent.RuntimeContext;
import java.time.Duration;
import reactor.core.publisher.Mono;

/**
 * Always returns {@link SkillPromotionGate.PromotionDecision.Defer} — promotion can only
 * happen via an explicit {@code HarnessAgent.promoteSkill(...)} call from outside the gate.
 *
 * <p>This is the most paranoid default: the agent's own actions can never put a skill on the
 * live skills root by themselves. Suitable when SRE wants to keep promotion strictly manual.
 */
public class RejectAllGate implements SkillPromotionGate {

    private final Duration retryAfter;

    public RejectAllGate() {
        this(Duration.ofHours(24));
    }

    public RejectAllGate(Duration retryAfter) {
        this.retryAfter = retryAfter != null ? retryAfter : Duration.ofHours(24);
    }

    @Override
    public Mono<PromotionDecision> review(SkillCandidate candidate, RuntimeContext ctx) {
        return Mono.just(
                new PromotionDecision.Defer(
                        retryAfter,
                        "RejectAllGate: promotion requires explicit HarnessAgent.promoteSkill"
                                + " call by an authorized caller"));
    }
}
