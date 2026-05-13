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
package io.agentscope.spring.boot.a2a.properties;

import io.a2a.spec.TransportProtocol;
import io.agentscope.core.a2a.server.transport.CustomTransportProperties;
import io.agentscope.core.a2a.server.transport.DeploymentProperties;
import io.agentscope.core.a2a.server.transport.TransportProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for JSON-RPC transport.
 */
@ConfigurationProperties(Constants.A2A_JSON_RPC_SERVER_PREFIX)
public class JSONRPCProperties implements CustomTransportProperties {
    /**
     * Whether JSON-RPC transport is enabled.
     */
    private boolean enabled = true;

    /**
     * Deployment configuration including host and port.
     */
    private DeploymentProperties deploymentProperties;

    /**
     * Converts to {@link TransportProperties}.
     *
     * @return the transport properties
     */
    @Override
    public TransportProperties toTransportProperties() {
        return TransportProperties.builder(TransportProtocol.JSONRPC.asString())
                .host(deploymentProperties.host())
                .port(deploymentProperties.port())
                .path(deploymentProperties.path())
                .build();
    }

    @Override
    public void setDeploymentProperties(DeploymentProperties deploymentProperties) {
        this.deploymentProperties = deploymentProperties;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
