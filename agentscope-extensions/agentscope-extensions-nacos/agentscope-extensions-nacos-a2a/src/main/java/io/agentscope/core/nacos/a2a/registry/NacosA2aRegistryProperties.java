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

import java.util.HashMap;
import java.util.Map;

/**
 * Properties for A2A AgentCard and Endpoint registry to Nacos.
 *
 * <p>Property description:
 * <ul>
 *     <li>{@code isSetAsLatest}: Always register the A2A service as the latest version, default is {@code false}.</li>
 *     <li>{@code enabledRegisterEndpoint}: Automatically register all `Transport` as Endpoints for this A2A service,
 *     default is {@code true}. When set to {@code false}, only Agent Card will be published.</li>
 *     <li>{@code overwritePreferredTransport}: When registering A2A services, use this `Transport` to override the
 *     `preferredTransport` and `url` in the Agent Card, default is `null`.</li>
 * </ul>
 */
public record NacosA2aRegistryProperties(
        boolean isSetAsLatest,
        boolean enabledRegisterEndpoint,
        String overwritePreferredTransport,
        Map<String, NacosA2aRegistryTransportProperties> transportProperties) {

    /**
     * Adds properties of transport to the registry.
     *
     * <p>Each transport will be transferred to {@link com.alibaba.nacos.api.ai.model.a2a.AgentEndpoint} and registered into Nacos.
     *
     * @param transport properties of transport
     */
    public void addTransport(NacosA2aRegistryTransportProperties transport) {
        transportProperties.put(transport.transport(), transport);
    }

    /**
     * Creates a new builder instance for {@link NacosA2aRegistryProperties}.
     *
     * @return builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Creates a new builder instance for {@link NacosA2aRegistryProperties} from an existing prototype instance.
     *
     * <p>The new builder will contain the same values as the prototype instance exclude {@link #transportProperties}.
     *
     * @param prototype the prototype instance to copy properties from
     * @return builder instance
     */
    public static Builder builder(NacosA2aRegistryProperties prototype) {
        Builder newBuilder = builder();
        newBuilder.setAsLatest(prototype.isSetAsLatest());
        newBuilder.enabledRegisterEndpoint(prototype.enabledRegisterEndpoint());
        newBuilder.overwritePreferredTransport(prototype.overwritePreferredTransport());
        return newBuilder;
    }

    /**
     * The Builder for {@link NacosA2aRegistryProperties}.
     */
    public static class Builder {

        private final Map<String, NacosA2aRegistryTransportProperties> transportProperties;

        private boolean setAsLatest;

        private boolean enabledRegisterEndpoint;

        private String overwritePreferredTransport;

        private Builder() {
            transportProperties = new HashMap<>();
            enabledRegisterEndpoint = true;
        }

        /**
         * Sets whether to register the A2A service as the latest version.
         *
         * @param setAsLatest {@code true} to register as latest version, {@code false} otherwise
         * @return the builder instance for method chaining
         */
        public Builder setAsLatest(boolean setAsLatest) {
            this.setAsLatest = setAsLatest;
            return this;
        }

        /**
         * Sets whether to automatically register all Transport as Endpoints for this A2A service.
         *
         * @param enabledRegisterEndpoint {@code true} to enable endpoint registration, {@code false} to disable
         * @return the builder instance for method chaining
         */
        public Builder enabledRegisterEndpoint(boolean enabledRegisterEndpoint) {
            this.enabledRegisterEndpoint = enabledRegisterEndpoint;
            return this;
        }

        /**
         * Sets the transport to use for overriding the preferredTransport and URL in the Agent Card.
         *
         * @param overwritePreferredTransport the transport to use for overriding, or {@code null} to not override
         * @return the builder instance for method chaining
         */
        public Builder overwritePreferredTransport(String overwritePreferredTransport) {
            this.overwritePreferredTransport = overwritePreferredTransport;
            return this;
        }

        /**
         * Build a new instance of {@link NacosA2aRegistryProperties}.
         *
         * @return new instance of {@link NacosA2aRegistryProperties}
         */
        public NacosA2aRegistryProperties build() {
            return new NacosA2aRegistryProperties(
                    setAsLatest,
                    enabledRegisterEndpoint,
                    overwritePreferredTransport,
                    transportProperties);
        }
    }
}
