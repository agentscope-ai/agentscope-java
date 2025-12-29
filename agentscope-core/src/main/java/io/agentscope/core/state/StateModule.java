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
import java.util.Map;
import java.util.function.Function;

/**
 * Interface for all stateful components in AgentScope.
 *
 * <p>This interface provides state serialization and deserialization capabilities for components
 * that need to persist and restore their internal state. Components that implement this interface
 * can have their state saved to and restored from external storage through the session management
 * system.
 *
 * <p><b>New API (Recommended):</b> Use {@link #saveTo(Session, SessionKey)} and {@link
 * #loadFrom(Session, SessionKey)} for direct session interaction with type-safe state objects.
 *
 * <p><b>Legacy API (Deprecated):</b> The {@link #stateDict()} and {@link #loadStateDict(Map,
 * boolean)} methods are deprecated. Migrate to the new API for better type safety and incremental
 * storage support.
 *
 * <p>Example usage with new API:
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

    // ==================== New API (Recommended) ====================

    /**
     * Save state to the session.
     *
     * <p>Components should implement this method to persist their state using the Session's save
     * methods. This is the recommended way to persist state.
     *
     * @param session the session to save state to
     * @param sessionKey the session identifier
     */
    default void saveTo(Session session, SessionKey sessionKey) {
        // Default implementation uses legacy stateDict() for backward compatibility
        Map<String, Object> state = stateDict();
        if (state != null && !state.isEmpty()) {
            String componentName = getComponentName();
            if (componentName == null || componentName.isEmpty()) {
                componentName = getClass().getSimpleName().toLowerCase();
            }
            // Note: This default implementation doesn't use the new State-based API
            // Subclasses should override this method to use the new API directly
        }
    }

    /**
     * Load state from the session.
     *
     * <p>Components should implement this method to restore their state using the Session's get
     * methods. This is the recommended way to restore state.
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

    // ==================== Legacy API (Deprecated) ====================

    /**
     * Get the state map containing all stateful data.
     *
     * <p>This method recursively collects state from nested StateModules and registered attributes,
     * returning a map that can be serialized to JSON or other storage formats.
     *
     * @return Map containing all state data
     * @deprecated Use {@link #saveTo(Session, SessionKey)} instead for better type safety and
     *     incremental storage support.
     */
    @Deprecated
    Map<String, Object> stateDict();

    /**
     * Load state from a map, restoring the component to a previous state.
     *
     * <p>This method recursively restores state to nested StateModules and registered attributes
     * from the provided state map.
     *
     * @param stateDict Map containing state data to restore
     * @param strict Whether to enforce strict loading (fail on missing keys)
     * @throws IllegalArgumentException if strict=true and required state is missing
     * @deprecated Use {@link #loadFrom(Session, SessionKey)} instead for better type safety.
     */
    @Deprecated
    void loadStateDict(Map<String, Object> stateDict, boolean strict);

    /**
     * Load state from a map with default strict mode (true).
     *
     * @param stateDict Map containing state data to restore
     * @throws IllegalArgumentException if stateDict is null or contains invalid data
     * @deprecated Use {@link #loadFrom(Session, SessionKey)} instead for better type safety.
     */
    @Deprecated
    default void loadStateDict(Map<String, Object> stateDict) {
        loadStateDict(stateDict, true);
    }

    /**
     * Register an attribute for state tracking with optional custom serialization.
     *
     * <p>This method allows manual registration of attributes that should be included in the state
     * map. Custom serialization functions can be provided for complex objects that don't have
     * natural JSON representation.
     *
     * @param attributeName Name of the attribute to register
     * @param toJsonFunction Optional function to convert attribute to JSON-serializable form (null
     *     for default)
     * @param fromJsonFunction Optional function to restore attribute from JSON form (null for
     *     default)
     * @throws IllegalArgumentException if attributeName is null or empty
     * @deprecated Use the new {@link #saveTo(Session, SessionKey)} and {@link #loadFrom(Session,
     *     SessionKey)} API with State objects instead.
     */
    @Deprecated
    void registerState(
            String attributeName,
            Function<Object, Object> toJsonFunction,
            Function<Object, Object> fromJsonFunction);

    /**
     * Register an attribute for state tracking with default serialization.
     *
     * @param attributeName Name of the attribute to register
     * @throws IllegalArgumentException if attributeName is null or empty
     * @deprecated Use the new {@link #saveTo(Session, SessionKey)} and {@link #loadFrom(Session,
     *     SessionKey)} API with State objects instead.
     */
    @Deprecated
    default void registerState(String attributeName) {
        registerState(attributeName, null, null);
    }

    /**
     * Get the list of manually registered attribute names.
     *
     * @return Array of registered attribute names
     * @deprecated This method is part of the legacy API.
     */
    @Deprecated
    String[] getRegisteredAttributes();

    /**
     * Check if an attribute is registered for state tracking.
     *
     * @param attributeName Name of the attribute to check
     * @return true if the attribute is registered
     * @throws IllegalArgumentException if attributeName is null
     * @deprecated This method is part of the legacy API.
     */
    @Deprecated
    default boolean isAttributeRegistered(String attributeName) {
        String[] registered = getRegisteredAttributes();
        for (String attr : registered) {
            if (attr.equals(attributeName)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Unregister an attribute from state tracking.
     *
     * @param attributeName Name of the attribute to unregister
     * @return true if the attribute was registered and removed
     * @throws IllegalArgumentException if attributeName is null
     * @deprecated This method is part of the legacy API.
     */
    @Deprecated
    boolean unregisterState(String attributeName);

    /**
     * Clear all registered attributes.
     *
     * @deprecated This method is part of the legacy API.
     */
    @Deprecated
    void clearRegisteredState();

    /**
     * Get the component name for session management.
     *
     * <p>This method allows components to specify their name when used in session management. By
     * default, components can return null to use automatic naming based on class name.
     *
     * @return Component name or null to use default naming
     */
    default String getComponentName() {
        return null;
    }
}
