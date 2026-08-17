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
package io.agentscope.claw2.web.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.claw2.marketplace.ClawMarketplace;
import io.agentscope.claw2.marketplace.ClawMarketplaceRegistry;
import io.agentscope.claw2.marketplace.MarketSkillContent;
import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.web.catalog.AgentCatalogService;
import io.agentscope.claw2.web.catalog.UserAgentDefinitionStore;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.repository.AgentSkillRepository;
import io.agentscope.core.skill.repository.AgentSkillRepositoryInfo;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
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
 * Skills management endpoints for an agent. Exposes two layers:
 *
 * <ul>
 *   <li>{@code /workspace} — CRUD on {@code workspace/skills/<name>/} for the current agent
 *   <li>{@code /repositories} — read-only browse of all skill repositories bound to the agent
 *       (project-global, marketplace, workspace shared, per-user namespace), with one-click
 *       install into the workspace
 * </ul>
 */
@RestController
@RequestMapping("/api/agents/{agentId}/skills")
public class AgentSkillsController {

    private static final Pattern FRONT_MATTER =
            Pattern.compile("^---\\s*\\n(.*?)\\n---\\s*\\n", Pattern.DOTALL);
    private static final Pattern DESCRIPTION_LINE =
            Pattern.compile("^\\s*description\\s*:\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern NAME_LINE =
            Pattern.compile("^\\s*name\\s*:\\s*(.+?)\\s*$", Pattern.MULTILINE);

    /**
     * Sidecar file written into a skill directory when the skill was installed from a marketplace
     * repository. Allows the UI to distinguish self-authored skills from market-installed ones
     * without rescanning each repository on every list request.
     */
    static final String INSTALL_META_FILE = "_install.meta.json";

    static final String ORIGIN_CUSTOM = "custom";
    static final String ORIGIN_MARKETPLACE = "marketplace";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ClawBootstrap bootstrap;
    private final AgentCatalogService catalogService;
    private final ClawMarketplaceRegistry marketplaceRegistry;

    public AgentSkillsController(
            ClawBootstrap bootstrap,
            AgentCatalogService catalogService,
            ClawMarketplaceRegistry marketplaceRegistry) {
        this.bootstrap = bootstrap;
        this.catalogService = catalogService;
        this.marketplaceRegistry = marketplaceRegistry;
    }

    // -----------------------------------------------------------------
    //  Workspace skills
    // -----------------------------------------------------------------

    @GetMapping("/workspace")
    public Mono<List<WorkspaceSkillInfo>> listWorkspaceSkills(@PathVariable String agentId) {
        return Mono.fromCallable(
                () -> {
                    Path skillsDir = resolveWorkspace(agentId).resolve("skills");
                    if (!Files.isDirectory(skillsDir)) {
                        return List.<WorkspaceSkillInfo>of();
                    }
                    List<WorkspaceSkillInfo> out = new ArrayList<>();
                    try (DirectoryStream<Path> ds = Files.newDirectoryStream(skillsDir)) {
                        for (Path child : ds) {
                            if (!Files.isDirectory(child)) continue;
                            WorkspaceSkillInfo info = readWorkspaceSkill(child);
                            if (info != null) out.add(info);
                        }
                    }
                    out.sort(Comparator.comparing(WorkspaceSkillInfo::name));
                    return out;
                });
    }

    @GetMapping("/workspace/{name}")
    public Mono<WorkspaceSkillDetail> getWorkspaceSkill(
            @PathVariable String agentId, @PathVariable String name) {
        return Mono.fromCallable(
                () -> {
                    validateSkillName(name);
                    Path dir = resolveWorkspace(agentId).resolve("skills").resolve(name);
                    if (!Files.isDirectory(dir)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Skill not found: " + name);
                    }
                    Path md = dir.resolve("SKILL.md");
                    if (!Files.isRegularFile(md)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "SKILL.md missing for: " + name);
                    }
                    String markdown = Files.readString(md, StandardCharsets.UTF_8);
                    Map<String, String> resources = collectResources(dir);
                    String description = parseFrontMatterField(markdown, DESCRIPTION_LINE);
                    return new WorkspaceSkillDetail(name, description, markdown, resources);
                });
    }

    @PutMapping("/workspace/{name}")
    public Mono<WorkspaceSkillInfo> upsertWorkspaceSkill(
            @PathVariable String agentId,
            @PathVariable String name,
            @RequestBody WorkspaceSkillUpsertRequest req) {
        return Mono.fromCallable(
                () -> {
                    validateSkillName(name);
                    if (req == null || req.markdown() == null || req.markdown().isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "markdown is required");
                    }
                    return withWorkspaceContext(
                            agentId,
                            ctx -> {
                                String relMd = "skills/" + name + "/SKILL.md";
                                ctx.manager()
                                        .writeUtf8WorkspaceRelative(
                                                RuntimeContext.empty(), relMd, req.markdown());
                                if (req.resources() != null) {
                                    for (Map.Entry<String, String> e : req.resources().entrySet()) {
                                        String key = e.getKey();
                                        if (key == null || key.isBlank()) continue;
                                        String safe = sanitiseRelativePath(key);
                                        ctx.manager()
                                                .writeUtf8WorkspaceRelative(
                                                        RuntimeContext.empty(),
                                                        "skills/" + name + "/" + safe,
                                                        e.getValue() != null ? e.getValue() : "");
                                    }
                                }
                                Path dir = ctx.workspace().resolve("skills").resolve(name);
                                return readWorkspaceSkill(dir);
                            });
                });
    }

    @DeleteMapping("/workspace/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteWorkspaceSkill(
            @PathVariable String agentId, @PathVariable String name) {
        return Mono.fromRunnable(
                () -> {
                    validateSkillName(name);
                    Path dir = resolveWorkspace(agentId).resolve("skills").resolve(name);
                    if (!Files.isDirectory(dir)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Skill not found: " + name);
                    }
                    try {
                        deleteRecursive(dir);
                    } catch (IOException e) {
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "Delete failed: " + e.getMessage());
                    }
                });
    }

    // -----------------------------------------------------------------
    //  Repositories (marketplaces) bound to the agent
    // -----------------------------------------------------------------

    @GetMapping("/repositories")
    public Mono<List<RepositoryInfo>> listRepositories(@PathVariable String agentId) {
        return Mono.fromCallable(
                () -> {
                    List<AgentSkillRepository> repos = repositoriesFor(agentId);
                    List<RepositoryInfo> out = new ArrayList<>();
                    for (int i = 0; i < repos.size(); i++) {
                        out.add(toRepositoryInfo(i, repos.get(i)));
                    }
                    return out;
                });
    }

    @GetMapping("/repositories/{index}/skills")
    public Mono<List<MarketSkillInfo>> listRepositorySkills(
            @PathVariable String agentId, @PathVariable int index) {
        return Mono.fromCallable(
                () -> {
                    AgentSkillRepository repo = repoAt(agentId, index);
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
            @PathVariable String agentId, @PathVariable int index, @PathVariable String name) {
        return Mono.fromCallable(
                () -> {
                    validateSkillName(name);
                    AgentSkillRepository repo = repoAt(agentId, index);
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
            @PathVariable String agentId, @RequestBody InstallRequest req) {
        return Mono.fromCallable(
                () -> {
                    if (req == null || req.skillName() == null || req.skillName().isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "skillName is required");
                    }
                    validateSkillName(req.skillName());
                    AgentSkillRepository repo = repoAt(agentId, req.repoIndex());
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

                    return withWorkspaceContext(
                            agentId,
                            ctx -> {
                                Path target = ctx.workspace().resolve("skills").resolve(targetName);
                                if (Files.exists(target) && !Boolean.TRUE.equals(req.overwrite())) {
                                    throw new ResponseStatusException(
                                            HttpStatus.CONFLICT,
                                            "Workspace skill already exists: " + targetName);
                                }
                                if (Files.exists(target)) {
                                    try {
                                        deleteRecursive(target);
                                    } catch (IOException e) {
                                        throw new ResponseStatusException(
                                                HttpStatus.INTERNAL_SERVER_ERROR,
                                                "Failed to overwrite: " + e.getMessage());
                                    }
                                }
                                String markdown = skill.getSkillContent();
                                if (markdown == null || markdown.isBlank()) {
                                    throw new ResponseStatusException(
                                            HttpStatus.BAD_GATEWAY,
                                            "Repository returned empty SKILL.md for: "
                                                    + req.skillName());
                                }
                                ctx.manager()
                                        .writeUtf8WorkspaceRelative(
                                                RuntimeContext.empty(),
                                                "skills/" + targetName + "/SKILL.md",
                                                markdown);
                                Map<String, String> resources = skill.getResources();
                                if (resources != null) {
                                    for (Map.Entry<String, String> e : resources.entrySet()) {
                                        String rel = e.getKey();
                                        if (rel == null || rel.isBlank()) continue;
                                        String safe = sanitiseRelativePath(rel);
                                        String content = e.getValue() != null ? e.getValue() : "";
                                        ctx.manager()
                                                .writeUtf8WorkspaceRelative(
                                                        RuntimeContext.empty(),
                                                        "skills/" + targetName + "/" + safe,
                                                        content);
                                    }
                                }
                                AgentSkillRepositoryInfo repoInfo = repo.getRepositoryInfo();
                                SkillMarketplaceMeta meta =
                                        new SkillMarketplaceMeta(
                                                repoInfo != null ? repoInfo.getType() : "unknown",
                                                repoInfo != null ? repoInfo.getLocation() : "",
                                                skill.getName(),
                                                Instant.now().toString());
                                ctx.manager()
                                        .writeUtf8WorkspaceRelative(
                                                RuntimeContext.empty(),
                                                "skills/" + targetName + "/" + INSTALL_META_FILE,
                                                MAPPER.writerWithDefaultPrettyPrinter()
                                                        .writeValueAsString(meta));
                                return readWorkspaceSkill(target);
                            });
                });
    }

    @PostMapping("/workspace/marketplace-install")
    public Mono<WorkspaceSkillInfo> installFromMarketplace(
            @PathVariable String agentId, @RequestBody MarketplaceInstallRequest req) {
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
                    validateSkillName(req.skillName());
                    ClawMarketplace mp =
                            marketplaceRegistry
                                    .find(req.marketplaceId())
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

                    return withWorkspaceContext(
                            agentId,
                            ctx -> {
                                Path target = ctx.workspace().resolve("skills").resolve(targetName);
                                if (Files.exists(target) && !Boolean.TRUE.equals(req.overwrite())) {
                                    throw new ResponseStatusException(
                                            HttpStatus.CONFLICT,
                                            "Workspace skill already exists: " + targetName);
                                }
                                if (Files.exists(target)) {
                                    try {
                                        deleteRecursive(target);
                                    } catch (IOException e) {
                                        throw new ResponseStatusException(
                                                HttpStatus.INTERNAL_SERVER_ERROR,
                                                "Failed to overwrite: " + e.getMessage());
                                    }
                                }
                                if (content.markdown() == null || content.markdown().isBlank()) {
                                    throw new ResponseStatusException(
                                            HttpStatus.BAD_GATEWAY,
                                            "Marketplace returned empty SKILL.md for: "
                                                    + req.skillName());
                                }
                                ctx.manager()
                                        .writeUtf8WorkspaceRelative(
                                                RuntimeContext.empty(),
                                                "skills/" + targetName + "/SKILL.md",
                                                content.markdown());
                                Map<String, String> resources = content.resources();
                                if (resources != null) {
                                    for (Map.Entry<String, String> e : resources.entrySet()) {
                                        String rel = e.getKey();
                                        if (rel == null || rel.isBlank()) continue;
                                        String safe = sanitiseRelativePath(rel);
                                        String body = e.getValue() != null ? e.getValue() : "";
                                        ctx.manager()
                                                .writeUtf8WorkspaceRelative(
                                                        RuntimeContext.empty(),
                                                        "skills/" + targetName + "/" + safe,
                                                        body);
                                    }
                                }
                                SkillMarketplaceMeta meta =
                                        new SkillMarketplaceMeta(
                                                mp.type(),
                                                mp.displayLocation(),
                                                content.name(),
                                                Instant.now().toString());
                                ctx.manager()
                                        .writeUtf8WorkspaceRelative(
                                                RuntimeContext.empty(),
                                                "skills/" + targetName + "/" + INSTALL_META_FILE,
                                                MAPPER.writerWithDefaultPrettyPrinter()
                                                        .writeValueAsString(meta));
                                return readWorkspaceSkill(target);
                            });
                });
    }

    // -----------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------

    private List<AgentSkillRepository> repositoriesFor(String agentId) {
        HarnessAgent agent = bootstrap.agents().get(agentId);
        if (agent == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Agent not running: " + agentId);
        }
        List<AgentSkillRepository> repos = agent.getSkillRepositories();
        return repos != null ? repos : List.of();
    }

    private AgentSkillRepository repoAt(String agentId, int index) {
        List<AgentSkillRepository> repos = repositoriesFor(agentId);
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

    private static WorkspaceSkillInfo readWorkspaceSkill(Path dir) throws IOException {
        Path md = dir.resolve("SKILL.md");
        if (!Files.isRegularFile(md)) {
            return null;
        }
        String content = Files.readString(md, StandardCharsets.UTF_8);
        String description = parseFrontMatterField(content, DESCRIPTION_LINE);
        String name = parseFrontMatterField(content, NAME_LINE);
        if (name == null || name.isBlank()) {
            name = dir.getFileName().toString();
        }
        long size = sizeOfDir(dir);
        boolean hasReferences = Files.isDirectory(dir.resolve("references"));
        boolean hasScripts = Files.isDirectory(dir.resolve("scripts"));
        int resourceCount = countResources(dir);
        SkillMarketplaceMeta meta = readInstallMeta(dir);
        String origin = meta != null ? ORIGIN_MARKETPLACE : ORIGIN_CUSTOM;
        return new WorkspaceSkillInfo(
                dir.getFileName().toString(),
                name,
                description,
                size,
                resourceCount,
                hasReferences,
                hasScripts,
                origin,
                meta);
    }

    private static SkillMarketplaceMeta readInstallMeta(Path skillDir) {
        Path metaPath = skillDir.resolve(INSTALL_META_FILE);
        if (!Files.isRegularFile(metaPath)) {
            return null;
        }
        try {
            String json = Files.readString(metaPath, StandardCharsets.UTF_8);
            return MAPPER.readValue(json, SkillMarketplaceMeta.class);
        } catch (IOException e) {
            // Treat unreadable / malformed sidecar as missing — surface as custom origin rather
            // than fail the whole listing.
            return null;
        }
    }

    private static boolean isInternalSidecar(Path file) {
        String name = file.getFileName().toString();
        return INSTALL_META_FILE.equals(name);
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

    private static Map<String, String> collectResources(Path skillDir) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(skillDir)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().equals("SKILL.md"))
                    .filter(p -> !isInternalSidecar(p))
                    .sorted()
                    .forEach(
                            p -> {
                                String rel = skillDir.relativize(p).toString().replace('\\', '/');
                                try {
                                    out.put(rel, Files.readString(p, StandardCharsets.UTF_8));
                                } catch (IOException ex) {
                                    out.put(rel, "(unreadable: " + ex.getMessage() + ")");
                                }
                            });
        }
        return out;
    }

    private static int countResources(Path skillDir) {
        try (Stream<Path> walk = Files.walk(skillDir)) {
            return (int)
                    walk.filter(Files::isRegularFile)
                            .filter(p -> !p.getFileName().toString().equals("SKILL.md"))
                            .filter(p -> !isInternalSidecar(p))
                            .count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long sizeOfDir(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> !isInternalSidecar(p))
                    .mapToLong(
                            p -> {
                                try {
                                    return Files.size(p);
                                } catch (IOException e) {
                                    return 0L;
                                }
                            })
                    .sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.delete(p);
                                } catch (IOException ex) {
                                    throw new RuntimeException(ex);
                                }
                            });
        }
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

    private Path resolveWorkspace(String agentId) {
        return resolveWorkspacePath(agentId);
    }

    private Path resolveWorkspacePath(String agentId) {
        if (catalogService.isBuiltin(agentId)) {
            return bootstrap.resolveWorkspace(agentId).normalize();
        }
        UserAgentDefinitionStore.StoredEntry entry =
                catalogService
                        .findStoredEntry(agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));
        return customAgentWorkspace(entry);
    }

    private WorkspaceContext resolveContext(String agentId) {
        Path ws = resolveWorkspacePath(agentId);
        return new WorkspaceContext(ws, newWorkspaceManager(ws));
    }

    /**
     * Runs {@code action} with a short-lived {@link WorkspaceManager} and closes its SQLite index
     * afterward. Required on Windows where an open {@code .index/workspace.db} handle prevents
     * {@code @TempDir} cleanup in unit tests.
     */
    private <T> T withWorkspaceContext(String agentId, WorkspaceAction<T> action) throws Exception {
        WorkspaceContext ctx = resolveContext(agentId);
        try {
            return action.run(ctx);
        } finally {
            ctx.manager().close();
        }
    }

    @FunctionalInterface
    private interface WorkspaceAction<T> {
        T run(WorkspaceContext ctx) throws Exception;
    }

    private Path customAgentWorkspace(UserAgentDefinitionStore.StoredEntry entry) {
        Path clawHome = bootstrap.clawHome();
        if (entry.workspacePath() != null && !entry.workspacePath().isBlank()) {
            Path p = Paths.get(entry.workspacePath());
            return p.isAbsolute() ? p.normalize() : clawHome.resolve(p).normalize();
        }
        return ClawBootstrap.defaultAgentWorkspace(clawHome, entry.id());
    }

    // Workspace filesystem is constructed in {@code virtualMode=true} so leading-slash paths
    // (the {@link AbstractFilesystem} contract used by {@code /skills} listings) resolve to the
    // workspace root rather than the host filesystem root. Mirrors
    // {@link AgentWorkspaceController#newWorkspaceManager}.
    private static WorkspaceManager newWorkspaceManager(Path workspace) {
        return new WorkspaceManager(workspace, new LocalFilesystem(workspace, true, 10, null));
    }

    private record WorkspaceContext(Path workspace, WorkspaceManager manager) {}

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

    /**
     * Provenance for a workspace skill that was copied in from a marketplace repository. Stored
     * as {@code _install.meta.json} alongside the skill's {@code SKILL.md} so the install source
     * survives across server restarts. Persists across edits — editing a market-installed skill
     * doesn't detach it.
     */
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
