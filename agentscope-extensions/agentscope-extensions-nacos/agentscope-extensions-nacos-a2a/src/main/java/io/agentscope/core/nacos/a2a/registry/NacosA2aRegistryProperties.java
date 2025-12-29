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

        public Builder setAsLatest(boolean setAsLatest) {
            this.setAsLatest = setAsLatest;
            return this;
        }

        public Builder enabledRegisterEndpoint(boolean enabledRegisterEndpoint) {
            this.enabledRegisterEndpoint = enabledRegisterEndpoint;
            return this;
        }

        public Builder overwritePreferredTransport(String overwritePreferredTransport) {
            this.overwritePreferredTransport = overwritePreferredTransport;
            return this;
        }

        public NacosA2aRegistryProperties build() {
            return new NacosA2aRegistryProperties(
                    setAsLatest,
                    enabledRegisterEndpoint,
                    overwritePreferredTransport,
                    transportProperties);
        }
    }
}
