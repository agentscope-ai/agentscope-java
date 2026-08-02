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
package io.agentscope.builder.web.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.builder.control.ControlPlaneClient;
import io.agentscope.builder.control.SessionResolveResult;
import io.agentscope.builder.runtime.config.SkillRepositorySupport;
import io.agentscope.builder.web.catalog.spec.AgentSpecCodec;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.AgentToolset;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.McpServerSpec;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.SkillRef;
import io.agentscope.builder.web.managed.AgentVersionSnapshot;
import io.agentscope.builder.web.managed.EnvironmentSpecFactory;
import io.agentscope.builder.web.managed.ManagedSessionDto;
import io.agentscope.builder.web.managed.MemoryMountService;
import io.agentscope.builder.web.managed.SessionAgentBuildSpec;
import io.agentscope.builder.web.managed.SessionResourceMountService;
import io.agentscope.builder.web.managed.VaultCredentialResolver;
import io.agentscope.builder.web.managed.service.AgentVersionService;
import io.agentscope.builder.web.persistence.jpa.AgentVersionEntity;
import io.agentscope.builder.web.toolbus.ToolConfirmationMiddleware;
import io.agentscope.builder.web.toolbus.ToolEventBus;
import io.agentscope.builder.web.toolbus.ToolNotificationMiddleware;
import io.agentscope.builder.web.workspace.SharedWorkspacePaths;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Data-plane agent factory: instantiates (and caches) {@link HarnessAgent} instances for managed
 * sessions.
 *
 * <p>Every agent is built from the control-plane resolve {@code agentSnapshot} when available,
 * otherwise from the {@link AgentVersionEntity} snapshot pinned on the session, falling back to the
 * stored user-agent entry when neither can be loaded.
 *
 * <h2>Namespace rules</h2>
 *
 * <ul>
 *   <li><b>Version owner</b>: the agent owner's namespace for user-custom agents, {@link
 *       AgentVersionService#GLOBAL_OWNER} for global agents.
 *   <li><b>Build owner</b> (definition-store / memory / vault / resource mounts): the agent owner
 *       for user-custom agents; the session owner for global agents, mirroring the control-plane
 *       per-user overlay write path.
 * </ul>
 *
 * <p>There is no gateway: built agents are cached locally keyed by {@code
 * sessionOwner/agentId/spec.cacheSuffix()}.
 */
@Service
public class HarnessAgentBuildService {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgentBuildService.class);

    /** Prefix for locally-built data-plane agent instance ids. */
    private static final String DP_AGENT_PREFIX = "dpa-";

    private static final ObjectMapper TOOLS_JSON_MAPPER = new ObjectMapper();

    private final UserAgentDefinitionStore store;
    private final Model model;
    private final ToolEventBus toolEventBus;
    private final SharedWorkspacePaths sharedWorkspacePaths;
    private final AgentVersionService versionService;
    private final EnvironmentSpecFactory environmentSpecFactory;
    private final ToolConfirmationMiddleware toolConfirmationMiddleware;
    private final MemoryMountService memoryMountService;
    private final VaultCredentialResolver vaultCredentialResolver;
    private final AgentStateStore agentStateStore;
    private final SessionResourceMountService sessionResourceMountService;
    private final DefinitionStore definitionStore;
    private final ControlPlaneClient controlPlaneClient;

    private final ConcurrentHashMap<String, HarnessAgent> agentCache = new ConcurrentHashMap<>();

    public HarnessAgentBuildService(
            UserAgentDefinitionStore store,
            Optional<Model> modelOpt,
            ToolEventBus toolEventBus,
            SharedWorkspacePaths sharedWorkspacePaths,
            AgentVersionService versionService,
            EnvironmentSpecFactory environmentSpecFactory,
            @Lazy ToolConfirmationMiddleware toolConfirmationMiddleware,
            MemoryMountService memoryMountService,
            VaultCredentialResolver vaultCredentialResolver,
            AgentStateStore agentStateStore,
            SessionResourceMountService sessionResourceMountService,
            DefinitionStore definitionStore,
            ControlPlaneClient controlPlaneClient) {
        this.store = store;
        this.model = modelOpt.orElse(null);
        this.toolEventBus = toolEventBus;
        this.sharedWorkspacePaths = sharedWorkspacePaths;
        this.versionService = versionService;
        this.environmentSpecFactory = environmentSpecFactory;
        this.toolConfirmationMiddleware = toolConfirmationMiddleware;
        this.memoryMountService = memoryMountService;
        this.vaultCredentialResolver = vaultCredentialResolver;
        this.agentStateStore = agentStateStore;
        this.sessionResourceMountService = sessionResourceMountService;
        this.definitionStore = definitionStore;
        this.controlPlaneClient = controlPlaneClient;
    }

    /** Resolves (and caches) the {@link HarnessAgent} for a managed-session turn. */
    public HarnessAgent getOrBuildAgent(ManagedSessionDto session, SessionAgentBuildSpec spec) {
        String cacheKey = session.ownerId() + "/" + session.agentId() + "/" + spec.cacheSuffix();
        return agentCache.computeIfAbsent(cacheKey, k -> build(session, spec));
    }

    /** Evicts all cached instance variants for a session-owner/agent pair. */
    public void evict(String sessionOwnerId, String agentId) {
        String prefix = sessionOwnerId + "/" + agentId;
        agentCache.keySet().removeIf(k -> k.equals(prefix) || k.startsWith(prefix + "/"));
    }

    /** Returns the workspace path used for a user-custom or global agent. */
    public Path resolveAgentWorkspace(String agentOwnerId, String agentId) {
        if (agentOwnerId == null) {
            return sharedWorkspacePaths.resolveAgentDataPath(null, agentId);
        }
        UserAgentDefinitionStore.StoredEntry entry = requireEntry(agentOwnerId, agentId);
        return sharedWorkspacePaths.resolveAgentDataPath(entry.workspacePath(), entry.id());
    }

    private HarnessAgent build(ManagedSessionDto session, SessionAgentBuildSpec spec) {
        String agentId = session.agentId();
        String agentOwnerId = session.agentOwnerId();
        boolean global = agentOwnerId == null;
        String versionOwner = global ? AgentVersionService.GLOBAL_OWNER : agentOwnerId;
        // Definition-store / memory / vault / resource mounts namespace: agent owner for
        // user-custom agents; session owner for global agents (per-user overlay, mirroring the
        // control-plane write path).
        String buildOwnerId = global ? session.ownerId() : agentOwnerId;

        UserAgentDefinitionStore.StoredEntry entry =
                global ? null : requireEntry(agentOwnerId, agentId);
        Path workspace =
                global
                        ? sharedWorkspacePaths.resolveAgentDataPath(null, agentId)
                        : sharedWorkspacePaths.resolveAgentDataPath(
                                entry.workspacePath(), entry.id());

        AgentVersionSnapshot snapshot = loadSnapshotFromControlPlane(session);
        if (snapshot == null && spec.version() != null) {
            try {
                AgentVersionEntity versionEntity =
                        versionService.getVersion(versionOwner, agentId, spec.version());
                snapshot = versionService.fromJson(versionEntity.getSnapshotJson());
            } catch (Exception ex) {
                log.warn(
                        "Failed to load agent version {}/{}@{}: {}",
                        versionOwner,
                        agentId,
                        spec.version(),
                        ex.getMessage());
            }
        }

        String name =
                snapshot != null && snapshot.name() != null
                        ? snapshot.name()
                        : (entry != null && entry.name() != null ? entry.name() : agentId);
        String description =
                snapshot != null
                        ? snapshot.description()
                        : entry != null ? entry.description() : null;
        String sysPrompt =
                snapshot != null ? snapshot.system() : entry != null ? entry.system() : null;
        String modelName =
                snapshot != null ? snapshot.model() : entry != null ? entry.model() : null;
        Integer maxIters =
                snapshot != null ? snapshot.maxIters() : entry != null ? entry.maxIters() : null;
        var skillRepos =
                snapshot != null
                        ? snapshot.skillRepositories()
                        : entry != null ? entry.skillRepositories() : null;

        if (spec.overridesJson() != null && !spec.overridesJson().isBlank()) {
            Map<String, Object> overrides = parseOverrides(spec.overridesJson());
            if (overrides.get("name") instanceof String s) {
                name = s;
            }
            if (overrides.get("description") instanceof String s) {
                description = s;
            }
            if (overrides.get("system") instanceof String s) {
                sysPrompt = s;
            } else if (overrides.get("sysPrompt") instanceof String s) {
                // legacy override key — prefer "system"
                sysPrompt = s;
            }
            if (overrides.get("model") instanceof String s) {
                modelName = s;
            }
            if (overrides.get("maxIters") instanceof Number n) {
                maxIters = n.intValue();
            }
        }

        String instanceId =
                DP_AGENT_PREFIX
                        + session.ownerId()
                        + "-"
                        + agentId
                        + "-"
                        + Integer.toHexString(spec.cacheSuffix().hashCode());

        HarnessAgent.Builder b = HarnessAgent.builder();
        // Pin the stable namespace key to the instance id (unique across users). The display
        // name (b.name) is human-facing and may change without rewriting any composite-filesystem
        // keys.
        b.agentId(instanceId);
        b.name(name);
        if (description != null) {
            b.description(description);
        }
        if (sysPrompt != null) {
            b.sysPrompt(sysPrompt);
        }
        if (maxIters != null) {
            b.maxIters(maxIters);
        }
        // Model: prefer per-agent override, fall back to the data-plane default model.
        if (modelName != null && !modelName.isBlank()) {
            b.model(modelName);
        } else if (model != null) {
            b.model(model);
        }
        b.workspace(workspace);
        b.stateStore(agentStateStore);

        // Tools / MCP: version snapshot (or head entry) is authoritative — inject ToolsConfig
        // so HarnessAgent.build does not depend on a node-local tools.json. Global agents may
        // still declare a workspace tools.json when the snapshot carries no toolset.
        List<AgentToolset> tools =
                snapshot != null ? snapshot.tools() : entry != null ? entry.tools() : null;
        List<McpServerSpec> mcpServers =
                snapshot != null
                        ? snapshot.mcpServers()
                        : entry != null ? entry.mcpServers() : null;
        List<SkillRef> skillRefs =
                snapshot != null ? snapshot.skills() : entry != null ? entry.skills() : null;
        ToolsConfig toolsConfig = AgentSpecCodec.toToolsConfig(tools, mcpServers);
        if (toolsConfig == null && global) {
            toolsConfig = readOptionalToolsJson(workspace);
        }
        ToolsConfig resolved =
                vaultCredentialResolver.resolveToolsConfig(
                        buildOwnerId, toolsConfig, spec.vaultIds());
        if (resolved != null) {
            b.toolsConfig(resolved);
        }

        // Sync control-plane workspace_files into DefinitionStore so skills/subagents are
        // replica-safe (not bound to aistiod local disk).
        syncDefinitionFilesFromControlPlane(session, buildOwnerId, agentId);

        // Skills: control-plane DefinitionStore + optional git/fs skillRepositories.
        // Do not rely on Hands primary filesystem Layer-4 workspace skills (sandbox would
        // look inside the sandbox, not the definition store).
        List<AgentSkillRepository> skillReposList = new ArrayList<>();
        skillReposList.add(
                new DefinitionStoreSkillRepository(definitionStore, buildOwnerId, agentId));
        if (skillRepos != null && !skillRepos.isEmpty()) {
            skillReposList.addAll(SkillRepositorySupport.createAll(workspace, skillRepos));
        }
        b.skillRepositories(skillReposList);
        b.disableDefaultWorkspaceSkills();
        List<String> workspaceSkillNames = AgentSpecCodec.workspaceSkillNames(skillRefs);
        if (!workspaceSkillNames.isEmpty()) {
            b.enableSkills(workspaceSkillNames.toArray(String[]::new));
        }

        // Tool event + confirmation middlewares (data-plane local bus; no gateway fan-out).
        b.middleware(new ToolNotificationMiddleware(toolEventBus));
        b.middleware(toolConfirmationMiddleware);

        applyManagedSessionBuildOptions(b, buildOwnerId, agentId, workspace, spec, sysPrompt);

        HarnessAgent agent = b.build();
        log.info(
                "Built data-plane agent: sessionOwner={}, agentId={}, instanceId={}, version={}",
                session.ownerId(),
                agentId,
                instanceId,
                spec.version());
        return agent;
    }

    private void applyManagedSessionBuildOptions(
            HarnessAgent.Builder b,
            String buildOwnerId,
            String agentId,
            Path workspace,
            SessionAgentBuildSpec spec,
            String baseSysPrompt) {
        var environment = spec.environment();
        if (environment != null) {
            environmentSpecFactory.applyEnvironment(b, environment);
        } else {
            environmentSpecFactory.applyAgentSandboxFields(b, null, null);
        }

        var filesystems =
                memoryMountService.createFilesystems(
                        buildOwnerId, spec.memoryStoreIds(), memoryAccessOverrides(environment));
        environmentSpecFactory.applyMemoryStoreRoutes(b, buildOwnerId, filesystems);
        var mounts = memoryMountService.resolveMounts(buildOwnerId, spec.memoryStoreIds());
        String appendix = memoryMountService.promptAppendix(mounts);
        if (appendix != null) {
            String combined = (baseSysPrompt == null ? "" : baseSysPrompt) + appendix;
            b.sysPrompt(combined);
        }

        // Stage session resources into the Hands workspace Path used by the Environment
        // filesystem (local disk / sandbox workspace root). Not a RemoteFilesystem primary.
        sessionResourceMountService.restageFromDefinitionStore(
                definitionStore, buildOwnerId, agentId, workspace);
        sessionResourceMountService.apply(workspace, spec.resources());
        sessionResourceMountService.mirrorFileResourcesToDefinitionStore(
                definitionStore, buildOwnerId, agentId, spec.resources());
    }

    /**
     * Extracts a {@code storeId -> "read_only"|"read_write"} map from {@code
     * environment.config().memoryAccess}, if present, so a session's environment can pin some
     * mounted memory stores read-only (e.g. shared reference knowledge bases).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> memoryAccessOverrides(
            io.agentscope.builder.web.managed.EnvironmentDto environment) {
        if (environment == null || environment.config() == null) {
            return Map.of();
        }
        Object raw = environment.config().get("memoryAccess");
        if (!(raw instanceof Map<?, ?> rawMap)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
        }
        return result;
    }

    /**
     * Prefers the agent snapshot returned by control-plane session resolve. Falls back to null so
     * the caller can load a pinned version from JPA or the user-agent definition store.
     */
    private AgentVersionSnapshot loadSnapshotFromControlPlane(ManagedSessionDto session) {
        if (session == null || session.id() == null) {
            return null;
        }
        try {
            SessionResolveResult resolved = controlPlaneClient.resolveSession(session.id());
            Map<String, Object> agentSnapshot = resolved.agentSnapshot();
            if (agentSnapshot == null || agentSnapshot.isEmpty()) {
                return null;
            }
            return versionService.fromJson(TOOLS_JSON_MAPPER.writeValueAsString(agentSnapshot));
        } catch (Exception ex) {
            log.debug(
                    "Control-plane agentSnapshot unavailable for session {}: {}",
                    session.id(),
                    ex.getMessage());
            return null;
        }
    }

    private void syncDefinitionFilesFromControlPlane(
            ManagedSessionDto session, String buildOwnerId, String agentId) {
        if (session == null || session.id() == null || definitionStore == null) {
            return;
        }
        try {
            SessionResolveResult resolved = controlPlaneClient.resolveSession(session.id());
            Map<String, String> files = resolved.definitionFiles();
            if (files == null || files.isEmpty()) {
                return;
            }
            for (Map.Entry<String, String> e : files.entrySet()) {
                if (e.getKey() == null || e.getKey().isBlank()) {
                    continue;
                }
                definitionStore.putText(buildOwnerId, agentId, e.getKey(), e.getValue());
            }
            log.debug(
                    "Synced {} definition files from control plane for {}/{}",
                    files.size(),
                    buildOwnerId,
                    agentId);
        } catch (Exception ex) {
            log.warn(
                    "Failed to sync definitionFiles for session {}: {}",
                    session.id(),
                    ex.getMessage());
        }
    }

    private UserAgentDefinitionStore.StoredEntry requireEntry(String ownerId, String agentId) {
        return store.findById(ownerId, agentId)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Agent not found: " + agentId));
    }

    /** Optional read of a local tools.json cache (global agents / operator inspection). */
    private static ToolsConfig readOptionalToolsJson(Path workspace) {
        if (workspace == null) {
            return null;
        }
        Path file = workspace.resolve("tools.json");
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return TOOLS_JSON_MAPPER.readValue(Files.readString(file), ToolsConfig.class);
        } catch (Exception ex) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseOverrides(String overridesJson) {
        try {
            return new ObjectMapper().readValue(overridesJson, Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
