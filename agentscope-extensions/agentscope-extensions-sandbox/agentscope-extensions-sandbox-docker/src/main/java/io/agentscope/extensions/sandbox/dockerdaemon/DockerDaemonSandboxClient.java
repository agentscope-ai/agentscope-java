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
package io.agentscope.extensions.sandbox.dockerdaemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.RemoteSnapshotSpec;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshot;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SandboxClient} for the docker-java Docker daemon sandbox store.
 *
 * <p>Creates and manages Docker containers over the daemon's Engine HTTP API via docker-java.
 * No docker CLI, local socket, or docker-in-docker is required — point {@code daemonUrl} (or
 * {@code DOCKER_HOST}) at a reachable daemon.
 */
public class DockerDaemonSandboxClient implements SandboxClient<DockerDaemonSandboxClientOptions> {

    private static final Logger log = LoggerFactory.getLogger(DockerDaemonSandboxClient.class);

    private final ObjectMapper objectMapper;

    public DockerDaemonSandboxClient() {
        this(
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new DockerDaemonHarnessSandboxJacksonModule()));
    }

    /**
     * Uses the given mapper as-is. For {@link SandboxState} JSON round-trip, register
     * {@link DockerDaemonHarnessSandboxJacksonModule} on this mapper before calling
     * {@link #deserializeState}.
     *
     * @param objectMapper mapper to use
     */
    public DockerDaemonSandboxClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Sandbox create(
            WorkspaceSpec workspaceSpec,
            SandboxSnapshotSpec snapshotSpec,
            DockerDaemonSandboxClientOptions options) {
        String sessionId = UUID.randomUUID().toString();

        String image =
                options != null && options.getImage() != null ? options.getImage() : "ubuntu:22.04";
        String workspaceRoot =
                options != null && options.getWorkspaceRoot() != null
                        ? options.getWorkspaceRoot()
                        : "/workspace";

        DockerDaemonSandboxState state = new DockerDaemonSandboxState();
        state.setSessionId(sessionId);
        state.setWorkspaceSpec(workspaceSpec);
        state.setImage(image);
        state.setWorkspaceRoot(workspaceRoot);
        state.setContainerOwned(true);
        state.setWorkspaceRootReady(false);
        state.setDaemonUrl(
                DockerDaemonClientFactory.resolveDaemonUrl(
                        options != null ? options.getDaemonUrl() : null,
                        System.getenv("DOCKER_HOST")));

        if (options != null) {
            state.setMemorySizeBytes(options.getMemorySizeBytes());
            state.setCpuCount(options.getCpuCount());
            state.setExposedPorts(options.getExposedPorts());
            state.setNetwork(options.getNetwork());
            state.setMounts(options.getMounts());
            mergeEnvironment(workspaceSpec, options.getEnvironment());
        }

        if (snapshotSpec != null) {
            state.setSnapshot(snapshotSpec.build(sessionId));
        }

        log.debug(
                "[sandbox-docker-daemon] Creating new sandbox: id={}, image={}", sessionId, image);
        return new DockerDaemonSandbox(state);
    }

    /**
     * Merges option environment variables into the workspace spec (the context's private copy).
     * Options win on key conflicts.
     *
     * @param workspaceSpec workspace spec to mutate
     * @param optionEnv     environment from {@link DockerDaemonSandboxClientOptions}
     */
    private static void mergeEnvironment(
            WorkspaceSpec workspaceSpec, Map<String, String> optionEnv) {
        if (optionEnv == null || optionEnv.isEmpty()) {
            return;
        }
        Map<String, String> merged = new LinkedHashMap<>(workspaceSpec.getEnvironment());
        merged.putAll(optionEnv);
        workspaceSpec.setEnvironment(merged);
    }

    @Override
    public Sandbox resume(SandboxState state) {
        if (!(state instanceof DockerDaemonSandboxState dockerState)) {
            throw new IllegalArgumentException(
                    "Expected DockerDaemonSandboxState but got: " + state.getClass().getName());
        }
        log.debug(
                "[sandbox-docker-daemon] Resuming sandbox: id={}, containerId={}",
                dockerState.getSessionId(),
                dockerState.getContainerId());
        if (dockerState.getWorkspaceSpec() == null) {
            // AbstractBaseSandbox requires a non-null workspace spec at construction.
            dockerState.setWorkspaceSpec(new WorkspaceSpec());
        }
        return new DockerDaemonSandbox(dockerState);
    }

    @Override
    public void delete(Sandbox sandbox) {
        // No-op: cleanup is handled by DockerDaemonSandbox.shutdown()
    }

    @Override
    public String serializeState(SandboxState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "Failed to serialize Docker daemon sandbox state", e);
        }
    }

    @Override
    public SandboxState deserializeState(String json) {
        try {
            return objectMapper.readValue(json, SandboxState.class);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "Failed to deserialize Docker daemon sandbox state", e);
        }
    }

    @Override
    public SandboxState deserializeState(String json, SandboxSnapshotSpec snapshotSpec) {
        try {
            SandboxState state = objectMapper.readValue(json, SandboxState.class);
            rebindRemoteSnapshot(state, snapshotSpec);
            return state;
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "Failed to deserialize Docker daemon sandbox state", e);
        }
    }

    private static void rebindRemoteSnapshot(SandboxState state, SandboxSnapshotSpec snapshotSpec) {
        if (!(snapshotSpec instanceof RemoteSnapshotSpec remoteSnapshotSpec)) {
            return;
        }
        SandboxSnapshot snapshot = state.getSnapshot();
        if (!(snapshot instanceof RemoteSandboxSnapshot)) {
            return;
        }
        state.setSnapshot(
                new RemoteSandboxSnapshot(remoteSnapshotSpec.getClient(), snapshot.getId()));
    }
}
