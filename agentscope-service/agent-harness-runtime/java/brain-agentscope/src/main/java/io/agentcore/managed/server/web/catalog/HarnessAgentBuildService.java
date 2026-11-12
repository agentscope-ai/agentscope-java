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
package io.agentcore.managed.server.web.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentcore.managed.server.control.ControlPlaneClient;
import io.agentcore.managed.server.control.SessionResolveResult;
import io.agentcore.managed.server.runtime.ManagedCatalog;
import io.agentcore.managed.server.runtime.config.SkillRepositorySupport;
import io.agentcore.managed.server.runtime.managed.ManagedArtifactTool;
import io.agentcore.managed.server.runtime.managed.ManagedAsyncToolRegistry;
import io.agentcore.managed.server.runtime.managed.ManagedMcpBatchToolkit;
import io.agentcore.managed.server.runtime.managed.ManagedMcpCatalog;
import io.agentcore.managed.server.runtime.managed.ManagedMcpTool;
import io.agentcore.managed.server.runtime.managed.ManagedPrivateFilesystem;
import io.agentcore.managed.server.runtime.managed.ManagedRedisMessageBus;
import io.agentcore.managed.server.runtime.managed.ManagedRemoteFilesystem;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeInputMiddleware;
import io.agentcore.managed.server.runtime.managed.ManagedRuntimeLog;
import io.agentcore.managed.server.runtime.managed.ManagedScope;
import io.agentcore.managed.server.runtime.managed.ManagedSessionMessageBus;
import io.agentcore.managed.server.runtime.managed.ManagedSubagentDefinition;
import io.agentcore.managed.server.runtime.managed.ManagedTaskRepository;
import io.agentcore.managed.server.runtime.managed.ManagedToolRequests;
import io.agentcore.managed.server.runtime.managed.ManagedTurnExecution;
import io.agentcore.managed.server.runtime.managed.RedisManagedInfrastructure;
import io.agentcore.managed.server.web.catalog.spec.AgentSpecCodec;
import io.agentcore.managed.server.web.catalog.spec.AgentSpecTypes.AgentToolset;
import io.agentcore.managed.server.web.catalog.spec.AgentSpecTypes.McpServerSpec;
import io.agentcore.managed.server.web.catalog.spec.AgentSpecTypes.SkillRef;
import io.agentcore.managed.server.web.catalog.spec.EffectiveAgentDefinition;
import io.agentcore.managed.server.web.managed.AgentVersionSnapshot;
import io.agentcore.managed.server.web.managed.ManagedSessionDto;
import io.agentcore.managed.server.web.managed.MemoryMountService;
import io.agentcore.managed.server.web.managed.MemoryStoreFilesystem;
import io.agentcore.managed.server.web.managed.SessionAgentBuildSpec;
import io.agentcore.managed.server.web.managed.SessionResourceMountService;
import io.agentcore.managed.server.web.managed.VaultCredentialResolver;
import io.agentcore.managed.server.web.toolbus.ToolConfirmationMiddleware;
import io.agentcore.managed.server.web.toolbus.ToolEventBus;
import io.agentcore.managed.server.web.toolbus.ToolNotificationMiddleware;
import io.agentcore.managed.server.web.workspace.SharedWorkspacePaths;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.context.ContextPolicy;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.RoutedSandboxFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.sandbox.AbstractSandboxFilesystem;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Data-plane agent factory: instantiates (and caches) {@link HarnessAgent} instances for managed
 * sessions.
 *
 * <p>Managed session turns build exclusively from the control-plane ({@code control-plane}) session
 * resolve payload — {@code agentSnapshot}, {@code workspacePath}, and {@code definitionFiles}.
 * There is no JPA catalog fallback; resolve must succeed with a non-empty snapshot.
 *
 * <h2>Namespace rules</h2>
 *
 * <ul>
 *   <li><b>Build owner</b> (definition-store / memory / vault / resource mounts): the agent owner
 *       for user-custom agents; the session owner for global agents, mirroring the control-plane
 *       per-user overlay write path.
 * </ul>
 *
 * <p>Built agents, mutable workspaces, and staged definitions are isolated per session. Task
 * attempts additionally fence cached credentials by attempt and generation.
 */
@Service
public class HarnessAgentBuildService {
    // Web tools keep the client for the lifetime of each cached agent. Share the thread-safe,
    // credential-free transport while preserving the SDK's default timeout and redirect policy.
    private static final HttpClient WEB_HTTP_CLIENT =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    private static final Logger log = LoggerFactory.getLogger(HarnessAgentBuildService.class);

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /** Prefix for locally-built data-plane agent instance ids. */
    private static final String DP_AGENT_PREFIX = "dpa-";

    public static String sessionLogAgentId(ManagedSessionDto session) {
        try {
            byte[] identity =
                    JSON_MAPPER.writeValueAsBytes(
                            List.of(session.ownerId(), session.agentId(), session.id()));
            return DP_AGENT_PREFIX
                    + HexFormat.of()
                            .formatHex(MessageDigest.getInstance("SHA-256").digest(identity));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Cannot derive session agent identity", e);
        }
    }

    private static final String COLLABORATION_MCP_NAME = "controlplane-collaboration";

    private io.agentscope.core.session.SessionLogStore sessionLogStore;

    @Autowired(required = false)
    public void setSessionLogStore(io.agentscope.core.session.SessionLogStore store) {
        this.sessionLogStore = store;
    }

    private final Model model;
    private final ToolEventBus toolEventBus;
    private final SharedWorkspacePaths sharedWorkspacePaths;
    private final ToolConfirmationMiddleware toolConfirmationMiddleware;
    private final MemoryMountService memoryMountService;
    private final VaultCredentialResolver vaultCredentialResolver;
    private final AgentStateStore agentStateStore;
    private final SessionResourceMountService sessionResourceMountService;
    private final DefinitionStore definitionStore;
    private final ControlPlaneClient controlPlaneClient;

    @Autowired(required = false)
    private ManagedCatalog managedCatalog;

    @Autowired(required = false)
    private ManagedToolRequests managedToolRequests;

    @Autowired(required = false)
    private ManagedMcpCatalog managedMcpCatalog;

    @Autowired(required = false)
    private RedisManagedInfrastructure managedRedis;

    @Autowired private BaseStore runtimeWorkspaceStore;

    @Value("${managed-agents.user-files-enabled:${MANAGED_AGENTS_USER_FILES_ENABLED:true}}")
    private boolean userFilesEnabled = true;

    @Value("${managed-agents.runtime.model-timeout-seconds:0}")
    private long modelTimeoutSeconds;

    @Autowired(required = false)
    private LoadTestModels loadTestModels;

    @Autowired(required = false)
    private ManagedAgentModels managedAgentModels;

    private final ConcurrentHashMap<String, HarnessAgent> agentCache = new ConcurrentHashMap<>();

    /** Managed native sessions restore from SessionLog; no legacy state or Vault DAO is wired. */
    @Autowired
    public HarnessAgentBuildService(
            Optional<Model> modelOpt,
            ToolEventBus toolEventBus,
            SharedWorkspacePaths paths,
            ToolConfirmationMiddleware confirmation,
            MemoryMountService memories,
            SessionResourceMountService resources,
            DefinitionStore definitions,
            ControlPlaneClient control) {
        this(
                modelOpt,
                toolEventBus,
                paths,
                confirmation,
                memories,
                null,
                null,
                resources,
                definitions,
                control);
    }

    public HarnessAgentBuildService(
            Optional<Model> modelOpt,
            ToolEventBus toolEventBus,
            SharedWorkspacePaths sharedWorkspacePaths,
            @Lazy ToolConfirmationMiddleware toolConfirmationMiddleware,
            MemoryMountService memoryMountService,
            VaultCredentialResolver vaultCredentialResolver,
            AgentStateStore agentStateStore,
            SessionResourceMountService sessionResourceMountService,
            DefinitionStore definitionStore,
            ControlPlaneClient controlPlaneClient) {
        this.model = modelOpt.orElse(null);
        this.toolEventBus = toolEventBus;
        this.sharedWorkspacePaths = sharedWorkspacePaths;
        this.toolConfirmationMiddleware = toolConfirmationMiddleware;
        this.memoryMountService = memoryMountService;
        this.vaultCredentialResolver = vaultCredentialResolver;
        this.agentStateStore = agentStateStore;
        this.sessionResourceMountService = sessionResourceMountService;
        this.definitionStore = definitionStore;
        this.controlPlaneClient = controlPlaneClient;
    }

    private final java.util.Set<String> degradedInstances =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private io.agentcore.managed.server.web.managed.service.SessionEventLog managedEventLog;

    @Autowired(required = false)
    public void setManagedEventLog(
            io.agentcore.managed.server.web.managed.service.SessionEventLog eventLog) {
        this.managedEventLog = eventLog;
    }

    private JevServiceSupport jevServiceSupport;

    @Autowired
    public void setJevServiceSupport(JevServiceSupport support) {
        this.jevServiceSupport = support;
    }

    /** Builds a segment-owned agent; the caller must close it after execution or suspension. */
    public HarnessAgent buildExecutionAgent(ManagedSessionDto session, SessionAgentBuildSpec spec) {
        return build(session, spec, resolveSession(session), null);
    }

    public HarnessAgent buildExecutionAgent(
            ManagedSessionDto session, SessionAgentBuildSpec spec, ManagedTurnExecution execution) {
        var resolved = resolveSession(session);
        if (managedAgentModels == null)
            throw new IllegalStateException("Managed model configuration client is unavailable");
        var selection = managedAgentModels.resolve(execution, resolved, spec);
        return build(session, spec, resolved, null, selection);
    }

    /** Resolves a cached instance for callers that explicitly retain its lifecycle. */
    public HarnessAgent getOrBuildAgent(ManagedSessionDto session, SessionAgentBuildSpec spec) {
        SessionResolveResult resolved = resolveSession(session);
        String prefix =
                session.ownerId() + "/" + session.agentId() + "/session/" + session.id() + "/";
        String materialization = materializationFingerprint(resolved, spec);
        if (managedMcpCatalog != null
                && managedCatalog != null
                && managedCatalog.owns(session.id())) {
            var description =
                    managedMcpCatalog.require(
                            managedCatalog.scope(session.id()),
                            managedToolConnections(resolved),
                            resolved);
            materialization =
                    ManagedScope.fingerprint(
                            materialization, JSON_MAPPER.valueToTree(description).toString());
        }
        String cacheKey = prefix + materialization;
        closeCachedAgents(k -> k.startsWith(prefix) && !k.equals(cacheKey));
        if (degradedInstances.remove(cacheKey)) closeCachedAgents(cacheKey::equals);
        var agent = agentCache.computeIfAbsent(cacheKey, k -> build(session, spec, resolved, k));
        ManagedRuntimeLog.info(
                log,
                "harness_runtime.agent.cache.resolved",
                null,
                null,
                "session_id",
                session.id(),
                "agent_id",
                session.agentId(),
                "logical_agent_id",
                agent.getAgentId(),
                "cache_entry",
                ManagedScope.fingerprint(cacheKey));
        return agent;
    }

    /** Includes resource revisions so credential rotation and environment edits affect the next turn. */
    static String materializationFingerprint(
            SessionResolveResult resolved, SessionAgentBuildSpec spec) {
        try {
            Map<String, Object> material = new java.util.TreeMap<>();
            material.put("spec", spec);
            material.put("snapshot", resolved.agentSnapshot());
            material.put("environment", resolved.environment());
            material.put("memoryMounts", resolved.memoryMounts());
            material.put("files", resolved.definitionFiles());
            material.put("execution", resolved.executionContext());
            material.put(
                    "credentials",
                    resolved.vaultCredentials() == null
                            ? List.of()
                            : resolved.vaultCredentials().stream()
                                    .map(
                                            cred -> {
                                                Map<String, Object> revision =
                                                        new java.util.TreeMap<>(cred);
                                                revision.remove("secret");
                                                return revision;
                                            })
                                    .toList());
            byte[] bytes =
                    JSON_MAPPER
                            .copy()
                            .configure(
                                    com.fasterxml.jackson.databind.SerializationFeature
                                            .ORDER_MAP_ENTRIES_BY_KEYS,
                                    true)
                            .writeValueAsBytes(material);
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot fingerprint session resources", e);
        }
    }

    /** Returns the immutable-definition cache key for a managed agent instance. */
    static String cacheKey(ManagedSessionDto session, SessionAgentBuildSpec spec) {
        return session.ownerId()
                + "/"
                + session.agentId()
                + "/session/"
                + session.id()
                + "/"
                + spec.cacheSuffix();
    }

    /** Task attempts carry fenced credentials and therefore never share cached instances. */
    static String cacheKey(
            ManagedSessionDto session,
            SessionAgentBuildSpec spec,
            Map<String, Object> executionContext) {
        String base = cacheKey(session, spec);
        if (executionContext == null || executionContext.isEmpty()) {
            return base + "/managed/" + session.id() + "/unresolved";
        }
        return base
                + "/managed/"
                + session.id()
                + "/"
                + String.valueOf(executionContext.get("attemptId"))
                + ":"
                + String.valueOf(executionContext.get("dispatchGeneration"));
    }

    private static boolean isAgentTaskSession(ManagedSessionDto session) {
        return session != null
                && session.externalKey() != null
                && session.externalKey().startsWith("agent-task|");
    }

    /** Evicts all cached instance variants for a session-owner/agent pair. */
    public void evict(String sessionOwnerId, String agentId) {
        String prefix = sessionOwnerId + "/" + agentId;
        closeCachedAgents(k -> k.equals(prefix) || k.startsWith(prefix + "/"));
    }

    private void closeCachedAgents(java.util.function.Predicate<String> selector) {
        agentCache.forEach(
                (key, agent) -> {
                    if (selector.test(key) && agentCache.remove(key, agent)) {
                        degradedInstances.remove(key);
                        try {
                            agent.close();
                            ManagedRuntimeLog.info(
                                    log,
                                    "harness_runtime.agent.cache.closed",
                                    null,
                                    null,
                                    "logical_agent_id",
                                    agent.getAgentId(),
                                    "cache_entry",
                                    ManagedScope.fingerprint(key));
                        } catch (RuntimeException e) {
                            log.warn(
                                    "Managed agent cleanup failed ({})",
                                    e.getClass().getSimpleName());
                        }
                    }
                });
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        closeCachedAgents(key -> true);
    }

    /**
     * Drops persisted state and releases the deleted session's cached runtime resources.
     */
    public void discardSession(String ownerId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            if (agentStateStore != null) agentStateStore.delete(ownerId, sessionId);
            closeCachedAgents(
                    k ->
                            k.startsWith(ownerId + "/")
                                    && (k.contains("/managed/" + sessionId + "/")
                                            || k.contains("/session/" + sessionId + "/")));
        } catch (RuntimeException ex) {
            log.warn("Agent state cleanup failed for session {}: {}", sessionId, ex.getMessage());
        }
    }

    /**
     * Workspace path for definition management. Runtime turns use a separate session workspace.
     */
    public Path resolveAgentWorkspace(String agentOwnerId, String agentId) {
        return sharedWorkspacePaths.resolveAgentDataPath(null, agentId);
    }

    /** Session-private staging root shared by HarnessRuntime construction and Worker skill delivery. */
    public Path resolveSessionWorkspace(ManagedSessionDto session, SessionResolveResult resolved) {
        return sharedWorkspacePaths.resolveSessionDataPath(session.ownerId(), session.id());
    }

    private HarnessAgent build(
            ManagedSessionDto session,
            SessionAgentBuildSpec spec,
            SessionResolveResult preResolved,
            String cacheKey) {
        return build(session, spec, preResolved, cacheKey, null);
    }

    private HarnessAgent build(
            ManagedSessionDto session,
            SessionAgentBuildSpec spec,
            SessionResolveResult preResolved,
            String cacheKey,
            JsonNode modelSelection) {
        String agentId = session.agentId();
        String agentOwnerId = session.agentOwnerId();
        boolean global = agentOwnerId == null;
        // Definition-store / memory / vault / resource mounts namespace: agent owner for
        // user-custom agents; session owner for global agents (per-user overlay, mirroring the
        // control-plane write path).
        String buildOwnerId = global ? session.ownerId() : agentOwnerId;

        SessionResolveResult resolved = preResolved != null ? preResolved : resolveSession(session);
        AgentVersionSnapshot snapshot = snapshotFromResolve(resolved);
        if (snapshot == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Agent snapshot missing from control-plane resolve for session "
                            + session.id()
                            + " (agentId="
                            + agentId
                            + ")");
        }

        var effective =
                EffectiveAgentDefinition.resolve(resolved.agentSnapshot(), spec.overridesJson());
        snapshot = JSON_MAPPER.convertValue(effective, AgentVersionSnapshot.class);

        Path workspace = resolveSessionWorkspace(session, resolved);
        ManagedDefinitionMaterializer.materialize(workspace, resolved.definitionFiles());
        String definitionNamespace =
                agentId
                        + "--session-"
                        + java.util.UUID.nameUUIDFromBytes(
                                session.id().getBytes(java.nio.charset.StandardCharsets.UTF_8));

        String name = snapshot.name() != null ? snapshot.name() : agentId;
        String description = snapshot.description();
        String sysPrompt = snapshot.system();
        String modelName = snapshot.model();
        Integer maxIters = snapshot.maxIters();
        var skillRepos = snapshot.skillRepositories();
        var contextPolicy = ContextPolicy.defaults();

        if (effective.get("contextPolicy") instanceof Map<?, ?> policyValues)
            contextPolicy = ContextPolicy.fromMap(policyValues);
        sysPrompt = appendManagedExecutionPrompt(sysPrompt, resolved.executionContext());

        String instanceId = sessionLogAgentId(session);

        HarnessAgent.Builder b = newAgentBuilder();
        b.contextPolicy(contextPolicy);
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
        JsonNode modelBinding =
                modelSelection != null && "binding".equals(modelSelection.path("mode").asText())
                        ? modelSelection.required("model")
                        : null;
        if (modelSelection != null && "native".equals(modelSelection.path("mode").asText()))
            modelName = modelSelection.has("name") ? modelSelection.get("name").asText() : null;
        if (modelBinding != null) {
            b.model(managedAgentModels.create(modelBinding));
        } else if (modelName != null && modelName.startsWith("loadtest:")) {
            if (loadTestModels == null)
                throw new IllegalArgumentException("Load-test model configuration is unavailable");
            b.model(loadTestModels.resolve(modelName));
        } else if (modelName != null && !modelName.isBlank()) {
            b.model(modelName);
        } else if (model != null) {
            b.model(model);
        }
        b.workspace(workspace);
        if (sessionLogStore == null && agentStateStore != null) b.stateStore(agentStateStore);
        if (sessionLogStore != null) b.sessionLogStore(sessionLogStore);

        List<AgentToolset> tools = snapshot.tools();
        List<McpServerSpec> mcpServers = snapshot.mcpServers();
        List<SkillRef> skillRefs = snapshot.skills();
        ToolsConfig toolsConfig = AgentSpecCodec.toToolsConfig(tools, mcpServers);
        var runtimeEnvironment =
                resolved.environment() != null ? resolved.environment() : spec.environment();
        if (toolsConfig.getMcpServers() != null
                && (runtimeEnvironment == null || !"local".equals(runtimeEnvironment.type()))) {
            for (var connection : toolsConfig.getMcpServers().entrySet()) {
                if ("stdio".equalsIgnoreCase(connection.getValue().getTransport()))
                    throw new IllegalArgumentException(
                            "stdio MCP requires an explicit local environment; expose "
                                    + connection.getKey()
                                    + " over HTTP for hosted or self-hosted sandboxes");
            }
        }
        if (toolsConfig == null && global) {
            toolsConfig = readOptionalToolsJson(workspace);
        }
        ToolsConfig resolvedTools =
                VaultCredentialResolver.resolveSessionToolsConfig(
                        toolsConfig, resolved.vaultCredentials());
        resolvedTools =
                withManagedCollaborationTools(
                        resolvedTools,
                        resolved.executionContext(),
                        controlPlaneClient.collaborationMcpUrl());
        if (resolvedTools != null) {
            if (resolvedTools.getMcpServers() != null)
                resolvedTools
                        .getMcpServers()
                        .forEach(
                                (serverName, config) ->
                                        config.setConnectionFailureHandler(
                                                failure -> {
                                                    if (managedEventLog != null)
                                                        managedEventLog.append(
                                                                session.id(),
                                                                "session.error",
                                                                Map.of(
                                                                        "error",
                                                                        Map.of(
                                                                                "type",
                                                                                "mcp_connection_failed_error",
                                                                                "code",
                                                                                "mcp_connection_failed_error",
                                                                                "message",
                                                                                failure
                                                                                        .getMessage(),
                                                                                "mcp_server_name",
                                                                                serverName,
                                                                                "retry_status",
                                                                                "next_turn")));
                                                    // Rebuild on the next turn so an optional
                                                    // connection can recover.
                                                    if (cacheKey != null)
                                                        degradedInstances.add(cacheKey);
                                                }));
            if (managedCatalog != null && managedCatalog.owns(session.id())) {
                if (managedMcpCatalog == null)
                    throw new IllegalStateException(
                            "Managed tool discovery is unavailable while HarnessRuntime consumers"
                                    + " are disabled");
                var descriptors =
                        managedMcpCatalog.require(
                                managedCatalog.scope(session.id()), resolvedTools, resolved);
                b.toolsConfig(managedProxyConfiguration(resolvedTools, descriptors));
            } else b.toolsConfig(resolvedTools);
        }
        PermissionContextState managedTaskPermissions =
                managedTaskPermissionContext(resolved.executionContext());
        b.permissionContext(
                managedTaskPermissions != null
                        ? managedTaskPermissions
                        : PermissionContextState.builder().mode(PermissionMode.BYPASS).build());

        syncDefinitionFilesFromResolve(resolved, buildOwnerId, definitionNamespace);

        List<AgentSkillRepository> skillReposList = new ArrayList<>();
        skillReposList.add(
                new DefinitionStoreSkillRepository(
                        definitionStore, buildOwnerId, definitionNamespace));
        if (skillRepos != null && !skillRepos.isEmpty()) {
            skillReposList.addAll(SkillRepositorySupport.createAll(workspace, skillRepos));
        }
        b.skillRepositories(skillReposList);
        b.disableDefaultWorkspaceSkills();
        List<String> workspaceSkillNames = AgentSpecCodec.workspaceSkillNames(skillRefs);
        b.skillFilter(
                workspaceSkillNames.isEmpty()
                        ? io.agentscope.core.skill.SkillFilter.none()
                        : io.agentscope.core.skill.SkillFilter.only(
                                workspaceSkillNames.toArray(String[]::new)));

        b.middleware(new ToolNotificationMiddleware(toolEventBus));
        b.middleware(toolConfirmationMiddleware);
        b.middleware(new ManagedRuntimeInputMiddleware(JSON_MAPPER));
        if (jevServiceSupport != null) {
            jevServiceSupport.middlewares(spec.overridesJson()).forEach(b::middleware);
            jevServiceSupport
                    .compaction(
                            spec.overridesJson(),
                            new io.agentscope.extensions.judge.jev.context.FileJevContextArchive(
                                    sharedWorkspacePaths
                                            .resolveSessionDataPath(session.ownerId(), session.id())
                                            .resolve("jev-context"),
                                    20_000_000))
                    .ifPresent(b::compaction);
        }

        var memoryRoutes =
                applyManagedSessionBuildOptions(
                        b,
                        buildOwnerId,
                        definitionNamespace,
                        workspace,
                        spec,
                        sysPrompt,
                        session.id(),
                        resolved);
        boolean managedSession = managedCatalog != null && managedCatalog.owns(session.id());
        if (managedSession) {
            if (managedToolRequests == null)
                throw new IllegalStateException("Managed Tool Worker bridge is unavailable");
            if (userFilesEnabled) {
                b.abstractFilesystem(managedFilesystem(managedToolRequests, memoryRoutes));
            } else {
                configureFilelessManagedAgent(
                        b, runtimeWorkspaceStore, managedCatalog.scope(session.id()), memoryRoutes);
            }
            b.asyncToolRegistry(new ManagedAsyncToolRegistry());
            b.taskRepository(new ManagedTaskRepository());
            // Public multiagent capability is rejected at admission. Native SessionLog owns
            // recovery; do not install a distributed bus solely for unused SDK defaults.
            if (managedRedis != null)
                b.messageBus(
                        new ManagedRedisMessageBus(
                                managedCatalog.scope(session.id()), managedRedis, JSON_MAPPER));
            else b.messageBus(new ManagedSessionMessageBus());
        } else memoryRoutes.forEach(b::filesystemRoute);
        if (modelTimeoutSeconds < 0 || modelTimeoutSeconds > 3600)
            throw new IllegalArgumentException("Model timeout must be 0..3600 seconds");
        if (managedSession || modelTimeoutSeconds > 0 || modelBinding != null) {
            var options = GenerateOptions.builder();
            // Permit a complete tool wave while bounding only the model request, not tool waits.
            if (managedSession) options.parallelToolCalls(true);
            if (modelTimeoutSeconds > 0)
                options.executionConfig(
                        ExecutionConfig.builder()
                                .timeout(Duration.ofSeconds(modelTimeoutSeconds))
                                .build());
            b.generateOptions(
                    modelBinding == null
                            ? options.build()
                            : ManagedAgentModels.options(modelBinding, options.build()));
        }
        ManagedSubagentDefinition childDefinition =
                managedCatalog == null || !managedCatalog.owns(session.id())
                        ? null
                        : managedCatalog.childDefinition(managedCatalog.scope(session.id()));
        if (childDefinition != null) childDefinition.configure(b);
        if (managedSession
                && "mcp-only".equals(System.getenv("MANAGED_AGENTS_EVALUATION_TOOL_SURFACE"))) {
            var declared =
                    managedMcpCatalog.require(
                            managedCatalog.scope(session.id()), resolvedTools, resolved);
            configureMcpOnlyEvaluation(
                    b, declared.tools().stream().map(ManagedMcpCatalog.Tool::name).toList());
        }
        // Agent/extension configuration must not re-enable transformations after
        // the sealed evaluation policy has disabled them.
        configureEvaluationContext(b, System.getenv("MANAGED_AGENTS_EVALUATION_CONTEXT_MODE"));
        HarnessAgent agent = b.build();
        try {
            if (managedCatalog != null && managedCatalog.owns(session.id())) {
                ManagedTaskRepository.LOCAL_TOOLS.forEach(agent.getToolkit()::removeTool);
            }
            if (childDefinition != null) childDefinition.restrict(agent);
        } catch (RuntimeException failure) {
            agent.close();
            throw failure;
        }
        log.info(
                "Built data-plane agent from control-plane snapshot: sessionOwner={}, agentId={},"
                        + " instanceId={}, version={}",
                session.ownerId(),
                agentId,
                instanceId,
                spec.version());
        return agent;
    }

    @SuppressWarnings("unchecked")
    static ToolsConfig withManagedCollaborationTools(
            ToolsConfig source, Map<String, Object> executionContext, String collaborationMcpUrl) {
        if (executionContext == null
                || !(executionContext.get("taskContext") instanceof Map<?, ?> rawTaskContext)) {
            return source;
        }
        Map<String, Object> taskContext = (Map<String, Object>) rawTaskContext;
        Object rawToken = taskContext.get("taskToken");
        if (!(rawToken instanceof String taskToken) || taskToken.isBlank()) {
            return source;
        }
        List<String> actions = stringList(taskContext.get("availableActions"));
        ToolsConfig merged = copyToolsConfig(source);
        McpServerConfig collaboration = new McpServerConfig();
        collaboration.setTransport("http");
        collaboration.setUrl(collaborationMcpUrl);
        collaboration.setHeaders(Map.of("X-Agent-Task-Token", taskToken));
        collaboration.setEnableTools(actions);
        Map<String, McpServerConfig> servers =
                merged.getMcpServers() != null
                        ? new LinkedHashMap<>(merged.getMcpServers())
                        : new LinkedHashMap<>();
        servers.put(COLLABORATION_MCP_NAME, collaboration);
        merged.setMcpServers(servers);

        if (!merged.isDefaultToolsEnabled()
                || (merged.getAllow() != null && !merged.getAllow().isEmpty())) {
            LinkedHashSet<String> allow =
                    new LinkedHashSet<>(merged.getAllow() == null ? List.of() : merged.getAllow());
            allow.addAll(actions);
            merged.setAllow(new ArrayList<>(allow));
        }
        if (merged.getDeny() != null && !merged.getDeny().isEmpty()) {
            List<String> deny = new ArrayList<>(merged.getDeny());
            deny.removeAll(actions);
            merged.setDeny(deny);
        }
        return merged;
    }

    @SuppressWarnings("unchecked")
    static PermissionContextState managedTaskPermissionContext(
            Map<String, Object> executionContext) {
        if (executionContext == null
                || !(executionContext.get("taskContext") instanceof Map<?, ?> rawTaskContext)) {
            return null;
        }
        List<String> actions =
                stringList(((Map<String, Object>) rawTaskContext).get("availableActions"));
        if (actions.isEmpty()) {
            return null;
        }
        // AgentTask turns are unattended. Use the service's ToolConfirmationMiddleware as the
        // single HITL authority: it enforces the AgentSpec always_ask/deny policies and creates a
        // durable, user-visible confirmation ticket. Leaving this context in DEFAULT mode makes
        // the core PermissionEngine ASK for every tool not listed below (including read-only
        // built-ins such as web_search), but that Core prompt has no control-plane approval ticket
        // and therefore cannot be resumed. BYPASS still runs every tool's bypass-immune safety
        // check before falling back to allow.
        PermissionContextState.Builder permissions =
                PermissionContextState.builder().mode(PermissionMode.BYPASS);
        for (String action : actions) {
            permissions.addAllowRule(
                    action,
                    new PermissionRule(
                            action, null, PermissionBehavior.ALLOW, "managed-agent-task"));
        }
        return permissions.build();
    }

    static ToolsConfig managedProxyConfiguration(
            ToolsConfig source, ManagedMcpCatalog.Description description) {
        var proxy = copyToolsConfig(source);
        // MCP selection has already happened in Tool Worker. Preserve those tools when the built-in
        // allowlist is restrictive, without opening any connection from the Harness builder.
        if (!proxy.isStrictAllow()
                && (!proxy.isDefaultToolsEnabled()
                        || (proxy.getAllow() != null && !proxy.getAllow().isEmpty()))) {
            var allow =
                    new LinkedHashSet<String>(
                            proxy.getAllow() == null ? List.of() : proxy.getAllow());
            description.tools().forEach(tool -> allow.add(tool.name()));
            proxy.setAllow(new ArrayList<>(allow));
        }
        proxy.setMcpServers(Map.of());
        return proxy;
    }

    private static ToolsConfig copyToolsConfig(ToolsConfig source) {
        ToolsConfig copy = new ToolsConfig();
        if (source == null) {
            return copy;
        }
        copy.setDefaultToolsEnabled(source.isDefaultToolsEnabled());
        copy.setStrictAllow(source.isStrictAllow());
        copy.setAllow(source.getAllow() != null ? new ArrayList<>(source.getAllow()) : null);
        copy.setDeny(source.getDeny() != null ? new ArrayList<>(source.getDeny()) : null);
        copy.setMcpServers(source.getMcpServers());
        return copy;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    @SuppressWarnings("unchecked")
    static String appendManagedExecutionPrompt(
            String basePrompt, Map<String, Object> executionContext) {
        if (executionContext == null
                || !(executionContext.get("taskContext") instanceof Map<?, ?> rawTaskContext)) {
            return basePrompt;
        }
        Map<String, Object> taskContext = new LinkedHashMap<>((Map<String, Object>) rawTaskContext);
        taskContext.remove("taskToken");
        try {
            String contextJson = JSON_MAPPER.writeValueAsString(taskContext);
            return (basePrompt == null ? "" : basePrompt)
                    + "\n\n"
                    + "Managed AgentTask protocol:\n"
                    + "- The control plane has already admitted and started this execution"
                    + " attempt.\n"
                    + "- Read currentRequest first. For a comment-triggered task it is the current"
                    + " assignment and overrides older Issue requirements. requestContext contains"
                    + " the initiating hand-off instructions needed to interpret a reply; do not"
                    + " execute that history again or answer the obsolete original Issue.\n"
                    + "- If replyToOwnDelegation is true, this is the answer to work you delegated."
                    + " Follow initiatingRequest for the final delivery. Call task.complete to"
                    + " deliver it; do not mention the responder merely to acknowledge the answer."
                    + " A mention schedules new work and is appropriate only for a new actionable"
                    + " request, never as a courtesy notification.\n"
                    + "- Use math.evaluate to verify arithmetic. Verify other objective results"
                    + " before completing or accepting work. A worker success flag is not proof of"
                    + " accuracy.\n"
                    + "- Use the controlplane-collaboration tools for every durable read, progress"
                    + " update, delegation, response, and completion.\n"
                    + "- Never claim that an Issue, child task, or coordinator node changed unless"
                    + " the corresponding tool call succeeded.\n"
                    + "- A worker should call task.complete with the verified result (or"
                    + " task.fail). Completion publishes its result; do not send the same reply"
                    + " twice.\n"
                    + "- An initial Team leader turn delegates all suitable work with"
                    + " issue.child.create, then immediately calls task.complete and stops. It must"
                    + " not wait for workers inside that turn.\n"
                    + "- A later Team leader follow-up validates worker results, accepts completed"
                    + " child Issues, calls run.node.complete only after the whole coordinator has"
                    + " converged, and stops. run.node.complete also completes that leader Task; do"
                    + " not call task.complete afterwards. Use run.node.fail on failure.\n"
                    + "Authoritative task context (credentials omitted):\n"
                    + contextJson;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize managed AgentTask context", ex);
        }
    }

    private ToolsConfig managedToolConnections(SessionResolveResult resolved) {
        var snapshot =
                JSON_MAPPER.convertValue(resolved.agentSnapshot(), AgentVersionSnapshot.class);
        var source = AgentSpecCodec.toToolsConfig(snapshot.tools(), snapshot.mcpServers());
        return withManagedCollaborationTools(
                VaultCredentialResolver.resolveSessionToolsConfig(
                        source, resolved.vaultCredentials()),
                resolved.executionContext(),
                controlPlaneClient.collaborationMcpUrl());
    }

    /** Pins read-only MCP descriptors before acquiring an execution epoch. */
    public void prepareManagedTools(SessionResolveResult resolved, String turn) {
        if (managedMcpCatalog != null)
            managedMcpCatalog.require(
                    managedCatalog.scope(resolved.session().id()),
                    managedToolConnections(resolved),
                    resolved,
                    turn);
    }

    public Mono<Void> prepareManagedToolsAsync(
            ManagedScope scope, SessionResolveResult resolved, String turn) {
        if (managedMcpCatalog == null) return Mono.empty();
        return managedMcpCatalog
                .requireAsync(scope, managedToolConnections(resolved), resolved, turn)
                .then();
    }

    /** Reads the Turn's pinned descriptors only; cancellation must not rediscover MCP servers. */
    public Set<String> managedPinnedMcpTools(
            ManagedScope scope, SessionResolveResult resolved, String turn) {
        if (managedMcpCatalog == null) return Set.of();
        var tools = managedToolConnections(resolved);
        var description =
                managedMcpCatalog.lookup(
                        scope, turn, managedMcpCatalog.id(scope, tools), List.of());
        if (description == null) return Set.of();
        return description.tools().stream()
                .map(ManagedMcpCatalog.Tool::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    public String managedExecutionConfiguration(SessionResolveResult resolved) {
        if (managedMcpCatalog == null)
            throw new IllegalStateException("Managed MCP catalog is required");
        return managedMcpCatalog.seal(resolved, managedToolConnections(resolved));
    }

    /** Internal Harness bookkeeping remains shared without exposing user files or requiring Tool Worker. */
    static void configureFilelessManagedAgent(
            HarnessAgent.Builder builder,
            BaseStore store,
            ManagedScope scope,
            Map<String, AbstractFilesystem> routes) {
        builder.abstractFilesystem(
                new ManagedPrivateFilesystem(
                        store, List.of("managed-private-workspace", scope.messageGroup())));
        routes.forEach(builder::filesystemRoute);
        builder.disableFilesystemTools().disableShellTool().disableAtPathExpansion();
    }

    static void configureMcpOnlyEvaluation(HarnessAgent.Builder builder, List<String> tools) {
        // Use SDK opt-outs: platform tools bypass its ordinary built-in allowlist,
        // and skill middleware can register a loader after that filter has run.
        var config = new ToolsConfig();
        config.setDefaultToolsEnabled(false);
        config.setStrictAllow(true);
        config.setAllow(tools);
        config.setMcpServers(Map.of());
        builder.toolsConfig(config)
                .disableSubagents()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .skillRepositories(List.of())
                .skillsEnabled(false)
                .disableFilesystemTools()
                .disableShellTool()
                .disableWebTools()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableWorkspaceContext()
                .disableAtPathExpansion();
    }

    static HarnessAgent.Builder newAgentBuilder() {
        return newAgentBuilder(System.getenv("MANAGED_AGENTS_EVALUATION_CONTEXT_MODE"));
    }

    static HarnessAgent.Builder newAgentBuilder(String contextMode) {
        // Managed execution does not implicitly extract or consolidate long-term memories.
        // Keep context compaction, but do not add a memory-model call before summarization.
        var compaction = CompactionConfig.builder().flushBeforeCompact(false);
        if ("short".equals(contextMode)) {
            // The evaluation gateway enforces the common input envelope. Disable SDK
            // message-count summaries and history pruning inside that envelope.
            compaction
                    .triggerMessages(0)
                    .triggerTokens(Integer.MAX_VALUE)
                    .prune(null)
                    .truncateArgs(null);
        }
        var builder =
                HarnessAgent.builder()
                        .webHttpClient(WEB_HTTP_CLIENT)
                        .disableMemoryHooks()
                        .compaction(compaction.build());
        configureEvaluationContext(builder, contextMode);
        return builder;
    }

    static void configureEvaluationContext(HarnessAgent.Builder builder, String contextMode) {
        if ("short".equals(contextMode)) builder.disableCompaction().disableToolResultEviction();
    }

    static AbstractSandboxFilesystem managedFilesystem(
            ManagedToolRequests requests, Map<String, AbstractFilesystem> routes) {
        var primary = new ManagedRemoteFilesystem(requests);
        return routes.isEmpty() ? primary : new RoutedSandboxFilesystem(primary, routes);
    }

    private Map<String, AbstractFilesystem> applyManagedSessionBuildOptions(
            HarnessAgent.Builder b,
            String buildOwnerId,
            String agentId,
            Path workspace,
            SessionAgentBuildSpec spec,
            String baseSysPrompt,
            String sessionId,
            SessionResolveResult resolved) {
        var filesystems =
                memoryMountService.createResolvedFilesystems(
                        controlPlaneClient,
                        sessionId,
                        buildOwnerId,
                        resolved.memoryMounts(),
                        sharedKnowledgeAccess(
                                resolved.memoryMounts() == null
                                        ? null
                                        : resolved.memoryMounts().stream()
                                                .map(mount -> (String) mount.get("storeId"))
                                                .toList()));
        Map<String, AbstractFilesystem> routes = new LinkedHashMap<>();
        if (filesystems != null)
            for (var store : filesystems)
                routes.put(MemoryStoreFilesystem.routePrefix(store.storeName()), store);
        var reviewTool =
                jevServiceSupport == null
                        ? java.util.Optional
                                .<io.agentscope.extensions.judge.jev.review.JevCodeReviewTool>
                                        empty()
                        : jevServiceSupport.reviewTool(spec.overridesJson());
        var evidenceTool =
                jevServiceSupport == null
                        ? java.util.Optional
                                .<io.agentscope.extensions.judge.jev.evidence.JevEvidenceTool>
                                        empty()
                        : jevServiceSupport.evidenceTool(spec.overridesJson());
        var browserTool =
                jevServiceSupport == null
                        ? java.util.Optional
                                .<io.agentscope.extensions.judge.jev.browser.JevBrowserReadTool>
                                        empty()
                        : jevServiceSupport.browserTool(spec.overridesJson());
        boolean managed = managedCatalog != null && managedCatalog.owns(sessionId);
        if (managed
                || !filesystems.isEmpty()
                || reviewTool.isPresent()
                || evidenceTool.isPresent()
                || browserTool.isPresent()) {
            Toolkit applicationToolkit = managed ? new ManagedMcpBatchToolkit() : new Toolkit();
            if (managed && managedMcpCatalog != null) {
                var description =
                        managedMcpCatalog.require(
                                managedCatalog.scope(sessionId),
                                managedToolConnections(resolved),
                                resolved);
                for (var tool : description.tools())
                    applicationToolkit.registerTool(
                            new ManagedMcpTool(tool, managedToolRequests, JSON_MAPPER));
            }
            if (managed && userFilesEnabled)
                applicationToolkit.registerTool(new ManagedArtifactTool(managedToolRequests));
            if (!filesystems.isEmpty())
                applicationToolkit.registerTool(
                        new io.agentcore.managed.server.web.managed.ManagedMemoryTools(
                                filesystems));
            reviewTool.ifPresent(applicationToolkit::registerTool);
            evidenceTool.ifPresent(applicationToolkit::registerTool);
            browserTool.ifPresent(applicationToolkit::registerTool);
            b.toolkit(applicationToolkit);
        }
        var mounts =
                filesystems.stream()
                        .map(
                                fs ->
                                        new MemoryMountService.MountInfo(
                                                fs.storeId(),
                                                fs.storeName(),
                                                MemoryStoreFilesystem.routePrefix(fs.storeName())
                                                        .replaceAll("/+$", ""),
                                                fs.accessMode()))
                        .toList();
        String appendix = memoryMountService.promptAppendix(mounts);
        if (!filesystems.isEmpty())
            appendix +=
                    "\n"
                        + "Use memory_store_list and memory_store_read for these read-only shared"
                        + " stores in every environment. Do not write or edit shared knowledge."
                        + " Shell commands and Worker file tools do not access these live"
                        + " mounts.\n";
        if (appendix != null) {
            String combined = (baseSysPrompt == null ? "" : baseSysPrompt) + appendix;
            b.sysPrompt(combined);
        }

        // Stage session resources into the Tool Worker workspace Path used by the Environment
        // filesystem (local disk / sandbox workspace root). Not a RemoteFilesystem primary.
        sessionResourceMountService.restageFromDefinitionStore(
                definitionStore, buildOwnerId, agentId, workspace);
        sessionResourceMountService.apply(workspace, spec.resources());
        // Session inputs must never be published back into a reusable Agent definition.
        return Map.copyOf(routes);
    }

    /** Shared knowledge is read-only during execution; session files hold private working memory. */
    static Map<String, String> sharedKnowledgeAccess(List<String> memoryStoreIds) {
        Map<String, String> access = new LinkedHashMap<>();
        if (memoryStoreIds != null) {
            for (String id : memoryStoreIds) {
                access.put(id, "read_only");
            }
        }
        return access;
    }

    private SessionResolveResult resolveSession(ManagedSessionDto session) {
        if (session == null || session.id() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Managed session id is required to build an agent");
        }
        return controlPlaneClient.resolveSession(session.id());
    }

    private AgentVersionSnapshot snapshotFromResolve(SessionResolveResult resolved) {
        if (resolved.agentSnapshot() == null || resolved.agentSnapshot().isEmpty()) {
            return null;
        }
        try {
            return JSON_MAPPER.convertValue(resolved.agentSnapshot(), AgentVersionSnapshot.class);
        } catch (Exception ex) {
            log.warn("Failed to parse control-plane agentSnapshot: {}", ex.toString());
            return null;
        }
    }

    private void syncDefinitionFilesFromResolve(
            SessionResolveResult resolved, String buildOwnerId, String agentId) {
        if (resolved == null || definitionStore == null) {
            return;
        }
        Map<String, String> files = resolved.definitionFiles();
        if (files == null) return;
        for (String previous : definitionStore.list(buildOwnerId, agentId, "")) {
            if (!files.containsKey(previous) && !previous.startsWith("resources/"))
                definitionStore.delete(buildOwnerId, agentId, previous);
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
            return JSON_MAPPER.readValue(Files.readString(file), ToolsConfig.class);
        } catch (Exception ex) {
            return null;
        }
    }
}
