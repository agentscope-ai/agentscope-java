/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.sandbox.kubernetes.client.config;

import io.agentscope.extensions.sandbox.kubernetes.client.Constants;

/** Configuration for connecting via the Kubernetes Gateway API. */
public final class GatewayConnectionConfig implements SandboxConnectionConfig {

    private final String gatewayName;
    private final String gatewayNamespace;
    private final String gatewayScheme;
    private final long gatewayReadyTimeoutSeconds;
    private final int serverPort;

    public GatewayConnectionConfig(String gatewayName) {
        this(gatewayName, "default", "http", 180, Constants.DEFAULT_SERVER_PORT);
    }

    public GatewayConnectionConfig(
            String gatewayName,
            String gatewayNamespace,
            String gatewayScheme,
            long gatewayReadyTimeoutSeconds,
            int serverPort) {
        if (gatewayName == null || gatewayName.isBlank()) {
            throw new IllegalArgumentException("Gateway name must not be blank");
        }
        this.gatewayName = gatewayName;
        this.gatewayNamespace = gatewayNamespace != null ? gatewayNamespace : "default";
        this.gatewayScheme = gatewayScheme != null ? gatewayScheme : "http";
        this.gatewayReadyTimeoutSeconds =
                gatewayReadyTimeoutSeconds > 0 ? gatewayReadyTimeoutSeconds : 180;
        this.serverPort = serverPort > 0 ? serverPort : Constants.DEFAULT_SERVER_PORT;
    }

    public String gatewayName() {
        return gatewayName;
    }

    public String gatewayNamespace() {
        return gatewayNamespace;
    }

    public String gatewayScheme() {
        return gatewayScheme;
    }

    public long gatewayReadyTimeoutSeconds() {
        return gatewayReadyTimeoutSeconds;
    }

    @Override
    public int serverPort() {
        return serverPort;
    }
}
