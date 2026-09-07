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
import io.agentscope.builder.web.toolbus.ToolConfirmationMiddleware;
import io.agentscope.builder.web.toolbus.ToolEventBus;
import io.agentscope.builder.web.toolbus.ToolNotificationMiddleware;
import io.agentscope.builder.web.workspace.SharedWorkspacePaths;
import io.agentscope.core.model.Model;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.McpServerConfig;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
 * <p>Managed session turns build exclusively from the control-plane ({@code aistiod}) session
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
 * <p>Built agents are cached locally keyed by {@code sessionOwner/agentId/spec.cacheSuffix()}, plus
 * the session id for team member sessions, whose team role is fixed at build time.
 */
@Service
public class HarnessAgentBuildService {

    private static final Logger log = LoggerFactory.getLogger(HarnessAgentBuildService.class);

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    /** Prefix for locally-built data-plane agent instance ids. */
    private static final String DP_AGENT_PREFIX = "dpa-";

    private static final String COLLABORATION_MCP_NAME = "aistio-collaboration";

    private final Model model;
    private final ToolEventBus toolEventBus;
    private final SharedWorkspacePaths sharedWorkspacePaths;
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
            Optional<Model> modelOpt,
            ToolEventBus toolEventBus,
            SharedWorkspacePaths sharedWorkspacePaths,
            EnvironmentSpecFactory environmentSpecFactory,
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
        if (!isAgentTaskSession(session)) {
            String cacheKey = cacheKey(session, spec);
            return agentCache.computeIfAbsent(cacheKey, k -> build(session, spec, null, k));
        }
        SessionResolveResult resolved = resolveSession(session);
        String cacheKey = cacheKey(session, spec, resolved.executionContext());
        String attemptPrefix = cacheKey(session, spec) + "/managed/" + session.id() + "/";
        agentCache.keySet().removeIf(k -> k.startsWith(attemptPrefix) && !k.equals(cacheKey));
        return agentCache.computeIfAbsent(cacheKey, k -> build(session, spec, resolved, k));
    }

    /** Returns the immutable-definition cache key for a managed agent instance. */
    static String cacheKey(ManagedSessionDto session, SessionAgentBuildSpec spec) {
        return session.ownerId() + "/" + session.agentId() + "/" + spec.cacheSuffix();
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
        agentCache.keySet().removeIf(k -> k.equals(prefix) || k.startsWith(prefix + "/"));
    }

    /**
     * Drops the persisted state for a deleted session. Agent instances are immutable-definition
     * scoped and remain cached until their owner/agent definition is evicted.
     */
    public void discardSession(String ownerId, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            agentStateStore.delete(ownerId, sessionId);
            agentCache.keySet().removeIf(k -> k.contains("/managed/" + sessionId + "/"));
        } catch (RuntimeException ex) {
            log.warn("Agent state cleanup failed for session {}: {}", sessionId, ex.getMessage());
        }
    }

    /**
     * Workspace path for definition-store staging. Product agents use the default agent-data
     * layout under the shared root (CP {@code workspacePath} is applied when building a turn).
     */
    public Path resolveAgentWorkspace(String agentOwnerId, String agentId) {
        return sharedWorkspacePaths.resolveAgentDataPath(null, agentId);
    }

    private HarnessAgent build(
            ManagedSessionDto session,
            SessionAgentBuildSpec spec,
            SessionResolveResult preResolved,
            String cacheKey) {
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

        String cpWorkspace =
                resolved.workspacePath() != null ? resolved.workspacePath().trim() : "";
        Path workspace =
                global
                        ? sharedWorkspacePaths.resolveAgentDataPath(null, agentId)
                        : sharedWorkspacePaths.resolveAgentDataPath(
                                cpWorkspace.isEmpty() ? null : cpWorkspace, agentId);

        String name = snapshot.name() != null ? snapshot.name() : agentId;
        String description = snapshot.description();
        String sysPrompt = snapshot.system();
        String modelName = snapshot.model();
        Integer maxIters = snapshot.maxIters();
        var skillRepos = snapshot.skillRepositories();

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
        sysPrompt = appendManagedExecutionPrompt(sysPrompt, resolved.executionContext());

        String instanceId =
                DP_AGENT_PREFIX
                        + session.ownerId()
                        + "-"
                        + agentId
                        + "-"
                        + Integer.toHexString(cacheKey.hashCode());

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

        List<AgentToolset> tools = snapshot.tools();
        List<McpServerSpec> mcpServers = snapshot.mcpServers();
        List<SkillRef> skillRefs = snapshot.skills();
        ToolsConfig toolsConfig = AgentSpecCodec.toToolsConfig(tools, mcpServers);
        if (toolsConfig == null && global) {
            toolsConfig = readOptionalToolsJson(workspace);
        }
        ToolsConfig resolvedTools =
                vaultCredentialResolver.resolveToolsConfig(
                        buildOwnerId, toolsConfig, spec.vaultIds());
        resolvedTools =
                withManagedCollaborationTools(
                        resolvedTools,
                        resolved.executionContext(),
                        controlPlaneClient.collaborationMcpUrl());
        if (resolvedTools != null) {
            b.toolsConfig(resolvedTools);
        }
        PermissionContextState managedTaskPermissions =
                managedTaskPermissionContext(resolved.executionContext());
        if (managedTaskPermissions != null) {
            b.permissionContext(managedTaskPermissions);
        }

        syncDefinitionFilesFromResolve(resolved, buildOwnerId, agentId);

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

        b.middleware(new ToolNotificationMiddleware(toolEventBus));
        b.middleware(toolConfirmationMiddleware);

        applyManagedSessionBuildOptions(b, buildOwnerId, agentId, workspace, spec, sysPrompt);
        HarnessAgent agent = b.build();
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

        if (merged.getAllow() != null && !merged.getAllow().isEmpty()) {
            LinkedHashSet<String> allow = new LinkedHashSet<>(merged.getAllow());
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

    private static ToolsConfig copyToolsConfig(ToolsConfig source) {
        ToolsConfig copy = new ToolsConfig();
        if (source == null) {
            return copy;
        }
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
                    + "- Use the aistio-collaboration tools for every durable read, progress"
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseOverrides(String overridesJson) {
        try {
            return JSON_MAPPER.readValue(overridesJson, Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }
}
