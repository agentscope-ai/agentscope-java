/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.agui;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Thread-local context for passing HTTP request metadata to agent factories.
 *
 * <p>This class provides a mechanism for agent factory methods (e.g., {@code @AgentScopeBean}
 * annotated methods) to access HTTP request headers and query parameters from the current
 * AGUI request. The context is set before agent resolution and cleared after processing.
 *
 * <p><b>Usage in agent factory:</b>
 * <pre>{@code
 * @AgentScopeBean
 * public ReActAgent myAgent() {
 *     String userId = AguiRequestContext.current().getHeader("X-User-Id");
 *     String tenantId = AguiRequestContext.current().getParameter("tenantId");
 *     return ReActAgent.builder()
 *         .name("MyAgent")
 *         .longTermMemory(memory.userId(userId))
 *         .build();
 * }
 * }</pre>
 *
 * <p><b>Thread safety:</b> The context is bound to the current thread via {@link ThreadLocal}.
 * It is set before {@code processor.process()} and cleared in a {@code finally} block,
 * ensuring no leaks across requests in thread pools.
 *
 * <p><b>Null safety:</b> {@link #current()} never returns {@code null}. When no context
 * is set (e.g., in tests or non-AGUI paths), it returns an empty context where all
 * getter methods return {@code null} or empty collections.
 */
public class AguiRequestContext {

    private static final ThreadLocal<AguiRequestContext> HOLDER = new ThreadLocal<>();

    private static final AguiRequestContext EMPTY =
            new AguiRequestContext(Collections.emptyMap(), Collections.emptyMap());

    private final Map<String, List<String>> headers;
    private final Map<String, List<String>> params;

    private AguiRequestContext(
            Map<String, List<String>> headers, Map<String, List<String>> params) {
        // Defensive copy with case-insensitive keys for headers
        TreeMap<String, List<String>> headersCopy = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        headersCopy.putAll(headers);
        this.headers = Collections.unmodifiableMap(headersCopy);
        this.params = Collections.unmodifiableMap(params);
    }

    /**
     * Initialize the request context for the current thread.
     *
     * <p>Must be paired with {@link #clear()} in a {@code finally} block.
     *
     * @param headers HTTP request headers
     * @param params  HTTP query parameters
     */
    public static void init(Map<String, List<String>> headers, Map<String, List<String>> params) {
        HOLDER.set(
                new AguiRequestContext(
                        headers != null ? headers : Collections.emptyMap(),
                        params != null ? params : Collections.emptyMap()));
    }

    /**
     * Get the current request context.
     *
     * <p>Never returns {@code null}. Returns an empty context when no context is set.
     *
     * @return The current request context, or an empty context
     */
    public static AguiRequestContext current() {
        AguiRequestContext ctx = HOLDER.get();
        return ctx != null ? ctx : EMPTY;
    }

    /**
     * Clear the request context for the current thread.
     *
     * <p>Must be called in a {@code finally} block after {@link #init}.
     */
    public static void clear() {
        HOLDER.remove();
    }

    // --- Header methods ---

    /**
     * Get the first value of the specified HTTP header.
     *
     * <p>Header name lookup is case-insensitive per HTTP specification.
     *
     * @param name The header name
     * @return The first header value, or {@code null} if not present
     */
    public String getHeader(String name) {
        List<String> values = headers.get(name);
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }

    /**
     * Get all values of the specified HTTP header.
     *
     * @param name The header name
     * @return All header values, or an empty list if not present
     */
    public List<String> getHeaders(String name) {
        List<String> values = headers.get(name);
        return values != null ? values : Collections.emptyList();
    }

    /**
     * Get all HTTP headers.
     *
     * @return An unmodifiable map of all headers
     */
    public Map<String, List<String>> getAllHeaders() {
        return headers;
    }

    // --- Parameter methods ---

    /**
     * Get the first value of the specified query parameter.
     *
     * @param name The parameter name
     * @return The first parameter value, or {@code null} if not present
     */
    public String getParameter(String name) {
        List<String> values = params.get(name);
        return (values != null && !values.isEmpty()) ? values.get(0) : null;
    }

    /**
     * Get all values of the specified query parameter.
     *
     * @param name The parameter name
     * @return All parameter values, or an empty list if not present
     */
    public List<String> getParameters(String name) {
        List<String> values = params.get(name);
        return values != null ? values : Collections.emptyList();
    }

    /**
     * Get all query parameters.
     *
     * @return An unmodifiable map of all parameters
     */
    public Map<String, List<String>> getAllParameters() {
        return params;
    }
}
