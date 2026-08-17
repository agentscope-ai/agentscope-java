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
import java.time.Instant;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Decides whether a draft skill is allowed to be promoted into the live skills directory.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@code RejectAllGate} — defers everything; only programmatic
 *       {@code HarnessAgent.promoteSkill(...)} can move drafts forward (enterprise default)</li>
 *   <li>{@code LocalApprovalGate} — single-replica HITL via the host shell / CLI prompt</li>
 *   <li>{@code NotifyAndWaitGate} — multi-replica: writes a {@code .review_request.json}
 *       file next to the draft and notifies one or more {@code NotificationSink}s; waits
 *       for an external system to call {@code promoteSkill} again</li>
 * </ul>
 *
 * @see SkillCandidate
 * @see PromotionDecision
 */
public interface SkillPromotionGate {

    /**
     * Review a draft. Implementations should perform whatever side effects they need
     * (notification, prompt, etc.) and return a {@link PromotionDecision}.
     */
    Mono<PromotionDecision> review(SkillCandidate candidate, RuntimeContext ctx);

    /** Final outcome of a gate review. */
    sealed interface PromotionDecision
            permits PromotionDecision.Approve, PromotionDecision.Reject, PromotionDecision.Defer {

        record Approve(String reviewerId, List<String> targetEnvironments, Instant decidedAt)
                implements PromotionDecision {}

        record Reject(String reason, String reviewerId) implements PromotionDecision {}

        record Defer(Duration retryAfter, String reason) implements PromotionDecision {}
    }
}
