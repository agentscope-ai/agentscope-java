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
package io.agentscope.core.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a formatted message ready for model consumption.
 * This class encapsulates the output from formatters and provides
 * type-safe access to message properties while maintaining compatibility
 * with the underlying Map-based structure expected by model APIs.
 */
public class FormattedMessage {
    private final Map<String, Object> data;

    public FormattedMessage(Map<String, Object> data) {
        this.data = data != null ? new HashMap<>(data) : new HashMap<>();
    }

    public String getRole() {
        return getString("role");
    }

    public String getName() {
        return getString("name");
    }

    public Object getContent() {
        return data.get("content");
    }

    public String getContentAsString() {
        Object content = getContent();
        return content != null ? content.toString() : null;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getContentAsList() {
        Object content = getContent();
        if (content instanceof List) {
            return (List<Map<String, Object>>) content;
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> getToolCalls() {
        Object toolCalls = data.get("tool_calls");
        if (toolCalls instanceof List) {
            return (List<Map<String, Object>>) toolCalls;
        }
        return Collections.emptyList();
    }

    public String getToolCallId() {
        return getString("tool_call_id");
    }

    public boolean hasToolCalls() {
        return !getToolCalls().isEmpty();
    }

    public boolean isRole(String role) {
        return role != null && role.equals(getRole());
    }

    /**
     * Get the underlying Map representation for direct model API usage.
     * This allows backward compatibility with existing code that expects Map objects.
     */
    public Map<String, Object> asMap() {
        return Collections.unmodifiableMap(data);
    }

    /**
     * Create a copy of this FormattedMessage with additional or modified properties.
     */
    public FormattedMessage withProperty(String key, Object value) {
        Map<String, Object> newData = new HashMap<>(data);
        newData.put(key, value);
        return new FormattedMessage(newData);
    }

    /**
     * Create a copy of this FormattedMessage without specific properties.
     */
    public FormattedMessage withoutProperty(String key) {
        Map<String, Object> newData = new HashMap<>(data);
        newData.remove(key);
        return new FormattedMessage(newData);
    }

    private String getString(String key) {
        Object value = data.get(key);
        return value != null ? value.toString() : null;
    }

    @Override
    public String toString() {
        return "FormattedMessage{"
                + "role='"
                + getRole()
                + '\''
                + ", content="
                + getContent()
                + ", hasToolCalls="
                + hasToolCalls()
                + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FormattedMessage that = (FormattedMessage) o;
        return data.equals(that.data);
    }

    @Override
    public int hashCode() {
        return data.hashCode();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final Map<String, Object> data = new HashMap<>();

        public Builder role(String role) {
            data.put("role", role);
            return this;
        }

        public Builder name(String name) {
            data.put("name", name);
            return this;
        }

        public Builder content(Object content) {
            data.put("content", content);
            return this;
        }

        public Builder toolCalls(List<Map<String, Object>> toolCalls) {
            data.put("tool_calls", toolCalls);
            return this;
        }

        public Builder toolCallId(String toolCallId) {
            data.put("tool_call_id", toolCallId);
            return this;
        }

        public Builder property(String key, Object value) {
            data.put(key, value);
            return this;
        }

        public FormattedMessage build() {
            return new FormattedMessage(data);
        }
    }
}
