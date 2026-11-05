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
package io.agentscope.core.tool;

import io.agentscope.core.message.ToolResultBlock;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * Intercepts a single tool call at the Toolkit layer, after schema validation and preset merging
 * and before session dispatch logging and real execution.
 *
 * <p>Use cases are per-call, parameter-level business checks that tool-level permissions cannot
 * express: sandboxing file paths by caller identity, auditing, argument normalization, and policy
 * denial. Tool-level "may this tool be called at all" (ALLOW/ASK/DENY, human review) remains the
 * permission engine's concern.
 *
 * <p>Common actions:
 * <ul>
 *   <li>Pass through: {@code next.apply(invocation)}
 *   <li>Policy deny: {@code Mono.just(ToolResultBlock.text(msg)
 *       .withState(ToolResultState.DENIED))} — recorded as a {@code denied} decision
 *   <li>Invalid call: {@code Mono.just(ToolResultBlock.error(msg))} — no denied decision record
 *   <li>Rewrite arguments: {@code next.apply(invocation.withInput(newInput))} (may override
 *       presets; not re-validated against the schema)
 *   <li>Audit: {@code next.apply(invocation).doOnSuccess(...)}
 *   <li>Suspend for HITL: throw {@link ToolSuspendException}
 * </ul>
 *
 * <p>Implementations must never return a {@code null} or empty {@code Mono} (an empty one is
 * mapped to a misleading "completed without returning a result" error) and must be stateless or
 * thread-safe: interceptors registered on a {@link ToolkitConfig} are shared across concurrent
 * sessions. On the direct {@link Toolkit#callTool(ToolCallParam)} path there is no timeout or
 * scheduling wrapper, so implementations must not block; offload blocking I/O to a Reactor
 * scheduler instead.
 *
 * <p>External tools ({@code externalTool=true} / {@link SchemaOnlyTool}) are executed outside the
 * framework and never reach interceptors.
 *
 * @author shanhongyu
 */
public interface ToolCallInterceptor {

    /**
     * Intercepts a single tool call.
     *
     * @param invocation the call snapshot (tool name, tool instance, final input, runtime context)
     * @param next       invokes the next interceptor or the real tool execution
     * @return the tool result (pass-through result / denial reason / rewritten-execution result);
     *     never an empty {@code Mono}
     */
    Mono<ToolResultBlock> intercept(
            ToolInvocation invocation, Function<ToolInvocation, Mono<ToolResultBlock>> next);

    /**
     * Execution order: a larger value sits closer to the outside of the chain (intercepts first,
     * receives the result last). Same-order interceptors keep their registration order. Matches
     * the {@code MiddlewareBase#order()} convention. The default is {@code 1}.
     *
     * @return the execution order; higher values run outermost
     */
    default int order() {
        return 1;
    }
}
