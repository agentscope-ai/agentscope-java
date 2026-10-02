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
package io.agentscope.examples.copilotkit.workbench;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.AgentState;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Keeps one {@link WorkbenchState} per AG-UI thread.
 *
 * <p>The AG-UI adapter sets {@code RuntimeContext.sessionId} to the thread id, so tools and
 * middlewares can resolve the right state without any extra plumbing.
 *
 */
@Component
public class WorkbenchStateRegistry {

    private static final String FALLBACK_THREAD_ID = "default-thread";

    private final Map<String, WorkbenchState> states = new ConcurrentHashMap<>();

    public WorkbenchState forThread(String threadId) {
        String key = (threadId == null || threadId.isBlank()) ? FALLBACK_THREAD_ID : threadId;
        return states.computeIfAbsent(key, WorkbenchState::new);
    }

    public WorkbenchState forContext(RuntimeContext ctx) {
        return forThread(ctx == null ? null : ctx.getSessionId());
    }

    /**
     * Resolves the state for a tool call.
     *
     * <p>{@code AgentState} is keyed by session id, and the AG-UI adapter sets the session id to the
     * thread id, so injected state is enough to find the right workbench.
     */
    public WorkbenchState forState(AgentState state) {
        return forThread(state == null ? null : state.getSessionId());
    }

    public void remove(String threadId) {
        if (threadId != null) {
            states.remove(threadId);
        }
    }
}
