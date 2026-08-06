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

import io.agentscope.builder.web.api.error.ApiException;
import io.agentscope.builder.web.config.BuilderE2bProperties;
import io.agentscope.extensions.sandbox.e2b.E2bFilesystemSpec;
import io.agentscope.extensions.sandbox.e2b.E2bPersistenceMode;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.spec.LocalFilesystemSpec;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Maps Managed Agents {@link EnvironmentDto} onto harness filesystem / sandbox specs.
 *
 * <p>Type semantics:
 *
 * <ul>
 *   <li>{@code local} — host {@link LocalFilesystemSpec} (dev; not production hands)
 *   <li>{@code sandbox} — E2B cloud sandbox via {@link E2bFilesystemSpec} (platform-managed hands)
 *   <li>{@code remote} — distributed KV filesystem (no shell); for shared artifacts / LTM routing
 *   <li>{@code self_hosted} — Brain-local filesystem/shell tools disabled; schema-only external
 *       hands tools suspend for an Environment Worker (Claude-style outbound execution)
 * </ul>
 *
 * <p>Managed session default {@link IsolationScope} is {@link IsolationScope#SESSION}.
 */
@Component
public class EnvironmentSpecFactory {

    private static final Logger log = LoggerFactory.getLogger(EnvironmentSpecFactory.class);

    private static final Set<String> LEGACY_DOCKER_CONFIG_KEYS =
            Set.of(
                    "image",
                    "cpus",
                    "memoryMb",
                    "network",
                    "exposedPorts",
                    "additionalRunArgs",
                    "networkMode",
                    "allowed_hosts",
                    "packages");

    private final Optional<BaseStore> baseStore;
    private final Optional<io.agentscope.core.state.AgentStateStore> stateStore;
    private final BuilderE2bProperties e2bProperties;

    public EnvironmentSpecFactory(
            Optional<BaseStore> baseStore,
            Optional<io.agentscope.core.state.AgentStateStore> stateStore,
            BuilderE2bProperties e2bProperties) {
        this.baseStore = baseStore;
        this.stateStore = stateStore;
        this.e2bProperties = e2bProperties;
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
            case EnvironmentTypes.TYPE_SANDBOX ->
                    applySandbox(builder, scope, environment.config());
            case EnvironmentTypes.TYPE_REMOTE -> applyRemote(builder, scope);
            case EnvironmentTypes.TYPE_SELF_HOSTED ->
                    applySelfHosted(builder, scope, environment.config());
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
            case "self_hosted" -> {
                builder.disableFilesystemTools();
                builder.disableShellTool();
                builder.registerExternalSchemas(
                        io.agentscope.builder.web.managed.selfhosted.SelfHostedToolSchemas.all());
                applyLocal(builder, scope);
            }
            default -> applyLocal(builder, scope);
        }
    }

    /**
     * Mounts MemoryStore backends under {@code memory-stores/{name}/} via harness route API.
     * Works for both sandbox (RoutedSandboxFilesystem) and non-sandbox (CompositeFilesystem)
     * modes.
     */
    public void applyMemoryStoreRoutes(
            HarnessAgent.Builder builder, String ownerId, List<MemoryStoreFilesystem> stores) {
        if (stores == null || stores.isEmpty()) {
            return;
        }
        for (MemoryStoreFilesystem storeFs : stores) {
            String prefix = MemoryStoreFilesystem.routePrefix(storeFs.storeName());
            builder.filesystemRoute(prefix, storeFs);
        }
        log.debug("Mounted {} memory-store route(s) for owner {}", stores.size(), ownerId);
    }

    /**
     * Resolves the E2B API key for a sandbox environment config. Priority: {@code config.apiKey}
     * → {@code builder.e2b.api-key} / {@code BUILDER_E2B_API_KEY} → {@code E2B_API_KEY}.
     */
    public Optional<String> resolveE2bApiKey(Map<String, Object> config) {
        String fromConfig = stringConfig(config, "apiKey");
        if (fromConfig != null && !fromConfig.isBlank()) {
            return Optional.of(fromConfig.trim());
        }
        if (e2bProperties.getApiKey() != null && !e2bProperties.getApiKey().isBlank()) {
            return Optional.of(e2bProperties.getApiKey().trim());
        }
        String envBuilder = System.getenv("BUILDER_E2B_API_KEY");
        if (envBuilder != null && !envBuilder.isBlank()) {
            return Optional.of(envBuilder.trim());
        }
        String envE2b = System.getenv("E2B_API_KEY");
        if (envE2b != null && !envE2b.isBlank()) {
            return Optional.of(envE2b.trim());
        }
        return Optional.empty();
    }

    /** Whether an E2B API key is available for the given (optional) environment config. */
    public boolean hasE2bApiKey(Map<String, Object> config) {
        return resolveE2bApiKey(config).isPresent();
    }

    /**
     * Builds the E2B filesystem spec for {@code type=sandbox}. Package-visible for unit tests.
     */
    E2bFilesystemSpec buildE2bFilesystemSpec(IsolationScope scope, Map<String, Object> config) {
        warnLegacyDockerKeys(config);
        String apiKey =
                resolveE2bApiKey(config)
                        .orElseThrow(
                                () ->
                                        ApiException.invalidRequest(
                                                "missing_e2b_api_key",
                                                "sandbox environments require an E2B API key"
                                                        + " (config.apiKey, builder.e2b.api-key,"
                                                        + " BUILDER_E2B_API_KEY, or E2B_API_KEY)",
                                                "config.apiKey"));

        E2bFilesystemSpec spec = new E2bFilesystemSpec().apiKey(apiKey);

        String templateId =
                firstNonBlank(
                        stringConfig(config, "templateId"), e2bProperties.getTemplateId(), "base");
        spec.templateId(templateId);

        String workspaceRoot =
                firstNonBlank(
                        stringConfig(config, "workspaceRoot"),
                        e2bProperties.getWorkspaceRoot(),
                        "/home/user");
        spec.workspaceRoot(workspaceRoot);

        int timeout =
                intConfig(
                        config,
                        "sandboxTimeoutSeconds",
                        e2bProperties.getSandboxTimeoutSeconds() > 0
                                ? e2bProperties.getSandboxTimeoutSeconds()
                                : 300);
        spec.sandboxTimeoutSeconds(timeout);

        String apiBaseUrl =
                firstNonBlank(stringConfig(config, "apiBaseUrl"), e2bProperties.getApiBaseUrl());
        if (apiBaseUrl != null) {
            spec.apiBaseUrl(apiBaseUrl);
        }
        String domain = firstNonBlank(stringConfig(config, "domain"), e2bProperties.getDomain());
        if (domain != null) {
            spec.domain(domain);
        }

        String persistence =
                firstNonBlank(
                        stringConfig(config, "persistenceMode"),
                        e2bProperties.getPersistenceMode(),
                        "TAR");
        try {
            spec.persistenceMode(E2bPersistenceMode.valueOf(persistence.trim().toUpperCase()));
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown E2B persistenceMode '{}'; using TAR", persistence);
            spec.persistenceMode(E2bPersistenceMode.TAR);
        }

        if (config != null
                && (config.containsKey("packages") || config.containsKey("networking"))) {
            log.warn(
                    "sandbox config packages/networking are not enforced on E2B; bake runtimes into"
                            + " a custom templateId instead (see docs/SANDBOX_GAPS.md)");
        }

        spec.isolationScope(scope != null ? scope : IsolationScope.SESSION);
        return spec;
    }

    private void applyLocal(HarnessAgent.Builder builder, IsolationScope scope) {
        builder.filesystem(new LocalFilesystemSpec().isolationScope(scope));
    }

    private void applyRemote(HarnessAgent.Builder builder, IsolationScope scope) {
        io.agentscope.core.state.AgentStateStore store = stateStore.orElse(null);
        if (store == null
                || store instanceof io.agentscope.core.state.InMemoryAgentStateStore
                || store instanceof io.agentscope.core.state.JsonFileAgentStateStore
                || baseStore.isEmpty()) {
            log.warn(
                    "remote environment requested but distributed BaseStore/AgentStateStore is"
                            + " unavailable; falling back to local filesystem");
            applyLocal(builder, scope);
            return;
        }
        builder.filesystem(
                new RemoteFilesystemSpec(baseStore.get())
                        .isolationScope(scope)
                        .addSharedPrefix("activity/"));
        builder.stateStore(store);
    }

    /**
     * Self-hosted hands: disable Brain-local filesystem/shell tools and register schema-only
     * external tools that suspend for an out-of-process Environment Worker (Claude-style).
     */
    private void applySelfHosted(
            HarnessAgent.Builder builder, IsolationScope scope, Map<String, Object> config) {
        builder.disableFilesystemTools();
        builder.disableShellTool();
        builder.registerExternalSchemas(
                io.agentscope.builder.web.managed.selfhosted.SelfHostedToolSchemas.all());
        applyLocal(builder, scope);
    }

    private void applySandbox(
            HarnessAgent.Builder builder, IsolationScope scope, Map<String, Object> config) {
        builder.filesystem(buildE2bFilesystemSpec(scope, config));
    }

    private static void warnLegacyDockerKeys(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            return;
        }
        for (String key : LEGACY_DOCKER_CONFIG_KEYS) {
            if (config.containsKey(key)) {
                log.warn(
                        "Ignoring legacy Docker sandbox config key '{}' — Managed sandbox uses"
                                + " E2B only (templateId / apiKey / workspaceRoot / …)",
                        key);
            }
        }
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

    private static String stringConfig(Map<String, Object> config, String key) {
        if (config == null) {
            return null;
        }
        Object value = config.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static int intConfig(Map<String, Object> config, String key, int defaultValue) {
        if (config == null) {
            return defaultValue;
        }
        Object value = config.get(key);
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
