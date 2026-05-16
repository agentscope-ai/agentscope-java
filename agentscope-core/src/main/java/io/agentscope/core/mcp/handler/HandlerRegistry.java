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

package io.agentscope.core.mcp.handler;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for MCP method handlers.
 *
 * <p>Stores and retrieves handlers by method name.
 */
public class HandlerRegistry {

    private final Map<String, MethodHandler> handlers = new ConcurrentHashMap<>();

    /**
     * Register a handler for a method.
     *
     * @param method the method name
     * @param handler the handler
     */
    public void register(String method, MethodHandler handler) {
        handlers.put(method, handler);
    }

    /**
     * Get a handler for a method.
     *
     * @param method the method name
     * @return the handler, or empty if not found
     */
    public Optional<MethodHandler> get(String method) {
        return Optional.ofNullable(handlers.get(method));
    }

    /**
     * Check if a handler is registered for a method.
     *
     * @param method the method name
     * @return true if handler exists
     */
    public boolean has(String method) {
        return handlers.containsKey(method);
    }

    /**
     * Unregister a handler for a method.
     *
     * @param method the method name
     */
    public void unregister(String method) {
        handlers.remove(method);
    }

    /**
     * Get all registered method names.
     *
     * @return set of method names
     */
    public Map<String, MethodHandler> getAll() {
        return new ConcurrentHashMap<>(handlers);
    }

    /**
     * Clear all handlers.
     */
    public void clear() {
        handlers.clear();
    }
}
