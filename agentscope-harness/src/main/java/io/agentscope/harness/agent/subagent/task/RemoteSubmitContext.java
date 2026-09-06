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
package io.agentscope.harness.agent.subagent.task;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Context sent with a remote task submission (permissions, streaming preferences, parent identity).
 */
public final class RemoteSubmitContext {

    private final String userId;
    private final String parentSessionId;
    private final boolean stream;
    private final String detail;
    private final List<Map<String, String>> denyRules;
    private final Map<String, Object> attributes;

    private RemoteSubmitContext(Builder builder) {
        this.userId = builder.userId;
        this.parentSessionId = builder.parentSessionId;
        this.stream = builder.stream;
        this.detail = builder.detail != null ? builder.detail : "status";
        this.denyRules =
                builder.denyRules == null || builder.denyRules.isEmpty()
                        ? List.of()
                        : List.copyOf(builder.denyRules);
        this.attributes =
                builder.attributes == null || builder.attributes.isEmpty()
                        ? Map.of()
                        : Collections.unmodifiableMap(new LinkedHashMap<>(builder.attributes));
    }

    public static Builder builder() {
        return new Builder();
    }

    public static RemoteSubmitContext empty() {
        return builder().build();
    }

    public String userId() {
        return userId;
    }

    public String parentSessionId() {
        return parentSessionId;
    }

    public boolean stream() {
        return stream;
    }

    public String detail() {
        return detail;
    }

    public List<Map<String, String>> denyRules() {
        return denyRules;
    }

    /**
     * Caller-defined attributes forwarded as {@code context.attributes}. Never null; empty when the
     * parent supplied none.
     */
    public Map<String, Object> attributes() {
        return attributes;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (userId != null) {
            m.put("user_id", userId);
        }
        if (parentSessionId != null) {
            m.put("parent_session_id", parentSessionId);
        }
        m.put("stream", stream);
        m.put("detail", detail);
        if (!denyRules.isEmpty()) {
            m.put("deny_rules", denyRules);
        }
        if (!attributes.isEmpty()) {
            m.put("attributes", attributes);
        }
        return Collections.unmodifiableMap(m);
    }

    public static final class Builder {
        private String userId;
        private String parentSessionId;
        private boolean stream = true;
        private String detail = "status";
        private List<Map<String, String>> denyRules;
        private Map<String, Object> attributes;

        public Builder userId(String userId) {
            this.userId = userId;
            return this;
        }

        public Builder parentSessionId(String parentSessionId) {
            this.parentSessionId = parentSessionId;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder detail(String detail) {
            this.detail = detail;
            return this;
        }

        public Builder denyRules(List<Map<String, String>> denyRules) {
            this.denyRules = denyRules;
            return this;
        }

        /**
         * Caller-defined attributes sent as {@code context.attributes}, kept apart from the
         * protocol's own context fields. Values must be JSON-serializable.
         */
        public Builder attributes(Map<String, Object> attributes) {
            this.attributes = attributes;
            return this;
        }

        public RemoteSubmitContext build() {
            Objects.requireNonNull(detail, "detail");
            return new RemoteSubmitContext(this);
        }
    }
}
