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

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.a2aproject.sdk.client.config.ClientConfig;
import org.a2aproject.sdk.client.transport.spi.ClientTransport;
import org.a2aproject.sdk.client.transport.spi.ClientTransportConfig;

/**
 * Config of A2A Agent.
 */
public record A2aAgentConfig(
        @SuppressWarnings("rawtypes") Map<Class, ClientTransportConfig> clientTransports,
        ClientConfig clientConfig,
        Map<String, String> defaultHeaders,
        Map<String, Object> defaultMetadata) {

    public A2aAgentConfig {
        defaultHeaders = immutableMap(defaultHeaders);
        defaultMetadata = immutableMap(defaultMetadata);
    }

    /**
     * Create a config using the public constructor shape available before the SDK 1.0 migration.
     *
     * @param clientTransports client transport configurations
     * @param clientConfig SDK client configuration
     */
    public A2aAgentConfig(
            @SuppressWarnings("rawtypes") Map<Class, ClientTransportConfig> clientTransports,
            ClientConfig clientConfig) {
        this(clientTransports, clientConfig, Map.of(), Map.of());
    }

    /**
     * Create a new builder instance for A2aAgentConfig.
     *
     * @return new builder instance
     */
    public static A2aAgentConfigBuilder builder() {
        return new A2aAgentConfigBuilder();
    }

    public static class A2aAgentConfigBuilder {

        @SuppressWarnings("rawtypes")
        private final Map<Class, ClientTransportConfig> clientTransports;

        private ClientConfig clientConfig;

        private Map<String, String> defaultHeaders = Map.of();

        private Map<String, Object> defaultMetadata = Map.of();

        public A2aAgentConfigBuilder() {
            clientTransports = new HashMap<>();
        }

        /**
         * Add client transport configuration which will be used to
         * {@link org.a2aproject.sdk.client.ClientBuilder#withTransport(Class, ClientTransportConfig)}.
         *
         * @param clazz  the client transport implementation class
         * @param config the client transport configuration
         * @param <T>    the subtype of ClientTransport
         * @return the current {@link A2aAgentConfigBuilder} instance for chaining calls
         */
        public <T extends ClientTransport> A2aAgentConfigBuilder withTransport(
                Class<T> clazz, ClientTransportConfig<T> config) {
            this.clientTransports.put(clazz, config);
            return this;
        }

        /**
         * Add client relative config for A2A client.
         *
         * @param clientConfig A2A client config
         * @return the current {@link A2aAgentConfigBuilder} instance for chaining calls
         */
        public A2aAgentConfigBuilder clientConfig(ClientConfig clientConfig) {
            this.clientConfig = clientConfig;
            return this;
        }

        public A2aAgentConfigBuilder defaultHeaders(Map<String, String> defaultHeaders) {
            this.defaultHeaders = defaultHeaders;
            return this;
        }

        public A2aAgentConfigBuilder defaultMetadata(Map<String, Object> defaultMetadata) {
            this.defaultMetadata = defaultMetadata;
            return this;
        }

        public A2aAgentConfig build() {
            return new A2aAgentConfig(
                    this.clientTransports,
                    this.clientConfig,
                    this.defaultHeaders,
                    this.defaultMetadata);
        }
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        return Map.copyOf(new LinkedHashMap<>(source));
    }
}
