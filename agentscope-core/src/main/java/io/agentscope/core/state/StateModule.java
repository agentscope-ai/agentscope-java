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
package io.agentscope.core.state;

import io.agentscope.core.session.Session;

/**
 * Interface for all stateful components in AgentScope.
 *
 * <p>This interface provides state serialization and deserialization capabilities for components
 * that need to persist and restore their internal state. Components that implement this interface
 * can have their state saved to and restored from external storage through the session management
 * system.
 *
 * <p>Use {@link #saveTo(Session, SessionKey)} and {@link #loadFrom(Session, SessionKey)} for direct
 * session interaction with type-safe state objects.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * Session session = new JsonSession(Path.of("sessions"));
 * SessionKey sessionKey = SimpleSessionKey.of("user_123");
 *
 * // Load state if exists
 * agent.loadIfExists(session, sessionKey);
 *
 * // ... use agent ...
 *
 * // Save state
 * agent.saveTo(session, sessionKey);
 * }</pre>
 */
public interface StateModule {

    /**
     * Save state to the session.
     *
     * <p>Components should implement this method to persist their state using the Session's save
     * methods.
     *
     * @param session the session to save state to
     * @param sessionKey the session identifier
     */
    default void saveTo(Session session, SessionKey sessionKey) {
        // Default implementation is a no-op
        // Subclasses should override this method to implement state saving
    }

    /**
     * Load state from the session.
     *
     * <p>Components should implement this method to restore their state using the Session's get
     * methods.
     *
     * @param session the session to load state from
     * @param sessionKey the session identifier
     */
    default void loadFrom(Session session, SessionKey sessionKey) {
        // Default implementation is a no-op
        // Subclasses should override this method to implement state loading
    }

    /**
     * Load state from the session if it exists.
     *
     * @param session the session to load state from
     * @param sessionKey the session identifier
     * @return true if the session existed and state was loaded, false otherwise
     */
    default boolean loadIfExists(Session session, SessionKey sessionKey) {
        if (session.exists(sessionKey)) {
            loadFrom(session, sessionKey);
            return true;
        }
        return false;
    }
}
