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
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *   <li>{@code POST   /api/agents/{agentId}/workspace/scaffold} — seed AGENTS.md if absent
 *   <li>{@code GET    /api/agents/{agentId}/workspace/memory} — MEMORY.md + per-day index
 *   <li>{@code GET    /api/agents/{agentId}/workspace/files?recursive=…} — file tree
 *   <li>{@code GET    /api/agents/{agentId}/workspace/file?path=…} — read raw file
 *   <li>{@code PUT    /api/agents/{agentId}/workspace/file?path=…} — write file (body
 *       {@code {content}})
 *   <li>{@code POST   /api/agents/{agentId}/workspace/file?path=…&type=file} — create empty file
 *       (type=dir is rejected; empty directories are not representable on the composite
 *       filesystem)
 *   <li>{@code POST   /api/agents/{agentId}/workspace/file/move} — rename/move (body
 *       {@code {from, to}})
 *   <li>{@code DELETE /api/agents/{agentId}/workspace/file?path=…} — delete file or directory
 *   <li>{@code POST   /api/agents/{agentId}/workspace/upload?path=…} — multipart upload
 * </ul>
 *
 * <p>All paths are workspace-relative. All file IO goes through the per-caller
 * {@link AbstractFilesystem} obtained from {@link HarnessAgent#workspaceFor(String, String)}, so
 * userId-based isolation (provided by {@code CompositeFilesystem} + per-route
 * {@code RemoteFilesystem} namespacing) is honored automatically and remote routes
 * (memory/, skills/, subagents/, …) are visible in the same tree as local-fallback content.
 *
 * <p>Visibility is enforced via {@link AgentCatalogService#findVisible(String, String)}; path
 * traversal is rejected by {@link AbstractFilesystem#validatePath(String)}. The
 * {@code workspacePath} field returned by {@link #summary} is the agent's shared workspace root,
 * exposed for display only — it does not point at where the caller's data physically lives.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/workspace")
public class AgentWorkspaceController {

    private static final int MAX_FILE_SIZE = 512 * 1024;
    private static final RuntimeContext FS_RC = RuntimeContext.empty();

    private final AgentCatalogService catalogService;
    private final AgentAccessGuard guard;
    private final AgentActivityStore activity;

    public AgentWorkspaceController(
            AgentCatalogService catalogService,
            AgentAccessGuard guard,
            AgentActivityStore activity) {
        this.catalogService = catalogService;
        this.guard = guard;
        this.activity = activity;
    }

    // -----------------------------------------------------------------
    //  Summary + scaffold
    // -----------------------------------------------------------------

    @GetMapping
    public Mono<WorkspaceSummary> summary(
            @PathVariable("agentId") String agentId, Authentication auth) {
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
            @PathVariable("agentId") String agentId,
            @RequestParam(name = "name", defaultValue = "") String agentName,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.EDIT);
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    AbstractFilesystem fs = ctx.manager().getFilesystem();
                    if (!fs.exists(FS_RC, "/AGENTS.md")) {
                        String displayName = agentName.isBlank() ? agentId : agentName;
                        String body = "# " + displayName + "\n\nYou are " + displayName + ".\n";
                        fs.uploadFiles(
                                FS_RC,
                                List.of(
                                        Map.entry(
                                                "AGENTS.md",
                                                body.getBytes(StandardCharsets.UTF_8))));
                    }
                    // skills/, subagents/, memory/ directories are no longer pre-created — they
                    // materialize implicitly on first write through AbstractFilesystem and live
                    // under the per-user namespace by design.
                    return summarize(agentId, ctx);
                });
    }

    // -----------------------------------------------------------------
    //  Memory (read-only convenience view)
    // -----------------------------------------------------------------

    @GetMapping("/memory")
    public Mono<MemoryView> memory(@PathVariable("agentId") String agentId, Authentication auth) {
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
            @PathVariable("agentId") String agentId,
            @RequestParam(name = "recursive", defaultValue = "true") boolean recursive,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    AbstractFilesystem fs = ctx.manager().getFilesystem();
                    if (recursive) {
                        GlobResult gr = fs.glob(FS_RC, "**/*", "/");
                        if (!gr.isSuccess() || gr.matches() == null) {
                            return List.<FileNode>of();
                        }
                        return buildTreeFromGlob(gr.matches());
                    }
                    LsResult ls = fs.ls(FS_RC, "/");
                    if (!ls.isSuccess() || ls.entries() == null) {
                        return List.<FileNode>of();
                    }
                    return flattenShallow(ls.entries());
                });
    }

    @GetMapping("/file")
    public Mono<String> readFile(
            @PathVariable("agentId") String agentId,
            @RequestParam("path") String path,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.RUN);
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    AbstractFilesystem fs = ctx.manager().getFilesystem();
                    String abs = toAbsFsPath(path);
                    if (!fs.exists(FS_RC, abs)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "File not found: " + path);
                    }
                    ReadResult rr = fs.read(FS_RC, abs, 0, Integer.MAX_VALUE);
                    if (!rr.isSuccess() || rr.fileData() == null) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "File not found: " + path);
                    }
                    String content = rr.fileData().content();
                    String encoding = rr.fileData().encoding();
                    if (content == null) {
                        return "";
                    }
                    if ("base64".equalsIgnoreCase(encoding)) {
                        // Binary content — editor only handles text. Surface a placeholder rather
                        // than the base64 blob.
                        return "(binary file: " + content.length() + " base64 chars; not editable)";
                    }
                    if (content.length() > MAX_FILE_SIZE) {
                        return "(file too large to display: " + content.length() + " bytes)";
                    }
                    return content;
                });
    }

    @PutMapping("/file")
    public Mono<FileNode> writeFile(
            @PathVariable("agentId") String agentId,
            @RequestParam("path") String path,
            @RequestBody WriteRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.EDIT);
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    AbstractFilesystem fs = ctx.manager().getFilesystem();
                    String abs = toAbsFsPath(path);
                    String rel = toRelFsPath(path);
                    boolean existedAsDir = fs.exists(FS_RC, abs.endsWith("/") ? abs : abs + "/");
                    if (existedAsDir) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Path is a directory: " + path);
                    }
                    boolean existed = fs.exists(FS_RC, abs);
                    String content = req != null && req.content() != null ? req.content() : "";
                    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                    List<FileUploadResponse> ur =
                            fs.uploadFiles(FS_RC, List.of(Map.entry(rel, bytes)));
                    if (ur.isEmpty() || !ur.get(0).isSuccess()) {
                        String err = ur.isEmpty() ? "no response" : ur.get(0).error();
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR, "Failed to write file: " + err);
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
                    return new FileNode(basename(rel), rel, "file", (long) bytes.length, null);
                });
    }

    @PostMapping("/file")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<FileNode> createNode(
            @PathVariable("agentId") String agentId,
            @RequestParam("path") String path,
            @RequestParam(name = "type", defaultValue = "file") String type,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    guard.require(userId, agentId, Tier.EDIT);
                    if ("dir".equalsIgnoreCase(type)) {
                        // CompositeFilesystem has no explicit directory-create primitive;
                        // intermediate dirs materialize implicitly on first file write. Empty
                        // directories aren't representable, so reject the request outright.
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST,
                                "Empty directory creation is not supported — create a file inside"
                                        + " the directory instead.");
                    }
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    AbstractFilesystem fs = ctx.manager().getFilesystem();
                    String abs = toAbsFsPath(path);
                    String rel = toRelFsPath(path);
                    if (fs.exists(FS_RC, abs)
                            || fs.exists(FS_RC, abs.endsWith("/") ? abs : abs + "/")) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Already exists: " + path);
                    }
                    List<FileUploadResponse> ur =
                            fs.uploadFiles(FS_RC, List.of(Map.entry(rel, new byte[0])));
                    if (ur.isEmpty() || !ur.get(0).isSuccess()) {
                        String err = ur.isEmpty() ? "no response" : ur.get(0).error();
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create file: " + err);
                    }
                    if (ctx.ownerId() != null) {
                        activity.record(
                                ctx.ownerId(),
                                agentId,
                                activity.actor(userId),
                                ActivityEvent.Action.CREATE_FILE,
                                path,
                                null);
                    }
                    return new FileNode(basename(rel), rel, "file", 0L, null);
                });
    }

    @PostMapping("/file/move")
    public Mono<FileNode> moveNode(
            @PathVariable("agentId") String agentId,
            @RequestBody MoveRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> {
                    if (req == null || req.from() == null || req.to() == null) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "from and to are required");
                    }
                    guard.require(userId, agentId, Tier.EDIT);
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    AbstractFilesystem fs = ctx.manager().getFilesystem();
                    String absFrom = toAbsFsPath(req.from());
                    String absTo = toAbsFsPath(req.to());
                    if (!fs.exists(FS_RC, absFrom)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Source not found: " + req.from());
                    }
                    if (fs.exists(FS_RC, absTo)
                            || fs.exists(FS_RC, absTo.endsWith("/") ? absTo : absTo + "/")) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Target already exists: " + req.to());
                    }
                    WriteResult wr = fs.move(FS_RC, absFrom, absTo);
                    if (!wr.isSuccess()) {
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR, "Move failed: " + wr.error());
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
                    String relTo = toRelFsPath(req.to());
                    return new FileNode(basename(relTo), relTo, "file", null, null);
                });
    }

    @DeleteMapping("/file")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteNode(
            @PathVariable("agentId") String agentId,
            @RequestParam("path") String path,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(
                () -> {
                    guard.require(userId, agentId, Tier.EDIT);
                    WorkspaceContext ctx = resolveContext(userId, agentId);
                    AbstractFilesystem fs = ctx.manager().getFilesystem();
                    String abs = toAbsFsPath(path);
                    String absDir = abs.endsWith("/") ? abs : abs + "/";
                    if (!fs.exists(FS_RC, abs) && !fs.exists(FS_RC, absDir)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Not found: " + path);
                    }
                    WriteResult wr = fs.delete(FS_RC, abs);
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
            @PathVariable("agentId") String agentId,
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
                            String filename = sanitiseFilename(file.filename());
                            String relDir = toAbsFsPath(path);
                            // Build "dir/filename" relative path (no leading slash, for
                            // uploadFiles).
                            String dirRel = relDir.equals("/") ? "" : relDir.substring(1);
                            if (!dirRel.isEmpty() && !dirRel.endsWith("/")) {
                                dirRel = dirRel + "/";
                            }
                            final String rel = dirRel + filename;
                            // Final defensive validation on the assembled relative path.
                            try {
                                AbstractFilesystem.validatePath("/" + rel);
                            } catch (IllegalArgumentException e) {
                                return Mono.error(
                                        new ResponseStatusException(
                                                HttpStatus.BAD_REQUEST, e.getMessage()));
                            }
                            return DataBufferUtils.join(file.content())
                                    .map(
                                            buf -> {
                                                try {
                                                    byte[] bytes =
                                                            new byte[buf.readableByteCount()];
                                                    buf.read(bytes);
                                                    return bytes;
                                                } finally {
                                                    DataBufferUtils.release(buf);
                                                }
                                            })
                                    .defaultIfEmpty(new byte[0])
                                    .map(
                                            bytes -> {
                                                AbstractFilesystem fs =
                                                        ctx.manager().getFilesystem();
                                                List<FileUploadResponse> ur =
                                                        fs.uploadFiles(
                                                                FS_RC,
                                                                List.of(Map.entry(rel, bytes)));
                                                if (ur.isEmpty() || !ur.get(0).isSuccess()) {
                                                    String err =
                                                            ur.isEmpty()
                                                                    ? "no response"
                                                                    : ur.get(0).error();
                                                    throw new ResponseStatusException(
                                                            HttpStatus.INTERNAL_SERVER_ERROR,
                                                            "Upload failed: " + err);
                                                }
                                                if (ctx.ownerId() != null) {
                                                    activity.record(
                                                            ctx.ownerId(),
                                                            agentId,
                                                            activity.actor(userId),
                                                            ActivityEvent.Action.UPLOAD_FILE,
                                                            rel,
                                                            null);
                                                }
                                                return new FileNode(
                                                        basename(rel),
                                                        rel,
                                                        "file",
                                                        (long) bytes.length,
                                                        null);
                                            });
                        });
    }

    // -----------------------------------------------------------------
    //  Subagent CRUD
    // -----------------------------------------------------------------

    @GetMapping("/subagents")
    public Mono<List<SubagentInfo>> listSubagents(
            @PathVariable("agentId") String agentId, Authentication auth) {
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
            @PathVariable("agentId") String agentId,
            @PathVariable("name") String name,
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
                    SubagentUpsertRequest effective =
                            withDefaultWorkspacePath(req, userId, agentId, name);
                    String markdown = renderSubagentMarkdown(effective);
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
            @PathVariable("agentId") String agentId,
            @RequestBody FromAgentRequest req,
            Authentication auth) {
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
            @PathVariable("agentId") String agentId,
            @PathVariable("name") String name,
            Authentication auth) {
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
     * Resolves the (display workspace path, {@link WorkspaceManager}, audit-owner) tuple for an
     * agent.
     *
     * <p>The {@link WorkspaceManager} is obtained from the live {@link HarnessAgent} via
     * {@code workspaceFor(ctxUser, null)} so reads and writes flow through the composite
     * filesystem (per-pod local with per-user namespacing for the shared content layer,
     * BaseStore-backed for routed runtime prefixes). The {@code ctxUser} is the agent owner for
     * SCOPE_USER agents (so EDIT-delegated mutations land in the owner's namespace, matching
     * pre-PR4 semantics) and the caller for globals (which have no single owner — per-caller
     * isolation is the right default). The gateway pins chat-time reads to the same {@code
     * ctxUser} via the {@code fsUserIdResolver} installed by {@link AgentCatalogService}, so the
     * controller-write and chat-read namespaces always agree.
     *
     * <p><strong>The {@code workspace} {@link Path} returned by this method is the agent's shared
     * workspace root, exposed only for {@link #summary} display.</strong> It must not be used for
     * file IO — go through {@code ctx.manager().getFilesystem()} instead so userId namespacing
     * and remote routes are honored.
     *
     * <p>The {@code ownerId} returned in the context is the value used to key activity-log
     * records, mirroring the audit semantics described above.
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
        HarnessAgent agent = catalogService.getOrInstantiateRunningAgent(userId, agentId);
        if (agent == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent not found: " + agentId);
        }
        String ctxUser;
        if (AgentDefinition.SCOPE_USER.equals(def.scope())) {
            ctxUser = def.ownerId() != null ? def.ownerId() : userId;
        } else {
            ctxUser = userId;
        }
        WorkspaceManager wm = agent.workspaceFor(ctxUser, null);
        return new WorkspaceContext(wm.getWorkspace().normalize(), wm, ctxUser);
    }

    private record WorkspaceContext(Path workspace, WorkspaceManager manager, String ownerId) {}

    /**
     * Normalizes a user-supplied workspace-relative path into an {@link AbstractFilesystem}
     * absolute path (leading {@code /}). Strips any leading slashes from the caller, then routes
     * through {@link AbstractFilesystem#validatePath(String)} which rejects {@code ..} traversal.
     *
     * <p>An empty or blank input maps to {@code "/"} (the workspace root) — useful for listing.
     */
    private static String toAbsFsPath(String userPath) {
        String p = userPath == null ? "" : userPath.trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.isEmpty()) {
            return "/";
        }
        String abs = "/" + p;
        try {
            AbstractFilesystem.validatePath(abs);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return abs;
    }

    /**
     * Returns the workspace-relative path (no leading {@code /}) for use with
     * {@link AbstractFilesystem#uploadFiles(RuntimeContext, List)} — that API takes relative
     * paths everywhere else in the codebase.
     */
    private static String toRelFsPath(String userPath) {
        String abs = toAbsFsPath(userPath);
        if ("/".equals(abs)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Path is required for this operation");
        }
        return abs.substring(1);
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
     * Normalizes a {@link FileInfo} path coming out of an {@link AbstractFilesystem} into a
     * workspace-relative path: strips leading/trailing slashes. Local backends in namespaced mode
     * already return relative paths, but virtual-mode backends and composite routes prepend
     * {@code /}; this collapses both forms.
     */
    private static String relPath(String fsPath) {
        if (fsPath == null) {
            return "";
        }
        String p = fsPath;
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        while (p.endsWith("/")) {
            p = p.substring(0, p.length() - 1);
        }
        return p;
    }

    private static String basename(String relPath) {
        int slash = relPath.lastIndexOf('/');
        return slash >= 0 ? relPath.substring(slash + 1) : relPath;
    }

    /**
     * Folds a flat {@code glob("**&#47;*", "/")} result into the nested {@link FileNode} tree the
     * UI expects. Each file is placed under the chain of directory nodes derived from its path
     * components; directories listed explicitly by the backend are merged in by deduplicating on
     * relative path. Sort: directories first, then files, both alphabetical — same order the old
     * {@code Files.newDirectoryStream}-based implementation produced.
     */
    private static List<FileNode> buildTreeFromGlob(List<FileInfo> files) {
        // Use ordered maps so the tree-building remains deterministic across runs.
        Map<String, List<FileNode>> rootChildren = new LinkedHashMap<>();
        Map<String, List<FileNode>> dirChildren = new LinkedHashMap<>();
        Map<String, FileNode> dirNodes = new LinkedHashMap<>();
        Set<String> dirPaths = new LinkedHashSet<>();

        for (FileInfo fi : files) {
            String rel = relPath(fi.path());
            if (rel.isEmpty()) {
                continue;
            }
            String[] parts = rel.split("/");
            if (fi.isDirectory()) {
                ensureDirChain(parts, parts.length, dirPaths, dirNodes, rootChildren, dirChildren);
                continue;
            }
            // Ensure parent directories exist as nodes
            ensureDirChain(parts, parts.length - 1, dirPaths, dirNodes, rootChildren, dirChildren);
            FileNode fileNode = new FileNode(parts[parts.length - 1], rel, "file", fi.size(), null);
            if (parts.length == 1) {
                rootChildren.computeIfAbsent("", k -> new ArrayList<>()).add(fileNode);
            } else {
                String parent = String.join("/", Arrays.copyOf(parts, parts.length - 1));
                dirChildren.computeIfAbsent(parent, k -> new ArrayList<>()).add(fileNode);
            }
        }

        // Stitch children into directory nodes (replacing the placeholder dir nodes inline).
        for (Map.Entry<String, FileNode> e : dirNodes.entrySet()) {
            List<FileNode> kids = dirChildren.getOrDefault(e.getKey(), new ArrayList<>());
            sortNodes(kids);
            // Find the corresponding parent's child list and replace the placeholder with a
            // populated node that owns these children.
            FileNode populated =
                    new FileNode(e.getValue().name(), e.getValue().path(), "dir", null, kids);
            replaceInParent(e.getKey(), populated, rootChildren, dirChildren);
        }

        List<FileNode> roots = rootChildren.getOrDefault("", new ArrayList<>());
        sortNodes(roots);
        return roots;
    }

    /**
     * Walks the parent chain of {@code parts[0..len-1]} and inserts placeholder directory nodes
     * for any segment we haven't seen yet. Each new dir is hooked into its parent's child list
     * once (deduplication is done via {@code dirPaths}).
     */
    private static void ensureDirChain(
            String[] parts,
            int len,
            Set<String> dirPaths,
            Map<String, FileNode> dirNodes,
            Map<String, List<FileNode>> rootChildren,
            Map<String, List<FileNode>> dirChildren) {
        for (int i = 1; i <= len; i++) {
            String dirPath = String.join("/", Arrays.copyOf(parts, i));
            if (!dirPaths.add(dirPath)) {
                continue;
            }
            FileNode placeholder = new FileNode(parts[i - 1], dirPath, "dir", null, null);
            dirNodes.put(dirPath, placeholder);
            if (i == 1) {
                rootChildren.computeIfAbsent("", k -> new ArrayList<>()).add(placeholder);
            } else {
                String parent = String.join("/", Arrays.copyOf(parts, i - 1));
                dirChildren.computeIfAbsent(parent, k -> new ArrayList<>()).add(placeholder);
            }
        }
    }

    private static void replaceInParent(
            String dirPath,
            FileNode populated,
            Map<String, List<FileNode>> rootChildren,
            Map<String, List<FileNode>> dirChildren) {
        int slash = dirPath.lastIndexOf('/');
        List<FileNode> parentList;
        if (slash < 0) {
            parentList = rootChildren.get("");
        } else {
            parentList = dirChildren.get(dirPath.substring(0, slash));
        }
        if (parentList == null) {
            return;
        }
        for (int i = 0; i < parentList.size(); i++) {
            if (parentList.get(i).path().equals(dirPath)) {
                parentList.set(i, populated);
                return;
            }
        }
    }

    /**
     * Shallow listing for {@code recursive=false}: take the entries from {@code fs.ls("/")} as a
     * flat (no children) {@link FileNode} list, deduplicate on path (composite's union view may
     * surface the same dir from both the default backend and a route placeholder), and apply the
     * same sort the recursive variant uses.
     */
    private static List<FileNode> flattenShallow(List<FileInfo> entries) {
        Map<String, FileNode> seen = new LinkedHashMap<>();
        for (FileInfo fi : entries) {
            String rel = relPath(fi.path());
            if (rel.isEmpty()) {
                continue;
            }
            seen.putIfAbsent(
                    rel,
                    new FileNode(
                            basename(rel),
                            rel,
                            fi.isDirectory() ? "dir" : "file",
                            fi.isDirectory() ? null : fi.size(),
                            null));
        }
        List<FileNode> out = new ArrayList<>(seen.values());
        sortNodes(out);
        return out;
    }

    private static void sortNodes(List<FileNode> nodes) {
        nodes.sort(
                Comparator.<FileNode, Integer>comparing(n -> "dir".equals(n.type()) ? 0 : 1)
                        .thenComparing(FileNode::name));
    }

    private static WorkspaceSummary summarize(String agentId, WorkspaceContext ctx) {
        AbstractFilesystem fs = ctx.manager().getFilesystem();
        boolean agentsMdExists = fs.exists(FS_RC, "/AGENTS.md");
        boolean memoryMdExists = fs.exists(FS_RC, "/MEMORY.md");
        int skillCount = countLs(fs, "/skills", true, null);
        int subagentCount = countLs(fs, "/subagents", false, ".md");
        int dailyMemoryCount = countLs(fs, "/memory", false, ".md");
        // {@code exists} historically meant "the workspace directory is present on disk". With
        // composite/remote backends the per-caller workspace is logical, not physical — so use
        // "any expected file or directory present" as a proxy. This keeps the UI's empty-state
        // behavior consistent across backends.
        boolean exists =
                agentsMdExists
                        || memoryMdExists
                        || skillCount > 0
                        || subagentCount > 0
                        || dailyMemoryCount > 0;
        return new WorkspaceSummary(
                agentId,
                ctx.workspace().toAbsolutePath().toString(),
                exists,
                agentsMdExists,
                memoryMdExists,
                skillCount,
                subagentCount,
                dailyMemoryCount);
    }

    /**
     * Counts entries under {@code dirAbsPath} via {@code fs.ls}, optionally restricted to
     * directories ({@code dirOnly=true}) or to files whose name ends with {@code suffix} (when
     * non-null). Missing directories count as zero.
     */
    private static int countLs(
            AbstractFilesystem fs, String dirAbsPath, boolean dirOnly, String suffix) {
        LsResult ls = fs.ls(FS_RC, dirAbsPath);
        if (!ls.isSuccess() || ls.entries() == null) {
            return 0;
        }
        int n = 0;
        for (FileInfo fi : ls.entries()) {
            if (dirOnly) {
                if (fi.isDirectory()) {
                    n++;
                }
            } else if (!fi.isDirectory()) {
                if (suffix == null || fi.path().endsWith(suffix)) {
                    n++;
                }
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

    /**
     * Ensures an ISOLATED subagent gets a per-user absolute default {@code workspacePath} rooted
     * at {@code <builderHome>/users/<userId>/agents/<parentAgentId>/agents/<subName>/workspace}
     * when the caller didn't specify one. The {@code userId} prefix is what keeps each tenant's
     * subagent runtime data on disk separated.
     *
     * <p>Pass-through cases (returned unchanged):
     * <ul>
     *   <li>{@code workspaceMode} is {@code "shared"} (a shared-mode subagent reuses the parent
     *       workspace; {@code path} is ignored)
     *   <li>{@code workspacePath} was already supplied by the caller
     * </ul>
     */
    private static SubagentUpsertRequest withDefaultWorkspacePath(
            SubagentUpsertRequest req, String userId, String parentAgentId, String subName) {
        if (req.workspacePath() != null && !req.workspacePath().isBlank()) {
            return req;
        }
        if ("shared".equalsIgnoreCase(req.workspaceMode())) {
            return req;
        }
        Path builderHome = BuilderBootstrap.DEFAULT_WORKSPACE_ROOT.getParent();
        Path defaultPath =
                builderHome
                        .resolve("users")
                        .resolve(userId)
                        .resolve("agents")
                        .resolve(parentAgentId)
                        .resolve("agents")
                        .resolve(subName)
                        .resolve("workspace");
        return new SubagentUpsertRequest(
                req.description(),
                req.model(),
                req.maxIters(),
                req.tools(),
                req.workspaceMode() != null ? req.workspaceMode() : "isolated",
                defaultPath.toString(),
                req.inlineBody(),
                req.sourceAgentId());
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
