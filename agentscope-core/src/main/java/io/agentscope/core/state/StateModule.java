/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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
package io.agentscope.core.state;

import java.util.Map;
import java.util.function.Function;

/**
 * Interface for all stateful components in AgentScope.
 *
 * This interface provides state serialization and deserialization capabilities
 * for components that need to persist and restore their internal state. Components
 * that implement this interface can have their state saved to and restored from
 * external storage through the session management system.
 *
 * Key features:
 * - Hierarchical state management (StateModules can contain other StateModules)
 * - Custom serialization support for complex objects
 * - Automatic nested state collection and restoration
 * - Manual attribute registration with custom serialization functions
 */
public interface StateModule {

    /**
     * Get the state map containing all stateful data.
     *
     * This method recursively collects state from nested StateModules and
     * registered attributes, returning a map that can be serialized to JSON
     * or other storage formats.
     *
     * @return Map containing all state data
     */
    Map<String, Object> stateDict();

    /**
     * Load state from a map, restoring the component to a previous state.
     *
     * This method recursively restores state to nested StateModules and
     * registered attributes from the provided state map.
     *
     * @param stateDict Map containing state data to restore
     * @param strict Whether to enforce strict loading (fail on missing keys)
     * @throws IllegalArgumentException if strict=true and required state is missing
     */
    void loadStateDict(Map<String, Object> stateDict, boolean strict);

    /**
     * Load state from a map with default strict mode (true).
     *
     * @param stateDict Map containing state data to restore
     * @throws IllegalArgumentException if stateDict is null or contains invalid data
     */
    default void loadStateDict(Map<String, Object> stateDict) {
        loadStateDict(stateDict, true);
    }

    /**
     * Register an attribute for state tracking with optional custom serialization.
     *
     * This method allows manual registration of attributes that should be included
     * in the state map. Custom serialization functions can be provided for
     * complex objects that don't have natural JSON representation.
     *
     * @param attributeName Name of the attribute to register
     * @param toJsonFunction Optional function to convert attribute to JSON-serializable form (null for default)
     * @param fromJsonFunction Optional function to restore attribute from JSON form (null for default)
     * @throws IllegalArgumentException if attributeName is null or empty
     */
    void registerState(
            String attributeName,
            Function<Object, Object> toJsonFunction,
            Function<Object, Object> fromJsonFunction);

    /**
     * Register an attribute for state tracking with default serialization.
     *
     * @param attributeName Name of the attribute to register
     * @throws IllegalArgumentException if attributeName is null or empty
     */
    default void registerState(String attributeName) {
        registerState(attributeName, null, null);
    }

    /**
     * Get the list of manually registered attribute names.
     *
     * @return Array of registered attribute names
     */
    String[] getRegisteredAttributes();

    /**
     * Check if an attribute is registered for state tracking.
     *
     * @param attributeName Name of the attribute to check
     * @return true if the attribute is registered
     * @throws IllegalArgumentException if attributeName is null
     */
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
     */
    boolean unregisterState(String attributeName);

    /**
     * Clear all registered attributes.
     */
    void clearRegisteredState();

    /**
     * Get the component name for session management.
     *
     * This method allows components to specify their name when used in session
     * management. By default, components can return null to use automatic naming
     * based on class name.
     *
     * @return Component name or null to use default naming
     */
    default String getComponentName() {
        return null;
    }
}
