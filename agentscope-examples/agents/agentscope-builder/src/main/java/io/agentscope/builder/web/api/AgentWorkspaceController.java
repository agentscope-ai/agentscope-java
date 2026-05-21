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
package io.agentscope.builder.web.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.agentscope.builder.runtime.BuilderBootstrap;
import io.agentscope.builder.web.audit.ActivityEvent;
import io.agentscope.builder.web.audit.AgentActivityStore;
import io.agentscope.builder.web.catalog.AgentCatalogService;
import io.agentscope.builder.web.catalog.AgentDefinition;
import io.agentscope.builder.web.share.AgentAccessGuard;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import io.agentscope.builder.web.workspace.WorkspaceManagerFactory;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * Generic workspace file CRUD for an agent.
 *
 * <ul>
 *   <li>{@code GET    /api/agents/{agentId}/workspace} — workspace summary
 *   <li>{@code POST   /api/agents/{agentId}/workspace/scaffold} — create skeleton dirs + AGENTS.md
 *   <li>{@code GET    /api/agents/{agentId}/workspace/memory} — MEMORY.md + per-day index
 *   <li>{@code GET    /api/agents/{agentId}/workspace/files?recursive=…} — file tree
 *   <li>{@code GET    /api/agents/{agentId}/workspace/file?path=…} — read raw file
 *   <li>{@code PUT    /api/agents/{agentId}/workspace/file?path=…} — write file (body
 *       {@code {content}})
 *   <li>{@code POST   /api/agents/{agentId}/workspace/file?path=…&type=file|dir} — create node
 *   <li>{@code POST   /api/agents/{agentId}/workspace/file/move} — rename/move (body
 *       {@code {from, to}})
 *   <li>{@code DELETE /api/agents/{agentId}/workspace/file?path=…} — delete file or directory
 *   <li>{@code POST   /api/agents/{agentId}/workspace/upload?path=…} — multipart upload
 * </ul>
 *
 * <p>All paths are relative to the agent's workspace root and validated to live within it.
 * Visibility is enforced via {@link AgentCatalogService#findVisible(String, String)}.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/workspace")
public class AgentWorkspaceController {

    private static final int MAX_FILE_SIZE = 512 * 1024;

    private final BuilderBootstrap builderBootstrap;
    private final AgentCatalogService catalogService;
    private final WorkspaceManagerFactory workspaceManagerFactory;
    private final AgentAccessGuard guard;
    private final AgentActivityStore activity;

    public AgentWorkspaceController(
            BuilderBootstrap builderBootstrap,
            AgentCatalogService catalogService,
            WorkspaceManagerFactory workspaceManagerFactory,
            AgentAccessGuard guard,
            AgentActivityStore activity) {
        this.builderBootstrap = builderBootstrap;
        this.catalogService = catalogService;
        this.workspaceManagerFactory = workspaceManagerFactory;
        this.guard = guard;
        this.activity = activity;
    }

    // -----------------------------------------------------------------
    //  Summary + scaffold
    // -----------------------------------------------------------------

    @GetMapping
    public Mono<WorkspaceSummary> summary(@PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    Path ws = resolveWorkspace(userId, agentId);
                    boolean exists = Files.isDirectory(ws);
                    int skillCount = countEntries(ws.resolve("skills"), true);
                    int subagentCount = countMdFiles(ws.resolve("subagents"));
                    int dailyMemoryCount = countMdFiles(ws.resolve("memory"));
                    boolean agentsMdExists = Files.isRegularFile(ws.resolve("AGENTS.md"));
                    boolean memoryMdExists = Files.isRegularFile(ws.resolve("MEMORY.md"));
                    return new WorkspaceSummary(
                            agentId,
                            ws.toAbsolutePath().toString(),
                            exists,
                            agentsMdExists,
                            memoryMdExists,
                            skillCount,
                            subagentCount,
                            dailyMemoryCount);
                });
    }

    @PostMapping("/scaffold")
    public Mono<WorkspaceSummary> scaffold(
            @PathVariable String agentId,
            @RequestParam(name = "name", defaultValue = "") String agentName,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.EDIT);
                    Path ws = resolveWorkspace(userId, agentId);
                    Files.createDirectories(ws.resolve("skills"));
                    Files.createDirectories(ws.resolve("subagents"));
                    Files.createDirectories(ws.resolve("memory"));
                    if (!Files.exists(ws.resolve("AGENTS.md"))) {
                        String displayName = agentName.isBlank() ? agentId : agentName;
                        writeAtomic(
                                ws.resolve("AGENTS.md"),
                                "# " + displayName + "\n\nYou are " + displayName + ".\n");
                    }
                    return summarize(agentId, ws);
                });
    }

    // -----------------------------------------------------------------
    //  Memory (read-only convenience view)
    // -----------------------------------------------------------------

    @GetMapping("/memory")
    public Mono<MemoryView> memory(@PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    Path ws = resolveWorkspace(userId, agentId);
                    Path memoryMd = ws.resolve("MEMORY.md");
                    String memoryContent =
                            Files.isRegularFile(memoryMd) ? readSafe(memoryMd) : null;
                    Path memoryDir = ws.resolve("memory");
                    List<DailyMemoryFile> dailyFiles = new ArrayList<>();
                    if (Files.isDirectory(memoryDir)) {
                        try (Stream<Path> stream = Files.list(memoryDir)) {
                            stream.filter(p -> p.getFileName().toString().endsWith(".md"))
                                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                                    .forEach(
                                            p ->
                                                    dailyFiles.add(
                                                            new DailyMemoryFile(
                                                                    p.getFileName().toString(),
                                                                    sizeOf(p))));
                        }
                    }
                    return new MemoryView(memoryContent, dailyFiles);
                });
    }

    // -----------------------------------------------------------------
    //  Generic file CRUD
    // -----------------------------------------------------------------

    @GetMapping("/files")
    public Mono<List<FileNode>> tree(
            @PathVariable String agentId,
            @RequestParam(name = "recursive", defaultValue = "true") boolean recursive,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    Path ws = resolveWorkspace(userId, agentId);
                    if (!Files.isDirectory(ws)) {
                        return List.of();
                    }
                    return collectChildren(ws, ws, recursive ? 6 : 1);
                });
    }

    @GetMapping("/file")
    public Mono<String> readFile(
            @PathVariable String agentId, @RequestParam("path") String path, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    Path target = guardPath(ctx.workspace().resolve(path), ctx.workspace());
                    if (!Files.isRegularFile(target)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "File not found: " + path);
                    }
                    if (ctx.manager() != null) {
                        long size = sizeOf(target);
                        if (size > MAX_FILE_SIZE) {
                            return "(file too large to display: " + size + " bytes)";
                        }
                        String rel = relativize(ctx.workspace(), target);
                        return ctx.manager().readManagedWorkspaceFileUtf8(rel);
                    }
                    return readSafe(target);
                });
    }

    @PutMapping("/file")
    public Mono<FileNode> writeFile(
            @PathVariable String agentId,
            @RequestParam("path") String path,
            @RequestBody WriteRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.EDIT);
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    Path target = guardPath(ctx.workspace().resolve(path), ctx.workspace());
                    if (Files.isDirectory(target)) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Path is a directory: " + path);
                    }
                    boolean existed = Files.isRegularFile(target);
                    String content = req != null && req.content() != null ? req.content() : "";
                    if (ctx.manager() != null) {
                        String rel = relativize(ctx.workspace(), target);
                        ctx.manager().writeUtf8WorkspaceRelative(rel, content);
                    } else {
                        Files.createDirectories(target.getParent());
                        writeAtomic(target, content);
                    }
                    if (ctx.ownerId() != null) {
                        activity.record(
                                ctx.ownerId(),
                                agentId,
                                activity.actor(userId),
                                existed
                                        ? ActivityEvent.Action.EDIT_FILE
                                        : ActivityEvent.Action.CREATE_FILE,
                                path,
                                null);
                    }
                    return toNode(ctx.workspace(), target);
                });
    }

    @PostMapping("/file")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<FileNode> createNode(
            @PathVariable String agentId,
            @RequestParam("path") String path,
            @RequestParam(name = "type", defaultValue = "file") String type,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.EDIT);
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    Path ws = ctx.workspace();
                    Path target = guardPath(ws.resolve(path), ws);
                    if (Files.exists(target)) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Already exists: " + path);
                    }
                    boolean isDir = "dir".equalsIgnoreCase(type);
                    if (isDir) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        writeAtomic(target, "");
                    }
                    if (ctx.ownerId() != null && !isDir) {
                        activity.record(
                                ctx.ownerId(),
                                agentId,
                                activity.actor(userId),
                                ActivityEvent.Action.CREATE_FILE,
                                path,
                                null);
                    }
                    return toNode(ws, target);
                });
    }

    @PostMapping("/file/move")
    public Mono<FileNode> moveNode(
            @PathVariable String agentId, @RequestBody MoveRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    if (req == null || req.from() == null || req.to() == null) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "from and to are required");
                    }
                    guard.require(userId, agentId, Tier.EDIT);
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    Path ws = ctx.workspace();
                    Path from = guardPath(ws.resolve(req.from()), ws);
                    Path to = guardPath(ws.resolve(req.to()), ws);
                    if (!Files.exists(from)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Source not found: " + req.from());
                    }
                    if (Files.exists(to)) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Target already exists: " + req.to());
                    }
                    Files.createDirectories(to.getParent());
                    try {
                        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
                    } catch (IOException atomicFailed) {
                        Files.move(from, to);
                    }
                    if (ctx.ownerId() != null) {
                        activity.record(
                                ctx.ownerId(),
                                agentId,
                                activity.actor(userId),
                                ActivityEvent.Action.RENAME_FILE,
                                req.to(),
                                Map.of("from", req.from()));
                    }
                    return toNode(ws, to);
                });
    }

    @DeleteMapping("/file")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteNode(
            @PathVariable String agentId, @RequestParam("path") String path, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(
                () -> {
                    guard.require(userId, agentId, Tier.EDIT);
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    Path ws = ctx.workspace();
                    Path target = guardPath(ws.resolve(path), ws);
                    if (!Files.exists(target)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Not found: " + path);
                    }
                    try {
                        if (Files.isDirectory(target)) {
                            deleteRecursive(target);
                        } else {
                            Files.delete(target);
                        }
                    } catch (IOException e) {
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "Delete failed: " + e.getMessage());
                    }
                    if (ctx.ownerId() != null) {
                        activity.record(
                                ctx.ownerId(),
                                agentId,
                                activity.actor(userId),
                                ActivityEvent.Action.DELETE_FILE,
                                path,
                                null);
                    }
                });
    }

    @PostMapping("/upload")
    public Mono<FileNode> upload(
            @PathVariable String agentId,
            @RequestParam("path") String path,
            @RequestPart("file") FilePart file,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                        () -> {
                            guard.require(userId, agentId, Tier.EDIT);
                            return resolveContext(userId, agentId);
                        })
                .flatMap(
                        ctx -> {
                            Path ws = ctx.workspace();
                            Path dir = guardPath(ws.resolve(path), ws);
                            try {
                                Files.createDirectories(dir);
                            } catch (IOException e) {
                                return Mono.error(
                                        new ResponseStatusException(
                                                HttpStatus.INTERNAL_SERVER_ERROR,
                                                "Failed to create dir: " + e.getMessage()));
                            }
                            String filename = sanitiseFilename(file.filename());
                            Path target = guardPath(dir.resolve(filename), ws);
                            return file.transferTo(target)
                                    .then(
                                            Mono.fromCallable(
                                                    () -> {
                                                        if (ctx.ownerId() != null) {
                                                            activity.record(
                                                                    ctx.ownerId(),
                                                                    agentId,
                                                                    activity.actor(userId),
                                                                    ActivityEvent.Action
                                                                            .UPLOAD_FILE,
                                                                    relativize(ws, target),
                                                                    null);
                                                        }
                                                        return toNode(ws, target);
                                                    }));
                        });
    }

    // -----------------------------------------------------------------
    //  Subagent CRUD
    // -----------------------------------------------------------------

    @GetMapping("/subagents")
    public Mono<List<SubagentInfo>> listSubagents(
            @PathVariable String agentId, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    Path ws = resolveWorkspace(userId, agentId);
                    Path subagentsDir = ws.resolve("subagents");
                    if (!Files.isDirectory(subagentsDir)) {
                        return List.<SubagentInfo>of();
                    }
                    List<SubagentInfo> result = new ArrayList<>();
                    try (DirectoryStream<Path> ds =
                            Files.newDirectoryStream(subagentsDir, "*.md")) {
                        for (Path file : ds) {
                            String markdown = readSafe(file);
                            String name = stripMdExtension(file.getFileName().toString());
                            SubagentDeclaration decl = AgentSpecLoader.parse(markdown, name, ws);
                            if (decl != null) {
                                result.add(toSubagentInfo(decl));
                            }
                        }
                    }
                    result.sort(Comparator.comparing(SubagentInfo::name));
                    return result;
                });
    }

    @PutMapping("/subagents/{name}")
    public Mono<SubagentInfo> upsertSubagent(
            @PathVariable String agentId,
            @PathVariable String name,
            @RequestBody SubagentUpsertRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.EDIT);
                    if (req == null || req.description() == null || req.description().isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "description is required");
                    }
                    validateSubagentName(name);
                    Path ws = resolveWorkspace(userId, agentId);
                    String markdown = renderSubagentMarkdown(req);
                    Path subagentsDir = ws.resolve("subagents");
                    Files.createDirectories(subagentsDir);
                    Path target = subagentsDir.resolve(name + ".md");
                    writeAtomic(target, markdown);
                    SubagentDeclaration decl = AgentSpecLoader.parse(markdown, name, ws);
                    if (decl == null) {
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "Generated markdown failed to parse");
                    }
                    return toSubagentInfo(decl);
                });
    }

    @PostMapping("/subagents/from-agent")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<SubagentInfo> createSubagentFromAgent(
            @PathVariable String agentId, @RequestBody FromAgentRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.EDIT);
                    if (req == null
                            || req.sourceAgentId() == null
                            || req.sourceAgentId().isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "sourceAgentId is required");
                    }
                    AgentDefinition source =
                            catalogService
                                    .findVisible(userId, req.sourceAgentId())
                                    .orElseThrow(
                                            () ->
                                                    new ResponseStatusException(
                                                            HttpStatus.NOT_FOUND,
                                                            "Source agent not found: "
                                                                    + req.sourceAgentId()));
                    String subName =
                            (req.name() != null && !req.name().isBlank())
                                    ? req.name()
                                    : req.sourceAgentId();
                    validateSubagentName(subName);

                    String description =
                            (source.description() != null && !source.description().isBlank())
                                    ? source.description()
                                    : source.name();
                    SubagentUpsertRequest upsert =
                            new SubagentUpsertRequest(
                                    description,
                                    source.model(),
                                    source.maxIters(),
                                    source.tools(),
                                    "shared",
                                    null,
                                    source.sysPrompt(),
                                    req.sourceAgentId());
                    String markdown = renderSubagentMarkdown(upsert);
                    Path ws = resolveWorkspace(userId, agentId);
                    Path subagentsDir = ws.resolve("subagents");
                    Files.createDirectories(subagentsDir);
                    writeAtomic(subagentsDir.resolve(subName + ".md"), markdown);
                    SubagentDeclaration decl = AgentSpecLoader.parse(markdown, subName, ws);
                    if (decl == null) {
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "Generated markdown failed to parse");
                    }
                    return toSubagentInfo(decl);
                });
    }

    @DeleteMapping("/subagents/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteSubagent(
            @PathVariable String agentId, @PathVariable String name, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(
                () -> {
                    guard.require(userId, agentId, Tier.EDIT);
                    validateSubagentName(name);
                    Path ws = resolveWorkspace(userId, agentId);
                    Path target = ws.resolve("subagents").resolve(name + ".md");
                    if (!Files.isRegularFile(target)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Subagent not found: " + name);
                    }
                    try {
                        Files.delete(target);
                    } catch (IOException e) {
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "Delete failed: " + e.getMessage());
                    }
                });
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    /**
     * Resolves the workspace root for a given agent, enforcing that the agent is visible to the
     * caller. For global agents the path comes from {@link BuilderBootstrap#resolveWorkspace};
     * for user-custom agents it is computed by {@link WorkspaceManagerFactory}, which is the
     * single source of truth for the per-tenant {@code users/{ownerId}/agents/{id}/} namespace.
     */
    private Path resolveWorkspace(String userId, String agentId) {
        return resolveContext(userId, agentId).workspace();
    }

    /**
     * Resolves the (workspace path, optional {@link WorkspaceManager}) tuple for an agent.
     *
     * <p>For SCOPE_USER agents the manager is populated and read/write endpoints route through it
     * so the eventual {@code fs-spec=sandbox|remote} deployments work without further controller
     * changes. For global agents the manager is {@code null} and callers fall back to NIO; globals
     * are read-only in practice (edits go through {@code agentscope.json}) and are not subject to
     * multi-tenant routing.
     */
    private WorkspaceContext resolveContext(String userId, String agentId) {
        AgentDefinition def =
                catalogService
                        .findVisible(userId, agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found or not accessible: " + agentId));
        if (AgentDefinition.SCOPE_USER.equals(def.scope())) {
            String ownerId = def.ownerId() != null ? def.ownerId() : userId;
            WorkspaceManager wm = workspaceManagerFactory.forAgent(ownerId, agentId);
            return new WorkspaceContext(wm.getWorkspace().normalize(), wm, ownerId);
        }
        return new WorkspaceContext(builderBootstrap.resolveWorkspace(agentId), null, null);
    }

    private static String relativize(Path workspace, Path target) {
        return workspace.relativize(target).toString().replace('\\', '/');
    }

    private record WorkspaceContext(Path workspace, WorkspaceManager manager, String ownerId) {}

    private static Path guardPath(Path target, Path workspace) {
        Path normalized = target.normalize();
        if (!normalized.startsWith(workspace.normalize())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path");
        }
        return normalized;
    }

    private static String sanitiseFilename(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing filename");
        }
        String trimmed = name.replace("\\", "/");
        int slash = trimmed.lastIndexOf('/');
        String basename = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        if (basename.isEmpty() || basename.equals(".") || basename.equals("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid filename");
        }
        return basename;
    }

    private static String readSafe(Path file) {
        try {
            long size = Files.size(file);
            if (size > MAX_FILE_SIZE) {
                return "(file too large to display: " + size + " bytes)";
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void writeAtomic(Path target, String content) {
        try {
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, content, StandardCharsets.UTF_8);
            Files.move(
                    tmp,
                    target,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Failed to write file: " + e.getMessage());
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

    private static int countEntries(Path dir, boolean dirOnly) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.list(dir)) {
            return (int) s.filter(p -> !dirOnly || Files.isDirectory(p)).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static int countMdFiles(Path dir) {
        if (!Files.isDirectory(dir)) return 0;
        try (Stream<Path> s = Files.list(dir)) {
            return (int) s.filter(p -> p.getFileName().toString().endsWith(".md")).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long sizeOf(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    /** Returns the immediate children of {@code dir} as {@link FileNode}s, optionally recursing. */
    private static List<FileNode> collectChildren(Path base, Path dir, int depth) {
        List<FileNode> out = new ArrayList<>();
        if (depth <= 0 || !Files.isDirectory(dir)) return out;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path entry : ds) {
                boolean isDir = Files.isDirectory(entry);
                String rel = base.relativize(entry).toString();
                String name = entry.getFileName().toString();
                if (isDir) {
                    List<FileNode> children = collectChildren(base, entry, depth - 1);
                    out.add(new FileNode(name, rel, "dir", null, children));
                } else {
                    out.add(new FileNode(name, rel, "file", sizeOf(entry), null));
                }
            }
        } catch (IOException e) {
            // skip unreadable
        }
        out.sort(
                Comparator.<FileNode, Integer>comparing(n -> "dir".equals(n.type()) ? 0 : 1)
                        .thenComparing(FileNode::name));
        return out;
    }

    private static FileNode toNode(Path workspace, Path target) {
        String rel = workspace.relativize(target).toString();
        boolean isDir = Files.isDirectory(target);
        return new FileNode(
                target.getFileName().toString(),
                rel,
                isDir ? "dir" : "file",
                isDir ? null : sizeOf(target),
                null);
    }

    private WorkspaceSummary summarize(String agentId, Path ws) {
        boolean exists = Files.isDirectory(ws);
        int skillCount = countEntries(ws.resolve("skills"), true);
        int subagentCount = countMdFiles(ws.resolve("subagents"));
        int dailyMemoryCount = countMdFiles(ws.resolve("memory"));
        boolean agentsMdExists = Files.isRegularFile(ws.resolve("AGENTS.md"));
        boolean memoryMdExists = Files.isRegularFile(ws.resolve("MEMORY.md"));
        return new WorkspaceSummary(
                agentId,
                ws.toAbsolutePath().toString(),
                exists,
                agentsMdExists,
                memoryMdExists,
                skillCount,
                subagentCount,
                dailyMemoryCount);
    }

    // -----------------------------------------------------------------
    //  Subagent helpers
    // -----------------------------------------------------------------

    private static SubagentInfo toSubagentInfo(SubagentDeclaration decl) {
        return new SubagentInfo(
                decl.getName(),
                decl.getDescription(),
                decl.getModel(),
                decl.getMaxIters() != 10 ? decl.getMaxIters() : null,
                decl.getTools().isEmpty() ? null : decl.getTools(),
                decl.getWorkspaceMode() == WorkspaceMode.SHARED ? "shared" : "isolated",
                decl.getWorkspacePath() != null ? decl.getWorkspacePath().toString() : null,
                decl.getInlineAgentsBody() != null && !decl.getInlineAgentsBody().isBlank(),
                null);
    }

    static String renderSubagentMarkdown(SubagentUpsertRequest req) {
        StringBuilder sb = new StringBuilder("---\n");
        sb.append("description: ").append(req.description().replace("\n", " ")).append("\n");
        if (req.workspaceMode() != null || req.workspacePath() != null) {
            sb.append("workspace:\n");
            sb.append("  mode: ")
                    .append(req.workspaceMode() != null ? req.workspaceMode() : "isolated")
                    .append("\n");
            if (req.workspacePath() != null && !req.workspacePath().isBlank()) {
                sb.append("  path: ").append(req.workspacePath()).append("\n");
            }
        }
        if (req.model() != null && !req.model().isBlank()) {
            sb.append("model: ").append(req.model()).append("\n");
        }
        if (req.maxIters() != null) {
            sb.append("maxIters: ").append(req.maxIters()).append("\n");
        }
        if (req.tools() != null && !req.tools().isEmpty()) {
            sb.append("tools: [").append(String.join(", ", req.tools())).append("]\n");
        }
        sb.append("---\n");
        if (req.inlineBody() != null && !req.inlineBody().isBlank()) {
            sb.append("\n").append(req.inlineBody().strip()).append("\n");
        }
        return sb.toString();
    }

    private static void validateSubagentName(String name) {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Subagent name is required");
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid subagent name: " + name);
        }
    }

    private static String stripMdExtension(String filename) {
        return filename.endsWith(".md") ? filename.substring(0, filename.length() - 3) : filename;
    }

    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FileNode(
            String name, String path, String type, Long size, List<FileNode> children) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WriteRequest(String content) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MoveRequest(String from, String to) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record WorkspaceSummary(
            String agentId,
            String workspacePath,
            boolean exists,
            boolean agentsMdExists,
            boolean memoryMdExists,
            int skillCount,
            int subagentCount,
            int dailyMemoryCount) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MemoryView(String memoryMd, List<DailyMemoryFile> dailyFiles) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DailyMemoryFile(String name, long sizeBytes) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubagentInfo(
            String name,
            String description,
            String model,
            Integer maxIters,
            List<String> tools,
            String workspaceMode,
            String workspacePath,
            boolean hasInlineBody,
            String sourceAgentId) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SubagentUpsertRequest(
            String description,
            String model,
            Integer maxIters,
            List<String> tools,
            String workspaceMode,
            String workspacePath,
            String inlineBody,
            String sourceAgentId) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FromAgentRequest(String sourceAgentId, String name) {}
}
