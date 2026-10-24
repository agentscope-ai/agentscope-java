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
package io.agentscope.extensions.sandbox.e2b;

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

/** {@link SandboxClient} for E2B. */
public class E2bSandboxClient implements SandboxClient<E2bSandboxClientOptions> {

    private static final Logger log = LoggerFactory.getLogger(E2bSandboxClient.class);

    private final ObjectMapper objectMapper;
    private final E2bSandboxClientOptions defaultOptions;

    public E2bSandboxClient() {
        this(new E2bSandboxClientOptions(), null);
    }

    public E2bSandboxClient(E2bSandboxClientOptions defaultOptions, ObjectMapper objectMapper) {
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : new E2bSandboxClientOptions();
        this.objectMapper =
                objectMapper != null
                        ? objectMapper
                        : new ObjectMapper()
                                .findAndRegisterModules()
                                .registerModule(new HarnessSandboxJacksonModule())
                                .registerModule(new E2bHarnessSandboxJacksonModule());
    }

    @Override
    public Sandbox create(
            WorkspaceSpec workspaceSpec,
            SandboxSnapshotSpec snapshotSpec,
            E2bSandboxClientOptions options) {
        String sessionId = UUID.randomUUID().toString();
        E2bSandboxClientOptions merged = merge(options);
        requireAbsoluteWorkspaceRoot(merged.getWorkspaceRoot());

        E2bSandboxState state = new E2bSandboxState();
        state.setSessionId(sessionId);
        state.setWorkspaceSpec(workspaceSpec);
        state.setTemplateId(merged.getTemplateId());
        state.setWorkspaceRoot(merged.getWorkspaceRoot());
        state.setSandboxOwned(true);
        state.setWorkspaceRootReady(false);
        state.setPersistenceMode(merged.getPersistenceMode());
        state.setCodec(merged.getCodec());
        state.setSandboxDomain(merged.getDomain());
        state.setEnvdSandboxUrl(merged.getEnvdSandboxUrl());
        state.setVolumeMounts(merged.getVolumeMounts());
        state.setWorkspaceOnVolume(isWorkspaceOnVolume(merged));

        if (snapshotSpec != null) {
            state.setSnapshot(snapshotSpec.build(sessionId));
        }

        log.debug("[sandbox-e2b] Creating sandbox sessionId={}", sessionId);
        return new E2bSandbox(state, merged);
    }

    @Override
    public Sandbox resume(SandboxState state) {
        if (!(state instanceof E2bSandboxState e2b)) {
            throw new IllegalArgumentException(
                    "Expected E2bSandboxState but got: " + state.getClass().getName());
        }
        requireAbsoluteWorkspaceRoot(e2b.getWorkspaceRoot());
        E2bSandboxClientOptions resumed = copy(defaultOptions);
        resumed.setCodec(e2b.getCodec());
        resumed.setEnvdSandboxUrl(e2b.getEnvdSandboxUrl());
        return new E2bSandbox(e2b, resumed);
    }

    @Override
    public void delete(Sandbox sandbox) {}

    @Override
    public String serializeState(SandboxState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "Failed to serialize E2B sandbox state", e);
        }
    }

    @Override
    public SandboxState deserializeState(String json) {
        try {
            return objectMapper.readValue(json, SandboxState.class);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "Failed to deserialize E2B sandbox state", e);
        }
    }

    private E2bSandboxClientOptions merge(E2bSandboxClientOptions call) {
        E2bSandboxClientOptions o = copy(defaultOptions);
        if (call == null) {
            return o;
        }
        if (call.getApiKey() != null) {
            o.setApiKey(call.getApiKey());
        }
        if (call.getApiBaseUrl() != null) {
            o.setApiBaseUrl(call.getApiBaseUrl());
        }
        if (call.getDomain() != null) {
            o.setDomain(call.getDomain());
        }
        if (call.getEnvdSandboxUrl() != null) {
            o.setEnvdSandboxUrl(call.getEnvdSandboxUrl());
        }
        if (call.getSandboxTimeoutSeconds() > 0) {
            o.setSandboxTimeoutSeconds(call.getSandboxTimeoutSeconds());
        }
        if (call.getRunUser() != null) {
            o.setRunUser(call.getRunUser());
        }
        if (call.getPersistenceMode() != null) {
            o.setPersistenceMode(call.getPersistenceMode());
        }
        if (call.getCodec() != null) {
            o.setCodec(call.getCodec());
        }
        if (call.getTemplateId() != null) {
            o.setTemplateId(call.getTemplateId());
        }
        if (call.getWorkspaceRoot() != null) {
            o.setWorkspaceRoot(call.getWorkspaceRoot());
        }
        if (call.getHttpClient() != null) {
            o.setHttpClient(call.getHttpClient());
        }
        // Null or empty call mounts keep the defaults (same convention as AgentRun OSS mounts);
        // there is intentionally no "clear all mounts" via merge.
        if (call.getVolumeMounts() != null && !call.getVolumeMounts().isEmpty()) {
            o.setVolumeMounts(call.getVolumeMounts());
        }
        o.setConnectTimeoutSeconds(call.getConnectTimeoutSeconds());
        o.setReadTimeoutSeconds(call.getReadTimeoutSeconds());
        o.setMaxRetries(call.getMaxRetries());
        // 0 means "left at default" (indistinguishable from an explicit disable on a primitive),
        // so only a positive per-call value overrides; the client default governs otherwise.
        if (call.getSnapshotRetention() > 0) {
            o.setSnapshotRetention(call.getSnapshotRetention());
        }
        if (!call.getEnvs().isEmpty()) {
            o.getEnvs().putAll(call.getEnvs());
        }
        return o;
    }

    /**
     * Rejects blank or relative workspace roots fail-fast; every E2B operation assumes an
     * absolute in-sandbox path.
     *
     * @param workspaceRoot workspace root to check
     */
    private static void requireAbsoluteWorkspaceRoot(String workspaceRoot) {
        if (workspaceRoot == null
                || workspaceRoot.isBlank()
                || !workspaceRoot.strip().startsWith("/")) {
            throw new SandboxException.SandboxConfigurationException(
                    "E2B workspaceRoot must be an absolute in-sandbox path but was: "
                            + workspaceRoot);
        }
    }

    /**
     * Returns whether the workspace root lives on one of the configured volume mounts.
     *
     * @param opt merged client options
     * @return true when a mount covers the workspace root
     */
    private static boolean isWorkspaceOnVolume(E2bSandboxClientOptions opt) {
        String root = opt.getWorkspaceRoot();
        if (root == null || root.isBlank() || opt.getVolumeMounts() == null) {
            return false;
        }
        for (E2bVolumeMount m : opt.getVolumeMounts()) {
            if (m != null && E2bVolumeMount.coversWorkspaceRoot(root, m.getPath())) {
                return true;
            }
        }
        return false;
    }

    private static E2bSandboxClientOptions copy(E2bSandboxClientOptions src) {
        E2bSandboxClientOptions o = new E2bSandboxClientOptions();
        o.setApiKey(src.getApiKey());
        o.setApiBaseUrl(src.getApiBaseUrl());
        o.setDomain(src.getDomain());
        o.setEnvdSandboxUrl(src.getEnvdSandboxUrl());
        o.setTemplateId(src.getTemplateId());
        o.setWorkspaceRoot(src.getWorkspaceRoot());
        o.setSandboxTimeoutSeconds(src.getSandboxTimeoutSeconds());
        o.setRunUser(src.getRunUser());
        o.setPersistenceMode(src.getPersistenceMode());
        o.setCodec(src.getCodec());
        o.setHttpClient(src.getHttpClient());
        o.setVolumeMounts(src.getVolumeMounts());
        o.setConnectTimeoutSeconds(src.getConnectTimeoutSeconds());
        o.setReadTimeoutSeconds(src.getReadTimeoutSeconds());
        o.setMaxRetries(src.getMaxRetries());
        o.setSnapshotRetention(src.getSnapshotRetention());
        o.setEnvs(src.getEnvs());
        return o;
    }
}
