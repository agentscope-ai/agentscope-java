/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.hook;

import io.agentscope.core.agent.AgentBase;
import reactor.core.publisher.Mono;

/**
 * Functional interface for pre-execution hooks in agent operations.
 *
 * Pre-hooks are executed before the core agent operation (reply, observe, print)
 * and can modify the arguments that will be passed to the operation.
 *
 * Following the Python AgentScope pattern:
 * def pre_hook(self: AgentBase, args: HookArgs) -> HookArgs | None
 *
 * @param <T> The agent type that this hook applies to
 */
@FunctionalInterface
public interface PreHook<T extends AgentBase> {

    /**
     * Execute the pre-hook with the given agent and arguments.
     *
     * @param agent The agent instance the hook is applied to
     * @param args Typed arguments for the operation
     * @return Modified arguments, or null to pass original arguments unchanged
     * @throws Exception if hook execution fails (will not abort the operation)
     */
    Mono<HookArgs> execute(T agent, HookArgs args);
}
