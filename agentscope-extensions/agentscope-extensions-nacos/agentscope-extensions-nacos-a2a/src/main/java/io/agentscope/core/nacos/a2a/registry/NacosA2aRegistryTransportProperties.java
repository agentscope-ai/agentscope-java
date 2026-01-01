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

package io.agentscope.core.nacos.a2a.registry;

import com.alibaba.nacos.common.utils.StringUtils;
import io.agentscope.core.nacos.a2a.registry.constants.Constants;

/**
 * Properties for A2A Transports Endpoint registry to Nacos.
 *
 * <p>Used to configure A2A Interface(Endpoint) which registers to Nacos, these properties will be merged to `transport`
 * and `url`. The format is:
 * {@code [protocol://]host:port[/path][?query]}.
 *
 * <p>When {@link #protocol} is null, protocol will be set to {@link Constants#PROTOCOL_TYPE_HTTP}.
 *
 * <p>When {@link #protocol} is {@link Constants#PROTOCOL_TYPE_HTTP} (including when set by null) and the
 * {@link #supportTls} is {@code true}, it will determine whether to transform {@link #protocol} to
 * {@link Constants#PROTOCOL_TYPE_HTTPS}.
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
     * Creates a new builder instance for {@link NacosA2aRegistryTransportProperties}.
     *
     * @return builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * The Builder for {@link NacosA2aRegistryTransportProperties}.
     *
     * <p>Provides a fluent API for constructing instances with various configuration options.
     */
    public static class Builder {

        /** The transport identifier for the A2A endpoint. */
        private String transport;

        /** The host address for the A2A endpoint. */
        private String host;

        /** The port number for the A2A endpoint. */
        private int port;

        /** The path component for the A2A endpoint URL. */
        private String path;

        /** Flag indicating whether the A2A endpoint supports TLS encryption. */
        private boolean supportTls;

        /** The protocol for the A2A endpoint (e.g., http, https). */
        private String protocol;

        /** The query string for the A2A endpoint URL. */
        private String query;

        /**
         * Sets the transport identifier for the A2A endpoint.
         *
         * @param transport the transport identifier
         * @return the builder instance for method chaining
         */
        public Builder transport(String transport) {
            this.transport = transport;
            return this;
        }

        /**
         * Sets the host address for the A2A endpoint.
         *
         * @param host the host address
         * @return the builder instance for method chaining
         */
        public Builder host(String host) {
            this.host = host;
            return this;
        }

        /**
         * Sets the port number for the A2A endpoint.
         *
         * @param port the port number
         * @return the builder instance for method chaining
         */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /**
         * Sets the path component for the A2A endpoint URL.
         *
         * @param path the path component
         * @return the builder instance for method chaining
         */
        public Builder path(String path) {
            this.path = path;
            return this;
        }

        /**
         * Sets whether the A2A endpoint supports TLS encryption.
         *
         * @param supportTls {@code true} if TLS is supported, {@code false} otherwise
         * @return the builder instance for method chaining
         */
        public Builder supportTls(boolean supportTls) {
            this.supportTls = supportTls;
            return this;
        }

        /**
         * Sets the protocol for the A2A endpoint (e.g., http, https).
         *
         * @param protocol the protocol to use
         * @return the builder instance for method chaining
         */
        public Builder protocol(String protocol) {
            this.protocol = protocol;
            return this;
        }

        /**
         * Sets the query string for the A2A endpoint URL.
         *
         * @param query the query string
         * @return the builder instance for method chaining
         */
        public Builder query(String query) {
            this.query = query;
            return this;
        }

        /**
         * Builds a new instance of {@link NacosA2aRegistryTransportProperties} with the configured properties.
         *
         * <p>Validates that required properties (transport) are not empty.
         *
         * @return a new instance of {@link NacosA2aRegistryTransportProperties}
         * @throws IllegalArgumentException if transport is empty
         */
        public NacosA2aRegistryTransportProperties build() {
            if (StringUtils.isEmpty(transport)) {
                throw new IllegalArgumentException("A2A Endpoint `transport` can not be empty.");
            }
            return new NacosA2aRegistryTransportProperties(
                    transport, host, port, path, supportTls, protocol, query);
        }
    }
}
