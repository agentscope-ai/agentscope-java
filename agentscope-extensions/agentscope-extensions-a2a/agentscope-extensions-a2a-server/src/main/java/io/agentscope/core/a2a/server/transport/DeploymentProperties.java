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

import io.agentscope.core.a2a.server.utils.NetworkUtils;
import java.net.SocketException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Some deployment relative properties, such as server default export port and host.
 *
 * <p>When developers don't specified target {@link TransportProperties}, and want to use default transport, developers
 * should create this class and input port at least to make sure AgentScope can generate the url for default transport.
 */
public record DeploymentProperties(String host, int port) {

    private static final Logger log = LoggerFactory.getLogger(DeploymentProperties.class);

    public static class Builder {

        private String host;

        private Integer port;

        public Builder host(String host) {
            this.host = host;
            return this;
        }

        public Builder port(Integer port) {
            this.port = port;
            return this;
        }

        public DeploymentProperties build() {
            if (null == host) {
                log.info(
                        "No host configured in deployment properties, try to get local IP address"
                                + " as host");
                try {
                    host = NetworkUtils.getLocalIpAddress();
                    log.info("Local IP address: {}", host);
                } catch (SocketException ignored) {
                }
            }
            if (null == port) {
                throw new IllegalArgumentException("port must be configured.");
            }
            return new DeploymentProperties(host, port);
        }
    }
}
