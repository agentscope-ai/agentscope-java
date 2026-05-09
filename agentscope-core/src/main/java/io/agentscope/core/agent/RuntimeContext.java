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
package io.agentscope.core.agent;

import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.core.tool.ContextStore;
import io.agentscope.core.tool.ToolExecutionContext;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Per-call metadata for an agent run: session-scoped fields plus a thread-safe attribute bag and
 * an optional {@link ToolExecutionContext} (tool-POJO / DI layer).
 *
 * <p>Attributes are not persisted. Hooks and tools may read and update the same instance for the
 * duration of a single {@code call}.
 */
public class RuntimeContext {

    private static final String TYPED_DEFAULT_KEY = "";

    private final String sessionId;
    private final String userId;
    private final Session session;
    private final SessionKey sessionKey;

    /** String-keyed extras (legacy and generic extension). */
    private final ConcurrentMap<String, Object> stringAttributes;

    /**
     * Typed layer: class -&gt; (key -&gt; value). For singleton-typed access, use {@link
     * #TYPED_DEFAULT_KEY}.
     */
    private final ConcurrentMap<Class<?>, ConcurrentMap<String, Object>> typedAttributes;

    private final ToolExecutionContext toolExecutionContext;

    private RuntimeContext(Builder builder) {
        this.sessionId = builder.sessionId;
        this.userId = builder.userId;
        this.session = builder.session;
        this.sessionKey = builder.sessionKey;
        this.stringAttributes = new ConcurrentHashMap<>();
        this.typedAttributes = new ConcurrentHashMap<>();
        this.toolExecutionContext = builder.toolExecutionContext;
        if (builder.stringExtras != null) {
            this.stringAttributes.putAll(builder.stringExtras);
        }
        for (Map.Entry<Class<?>, Object> e : builder.typedSingletons.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Class<Object> type = (Class<Object>) e.getKey();
            putValue(TYPED_DEFAULT_KEY, type, e.getValue());
        }
    }

    /**
     * Shallow, mutable empty context (null session fields, empty attribute maps, no tool context).
     */
    public static RuntimeContext empty() {
        return new Builder().build();
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public Session getSession() {
        return session;
    }

    public SessionKey getSessionKey() {
        return sessionKey;
    }

    /**
     * Returns the tool execution context provided at build time, if any.
     *
     * <p>Does not include runtime attribute projections; use {@link #asToolExecutionContext()}.
     */
    public ToolExecutionContext getToolExecutionContext() {
        return toolExecutionContext;
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        if (key == null) {
            return null;
        }
        return (T) stringAttributes.get(key);
    }

    public void put(String key, Object value) {
        if (key == null) {
            return;
        }
        if (value == null) {
            stringAttributes.remove(key);
        } else {
            stringAttributes.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T get(Class<T> type) {
        if (type == null) {
            return null;
        }
        T v = getValue(TYPED_DEFAULT_KEY, type);
        if (v != null) {
            return v;
        }
        // Allow accessing this RuntimeContext itself
        if (type == RuntimeContext.class) {
            return (T) this;
        }
        return null;
    }

    public <T> void put(Class<T> type, T value) {
        if (type == null) {
            return;
        }
        if (value == null) {
            removeTyped(type, TYPED_DEFAULT_KEY);
        } else {
            putValue(TYPED_DEFAULT_KEY, type, value);
        }
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Class<T> type) {
        if (key == null || type == null) {
            return null;
        }
        T v = getValue(key, type);
        if (v != null) {
            return v;
        }
        if (TYPED_DEFAULT_KEY.equals(key) && type == RuntimeContext.class) {
            return (T) this;
        }
        return null;
    }

    public <T> void put(String key, Class<T> type, T value) {
        if (key == null || type == null) {
            return;
        }
        if (value == null) {
            removeTyped(type, key);
        } else {
            putValue(key, type, value);
        }
    }

    /**
     * View of string-keyed attributes; mutating the returned map affects this context.
     *
     * <p>Typed {@link #get(Class)} values are not included; use type-based accessors.
     */
    public Map<String, Object> getExtra() {
        return stringAttributes;
    }

    private <T> void putValue(String key, Class<T> type, Object value) {
        typedAttributes.computeIfAbsent(type, k -> new ConcurrentHashMap<>()).put(key, value);
    }

    private <T> T getValue(String key, Class<T> type) {
        Map<String, Object> m = typedAttributes.get(type);
        if (m == null) {
            return null;
        }
        Object o = m.get(key);
        if (o == null) {
            return null;
        }
        return type.isInstance(o) ? type.cast(o) : null;
    }

    private <T> void removeTyped(Class<T> type, String key) {
        Map<String, Object> m = typedAttributes.get(type);
        if (m == null) {
            return;
        }
        m.remove(key);
        if (m.isEmpty()) {
            typedAttributes.remove(type);
        }
    }

    /**
     * Merges this context's data into a {@link ToolExecutionContext} for tool invocations.
     *
     * <p>Order: this instance is registered and exposed first (highest priority in {@link
     * ToolExecutionContext#merge}, then a {@link ContextStore} for typed and string attributes,
     * then stores from the nested {@link #getToolExecutionContext()} (if any).
     */
    public ToolExecutionContext asToolExecutionContext() {
        ToolExecutionContext.Builder b = ToolExecutionContext.builder();
        b.addStore(new DefaultMutableContextStore(this));
        if (toolExecutionContext != null) {
            for (ContextStore s : toolExecutionContext.getStores()) {
                if (s != null) {
                    b.addStore(s);
                }
            }
        }
        return b.build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String sessionId;
        private String userId;
        private Session session;
        private SessionKey sessionKey;
        private Map<String, Object> stringExtras;
        private final Map<Class<?>, Object> typedSingletons = new HashMap<>();
        private ToolExecutionContext toolExecutionContext;

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder session(Session session) {
            this.session = session;
            return this;
        }

        public Builder sessionKey(SessionKey sessionKey) {
            this.sessionKey = sessionKey;
            return this;
        }

        public Builder put(String key, Object value) {
            if (this.stringExtras == null) {
                this.stringExtras = new ConcurrentHashMap<>();
            }
            this.stringExtras.put(key, value);
            return this;
        }

        public Builder putAll(Map<String, Object> extras) {
            if (extras == null || extras.isEmpty()) {
                return this;
            }
            if (this.stringExtras == null) {
                this.stringExtras = new ConcurrentHashMap<>();
            }
            this.stringExtras.putAll(extras);
            return this;
        }

        public <T> Builder put(Class<T> type, T value) {
            if (type != null) {
                this.typedSingletons.put(type, value);
            }
            return this;
        }

        /**
         * Nests a {@link ToolExecutionContext} (e.g. agent builder-level tool DI) that will be
         * visible at lower priority than runtime attributes in {@link #asToolExecutionContext()}.
         */
        public Builder toolExecutionContext(ToolExecutionContext toolExecutionContext) {
            this.toolExecutionContext = toolExecutionContext;
            return this;
        }

        public RuntimeContext build() {
            return new RuntimeContext(this);
        }
    }

    /**
     * Merged view of this {@link RuntimeContext} for the tool stack: first checks typed, then
     * string map for legacy {@link #get(String)} keys, then defers to delegate stores.
     */
    private static final class DefaultMutableContextStore implements ContextStore {

        private final RuntimeContext runtimeContext;

        private DefaultMutableContextStore(RuntimeContext runtimeContext) {
            this.runtimeContext = runtimeContext;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(String key, Class<T> type) {
            T t = runtimeContext.getValue(key, type);
            if (t != null) {
                return t;
            }
            if (TYPED_DEFAULT_KEY.equals(key) && type == RuntimeContext.class) {
                return (T) runtimeContext;
            }
            Object fromString = runtimeContext.stringAttributes.get(key);
            if (type.isInstance(fromString)) {
                return (T) fromString;
            }
            return null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Class<T> type) {
            T t = get(TYPED_DEFAULT_KEY, type);
            if (t != null) {
                return t;
            }
            if (type == RuntimeContext.class) {
                return (T) runtimeContext;
            }
            return null;
        }

        @Override
        public boolean contains(String key, Class<?> type) {
            return get(key, type) != null;
        }

        @Override
        public boolean contains(Class<?> type) {
            return get(type) != null;
        }
    }
}
