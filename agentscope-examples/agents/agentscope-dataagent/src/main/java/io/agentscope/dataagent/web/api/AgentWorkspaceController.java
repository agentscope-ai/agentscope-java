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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.dataagent.runtime.DataAgentBootstrap;
import io.agentscope.dataagent.web.audit.ActivityEvent;
import io.agentscope.dataagent.web.audit.AgentActivityStore;
import io.agentscope.dataagent.web.catalog.AgentCatalogService;
import io.agentscope.dataagent.web.catalog.AgentDefinition;
import io.agentscope.dataagent.web.share.AgentAccessGuard;
import io.agentscope.dataagent.web.share.AgentAclService.Tier;
import io.agentscope.dataagent.web.workspace.WorkspaceManagerFactory;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.buffer.DataBufferUtils;
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

    private final DataAgentBootstrap builderBootstrap;
    private final AgentCatalogService catalogService;
    private final WorkspaceManagerFactory workspaceManagerFactory;
    private final AgentAccessGuard guard;
    private final AgentActivityStore activity;

    public AgentWorkspaceController(
            DataAgentBootstrap builderBootstrap,
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
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    return summarize(agentId, ctx);
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
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    AbstractFilesystem fs = ctx.manager().getFilesystem();
                    RuntimeContext rc = RuntimeContext.empty();
                    // skills/, subagents/, memory/ are virtual via composite routes — no mkdir
                    // needed. Only AGENTS.md needs materialisation, and only if missing.
                    if (!fs.exists(rc, "AGENTS.md")) {
                        String displayName = agentName.isBlank() ? agentId : agentName;
                        ctx.manager()
                                .writeUtf8WorkspaceRelative(
                                        rc,
                                        "AGENTS.md",
                                        "# " + displayName + "\n\nYou are " + displayName + ".\n");
                    }
                    return summarize(agentId, ctx);
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
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    AbstractFilesystem fs = ctx.manager().getFilesystem();
                    RuntimeContext rc = RuntimeContext.empty();
                    String memoryContent = null;
                    if (fs.exists(rc, "MEMORY.md")) {
                        ReadResult rr = fs.read(rc, "MEMORY.md", 0, 50000);
                        if (rr.isSuccess()) {
                            memoryContent = rr.fileData().content();
                        }
                    }
                    List<DailyMemoryFile> dailyFiles = new ArrayList<>();
                    LsResult ls = fs.ls(rc, "/memory");
                    if (ls.isSuccess() && ls.entries() != null) {
                        ls.entries().stream()
                                .filter(fi -> !fi.isDirectory() && fi.path().endsWith(".md"))
                                .sorted(Comparator.comparing(FileInfo::path).reversed())
                                .forEach(
                                        fi ->
                                                dailyFiles.add(
                                                        new DailyMemoryFile(
                                                                fileName(fi.path()), fi.size())));
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
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    AbstractFilesystem fs = ctx.manager().getFilesystem();
                    return collectChildrenFs(fs, "/", recursive ? 6 : 1);
                });
    }

    @GetMapping("/file")
    public Mono<String> readFile(
            @PathVariable String agentId, @RequestParam("path") String path, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    String rel = validateRelPath(path);
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    AbstractFilesystem fs = ctx.manager().getFilesystem();
                    RuntimeContext rc = RuntimeContext.empty();
                    if (!fs.exists(rc, rel)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "File not found: " + path);
                    }
                    ReadResult rr = fs.read(rc, rel, 0, 0);
                    if (!rr.isSuccess()) {
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR, "Read failed: " + rr.error());
                    }
                    String content =
                            rr.fileData() != null && rr.fileData().content() != null
                                    ? rr.fileData().content()
                                    : "";
                    if (content.length() > MAX_FILE_SIZE) {
                        return "(file too large to display: " + content.length() + " bytes)";
                    }
                    return content;
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
                    String rel = validateRelPath(path);
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    AbstractFilesystem fs = ctx.manager().getFilesystem();
                    RuntimeContext rc = RuntimeContext.empty();
                    if (isDirectoryEntry(fs, rc, rel)) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Path is a directory: " + path);
                    }
                    boolean existed = fs.exists(rc, rel);
                    String content = req != null && req.content() != null ? req.content() : "";
                    ctx.manager().writeUtf8WorkspaceRelative(rc, rel, content);
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
                    return fileNode(
                            rel, false, (long) content.getBytes(StandardCharsets.UTF_8).length);
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
                    String rel = validateRelPath(path);
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    AbstractFilesystem fs = ctx.manager().getFilesystem();
                    RuntimeContext rc = RuntimeContext.empty();
                    boolean isDir = "dir".equalsIgnoreCase(type);
                    String materialised = isDir ? rel + "/.keep" : rel;
                    if (fs.exists(rc, materialised)) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Already exists: " + path);
                    }
                    ctx.manager().writeUtf8WorkspaceRelative(rc, materialised, "");
                    if (ctx.ownerId() != null && !isDir) {
                        activity.record(
                                ctx.ownerId(),
                                agentId,
                                activity.actor(userId),
                                ActivityEvent.Action.CREATE_FILE,
                                path,
                                null);
                    }
                    return fileNode(rel, isDir, isDir ? null : 0L);
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
                    String fromRel = validateRelPath(req.from());
                    String toRel = validateRelPath(req.to());
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    AbstractFilesystem fs = ctx.manager().getFilesystem();
                    RuntimeContext rc = RuntimeContext.empty();
                    if (!fs.exists(rc, fromRel)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Source not found: " + req.from());
                    }
                    if (fs.exists(rc, toRel)) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Target already exists: " + req.to());
                    }
                    WriteResult mv = fs.move(rc, fromRel, toRel);
                    if (!mv.isSuccess()) {
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR, "Move failed: " + mv.error());
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
                    return fileNode(toRel, isDirectoryEntry(fs, rc, toRel), null);
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
                    String rel = validateRelPath(path);
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    AbstractFilesystem fs = ctx.manager().getFilesystem();
                    RuntimeContext rc = RuntimeContext.empty();
                    if (!fs.exists(rc, rel)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Not found: " + path);
                    }
                    WriteResult wr = fs.delete(rc, rel);
                    if (!wr.isSuccess()) {
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR, "Delete failed: " + wr.error());
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
                            String dirRel;
                            try {
                                dirRel = validateRelPath(path);
                            } catch (ResponseStatusException ex) {
                                return Mono.error(ex);
                            }
                            String filename = sanitiseFilename(file.filename());
                            String targetRel = (dirRel.isEmpty() ? "" : dirRel + "/") + filename;
                            return DataBufferUtils.join(file.content())
                                    .map(
                                            db -> {
                                                byte[] bytes = new byte[db.readableByteCount()];
                                                db.read(bytes);
                                                DataBufferUtils.release(db);
                                                return bytes;
                                            })
                                    .map(
                                            bytes -> {
                                                AbstractFilesystem fs =
                                                        ctx.manager().getFilesystem();
                                                List<FileUploadResponse> resp =
                                                        fs.uploadFiles(
                                                                RuntimeContext.empty(),
                                                                List.of(
                                                                        Map.entry(
                                                                                targetRel, bytes)));
                                                if (!resp.isEmpty()
                                                        && resp.get(0).error() != null) {
                                                    throw new ResponseStatusException(
                                                            HttpStatus.INTERNAL_SERVER_ERROR,
                                                            "Upload failed: "
                                                                    + resp.get(0).error());
                                                }
                                                if (ctx.ownerId() != null) {
                                                    activity.record(
                                                            ctx.ownerId(),
                                                            agentId,
                                                            activity.actor(userId),
                                                            ActivityEvent.Action.UPLOAD_FILE,
                                                            targetRel,
                                                            null);
                                                }
                                                return fileNode(
                                                        targetRel, false, (long) bytes.length);
                                            });
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
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    AbstractFilesystem fs = ctx.manager().getFilesystem();
                    RuntimeContext rc = RuntimeContext.empty();
                    LsResult ls = fs.ls(rc, "/subagents");
                    if (!ls.isSuccess() || ls.entries() == null) {
                        return List.<SubagentInfo>of();
                    }
                    List<SubagentInfo> result = new ArrayList<>();
                    for (FileInfo fi : ls.entries()) {
                        String entryPath = fi.path();
                        if (fi.isDirectory() || !entryPath.endsWith(".md")) {
                            continue;
                        }
                        ReadResult rr = fs.read(rc, "subagents/" + fileName(entryPath), 0, 50000);
                        if (!rr.isSuccess()) {
                            continue;
                        }
                        String markdown = rr.fileData().content();
                        String name = stripMdExtension(fileName(entryPath));
                        SubagentDeclaration decl =
                                AgentSpecLoader.parse(markdown, name, ctx.workspace());
                        if (decl != null) {
                            result.add(toSubagentInfo(decl));
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
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    String markdown = renderSubagentMarkdown(req);
                    ctx.manager()
                            .writeUtf8WorkspaceRelative(
                                    RuntimeContext.empty(), "subagents/" + name + ".md", markdown);
                    SubagentDeclaration decl =
                            AgentSpecLoader.parse(markdown, name, ctx.workspace());
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
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    ctx.manager()
                            .writeUtf8WorkspaceRelative(
                                    RuntimeContext.empty(),
                                    "subagents/" + subName + ".md",
                                    markdown);
                    SubagentDeclaration decl =
                            AgentSpecLoader.parse(markdown, subName, ctx.workspace());
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
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    AbstractFilesystem fs = ctx.manager().getFilesystem();
                    String path = "subagents/" + name + ".md";
                    if (!fs.exists(RuntimeContext.empty(), path)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Subagent not found: " + name);
                    }
                    WriteResult wr = fs.delete(RuntimeContext.empty(), path);
                    if (!wr.isSuccess()) {
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR, "Delete failed: " + wr.error());
                    }
                });
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    /**
     * Resolves the (workspace path, {@link WorkspaceManager}) tuple for an agent.
     *
     * <p>Both SCOPE_USER and global agents route through a {@link WorkspaceManager} backed by the
     * per-{@code (userId, agentId)} Docker sandbox supplied by {@link WorkspaceManagerFactory}.
     * Shared seed content (AGENTS.md, skills/, subagents/, knowledge/) is read-only-projected into
     * every sandbox at start; user writes stay inside the container.
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
            WorkspaceManager wm =
                    workspaceManagerFactory.forAgent(ownerId, agentId, def.workspacePath());
            return new WorkspaceContext(wm.getWorkspace().normalize(), wm, ownerId);
        }
        WorkspaceManager wm =
                workspaceManagerFactory.forGlobalAgent(userId, agentId, def.workspacePath());
        return new WorkspaceContext(wm.getWorkspace().normalize(), wm, userId);
    }

    private record WorkspaceContext(Path workspace, WorkspaceManager manager, String ownerId) {}

    /**
     * Validates a caller-supplied workspace-relative path. Rejects null/blank, absolute paths,
     * and any segment equal to {@code ".."} or starting with {@code "."}. Returns the trimmed
     * value with backslashes normalised to forward slashes.
     */
    private static String validateRelPath(String path) {
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
        }
        String trimmed = path.trim().replace('\\', '/');
        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path");
        }
        for (String segment : trimmed.split("/")) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid path: " + path);
            }
        }
        return trimmed;
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

    /**
     * Recursively walks the composite filesystem starting at {@code absPath} (an absolute path
     * understood by the filesystem — {@code "/"} for root, {@code "/memory"} for a subdir),
     * building {@link FileNode}s with workspace-relative paths suitable for the public API.
     *
     * <p>Entries are de-duplicated by relative path: the composite root listing may surface both
     * a routed virtual directory ({@code /memory/}) and a same-named entry from the default
     * store, in which case the routed entry wins.
     */
    private static List<FileNode> collectChildrenFs(
            AbstractFilesystem fs, String absPath, int depth) {
        List<FileNode> out = new ArrayList<>();
        if (depth <= 0) {
            return out;
        }
        LsResult ls = fs.ls(RuntimeContext.empty(), absPath);
        if (!ls.isSuccess() || ls.entries() == null) {
            return out;
        }
        java.util.LinkedHashMap<String, FileNode> bySeg = new java.util.LinkedHashMap<>();
        String prefix = "/".equals(absPath) ? "" : trimTrailingSlash(absPath) + "/";
        for (FileInfo fi : ls.entries()) {
            String basename = basenameFromFiPath(fi.path());
            if (basename.isEmpty() || basename.equals(".") || basename.equals("..")) {
                continue;
            }
            String rel =
                    prefix.isEmpty()
                            ? basename
                            : (prefix.startsWith("/")
                                    ? prefix.substring(1) + basename
                                    : prefix + basename);
            if (fi.isDirectory()) {
                String childAbs = "/" + rel;
                List<FileNode> children = collectChildrenFs(fs, childAbs, depth - 1);
                bySeg.put(basename, new FileNode(basename, rel, "dir", null, children));
            } else {
                if (!bySeg.containsKey(basename)) {
                    bySeg.put(basename, new FileNode(basename, rel, "file", fi.size(), null));
                }
            }
        }
        out.addAll(bySeg.values());
        out.sort(
                Comparator.<FileNode, Integer>comparing(n -> "dir".equals(n.type()) ? 0 : 1)
                        .thenComparing(FileNode::name));
        return out;
    }

    /**
     * Returns whether {@code relPath} exists in {@code fs} as a directory entry. Implemented by
     * listing the parent directory and matching basenames — there is no dedicated
     * {@code isDirectory} on {@link AbstractFilesystem}.
     */
    private static boolean isDirectoryEntry(
            AbstractFilesystem fs, RuntimeContext rc, String relPath) {
        if (relPath == null || relPath.isEmpty()) {
            return true;
        }
        int slash = relPath.lastIndexOf('/');
        String parent = slash > 0 ? "/" + relPath.substring(0, slash) : "/";
        String base = slash >= 0 ? relPath.substring(slash + 1) : relPath;
        LsResult ls = fs.ls(rc, parent);
        if (!ls.isSuccess() || ls.entries() == null) {
            return false;
        }
        for (FileInfo fi : ls.entries()) {
            if (basenameFromFiPath(fi.path()).equals(base)) {
                return fi.isDirectory();
            }
        }
        return false;
    }

    private static FileNode fileNode(String rel, boolean isDir, Long size) {
        int slash = rel.lastIndexOf('/');
        String name = slash >= 0 ? rel.substring(slash + 1) : rel;
        return new FileNode(name, rel, isDir ? "dir" : "file", isDir ? null : size, null);
    }

    private static String basenameFromFiPath(String fiPath) {
        if (fiPath == null) return "";
        String s = trimTrailingSlash(fiPath);
        int slash = s.lastIndexOf('/');
        return slash >= 0 ? s.substring(slash + 1) : s;
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) return "";
        int end = s.length();
        while (end > 1 && s.charAt(end - 1) == '/') {
            end--;
        }
        return s.substring(0, end);
    }

    /**
     * Computes the summary via the composite filesystem so that user-isolated routed content
     * (MEMORY.md, memory/, sessions/, skills/, subagents/) is correctly reflected — disk-only
     * probes would miss everything stored in the {@link BaseStore}.
     */
    private WorkspaceSummary summarize(String agentId, WorkspaceContext ctx) {
        AbstractFilesystem fs = ctx.manager().getFilesystem();
        RuntimeContext rc = RuntimeContext.empty();
        boolean agentsMdExists = fs.exists(rc, "AGENTS.md");
        boolean memoryMdExists = fs.exists(rc, "MEMORY.md");
        int skillCount = countDirChildren(fs, rc, "/skills", true);
        int subagentCount = countMdLeafFiles(fs, rc, "/subagents");
        int dailyMemoryCount = countMdLeafFiles(fs, rc, "/memory");
        return new WorkspaceSummary(
                agentId,
                ctx.workspace().toAbsolutePath().toString(),
                true,
                agentsMdExists,
                memoryMdExists,
                skillCount,
                subagentCount,
                dailyMemoryCount);
    }

    private static int countDirChildren(
            AbstractFilesystem fs, RuntimeContext rc, String absPath, boolean dirOnly) {
        LsResult ls = fs.ls(rc, absPath);
        if (!ls.isSuccess() || ls.entries() == null) {
            return 0;
        }
        int n = 0;
        for (FileInfo fi : ls.entries()) {
            if (!dirOnly || fi.isDirectory()) {
                n++;
            }
        }
        return n;
    }

    private static int countMdLeafFiles(AbstractFilesystem fs, RuntimeContext rc, String absPath) {
        LsResult ls = fs.ls(rc, absPath);
        if (!ls.isSuccess() || ls.entries() == null) {
            return 0;
        }
        int n = 0;
        for (FileInfo fi : ls.entries()) {
            if (!fi.isDirectory() && fi.path().endsWith(".md")) {
                n++;
            }
        }
        return n;
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

    private static String fileName(String path) {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
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
