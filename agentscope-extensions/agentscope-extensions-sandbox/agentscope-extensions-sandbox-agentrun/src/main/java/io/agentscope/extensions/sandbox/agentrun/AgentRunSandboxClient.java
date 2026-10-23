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
package io.agentscope.extensions.sandbox.agentrun;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.sandbox.Sandbox;
import io.agentscope.harness.agent.sandbox.SandboxClient;
import io.agentscope.harness.agent.sandbox.SandboxException;
import io.agentscope.harness.agent.sandbox.SandboxState;
import io.agentscope.harness.agent.sandbox.WorkspaceSpec;
import io.agentscope.harness.agent.sandbox.json.HarnessSandboxJacksonModule;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** {@link SandboxClient} for Alibaba Cloud AgentRun. */
public class AgentRunSandboxClient implements SandboxClient<AgentRunSandboxClientOptions> {

    private static final Logger log = LoggerFactory.getLogger(AgentRunSandboxClient.class);

    /** Crockford Base32 alphabet (ULID-style). */
    private static final char[] CROCKFORD32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    /** Length of an AgentRun sandboxId (matches the public ULID example). */
    private static final int SANDBOX_ID_LENGTH = 26;

    private final ObjectMapper objectMapper;
    private final AgentRunSandboxClientOptions defaultOptions;

    public AgentRunSandboxClient() {
        this(new AgentRunSandboxClientOptions(), null);
    }

    public AgentRunSandboxClient(
            AgentRunSandboxClientOptions defaultOptions, ObjectMapper objectMapper) {
        this.defaultOptions =
                defaultOptions != null ? defaultOptions : new AgentRunSandboxClientOptions();
        this.objectMapper =
                objectMapper != null
                        ? objectMapper
                        : new ObjectMapper()
                                .findAndRegisterModules()
                                .registerModule(new HarnessSandboxJacksonModule())
                                .registerModule(new AgentRunHarnessSandboxJacksonModule());
    }

    @Override
    public Sandbox create(
            WorkspaceSpec workspaceSpec,
            SandboxSnapshotSpec snapshotSpec,
            AgentRunSandboxClientOptions options) {
        AgentRunSandboxClientOptions merged = merge(options);
        merged.validate();

        String sessionId = UUID.randomUUID().toString();
        String sandboxId = deriveSandboxId(sessionId);

        AgentRunSandboxState state = new AgentRunSandboxState();
        state.setSessionId(sessionId);
        state.setWorkspaceSpec(workspaceSpec);
        state.setWorkspaceRoot(merged.getWorkspaceRoot());
        state.setTemplateName(merged.getTemplateName());
        state.setAccountId(merged.getAccountId());
        state.setRegion(merged.getRegion());
        state.setMcpServerUrl(merged.getMcpServerUrl());
        state.setSandboxId(sandboxId);
        state.setSandboxOwned(true);
        state.setWorkspaceRootReady(false);
        state.setWorkspaceOnNas(isWorkspaceUnderMounts(merged));

        if (snapshotSpec != null) {
            state.setSnapshot(snapshotSpec.build(sessionId));
        }

        log.debug(
                "[sandbox-agentrun] Creating sandbox sessionId={} sandboxId={} onNas={}",
                sessionId,
                sandboxId,
                state.isWorkspaceOnNas());
        return build(state, merged);
    }

    @Override
    public Sandbox resume(SandboxState state) {
        if (!(state instanceof AgentRunSandboxState ar)) {
            throw new IllegalArgumentException(
                    "Expected AgentRunSandboxState but got: " + state.getClass().getName());
        }
        return build(ar, merge(null));
    }

    @Override
    public void delete(Sandbox sandbox) {
        if (sandbox == null) {
            return;
        }
        try {
            sandbox.close();
        } catch (Exception e) {
            log.warn("[sandbox-agentrun] delete: close failed: {}", e.getMessage());
        }
    }

    @Override
    public String serializeState(SandboxState state) {
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "Failed to serialize AgentRun sandbox state", e);
        }
    }

    @Override
    public SandboxState deserializeState(String json) {
        try {
            return objectMapper.readValue(json, SandboxState.class);
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "Failed to deserialize AgentRun sandbox state", e);
        }
    }

    private Sandbox build(AgentRunSandboxState state, AgentRunSandboxClientOptions merged) {
        AgentRunDataPlaneHttp http = new AgentRunDataPlaneHttp(merged);
        AgentRunMcpChannel mcp = new AgentRunMcpChannel(merged);
        return new AgentRunSandbox(state, merged, http, mcp);
    }

    private static boolean isWorkspaceUnderMounts(AgentRunSandboxClientOptions opt) {
        String root = opt.getWorkspaceRoot();
        if (root == null || root.isBlank()) {
            return false;
        }
        AgentRunNasMountConfig nas = opt.getNasConfig();
        if (nas != null && nas.getMountDir() != null && root.startsWith(nas.getMountDir())) {
            return true;
        }
        if (opt.getOssMountConfigs() != null) {
            for (AgentRunOssMountConfig m : opt.getOssMountConfigs()) {
                if (m != null
                        && m.getMountDir() != null
                        && !m.isReadOnly()
                        && root.startsWith(m.getMountDir())) {
                    return true;
                }
            }
        }
        return false;
    }

    private AgentRunSandboxClientOptions merge(AgentRunSandboxClientOptions call) {
        AgentRunSandboxClientOptions o = copy(defaultOptions);
        if (call == null) {
            return o;
        }
        if (call.getApiKey() != null) {
            o.setApiKey(call.getApiKey());
        }
        if (call.getAccountId() != null) {
            o.setAccountId(call.getAccountId());
        }
        if (call.getRegion() != null) {
            o.setRegion(call.getRegion());
        }
        if (call.getDataPlaneBaseUrl() != null) {
            o.setDataPlaneBaseUrl(call.getDataPlaneBaseUrl());
        }
        if (call.getTemplateName() != null) {
            o.setTemplateName(call.getTemplateName());
        }
        if (call.getMcpServerUrl() != null) {
            o.setMcpServerUrl(call.getMcpServerUrl());
        }
        if (call.getMcpEndpoint() != null) {
            o.setMcpEndpoint(call.getMcpEndpoint());
        }
        if (call.getNasConfig() != null) {
            o.setNasConfig(call.getNasConfig());
        }
        if (call.getOssMountConfigs() != null && !call.getOssMountConfigs().isEmpty()) {
            o.setOssMountConfigs(call.getOssMountConfigs());
        }
        if (call.getWorkspaceRoot() != null) {
            o.setWorkspaceRoot(call.getWorkspaceRoot());
        }
        if (call.getHttpClient() != null) {
            o.setHttpClient(call.getHttpClient());
        }
        o.setSandboxIdleTimeoutSeconds(call.getSandboxIdleTimeoutSeconds());
        o.setConnectTimeoutSeconds(call.getConnectTimeoutSeconds());
        o.setReadTimeoutSeconds(call.getReadTimeoutSeconds());
        o.setMaxRetries(call.getMaxRetries());
        return o;
    }

    private static AgentRunSandboxClientOptions copy(AgentRunSandboxClientOptions src) {
        AgentRunSandboxClientOptions o = new AgentRunSandboxClientOptions();
        o.setApiKey(src.getApiKey());
        o.setAccountId(src.getAccountId());
        o.setRegion(src.getRegion());
        o.setDataPlaneBaseUrl(src.getDataPlaneBaseUrl());
        o.setTemplateName(src.getTemplateName());
        o.setMcpServerUrl(src.getMcpServerUrl());
        o.setMcpEndpoint(src.getMcpEndpoint());
        o.setSandboxIdleTimeoutSeconds(src.getSandboxIdleTimeoutSeconds());
        o.setNasConfig(src.getNasConfig());
        o.setOssMountConfigs(src.getOssMountConfigs());
        o.setWorkspaceRoot(src.getWorkspaceRoot());
        o.setHttpClient(src.getHttpClient());
        o.setConnectTimeoutSeconds(src.getConnectTimeoutSeconds());
        o.setReadTimeoutSeconds(src.getReadTimeoutSeconds());
        o.setMaxRetries(src.getMaxRetries());
        return o;
    }

    /**
     * Derives a deterministic 26-character Crockford-base32 sandbox id from {@code sessionId}.
     *
     * <p>The first 26 characters of {@code base32(SHA-256(sessionId))} are used. This shape
     * matches AgentRun's public ULID example and lets "resume same session" map to "recreate
     * sandbox with same id".
     *
     * @param sessionId session identifier
     * @return deterministic sandbox id with the AgentRun-acceptable shape
     */
    static String deriveSandboxId(String sessionId) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(sessionId.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(SANDBOX_ID_LENGTH);
            int bitBuffer = 0;
            int bitsInBuffer = 0;
            int byteIndex = 0;
            while (sb.length() < SANDBOX_ID_LENGTH) {
                if (bitsInBuffer < 5) {
                    if (byteIndex >= digest.length) {
                        // Should not happen for SHA-256 (256 bits → 51 base32 chars)
                        break;
                    }
                    bitBuffer = (bitBuffer << 8) | (digest[byteIndex++] & 0xFF);
                    bitsInBuffer += 8;
                }
                int shift = bitsInBuffer - 5;
                int idx = (bitBuffer >> shift) & 0x1F;
                bitBuffer &= (1 << shift) - 1;
                bitsInBuffer -= 5;
                sb.append(CROCKFORD32[idx]);
            }
            return sb.toString();
        } catch (Exception e) {
            throw new SandboxException.SandboxConfigurationException(
                    "Failed to derive AgentRun sandbox id", e);
        }
    }
}
