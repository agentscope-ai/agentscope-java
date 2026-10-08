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
package io.agentscope.core.shutdown;

import io.agentscope.core.agent.RunControl;
import io.agentscope.core.interruption.InterruptSource;
import io.agentscope.core.state.AgentState;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime context for a single active request.
 *
 * <p>When the owning {@code call()} resolves its per-(userId, sessionId) {@link AgentState} slot,
 * it is bound here via {@link #bindState(AgentState)} so shutdown interruption and state-saving
 * target that exact session — concurrency-safe even when an agent instance serves multiple
 * sessions. Queued executions have no bound state and are cancelled without reading or saving
 * another execution's state.
 */
final class ActiveRequestContext {

    private static final Logger log = LoggerFactory.getLogger(ActiveRequestContext.class);

    private final String requestId;
    private final RunControl control;
    private final AtomicBoolean shutdownInterruptIssued = new AtomicBoolean(false);

    private final ShutdownStateSaver saver;

    /**
     * The per-call session state this request is running against, bound once {@code call()} has
     * resolved its slot. Null while queued: no conversation state is saved for that request.
     */
    private volatile AgentState boundState;

    ActiveRequestContext(String requestId, ShutdownStateSaver saver, RunControl control) {
        this.requestId = requestId;
        this.control = control;
        this.saver = saver;
    }

    String getRequestId() {
        return requestId;
    }

    /**
     * Bind the per-call session state resolved for this request so interrupt and save target the
     * exact {@code (userId, sessionId)} session. Called by {@code AgentBase} once the call's slot
     * has been activated.
     */
    void bindState(AgentState state) {
        if (state != null) {
            this.boundState = state;
        }
    }

    private AgentState resolveState() {
        return boundState;
    }

    void saveState() {
        AgentState state = resolveState();
        if (saver == null || state == null) {
            return;
        }
        try {
            state.setShutdownInterrupted(true);
            saver.save(state);
        } catch (Exception e) {
            log.warn("Failed to save agent state for request {}", requestId, e);
        }
    }

    boolean interruptForShutdown() {
        if (!shutdownInterruptIssued.compareAndSet(false, true)) {
            return false;
        }
        control.interrupt(InterruptSource.SYSTEM, null);
        return true;
    }
}
