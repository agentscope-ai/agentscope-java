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
package io.agentscope.harness.agent;

import io.agentscope.core.session.Session;
import io.agentscope.core.state.SessionKey;
import io.agentscope.harness.agent.sandbox.SandboxContext;
import java.util.HashMap;
import java.util.Map;

/**
 * Runtime context passed into agent.call() to carry session-scoped metadata.
 *
 * <p>This context is available throughout the reasoning loop (hooks, tools) but is
 * NOT persisted to storage media.
 */
public class RuntimeContext {

    private final String sessionId;
    private final String userId;
    private final Session session;
    private final SessionKey sessionKey;
    private final Map<String, Object> extra;
    private final SandboxContext sandboxContext;

    private RuntimeContext(Builder builder) {
        this.sessionId = builder.sessionId;
        this.userId = builder.userId;
        this.session = builder.session;
        this.sessionKey = builder.sessionKey;
        this.extra = Map.copyOf(builder.extra);
        this.sandboxContext = builder.sandboxContext;
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

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) extra.get(key);
    }

    public Map<String, Object> getExtra() {
        return extra;
    }

    /**
     * Returns the sandbox context for this call.
     *
     * @return sandbox context, or {@code null} if sandbox is not configured
     */
    public SandboxContext getSandboxContext() {
        return sandboxContext;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String sessionId;
        private String userId;
        private Session session;
        private SessionKey sessionKey;
        private final Map<String, Object> extra = new HashMap<>();
        private SandboxContext sandboxContext;

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
            this.extra.put(key, value);
            return this;
        }

        public Builder putAll(Map<String, Object> extras) {
            if (extras != null) {
                this.extra.putAll(extras);
            }
            return this;
        }

        /**
         * Sets the sandbox context for this call.
         *
         * @param sandboxContext sandbox configuration and state
         * @return this builder
         */
        public Builder sandboxContext(SandboxContext sandboxContext) {
            this.sandboxContext = sandboxContext;
            return this;
        }

        public RuntimeContext build() {
            return new RuntimeContext(this);
        }
    }
}
