/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * You may not use this file except in compliance with the License.
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

package io.agentscope.core.nacos.a2a.registry;

import com.alibaba.nacos.common.utils.StringUtils;
import io.agentscope.core.nacos.a2a.registry.constants.Constants;

/**
 * Properties for A2A Transports Endpoint registry to Nacos.
 *
 * <p>Used to configure A2A Interface(Endpoint) which register to Nacos, these properties will be merged to `transport`
 * and `url`. The format is:
 * {@code [protocol://]host:port[/path][?query]}.
 *
 * <p>When {@link #protocol} is null, protocol will be set to {@link Constants#PROTOCOL_TYPE_HTTP}.
 *
 * <p>When {@link #protocol} is {@link Constants#PROTOCOL_TYPE_HTTP} (include set by null) and the {@link #supportTls}
 * is {@code true} will determine whether transform {@link #protocol} to {@link Constants#PROTOCOL_TYPE_HTTPS}.
 */
public record NacosA2aRegistryTransportProperties(
        String transport,
        String host,
        int port,
        String path,
        boolean supportTls,
        String protocol,
        String query) {

    /**
     * New builder instance for {@link NacosA2aRegistryTransportProperties}.
     *
     * @return builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {

        private String transport;

        private String host;

        private int port;

        private String path;

        private boolean supportTls;

        private String protocol;

        private String query;

        public Builder transport(String transport) {
            this.transport = transport;
            return this;
        }

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(int port) {
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

        public Builder protocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        public Builder query(String query) {
            this.query = query;
            return this;
        }

        public NacosA2aRegistryTransportProperties build() {
            if (StringUtils.isEmpty(transport)) {
                throw new IllegalArgumentException("A2a Endpoint `transport` can not be empty.");
            }
            if (StringUtils.isEmpty(host)) {
                throw new IllegalArgumentException("A2a Endpoint `host` can not be empty.");
            }
            return new NacosA2aRegistryTransportProperties(
                    transport, host, port, path, supportTls, protocol, query);
        }
    }
}
