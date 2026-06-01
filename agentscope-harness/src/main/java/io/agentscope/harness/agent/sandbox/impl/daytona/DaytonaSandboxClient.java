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
package io.agentscope.harness.agent.sandbox.impl.daytona;

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

/** {@link SandboxClient} for Daytona. */
public class DaytonaSandboxClient implements SandboxClient<DaytonaSandboxClientOptions> {

    private static final Logger log = LoggerFactory.getLogger(DaytonaSandboxClient.class);

    private final ObjectMapper objectMapper;
    private final DaytonaSandboxClientOptions defaultOptions;

    public DaytonaSandboxClient() {
        this(new DaytonaSandboxClientOptions(), null);
    }

    public DaytonaSandboxClient(
            DaytonaSandboxClientOptions defaultOptions, ObjectMapper objectMapper) {
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : new DaytonaSandboxClientOptions();
        this.objectMapper =
                objectMapper != null
                        ? objectMapper
                        : new ObjectMapper()
                                .findAndRegisterModules()
                                .registerModule(new HarnessSandboxJacksonModule())
                                .registerModule(new DaytonaHarnessSandboxJacksonModule());
    }

    @Override
    public Sandbox create(
            WorkspaceSpec workspaceSpec,
            SandboxSnapshotSpec snapshotSpec,
            DaytonaSandboxClientOptions options) {
        String sessionId = UUID.randomUUID().toString();
        DaytonaSandboxClientOptions merged = merge(options);

        DaytonaSandboxState state = new DaytonaSandboxState();
        state.setSessionId(sessionId);
        state.setWorkspaceSpec(workspaceSpec);
        state.setWorkspaceRoot(merged.getWorkspaceRoot());
        state.setImage(merged.getImage());
        state.setSnapshotId(merged.getSnapshotId());
        state.setSandboxOwned(true);
        state.setWorkspaceRootReady(false);

        if (snapshotSpec != null) {
            state.setSnapshot(snapshotSpec.build(sessionId));
        }

        log.debug("[sandbox-daytona] Creating sandbox sessionId={}", sessionId);
        return new DaytonaSandbox(state, new DaytonaHttp(merged));
    }

    @Override
    public Sandbox resume(SandboxState state) {
        if (!(state instanceof DaytonaSandboxState d)) {
            throw new IllegalArgumentException(
                    "Expected DaytonaSandboxState but got: " + state.getClass().getName());
        }
        return new DaytonaSandbox(d, new DaytonaHttp(merge(null)));
    }

    @Override
    public void delete(Sandbox sandbox) {}

    @Override
    public String serializeState(SandboxState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "Failed to serialize Daytona sandbox state", e);
        }
    }

    @Override
    public SandboxState deserializeState(String json) {
        try {
            return objectMapper.readValue(json, SandboxState.class);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "Failed to deserialize Daytona sandbox state", e);
        }
    }

    private DaytonaSandboxClientOptions merge(DaytonaSandboxClientOptions call) {
        DaytonaSandboxClientOptions o = copy(defaultOptions);
        if (call == null) {
            return o;
        }
        if (call.getApiKey() != null) {
            o.setApiKey(call.getApiKey());
        }
        if (call.getControlPlaneBaseUrl() != null) {
            o.setControlPlaneBaseUrl(call.getControlPlaneBaseUrl());
        }
        if (call.getToolboxBaseUrl() != null) {
            o.setToolboxBaseUrl(call.getToolboxBaseUrl());
        }
        if (call.getImage() != null) {
            o.setImage(call.getImage());
        }
        if (call.getSnapshotId() != null) {
            o.setSnapshotId(call.getSnapshotId());
        }
        if (call.getCpu() != null) {
            o.setCpu(call.getCpu());
        }
        if (call.getMemory() != null) {
            o.setMemory(call.getMemory());
        }
        if (call.getDisk() != null) {
            o.setDisk(call.getDisk());
        }
        if (call.getWorkspaceRoot() != null) {
            o.setWorkspaceRoot(call.getWorkspaceRoot());
        }
        if (call.getHttpClient() != null) {
            o.setHttpClient(call.getHttpClient());
        }
        o.setConnectTimeoutSeconds(call.getConnectTimeoutSeconds());
        o.setReadTimeoutSeconds(call.getReadTimeoutSeconds());
        o.setMaxRetries(call.getMaxRetries());
        return o;
    }

    private static DaytonaSandboxClientOptions copy(DaytonaSandboxClientOptions src) {
        DaytonaSandboxClientOptions o = new DaytonaSandboxClientOptions();
        o.setApiKey(src.getApiKey());
        o.setControlPlaneBaseUrl(src.getControlPlaneBaseUrl());
        o.setToolboxBaseUrl(src.getToolboxBaseUrl());
        o.setImage(src.getImage());
        o.setSnapshotId(src.getSnapshotId());
        o.setCpu(src.getCpu());
        o.setMemory(src.getMemory());
        o.setDisk(src.getDisk());
        o.setWorkspaceRoot(src.getWorkspaceRoot());
        o.setHttpClient(src.getHttpClient());
        o.setConnectTimeoutSeconds(src.getConnectTimeoutSeconds());
        o.setReadTimeoutSeconds(src.getReadTimeoutSeconds());
        o.setMaxRetries(src.getMaxRetries());
        return o;
    }
}
