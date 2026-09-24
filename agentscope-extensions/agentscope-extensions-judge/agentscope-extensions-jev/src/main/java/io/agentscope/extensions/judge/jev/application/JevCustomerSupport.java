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
import io.agentscope.extensions.judge.jev.JevJudge;
import io.agentscope.extensions.judge.jev.example.JevCustomerSupportExample;
import java.util.Map;
import reactor.core.publisher.Mono;

/** Explicit shadow triage and draft review; never issues refunds or sends replies. */
public final class JevCustomerSupport {
    public record Report(
            JevExecution.Decision<JevCandidateSelector.Selection> triage, JevJudge.Result review) {}

    private final JevCandidateSelector selector;
    private final JevJudge judge;

    public JevCustomerSupport(JevCandidateSelector selector, JevJudge judge) {
        this.selector = java.util.Objects.requireNonNull(selector);
        this.judge = java.util.Objects.requireNonNull(judge);
    }

    public Mono<Report> review(
            RuntimeContext ctx, String request, String draft, Object businessRecord) {
        var state = Map.of("request", request, "draft", draft, "business_record", businessRecord);
        return selector.select(
                        ctx,
                        state,
                        "Does the customer request require this support queue? Preserve conditional"
                                + " requests as conditional.",
                        Map.of(
                                "orders",
                                "Order status and shipping",
                                "billing",
                                "Payments and conditional refund requests",
                                "technical",
                                "Technical failures"))
                .zipWith(judge.judge(state, JevCustomerSupportExample.definition()), Report::new);
    }
}
