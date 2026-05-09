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
package io.agentscope.harness.agent.sandbox.impl.docker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link SandboxClient} implementation for the Docker sandbox backend.
 *
 * <p>Creates and manages Docker containers via the {@code docker} CLI. The Docker daemon must
 * be accessible from the host's {@code PATH}.
 */
public class DockerSandboxClient implements SandboxClient<DockerSandboxClientOptions> {

    private static final Logger log = LoggerFactory.getLogger(DockerSandboxClient.class);

    private final ObjectMapper objectMapper;

    public DockerSandboxClient() {
        this.objectMapper =
                new ObjectMapper()
                        .findAndRegisterModules()
                        .registerModule(new HarnessSandboxJacksonModule());
    }

    /**
     * Uses the given mapper as-is. For {@link SandboxState} JSON round-trip, register {@link
     * HarnessSandboxJacksonModule} (and any extra {@code NamedType} for custom state subclasses)
     * on this mapper before calling {@link #deserializeState}.
     */
    public DockerSandboxClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Sandbox create(
            WorkspaceSpec workspaceSpec,
            SandboxSnapshotSpec snapshotSpec,
            DockerSandboxClientOptions options) {
        String sessionId = UUID.randomUUID().toString();

        String image =
                options != null && options.getImage() != null ? options.getImage() : "ubuntu:22.04";
        String workspaceRoot =
                options != null && options.getWorkspaceRoot() != null
                        ? options.getWorkspaceRoot()
                        : "/workspace";

        DockerSandboxState state = new DockerSandboxState();
        state.setSessionId(sessionId);
        state.setWorkspaceSpec(workspaceSpec);
        state.setImage(image);
        state.setWorkspaceRoot(workspaceRoot);
        state.setContainerOwned(true);
        state.setWorkspaceRootReady(false);

        if (options != null) {
            state.setMemorySizeBytes(options.getMemorySizeBytes());
            state.setCpuCount(options.getCpuCount());
            state.setExposedPorts(options.getExposedPorts());
            state.setNetwork(options.getNetwork());
            state.setAdditionalRunArgs(options.getAdditionalRunArgs());
        }

        if (snapshotSpec != null) {
            state.setSnapshot(snapshotSpec.build(sessionId));
        }

        log.debug("[sandbox-docker] Creating new sandbox: id={}, image={}", sessionId, image);
        return new DockerSandbox(state);
    }

    @Override
    public Sandbox resume(SandboxState state) {
        if (!(state instanceof DockerSandboxState dockerState)) {
            throw new IllegalArgumentException(
                    "Expected DockerSandboxState but got: " + state.getClass().getName());
        }
        log.debug(
                "[sandbox-docker] Resuming sandbox: id={}, containerId={}",
                dockerState.getSessionId(),
                dockerState.getContainerId());
        return new DockerSandbox(dockerState);
    }

    @Override
    public void delete(Sandbox sandbox) {
        // No-op: cleanup is handled by DockerSandbox.shutdown()
    }

    @Override
    public String serializeState(SandboxState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "Failed to serialize Docker sandbox state", e);
        }
    }

    @Override
    public SandboxState deserializeState(String json) {
        try {
            return objectMapper.readValue(json, SandboxState.class);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "Failed to deserialize Docker sandbox state", e);
        }
    }
}
