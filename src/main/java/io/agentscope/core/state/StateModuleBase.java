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
 * This class implements the core state management functionality following
 * the Python agentscope StateModule pattern. It automatically discovers
 * nested StateModules via reflection and supports manual attribute registration
 * with custom serialization functions.
 *
 * Features:
 * - Automatic nested StateModule discovery and management
 * - Manual attribute registration with custom serialization
 * - Thread-safe state operations using concurrent collections
 * - JSON serialization support via Jackson ObjectMapper
 * - Hierarchical state collection and restoration
 */
public abstract class StateModuleBase implements StateModule {

    private final Map<String, StateModule> moduleDict = new LinkedHashMap<>();
    private final Map<String, AttributeInfo> attributeDict = new ConcurrentHashMap<>();
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
        for (Map.Entry<String, StateModule> entry : moduleDict.entrySet()) {
            state.put(entry.getKey(), entry.getValue().stateDict());
        }

        // Collect registered attribute states
        for (Map.Entry<String, AttributeInfo> entry : attributeDict.entrySet()) {
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
                throw new IllegalArgumentException(
                        "State dictionary cannot be null in strict mode");
            }
            return;
        }

        // Load nested module states
        for (Map.Entry<String, StateModule> entry : moduleDict.entrySet()) {
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
        for (Map.Entry<String, AttributeInfo> entry : attributeDict.entrySet()) {
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
        attributeDict.put(attributeName, new AttributeInfo(toJsonFunction, fromJsonFunction));
    }

    @Override
    public String[] getRegisteredAttributes() {
        return attributeDict.keySet().toArray(new String[0]);
    }

    @Override
    public boolean unregisterState(String attributeName) {
        return attributeDict.remove(attributeName) != null;
    }

    @Override
    public void clearRegisteredState() {
        attributeDict.clear();
    }

    /**
     * Discover/refresh nested StateModules via reflection and register them automatically.
     * This method is called on-demand to ensure all fields are initialized.
     */
    private void refreshNestedModules() {
        moduleDict.clear(); // Clear and rediscover to handle dynamic changes

        Class<?> clazz = this.getClass();
        while (clazz != null && clazz != StateModuleBase.class && clazz != Object.class) {
            for (Field field : clazz.getDeclaredFields()) {
                if (StateModule.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    try {
                        StateModule nestedModule = (StateModule) field.get(this);
                        if (nestedModule != null) {
                            moduleDict.put(field.getName(), nestedModule);
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
     * @param attributeName Name of the attribute
     * @return Attribute value
     * @throws RuntimeException if attribute cannot be accessed
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
            AttributeInfo attrInfo = attributeDict.get(attributeName);
            if (attrInfo != null && attrInfo.toJsonFunction != null) {
                // For attributes without fields, the function should provide the value from 'this'
                return attrInfo.toJsonFunction.apply(this);
            }
            throw new RuntimeException("Failed to get attribute value: " + attributeName, e);
        }

        // If field not found but there's a registered function, use it
        AttributeInfo attrInfo = attributeDict.get(attributeName);
        if (attrInfo != null && attrInfo.toJsonFunction != null) {
            return attrInfo.toJsonFunction.apply(this);
        }

        throw new RuntimeException("Attribute not found: " + attributeName);
    }

    /**
     * Set the value of an attribute by name using reflection.
     *
     * @param attributeName Name of the attribute
     * @param value New value for the attribute
     * @throws RuntimeException if attribute cannot be set
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
     * @return Map of nested StateModules
     */
    protected Map<String, StateModule> getNestedModules() {
        return Collections.unmodifiableMap(moduleDict);
    }

    /**
     * Manually add a nested module (for dynamic composition).
     *
     * @param name Name of the module
     * @param module StateModule to add
     */
    protected void addNestedModule(String name, StateModule module) {
        moduleDict.put(name, module);
    }

    /**
     * Remove a nested module.
     *
     * @param name Name of the module to remove
     * @return true if module was removed
     */
    protected boolean removeNestedModule(String name) {
        return moduleDict.remove(name) != null;
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
