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
package io.agentscope.core.agent;

/**
 * Optional call-scoped iteration positions supplied by a durable execution host through
 * {@link RuntimeContext}. The host resolves these from committed reasoning/tool facts, rather than
 * treating a process restart as a new iteration budget. Without a supplied position, native journals
 * restore an unfinished invocation's committed position; a fresh call starts at zero. This does not
 * replay Java stacks or middleware effects.
 *
 * @param reasoningIteration iteration for the next reasoning entry (including the maxIters check)
 * @param actingIteration iteration whose pending tool calls are being continued
 */
public record AgentExecutionPosition(int reasoningIteration, int actingIteration) {
    public AgentExecutionPosition {
        if (reasoningIteration < 0 || actingIteration < 0)
            throw new IllegalArgumentException("Execution iterations must be nonnegative");
    }
}
