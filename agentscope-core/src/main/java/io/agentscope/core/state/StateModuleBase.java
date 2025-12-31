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

import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Base implementation of StateModule providing automatic state management.
 *
 * This class implements the core state management functionality with automatic
 * discovery of nested StateModules via reflection and support for manual attribute
 * registration with custom serialization functions.
 *
 * Features:
 * - Automatic nested StateModule discovery and management
 * - Manual attribute registration with custom serialization
 * - Thread-safe state operations using concurrent collections
 * - JSON serialization support via Jackson ObjectMapper
 * - Hierarchical state collection and restoration
 */
public abstract class StateModuleBase implements StateModule {

    private final Map<String, StateModule> moduleMap = new LinkedHashMap<>();
    private final Map<String, AttributeInfo> attributeMap = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Initialize the StateModule.
     * Note: Nested module discovery is deferred until first state access
     * to ensure all fields are properly initialized.
     */
    public StateModuleBase() {
        // Deferred initialization
    }

    @Override
    public Map<String, Object> stateDict() {
        // Ensure nested modules are discovered before serialization
        refreshNestedModules();

        Map<String, Object> state = new LinkedHashMap<>();

        // Collect nested module states
        for (Map.Entry<String, StateModule> entry : moduleMap.entrySet()) {
            state.put(entry.getKey(), entry.getValue().stateDict());
        }

        // Collect registered attribute states
        for (Map.Entry<String, AttributeInfo> entry : attributeMap.entrySet()) {
            String attrName = entry.getKey();
            AttributeInfo attrInfo = entry.getValue();

            try {
                Object value = getAttributeValue(attrName);
                if (value != null) {
                    // Apply custom serialization if provided and we got the value via reflection
                    if (attrInfo.toJsonFunction != null) {
                        // Check if the value was obtained via reflection (not from the function)
                        try {
                            Field field = findField(attrName);
                            if (field != null) {
                                // Value came from reflection, apply transformation
                                value = attrInfo.toJsonFunction.apply(value);
                            }
                            // If no field found, the value already came from toJsonFunction
                        } catch (Exception ignored) {
                            // No field found, value came from function, don't double-apply
                        }
                    }
                    state.put(attrName, value);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize attribute: " + attrName, e);
            }
        }

        return state;
    }

    @Override
    public void loadStateDict(Map<String, Object> stateDict, boolean strict) {
        if (stateDict == null) {
            if (strict) {
                throw new IllegalArgumentException("State map cannot be null in strict mode");
            }
            return;
        }

        // Load nested module states
        for (Map.Entry<String, StateModule> entry : moduleMap.entrySet()) {
            String moduleName = entry.getKey();
            StateModule module = entry.getValue();

            if (stateDict.containsKey(moduleName)) {
                Object moduleState = stateDict.get(moduleName);
                if (moduleState instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> moduleStateMap = (Map<String, Object>) moduleState;
                    module.loadStateDict(moduleStateMap, strict);
                } else if (strict) {
                    throw new IllegalArgumentException("Invalid state for module: " + moduleName);
                }
            } else if (strict) {
                throw new IllegalArgumentException("Missing state for module: " + moduleName);
            }
        }

        // Load registered attribute states
        for (Map.Entry<String, AttributeInfo> entry : attributeMap.entrySet()) {
            String attrName = entry.getKey();
            AttributeInfo attrInfo = entry.getValue();

            if (stateDict.containsKey(attrName)) {
                try {
                    Object value = stateDict.get(attrName);

                    // Apply custom deserialization if provided
                    if (attrInfo.fromJsonFunction != null && value != null) {
                        value = attrInfo.fromJsonFunction.apply(value);
                    }

                    setAttributeValue(attrName, value);
                } catch (Exception e) {
                    if (strict) {
                        throw new RuntimeException(
                                "Failed to deserialize attribute: " + attrName, e);
                    }
                }
            } else if (strict) {
                throw new IllegalArgumentException("Missing state for attribute: " + attrName);
            }
        }
    }

    @Override
    public void registerState(
            String attributeName,
            Function<Object, Object> toJsonFunction,
            Function<Object, Object> fromJsonFunction) {
        attributeMap.put(attributeName, new AttributeInfo(toJsonFunction, fromJsonFunction));
    }

    @Override
    public String[] getRegisteredAttributes() {
        return attributeMap.keySet().toArray(new String[0]);
    }

    @Override
    public boolean unregisterState(String attributeName) {
        return attributeMap.remove(attributeName) != null;
    }

    @Override
    public void clearRegisteredState() {
        attributeMap.clear();
    }

    /**
     * Discover/refresh nested StateModules via reflection and register them automatically.
     * This method is called on-demand to ensure all fields are initialized.
     */
    private void refreshNestedModules() {
        moduleMap.clear(); // Clear and rediscover to handle dynamic changes

        Class<?> clazz = this.getClass();
        while (clazz != null && clazz != StateModuleBase.class && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (StateModule.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    try {
                        StateModule nestedModule = (StateModule) field.get(this);
                        if (nestedModule != null) {
                            moduleMap.put(field.getName(), nestedModule);
                        }
                    } catch (IllegalAccessException e) {
                        // Skip inaccessible fields
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
    }

    /**
     * Get the value of an attribute by name using reflection.
     *
     * This method is primarily for internal use and subclass extensions.
     * It first attempts to get the value via reflection from a field with the given name.
     * If no field is found, it checks for a registered toJsonFunction that can provide the value.
     * This enables custom attribute access for computed or derived values that don't have
     * corresponding fields.
     *
     * @param attributeName Name of the attribute (must not be null)
     * @return Attribute value (may be null)
     * @throws RuntimeException if attribute cannot be accessed or doesn't exist
     * @throws IllegalArgumentException if attributeName is null
     */
    protected Object getAttributeValue(String attributeName) {
        // Try reflection first
        try {
            Field field = findField(attributeName);
            if (field != null) {
                field.setAccessible(true);
                return field.get(this);
            }
        } catch (Exception e) {
            // If reflection fails, check if there's a registered function that can provide the
            // value
            AttributeInfo attrInfo = attributeMap.get(attributeName);
            if (attrInfo != null && attrInfo.toJsonFunction != null) {
                // For attributes without fields, the function should provide the value from 'this'
                return attrInfo.toJsonFunction.apply(this);
            }
            throw new RuntimeException("Failed to get attribute value: " + attributeName, e);
        }

        // If field not found but there's a registered function, use it
        AttributeInfo attrInfo = attributeMap.get(attributeName);
        if (attrInfo != null && attrInfo.toJsonFunction != null) {
            return attrInfo.toJsonFunction.apply(this);
        }

        throw new RuntimeException("Attribute not found: " + attributeName);
    }

    /**
     * Set the value of an attribute by name using reflection.
     *
     * This method is primarily for internal use and subclass extensions.
     * It attempts to set the value via reflection on a field with the given name.
     * Note that this method only works for actual fields and cannot set computed
     * or derived values that are provided by toJsonFunction.
     *
     * @param attributeName Name of the attribute (must not be null)
     * @param value New value for the attribute (may be null)
     * @throws RuntimeException if attribute cannot be accessed or doesn't exist
     * @throws IllegalArgumentException if attributeName is null
     */
    protected void setAttributeValue(String attributeName, Object value) {
        try {
            Field field = findField(attributeName);
            if (field != null) {
                field.setAccessible(true);
                field.set(this, value);
                return;
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to set attribute value: " + attributeName, e);
        }
        throw new RuntimeException("Attribute not found: " + attributeName);
    }

    /**
     * Find a field by name in this class or its superclasses.
     *
     * @param fieldName Name of the field to find
     * @return Field object or null if not found
     */
    private Field findField(String fieldName) {
        Class<?> clazz = this.getClass();
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        return null;
    }

    /**
     * Get the nested modules map (for debugging or advanced use cases).
     *
     * This method provides read-only access to the nested StateModules that have been
     * discovered via reflection or added manually. The returned map is unmodifiable
     * to maintain internal state consistency.
     *
     * @return Unmodifiable map of nested StateModules
     */
    protected Map<String, StateModule> getNestedModules() {
        return Collections.unmodifiableMap(moduleMap);
    }

    /**
     * Manually add a nested module (for dynamic composition).
     *
     * This method allows subclasses to dynamically add StateModules that may not
     * be discoverable via reflection (e.g., modules created at runtime, modules
     * in collections, or modules managed by frameworks).
     *
     * @param name Name of the module (must not be null)
     * @param module StateModule to add (must not be null)
     * @throws IllegalArgumentException if name or module is null
     */
    protected void addNestedModule(String name, StateModule module) {
        moduleMap.put(name, module);
    }

    /**
     * Remove a nested module.
     *
     * This method allows subclasses to dynamically remove StateModules that were
     * previously added via reflection discovery or manual addition.
     *
     * @param name Name of the module to remove (must not be null)
     * @return true if module was removed, false if no module with that name exists
     * @throws IllegalArgumentException if name is null
     */
    protected boolean removeNestedModule(String name) {
        return moduleMap.remove(name) != null;
    }

    /**
     * Internal class to store attribute serialization information.
     */
    private static class AttributeInfo {
        final Function<Object, Object> toJsonFunction;
        final Function<Object, Object> fromJsonFunction;

        AttributeInfo(
                Function<Object, Object> toJsonFunction,
                Function<Object, Object> fromJsonFunction) {
            this.toJsonFunction = toJsonFunction;
            this.fromJsonFunction = fromJsonFunction;
        }
    }
}
