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

import io.agentscope.extensions.sandbox.kubernetes.client.config.InClusterConnectionConfig;
import java.util.function.Supplier;

/**
 * Direct in-cluster connectivity to a sandbox pod, bypassing the router.
 * Does not inject {@code X-Sandbox-*} headers.
 */
public class InClusterStrategy implements ConnectionStrategy {

    private final String dnsUrl;
    private final int serverPort;
    private final Supplier<String> podIpSupplier;
    private volatile String cachedPodIpUrl;
    private volatile boolean resolved;

    public InClusterStrategy(
            String sandboxId,
            String namespace,
            InClusterConnectionConfig config,
            Supplier<String> podIpSupplier) {
        this.serverPort = config.serverPort();
        this.dnsUrl = "http://" + sandboxId + "." + namespace + ".svc.cluster.local:" + serverPort;
        this.podIpSupplier = podIpSupplier;
    }

    @Override
    public String connect() {
        if (podIpSupplier != null) {
            if (resolved) {
                return cachedPodIpUrl != null ? cachedPodIpUrl : dnsUrl;
            }
            String podIp = podIpSupplier.get();
            if (podIp != null && !podIp.isBlank()) {
                String host = podIp.contains(":") ? "[" + podIp + "]" : podIp;
                cachedPodIpUrl = "http://" + host + ":" + serverPort;
                resolved = true;
                return cachedPodIpUrl;
            }
        }
        return dnsUrl;
    }

    @Override
    public void close() {
        resolved = false;
        cachedPodIpUrl = null;
    }

    @Override
    public boolean shouldInjectRouterHeaders() {
        return false;
    }
}
