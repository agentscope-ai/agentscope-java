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

package io.agentscope.core.a2a.agent;

import io.agentscope.core.agent.RuntimeContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-request options for {@link A2aAgent}.
 */
public class A2aRequestOptions {

    private static final A2aRequestOptions EMPTY = builder().build();

    private final RuntimeContext runtimeContext;

    private final Map<String, String> headers;

    private final Map<String, Object> metadata;

    private final String agentId;

    private A2aRequestOptions(
            RuntimeContext runtimeContext,
            Map<String, String> headers,
            Map<String, Object> metadata,
            String agentId) {
        this.runtimeContext = runtimeContext;
        this.headers = immutableStringMap(headers);
        this.metadata = immutableObjectMap(metadata);
        this.agentId = agentId;
    }

    public static A2aRequestOptions empty() {
        return EMPTY;
    }

    public static Builder builder() {
        return new Builder();
    }

    public RuntimeContext runtimeContext() {
        return runtimeContext;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public Map<String, Object> metadata() {
        return metadata;
    }

    public String agentId() {
        return agentId;
    }

    private static Map<String, String> immutableStringMap(Map<String, String> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static Map<String, Object> immutableObjectMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    public static class Builder {

        private RuntimeContext runtimeContext;

        private Map<String, String> headers = Collections.emptyMap();

        private Map<String, Object> metadata = Collections.emptyMap();

        private String agentId;

        public Builder runtimeContext(RuntimeContext runtimeContext) {
            this.runtimeContext = runtimeContext;
            return this;
        }

        public Builder headers(Map<String, String> headers) {
            this.headers = headers;
            return this;
        }

        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata;
            return this;
        }

        public Builder agentId(String agentId) {
            this.agentId = agentId;
            return this;
        }

        public A2aRequestOptions build() {
            return new A2aRequestOptions(runtimeContext, headers, metadata, agentId);
        }
    }
}
