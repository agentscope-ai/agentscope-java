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
package io.agentscope.core.plan;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Flux;

/**
 * Middleware that injects the current {@link PlanNotebook} hint into every reasoning step's
 * input message list.
 *
 * <p>Extracted from the anonymous inner Hook previously created in
 * {@code ReActAgent.Builder.configurePlan(...)}. The hint message is appended to the tail of
 * the reasoning input messages; the framework's leading SYSTEM message is preserved.
 *
 * @deprecated since 2.0.0. The plan module has been removed from 2.0 core; this middleware
 *     adapter remains for source compatibility with code that still uses the legacy
 *     {@link PlanNotebook}.
 */
@Deprecated(since = "2.0.0")
public class PlanHintMiddleware implements MiddlewareBase {

    private final PlanNotebook planNotebook;

    public PlanHintMiddleware(PlanNotebook planNotebook) {
        this.planNotebook = planNotebook;
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent, ReasoningInput input, Function<ReasoningInput, Flux<AgentEvent>> next) {
        return planNotebook
                .getCurrentHint()
                .flatMapMany(
                        hintMsg -> {
                            List<Msg> messages =
                                    input.messages() != null ? input.messages() : List.of();
                            List<Msg> modified = new ArrayList<>(messages);
                            modified.add(hintMsg);
                            return next.apply(
                                    new ReasoningInput(modified, input.tools(), input.options()));
                        })
                .switchIfEmpty(Flux.defer(() -> next.apply(input)));
    }
}
