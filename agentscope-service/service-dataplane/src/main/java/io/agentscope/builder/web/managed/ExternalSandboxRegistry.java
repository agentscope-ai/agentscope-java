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
package io.agentscope.builder.web.managed;

import io.agentscope.harness.agent.sandbox.Sandbox;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Live {@code sessionId -> Sandbox} handles registered by an Environment Worker (a remote
 * worker calling the worker REST API).
 *
 * <p>{@link HandsLeaseService} polls this registry after enqueueing a work item on {@link
 * EnvironmentWorkQueue}; once a worker registers a sandbox for the session, the harness attaches
 * it to the turn's {@link io.agentscope.harness.agent.sandbox.SandboxContext} as the Priority-1
 * {@code externalSandbox}, so the harness never starts, stops, or shuts it down — the worker owns
 * the full lifecycle.
 */
@Component
public class ExternalSandboxRegistry {

    private final ConcurrentHashMap<String, Sandbox> sandboxes = new ConcurrentHashMap<>();

    /** Registers (or replaces) the live sandbox for a session. */
    public void register(String sessionId, Sandbox sandbox) {
        sandboxes.put(sessionId, sandbox);
    }

    /** Returns the live sandbox for a session, or {@code null} if none is registered. */
    public Sandbox get(String sessionId) {
        return sandboxes.get(sessionId);
    }

    /** Removes and returns the live sandbox for a session, or {@code null} if none was registered. */
    public Sandbox remove(String sessionId) {
        return sandboxes.remove(sessionId);
    }

    /** Returns the number of currently registered sandboxes. Exposed for metrics/diagnostics. */
    public int size() {
        return sandboxes.size();
    }
}
