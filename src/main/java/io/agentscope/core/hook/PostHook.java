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
 * Functional interface for post-execution hooks in agent operations.
 *
 * Post-hooks are executed after the core agent operation (reply, observe, print)
 * and can modify the output before it's returned to the caller.
 *
 * Following the Python AgentScope pattern:
 * def post_hook(self: AgentBase, args: HookArgs, output: Any) -> Any
 *
 * @param <T> The agent type that this hook applies to
 */
@FunctionalInterface
public interface PostHook<T extends AgentBase> {

    /**
     * Execute the post-hook with the given agent, arguments, and operation output.
     *
     * @param agent The agent instance the hook is applied to
     * @param args Typed arguments that were used for the operation
     * @param output The output produced by the core operation
     * @return Modified output, or the original output unchanged
     * @throws Exception if hook execution fails (will not abort the operation)
     */
    Mono<Object> execute(T agent, HookArgs args, Object output);
}
