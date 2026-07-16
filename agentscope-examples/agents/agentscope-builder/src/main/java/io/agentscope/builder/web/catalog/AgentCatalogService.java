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

import io.agentscope.builder.runtime.BuilderBootstrap;
import io.agentscope.builder.runtime.config.AgentConfigEntry;
import io.agentscope.builder.runtime.gateway.HarnessGateway;
import io.agentscope.builder.web.auth.UserStore;
import io.agentscope.builder.web.auth.UserStore.UserRecord;
import io.agentscope.builder.web.scaffold.WorkspaceScaffolder;
import io.agentscope.builder.web.share.AgentAclService;
import io.agentscope.builder.web.template.TemplateRegistry;
import io.agentscope.builder.web.workspace.SharedWorkspacePaths;
import io.agentscope.core.model.Model;
import io.agentscope.harness.agent.HarnessAgent;
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

    private final BuilderBootstrap builderBootstrap;
    private final UserAgentDefinitionStore store;
    private final Model model;
    private final TemplateRegistry templateRegistry;
    private final SharedWorkspacePaths sharedWorkspacePaths;
    private final UserStore userStore;
    private final AgentAclService aclService;

    /**
     * In-flight cache of dynamically-registered gateway agent IDs. Key: {@code {userId}/{agentId}},
     * Value: the gateway agent ID (e.g. {@code uca-{userId}-{agentId}}).
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
            TemplateRegistry templateRegistry,
            SharedWorkspacePaths sharedWorkspacePaths,
            UserStore userStore,
            AgentAclService aclService) {
        this.builderBootstrap = builderBootstrap;
        this.store = store;
        this.model = modelOpt.orElse(null);
        this.templateRegistry = templateRegistry;
        this.sharedWorkspacePaths = sharedWorkspacePaths;
        this.userStore = userStore;
        this.aclService = aclService;
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
        UserAgentDefinitionStore.StoredEntry entry =
                new UserAgentDefinitionStore.StoredEntry(
                        id,
                        req.name() != null ? req.name() : id,
                        req.description(),
                        req.sysPrompt(),
                        req.model(),
                        req.maxIters(),
                        req.toolsAllow(),
                        req.toolsDeny(),
                        req.identityName(),
                        req.identityEmoji(),
                        req.groupChatMentionPatterns(),
                        req.groupChatRequireMention(),
                        req.skillsAllow(),
                        req.skillsDeny(),
                        now,
                        now,
                        null, // shares — new agents start unshared
                        AgentDefinition.RUN_AS_INVOKER,
                        null,
                        workspacePath,
                        req.skillRepositories(),
                        req.sandboxMode(),
                        req.sandboxScope());
        store.save(userId, entry);
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
                WorkspaceScaffolder.scaffold(workspace, entry.name(), entry.sysPrompt());
            }
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

        return entry.toDefinition(userId);
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
        String sysPrompt =
                draft.sysPrompt() != null && !draft.sysPrompt().isBlank()
                        ? draft.sysPrompt()
                        : (entry.sysPrompt() != null
                                ? entry.sysPrompt()
                                : "You are a helpful assistant.");

        StringBuilder agentsMd = new StringBuilder();
        agentsMd.append("# ").append(displayName).append("\n\n");
        if (!description.isEmpty()) {
            agentsMd.append("> ").append(description.trim()).append("\n\n");
        }
        agentsMd.append(sysPrompt.trim()).append("\n");
        writeIfMissing(workspace.resolve("AGENTS.md"), agentsMd.toString());

        // tools.json
        if (draft.suggestedTools() != null && !draft.suggestedTools().isEmpty()) {
            StringBuilder tools = new StringBuilder();
            tools.append("{\n  \"allow\": [\n");
            for (int i = 0; i < draft.suggestedTools().size(); i++) {
                String t = draft.suggestedTools().get(i);
                if (t == null) continue;
                tools.append("    \"").append(escapeJson(t)).append("\"");
                if (i < draft.suggestedTools().size() - 1) tools.append(",");
                tools.append("\n");
            }
            tools.append("  ],\n  \"deny\": []\n}\n");
            writeIfMissing(workspace.resolve("tools.json"), tools.toString());
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

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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

        long now = System.currentTimeMillis();
        UserAgentDefinitionStore.StoredEntry updated =
                new UserAgentDefinitionStore.StoredEntry(
                        agentId,
                        req.name() != null ? req.name() : existing.name(),
                        req.description() != null ? req.description() : existing.description(),
                        req.sysPrompt() != null ? req.sysPrompt() : existing.sysPrompt(),
                        req.model() != null ? req.model() : existing.model(),
                        req.maxIters() != null ? req.maxIters() : existing.maxIters(),
                        req.toolsAllow() != null ? req.toolsAllow() : existing.toolsAllow(),
                        req.toolsDeny() != null ? req.toolsDeny() : existing.toolsDeny(),
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
                        req.skillsAllow() != null ? req.skillsAllow() : existing.skillsAllow(),
                        req.skillsDeny() != null ? req.skillsDeny() : existing.skillsDeny(),
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
                        req.sandboxScope() != null ? req.sandboxScope() : existing.sandboxScope());
        store.save(userId, updated);

        // Evict cached gateway registration so the next conversation picks up the new definition.
        registeredUcaIds.remove(ucaCacheKey(userId, agentId));

        log.info("User '{}' updated custom agent '{}'", userId, agentId);
        return updated.toDefinition(userId);
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
                        src.sysPrompt(),
                        src.model(),
                        src.maxIters(),
                        src.toolsAllow(),
                        src.toolsDeny(),
                        src.identityName(),
                        src.identityEmoji(),
                        src.groupChatMentionPatterns(),
                        src.groupChatRequireMention(),
                        src.skillsAllow(),
                        src.skillsDeny(),
                        now,
                        now,
                        null, // shares — clones start unshared
                        src.runAs(),
                        srcAgentId, // forkOf
                        id + WORKSPACE_DIR_SUFFIX, // workspacePath — clone uses its own id +
                        // suffix
                        src.skillRepositories(),
                        src.sandboxMode(),
                        src.sandboxScope());
        store.save(newOwnerId, clone);
        log.info(
                "User '{}' cloned agent '{}/{}' as '{}/{}'",
                newOwnerId,
                srcOwnerId,
                srcAgentId,
                newOwnerId,
                id);
        return new StoredEntryAndDefinition(clone, clone.toDefinition(newOwnerId));
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
        registeredUcaIds.remove(ucaCacheKey(userId, agentId));
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
        registeredUcaIds.remove(ucaCacheKey(ownerId, agentId));
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
        if (agentId == null) return null;
        if (isGlobal(agentId)) {
            return builderBootstrap.agents().get(agentId);
        }
        if (findVisible(userId, agentId).isEmpty()) {
            return null;
        }
        String ownerId = findOwnerOf(agentId).orElse(null);
        if (ownerId == null) return null;
        UserAgentDefinitionStore.StoredEntry entry =
                store.findById(ownerId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));
        String gatewayId =
                registeredUcaIds.computeIfAbsent(
                        ucaCacheKey(ownerId, agentId), k -> buildAndRegisterUca(ownerId, entry));
        return builderBootstrap.gateway().findAgent(gatewayId);
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
                ucaCacheKey(ownerId, agentId), k -> buildAndRegisterUca(ownerId, entry));
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

            // HarnessAgent does not expose a public getToolkit(); report standard built-in tools.
            List<String> toolNames =
                    List.of(
                            "filesystem",
                            "shell_execute",
                            "memory_search",
                            "memory_get",
                            "session_search");

            AgentConfigEntry.ToolsConfig tc = cfg != null ? cfg.getTools() : null;
            AgentConfigEntry.IdentityConfig ic = cfg != null ? cfg.getIdentity() : null;
            AgentConfigEntry.GroupChatConfig gc = cfg != null ? cfg.getGroupChat() : null;
            AgentConfigEntry.SkillsConfig sk = cfg != null ? cfg.getSkills() : null;

            result.add(
                    new AgentDefinition(
                            id,
                            name,
                            desc,
                            null, // don't expose sysPrompt in global catalog
                            cfg != null ? cfg.getModel() : null,
                            cfg != null ? cfg.getMaxIters() : null,
                            toolNames,
                            tc != null ? tc.getAllow() : null,
                            tc != null ? tc.getDeny() : null,
                            ic != null ? ic.getName() : null,
                            ic != null ? ic.getEmoji() : null,
                            gc != null ? gc.getMentionPatterns() : null,
                            gc != null ? gc.getRequireMention() : null,
                            sk != null ? sk.getAllow() : null,
                            sk != null ? sk.getDeny() : null,
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
                            null)); // tierForCurrentUser — populated by the controller
        }
        return result;
    }

    private List<AgentDefinition> userDefinitions(String userId) {
        return store.list(userId).stream().map(e -> e.toDefinition(userId)).toList();
    }

    private String buildAndRegisterUca(String userId, UserAgentDefinitionStore.StoredEntry entry) {
        String gatewayAgentId = UCA_PREFIX + userId + "-" + entry.id();

        Path workspace = userWorkspacePath(userId, entry);

        HarnessAgent.Builder b = HarnessAgent.builder();

        // Pin the stable namespace key to the gateway agentId (unique across users). The display
        // name (b.name) is human-facing and may change without rewriting any composite-filesystem
        // keys under [agents, <gatewayAgentId>, ...].
        b.agentId(gatewayAgentId);

        String name = entry.name() != null ? entry.name() : entry.id();
        b.name(name);

        if (entry.description() != null) {
            b.description(entry.description());
        }
        if (entry.sysPrompt() != null) {
            b.sysPrompt(entry.sysPrompt());
        }
        if (entry.maxIters() != null) {
            b.maxIters(entry.maxIters());
        }
        // Model: prefer per-agent override, fall back to bootstrap-level model.
        if (entry.model() != null && !entry.model().isBlank()) {
            b.model(entry.model());
        } else if (model != null) {
            b.model(model);
        }
        b.workspace(workspace);

        // Layered skill repositories: workspace overlay is implicit; explicit entries from the
        // user's saved definition are appended in order so earlier entries win on name clashes.
        if (entry.skillRepositories() != null && !entry.skillRepositories().isEmpty()) {
            var repos =
                    io.agentscope.builder.runtime.config.SkillRepositorySupport.createAll(
                            workspace, entry.skillRepositories());
            if (!repos.isEmpty()) {
                b.skillRepositories(repos);
            }
        }

        // Pre-populate this user-custom agent's toolkit with the outbound-send tool so the agent
        // can proactively push messages into any registered IM channel (subject to per-agent
        // tier ACL enforced at OutboundController + channel-routing check in OutboundService).
        io.agentscope.core.tool.Toolkit ucaToolkit = new io.agentscope.core.tool.Toolkit();
        ucaToolkit.registerTool(
                new io.agentscope.builder.runtime.outbound.OutboundTool(
                        builderBootstrap.channelManager()));
        b.toolkit(ucaToolkit);

        // Reuse the same cross-cutting configuration as startup-loaded agents. In particular,
        // this supplies the filesystem spec (including shared read prefixes), state store, and
        // middleware for the custom agent's own workspace.
        builderBootstrap.configureDynamicAgent(b);

        HarnessAgent agent = b.build();

        HarnessGateway gateway = builderBootstrap.gateway();
        gateway.registerAgent(gatewayAgentId, agent);
        // Record the gatewayId -> owner mapping so the gateway's fsUserIdResolver can pin
        // shared-agent chat reads to the owner's filesystem namespace. {@code userId} here is
        // the owner (see {@link #getOrInstantiateRunningAgent}).
        gatewayIdToOwner.put(gatewayAgentId, userId);

        log.info(
                "Registered user-custom agent in gateway: userId={}, agentId={}, gatewayId={}",
                userId,
                entry.id(),
                gatewayAgentId);

        return gatewayAgentId;
    }

    private static String ucaCacheKey(String userId, String agentId) {
        return userId + "/" + agentId;
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
            String sysPrompt,
            String model,
            Integer maxIters,
            List<String> toolsAllow,
            List<String> toolsDeny,
            String identityName,
            String identityEmoji,
            List<String> groupChatMentionPatterns,
            Boolean groupChatRequireMention,
            List<String> skillsAllow,
            List<String> skillsDeny,
            String workspacePath,
            String templateId,
            AgentDraft aiDraft,
            List<io.agentscope.builder.runtime.config.SkillRepositoryConfigEntry> skillRepositories,
            String sandboxMode,
            String sandboxScope) {}

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
