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

/**
 * Interface for custom transport properties that can be converted to standard TransportProperties.
 */
public interface CustomTransportProperties {
    /**
     * Converts this custom properties object to standard TransportProperties.
     *
     * @return the TransportProperties representation of this custom properties.
     */
    TransportProperties toTransportProperties();

    /**
     * Sets the deployment properties.
     * @param deploymentProperties the deployment properties.
     */
    void setDeploymentProperties(DeploymentProperties deploymentProperties);

    /**
     * Whether this transport is enabled.
     * @return true if enabled, false otherwise.
     */
    boolean isEnabled();
}
