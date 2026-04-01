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

import io.agentscope.core.agent.AgentBase;
import io.agentscope.core.interruption.InterruptSource;
import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime context for a single active request.
 */
final class ActiveRequestContext {

    private static final Logger log = LoggerFactory.getLogger(ActiveRequestContext.class);

    private final String requestId;
    private final AgentBase agent;
    private final AtomicBoolean shutdownInterruptIssued = new AtomicBoolean(false);

    private final Session session;
    private final SessionKey sessionKey;

    ActiveRequestContext(
            String requestId, AgentBase agent, Session session, SessionKey sessionKey) {
        this.requestId = requestId;
        this.agent = agent;
        this.session = session;
        this.sessionKey = sessionKey;
    }

    String getRequestId() {
        return requestId;
    }

    boolean hasSessionBinding() {
        return session != null && sessionKey != null;
    }

    static final String SHUTDOWN_INTERRUPTED_KEY = "shutdown_interrupted";

    void saveToSession() {
        if (!hasSessionBinding()) {
            return;
        }
        try {
            agent.saveTo(session, sessionKey);
            session.save(sessionKey, SHUTDOWN_INTERRUPTED_KEY, new ShutdownInterruptedState(true));
        } catch (Exception e) {
            log.warn("Failed to save agent state for request {}", requestId, e);
        }
    }

    boolean interruptForShutdown() {
        if (!shutdownInterruptIssued.compareAndSet(false, true)) {
            return false;
        }
        agent.interrupt(InterruptSource.SYSTEM);
        return true;
    }
}
