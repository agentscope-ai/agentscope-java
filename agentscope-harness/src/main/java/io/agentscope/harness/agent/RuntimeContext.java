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
import java.util.Map;

/**
 * Harness-facing runtime context: wraps {@link io.agentscope.core.agent.RuntimeContext} and adds
 * a typed {@link #getSandboxContext() sandbox} view (stored as a typed attribute on the core
 * object).
 *
 * <p>Pass {@link #toCore()} to {@link io.agentscope.core.ReActAgent#call} when calling the
 * delegate directly.
 */
public final class RuntimeContext {

    private final io.agentscope.core.agent.RuntimeContext core;

    private RuntimeContext(io.agentscope.core.agent.RuntimeContext core) {
        this.core = core;
    }

    public io.agentscope.core.agent.RuntimeContext toCore() {
        return core;
    }

    public String getSessionId() {
        return core.getSessionId();
    }

    public String getUserId() {
        return core.getUserId();
    }

    public Session getSession() {
        return core.getSession();
    }

    public SessionKey getSessionKey() {
        return core.getSessionKey();
    }

    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) core.get(key);
    }

    public Map<String, Object> getExtra() {
        return core.getExtra();
    }

    public SandboxContext getSandboxContext() {
        return core.get(SandboxContext.class);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final io.agentscope.core.agent.RuntimeContext.Builder b =
                io.agentscope.core.agent.RuntimeContext.builder();

        public Builder sessionId(String sessionId) {
            b.sessionId(sessionId);
            return this;
        }

        public Builder userId(String userId) {
            b.userId(userId);
            return this;
        }

        public Builder session(Session session) {
            b.session(session);
            return this;
        }

        public Builder sessionKey(SessionKey sessionKey) {
            b.sessionKey(sessionKey);
            return this;
        }

        public Builder put(String key, Object value) {
            b.put(key, value);
            return this;
        }

        public Builder putAll(Map<String, Object> extras) {
            b.putAll(extras);
            return this;
        }

        public Builder sandboxContext(SandboxContext sandboxContext) {
            b.put(SandboxContext.class, sandboxContext);
            return this;
        }

        public RuntimeContext build() {
            return new RuntimeContext(b.build());
        }
    }
}
