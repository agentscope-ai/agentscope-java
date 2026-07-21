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
package io.agentscope.extensions.sandbox.kubernetes.client.internal.strategy;

import io.agentscope.extensions.sandbox.kubernetes.client.Constants;
import io.agentscope.extensions.sandbox.kubernetes.client.config.LocalTunnelConnectionConfig;
import io.agentscope.extensions.sandbox.kubernetes.client.exceptions.SandboxPortForwardException;
import io.fabric8.kubernetes.api.model.EndpointAddress;
import io.fabric8.kubernetes.api.model.EndpointSubset;
import io.fabric8.kubernetes.api.model.Endpoints;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.LocalPortForward;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Port-forward connection strategy — establishes a fabric8 port-forward tunnel
 * to the sandbox-router service for development / out-of-cluster mode.
 */
public class PortForwardStrategy implements ConnectionStrategy {

    private static final Logger log = LoggerFactory.getLogger(PortForwardStrategy.class);

    private final KubernetesClient client;
    private final LocalTunnelConnectionConfig config;
    private volatile LocalPortForward portForward;
    private volatile String baseUrl;

    public PortForwardStrategy(KubernetesClient client, LocalTunnelConnectionConfig config) {
        this.client = client;
        this.config = config;
    }

    @Override
    public String connect() throws Exception {
        if (baseUrl != null && portForward != null && portForward.isAlive()) {
            return baseUrl;
        }
        if (portForward != null) {
            close();
        }

        String podName = resolveRouterPod();
        if (podName == null) {
            throw new SandboxPortForwardException(
                    "No ready endpoints found for "
                            + Constants.ROUTER_SERVICE_NAME
                            + " in namespace "
                            + config.routerNamespace());
        }

        log.debug("[sandbox-client] Establishing port-forward to pod: {}", podName);
        portForward =
                client.pods()
                        .inNamespace(config.routerNamespace())
                        .withName(podName)
                        .portForward(Constants.ROUTER_PORT, 0);

        int localPort = portForward.getLocalPort();
        baseUrl = "http://127.0.0.1:" + localPort;
        log.info(
                "[sandbox-client] Port-forward established: localPort={}, pod={}",
                localPort,
                podName);
        return baseUrl;
    }

    @Override
    public void close() {
        if (portForward != null) {
            try {
                portForward.close();
            } catch (Exception e) {
                log.debug("[sandbox-client] Error closing port-forward: {}", e.getMessage());
            }
            portForward = null;
        }
        baseUrl = null;
    }

    private String resolveRouterPod() {
        Endpoints endpoints =
                client.endpoints()
                        .inNamespace(config.routerNamespace())
                        .withName(Constants.ROUTER_SERVICE_NAME)
                        .get();
        if (endpoints == null || endpoints.getSubsets() == null) {
            return null;
        }
        for (EndpointSubset subset : endpoints.getSubsets()) {
            List<EndpointAddress> addresses = subset.getAddresses();
            if (addresses == null || addresses.isEmpty()) {
                continue;
            }
            for (EndpointAddress addr : addresses) {
                if (addr.getTargetRef() != null && addr.getTargetRef().getName() != null) {
                    return addr.getTargetRef().getName();
                }
            }
        }
        return null;
    }
}
