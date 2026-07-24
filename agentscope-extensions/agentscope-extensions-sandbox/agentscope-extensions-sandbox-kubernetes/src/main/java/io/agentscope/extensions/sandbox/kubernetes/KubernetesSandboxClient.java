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
package io.agentscope.extensions.sandbox.kubernetes;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.extensions.sandbox.kubernetes.client.CreateSandboxOptions;
import io.agentscope.extensions.sandbox.kubernetes.client.SandboxClient;
import io.agentscope.extensions.sandbox.kubernetes.client.config.DirectConnectionConfig;
import io.agentscope.extensions.sandbox.kubernetes.client.config.GatewayConnectionConfig;
import io.agentscope.extensions.sandbox.kubernetes.client.config.LocalTunnelConnectionConfig;
import io.agentscope.extensions.sandbox.kubernetes.client.config.SandboxConnectionConfig;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxErrorCode;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Harness {@link io.agentscope.harness.agent.sandbox.SandboxClient} for
 * agent-sandbox backed Kubernetes sandboxes.
 *
 * <p>Delegates lifecycle and transport to the Java agent-sandbox client SDK
 * ({@link SandboxClient}).
 */
public class KubernetesSandboxClient
        implements io.agentscope.harness.agent.sandbox.SandboxClient<
                KubernetesSandboxClientOptions> {

    private static final Logger log = LoggerFactory.getLogger(KubernetesSandboxClient.class);

    private final ObjectMapper objectMapper;
    private final KubernetesSandboxClientOptions defaultOptions;

    public KubernetesSandboxClient() {
        this(new KubernetesSandboxClientOptions(), null);
    }

    public KubernetesSandboxClient(KubernetesSandboxClientOptions defaultOptions) {
        this(defaultOptions, null);
    }

    /**
     * @param defaultOptions template options merged into each {@link #create} call
     * @param objectMapper optional mapper; when null a default mapper is created with harness and
     *     Kubernetes Jackson modules
     */
    public KubernetesSandboxClient(
            KubernetesSandboxClientOptions defaultOptions, ObjectMapper objectMapper) {
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : new KubernetesSandboxClientOptions();
        this.objectMapper =
                objectMapper != null
                        ? objectMapper
                        : new ObjectMapper()
                                .findAndRegisterModules()
                                .registerModule(new HarnessSandboxJacksonModule())
                                .registerModule(new KubernetesHarnessSandboxJacksonModule());
    }

    @Override
    public Sandbox create(
            WorkspaceSpec workspaceSpec,
            SandboxSnapshotSpec snapshotSpec,
            KubernetesSandboxClientOptions options) {
        String sessionId = UUID.randomUUID().toString();
        KubernetesSandboxClientOptions merged = merge(options);

        KubernetesSandboxState state = new KubernetesSandboxState();
        state.setSessionId(sessionId);
        state.setWorkspaceSpec(workspaceSpec);
        state.setNamespace(merged.getNamespace());
        state.setFileApiBaseDir(merged.getFileApiBaseDir());
        state.setWarmPoolName(merged.getWarmPoolName());
        state.setClaimOwned(true);
        state.setWorkspaceRootReady(false);

        if (snapshotSpec != null) {
            state.setSnapshot(snapshotSpec.build(sessionId));
        }

        String claimName =
                "as-sbx-"
                        + sessionId
                                .replace("-", "")
                                .substring(0, Math.min(20, sessionId.replace("-", "").length()));
        state.setClaimName(claimName);

        log.debug(
                "[sandbox-k8s] Creating sandbox sessionId={} ns={} warmPool={} claim={}",
                sessionId,
                state.getNamespace(),
                state.getWarmPoolName(),
                claimName);

        try {
            SandboxClient sdkClient = buildSdkClient(merged);
            io.agentscope.extensions.sandbox.kubernetes.client.Sandbox sdkSandbox =
                    sdkClient.createSandbox(
                            CreateSandboxOptions.builder(merged.getWarmPoolName())
                                    .namespace(merged.getNamespace())
                                    .claimName(claimName)
                                    .sandboxReadyTimeoutSeconds(
                                            merged.getSandboxReadyTimeoutSeconds())
                                    .build());
            state.setSandboxName(sdkSandbox.sandboxId());
            String podIp = sdkSandbox.getPodIp();
            if (podIp != null) {
                state.setPodIP(podIp);
            }
            try {
                state.setPodName(sdkSandbox.getPodName());
            } catch (Exception e) {
                log.debug("[sandbox-k8s] Could not resolve pod name: {}", e.getMessage());
            }
            return new KubernetesSandbox(state, sdkSandbox);
        } catch (Exception e) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "Failed to create agent-sandbox client: " + e.getMessage(),
                    e);
        }
    }

    @Override
    public Sandbox resume(SandboxState state) {
        if (!(state instanceof KubernetesSandboxState k8s)) {
            throw new IllegalArgumentException(
                    "Expected KubernetesSandboxState but got: " + state.getClass().getName());
        }
        KubernetesSandboxClientOptions merged = merge(null);
        try {
            SandboxClient sdkClient = buildSdkClient(merged);
            io.agentscope.extensions.sandbox.kubernetes.client.Sandbox sdkSandbox =
                    sdkClient.getSandbox(k8s.getClaimName(), k8s.getNamespace());
            return new KubernetesSandbox(k8s, sdkSandbox);
        } catch (Exception e) {
            throw new SandboxException.SandboxRuntimeException(
                    SandboxErrorCode.WORKSPACE_START_ERROR,
                    "Failed to resume agent-sandbox client: " + e.getMessage(),
                    e);
        }
    }

    @Override
    public void delete(Sandbox sandbox) {
        // shutdown performs deletion for owned claims
    }

    @Override
    public String serializeState(SandboxState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "Failed to serialize Kubernetes sandbox state", e);
        }
    }

    @Override
    public SandboxState deserializeState(String json) {
        try {
            return objectMapper.readValue(json, SandboxState.class);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "Failed to deserialize Kubernetes sandbox state", e);
        }
    }

    private SandboxClient buildSdkClient(KubernetesSandboxClientOptions opts) {
        KubernetesClient kc = resolveClient(opts);
        SandboxConnectionConfig connectionConfig = toConnectionConfig(opts);
        return new SandboxClient(
                connectionConfig,
                kc,
                Duration.ofSeconds(opts.getRequestTimeoutSeconds()),
                Duration.ofSeconds(opts.getPerAttemptTimeoutSeconds()));
    }

    /**
     * Maps harness options to an SDK connection config.
     *
     * @param opts harness options
     * @return SDK connection config
     */
    public static SandboxConnectionConfig toConnectionConfig(KubernetesSandboxClientOptions opts) {
        if (opts.getApiUrl() != null && !opts.getApiUrl().isBlank()) {
            return new DirectConnectionConfig(opts.getApiUrl(), opts.getServerPort());
        }
        if (opts.getGatewayName() != null && !opts.getGatewayName().isBlank()) {
            String gwNs =
                    opts.getGatewayNamespace() != null
                            ? opts.getGatewayNamespace()
                            : opts.getNamespace();
            return new GatewayConnectionConfig(
                    opts.getGatewayName(),
                    gwNs,
                    opts.getGatewayScheme(),
                    opts.getSandboxReadyTimeoutSeconds(),
                    opts.getServerPort());
        }
        // Preserve prior harness behaviour: port-forward into the sandbox namespace.
        return new LocalTunnelConnectionConfig(
                opts.getPortForwardTimeoutSeconds(),
                opts.getServerPort(),
                opts.getNamespace() != null ? opts.getNamespace() : "default");
    }

    private KubernetesSandboxClientOptions merge(KubernetesSandboxClientOptions callOptions) {
        KubernetesSandboxClientOptions base =
                defaultOptions != null ? defaultOptions : new KubernetesSandboxClientOptions();
        if (callOptions == null) {
            return copy(base);
        }
        KubernetesSandboxClientOptions o = copy(base);
        if (callOptions.getKubernetesClient() != null) {
            o.setKubernetesClient(callOptions.getKubernetesClient());
        }
        if (callOptions.getKubernetesConfig() != null) {
            o.setKubernetesConfig(callOptions.getKubernetesConfig());
        }
        if (callOptions.getNamespace() != null) {
            o.setNamespace(callOptions.getNamespace());
        }
        if (callOptions.getWarmPoolName() != null) {
            o.setWarmPoolName(callOptions.getWarmPoolName());
        }
        if (callOptions.getFileApiBaseDir() != null) {
            o.setFileApiBaseDir(callOptions.getFileApiBaseDir());
        }
        if (callOptions.getApiUrl() != null) {
            o.setApiUrl(callOptions.getApiUrl());
        }
        if (callOptions.getGatewayName() != null) {
            o.setGatewayName(callOptions.getGatewayName());
        }
        if (callOptions.getGatewayNamespace() != null) {
            o.setGatewayNamespace(callOptions.getGatewayNamespace());
        }
        if (callOptions.getGatewayScheme() != null) {
            o.setGatewayScheme(callOptions.getGatewayScheme());
        }
        if (callOptions.getServerPort() > 0) {
            o.setServerPort(callOptions.getServerPort());
        }
        return o;
    }

    private static KubernetesSandboxClientOptions copy(KubernetesSandboxClientOptions src) {
        KubernetesSandboxClientOptions o = new KubernetesSandboxClientOptions();
        o.setKubernetesClient(src.getKubernetesClient());
        o.setKubernetesConfig(src.getKubernetesConfig());
        o.setNamespace(src.getNamespace());
        o.setWarmPoolName(src.getWarmPoolName());
        o.setFileApiBaseDir(src.getFileApiBaseDir());
        o.setApiUrl(src.getApiUrl());
        o.setGatewayName(src.getGatewayName());
        o.setGatewayNamespace(src.getGatewayNamespace());
        o.setGatewayScheme(src.getGatewayScheme());
        o.setServerPort(src.getServerPort());
        o.setSandboxReadyTimeoutSeconds(src.getSandboxReadyTimeoutSeconds());
        o.setCleanupTimeoutSeconds(src.getCleanupTimeoutSeconds());
        o.setRequestTimeoutSeconds(src.getRequestTimeoutSeconds());
        o.setPerAttemptTimeoutSeconds(src.getPerAttemptTimeoutSeconds());
        o.setPortForwardTimeoutSeconds(src.getPortForwardTimeoutSeconds());
        return o;
    }

    private static KubernetesClient resolveClient(KubernetesSandboxClientOptions merged) {
        if (merged.getKubernetesClient() != null) {
            return merged.getKubernetesClient();
        }
        if (merged.getKubernetesConfig() != null) {
            return new KubernetesClientBuilder().withConfig(merged.getKubernetesConfig()).build();
        }
        return new KubernetesClientBuilder().build();
    }
}
