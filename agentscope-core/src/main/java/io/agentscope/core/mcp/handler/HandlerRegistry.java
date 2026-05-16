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
