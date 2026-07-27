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
package io.agentscope.extensions.sandbox.kubernetes.client;

import io.agentscope.extensions.sandbox.kubernetes.client.config.LocalTunnelConnectionConfig;
import io.agentscope.extensions.sandbox.kubernetes.client.config.SandboxConnectionConfig;
import io.agentscope.extensions.sandbox.kubernetes.client.crd.SandboxClaim;
import io.agentscope.extensions.sandbox.kubernetes.client.crd.SandboxResource;
import io.agentscope.extensions.sandbox.kubernetes.client.exceptions.SandboxException;
import io.agentscope.extensions.sandbox.kubernetes.client.exceptions.SandboxNotFoundException;
import io.agentscope.extensions.sandbox.kubernetes.client.internal.K8sHelper;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client for managing agent-sandbox lifecycles.
 *
 * <p>Mirrors the Python SDK's {@code SandboxClient}: creates SandboxClaims against a warm pool,
 * waits for readiness, and returns {@link Sandbox} handles for command / file operations.
 */
public class SandboxClient implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SandboxClient.class);

    private final SandboxConnectionConfig connectionConfig;
    private final K8sHelper k8sHelper;
    private final boolean ownsClient;
    private final Duration requestTimeout;
    private final Duration perAttemptTimeout;
    private final Map<String, Sandbox> activeSandboxes = new LinkedHashMap<>();

    public SandboxClient() {
        this(new LocalTunnelConnectionConfig(), null, null, null);
    }

    public SandboxClient(SandboxConnectionConfig connectionConfig) {
        this(connectionConfig, null, null, null);
    }

    public SandboxClient(
            SandboxConnectionConfig connectionConfig, KubernetesClient kubernetesClient) {
        this(connectionConfig, kubernetesClient, null, null);
    }

    public SandboxClient(
            SandboxConnectionConfig connectionConfig,
            KubernetesClient kubernetesClient,
            Duration requestTimeout,
            Duration perAttemptTimeout) {
        this.connectionConfig =
                connectionConfig != null ? connectionConfig : new LocalTunnelConnectionConfig();
        if (kubernetesClient != null) {
            this.k8sHelper = new K8sHelper(kubernetesClient);
            this.ownsClient = false;
        } else {
            this.k8sHelper = new K8sHelper(new KubernetesClientBuilder().build());
            this.ownsClient = true;
        }
        this.requestTimeout = requestTimeout;
        this.perAttemptTimeout = perAttemptTimeout;
    }

    /**
     * Creates a sandbox from the given options.
     *
     * @param options create options
     * @return sandbox handle
     */
    public Sandbox createSandbox(CreateSandboxOptions options) {
        String claimName = options.claimName();
        if (claimName == null || claimName.isBlank()) {
            claimName =
                    "sandbox-claim-"
                            + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        }
        String namespace = options.namespace();
        long readyTimeout = options.sandboxReadyTimeoutSeconds();

        try {
            k8sHelper.createSandboxClaim(
                    claimName,
                    options.warmPool(),
                    namespace,
                    options.labels().isEmpty() ? null : options.labels(),
                    options.shutdownAfterSeconds(),
                    options.podLabels().isEmpty() ? null : options.podLabels(),
                    options.podAnnotations().isEmpty() ? null : options.podAnnotations());

            long startNs = System.nanoTime();
            String sandboxId = k8sHelper.resolveSandboxName(claimName, namespace, readyTimeout);
            long elapsedSeconds = (System.nanoTime() - startNs) / 1_000_000_000L;
            long remaining = Math.max(0, readyTimeout - elapsedSeconds);
            if (remaining <= 0) {
                throw new SandboxException("Sandbox resolution exceeded the ready timeout.");
            }
            SandboxResource.SandboxIdentity identity =
                    k8sHelper.waitForSandboxReady(sandboxId, namespace, remaining);
            log.debug(
                    "[sandbox-client] Sandbox ready: claim={} sandbox={} podIP={}",
                    claimName,
                    sandboxId,
                    identity != null ? identity.getPodIP() : null);

            Sandbox sandbox =
                    new Sandbox(
                            claimName,
                            sandboxId,
                            namespace,
                            connectionConfig,
                            k8sHelper,
                            requestTimeout,
                            perAttemptTimeout);
            sandbox.getConnector().connect();
            activeSandboxes.put(key(namespace, claimName), sandbox);
            return sandbox;
        } catch (Exception e) {
            try {
                k8sHelper.deleteSandboxClaim(claimName, namespace);
            } catch (Exception cleanup) {
                log.debug(
                        "[sandbox-client] Failed to clean orphaned claim {}/{}: {}",
                        namespace,
                        claimName,
                        cleanup.getMessage());
            }
            if (e instanceof SandboxException se) {
                throw se;
            }
            throw new SandboxException("Failed to create sandbox: " + e.getMessage(), e);
        }
    }

    /**
     * Convenience overload matching the Python SDK signature.
     *
     * @param warmPool warm pool name
     * @return sandbox handle
     */
    public Sandbox createSandbox(String warmPool) {
        return createSandbox(CreateSandboxOptions.builder(warmPool).build());
    }

    /**
     * Convenience overload with namespace.
     *
     * @param warmPool warm pool name
     * @param namespace namespace
     * @return sandbox handle
     */
    public Sandbox createSandbox(String warmPool, String namespace) {
        return createSandbox(CreateSandboxOptions.builder(warmPool).namespace(namespace).build());
    }

    /**
     * Re-attaches to an existing SandboxClaim.
     *
     * @param claimName claim name
     * @param namespace namespace
     * @return sandbox handle
     */
    public Sandbox getSandbox(String claimName, String namespace) {
        return getSandbox(claimName, namespace, 30);
    }

    /**
     * Re-attaches to an existing SandboxClaim.
     *
     * @param claimName claim name
     * @param namespace namespace
     * @param resolveTimeoutSeconds timeout for name resolution
     * @return sandbox handle
     */
    public Sandbox getSandbox(String claimName, String namespace, long resolveTimeoutSeconds) {
        String ns = namespace != null ? namespace : "default";
        String key = key(ns, claimName);
        Sandbox existing = activeSandboxes.get(key);

        String sandboxId;
        try {
            sandboxId = k8sHelper.resolveSandboxName(claimName, ns, resolveTimeoutSeconds);
            SandboxResource sandboxObject = k8sHelper.getSandbox(sandboxId, ns);
            if (sandboxObject == null) {
                throw new SandboxNotFoundException(
                        "Underlying Sandbox '" + sandboxId + "' not found.");
            }
        } catch (Exception e) {
            if (existing != null) {
                existing.terminate();
            }
            activeSandboxes.remove(key);
            if (e instanceof SandboxNotFoundException snf) {
                throw snf;
            }
            throw new SandboxNotFoundException(
                    "Sandbox claim '"
                            + claimName
                            + "' not found or resolution failed in namespace '"
                            + ns
                            + "': "
                            + e.getMessage(),
                    e);
        }

        if (existing != null && existing.isActive()) {
            return existing;
        }
        if (existing != null) {
            activeSandboxes.remove(key);
        }

        try {
            Sandbox handle =
                    new Sandbox(
                            claimName,
                            sandboxId,
                            ns,
                            connectionConfig,
                            k8sHelper,
                            requestTimeout,
                            perAttemptTimeout);
            handle.getConnector().connect();
            activeSandboxes.put(key, handle);
            return handle;
        } catch (Exception e) {
            throw new SandboxException("Failed to attach to sandbox: " + e.getMessage(), e);
        }
    }

    /**
     * Returns {@code (namespace, claimName)} pairs tracked by this client.
     *
     * @return active sandbox keys as {@code namespace/claimName} strings
     */
    public List<String> listActiveSandboxes() {
        Iterator<Map.Entry<String, Sandbox>> it = activeSandboxes.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Sandbox> entry = it.next();
            if (!entry.getValue().isActive()) {
                it.remove();
            }
        }
        return new ArrayList<>(activeSandboxes.keySet());
    }

    /**
     * Lists all SandboxClaim names in the cluster for the given namespace.
     *
     * @param namespace namespace
     * @return claim names
     */
    public List<String> listAllSandboxes(String namespace) {
        return listAllSandboxes(namespace, null);
    }

    /**
     * Lists SandboxClaim names, optionally filtered by label selector.
     *
     * @param namespace namespace
     * @param labelSelector optional label selector
     * @return claim names
     */
    public List<String> listAllSandboxes(String namespace, String labelSelector) {
        return k8sHelper.listSandboxClaims(
                namespace != null ? namespace : "default", labelSelector);
    }

    /**
     * Stops the client-side connection and deletes the Kubernetes claim.
     *
     * @param claimName claim name
     * @param namespace namespace
     */
    public void deleteSandbox(String claimName, String namespace) {
        String ns = namespace != null ? namespace : "default";
        String key = key(ns, claimName);
        Sandbox sandbox = activeSandboxes.get(key);
        try {
            if (sandbox != null) {
                sandbox.terminate();
                activeSandboxes.remove(key);
            } else {
                k8sHelper.deleteSandboxClaim(claimName, ns);
            }
        } catch (Exception e) {
            log.error(
                    "[sandbox-client] Failed to delete sandbox '{}' in namespace '{}': {}",
                    claimName,
                    ns,
                    e.getMessage());
        }
    }

    /** Deletes all sandboxes tracked by this client. */
    public void deleteAll() {
        for (String key : new ArrayList<>(activeSandboxes.keySet())) {
            int slash = key.indexOf('/');
            String ns = key.substring(0, slash);
            String claimName = key.substring(slash + 1);
            try {
                deleteSandbox(claimName, ns);
            } catch (Exception e) {
                log.error(
                        "[sandbox-client] Cleanup failed for {} in namespace {}: {}",
                        claimName,
                        ns,
                        e.getMessage());
            }
        }
    }

    /**
     * Returns the warm pool name referenced by a claim.
     *
     * @param claimName claim name
     * @param namespace namespace
     * @return warm pool name
     */
    public String getSandboxClaimWarmPoolName(String claimName, String namespace) {
        SandboxClaim claim = k8sHelper.getSandboxClaim(claimName, namespace);
        if (claim == null) {
            throw new SandboxNotFoundException(
                    "SandboxClaim '" + claimName + "' not found in namespace '" + namespace + "'.");
        }
        if (claim.getSpec() == null || claim.getSpec().getWarmPoolRef() == null) {
            return null;
        }
        return claim.getSpec().getWarmPoolRef().getName();
    }

    public K8sHelper getK8sHelper() {
        return k8sHelper;
    }

    public SandboxConnectionConfig getConnectionConfig() {
        return connectionConfig;
    }

    @Override
    public void close() {
        deleteAll();
        if (ownsClient) {
            try {
                k8sHelper.getClient().close();
            } catch (Exception e) {
                log.debug("[sandbox-client] Error closing KubernetesClient: {}", e.getMessage());
            }
        }
    }

    private static String key(String namespace, String claimName) {
        return namespace + "/" + claimName;
    }
}
