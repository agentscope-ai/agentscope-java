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
package io.agentscope.core.responses.hook;

import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.hook.PreReasoningEvent;
import io.agentscope.core.model.GenerateOptions;
import java.util.List;
import reactor.core.publisher.Mono;

/** Request-scoped hook for Responses system fragments and generation options. */
public class ResponsesRequestHook implements Hook {

    private final List<String> systemFragments;
    private final GenerateOptions requestOptions;

    /**
     * Constructs a hook for one Responses request.
     *
     * @param systemFragments Instructions, system messages, and developer messages to append to
     *     the model call
     * @param requestOptions Generation options derived from the request body
     */
    public ResponsesRequestHook(List<String> systemFragments, GenerateOptions requestOptions) {
        this.systemFragments = systemFragments != null ? List.copyOf(systemFragments) : List.of();
        this.requestOptions = requestOptions;
    }

    /**
     * Apply request-scoped instructions and generation options to AgentScope lifecycle events.
     *
     * <p>{@link PreCallEvent} is used for system content, while {@link PreReasoningEvent} is used
     * for model options so application defaults and per-request overrides can be merged.
     *
     * @param event AgentScope hook event
     * @return The same event after mutation
     */
    @Override
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (event instanceof PreCallEvent preCallEvent) {
            for (String fragment : systemFragments) {
                if (fragment != null && !fragment.isBlank()) {
                    preCallEvent.appendSystemContent(fragment);
                }
            }
            return Mono.just(event);
        }
        if (event instanceof PreReasoningEvent preReasoningEvent && requestOptions != null) {
            GenerateOptions merged =
                    GenerateOptions.mergeOptions(
                            requestOptions, preReasoningEvent.getEffectiveGenerateOptions());
            preReasoningEvent.setGenerateOptions(merged);
            return Mono.just(event);
        }
        return Mono.just(event);
    }

    /**
     * Run after core hooks with lower priority, while still leaving room for application hooks to
     * override this starter if they use a higher priority.
     */
    @Override
    public int priority() {
        return 50;
    }
}
