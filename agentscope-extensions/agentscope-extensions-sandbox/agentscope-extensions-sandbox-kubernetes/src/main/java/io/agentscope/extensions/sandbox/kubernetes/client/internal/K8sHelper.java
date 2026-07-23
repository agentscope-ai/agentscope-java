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
package io.agentscope.extensions.sandbox.kubernetes.client.internal;

import io.agentscope.extensions.sandbox.kubernetes.client.Constants;
import io.agentscope.extensions.sandbox.kubernetes.client.crd.SandboxClaim;
import io.agentscope.extensions.sandbox.kubernetes.client.crd.SandboxClaimSpec;
import io.agentscope.extensions.sandbox.kubernetes.client.crd.SandboxClaimStatus;
import io.agentscope.extensions.sandbox.kubernetes.client.crd.SandboxResource;
import io.agentscope.extensions.sandbox.kubernetes.client.exceptions.SandboxException;
import io.agentscope.extensions.sandbox.kubernetes.client.exceptions.SandboxMetadataException;
import io.agentscope.extensions.sandbox.kubernetes.client.exceptions.SandboxNotFoundException;
import io.agentscope.extensions.sandbox.kubernetes.client.exceptions.SandboxTemplateNotFoundException;
import io.agentscope.extensions.sandbox.kubernetes.client.exceptions.SandboxWarmPoolNotFoundException;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encapsulates Kubernetes CRD operations for agent-sandbox lifecycle management.
 * Mirrors the Python SDK's {@code K8sHelper}.
 */
public class K8sHelper {

    private static final Logger log = LoggerFactory.getLogger(K8sHelper.class);

    private final KubernetesClient client;

    public K8sHelper(KubernetesClient client) {
        this.client = client;
    }

    public KubernetesClient getClient() {
        return client;
    }

    /**
     * Creates a SandboxClaim referencing the given warm pool.
     *
     * @param name claim name
     * @param warmPool warm pool name
     * @param namespace Kubernetes namespace
     * @param labels optional claim labels
     * @param shutdownAfterSeconds optional TTL; when set, populates {@code spec.lifecycle}
     * @param podLabels optional labels for {@code spec.additionalPodMetadata}
     * @param podAnnotations optional annotations for {@code spec.additionalPodMetadata}
     * @return the created claim
     */
    public SandboxClaim createSandboxClaim(
            String name,
            String warmPool,
            String namespace,
            Map<String, String> labels,
            Long shutdownAfterSeconds,
            Map<String, String> podLabels,
            Map<String, String> podAnnotations) {
        Map<String, String> claimLabels = new HashMap<>();
        if (labels != null) {
            claimLabels.putAll(labels);
        }
        claimLabels.put(Constants.CREATED_BY_LABEL, Constants.CREATED_BY_VALUE);

        SandboxClaim claim = new SandboxClaim();
        claim.setMetadata(
                new ObjectMetaBuilder()
                        .withName(name)
                        .withNamespace(namespace)
                        .withLabels(claimLabels)
                        .build());

        SandboxClaimSpec spec = new SandboxClaimSpec(warmPool);
        if (shutdownAfterSeconds != null) {
            if (shutdownAfterSeconds <= 0) {
                throw new IllegalArgumentException("shutdownAfterSeconds must be positive");
            }
            Instant shutdownAt = Instant.now().plusSeconds(shutdownAfterSeconds);
            String shutdownTime =
                    DateTimeFormatter.ISO_INSTANT.format(shutdownAt.atOffset(ZoneOffset.UTC));
            spec.setLifecycle(new SandboxClaimSpec.Lifecycle(shutdownTime, "Delete"));
        }
        if ((podLabels != null && !podLabels.isEmpty())
                || (podAnnotations != null && !podAnnotations.isEmpty())) {
            spec.setAdditionalPodMetadata(
                    new SandboxClaimSpec.AdditionalPodMetadata(
                            podLabels != null && !podLabels.isEmpty() ? podLabels : null,
                            podAnnotations != null && !podAnnotations.isEmpty()
                                    ? podAnnotations
                                    : null));
        }
        claim.setSpec(spec);

        SandboxClaim created = client.resource(claim).inNamespace(namespace).create();
        log.debug(
                "[sandbox-client] Created SandboxClaim: {}/{}",
                namespace,
                created.getMetadata().getName());
        return created;
    }

    /**
     * Deletes a SandboxClaim. No-op if already deleted.
     *
     * @param name claim name
     * @param namespace namespace
     */
    public void deleteSandboxClaim(String name, String namespace) {
        if (name == null || name.isBlank()) {
            return;
        }
        try {
            client.resources(SandboxClaim.class).inNamespace(namespace).withName(name).delete();
            log.debug("[sandbox-client] Deleted SandboxClaim: {}/{}", namespace, name);
        } catch (Exception e) {
            if (!isNotFound(e)) {
                throw e;
            }
            log.debug("[sandbox-client] SandboxClaim already deleted: {}/{}", namespace, name);
        }
    }

    /**
     * Gets a SandboxClaim, or null if not found.
     *
     * @param name claim name
     * @param namespace namespace
     * @return claim or null
     */
    public SandboxClaim getSandboxClaim(String name, String namespace) {
        return client.resources(SandboxClaim.class).inNamespace(namespace).withName(name).get();
    }

    /**
     * Lists SandboxClaim names in a namespace, optionally filtered by label selector.
     *
     * @param namespace namespace
     * @param labelSelector optional label selector
     * @return claim names
     */
    public List<String> listSandboxClaims(String namespace, String labelSelector) {
        var op = client.resources(SandboxClaim.class).inNamespace(namespace);
        List<SandboxClaim> items;
        if (labelSelector != null && !labelSelector.isBlank()) {
            items = op.withLabelSelector(labelSelector).list().getItems();
        } else {
            items = op.list().getItems();
        }
        List<String> names = new ArrayList<>();
        for (SandboxClaim claim : items) {
            if (claim.getMetadata() != null && claim.getMetadata().getName() != null) {
                names.add(claim.getMetadata().getName());
            }
        }
        return names;
    }

    /**
     * Gets a Sandbox resource, or null if not found.
     *
     * @param name sandbox name
     * @param namespace namespace
     * @return sandbox or null
     */
    public SandboxResource getSandbox(String name, String namespace) {
        return client.resources(SandboxResource.class).inNamespace(namespace).withName(name).get();
    }

    /**
     * Watches the SandboxClaim until the sandbox name is resolved in its status.
     *
     * @param claimName claim name
     * @param namespace namespace
     * @param timeoutSeconds timeout
     * @return resolved sandbox name
     */
    public String resolveSandboxName(String claimName, String namespace, long timeoutSeconds)
            throws Exception {
        SandboxClaim current =
                client.resources(SandboxClaim.class)
                        .inNamespace(namespace)
                        .withName(claimName)
                        .get();
        if (current != null) {
            checkClaimFailureConditions(current, claimName);
            String resolved = extractSandboxName(current);
            if (resolved != null) {
                log.debug(
                        "[sandbox-client] Sandbox name already resolved: {} -> {}",
                        claimName,
                        resolved);
                return resolved;
            }
        }

        CompletableFuture<String> future = new CompletableFuture<>();
        try (Watch watch =
                client.resources(SandboxClaim.class)
                        .inNamespace(namespace)
                        .withName(claimName)
                        .watch(
                                new Watcher<>() {
                                    @Override
                                    public void eventReceived(Action action, SandboxClaim claim) {
                                        if (action == Action.DELETED) {
                                            future.completeExceptionally(
                                                    new SandboxMetadataException(
                                                            "SandboxClaim deleted during"
                                                                    + " resolution: "
                                                                    + claimName));
                                            return;
                                        }
                                        try {
                                            checkClaimFailureConditions(claim, claimName);
                                        } catch (RuntimeException e) {
                                            future.completeExceptionally(e);
                                            return;
                                        }
                                        String name = extractSandboxName(claim);
                                        if (name != null) {
                                            future.complete(name);
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
                return future.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new SandboxException(
                        "Timed out waiting for sandbox name resolution for claim: " + claimName, e);
            }
        }
    }

    /**
     * Waits for a Sandbox resource to become Ready.
     *
     * @param sandboxName sandbox name
     * @param namespace namespace
     * @param timeoutSeconds timeout
     * @return sandbox identity metadata
     */
    public SandboxResource.SandboxIdentity waitForSandboxReady(
            String sandboxName, String namespace, long timeoutSeconds) throws Exception {
        SandboxResource current =
                client.resources(SandboxResource.class)
                        .inNamespace(namespace)
                        .withName(sandboxName)
                        .get();
        if (current != null && isSandboxReady(current)) {
            return extractIdentity(current);
        }

        CompletableFuture<SandboxResource.SandboxIdentity> future = new CompletableFuture<>();
        try (Watch watch =
                client.resources(SandboxResource.class)
                        .inNamespace(namespace)
                        .withName(sandboxName)
                        .watch(
                                new Watcher<>() {
                                    @Override
                                    public void eventReceived(
                                            Action action, SandboxResource sandbox) {
                                        if (action == Action.DELETED) {
                                            future.completeExceptionally(
                                                    new SandboxNotFoundException(
                                                            "Sandbox deleted before ready: "
                                                                    + sandboxName));
                                            return;
                                        }
                                        if (isSandboxReady(sandbox)) {
                                            future.complete(extractIdentity(sandbox));
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
                return future.get(timeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new SandboxException(
                        "Timed out waiting for sandbox ready: " + sandboxName, e);
            }
        }
    }

    /**
     * Selects a preferred pod IP (first IPv4 if present, otherwise first entry).
     *
     * @param podIPs pod IP list
     * @return selected IP or null
     */
    public static String selectPodIp(List<String> podIPs) {
        if (podIPs == null || podIPs.isEmpty()) {
            return null;
        }
        for (String ip : podIPs) {
            if (ip != null && !ip.isBlank() && !ip.contains(":")) {
                return ip;
            }
        }
        String first = podIPs.get(0);
        return first != null && !first.isBlank() ? first : null;
    }

    private void checkClaimFailureConditions(SandboxClaim claim, String claimName) {
        if (claim.getStatus() == null || claim.getStatus().getConditions() == null) {
            return;
        }
        for (SandboxClaimStatus.Condition cond : claim.getStatus().getConditions()) {
            if ("Ready".equals(cond.getType())
                    && "False".equals(cond.getStatus())
                    && "TemplateNotFound".equals(cond.getReason())) {
                throw new SandboxTemplateNotFoundException(
                        "SandboxTemplate requested does not exist: "
                                + (cond.getMessage() != null
                                        ? cond.getMessage()
                                        : "Template not found"));
            }
            if ("WarmPoolNotFound".equals(cond.getReason())) {
                throw new SandboxWarmPoolNotFoundException(
                        "SandboxWarmPool requested does not exist: "
                                + (cond.getMessage() != null
                                        ? cond.getMessage()
                                        : "WarmPool not found"));
            }
        }
    }

    private static String extractSandboxName(SandboxClaim claim) {
        if (claim.getStatus() == null
                || claim.getStatus().getSandbox() == null
                || claim.getStatus().getSandbox().getName() == null) {
            return null;
        }
        String name = claim.getStatus().getSandbox().getName();
        return name.isBlank() ? null : name;
    }

    private boolean isSandboxReady(SandboxResource sandbox) {
        if (sandbox.getStatus() == null || sandbox.getStatus().getConditions() == null) {
            return false;
        }
        for (SandboxClaimStatus.Condition cond : sandbox.getStatus().getConditions()) {
            if ("Ready".equals(cond.getType()) && "True".equals(cond.getStatus())) {
                return true;
            }
        }
        return false;
    }

    private SandboxResource.SandboxIdentity extractIdentity(SandboxResource sandbox) {
        String name = sandbox.getMetadata().getName();
        Map<String, String> annotations = sandbox.getMetadata().getAnnotations();
        String podName = name;
        if (annotations != null && annotations.containsKey(Constants.POD_NAME_ANNOTATION)) {
            podName = annotations.get(Constants.POD_NAME_ANNOTATION);
        }
        String podIP = null;
        if (sandbox.getStatus() != null) {
            podIP = selectPodIp(sandbox.getStatus().getPodIPs());
        }
        Map<String, String> annotationsCopy =
                annotations != null ? new HashMap<>(annotations) : Map.of();
        return new SandboxResource.SandboxIdentity(name, podName, podIP, annotationsCopy);
    }

    private static boolean isNotFound(Exception e) {
        return e.getMessage() != null && e.getMessage().contains("NotFound");
    }
}
