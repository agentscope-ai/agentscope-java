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

package io.agentscope.core.a2a.server.transport;

import java.util.HashMap;
import java.util.Map;

/**
 * Properties for transports.
 *
 * <p>Usage for auto-generate {@link io.a2a.spec.AgentCard} and do register to A2A Registries.
 */
public record TransportProperties(
        String transportType,
        String host,
        Integer port,
        String path,
        boolean supportTls,
        Map<String, Object> extra) {

    public static Builder builder(String transportType) {
        return new Builder(transportType);
    }

    public static class Builder {

        private final String transportType;

        private final Map<String, Object> extra;

        private String host;

        private Integer port;

        private String path;

        private boolean supportTls;

        private Builder(String transportType) {
            this.transportType = transportType;
            this.extra = new HashMap<>();
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(Integer port) {
            this.port = port;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder supportTls(boolean supportTls) {
            this.supportTls = supportTls;
            return this;
        }

        public Builder extra(String key, Object value) {
            this.extra.put(key, value);
            return this;
        }

        public Builder extra(Map<String, Object> extra) {
            this.extra.putAll(extra);
            return this;
        }

        public TransportProperties build() {
            return new TransportProperties(transportType, host, port, path, supportTls, extra);
        }
    }
}
