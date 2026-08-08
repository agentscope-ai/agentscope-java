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
package io.agentscope.extensions.aistio.store;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.aistio.AistioConfig;
import io.agentscope.extensions.aistio.transport.ControlPlaneHttpClient;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.bus.AsyncToolRegistry;
import io.agentscope.harness.agent.bus.MessageBus;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.gateway.SessionTurnGate;
import io.agentscope.harness.agent.sandbox.SandboxExecutionGuard;
import io.agentscope.harness.agent.sandbox.snapshot.SandboxSnapshotSpec;
import io.agentscope.harness.agent.subagent.task.TaskRepository;
import io.agentscope.harness.agent.team.TeamClient;
import java.util.Objects;

/**
 * Factory for control-plane hosted store components ({@code /api/v1/dp/*}).
 *
 * <p>{@link AgentStateStore} is deliberately excluded — callers must supply their own via
 * {@link #withAgentStateStore(AgentStateStore)}.
 *
 * <pre>{@code
 * ControlPlaneStores cp = ControlPlaneStores.fromEnv();
 * HarnessAgent.builder()
 *     .distributedStore(cp.withAgentStateStore(redisStore.agentStateStore()))
 *     .filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER))
 *     .build();
 * }</pre>
 */
public final class ControlPlaneStores {

    private final ControlPlaneHttpClient http;
    private final String agentName;
    private final String namespace;

    private ControlPlaneStores(ControlPlaneHttpClient http, String agentName, String namespace) {
        this.http = Objects.requireNonNull(http, "http");
        this.agentName = Objects.requireNonNull(agentName, "agentName");
        this.namespace = (namespace == null || namespace.isBlank()) ? "default" : namespace;
    }

    /**
     * Creates a factory from environment variables:
     * {@code AISTIO_CONTROL_PLANE_HTTP}, {@code BUILDER_INTERNAL_TOKEN},
     * {@code AISTIO_AGENT_NAME}, {@code AISTIO_NAMESPACE}.
     */
    public static ControlPlaneStores fromEnv() {
        String httpBase = requireEnv("AISTIO_CONTROL_PLANE_HTTP");
        String token = requireEnv("BUILDER_INTERNAL_TOKEN");
        String agentName = requireEnv("AISTIO_AGENT_NAME");
        String namespace = envOr("AISTIO_NAMESPACE", "default");
        return create(httpBase, token, agentName, namespace);
    }

    /**
     * Creates a factory from explicit connection settings.
     *
     * @param httpBase control-plane HTTP base URL
     * @param token internal token for {@code X-Builder-Internal-Token}
     * @param agentName logical agent name
     * @param namespace tenant / Kubernetes namespace
     */
    public static ControlPlaneStores create(
            String httpBase, String token, String agentName, String namespace) {
        return new ControlPlaneStores(
                new ControlPlaneHttpClient(httpBase, token), agentName, namespace);
    }

    /**
     * Creates a factory reusing connection settings from an {@link AistioConfig}.
     *
     * @param config aistio bridge config (must have {@code controlPlaneHttp} and token set)
     */
    public static ControlPlaneStores from(AistioConfig config) {
        Objects.requireNonNull(config, "config");
        if (config.controlPlaneHttp() == null || config.controlPlaneHttp().isBlank()) {
            throw new IllegalArgumentException("controlPlaneHttp is required");
        }
        if (config.internalToken() == null || config.internalToken().isBlank()) {
            throw new IllegalArgumentException("internalToken is required");
        }
        return create(
                config.controlPlaneHttp(),
                config.internalToken(),
                config.agentName(),
                config.namespace());
    }

    /** Returns a control-plane backed {@link BaseStore}. */
    public BaseStore baseStore() {
        return new ControlPlaneBaseStore(http, agentName, namespace);
    }

    /** Returns a control-plane backed {@link SandboxExecutionGuard}. */
    public SandboxExecutionGuard sandboxExecutionGuard() {
        return new ControlPlaneSandboxExecutionGuard(http, agentName, namespace);
    }

    /** Returns a control-plane backed {@link SandboxSnapshotSpec}. */
    public SandboxSnapshotSpec sandboxSnapshotSpec() {
        return new ControlPlaneSnapshotSpec(http, agentName, namespace);
    }

    /**
     * Returns a control-plane backed {@link MessageBus}, or {@code null} when the hosted-store
     * endpoint is unreachable so {@code HarnessAgent} can fall back to {@code WorkspaceMessageBus}.
     */
    public MessageBus messageBus() {
        if (!hostedStoreReachable()) {
            return null;
        }
        return new ControlPlaneMessageBus(http, agentName, namespace);
    }

    /**
     * Returns a control-plane backed {@link AsyncToolRegistry}, or {@code null} when the
     * hosted-store endpoint is unreachable so {@code HarnessAgent} can fall back to the workspace
     * registry.
     */
    public AsyncToolRegistry asyncToolRegistry() {
        if (!hostedStoreReachable()) {
            return null;
        }
        return new ControlPlaneAsyncToolRegistry(http, agentName, namespace);
    }

    /** Returns a control-plane backed {@link TaskRepository}, or {@code null} when unreachable. */
    public TaskRepository taskRepository() {
        if (!hostedStoreReachable()) {
            return null;
        }
        return new ControlPlaneTaskRepository(http, agentName, namespace, agentName);
    }

    /**
     * Returns a control-plane backed {@link SessionTurnGate} for distributed per-session turn
     * serialization.
     *
     * <p>When wired via {@link #withAgentStateStore(AgentStateStore)}, callers should configure
     * {@link io.agentscope.core.ReActAgent} {@code conflictPolicy} to {@code FAIL}.
     */
    public SessionTurnGate sessionTurnGate() {
        return new ControlPlaneSessionTurnGate(http, agentName, namespace);
    }

    /** Returns a control-plane backed {@link io.agentscope.harness.agent.team.TeamClient}. */
    public TeamClient teamClient() {
        return new ControlPlaneTeamClient(http);
    }

    /**
     * Builds a {@link DistributedStore} by combining hosted components with a user-provided
     * {@link AgentStateStore}.
     *
     * @param stateStore required agent session state backend
     * @return a composite distributed store
     */
    public DistributedStore withAgentStateStore(AgentStateStore stateStore) {
        Objects.requireNonNull(stateStore, "stateStore");
        return DistributedStore.builder()
                .agentStateStore(stateStore)
                .baseStore(baseStore())
                .sandboxSnapshotSpec(sandboxSnapshotSpec())
                .sandboxExecutionGuard(sandboxExecutionGuard())
                .messageBus(messageBus())
                .asyncToolRegistry(asyncToolRegistry())
                .taskRepository(taskRepository())
                .sessionTurnGate(sessionTurnGate())
                .teamClient(teamClient())
                .build();
    }

    /** Probes {@code GET /api/v1/dp/healthz}; failures are treated as unreachable. */
    private boolean hostedStoreReachable() {
        try {
            ControlPlaneHttpClient.Response resp = http.send("GET", "/api/v1/dp/healthz", null);
            return resp.status() >= 200 && resp.status() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    private static String requireEnv(String name) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Required environment variable missing: " + name);
        }
        return v.trim();
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        if (v == null || v.isBlank()) {
            return fallback;
        }
        return v.trim();
    }
}
