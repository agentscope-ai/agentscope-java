/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.responses.middleware;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.GenerateOptions;
import java.util.List;
import java.util.function.Function;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Request-scoped middleware for Responses system fragments and generation options. */
public final class ResponsesRequestMiddleware implements MiddlewareBase {

    private final List<String> systemFragments;
    private final GenerateOptions requestOptions;

    /**
     * Constructs middleware for one Responses request.
     *
     * @param systemFragments instructions, system messages, and developer messages
     * @param requestOptions generation options derived from the request body
     */
    public ResponsesRequestMiddleware(
            List<String> systemFragments, GenerateOptions requestOptions) {
        this.systemFragments = systemFragments != null ? List.copyOf(systemFragments) : List.of();
        this.requestOptions = requestOptions;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext ctx, String currentPrompt) {
        StringBuilder prompt = new StringBuilder(currentPrompt != null ? currentPrompt : "");
        for (String fragment : systemFragments) {
            if (fragment == null || fragment.isBlank()) {
                continue;
            }
            if (!prompt.isEmpty()) {
                prompt.append('\n');
            }
            prompt.append(fragment);
        }
        return Mono.just(prompt.toString());
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext ctx,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (requestOptions == null) {
            return next.apply(input);
        }
        GenerateOptions merged = GenerateOptions.mergeOptions(requestOptions, input.options());
        return next.apply(new ReasoningInput(input.messages(), input.tools(), merged));
    }
}
