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

/*
 * ============================================================================
 * 【学习笔记】AgentScope 运行时上下文 - RuntimeContext
 * ============================================================================
 *
 * 一、职责定位
 * -----------
 * RuntimeContext 是「单次调用的上下文容器」，区别于持久化的 AgentState：
 *   - 携带本次请求的会话/用户标识
 *   - 传递调用链上的临时属性
 *   - 不持久化，调用结束即销毁
 *
 * 二、核心字段
 * -----------
 *   RuntimeContext
 *       ├── sessionId            (会话ID，用于路由到正确的AgentState)
 *       ├── userId               (用户ID，支持多租户)
 *       ├── agentState           (当前调用绑定的AgentState引用)
 *       ├── stringAttributes     (字符串键值属性，通用扩展)
 *       ├── typedAttributes      (类型化属性，类型安全的扩展)
 *       └── toolExecutionContext (工具执行上下文，DI/POJO注入)
 *
 * 三、与 AgentState 的区别
 * -----------------------
 *   维度        │ RuntimeContext          │ AgentState
 *   ────────────┼─────────────────────────┼────────────────────
 *   生命周期    │ 单次 call()             │ 跨多次 call()
 *   持久化      │ 否                      │ 是（通过AgentStateStore）
 *   内容        │ 请求元数据、临时属性     │ 对话历史、权限状态
 *   并发        │ 每次调用独立实例        │ 同session共享
 *
 * 四、使用示例
 * -----------
 *   // 构建上下文
 *   RuntimeContext ctx = RuntimeContext.builder()
 *       .sessionId("session-123")
 *       .userId("user-456")
 *       .put(MyService.class, myService)  // 注入服务
 *       .build();
 *
 *   // 在工具中获取
 *   @Tool
 *   public String myTool(RuntimeContext ctx) {
 *       MyService svc = ctx.get(MyService.class);
 *       String session = ctx.getSessionId();
 *       ...
 *   }
 *
 * 五、属性传递
 * -----------
 *   // 字符串键值（通用）
 *   ctx.put("requestId", "req-123");
 *   String reqId = ctx.get("requestId");
 *
 *   // 类型化（推荐，类型安全）
 *   ctx.put(HttpServletRequest.class, request);
 *   HttpServletRequest req = ctx.get(HttpServletRequest.class);
 *
 * 六、线程安全
 * -----------
 *   使用 ConcurrentHashMap 存储属性
 *   同一 RuntimeContext 可在中间件/工具间安全传递
 *
 * ============================================================================
 */

package io.agentscope.core.agent;

import io.agentscope.core.state.AgentState;
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

    /**
     * Call-scoped {@link AgentState} for the active {@code (userId, sessionId)} slot. Set once at
     * call entry by the agent and read by middlewares / tools that need the live conversational
     * state during the call (instead of {@code agent.getAgentState()}, which is not call-scoped
     * under concurrency). {@code null} outside of a call.
     */
    private volatile AgentState agentState;

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
        this.stringAttributes = new ConcurrentHashMap<>();
        this.typedAttributes = new ConcurrentHashMap<>();
        this.toolExecutionContext = builder.toolExecutionContext;
        this.agentState = builder.agentState;
        if (builder.stringExtras != null) {
            this.stringAttributes.putAll(builder.stringExtras);
        }
        for (Map.Entry<Class<?>, Map<String, Object>> e : builder.typedValues.entrySet()) {
            Class<?> type = e.getKey();
            Map<String, Object> values = e.getValue();
            if (type == null || values == null || values.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, Object> typedEntry : values.entrySet()) {
                if (typedEntry.getKey() == null || typedEntry.getValue() == null) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Class<Object> typedClass = (Class<Object>) type;
                putValue(typedEntry.getKey(), typedClass, typedEntry.getValue());
            }
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

    /**
     * Returns the call-scoped {@link AgentState} for this run, or {@code null} when accessed
     * outside of an active {@code call()}. Prefer this over {@code agent.getAgentState()} from
     * middlewares and tools so the correct session's state is used under concurrency.
     */
    public AgentState getAgentState() {
        return agentState;
    }

    /**
     * Installs the call-scoped {@link AgentState}. Called by the agent at call entry; not part of
     * the public tool/middleware contract.
     */
    public void setAgentState(AgentState agentState) {
        this.agentState = agentState;
    }

    /**
     * Resolves the live {@link AgentState} for the current call, preferring the call-scoped state
     * carried on {@code ctx} (concurrency-safe) and falling back to {@code fallbackAgent}'s state
     * only when the context carries none. Middlewares and tools should use this instead of calling
     * {@code agent.getAgentState()} directly, which is not call-scoped under concurrency.
     *
     * @param ctx the per-call runtime context (may be {@code null})
     * @param fallbackAgent the agent to fall back to (may be {@code null})
     * @return the resolved {@link AgentState}, or {@code null} if neither source provides one
     */
    public static AgentState resolveAgentState(RuntimeContext ctx, Agent fallbackAgent) {
        AgentState s = ctx != null ? ctx.getAgentState() : null;
        if (s != null) {
            return s;
        }
        return fallbackAgent != null ? fallbackAgent.getAgentState() : null;
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
        return getAssignableValue(TYPED_DEFAULT_KEY, type);
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
        T assignable = getAssignableValue(key, type);
        if (assignable != null) {
            return assignable;
        }
        Object fromString = stringAttributes.get(key);
        if (type.isInstance(fromString)) {
            return (T) fromString;
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

    public static Builder builder(RuntimeContext source) {
        return new Builder().from(source);
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

    private <T> T getAssignableValue(String key, Class<T> type) {
        if (type == null) {
            return null;
        }
        T candidate = null;
        Class<?> candidateType = null;
        for (Map.Entry<Class<?>, ConcurrentMap<String, Object>> entry :
                typedAttributes.entrySet()) {
            Class<?> storedType = entry.getKey();
            if (storedType == null || !type.isAssignableFrom(storedType)) {
                continue;
            }
            Map<String, Object> values = entry.getValue();
            if (values == null) {
                continue;
            }
            Object o = values.get(key);
            if (o == null || !type.isInstance(o)) {
                continue;
            }
            if (candidate == null) {
                candidate = type.cast(o);
                candidateType = storedType;
                continue;
            }
            if (candidateType.equals(storedType)) {
                continue;
            }
            if (candidateType.isAssignableFrom(storedType)) {
                candidate = type.cast(o);
                candidateType = storedType;
            }
        }
        return candidate;
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
        private Map<String, Object> stringExtras;
        private final Map<Class<?>, Map<String, Object>> typedValues = new HashMap<>();
        private ToolExecutionContext toolExecutionContext;
        private AgentState agentState;

        public Builder sessionId(String sessionId) {
            this.sessionId = sessionId;
            return this;
        }

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder agentState(AgentState agentState) {
            this.agentState = agentState;
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
            return put(TYPED_DEFAULT_KEY, type, value);
        }

        public <T> Builder put(String key, Class<T> type, T value) {
            if (key == null || type == null || value == null) {
                return this;
            }
            this.typedValues.computeIfAbsent(type, k -> new HashMap<>()).put(key, value);
            return this;
        }

        public Builder from(RuntimeContext source) {
            if (source == null) {
                return this;
            }
            this.sessionId = source.sessionId;
            this.userId = source.userId;
            this.agentState = source.agentState;
            this.toolExecutionContext = source.toolExecutionContext;
            if (!source.stringAttributes.isEmpty()) {
                this.stringExtras = new ConcurrentHashMap<>(source.stringAttributes);
            }
            for (Map.Entry<Class<?>, ConcurrentMap<String, Object>> entry :
                    source.typedAttributes.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isEmpty()) {
                    continue;
                }
                this.typedValues.put(entry.getKey(), new HashMap<>(entry.getValue()));
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
            return runtimeContext.get(key, type);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T get(Class<T> type) {
            return runtimeContext.get(type);
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
