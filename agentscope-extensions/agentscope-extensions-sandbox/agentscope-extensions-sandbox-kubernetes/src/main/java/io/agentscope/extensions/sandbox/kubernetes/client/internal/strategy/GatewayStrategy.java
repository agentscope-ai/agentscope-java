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
import io.agentscope.extensions.sandbox.kubernetes.client.config.GatewayConnectionConfig;
import io.agentscope.extensions.sandbox.kubernetes.client.exceptions.SandboxException;
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gateway connection strategy — watches a Kubernetes Gateway resource for an
 * external address, then routes through the sandbox-router.
 */
public class GatewayStrategy implements ConnectionStrategy {

    private static final Logger log = LoggerFactory.getLogger(GatewayStrategy.class);

    private static final CustomResourceDefinitionContext GATEWAY_CRD =
            new CustomResourceDefinitionContext.Builder()
                    .withGroup(Constants.GATEWAY_API_GROUP)
                    .withVersion(Constants.GATEWAY_API_VERSION)
                    .withPlural(Constants.GATEWAY_PLURAL)
                    .withKind("Gateway")
                    .withScope("Namespaced")
                    .build();

    private final KubernetesClient client;
    private final GatewayConnectionConfig config;
    private volatile String baseUrl;

    public GatewayStrategy(KubernetesClient client, GatewayConnectionConfig config) {
        this.client = client;
        this.config = config;
    }

    @Override
    public String connect() throws Exception {
        if (baseUrl != null) {
            return baseUrl;
        }
        String addr = resolveGatewayAddress();
        if (addr != null) {
            baseUrl = formatUrl(addr);
            return baseUrl;
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        try (Watch watch =
                client.genericKubernetesResources(GATEWAY_CRD)
                        .inNamespace(config.gatewayNamespace())
                        .withName(config.gatewayName())
                        .watch(
                                new Watcher<>() {
                                    @Override
                                    public void eventReceived(
                                            Action action, GenericKubernetesResource gw) {
                                        if (action == Action.DELETED) {
                                            future.completeExceptionally(
                                                    new SandboxException(
                                                            "Gateway deleted: "
                                                                    + config.gatewayName()));
                                            return;
                                        }
                                        String a = extractAddress(gw);
                                        if (a != null) {
                                            future.complete(a);
                                        }
                                    }

                                    @Override
                                    public void onClose(WatcherException cause) {
                                        if (cause != null && !future.isDone()) {
                                            future.completeExceptionally(cause);
                                        }
                                    }
                                })) {
            try {
                String address = future.get(config.gatewayReadyTimeoutSeconds(), TimeUnit.SECONDS);
                baseUrl = formatUrl(address);
                return baseUrl;
            } catch (TimeoutException e) {
                throw new SandboxException(
                        "Timed out waiting for gateway address: " + config.gatewayName(), e);
            }
        }
    }

    @Override
    public void close() {
        baseUrl = null;
    }

    private String resolveGatewayAddress() {
        GenericKubernetesResource gw =
                client.genericKubernetesResources(GATEWAY_CRD)
                        .inNamespace(config.gatewayNamespace())
                        .withName(config.gatewayName())
                        .get();
        if (gw == null) {
            return null;
        }
        return extractAddress(gw);
    }

    @SuppressWarnings("unchecked")
    private String extractAddress(GenericKubernetesResource gw) {
        try {
            Map<String, Object> status = (Map<String, Object>) gw.get("status");
            if (status == null) {
                return null;
            }
            List<Map<String, Object>> addresses =
                    (List<Map<String, Object>>) status.get("addresses");
            if (addresses == null || addresses.isEmpty()) {
                return null;
            }
            Map<String, Object> first = addresses.get(0);
            Object value = first.get("value");
            if (value == null) {
                return null;
            }
            String addr = value.toString();
            if (addr.isBlank()) {
                return null;
            }
            log.debug("[sandbox-client] Gateway address resolved: {}", addr);
            return addr;
        } catch (Exception e) {
            log.debug("[sandbox-client] Failed to extract gateway address: {}", e.getMessage());
            return null;
        }
    }

    private String formatUrl(String addr) {
        if (addr.contains(":")) {
            return config.gatewayScheme() + "://[" + addr + "]";
        }
        return config.gatewayScheme() + "://" + addr;
    }
}
