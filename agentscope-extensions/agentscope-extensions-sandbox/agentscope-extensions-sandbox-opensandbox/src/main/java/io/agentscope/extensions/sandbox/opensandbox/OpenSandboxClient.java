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
package io.agentscope.extensions.sandbox.opensandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.util.UUID;

/** {@link SandboxClient} backed by OpenSandbox. */
public class OpenSandboxClient implements SandboxClient<OpenSandboxClientOptions> {
    private final ObjectMapper objectMapper;
    private final OpenSandboxClientOptions defaultOptions;
    private final OpenSandboxSdk sdk;

    public OpenSandboxClient() {
        this(new OpenSandboxClientOptions(), null);
    }

    public OpenSandboxClient(OpenSandboxClientOptions defaultOptions, ObjectMapper objectMapper) {
        this(defaultOptions, objectMapper, new OfficialOpenSandboxSdk());
    }

    OpenSandboxClient(
            OpenSandboxClientOptions defaultOptions,
            ObjectMapper objectMapper,
            OpenSandboxSdk sdk) {
        this.defaultOptions =
                defaultOptions != null
                        ? OpenSandboxClientOptions.copyOf(defaultOptions)
                        : new OpenSandboxClientOptions();
        this.objectMapper =
                objectMapper != null
                        ? objectMapper
                        : new ObjectMapper()
                                .findAndRegisterModules()
                                .registerModule(new HarnessSandboxJacksonModule())
                                .registerModule(new OpenSandboxHarnessSandboxJacksonModule());
        this.sdk = sdk != null ? sdk : new OfficialOpenSandboxSdk();
    }

    @Override
    public Sandbox create(
            WorkspaceSpec workspaceSpec,
            SandboxSnapshotSpec snapshotSpec,
            OpenSandboxClientOptions options) {
        OpenSandboxClientOptions merged = merge(options);
        OpenSandboxState state = new OpenSandboxState();
        state.setSessionId(UUID.randomUUID().toString());
        state.setWorkspaceSpec(workspaceSpec != null ? workspaceSpec.copy() : new WorkspaceSpec());
        state.setImage(merged.getImage());
        state.setEntrypoint(merged.getEntrypoint());
        state.setResourceLimits(merged.getResourceLimits());
        state.setSandboxTimeoutSeconds(merged.getSandboxTimeoutSeconds());
        state.setSandboxOwned(true);
        state.setWorkspaceRootReady(false);
        if (snapshotSpec != null) {
            state.setSnapshot(snapshotSpec.build(state.getSessionId()));
        }
        return new OpenSandbox(state, merged, sdk);
    }

    @Override
    public Sandbox resume(SandboxState state) {
        if (!(state instanceof OpenSandboxState openSandboxState)) {
            throw new IllegalArgumentException(
                    "Expected OpenSandboxState but got "
                            + (state == null ? "null" : state.getClass().getName()));
        }
        return new OpenSandbox(
                openSandboxState, OpenSandboxClientOptions.copyOf(defaultOptions), sdk);
    }

    @Override
    public void delete(Sandbox sandbox) {
        if (sandbox != null) {
            try {
                sandbox.shutdown();
            } catch (Exception e) {
                throw new SandboxException.SandboxRuntimeException(
                        "Failed to delete OpenSandbox", e);
            }
        }
    }

    @Override
    public String serializeState(SandboxState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "Failed to serialize OpenSandbox state", e);
        }
    }

    @Override
    public SandboxState deserializeState(String json) {
        try {
            return objectMapper.readValue(json, SandboxState.class);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "Failed to deserialize OpenSandbox state", e);
        }
    }

    private OpenSandboxClientOptions merge(OpenSandboxClientOptions call) {
        OpenSandboxClientOptions merged = OpenSandboxClientOptions.copyOf(defaultOptions);
        if (call == null) {
            return merged;
        }
        merged.setEndpoint(call.getEndpoint());
        if (call.getApiKey() != null) merged.setApiKey(call.getApiKey());
        merged.setImage(call.getImage());
        merged.setEntrypoint(call.getEntrypoint());
        merged.setResourceLimits(call.getResourceLimits());
        merged.setSandboxTimeoutSeconds(call.getSandboxTimeoutSeconds());
        merged.setReadyTimeoutSeconds(call.getReadyTimeoutSeconds());
        merged.setRequestTimeoutSeconds(call.getRequestTimeoutSeconds());
        merged.setUseServerProxy(call.isUseServerProxy());
        return merged;
    }
}
