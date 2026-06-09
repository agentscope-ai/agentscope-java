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

import io.a2a.client.config.ClientConfig;
import io.a2a.client.transport.spi.ClientTransport;
import io.a2a.client.transport.spi.ClientTransportConfig;
import java.util.HashMap;
import java.util.Map;

/**
 * Config of A2A Agent.
 */
public record A2aAgentConfig(
        @SuppressWarnings("rawtypes") Map<Class, ClientTransportConfig> clientTransports,
        ClientConfig clientConfig) {

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

        public A2aAgentConfigBuilder() {
            clientTransports = new HashMap<>();
        }

        /**
         * Add client transport configuration which will be used to
         * {@link io.a2a.client.ClientBuilder#withTransport(Class, ClientTransportConfig)}.
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

        public A2aAgentConfig build() {
            return new A2aAgentConfig(this.clientTransports, this.clientConfig);
        }
    }
}
