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
package io.agentscope.extensions.aistio.adapter;

import io.agentscope.core.state.AgentState;
import reactor.core.publisher.Mono;

/**
 * On-demand context compaction for a session, invoked when the control plane issues {@code
 * compress}.
 *
 * <p>Compaction is deliberately not built in: it needs an LLM and a summarization policy, both of
 * which belong to the application rather than to an observability SDK. Supply an implementation
 * only if you want the control plane to be able to compress this agent; without one, {@code
 * compress} honestly reports itself as unsupported instead of silently doing nothing.
 *
 * <p>A typical implementation delegates to {@code ConversationCompactor} from
 * {@code agentscope-harness}:
 *
 * <pre>{@code
 * SessionCompactor compactor = (sessionId, state) ->
 *         conversationCompactor
 *                 .compactIfNeeded(RuntimeContext.builder().sessionId(sessionId).build(),
 *                         state.getContext().stream()
 *                                 .filter(m -> m.getRole() != MsgRole.SYSTEM)
 *                                 .toList(),
 *                         compactionConfig, agentId, sessionId)
 *                 .doOnNext(replacement -> replacement.ifPresent(msgs -> {
 *                     state.contextMutable().clear();
 *                     state.contextMutable().addAll(msgs);
 *                 }))
 *                 .then();
 * }</pre>
 */
@FunctionalInterface
public interface SessionCompactor {

    /**
     * Compacts the session's conversation in place.
     *
     * @param sessionId the session to compact
     * @param state live agent state for that session; mutate {@code state.contextMutable()} to
     *     apply the compacted history
     */
    Mono<Void> compact(String sessionId, AgentState state);
}
