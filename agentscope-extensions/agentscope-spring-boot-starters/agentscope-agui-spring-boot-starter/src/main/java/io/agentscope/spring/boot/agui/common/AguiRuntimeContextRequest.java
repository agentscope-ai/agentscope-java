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
package io.agentscope.spring.boot.agui.common;

import io.agentscope.core.agui.model.RunAgentInput;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Request-scoped data available when resolving an AG-UI {@link
 * io.agentscope.core.agent.RuntimeContext}.
 */
public final class AguiRuntimeContextRequest {

    /** Transport that received the AG-UI request. */
    public enum Transport {
        MVC,
        WEBFLUX,
        CUSTOM
    }

    private final RunAgentInput input;
    private final String headerAgentId;
    private final String pathAgentId;
    private final Transport transport;
    private final String method;
    private final String path;
    private final Map<String, List<String>> headers;
    private final Map<String, List<String>> queryParams;
    private final Object nativeRequest;

    private AguiRuntimeContextRequest(Builder builder) {
        this.input = Objects.requireNonNull(builder.input, "input cannot be null");
        this.headerAgentId = builder.headerAgentId;
        this.pathAgentId = builder.pathAgentId;
        this.transport = builder.transport != null ? builder.transport : Transport.CUSTOM;
        this.method = builder.method;
        this.path = builder.path;
        this.headers = immutableMultiValueMap(builder.headers);
        this.queryParams = immutableMultiValueMap(builder.queryParams);
        this.nativeRequest = builder.nativeRequest;
    }

    public RunAgentInput getInput() {
        return input;
    }

    public String getHeaderAgentId() {
        return headerAgentId;
    }

    public String getPathAgentId() {
        return pathAgentId;
    }

    public Transport getTransport() {
        return transport;
    }

    public String getMethod() {
        return method;
    }

    public String getPath() {
        return path;
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    public String firstHeader(String name) {
        return firstValue(headers, name, true);
    }

    public Map<String, List<String>> getQueryParams() {
        return queryParams;
    }

    public String firstQueryParam(String name) {
        return firstValue(queryParams, name, false);
    }

    public Object getNativeRequest() {
        return nativeRequest;
    }

    public <T> T getNativeRequest(Class<T> type) {
        if (type == null || !type.isInstance(nativeRequest)) {
            return null;
        }
        return type.cast(nativeRequest);
    }

    public static Builder builder() {
        return new Builder();
    }

    private static String firstValue(
            Map<String, List<String>> values, String name, boolean ignoreCase) {
        if (name == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> entry : values.entrySet()) {
            boolean matches =
                    ignoreCase
                            ? entry.getKey().equalsIgnoreCase(name)
                            : entry.getKey().equals(name);
            if (matches && !entry.getValue().isEmpty()) {
                return entry.getValue().get(0);
            }
        }
        return null;
    }

    private static Map<String, List<String>> immutableMultiValueMap(
            Map<String, List<String>> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        values.forEach(
                (key, value) -> {
                    if (key != null && value != null) {
                        copy.put(key, Collections.unmodifiableList(new ArrayList<>(value)));
                    }
                });
        return Collections.unmodifiableMap(copy);
    }

    /** Builder for {@link AguiRuntimeContextRequest}. */
    public static final class Builder {

        private RunAgentInput input;
        private String headerAgentId;
        private String pathAgentId;
        private Transport transport;
        private String method;
        private String path;
        private Map<String, List<String>> headers;
        private Map<String, List<String>> queryParams;
        private Object nativeRequest;

        public Builder input(RunAgentInput input) {
            this.input = input;
            return this;
        }

        public Builder headerAgentId(String headerAgentId) {
            this.headerAgentId = headerAgentId;
            return this;
        }

        public Builder pathAgentId(String pathAgentId) {
            this.pathAgentId = pathAgentId;
            return this;
        }

        public Builder transport(Transport transport) {
            this.transport = transport;
            return this;
        }

        public Builder method(String method) {
            this.method = method;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder headers(Map<String, List<String>> headers) {
            this.headers = headers;
            return this;
        }

        public Builder queryParams(Map<String, List<String>> queryParams) {
            this.queryParams = queryParams;
            return this;
        }

        public Builder nativeRequest(Object nativeRequest) {
            this.nativeRequest = nativeRequest;
            return this;
        }

        public AguiRuntimeContextRequest build() {
            return new AguiRuntimeContextRequest(this);
        }
    }
}
