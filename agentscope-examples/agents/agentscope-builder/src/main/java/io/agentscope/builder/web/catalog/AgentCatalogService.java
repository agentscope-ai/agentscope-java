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
import com.fasterxml.jackson.databind.SerializationFeature;
import io.agentscope.builder.runtime.BuilderBootstrap;
import io.agentscope.builder.runtime.config.AgentConfigEntry;
import io.agentscope.builder.runtime.gateway.HarnessGateway;
import io.agentscope.builder.web.auth.UserStore;
import io.agentscope.builder.web.auth.UserStore.UserRecord;
import io.agentscope.builder.web.catalog.spec.AgentSpecCodec;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.AgentToolset;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.McpServerSpec;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.MultiagentSpec;
import io.agentscope.builder.web.catalog.spec.AgentSpecTypes.SkillRef;
import io.agentscope.builder.web.managed.AgentVersionService;
import io.agentscope.builder.web.managed.AgentVersionSnapshot;
import io.agentscope.builder.web.managed.EnvironmentDto;
import io.agentscope.builder.web.managed.MemoryMountService;
import io.agentscope.builder.web.managed.SessionAgentBuildSpec;
import io.agentscope.builder.web.managed.SessionResourceMountService;
import io.agentscope.builder.web.managed.VaultCredentialResolver;
import io.agentscope.builder.web.persistence.jpa.AgentVersionEntity;
import io.agentscope.builder.web.scaffold.WorkspaceScaffolder;
import io.agentscope.builder.web.share.AgentAclService;
import io.agentscope.builder.web.template.TemplateRegistry;
import io.agentscope.builder.web.workspace.SharedWorkspacePaths;
import io.agentscope.core.model.Model;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.tools.ToolsConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Business logic for the agent catalog: merges global agent definitions (loaded from
 * {@code agentscope.json}) with per-user custom agent definitions, and dynamically instantiates
 * user-custom agents on demand.
 *
 * <h2>Visibility rules</h2>
 *
 * <ul>
 *   <li><b>Global agents</b> ({@code scope = "global"}): defined in {@code agentscope.json},
 *       registered in {@link HarnessGateway} at startup. All users can list and converse with
 *       them; each user's conversation is isolated via a separate session keyed by
 *       {@code (userId, agentId)}.
 *   <li><b>User-custom agents</b> ({@code scope = "user"}): stored per-user in
 *       {@code .agentscope/users/{userId}/agents.json}. Only the owning user can see,
 *       create, update, or delete them. On first use they are dynamically built and registered
 *       in the gateway under the namespace key {@code uca-{userId}-{agentId}}.
 * </ul>
 */
@Service
public class AgentCatalogService {

    private static final Logger log = LoggerFactory.getLogger(AgentCatalogService.class);

    /** Prefix for user-custom agent IDs when registered in the gateway. */
    public static final String UCA_PREFIX = "uca-";

    /** Writes derived {@code workspace/tools.json} with human-readable indentation. */
    private static final ObjectMapper TOOLS_JSON_MAPPER =
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private final BuilderBootstrap builderBootstrap;
    private final UserAgentDefinitionStore store;
    private final Model model;
    private final io.agentscope.builder.web.toolbus.ToolEventBus toolEventBus;
    private final TemplateRegistry templateRegistry;
    private final SharedWorkspacePaths sharedWorkspacePaths;
    private final UserStore userStore;
    private final AgentAclService aclService;
    private final io.agentscope.builder.web.managed.EnvironmentSpecFactory environmentSpecFactory;
    private final io.agentscope.builder.web.toolbus.ToolConfirmationMiddleware
            toolConfirmationMiddleware;
    private final AgentVersionService versionService;
    private final MemoryMountService memoryMountService;
    private final VaultCredentialResolver vaultCredentialResolver;
    private final AgentStateStore agentStateStore;
    private final SessionResourceMountService sessionResourceMountService;
    private final DefinitionStore definitionStore;

    /**
     * In-flight cache of dynamically-registered gateway agent IDs. Key: {@code {userId}/{agentId}}
     * for legacy head builds, or {@code {userId}/{agentId}/{cacheSuffix}} for managed-session
     * builds. Value: the gateway agent ID.
     */
    private final ConcurrentHashMap<String, String> registeredUcaIds = new ConcurrentHashMap<>();

    /**
     * Reverse index: gateway agent id -> owner user id. Populated by {@link #buildAndRegisterUca}
     * so the gateway's {@code fsUserIdResolver} can map a chat-time {@code (callerUserId,
     * gatewayAgentId)} pair to the owner whose filesystem namespace holds the shared agent's
     * skills / subagents / memory. Entries are monotonic because the gateway id encodes the
     * owner ({@code uca-<ownerId>-<entryId>}); owners do not change after creation.
     */
    private final ConcurrentHashMap<String, String> gatewayIdToOwner = new ConcurrentHashMap<>();

    public AgentCatalogService(
            BuilderBootstrap builderBootstrap,
            UserAgentDefinitionStore store,
            Optional<Model> modelOpt,
            io.agentscope.builder.web.toolbus.ToolEventBus toolEventBus,
            TemplateRegistry templateRegistry,
            SharedWorkspacePaths sharedWorkspacePaths,
            UserStore userStore,
            AgentAclService aclService,
            AgentVersionService versionService,
            io.agentscope.builder.web.managed.EnvironmentSpecFactory environmentSpecFactory,
            @Lazy
                    io.agentscope.builder.web.toolbus.ToolConfirmationMiddleware
                            toolConfirmationMiddleware,
            MemoryMountService memoryMountService,
            VaultCredentialResolver vaultCredentialResolver,
            AgentStateStore agentStateStore,
            SessionResourceMountService sessionResourceMountService,
            DefinitionStore definitionStore) {
        this.builderBootstrap = builderBootstrap;
        this.store = store;
        this.model = modelOpt.orElse(null);
        this.toolEventBus = toolEventBus;
        this.templateRegistry = templateRegistry;
        this.sharedWorkspacePaths = sharedWorkspacePaths;
        this.userStore = userStore;
        this.aclService = aclService;
        this.versionService = versionService;
        this.environmentSpecFactory = environmentSpecFactory;
        this.toolConfirmationMiddleware = toolConfirmationMiddleware;
        this.memoryMountService = memoryMountService;
        this.vaultCredentialResolver = vaultCredentialResolver;
        this.agentStateStore = agentStateStore;
        this.sessionResourceMountService = sessionResourceMountService;
        this.definitionStore = definitionStore;
        // Install the owner-pinned filesystem user-id resolver on the gateway so chat-time reads
        // for shared (SCOPE_USER) agents land in the same namespace the controller writes to.
        // See {@link #resolveFilesystemUserId} for the resolution rules.
        builderBootstrap.gateway().setFilesystemUserIdResolver(this::resolveFilesystemUserId);
    }

    /**
     * Resolves the user id the gateway should attach to a chat turn's {@link
     * io.agentscope.core.agent.RuntimeContext}, given the caller's user id and the routed gateway
     * agent id.
     *
     * <ul>
     *   <li><strong>Globals</strong> (e.g. {@code default}): returns the caller's user id, so
     *       each caller has their own per-user overlay on the shared agent definition.
     *   <li><strong>SCOPE_USER agents</strong> (id begins with {@link #UCA_PREFIX} once
     *       registered): returns the agent owner's user id. All callers reading a shared agent
     *       therefore observe the same skills, subagents, and memory under the owner's namespace
     *       — matching the namespace the controller writes to via
     *       {@code AgentSkillsController#resolveOwner}.
     *   <li>Unknown / not-yet-registered ids: falls back to the caller's user id, so the chat
     *       path is robust against races between session restore and UCA registration.
     * </ul>
     *
     * <p>AgentStateStore routing keys are unaffected (the gateway still derives them from {@link
     * io.agentscope.harness.agent.gateway.MsgContext#userId()}), so each caller retains an
     * independent conversation thread on a shared agent.
     */
    public String resolveFilesystemUserId(String callerUserId, String agentId) {
        if (callerUserId == null || callerUserId.isBlank()) {
            return callerUserId;
        }
        if (agentId == null || agentId.isBlank()) {
            return callerUserId;
        }
        if (isGlobal(agentId)) {
            return callerUserId;
        }
        String owner = gatewayIdToOwner.get(agentId);
        return owner != null ? owner : callerUserId;
    }

    // -----------------------------------------------------------------
    //  Query
    // -----------------------------------------------------------------

    /**
     * Lists all agent definitions visible to the given user: global agents, the user's own
     * custom agents, and any user-custom agents shared with the user via a {@link
     * io.agentscope.builder.web.share.AgentShareGrant} (USER or WORKSPACE grantee).
     *
     * <p>Globals always appear first; user-custom agents follow in owner-insertion order.
     * Duplicates by id are collapsed (the first match wins, normally the user's own copy).
     */
    public List<AgentDefinition> listVisible(String userId) {
        List<AgentDefinition> result = new ArrayList<>(globalDefinitions());
        Map<String, AgentDefinition> visibleUserAgents = new LinkedHashMap<>();
        // The user's own agents first so they win id collisions over shared-in ones.
        for (AgentDefinition def : userDefinitions(userId)) {
            visibleUserAgents.put(def.id(), def);
        }
        // Then everyone else's, filtered by ACL.
        for (UserRecord owner : userStore.listAll()) {
            if (owner.userId().equals(userId)) continue;
            for (UserAgentDefinitionStore.StoredEntry e : store.list(owner.userId())) {
                AgentDefinition def = e.toDefinition(owner.userId());
                if (aclService.tierFor(userId, def) != null) {
                    visibleUserAgents.putIfAbsent(def.id(), def);
                }
            }
        }
        result.addAll(visibleUserAgents.values());
        return result;
    }

    /**
     * Finds a single visible agent definition by id. Checks global agents first, then user-custom
     * (own or shared-in).
     */
    public Optional<AgentDefinition> findVisible(String userId, String agentId) {
        return listVisible(userId).stream().filter(d -> d.id().equals(agentId)).findFirst();
    }

    /**
     * Returns the owner of a user-custom agent, or {@link Optional#empty()} for globals / unknown
     * ids. Used by share, clone, and EDIT-delegated-mutation flows to resolve the storage
     * namespace.
     */
    public Optional<String> findOwnerOf(String agentId) {
        if (isGlobal(agentId)) return Optional.empty();
        for (UserRecord owner : userStore.listAll()) {
            if (store.findById(owner.userId(), agentId).isPresent()) {
                return Optional.of(owner.userId());
            }
        }
        return Optional.empty();
    }

    /** Look up the on-disk store entry for a user-custom agent by id, across all owners. */
    public Optional<UserAgentDefinitionStore.StoredEntry> findStoredEntry(String agentId) {
        for (UserRecord owner : userStore.listAll()) {
            Optional<UserAgentDefinitionStore.StoredEntry> e =
                    store.findById(owner.userId(), agentId);
            if (e.isPresent()) return e;
        }
        return Optional.empty();
    }

    /** Returns {@code true} if the agent id refers to a global (project-level) agent. */
    public boolean isGlobal(String agentId) {
        return builderBootstrap.agents().containsKey(agentId);
    }

    /**
     * Ensures every global agent has a materialized version-1 snapshot (owner {@link
     * AgentVersionService#GLOBAL_OWNER}). Idempotent — safe to call repeatedly, including once
     * per {@link #globalDefinitions()} call and once at startup from {@link
     * io.agentscope.builder.web.managed.ManagedAgentsMigrationRunner}.
     */
    public void ensureGlobalVersions() {
        globalDefinitions();
    }

    // -----------------------------------------------------------------
    //  Mutations (user-custom agents only)
    // -----------------------------------------------------------------

    /** Creates a new user-custom agent definition for the given user. */
    public AgentDefinition createUserAgent(String userId, AgentCreateRequest req) {
        validateRequest(req);

        String id =
                sanitizeId(
                        req.id() != null && !req.id().isBlank()
                                ? req.id()
                                : UUID.randomUUID().toString().replace("-", "").substring(0, 8));

        if (store.findById(userId, id).isPresent()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Agent with id '" + id + "' already exists");
        }
        if (isGlobal(id)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Agent id '" + id + "' conflicts with a global agent");
        }

        long now = System.currentTimeMillis();
        String workspacePath = normalizeWorkspacePathInput(req.workspacePath());
        if (workspacePath == null) {
            workspacePath = id + WORKSPACE_DIR_SUFFIX;
        }
        List<AgentToolset> tools =
                req.tools() != null ? req.tools() : AgentSpecCodec.defaultToolsets();
        UserAgentDefinitionStore.StoredEntry entry =
                new UserAgentDefinitionStore.StoredEntry(
                        id,
                        req.name() != null ? req.name() : id,
                        req.description(),
                        req.system(),
                        req.model(),
                        req.maxIters(),
                        tools,
                        req.mcpServers(),
                        req.skills(),
                        req.multiagent(),
                        req.identityName(),
                        req.identityEmoji(),
                        req.groupChatMentionPatterns(),
                        req.groupChatRequireMention(),
                        now,
                        now,
                        null, // shares — new agents start unshared
                        AgentDefinition.RUN_AS_INVOKER,
                        null,
                        workspacePath,
                        req.skillRepositories(),
                        req.sandboxMode(),
                        req.sandboxScope(),
                        1,
                        null);
        UserAgentDefinitionStore.StoredEntry saved = store.save(userId, entry);
        versionService.createInitialVersion(
                userId, id, versionService.snapshotFromStoredEntry(saved));
        log.info("User '{}' created custom agent '{}'", userId, id);

        // Workspace scaffolding. Template wins over AI draft if both are supplied; otherwise fall
        // back to the WorkspaceScaffolder default. Failures are logged but do not roll back the
        // save — the workspace is regenerable from the catalog at any time.
        Path workspace = userWorkspacePath(userId, entry);
        try {
            if (req.templateId() != null && !req.templateId().isBlank()) {
                boolean ok = templateRegistry.instantiate(req.templateId(), workspace);
                if (!ok) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "Unknown templateId: " + req.templateId());
                }
            } else if (req.aiDraft() != null) {
                writeDraftFiles(workspace, req.aiDraft(), entry);
            } else {
                WorkspaceScaffolder.scaffold(workspace, entry.name(), entry.system());
            }
            writeToolsJson(workspace, saved.tools(), saved.mcpServers());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            log.warn(
                    "Failed to scaffold workspace for user-custom agent '{}/{}' at {}: {}",
                    userId,
                    id,
                    workspace,
                    e.getMessage());
        }

        return saved.toDefinition(userId);
    }

    /**
     * Best-effort local cache of derived {@code workspace/tools.json}. Authoritative tools/MCP
     * config lives in the agent version snapshot and is injected via {@code b.toolsConfig} at
     * build time — this file is optional for operators / IDE inspection and must not be required
     * for multi-replica correctness.
     */
    private static void writeToolsJson(
            Path workspace, List<AgentToolset> tools, List<McpServerSpec> mcpServers)
            throws IOException {
        ToolsConfig cfg = AgentSpecCodec.toToolsConfig(tools, mcpServers);
        String json = TOOLS_JSON_MAPPER.writeValueAsString(cfg);
        Path file = workspace.resolve("tools.json");
        Files.createDirectories(workspace);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, json + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(
                    tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Optional read of a local tools.json cache (globals / operator inspection). */
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

    private Path userWorkspacePath(String userId, UserAgentDefinitionStore.StoredEntry entry) {
        return sharedWorkspacePaths.resolveAgentDataPath(entry.workspacePath(), entry.id());
    }

    /** Suffix automatically appended to the final segment of user-supplied workspace paths. */
    static final String WORKSPACE_DIR_SUFFIX = "-workspace";

    /**
     * Trims user-supplied workspace path input. Returns {@code null} for blank input (let the
     * resolver fall back to the agent id at runtime). Absolute paths are passed through unchanged.
     * Relative paths are rejected if they contain {@code ..} traversal segments. If the final
     * path segment does not already end with {@code -workspace}, the suffix is appended so all
     * agent workspaces share a consistent on-disk naming convention.
     */
    static String normalizeWorkspacePathInput(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        Path p = Paths.get(trimmed);
        if (!p.isAbsolute()) {
            for (Path seg : p) {
                if ("..".equals(seg.toString())) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "Relative workspace path must not contain '..' segments");
                }
            }
        }
        Path fileName = p.getFileName();
        if (fileName == null) {
            return trimmed;
        }
        String leaf = fileName.toString();
        if (leaf.endsWith(WORKSPACE_DIR_SUFFIX)) {
            return trimmed;
        }
        String suffixed = leaf + WORKSPACE_DIR_SUFFIX;
        Path parent = p.getParent();
        Path rebuilt = parent != null ? parent.resolve(suffixed) : Paths.get(suffixed);
        return rebuilt.toString();
    }

    /**
     * Materializes an AI-suggested agent into the workspace folder: {@code AGENTS.md} from
     * {@code (name, description, sysPrompt)}, {@code tools.json} from {@code suggestedTools},
     * one skill file per {@code suggestedSkills} entry, one subagent file per
     * {@code suggestedSubagents} entry, and a {@code memory/.gitkeep}.
     */
    private static void writeDraftFiles(
            Path workspace, AgentDraft draft, UserAgentDefinitionStore.StoredEntry entry)
            throws IOException {
        Files.createDirectories(workspace);
        Files.createDirectories(workspace.resolve("skills"));
        Files.createDirectories(workspace.resolve("subagents"));
        Files.createDirectories(workspace.resolve("memory"));

        String displayName =
                draft.name() != null && !draft.name().isBlank()
                        ? draft.name()
                        : (entry.name() != null ? entry.name() : entry.id());
        String description =
                draft.description() != null && !draft.description().isBlank()
                        ? draft.description()
                        : (entry.description() != null ? entry.description() : "");
        String system =
                draft.sysPrompt() != null && !draft.sysPrompt().isBlank()
                        ? draft.sysPrompt()
                        : (entry.system() != null
                                ? entry.system()
                                : "You are a helpful assistant.");

        StringBuilder agentsMd = new StringBuilder();
        agentsMd.append("# ").append(displayName).append("\n\n");
        if (!description.isEmpty()) {
            agentsMd.append("> ").append(description.trim()).append("\n\n");
        }
        agentsMd.append(system.trim()).append("\n");
        writeIfMissing(workspace.resolve("AGENTS.md"), agentsMd.toString());

        // tools.json — derived from the AI-suggested tool names via the same toolset shape used
        // by the Agent body, so the workspace file matches what the catalog would persist.
        if (draft.suggestedTools() != null && !draft.suggestedTools().isEmpty()) {
            List<AgentToolset> toolsets =
                    AgentSpecCodec.toolsetsFromAllowList(draft.suggestedTools());
            ToolsConfig cfg = AgentSpecCodec.toToolsConfig(toolsets, null);
            writeIfMissing(
                    workspace.resolve("tools.json"),
                    TOOLS_JSON_MAPPER.writeValueAsString(cfg) + "\n");
        }

        // Skills
        if (draft.suggestedSkills() != null) {
            for (NamedFile sk : draft.suggestedSkills()) {
                if (sk == null || sk.name() == null || sk.name().isBlank()) continue;
                Path skillDir = workspace.resolve("skills").resolve(sanitizeName(sk.name()));
                Files.createDirectories(skillDir);
                writeIfMissing(
                        skillDir.resolve("SKILL.md"), sk.content() != null ? sk.content() : "");
            }
        }

        // Subagents
        if (draft.suggestedSubagents() != null) {
            for (NamedFile sa : draft.suggestedSubagents()) {
                if (sa == null || sa.name() == null || sa.name().isBlank()) continue;
                Path file = workspace.resolve("subagents").resolve(sanitizeName(sa.name()) + ".md");
                writeIfMissing(file, sa.content() != null ? sa.content() : "");
            }
        }

        writeIfMissing(workspace.resolve("memory").resolve(".gitkeep"), "");
    }

    private static String sanitizeName(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_-]", "-").toLowerCase();
    }

    private static void writeIfMissing(Path file, String content) throws IOException {
        if (Files.exists(file)) return;
        Path parent = file.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, content, StandardCharsets.UTF_8);
        try {
            Files.move(
                    tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Updates an existing user-custom agent definition. Only the owner may update. */
    public AgentDefinition updateUserAgent(String userId, String agentId, AgentCreateRequest req) {
        validateRequest(req);
        UserAgentDefinitionStore.StoredEntry existing =
                store.findById(userId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));

        if (existing.archivedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent is archived: " + agentId);
        }
        if (req.version() == null || req.version() != existing.version()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "version mismatch");
        }

        long now = System.currentTimeMillis();
        UserAgentDefinitionStore.StoredEntry updated =
                new UserAgentDefinitionStore.StoredEntry(
                        agentId,
                        req.name() != null ? req.name() : existing.name(),
                        req.description() != null ? req.description() : existing.description(),
                        req.system() != null ? req.system() : existing.system(),
                        req.model() != null ? req.model() : existing.model(),
                        req.maxIters() != null ? req.maxIters() : existing.maxIters(),
                        req.tools() != null ? req.tools() : existing.tools(),
                        req.mcpServers() != null ? req.mcpServers() : existing.mcpServers(),
                        req.skills() != null ? req.skills() : existing.skills(),
                        req.multiagent() != null ? req.multiagent() : existing.multiagent(),
                        req.identityName() != null ? req.identityName() : existing.identityName(),
                        req.identityEmoji() != null
                                ? req.identityEmoji()
                                : existing.identityEmoji(),
                        req.groupChatMentionPatterns() != null
                                ? req.groupChatMentionPatterns()
                                : existing.groupChatMentionPatterns(),
                        req.groupChatRequireMention() != null
                                ? req.groupChatRequireMention()
                                : existing.groupChatRequireMention(),
                        existing.createdAt(),
                        now,
                        existing.shares(), // sharing is managed via the share API, not settings
                        existing.runAs() != null
                                ? existing.runAs()
                                : AgentDefinition.RUN_AS_INVOKER,
                        existing.forkOf(),
                        existing.workspacePath(), // workspacePath is creation-only
                        req.skillRepositories() != null
                                ? req.skillRepositories()
                                : existing.skillRepositories(),
                        req.sandboxMode() != null ? req.sandboxMode() : existing.sandboxMode(),
                        req.sandboxScope() != null ? req.sandboxScope() : existing.sandboxScope(),
                        existing.version(),
                        existing.archivedAt());
        UserAgentDefinitionStore.StoredEntry saved = store.save(userId, updated);
        int newVersion =
                versionService.appendVersion(
                        userId, agentId, versionService.snapshotFromStoredEntry(saved));
        saved =
                new UserAgentDefinitionStore.StoredEntry(
                        saved.id(),
                        saved.name(),
                        saved.description(),
                        saved.system(),
                        saved.model(),
                        saved.maxIters(),
                        saved.tools(),
                        saved.mcpServers(),
                        saved.skills(),
                        saved.multiagent(),
                        saved.identityName(),
                        saved.identityEmoji(),
                        saved.groupChatMentionPatterns(),
                        saved.groupChatRequireMention(),
                        saved.createdAt(),
                        saved.updatedAt(),
                        saved.shares(),
                        saved.runAs(),
                        saved.forkOf(),
                        saved.workspacePath(),
                        saved.skillRepositories(),
                        saved.sandboxMode(),
                        saved.sandboxScope(),
                        newVersion,
                        saved.archivedAt());

        try {
            Path workspace = userWorkspacePath(userId, saved);
            writeToolsJson(workspace, saved.tools(), saved.mcpServers());
        } catch (IOException e) {
            log.warn(
                    "Failed to write tools.json for user-custom agent '{}/{}': {}",
                    userId,
                    agentId,
                    e.getMessage());
        }

        // Evict cached gateway registration so the next conversation picks up the new definition.
        evictUcaCache(userId, agentId);

        log.info("User '{}' updated custom agent '{}'", userId, agentId);
        return saved.toDefinition(userId);
    }

    /** Archives a user-custom agent so it becomes read-only. */
    public AgentDefinition archiveUserAgent(String userId, String agentId) {
        UserAgentDefinitionStore.StoredEntry existing =
                store.findById(userId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));
        if (existing.archivedAt() != null) {
            return existing.toDefinition(userId);
        }
        long now = System.currentTimeMillis();
        UserAgentDefinitionStore.StoredEntry archived =
                new UserAgentDefinitionStore.StoredEntry(
                        existing.id(),
                        existing.name(),
                        existing.description(),
                        existing.system(),
                        existing.model(),
                        existing.maxIters(),
                        existing.tools(),
                        existing.mcpServers(),
                        existing.skills(),
                        existing.multiagent(),
                        existing.identityName(),
                        existing.identityEmoji(),
                        existing.groupChatMentionPatterns(),
                        existing.groupChatRequireMention(),
                        existing.createdAt(),
                        now,
                        existing.shares(),
                        existing.runAs(),
                        existing.forkOf(),
                        existing.workspacePath(),
                        existing.skillRepositories(),
                        existing.sandboxMode(),
                        existing.sandboxScope(),
                        existing.version(),
                        now);
        UserAgentDefinitionStore.StoredEntry saved = store.save(userId, archived);
        evictUcaCache(userId, agentId);
        log.info("User '{}' archived custom agent '{}'", userId, agentId);
        return saved.toDefinition(userId);
    }

    /** Lists version metadata for a user-custom agent. */
    public List<AgentVersionSummary> listAgentVersions(String userId, String agentId) {
        store.findById(userId, agentId)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Agent not found: " + agentId));
        return versionService.listVersions(userId, agentId).stream()
                .map(v -> new AgentVersionSummary(v.getVersion(), v.getCreatedAt()))
                .toList();
    }

    /** Returns a specific version snapshot for a user-custom agent. */
    public AgentVersionDetail getAgentVersion(String userId, String agentId, int version) {
        store.findById(userId, agentId)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Agent not found: " + agentId));
        AgentVersionEntity entity = versionService.getVersion(userId, agentId, version);
        AgentVersionSnapshot snapshot = versionService.fromJson(entity.getSnapshotJson());
        return new AgentVersionDetail(entity.getVersion(), entity.getCreatedAt(), snapshot);
    }

    /**
     * Materializes a clone of {@code (srcOwnerId, srcAgentId)} in {@code newOwnerId}'s namespace.
     * The clone copies settings (name/description/sysPrompt/tools/skills/identity) and
     * marks {@code forkOf = srcAgentId}. Shares, sessions, and channel bindings start empty —
     * see plan §5.
     *
     * <p>Caller is responsible for invoking {@link
     * io.agentscope.builder.web.util.WorkspaceCopier#copy} to copy files; this method only writes
     * the catalog entry.
     *
     * @param requestedId optional preferred id; if blank or already taken in newOwner's namespace,
     *     a short random id is generated.
     * @param requestedName optional preferred display name; defaults to "{src.name} (copy)".
     */
    public StoredEntryAndDefinition prepareClone(
            String srcOwnerId,
            String srcAgentId,
            String newOwnerId,
            String requestedId,
            String requestedName) {
        UserAgentDefinitionStore.StoredEntry src =
                store.findById(srcOwnerId, srcAgentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Source agent not found: " + srcAgentId));

        String id;
        if (requestedId != null && !requestedId.isBlank()) {
            id = sanitizeId(requestedId);
            if (store.findById(newOwnerId, id).isPresent() || isGlobal(id)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT, "Agent id '" + id + "' already taken");
            }
        } else {
            id = uniqueIdInNamespace(newOwnerId, srcAgentId);
        }

        String name =
                requestedName != null && !requestedName.isBlank()
                        ? requestedName
                        : (src.name() != null ? src.name() + " (copy)" : id);

        long now = System.currentTimeMillis();
        UserAgentDefinitionStore.StoredEntry clone =
                new UserAgentDefinitionStore.StoredEntry(
                        id,
                        name,
                        src.description(),
                        src.system(),
                        src.model(),
                        src.maxIters(),
                        src.tools(),
                        src.mcpServers(),
                        src.skills(),
                        src.multiagent(),
                        src.identityName(),
                        src.identityEmoji(),
                        src.groupChatMentionPatterns(),
                        src.groupChatRequireMention(),
                        now,
                        now,
                        null, // shares — clones start unshared
                        src.runAs(),
                        srcAgentId, // forkOf
                        id + WORKSPACE_DIR_SUFFIX, // workspacePath — clone uses its own id +
                        // suffix
                        src.skillRepositories(),
                        src.sandboxMode(),
                        src.sandboxScope(),
                        1,
                        null);
        UserAgentDefinitionStore.StoredEntry saved = store.save(newOwnerId, clone);
        versionService.createInitialVersion(
                newOwnerId, id, versionService.snapshotFromStoredEntry(saved));
        log.info(
                "User '{}' cloned agent '{}/{}' as '{}/{}'",
                newOwnerId,
                srcOwnerId,
                srcAgentId,
                newOwnerId,
                id);
        return new StoredEntryAndDefinition(saved, saved.toDefinition(newOwnerId));
    }

    private String uniqueIdInNamespace(String owner, String preferredBase) {
        String base = sanitizeId(preferredBase);
        if (!store.findById(owner, base).isPresent() && !isGlobal(base)) {
            return base + "-copy";
        }
        for (int i = 0; i < 16; i++) {
            String candidate =
                    base + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 6);
            if (!store.findById(owner, candidate).isPresent() && !isGlobal(candidate)) {
                return candidate;
            }
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT, "Could not allocate a unique agent id for clone");
    }

    /** Holder for the freshly-cloned entry + its API view. */
    public record StoredEntryAndDefinition(
            UserAgentDefinitionStore.StoredEntry entry, AgentDefinition definition) {}

    /** Deletes a user-custom agent definition. Only the owner may delete. */
    public void deleteUserAgent(String userId, String agentId) {
        if (!store.delete(userId, agentId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: " + agentId);
        }
        evictUcaCache(userId, agentId);
        log.info("User '{}' deleted custom agent '{}'", userId, agentId);
    }

    /**
     * Drops the cached UCA registration for a user-custom agent so the next chat call rebuilds
     * the {@link HarnessAgent} from the current {@link UserAgentDefinitionStore} entry. The
     * {@code userId} may be either the caller or the owner — both are resolved to the owner
     * before eviction so the single canonical cache entry is dropped.
     */
    public void invalidateUca(String userId, String agentId) {
        if (agentId == null) return;
        if (isGlobal(agentId)) return;
        String ownerId = findOwnerOf(agentId).orElse(userId);
        if (ownerId == null) return;
        evictUcaCache(ownerId, agentId);
    }

    /**
     * Resolves the running {@link HarnessAgent} for {@code (userId, agentId)}, returning {@code
     * null} if the agent does not exist or was never built. Use {@link
     * #getOrInstantiateRunningAgent} when the controller needs the agent built on demand.
     */
    public HarnessAgent getRunningAgent(String userId, String agentId) {
        if (agentId == null) return null;
        if (isGlobal(agentId)) {
            return builderBootstrap.agents().get(agentId);
        }
        if (findVisible(userId, agentId).isEmpty()) {
            return null;
        }
        String ownerId = findOwnerOf(agentId).orElse(null);
        if (ownerId == null) return null;
        String gatewayId = peekGatewayAgentId(ownerId, agentId);
        return builderBootstrap.gateway().findAgent(gatewayId);
    }

    /**
     * Resolves the running {@link HarnessAgent} for {@code (userId, agentId)}, building and
     * registering the UCA on first access if necessary. For globals, returns the
     * bootstrap-registered instance. Returns {@code null} if the caller has no visibility on
     * the agent.
     *
     * <p>The agent is built once per owner; subsequent callers (including users who have the
     * agent shared in to them) reuse the same {@link HarnessAgent} and rely on its per-user
     * composite-filesystem overlay (via {@code workspaceFor(callerUserId, sessionId)}) for
     * isolation. This is the entry point controllers should use whenever they need to interact
     * with the agent's runtime state.
     */
    public HarnessAgent getOrInstantiateRunningAgent(String userId, String agentId) {
        return getOrInstantiateRunningAgent(userId, agentId, null);
    }

    /**
     * Resolves (and caches) a {@link HarnessAgent} for a managed session, keyed by {@code (owner,
     * agent, version, environment, mounts)}. When {@code spec} is {@code null}, falls back to the
     * legacy head-build path.
     */
    public HarnessAgent getOrInstantiateRunningAgent(
            String userId, String agentId, SessionAgentBuildSpec spec) {
        if (agentId == null) {
            return null;
        }
        if (spec == null) {
            if (isGlobal(agentId)) {
                return builderBootstrap.agents().get(agentId);
            }
            if (findVisible(userId, agentId).isEmpty()) {
                return null;
            }
            String ownerId = findOwnerOf(agentId).orElse(null);
            if (ownerId == null) {
                return null;
            }
            UserAgentDefinitionStore.StoredEntry entry =
                    store.findById(ownerId, agentId)
                            .orElseThrow(
                                    () ->
                                            new ResponseStatusException(
                                                    HttpStatus.NOT_FOUND,
                                                    "Agent not found: " + agentId));
            String gatewayId =
                    registeredUcaIds.computeIfAbsent(
                            ucaCacheKey(ownerId, agentId),
                            k -> buildAndRegisterUca(ownerId, entry, null));
            return builderBootstrap.gateway().findAgent(gatewayId);
        }

        if (findVisible(userId, agentId).isEmpty() && !isGlobal(agentId)) {
            return null;
        }
        String ownerId = isGlobal(agentId) ? userId : findOwnerOf(agentId).orElse(userId);
        String cacheKey = ucaCacheKey(ownerId, agentId) + "/" + spec.cacheSuffix();
        String gatewayId =
                registeredUcaIds.computeIfAbsent(
                        cacheKey, k -> buildManagedSessionAgent(ownerId, agentId, spec));
        return builderBootstrap.gateway().findAgent(gatewayId);
    }

    /** Returns the workspace path used for a user-custom (or global-managed) agent. */
    public Path resolveAgentWorkspace(String ownerId, String agentId) {
        if (isGlobal(agentId)) {
            AgentConfigEntry cfg =
                    builderBootstrap.loadedConfig().getAgents() != null
                            ? builderBootstrap.loadedConfig().getAgents().get(agentId)
                            : null;
            String ws = cfg != null ? cfg.getWorkspace() : null;
            if (ws != null && !ws.isBlank()) {
                return Paths.get(ws).toAbsolutePath().normalize();
            }
            return sharedWorkspacePaths.resolveAgentDataPath(null, agentId);
        }
        UserAgentDefinitionStore.StoredEntry entry =
                store.findById(ownerId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));
        return userWorkspacePath(ownerId, entry);
    }

    // -----------------------------------------------------------------
    //  Gateway routing support
    // -----------------------------------------------------------------

    /**
     * Resolves the gateway agent ID to use when routing a chat message to the given agent.
     *
     * <ul>
     *   <li>For global agents: returns the agent id as-is (already in gateway registry).
     *   <li>For user-custom agents: ensures the agent is built and registered in the gateway
     *       under the <em>owner</em>'s namespace (so shared-in callers reuse the same instance),
     *       then returns the namespaced gateway id ({@code uca-{ownerId}-{agentId}}).
     * </ul>
     *
     * @throws ResponseStatusException 404 if the agent is not visible to the user
     */
    public String resolveGatewayAgentId(String userId, String agentId) {
        if (isGlobal(agentId)) {
            return agentId;
        }
        if (findVisible(userId, agentId).isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Agent not found or not accessible: " + agentId);
        }
        String ownerId =
                findOwnerOf(agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));
        UserAgentDefinitionStore.StoredEntry entry =
                store.findById(ownerId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));
        return registeredUcaIds.computeIfAbsent(
                ucaCacheKey(ownerId, agentId), k -> buildAndRegisterUca(ownerId, entry, null));
    }

    /**
     * Returns the gateway agent id that {@link #resolveGatewayAgentId} would produce, without
     * building or registering the agent. The id is keyed by <em>owner</em> for user-custom
     * agents so shared-in callers and the owner resolve to the same gateway entry.
     */
    public String peekGatewayAgentId(String userId, String agentId) {
        if (agentId == null) return null;
        if (isGlobal(agentId)) return agentId;
        String ownerId = findOwnerOf(agentId).orElse(userId);
        String gatewayAgentId = UCA_PREFIX + ownerId + "-" + agentId;
        // Defensive: keep gatewayIdToOwner warm even when the UCA hasn't been built yet (e.g.
        // session restore paths that reach the gateway before any controller has triggered
        // {@link #getOrInstantiateRunningAgent}). Entries are monotonic, so this is safe.
        if (ownerId != null && !ownerId.isBlank()) {
            gatewayIdToOwner.putIfAbsent(gatewayAgentId, ownerId);
        }
        return gatewayAgentId;
    }

    // -----------------------------------------------------------------
    //  Internals
    // -----------------------------------------------------------------

    private List<AgentDefinition> globalDefinitions() {
        Map<String, AgentConfigEntry> fileAgents = builderBootstrap.loadedConfig().getAgents();
        List<AgentDefinition> result = new ArrayList<>();
        for (Map.Entry<String, HarnessAgent> e : builderBootstrap.agents().entrySet()) {
            String id = e.getKey();
            AgentConfigEntry cfg = fileAgents != null ? fileAgents.get(id) : null;
            String name = cfg != null && cfg.getName() != null ? cfg.getName() : id;
            String desc = cfg != null ? cfg.getDescription() : null;

            AgentConfigEntry.ToolsConfig tc = cfg != null ? cfg.getTools() : null;
            AgentConfigEntry.IdentityConfig ic = cfg != null ? cfg.getIdentity() : null;
            AgentConfigEntry.GroupChatConfig gc = cfg != null ? cfg.getGroupChat() : null;
            AgentConfigEntry.SkillsConfig sk = cfg != null ? cfg.getSkills() : null;

            List<AgentToolset> toolsSpec =
                    tc != null && tc.getAllow() != null && !tc.getAllow().isEmpty()
                            ? AgentSpecCodec.toolsetsFromAllowList(tc.getAllow())
                            : null;
            List<SkillRef> skillsSpec =
                    sk != null && sk.getAllow() != null
                            ? AgentSpecCodec.workspaceSkills(sk.getAllow())
                            : null;

            AgentVersionSnapshot snapshot =
                    new AgentVersionSnapshot(
                            name,
                            desc,
                            cfg != null ? cfg.getSysPrompt() : null,
                            cfg != null ? cfg.getModel() : null,
                            cfg != null ? cfg.getMaxIters() : null,
                            toolsSpec,
                            null, // mcpServers — globals declare MCP servers via tools.json only
                            skillsSpec,
                            null, // multiagent
                            ic != null ? ic.getName() : null,
                            ic != null ? ic.getEmoji() : null,
                            gc != null ? gc.getMentionPatterns() : null,
                            gc != null ? gc.getRequireMention() : null,
                            cfg != null ? cfg.effectiveSkillRepositories() : null,
                            null, // sandboxMode — globals follow the platform default
                            null); // sandboxScope
            Integer headVersion = versionService.ensureGlobalVersion(id, snapshot);

            result.add(
                    new AgentDefinition(
                            id,
                            name,
                            desc,
                            null, // don't expose system prompt in global catalog
                            cfg != null ? cfg.getModel() : null,
                            cfg != null ? cfg.getMaxIters() : null,
                            toolsSpec,
                            null, // mcpServers
                            skillsSpec,
                            null, // multiagent
                            ic != null ? ic.getName() : null,
                            ic != null ? ic.getEmoji() : null,
                            gc != null ? gc.getMentionPatterns() : null,
                            gc != null ? gc.getRequireMention() : null,
                            AgentDefinition.SCOPE_GLOBAL,
                            null,
                            0L,
                            0L,
                            null, // shares — globals are never shared individually
                            AgentDefinition.RUN_AS_INVOKER,
                            null, // forkOf
                            cfg != null ? cfg.getWorkspace() : null, // mirror runtime workspace
                            null, // sandboxMode — globals follow the platform default
                            null, // sandboxScope
                            headVersion, // version — head of the materialized global snapshot
                            null, // archivedAt
                            null, // metadata
                            null)); // tierForCurrentUser — populated by the controller
        }
        return result;
    }

    private List<AgentDefinition> userDefinitions(String userId) {
        return store.list(userId).stream().map(e -> e.toDefinition(userId)).toList();
    }

    private String buildManagedSessionAgent(
            String ownerId, String agentId, SessionAgentBuildSpec spec) {
        if (isGlobal(agentId)) {
            return buildAndRegisterManagedGlobal(ownerId, agentId, spec);
        }
        UserAgentDefinitionStore.StoredEntry entry =
                store.findById(ownerId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));
        return buildAndRegisterUca(ownerId, entry, spec);
    }

    private String buildAndRegisterManagedGlobal(
            String sessionOwnerId, String agentId, SessionAgentBuildSpec spec) {
        String gatewayAgentId =
                UCA_PREFIX
                        + "mgr-"
                        + sessionOwnerId
                        + "-"
                        + agentId
                        + "-"
                        + Integer.toHexString(spec.cacheSuffix().hashCode());
        Path workspace = resolveAgentWorkspace(sessionOwnerId, agentId);

        AgentConfigEntry cfg =
                builderBootstrap.loadedConfig().getAgents() != null
                        ? builderBootstrap.loadedConfig().getAgents().get(agentId)
                        : null;

        HarnessAgent.Builder b = HarnessAgent.builder();
        b.agentId(gatewayAgentId);
        b.name(cfg != null && cfg.getName() != null ? cfg.getName() : agentId);
        if (cfg != null && cfg.getDescription() != null) {
            b.description(cfg.getDescription());
        }
        if (cfg != null && cfg.getSysPrompt() != null) {
            b.sysPrompt(cfg.getSysPrompt());
        }
        if (cfg != null && cfg.getMaxIters() != null) {
            b.maxIters(cfg.getMaxIters());
        }
        if (cfg != null && cfg.getModel() != null && !cfg.getModel().isBlank()) {
            b.model(cfg.getModel());
        } else if (model != null) {
            b.model(model);
        }
        b.workspace(workspace);
        b.stateStore(agentStateStore);
        b.externalSubagentTool(builderBootstrap.sessionsTool());

        // Prefer an in-memory ToolsConfig so vault injection does not require node-local
        // tools.json affinity. When the file is absent, still allow vault-only env injection
        // onto an empty MCP map.
        ToolsConfig baseTools = readOptionalToolsJson(workspace);
        if (baseTools == null) {
            baseTools = new ToolsConfig();
        }
        ToolsConfig patched =
                vaultCredentialResolver.resolveToolsConfig(
                        sessionOwnerId, baseTools, spec.vaultIds());
        if (patched != null) {
            b.toolsConfig(patched);
        }

        // Hands primary filesystem is owned exclusively by Environment.
        applyManagedSessionBuildOptions(
                b,
                sessionOwnerId,
                agentId,
                workspace,
                spec,
                cfg != null ? cfg.getSysPrompt() : null);

        HarnessAgent agent = b.build();
        builderBootstrap.gateway().registerAgent(gatewayAgentId, agent);
        gatewayIdToOwner.put(gatewayAgentId, sessionOwnerId);
        log.info(
                "Registered managed global agent clone: sessionOwner={}, agentId={}, gatewayId={}",
                sessionOwnerId,
                agentId,
                gatewayAgentId);
        return gatewayAgentId;
    }

    private String buildAndRegisterUca(
            String userId, UserAgentDefinitionStore.StoredEntry entry, SessionAgentBuildSpec spec) {
        boolean managed = spec != null;
        String gatewayAgentId =
                managed
                        ? UCA_PREFIX
                                + userId
                                + "-"
                                + entry.id()
                                + "-"
                                + Integer.toHexString(spec.cacheSuffix().hashCode())
                        : UCA_PREFIX + userId + "-" + entry.id();

        Path workspace = userWorkspacePath(userId, entry);

        AgentVersionSnapshot snapshot = null;
        if (managed && spec.version() != null) {
            try {
                AgentVersionEntity versionEntity =
                        versionService.getVersion(userId, entry.id(), spec.version());
                snapshot = versionService.fromJson(versionEntity.getSnapshotJson());
            } catch (Exception ex) {
                log.warn(
                        "Failed to load agent version {}/{}@{}: {}",
                        userId,
                        entry.id(),
                        spec.version(),
                        ex.getMessage());
            }
        }

        String name =
                snapshot != null && snapshot.name() != null
                        ? snapshot.name()
                        : (entry.name() != null ? entry.name() : entry.id());
        String description = snapshot != null ? snapshot.description() : entry.description();
        String sysPrompt = snapshot != null ? snapshot.system() : entry.system();
        String modelName = snapshot != null ? snapshot.model() : entry.model();
        Integer maxIters = snapshot != null ? snapshot.maxIters() : entry.maxIters();
        var skillRepos =
                snapshot != null ? snapshot.skillRepositories() : entry.skillRepositories();
        String sandboxMode = snapshot != null ? snapshot.sandboxMode() : entry.sandboxMode();
        String sandboxScope = snapshot != null ? snapshot.sandboxScope() : entry.sandboxScope();

        if (managed && spec.overridesJson() != null && !spec.overridesJson().isBlank()) {
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

        HarnessAgent.Builder b = HarnessAgent.builder();

        // Pin the stable namespace key to the gateway agentId (unique across users). The display
        // name (b.name) is human-facing and may change without rewriting any composite-filesystem
        // keys under [agents, <gatewayAgentId>, ...].
        b.agentId(gatewayAgentId);
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
        // Model: prefer per-agent override, fall back to bootstrap-level model.
        if (modelName != null && !modelName.isBlank()) {
            b.model(modelName);
        } else if (model != null) {
            b.model(model);
        }
        b.workspace(workspace);
        b.stateStore(agentStateStore);

        // Tools / MCP: version snapshot (or head entry) is authoritative — inject ToolsConfig
        // so HarnessAgent.build does not depend on a node-local tools.json.
        List<AgentToolset> tools = snapshot != null ? snapshot.tools() : entry.tools();
        List<McpServerSpec> mcpServers =
                snapshot != null ? snapshot.mcpServers() : entry.mcpServers();
        List<SkillRef> skillRefs = snapshot != null ? snapshot.skills() : entry.skills();
        ToolsConfig toolsConfig = AgentSpecCodec.toToolsConfig(tools, mcpServers);
        List<String> vaultIds = managed ? spec.vaultIds() : List.of();
        ToolsConfig resolved =
                vaultCredentialResolver.resolveToolsConfig(userId, toolsConfig, vaultIds);
        if (resolved != null) {
            b.toolsConfig(resolved);
        }

        // Skills: control-plane DefinitionStore + optional git/fs skillRepositories.
        // Do not rely on Hands primary filesystem Layer-4 workspace skills (sandbox would
        // look inside the sandbox, not the definition store).
        List<AgentSkillRepository> skillReposList = new ArrayList<>();
        skillReposList.add(new DefinitionStoreSkillRepository(definitionStore, userId, entry.id()));
        if (skillRepos != null && !skillRepos.isEmpty()) {
            var repos =
                    io.agentscope.builder.runtime.config.SkillRepositorySupport.createAll(
                            workspace, skillRepos);
            skillReposList.addAll(repos);
        }
        b.skillRepositories(skillReposList);
        b.disableDefaultWorkspaceSkills();
        List<String> workspaceSkillNames = AgentSpecCodec.workspaceSkillNames(skillRefs);
        if (!workspaceSkillNames.isEmpty()) {
            b.enableSkills(workspaceSkillNames.toArray(String[]::new));
        }

        // Pre-populate this user-custom agent's toolkit with the outbound-send tool so the agent
        // can proactively push messages into any registered IM channel (subject to per-agent
        // tier ACL enforced at OutboundController + channel-routing check in OutboundService).
        io.agentscope.core.tool.Toolkit ucaToolkit = new io.agentscope.core.tool.Toolkit();
        ucaToolkit.registerTool(
                new io.agentscope.builder.runtime.outbound.OutboundTool(
                        builderBootstrap.channelManager()));
        b.toolkit(ucaToolkit);

        // Inject the shared SessionsTool so user-custom agents can spawn SUBAGENT sessions
        // through the same SessionAgentManager as global (agentscope.json) agents.
        //
        // Hands note: subagent sessions spawned via SessionsTool do not get their own
        // HandsLeaseService lease — they run in-process against the parent's RuntimeContext, so a
        // self_hosted subagent currently shares the parent turn's externalSandbox (same
        // SandboxContext, same worker-attached WorkspaceSandbox). Isolating subagent hands behind
        // their own lease/IsolationScope is deferred (see Hands/Worker plan Phase D).
        b.externalSubagentTool(builderBootstrap.sessionsTool());

        // Inject ToolNotificationMiddleware so user-custom agents also publish tool-call events.
        b.middleware(
                new io.agentscope.builder.web.toolbus.ToolNotificationMiddleware(toolEventBus));
        b.middleware(toolConfirmationMiddleware);

        // Hands primary filesystem is owned exclusively by Environment (managed) or agent
        // sandbox fields (legacy). Do not pre-mount RemoteFilesystem here — it conflicts with
        // sandbox/local (at most one of local/remote/sandbox spec).
        if (managed) {
            applyManagedSessionBuildOptions(b, userId, entry.id(), workspace, spec, sysPrompt);
        } else {
            environmentSpecFactory.applyAgentSandboxFields(b, sandboxMode, sandboxScope);
        }

        HarnessAgent agent = b.build();

        HarnessGateway gateway = builderBootstrap.gateway();
        gateway.registerAgent(gatewayAgentId, agent);
        // Record the gatewayId -> owner mapping so the gateway's fsUserIdResolver can pin
        // shared-agent chat reads to the owner's filesystem namespace. {@code userId} here is
        // the owner (see {@link #getOrInstantiateRunningAgent}).
        gatewayIdToOwner.put(gatewayAgentId, userId);

        log.info(
                "Registered user-custom agent in gateway: userId={}, agentId={}, gatewayId={},"
                        + " managed={}",
                userId,
                entry.id(),
                gatewayAgentId,
                managed);

        return gatewayAgentId;
    }

    private void applyManagedSessionBuildOptions(
            HarnessAgent.Builder b,
            String ownerId,
            String agentId,
            Path workspace,
            SessionAgentBuildSpec spec,
            String baseSysPrompt) {
        EnvironmentDto environment = spec.environment();
        if (environment != null) {
            environmentSpecFactory.applyEnvironment(b, environment);
        } else {
            environmentSpecFactory.applyAgentSandboxFields(b, null, null);
        }

        var filesystems =
                memoryMountService.createFilesystems(
                        ownerId, spec.memoryStoreIds(), memoryAccessOverrides(environment));
        environmentSpecFactory.applyMemoryStoreRoutes(b, ownerId, filesystems);
        var mounts = memoryMountService.resolveMounts(ownerId, spec.memoryStoreIds());
        String appendix = memoryMountService.promptAppendix(mounts);
        if (appendix != null) {
            String combined = (baseSysPrompt == null ? "" : baseSysPrompt) + appendix;
            b.sysPrompt(combined);
        }

        // ToolsConfig already injected from the version snapshot in buildAndRegisterUca.
        // Vault patching also happens there using spec.vaultIds().

        // Stage session resources into the Hands workspace Path used by the Environment
        // filesystem (local disk / sandbox workspace root). Not a RemoteFilesystem primary.
        sessionResourceMountService.restageFromDefinitionStore(
                definitionStore, ownerId, agentId, workspace);
        sessionResourceMountService.apply(workspace, spec.resources());
        sessionResourceMountService.mirrorFileResourcesToDefinitionStore(
                definitionStore, ownerId, agentId, spec.resources());
    }

    /**
     * Extracts a {@code storeId -> "read_only"|"read_write"} map from {@code
     * environment.config().memoryAccess}, if present, so a session's environment can pin some
     * mounted memory stores read-only (e.g. shared reference knowledge bases).
     */
    @SuppressWarnings("unchecked")
    private static Map<String, String> memoryAccessOverrides(EnvironmentDto environment) {
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseOverrides(String overridesJson) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(overridesJson, Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static String ucaCacheKey(String userId, String agentId) {
        return userId + "/" + agentId;
    }

    /** Evicts all cached UCA variants for an owner/agent pair (legacy head + managed). */
    void evictUcaCache(String ownerId, String agentId) {
        String prefix = ucaCacheKey(ownerId, agentId);
        registeredUcaIds
                .entrySet()
                .removeIf(e -> e.getKey().equals(prefix) || e.getKey().startsWith(prefix + "/"));
    }

    private static void validateRequest(AgentCreateRequest req) {
        if (req == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body required");
        }
        if (req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'name' is required");
        }
    }

    private static String sanitizeId(String raw) {
        return raw.replaceAll("[^a-zA-Z0-9_-]", "-").toLowerCase();
    }

    // -----------------------------------------------------------------
    //  Request DTO
    // -----------------------------------------------------------------

    /** Request body for creating or updating a user-custom agent. */
    public record AgentCreateRequest(
            String id,
            String name,
            String description,
            String system,
            String model,
            Integer maxIters,
            List<AgentToolset> tools,
            List<McpServerSpec> mcpServers,
            List<SkillRef> skills,
            MultiagentSpec multiagent,
            String identityName,
            String identityEmoji,
            List<String> groupChatMentionPatterns,
            Boolean groupChatRequireMention,
            String workspacePath,
            String templateId,
            AgentDraft aiDraft,
            List<io.agentscope.builder.runtime.config.SkillRepositoryConfigEntry> skillRepositories,
            String sandboxMode,
            String sandboxScope,
            Integer version) {}

    /** Summary metadata for one agent version row. */
    public record AgentVersionSummary(int version, long createdAt) {}

    /** Full snapshot payload for one agent version row. */
    public record AgentVersionDetail(int version, long createdAt, AgentVersionSnapshot snapshot) {}

    /**
     * Optional AI-generated draft attached to a creation request. Carries the suggested
     * configuration plus optional skill/subagent files to scaffold into the new agent's workspace.
     * Wiring into {@link #createUserAgent(String, AgentCreateRequest)} happens in a later phase.
     */
    public record AgentDraft(
            String name,
            String description,
            String sysPrompt,
            List<String> suggestedTools,
            List<NamedFile> suggestedSkills,
            List<NamedFile> suggestedSubagents) {}

    /** A named file (e.g. a markdown skill or subagent definition). */
    public record NamedFile(String name, String content) {}
}
