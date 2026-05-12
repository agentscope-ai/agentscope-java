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
package io.agentscope.harness.agent.sandbox.impl.kubernetes;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@link SandboxClient} for Kubernetes Pods. */
public class KubernetesSandboxClient implements SandboxClient<KubernetesSandboxClientOptions> {

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
        state.setContainerName(merged.getContainerName());
        state.setWorkspaceRoot(merged.getWorkspaceRoot());
        state.setImage(merged.getImage());
        state.setPodOwned(true);
        state.setWorkspaceRootReady(false);

        if (snapshotSpec != null) {
            state.setSnapshot(snapshotSpec.build(sessionId));
        }

        KubernetesClient kc = resolveClient(merged);
        Fabric8KubernetesPodRuntime runtime = new Fabric8KubernetesPodRuntime(kc, merged);
        log.debug(
                "[sandbox-k8s] Creating sandbox sessionId={} ns={}",
                sessionId,
                state.getNamespace());
        return new KubernetesSandbox(state, runtime);
    }

    @Override
    public Sandbox resume(SandboxState state) {
        if (!(state instanceof KubernetesSandboxState k8s)) {
            throw new IllegalArgumentException(
                    "Expected KubernetesSandboxState but got: " + state.getClass().getName());
        }
        KubernetesSandboxClientOptions merged = merge(null);
        KubernetesClient kc = resolveClient(merged);
        Fabric8KubernetesPodRuntime runtime = new Fabric8KubernetesPodRuntime(kc, merged);
        return new KubernetesSandbox(k8s, runtime);
    }

    @Override
    public void delete(Sandbox sandbox) {
        // shutdown performs deletion for owned pods
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
        if (callOptions.getImage() != null) {
            o.setImage(callOptions.getImage());
        }
        if (callOptions.getContainerName() != null) {
            o.setContainerName(callOptions.getContainerName());
        }
        if (callOptions.getWorkspaceRoot() != null) {
            o.setWorkspaceRoot(callOptions.getWorkspaceRoot());
        }
        if (callOptions.getServiceAccount() != null) {
            o.setServiceAccount(callOptions.getServiceAccount());
        }
        if (callOptions.getNodeSelector() != null && !callOptions.getNodeSelector().isEmpty()) {
            o.setNodeSelector(callOptions.getNodeSelector());
        }
        if (callOptions.getPodLabels() != null && !callOptions.getPodLabels().isEmpty()) {
            o.setPodLabels(callOptions.getPodLabels());
        }
        if (callOptions.getCpuRequest() != null) {
            o.setCpuRequest(callOptions.getCpuRequest());
        }
        if (callOptions.getMemoryRequest() != null) {
            o.setMemoryRequest(callOptions.getMemoryRequest());
        }
        return o;
    }

    private static KubernetesSandboxClientOptions copy(KubernetesSandboxClientOptions src) {
        KubernetesSandboxClientOptions o = new KubernetesSandboxClientOptions();
        o.setKubernetesClient(src.getKubernetesClient());
        o.setKubernetesConfig(src.getKubernetesConfig());
        o.setNamespace(src.getNamespace());
        o.setImage(src.getImage());
        o.setContainerName(src.getContainerName());
        o.setWorkspaceRoot(src.getWorkspaceRoot());
        o.setServiceAccount(src.getServiceAccount());
        o.setNodeSelector(src.getNodeSelector());
        o.setPodLabels(src.getPodLabels());
        o.setCpuRequest(src.getCpuRequest());
        o.setMemoryRequest(src.getMemoryRequest());
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
