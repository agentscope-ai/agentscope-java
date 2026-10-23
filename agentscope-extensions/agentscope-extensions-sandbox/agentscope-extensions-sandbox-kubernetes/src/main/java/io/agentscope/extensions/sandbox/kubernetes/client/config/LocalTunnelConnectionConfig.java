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
import java.util.regex.Pattern;

/**
 * Configuration for connecting via a local port-forward tunnel to the
 * sandbox-router service (development / out-of-cluster mode).
 */
public final class LocalTunnelConnectionConfig implements SandboxConnectionConfig {

    private static final Pattern NAMESPACE_PATTERN =
            Pattern.compile("^[a-z0-9]([-a-z0-9]*[a-z0-9])?$");

    private final long portForwardReadyTimeoutSeconds;
    private final int serverPort;
    private final String routerNamespace;

    public LocalTunnelConnectionConfig() {
        this(30, Constants.DEFAULT_SERVER_PORT, "agent-sandbox-system");
    }

    public LocalTunnelConnectionConfig(
            long portForwardReadyTimeoutSeconds, int serverPort, String routerNamespace) {
        this.portForwardReadyTimeoutSeconds =
                portForwardReadyTimeoutSeconds > 0 ? portForwardReadyTimeoutSeconds : 30;
        this.serverPort = serverPort > 0 ? serverPort : Constants.DEFAULT_SERVER_PORT;
        String ns = routerNamespace != null ? routerNamespace : "agent-sandbox-system";
        if (!NAMESPACE_PATTERN.matcher(ns).matches()) {
            throw new IllegalArgumentException("Invalid Kubernetes namespace name format: " + ns);
        }
        this.routerNamespace = ns;
    }

    public long portForwardReadyTimeoutSeconds() {
        return portForwardReadyTimeoutSeconds;
    }

    public String routerNamespace() {
        return routerNamespace;
    }

    @Override
    public int serverPort() {
        return serverPort;
    }
}
