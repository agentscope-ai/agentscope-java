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
import io.agentscope.extensions.sandbox.kubernetes.client.crd.SandboxClaimStatus;
import io.agentscope.extensions.sandbox.kubernetes.client.crd.SandboxResource;
import io.agentscope.extensions.sandbox.kubernetes.client.internal.K8sHelper;
import io.agentscope.extensions.sandbox.kubernetes.client.internal.SandboxConnector;
import io.agentscope.extensions.sandbox.kubernetes.client.model.SandboxStatus;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handle for a running Sandbox instance. Provides command execution and file I/O
 * via {@link #commands()} and {@link #files()}.
 *
 * <p>Mirrors the Python SDK's {@code Sandbox} class.
 */
public class Sandbox {

    private static final Logger log = LoggerFactory.getLogger(Sandbox.class);

    private String claimName;
    private final String sandboxId;
    private final String namespace;
    private final SandboxConnectionConfig connectionConfig;
    private final K8sHelper k8sHelper;
    private final SandboxConnector connector;

    private CommandExecutor commands;
    private Filesystem files;
    private volatile boolean closed;
    private String cachedPodName;
    private String cachedSandboxNameHash;

    public Sandbox(
            String claimName,
            String sandboxId,
            String namespace,
            SandboxConnectionConfig connectionConfig,
            K8sHelper k8sHelper) {
        this(claimName, sandboxId, namespace, connectionConfig, k8sHelper, null, null);
    }

    public Sandbox(
            String claimName,
            String sandboxId,
            String namespace,
            SandboxConnectionConfig connectionConfig,
            K8sHelper k8sHelper,
            Duration requestTimeout,
            Duration perAttemptTimeout) {
        this.claimName = claimName;
        this.sandboxId = sandboxId;
        this.namespace = namespace != null ? namespace : "default";
        this.connectionConfig =
                connectionConfig != null ? connectionConfig : new LocalTunnelConnectionConfig();
        this.k8sHelper = k8sHelper;
        this.connector =
                new SandboxConnector(
                        this.sandboxId,
                        this.namespace,
                        this.connectionConfig,
                        this.k8sHelper,
                        this::getPodIp,
                        requestTimeout,
                        perAttemptTimeout);
        this.commands = new CommandExecutor(connector);
        this.files = new Filesystem(connector);
    }

    public String claimName() {
        return claimName;
    }

    public String sandboxId() {
        return sandboxId;
    }

    public String namespace() {
        return namespace;
    }

    /**
     * Returns the command executor, or null if the connection has been closed.
     *
     * @return command executor
     */
    public CommandExecutor commands() {
        return commands;
    }

    /**
     * Returns the filesystem API, or null if the connection has been closed.
     *
     * @return filesystem
     */
    public Filesystem files() {
        return files;
    }

    /**
     * Whether the client-side connection is still active.
     *
     * @return true if active
     */
    public boolean isActive() {
        return !closed && commands != null && files != null;
    }

    /**
     * Fetches the Sandbox object from Kubernetes and retrieves its current pod name.
     *
     * @return pod name
     */
    public String getPodName() {
        if (cachedPodName != null) {
            return cachedPodName;
        }
        SandboxResource sandbox = k8sHelper.getSandbox(sandboxId, namespace);
        if (sandbox == null || sandbox.getMetadata() == null) {
            cachedPodName = sandboxId;
            return cachedPodName;
        }
        var annotations = sandbox.getMetadata().getAnnotations();
        if (annotations != null && annotations.containsKey(Constants.POD_NAME_ANNOTATION)) {
            cachedPodName = annotations.get(Constants.POD_NAME_ANNOTATION);
        } else {
            cachedPodName = sandboxId;
        }
        return cachedPodName;
    }

    /**
     * Selects a pod IP from the Sandbox status (prefers IPv4).
     *
     * @return pod IP or null
     */
    public String getPodIp() {
        SandboxResource sandbox = k8sHelper.getSandbox(sandboxId, namespace);
        if (sandbox == null || sandbox.getStatus() == null) {
            return null;
        }
        return K8sHelper.selectPodIp(sandbox.getStatus().getPodIPs());
    }

    /**
     * Fetches the sandbox name hash from the status selector label.
     *
     * @return hash or null
     */
    public String getSandboxNameHash() {
        if (cachedSandboxNameHash != null) {
            return cachedSandboxNameHash;
        }
        SandboxResource sandbox = k8sHelper.getSandbox(sandboxId, namespace);
        if (sandbox == null
                || sandbox.getStatus() == null
                || sandbox.getStatus().getSelector() == null) {
            return null;
        }
        String selector = sandbox.getStatus().getSelector();
        int eq = selector.indexOf('=');
        if (eq > 0) {
            String key = selector.substring(0, eq);
            String value = selector.substring(eq + 1);
            if (Constants.SANDBOX_NAME_HASH_LABEL.equals(key)) {
                cachedSandboxNameHash = value;
                return value;
            }
        }
        return null;
    }

    /**
     * Retrieves the current status of the Sandbox.
     *
     * @return status pair
     */
    public SandboxStatus status() {
        SandboxResource sandbox = k8sHelper.getSandbox(sandboxId, namespace);
        if (sandbox == null) {
            return new SandboxStatus(
                    SandboxStatus.NOT_FOUND, "Sandbox object not found in Kubernetes.");
        }
        if (sandbox.getStatus() == null || sandbox.getStatus().getConditions() == null) {
            return new SandboxStatus(SandboxStatus.NOT_READY, "Unknown message");
        }
        for (SandboxClaimStatus.Condition cond : sandbox.getStatus().getConditions()) {
            if ("Ready".equals(cond.getType())) {
                String message = cond.getMessage() != null ? cond.getMessage() : "";
                if ("True".equals(cond.getStatus())) {
                    return new SandboxStatus(SandboxStatus.READY, message);
                }
                return new SandboxStatus(SandboxStatus.NOT_READY, message);
            }
        }
        return new SandboxStatus(SandboxStatus.NOT_READY, "Unknown message");
    }

    /**
     * Closes the client-side connection but leaves the remote Kubernetes resources running.
     */
    public void closeConnection() {
        if (closed) {
            return;
        }
        connector.close();
        commands = null;
        files = null;
        closed = true;
        log.info("[sandbox-client] Connection to sandbox claim '{}' has been closed.", claimName);
    }

    /**
     * Permanently deletes the SandboxClaim and closes the client-side connection.
     * Idempotent.
     */
    public void terminate() {
        closeConnection();
        if (claimName == null || claimName.isBlank()) {
            return;
        }
        k8sHelper.deleteSandboxClaim(claimName, namespace);
        claimName = null;
    }

    SandboxConnector getConnector() {
        return connector;
    }
}
