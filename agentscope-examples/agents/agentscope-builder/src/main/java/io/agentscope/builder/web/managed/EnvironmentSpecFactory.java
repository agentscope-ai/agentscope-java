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
package io.agentscope.builder.web.managed;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.InMemoryAgentStateStore;
import io.agentscope.core.state.JsonFileAgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.sandbox.impl.docker.DockerFilesystemSpec;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Maps Managed Agents {@link EnvironmentDto} (and agent-level sandbox fields) onto harness
 * filesystem specs.
 */
@Component
public class EnvironmentSpecFactory {

    private final Optional<BaseStore> baseStore;
    private final Optional<AgentStateStore> stateStore;

    public EnvironmentSpecFactory(
            Optional<BaseStore> baseStore, Optional<AgentStateStore> stateStore) {
        this.baseStore = baseStore;
        this.stateStore = stateStore;
    }

    /** Applies filesystem configuration from an environment resource onto a builder. */
    public void applyEnvironment(HarnessAgent.Builder builder, EnvironmentDto environment) {
        if (environment == null) {
            applyLocal(builder, IsolationScope.SESSION);
            return;
        }
        IsolationScope scope = isolationFromConfig(environment.config());
        String type = environment.type() == null ? "local" : environment.type().toLowerCase();
        switch (type) {
            case "sandbox" -> applySandbox(builder, scope, environment.config());
            case "remote" -> applyRemote(builder, scope);
            default -> applyLocal(builder, scope);
        }
    }

    /**
     * Applies filesystem configuration from agent-level sandboxMode / sandboxScope fields (used
     * when no session environment is available at build time).
     */
    public void applyAgentSandboxFields(
            HarnessAgent.Builder builder, String sandboxMode, String sandboxScope) {
        IsolationScope scope = parseScope(sandboxScope, IsolationScope.SESSION);
        String mode = sandboxMode == null ? "local" : sandboxMode.toLowerCase();
        switch (mode) {
            case "sandbox" -> applySandbox(builder, scope, Map.of());
            case "remote" -> applyRemote(builder, scope);
            default -> applyLocal(builder, scope);
        }
    }

    private void applyLocal(HarnessAgent.Builder builder, IsolationScope scope) {
        builder.filesystem(new LocalFilesystemSpec().isolationScope(scope));
    }

    private void applyRemote(HarnessAgent.Builder builder, IsolationScope scope) {
        AgentStateStore store = stateStore.orElse(null);
        if (store == null
                || store instanceof InMemoryAgentStateStore
                || store instanceof JsonFileAgentStateStore
                || baseStore.isEmpty()) {
            applyLocal(builder, scope);
            return;
        }
        builder.filesystem(
                new RemoteFilesystemSpec(baseStore.get())
                        .isolationScope(scope)
                        .addSharedPrefix("activity/"));
        builder.stateStore(store);
    }

    private void applySandbox(
            HarnessAgent.Builder builder, IsolationScope scope, Map<String, Object> config) {
        DockerFilesystemSpec spec = new DockerFilesystemSpec();
        if (config != null) {
            Object image = config.get("image");
            if (image instanceof String s && !s.isBlank()) {
                spec.image(s);
            }
            Object mem = config.get("memoryMb");
            if (mem instanceof Number n) {
                spec.memorySizeBytes(n.longValue() * 1024L * 1024L);
            }
            Object cpus = config.get("cpus");
            if (cpus instanceof Number n) {
                spec.cpuCount(n.longValue());
            }
        }
        spec.isolationScope(scope);
        builder.filesystem(spec);
    }

    private static IsolationScope isolationFromConfig(Map<String, Object> config) {
        if (config == null) {
            return IsolationScope.SESSION;
        }
        Object raw = config.get("isolationScope");
        return parseScope(raw == null ? null : String.valueOf(raw), IsolationScope.SESSION);
    }

    private static IsolationScope parseScope(String raw, IsolationScope fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return IsolationScope.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }
}
