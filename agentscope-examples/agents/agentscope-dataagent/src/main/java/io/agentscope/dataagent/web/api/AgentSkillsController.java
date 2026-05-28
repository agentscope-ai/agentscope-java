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
package io.agentscope.dataagent.web.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.legacy.skill.AgentSkill;
import io.agentscope.core.legacy.skill.repository.AgentSkillRepository;
import io.agentscope.core.legacy.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.dataagent.runtime.marketplace.DataAgentMarketplace;
import io.agentscope.dataagent.runtime.marketplace.MarketSkillContent;
import io.agentscope.dataagent.runtime.marketplace.UserMarketplaceRegistry;
import io.agentscope.dataagent.web.audit.ActivityEvent;
import io.agentscope.dataagent.web.audit.AgentActivityStore;
import io.agentscope.dataagent.web.catalog.AgentCatalogService;
import io.agentscope.dataagent.web.catalog.AgentDefinition;
import io.agentscope.dataagent.web.catalog.UserAgentDefinitionStore;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Per-agent skills management for the platform. Mirrors claw's {@code AgentSkillsController} in
 * URL/payload shape, but every operation is gated by {@link AgentAccessGuard} (RUN to browse, EDIT
 * to mutate) and every file I/O goes through the per-(owner, agent) filesystem returned by
 * {@link WorkspaceManagerFactory#forAgent} — writes land in the per-user Docker sandbox under
 * {@code skills/}, isolated to the owning {@code (userId, agentId)}.
 *
 * <p>Cross-user guardrails:
 *
 * <ul>
 *   <li>The agent must be visible to the caller; otherwise 404 (never 403, to avoid leaking
 *       existence). EDIT-tier operations additionally require EDIT.
 *   <li>Marketplace-install uses {@link UserMarketplaceRegistry#find(String, String)} against the
 *       caller's userId — referring to another user's private marketplace id returns 404.
 *   <li>Owner-side writes use {@link AgentCatalogService#findOwnerOf} so a shared-in editor's
 *       changes are persisted in the original owner's namespace (the same namespace the running
 *       {@link HarnessAgent} reads from).
 * </ul>
 *
 * <p>Activity: every write records an {@link ActivityEvent} via {@link AgentActivityStore} keyed
 * to the actual owner. After every write, the UCA cache is evicted via
 * {@link AgentCatalogService#invalidateUca} so the next chat call rebuilds the agent with the
 * updated skill set.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/skills")
public class AgentSkillsController {

    private static final Logger log = LoggerFactory.getLogger(AgentSkillsController.class);

    private static final Pattern FRONT_MATTER =
            Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);
    private static final Pattern DESCRIPTION_LINE =
            Pattern.compile("^\\s*description\\s*:\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern NAME_LINE =
            Pattern.compile("^\\s*name\\s*:\\s*(.+?)\\s*$", Pattern.MULTILINE);

    static final String INSTALL_META_FILE = "_install.meta.json";
    static final String ORIGIN_CUSTOM = "custom";
    static final String ORIGIN_MARKETPLACE = "marketplace";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AgentAccessGuard guard;
    private final AgentActivityStore activity;
    private final AgentCatalogService catalogService;
    private final WorkspaceManagerFactory workspaceFactory;
    private final UserMarketplaceRegistry marketplaceRegistry;

    public AgentSkillsController(
            AgentAccessGuard guard,
            AgentActivityStore activity,
            AgentCatalogService catalogService,
            WorkspaceManagerFactory workspaceFactory,
            UserMarketplaceRegistry marketplaceRegistry) {
        this.guard = guard;
        this.activity = activity;
        this.catalogService = catalogService;
        this.workspaceFactory = workspaceFactory;
        this.marketplaceRegistry = marketplaceRegistry;
    }

    // -----------------------------------------------------------------
    //  Workspace skills (per-agent overlay under skills/)
    // -----------------------------------------------------------------

    @GetMapping("/workspace")
    public Mono<List<WorkspaceSkillInfo>> listWorkspaceSkills(
            @PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    AbstractFilesystem fs = resolveFilesystem(userId, agentId);
                    LsResult ls = fs.ls(null, "/skills");
                    if (ls == null || !ls.isSuccess() || ls.entries() == null) {
                        return List.<WorkspaceSkillInfo>of();
                    }
                    List<WorkspaceSkillInfo> out = new ArrayList<>();
                    for (FileInfo info : ls.entries()) {
                        if (!info.isDirectory()) continue;
                        String dirName = leafName(info.path());
                        if (dirName.isBlank()) continue;
                        WorkspaceSkillInfo skill = readWorkspaceSkill(fs, dirName);
                        if (skill != null) out.add(skill);
                    }
                    out.sort(Comparator.comparing(WorkspaceSkillInfo::name));
                    return out;
                });
    }

    @GetMapping("/workspace/{name}")
    public Mono<WorkspaceSkillDetail> getWorkspaceSkill(
            @PathVariable String agentId, @PathVariable String name, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    validateSkillName(name);
                    AbstractFilesystem fs = resolveFilesystem(userId, agentId);
                    String markdown = readUtf8(fs, "/skills/" + name + "/SKILL.md");
                    if (markdown == null) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "SKILL.md missing for: " + name);
                    }
                    Map<String, String> resources = collectResources(fs, name);
                    String description = parseFrontMatterField(markdown, DESCRIPTION_LINE);
                    return new WorkspaceSkillDetail(name, description, markdown, resources);
                });
    }

    @PutMapping("/workspace/{name}")
    public Mono<WorkspaceSkillInfo> upsertWorkspaceSkill(
            @PathVariable String agentId,
            @PathVariable String name,
            @RequestBody WorkspaceSkillUpsertRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    AgentDefinition def = guard.require(userId, agentId, Tier.EDIT);
                    validateSkillName(name);
                    if (req == null || req.markdown() == null || req.markdown().isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "markdown is required");
                    }
                    OwnerCtx ctx = resolveOwner(userId, agentId, def);
                    WorkspaceManager wsm = ctx.workspaceManager();
                    wsm.writeUtf8WorkspaceRelative(
                            RuntimeContext.empty(), "skills/" + name + "/SKILL.md", req.markdown());
                    if (req.resources() != null) {
                        for (Map.Entry<String, String> e : req.resources().entrySet()) {
                            String key = e.getKey();
                            if (key == null || key.isBlank()) continue;
                            String safe = sanitiseRelativePath(key);
                            wsm.writeUtf8WorkspaceRelative(
                                    RuntimeContext.empty(),
                                    "skills/" + name + "/" + safe,
                                    e.getValue() != null ? e.getValue() : "");
                        }
                    }
                    activity.record(
                            ctx.ownerId(),
                            agentId,
                            activity.actor(userId),
                            ActivityEvent.Action.EDIT_FILE,
                            "skills/" + name,
                            null);
                    catalogService.invalidateUca(ctx.ownerId(), agentId);
                    return readWorkspaceSkill(wsm.getFilesystem(), name);
                });
    }

    @DeleteMapping("/workspace/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteWorkspaceSkill(
            @PathVariable String agentId, @PathVariable String name, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(
                () -> {
                    AgentDefinition def = guard.require(userId, agentId, Tier.EDIT);
                    validateSkillName(name);
                    OwnerCtx ctx = resolveOwner(userId, agentId, def);
                    AbstractFilesystem fs = ctx.workspaceManager().getFilesystem();
                    if (!fs.exists(null, "/skills/" + name)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Skill not found: " + name);
                    }
                    fs.delete(null, "/skills/" + name);
                    activity.record(
                            ctx.ownerId(),
                            agentId,
                            activity.actor(userId),
                            ActivityEvent.Action.DELETE_FILE,
                            "skills/" + name,
                            null);
                    catalogService.invalidateUca(ctx.ownerId(), agentId);
                });
    }

    // -----------------------------------------------------------------
    //  Repositories bound to the running agent (browse + per-repo install)
    // -----------------------------------------------------------------

    @GetMapping("/repositories")
    public Mono<List<RepositoryInfo>> listRepositories(
            @PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    List<AgentSkillRepository> repos = repositoriesFor(userId, agentId);
                    List<RepositoryInfo> out = new ArrayList<>();
                    for (int i = 0; i < repos.size(); i++) {
                        out.add(toRepositoryInfo(i, repos.get(i)));
                    }
                    return out;
                });
    }

    @GetMapping("/repositories/{index}/skills")
    public Mono<List<MarketSkillInfo>> listRepositorySkills(
            @PathVariable String agentId, @PathVariable int index, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    AgentSkillRepository repo = repoAt(userId, agentId, index);
                    List<AgentSkill> skills;
                    try {
                        skills = repo.getAllSkills();
                    } catch (RuntimeException e) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_GATEWAY,
                                "Repository read failed: " + e.getMessage());
                    }
                    List<MarketSkillInfo> out = new ArrayList<>();
                    if (skills != null) {
                        for (AgentSkill s : skills) {
                            if (s == null) continue;
                            out.add(
                                    new MarketSkillInfo(
                                            s.getName(),
                                            s.getDescription(),
                                            s.getSource(),
                                            s.getResources() != null
                                                    ? s.getResources().size()
                                                    : 0));
                        }
                    }
                    out.sort(Comparator.comparing(MarketSkillInfo::name));
                    return out;
                });
    }

    @GetMapping("/repositories/{index}/skills/{name}")
    public Mono<MarketSkillDetail> getRepositorySkill(
            @PathVariable String agentId,
            @PathVariable int index,
            @PathVariable String name,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    validateSkillName(name);
                    AgentSkillRepository repo = repoAt(userId, agentId, index);
                    AgentSkill skill;
                    try {
                        skill = repo.getSkill(name);
                    } catch (RuntimeException e) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_GATEWAY,
                                "Repository read failed: " + e.getMessage());
                    }
                    if (skill == null) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Skill not found in repository: " + name);
                    }
                    return new MarketSkillDetail(
                            skill.getName(),
                            skill.getDescription(),
                            skill.getSource(),
                            skill.getSkillContent(),
                            skill.getResources() != null
                                    ? new LinkedHashMap<>(skill.getResources())
                                    : Map.of());
                });
    }

    @PostMapping("/workspace/install")
    public Mono<WorkspaceSkillInfo> installFromRepository(
            @PathVariable String agentId, @RequestBody InstallRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    if (req == null || req.skillName() == null || req.skillName().isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "skillName is required");
                    }
                    AgentDefinition def = guard.require(userId, agentId, Tier.EDIT);
                    validateSkillName(req.skillName());
                    AgentSkillRepository repo = repoAt(userId, agentId, req.repoIndex());
                    AgentSkill skill;
                    try {
                        skill = repo.getSkill(req.skillName());
                    } catch (RuntimeException e) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_GATEWAY,
                                "Repository read failed: " + e.getMessage());
                    }
                    if (skill == null) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Skill not found in repository: " + req.skillName());
                    }
                    String targetName =
                            (req.targetName() != null && !req.targetName().isBlank())
                                    ? req.targetName()
                                    : skill.getName();
                    validateSkillName(targetName);

                    OwnerCtx ctx = resolveOwner(userId, agentId, def);
                    AbstractFilesystem fs = ctx.workspaceManager().getFilesystem();
                    if (fs.exists(null, "/skills/" + targetName)
                            && !Boolean.TRUE.equals(req.overwrite())) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Workspace skill already exists: " + targetName);
                    }
                    if (fs.exists(null, "/skills/" + targetName)) {
                        fs.delete(null, "/skills/" + targetName);
                    }
                    String markdown = skill.getSkillContent();
                    if (markdown == null || markdown.isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_GATEWAY,
                                "Repository returned empty SKILL.md for: " + req.skillName());
                    }
                    WorkspaceManager wsm = ctx.workspaceManager();
                    wsm.writeUtf8WorkspaceRelative(
                            RuntimeContext.empty(), "skills/" + targetName + "/SKILL.md", markdown);
                    writeResources(wsm, targetName, skill.getResources());
                    AgentSkillRepositoryInfo repoInfo = repo.getRepositoryInfo();
                    SkillMarketplaceMeta meta =
                            new SkillMarketplaceMeta(
                                    repoInfo != null ? repoInfo.getType() : "unknown",
                                    repoInfo != null ? repoInfo.getLocation() : "",
                                    skill.getName(),
                                    Instant.now().toString());
                    writeInstallMeta(wsm, targetName, meta);
                    activity.record(
                            ctx.ownerId(),
                            agentId,
                            activity.actor(userId),
                            ActivityEvent.Action.CREATE_FILE,
                            "skills/" + targetName,
                            Map.of(
                                    "source", "repository",
                                    "repoType", meta.repoType(),
                                    "originalName", meta.originalName()));
                    catalogService.invalidateUca(ctx.ownerId(), agentId);
                    return readWorkspaceSkill(fs, targetName);
                });
    }

    @PostMapping("/workspace/marketplace-install")
    public Mono<WorkspaceSkillInfo> installFromMarketplace(
            @PathVariable String agentId,
            @RequestBody MarketplaceInstallRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    if (req == null
                            || req.marketplaceId() == null
                            || req.marketplaceId().isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "marketplaceId is required");
                    }
                    if (req.skillName() == null || req.skillName().isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "skillName is required");
                    }
                    AgentDefinition def = guard.require(userId, agentId, Tier.EDIT);
                    validateSkillName(req.skillName());
                    // 404 (not 403) for cross-user lookups so we don't leak the existence of
                    // another user's marketplace ids.
                    DataAgentMarketplace mp =
                            marketplaceRegistry
                                    .find(userId, req.marketplaceId())
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND,
                                                            "Marketplace not registered: "
                                                                    + req.marketplaceId()));
                    MarketSkillContent content;
                    try {
                        content = mp.fetch(req.skillName());
                    } catch (RuntimeException e) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_GATEWAY,
                                "Marketplace fetch failed: " + e.getMessage());
                    }
                    if (content == null) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND,
                                "Skill not found in marketplace: " + req.skillName());
                    }
                    String targetName =
                            (req.targetName() != null && !req.targetName().isBlank())
                                    ? req.targetName()
                                    : content.name();
                    validateSkillName(targetName);

                    OwnerCtx ctx = resolveOwner(userId, agentId, def);
                    AbstractFilesystem fs = ctx.workspaceManager().getFilesystem();
                    if (fs.exists(null, "/skills/" + targetName)
                            && !Boolean.TRUE.equals(req.overwrite())) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Workspace skill already exists: " + targetName);
                    }
                    if (fs.exists(null, "/skills/" + targetName)) {
                        fs.delete(null, "/skills/" + targetName);
                    }
                    if (content.markdown() == null || content.markdown().isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_GATEWAY,
                                "Marketplace returned empty SKILL.md for: " + req.skillName());
                    }
                    WorkspaceManager wsm = ctx.workspaceManager();
                    wsm.writeUtf8WorkspaceRelative(
                            RuntimeContext.empty(),
                            "skills/" + targetName + "/SKILL.md",
                            content.markdown());
                    writeResources(wsm, targetName, content.resources());
                    SkillMarketplaceMeta meta =
                            new SkillMarketplaceMeta(
                                    mp.type(),
                                    mp.displayLocation(),
                                    content.name(),
                                    Instant.now().toString());
                    writeInstallMeta(wsm, targetName, meta);
                    activity.record(
                            ctx.ownerId(),
                            agentId,
                            activity.actor(userId),
                            ActivityEvent.Action.CREATE_FILE,
                            "skills/" + targetName,
                            Map.of(
                                    "source", "marketplace",
                                    "marketplaceId", req.marketplaceId(),
                                    "marketplaceType", meta.repoType(),
                                    "originalName", meta.originalName()));
                    catalogService.invalidateUca(ctx.ownerId(), agentId);
                    return readWorkspaceSkill(fs, targetName);
                });
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private List<AgentSkillRepository> repositoriesFor(String userId, String agentId) {
        HarnessAgent agent = catalogService.getRunningAgent(userId, agentId);
        if (agent == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Agent not running: " + agentId);
        }
        List<AgentSkillRepository> repos = agent.getSkillRepositories();
        return repos != null ? repos : List.of();
    }

    private AgentSkillRepository repoAt(String userId, String agentId, int index) {
        List<AgentSkillRepository> repos = repositoriesFor(userId, agentId);
        if (index < 0 || index >= repos.size()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Repository index out of range: " + index);
        }
        return repos.get(index);
    }

    private static RepositoryInfo toRepositoryInfo(int index, AgentSkillRepository repo) {
        AgentSkillRepositoryInfo info = repo.getRepositoryInfo();
        String type = info != null ? info.getType() : repo.getClass().getSimpleName();
        String location = info != null ? info.getLocation() : "";
        boolean writable = info != null ? info.isWritable() : repo.isWriteable();
        return new RepositoryInfo(index, type, location, writable, repo.getSource());
    }

    private OwnerCtx resolveOwner(String userId, String agentId, AgentDefinition def) {
        if (catalogService.isGlobal(agentId)) {
            // Globals never reach the EDIT-tier code paths via the ACL (admin-managed), but be
            // defensive: route writes to the user's own namespace if somehow reached.
            return new OwnerCtx(userId, workspaceFactory.forGlobalAgent(userId, agentId));
        }
        String ownerId =
                def != null && def.ownerId() != null
                        ? def.ownerId()
                        : catalogService.findOwnerOf(agentId).orElse(userId);
        Optional<UserAgentDefinitionStore.StoredEntry> entry =
                catalogService.findStoredEntry(agentId);
        String workspacePath =
                entry.map(UserAgentDefinitionStore.StoredEntry::workspacePath).orElse(null);
        return new OwnerCtx(ownerId, workspaceFactory.forAgent(ownerId, agentId, workspacePath));
    }

    /** Read-only filesystem for browsing — RUN-tier callers, no owner mutation. */
    private AbstractFilesystem resolveFilesystem(String userId, String agentId) {
        if (catalogService.isGlobal(agentId)) {
            return workspaceFactory.forGlobalAgent(userId, agentId).getFilesystem();
        }
        String ownerId = catalogService.findOwnerOf(agentId).orElse(userId);
        Optional<UserAgentDefinitionStore.StoredEntry> entry =
                catalogService.findStoredEntry(agentId);
        String workspacePath =
                entry.map(UserAgentDefinitionStore.StoredEntry::workspacePath).orElse(null);
        return workspaceFactory.forAgent(ownerId, agentId, workspacePath).getFilesystem();
    }

    private static void writeResources(
            WorkspaceManager wsm, String targetName, Map<String, String> resources) {
        if (resources == null) return;
        for (Map.Entry<String, String> e : resources.entrySet()) {
            String rel = e.getKey();
            if (rel == null || rel.isBlank()) continue;
            String safe = sanitiseRelativePath(rel);
            String body = e.getValue() != null ? e.getValue() : "";
            wsm.writeUtf8WorkspaceRelative(
                    RuntimeContext.empty(), "skills/" + targetName + "/" + safe, body);
        }
    }

    private static void writeInstallMeta(
            WorkspaceManager wsm, String targetName, SkillMarketplaceMeta meta) {
        try {
            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(meta);
            wsm.writeUtf8WorkspaceRelative(
                    RuntimeContext.empty(), "skills/" + targetName + "/" + INSTALL_META_FILE, json);
        } catch (Exception e) {
            log.warn(
                    "Failed to write {} for {}: {}", INSTALL_META_FILE, targetName, e.getMessage());
        }
    }

    private static WorkspaceSkillInfo readWorkspaceSkill(AbstractFilesystem fs, String dirName) {
        String content = readUtf8(fs, "/skills/" + dirName + "/SKILL.md");
        if (content == null) return null;
        String description = parseFrontMatterField(content, DESCRIPTION_LINE);
        String name = parseFrontMatterField(content, NAME_LINE);
        if (name == null || name.isBlank()) {
            name = dirName;
        }
        SkillSize size = computeSize(fs, dirName);
        SkillMarketplaceMeta meta = readInstallMeta(fs, dirName);
        String origin = meta != null ? ORIGIN_MARKETPLACE : ORIGIN_CUSTOM;
        return new WorkspaceSkillInfo(
                dirName,
                name,
                description,
                size.totalBytes(),
                size.resourceCount(),
                fs.exists(null, "/skills/" + dirName + "/references"),
                fs.exists(null, "/skills/" + dirName + "/scripts"),
                origin,
                meta);
    }

    private static SkillMarketplaceMeta readInstallMeta(AbstractFilesystem fs, String dirName) {
        String json = readUtf8(fs, "/skills/" + dirName + "/" + INSTALL_META_FILE);
        if (json == null || json.isBlank()) return null;
        try {
            return MAPPER.readValue(json, SkillMarketplaceMeta.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, String> collectResources(AbstractFilesystem fs, String dirName) {
        Map<String, String> out = new LinkedHashMap<>();
        walk(
                fs,
                "/skills/" + dirName,
                "/skills/" + dirName + "/",
                (relativePath, absolutePath) -> {
                    if (relativePath.equals("SKILL.md") || relativePath.equals(INSTALL_META_FILE)) {
                        return;
                    }
                    String content = readUtf8(fs, absolutePath);
                    out.put(relativePath, content != null ? content : "");
                });
        return out;
    }

    private static SkillSize computeSize(AbstractFilesystem fs, String dirName) {
        long[] total = new long[] {0L};
        int[] count = new int[] {0};
        walk(
                fs,
                "/skills/" + dirName,
                "/skills/" + dirName + "/",
                (relativePath, absolutePath) -> {
                    if (relativePath.equals(INSTALL_META_FILE)) return;
                    total[0] += fileSize(fs, absolutePath);
                    if (!relativePath.equals("SKILL.md")) count[0]++;
                });
        return new SkillSize(total[0], count[0]);
    }

    @FunctionalInterface
    private interface FileVisitor {
        void visit(String relativePath, String absolutePath);
    }

    /**
     * Recursively walks {@code rootAbs} on the abstract filesystem, invoking {@code visitor} for
     * each regular file with the path relative to {@code rootAbs + "/"}. Tolerant of ls failures
     * (silently treated as empty).
     */
    private static void walk(
            AbstractFilesystem fs, String rootAbs, String relativeBase, FileVisitor visitor) {
        LsResult ls = fs.ls(null, rootAbs);
        if (ls == null || !ls.isSuccess() || ls.entries() == null) return;
        for (FileInfo info : ls.entries()) {
            String abs = info.path();
            String name = leafName(abs);
            if (name.isBlank()) continue;
            if (info.isDirectory()) {
                walk(fs, abs, relativeBase, visitor);
            } else {
                String rel =
                        abs.startsWith(relativeBase) ? abs.substring(relativeBase.length()) : name;
                visitor.visit(rel, abs);
            }
        }
    }

    private static long fileSize(AbstractFilesystem fs, String absolutePath) {
        // The cheap path — info.size() from ls — is already consumed by the caller's walk; we
        // re-stat via a parent ls to keep this function self-contained. One shell exec per call
        // in the sandbox-backed filesystem, used only on individual workspace skill directories.
        int slash = absolutePath.lastIndexOf('/');
        if (slash <= 0) return 0L;
        String parent = absolutePath.substring(0, slash);
        String name = absolutePath.substring(slash + 1);
        LsResult ls = fs.ls(null, parent);
        if (ls == null || !ls.isSuccess() || ls.entries() == null) return 0L;
        for (FileInfo info : ls.entries()) {
            if (leafName(info.path()).equals(name)) return info.size();
        }
        return 0L;
    }

    private static String readUtf8(AbstractFilesystem fs, String absolutePath) {
        ReadResult r = fs.read(null, absolutePath, 0, Integer.MAX_VALUE);
        if (r == null || !r.isSuccess() || r.fileData() == null) return null;
        return r.fileData().content();
    }

    private static String leafName(String absolutePath) {
        if (absolutePath == null) return "";
        int slash = absolutePath.lastIndexOf('/');
        return slash >= 0 ? absolutePath.substring(slash + 1) : absolutePath;
    }

    private static String parseFrontMatterField(String markdown, Pattern fieldPattern) {
        if (markdown == null) return null;
        Matcher m = FRONT_MATTER.matcher(markdown);
        if (!m.find()) return null;
        Matcher f = fieldPattern.matcher(m.group(1));
        if (!f.find()) return null;
        String value = f.group(1).trim();
        if ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'"))) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static void validateSkillName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Skill name is required");
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid skill name: " + name);
        }
    }

    private static String sanitiseRelativePath(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Resource path is required");
        }
        String normalized = relative.replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        if (normalized.isEmpty() || normalized.contains("..")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid resource path: " + relative);
        }
        return normalized;
    }

    /** (ownerId, workspaceManager) tuple — owner is whose namespace the writes land in. */
    private record OwnerCtx(String ownerId, WorkspaceManager workspaceManager) {}

    private record SkillSize(long totalBytes, int resourceCount) {}

    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkspaceSkillInfo(
            String dirName,
            String name,
            String description,
            long sizeBytes,
            int resourceCount,
            boolean hasReferences,
            boolean hasScripts,
            String origin,
            SkillMarketplaceMeta marketplace) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillMarketplaceMeta(
            String repoType, String repoLocation, String originalName, String installedAt) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkspaceSkillDetail(
            String name, String description, String markdown, Map<String, String> resources) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkspaceSkillUpsertRequest(String markdown, Map<String, String> resources) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RepositoryInfo(
            int index, String type, String location, boolean writable, String source) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketSkillInfo(
            String name, String description, String source, int resourceCount) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketSkillDetail(
            String name,
            String description,
            String source,
            String content,
            Map<String, String> resources) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InstallRequest(
            int repoIndex, String skillName, String targetName, Boolean overwrite) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MarketplaceInstallRequest(
            String marketplaceId, String skillName, String targetName, Boolean overwrite) {}
}
