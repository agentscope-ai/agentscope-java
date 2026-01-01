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
package io.agentscope.core.session;

import io.agentscope.core.state.SessionKey;
import io.agentscope.core.state.SimpleSessionKey;
import io.agentscope.core.state.StateModule;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class for managing session state with simplified API.
 *
 * <p>This class provides a fluent API for both loading and saving session state without requiring
 * manual creation of component maps and manual string keys. It supports different session
 * implementations through dependency injection.
 *
 * <p>Usage example with JsonSession:
 *
 * <pre>{@code
 * SessionManager.forSessionId("user123")
 *     .withSession(new JsonSession(Path.of("sessions")))
 *     .addComponent(agent)
 *     .addComponent(memory)
 *     .loadIfExists();
 * }</pre>
 *
 * <p>Usage example with saving:
 *
 * <pre>{@code
 * SessionManager.forSessionId("user123")
 *     .withSession(new JsonSession(Path.of("sessions")))
 *     .addComponent(agent)
 *     .addComponent(memory)
 *     .saveSession(); // Save current state
 * }</pre>
 *
 * <p>Usage example with custom session:
 *
 * <pre>{@code
 * SessionManager.forSessionId("user123")
 *     .withSession(new DatabaseSession(dbConnection))
 *     .addComponent(agent)
 *     .addComponent(memory)
 *     .saveOrThrow(); // Save with error handling
 * }</pre>
 */
public class SessionManager {

    private final String sessionId;
    private final SessionKey sessionKey;
    private final List<StateModule> components = new ArrayList<>();
    private Session session;

    private SessionManager(String sessionId) {
        this.sessionId = sessionId;
        this.sessionKey = SimpleSessionKey.of(sessionId);
    }

    /**
     * Create a SessionManager for the given session ID.
     *
     * @param sessionId The session ID to manage
     * @return New SessionManager instance
     * @throws IllegalArgumentException if sessionId is null or empty
     */
    public static SessionManager forSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) {
            throw new IllegalArgumentException("Session ID cannot be null or empty");
        }
        return new SessionManager(sessionId);
    }

    /**
     * Set the session implementation to use.
     *
     * <p>This method allows using any Session implementation, making the manager extensible for
     * different storage backends.
     *
     * @param session session instance
     * @return This SessionManager for chaining
     * @throws IllegalArgumentException if session is null
     */
    public SessionManager withSession(Session session) {
        if (session == null) {
            throw new IllegalArgumentException("Session cannot be null");
        }
        this.session = session;
        return this;
    }

    /**
     * Add a component to be managed.
     *
     * <p>Components will be saved and loaded using their saveTo/loadFrom methods.
     *
     * @param component The StateModule component to add
     * @return This SessionManager for chaining
     * @throws IllegalArgumentException if component is null
     */
    public SessionManager addComponent(StateModule component) {
        if (component == null) {
            throw new IllegalArgumentException("Component cannot be null");
        }
        components.add(component);
        return this;
    }

    /**
     * Load session state if the session exists.
     *
     * <p>This method will only load the state if a session with the given ID exists. If no session
     * exists, this method does nothing.
     *
     * @throws IllegalStateException if no session has been configured
     */
    public void loadIfExists() {
        Session session = checkAndGetSession();
        if (session.exists(sessionKey)) {
            for (StateModule component : components) {
                component.loadFrom(session, sessionKey);
            }
        }
    }

    /**
     * Load session state, throwing an exception if session doesn't exist.
     *
     * @throws IllegalStateException if no session has been configured
     * @throws IllegalArgumentException if session doesn't exist
     */
    public void loadOrThrow() {
        Session session = checkAndGetSession();
        if (!session.exists(sessionKey)) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        for (StateModule component : components) {
            component.loadFrom(session, sessionKey);
        }
    }

    /**
     * Save current component states to session storage.
     *
     * <p>This method saves the current state of all registered components. If the session doesn't
     * exist, it will be created.
     *
     * @throws IllegalStateException if no session has been configured
     */
    public void saveSession() {
        Session session = checkAndGetSession();
        for (StateModule component : components) {
            component.saveTo(session, sessionKey);
        }
    }

    /**
     * Save current component states to session storage with error handling.
     *
     * <p>This method saves the current state of all registered components and throws an exception if
     * the save operation fails.
     *
     * @throws IllegalStateException if no session has been configured
     * @throws RuntimeException if save operation fails
     */
    public void saveOrThrow() {
        try {
            saveSession();
        } catch (Exception e) {
            throw new RuntimeException("Failed to save session: " + sessionId, e);
        }
    }

    /**
     * Save session state only if the session already exists.
     *
     * <p>This method only saves if a session with the given ID already exists. If no session exists,
     * this method does nothing.
     *
     * @throws IllegalStateException if no session has been configured
     */
    public void saveIfExists() {
        Session session = checkAndGetSession();
        if (session.exists(sessionKey)) {
            for (StateModule component : components) {
                component.saveTo(session, sessionKey);
            }
        }
    }

    /**
     * Check if the session exists.
     *
     * @return true if session exists, false otherwise
     * @throws IllegalStateException if no session has been configured
     */
    public boolean sessionExists() {
        Session session = checkAndGetSession();
        return session.exists(sessionKey);
    }

    /**
     * Get the configured session for advanced operations.
     *
     * @return The configured Session instance
     * @throws IllegalStateException if no session has been configured
     */
    public Session getSession() {
        return checkAndGetSession();
    }

    /**
     * Delete the session if it exists.
     *
     * @return true if session was deleted, false if it didn't exist
     * @throws IllegalStateException if no session has been configured
     */
    public boolean deleteIfExists() {
        Session session = checkAndGetSession();
        if (session.exists(sessionKey)) {
            session.delete(sessionKey);
            return true;
        }
        return false;
    }

    /**
     * Delete the session, throwing an exception if it doesn't exist.
     *
     * @throws IllegalStateException if no session has been configured
     * @throws IllegalArgumentException if session doesn't exist
     */
    public void deleteOrThrow() {
        Session session = checkAndGetSession();
        if (!session.exists(sessionKey)) {
            throw new IllegalArgumentException("Session not found: " + sessionId);
        }
        session.delete(sessionKey);
    }

    private Session checkAndGetSession() {
        if (session == null) {
            throw new IllegalStateException("No session configured. Use withSession()");
        }
        return session;
    }
}
