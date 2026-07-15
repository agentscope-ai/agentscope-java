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
import io.agentscope.claw2.runtime.ClawBootstrap;
import io.agentscope.claw2.web.catalog.AgentCatalogService;
import io.agentscope.claw2.web.catalog.AgentDefinition;
import io.agentscope.claw2.web.catalog.UserAgentDefinitionStore;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.subagent.AgentSpecLoader;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
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
 * Generic workspace file CRUD for an agent. All paths are relative to the agent's workspace root
 * under {@code ${clawHome}/agents/{agentId}/workspace/} (or a user-configured override) and are
 * validated to stay within that root.
 */
@RestController
@RequestMapping("/api/agents/{agentId}/workspace")
public class AgentWorkspaceController {

    // Generous cap so JSONL session transcripts and similar log-style files fit. Files larger
    // than this return a placeholder string rather than failing the request.
    private static final int MAX_FILE_SIZE = 5 * 1024 * 1024;

    private final ClawBootstrap bootstrap;
    private final AgentCatalogService catalogService;

    public AgentWorkspaceController(ClawBootstrap bootstrap, AgentCatalogService catalogService) {
        this.bootstrap = bootstrap;
        this.catalogService = catalogService;
    }

    // -----------------------------------------------------------------
    //  Summary + scaffold
    // -----------------------------------------------------------------

    @GetMapping
    public Mono<WorkspaceSummary> summary(@PathVariable String agentId) {
        return Mono.fromCallable(
                () -> {
                    Path ws = resolveWorkspace(agentId);
                    return summarize(agentId, ws);
                });
    }

    @PostMapping("/scaffold")
    public Mono<WorkspaceSummary> scaffold(
            @PathVariable String agentId,
            @RequestParam(name = "name", defaultValue = "") String agentName) {
        return Mono.fromCallable(
                () -> {
                    Path ws = resolveWorkspace(agentId);
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
    public Mono<MemoryView> memory(@PathVariable String agentId) {
        return Mono.fromCallable(
                () -> {
                    WorkspaceContext ctx = resolveContext(agentId);
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
            @RequestParam(name = "recursive", defaultValue = "true") boolean recursive) {
        return Mono.fromCallable(
                () -> {
                    Path ws = resolveWorkspace(agentId);
                    if (!Files.isDirectory(ws)) {
                        return List.of();
                    }
                    return collectChildren(ws, ws, recursive ? 6 : 1);
                });
    }

    @GetMapping("/file")
    public Mono<String> readFile(@PathVariable String agentId, @RequestParam("path") String path) {
        return Mono.fromCallable(
                () -> {
                    WorkspaceContext ctx = resolveContext(agentId);
                    Path target = guardPath(ctx.workspace().resolve(path), ctx.workspace());
                    if (!Files.isRegularFile(target)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "File not found: " + path);
                    }
                    long size = sizeOf(target);
                    if (size > MAX_FILE_SIZE) {
                        return "(file too large to display: " + size + " bytes)";
                    }
                    String rel = relativize(ctx.workspace(), target);
                    return ctx.manager().readManagedWorkspaceFileUtf8(RuntimeContext.empty(), rel);
                });
    }

    @PutMapping("/file")
    public Mono<FileNode> writeFile(
            @PathVariable String agentId,
            @RequestParam("path") String path,
            @RequestBody WriteRequest req) {
        return Mono.fromCallable(
                () -> {
                    WorkspaceContext ctx = resolveContext(agentId);
                    Path target = guardPath(ctx.workspace().resolve(path), ctx.workspace());
                    if (Files.isDirectory(target)) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Path is a directory: " + path);
                    }
                    String content = req != null && req.content() != null ? req.content() : "";
                    String rel = relativize(ctx.workspace(), target);
                    ctx.manager().writeUtf8WorkspaceRelative(RuntimeContext.empty(), rel, content);
                    return toNode(ctx.workspace(), target);
                });
    }

    @PostMapping("/file")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<FileNode> createNode(
            @PathVariable String agentId,
            @RequestParam("path") String path,
            @RequestParam(name = "type", defaultValue = "file") String type) {
        return Mono.fromCallable(
                () -> {
                    Path ws = resolveWorkspace(agentId);
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
                    return toNode(ws, target);
                });
    }

    @PostMapping("/file/move")
    public Mono<FileNode> moveNode(@PathVariable String agentId, @RequestBody MoveRequest req) {
        return Mono.fromCallable(
                () -> {
                    if (req == null || req.from() == null || req.to() == null) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "from and to are required");
                    }
                    Path ws = resolveWorkspace(agentId);
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
                    return toNode(ws, to);
                });
    }

    @DeleteMapping("/file")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteNode(@PathVariable String agentId, @RequestParam("path") String path) {
        return Mono.fromRunnable(
                () -> {
                    Path ws = resolveWorkspace(agentId);
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
                });
    }

    @PostMapping("/upload")
    public Mono<FileNode> upload(
            @PathVariable String agentId,
            @RequestParam("path") String path,
            @RequestPart("file") FilePart file) {
        return Mono.fromCallable(() -> resolveWorkspace(agentId))
                .flatMap(
                        ws -> {
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
                                    .then(Mono.fromCallable(() -> toNode(ws, target)));
                        });
    }

    // -----------------------------------------------------------------
    //  Subagent CRUD
    // -----------------------------------------------------------------

    @GetMapping("/subagents")
    public Mono<List<SubagentInfo>> listSubagents(@PathVariable String agentId) {
        return Mono.fromCallable(
                () -> {
                    WorkspaceContext ctx = resolveContext(agentId);
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
            @RequestBody SubagentUpsertRequest req) {
        return Mono.fromCallable(
                () -> {
                    if (req == null || req.description() == null || req.description().isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "description is required");
                    }
                    validateSubagentName(name);
                    WorkspaceContext ctx = resolveContext(agentId);
                    SubagentUpsertRequest effective = withDefaultWorkspacePath(req, name);
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
            @PathVariable String agentId, @RequestBody FromAgentRequest req) {
        return Mono.fromCallable(
                () -> {
                    if (req == null
                            || req.sourceAgentId() == null
                            || req.sourceAgentId().isBlank()) {
                        throw new ResponseStatusException(
                                HttpStatus.BAD_REQUEST, "sourceAgentId is required");
                    }
                    AgentDefinition source =
                            catalogService
                                    .find(req.sourceAgentId())
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
                    WorkspaceContext ctx = resolveContext(agentId);
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
    public Mono<Void> deleteSubagent(@PathVariable String agentId, @PathVariable String name) {
        return Mono.fromRunnable(
                () -> {
                    validateSubagentName(name);
                    WorkspaceContext ctx = resolveContext(agentId);
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
     * Resolves the workspace root for {@code agentId}. Built-in agents use {@link
     * ClawBootstrap#resolveWorkspace}; custom agents use the path stored on their
     * {@link UserAgentDefinitionStore.StoredEntry} (or the default
     * {@code ${clawHome}/agents/{id}/workspace} layout).
     */
    private Path resolveWorkspace(String agentId) {
        return resolveContext(agentId).workspace();
    }

    private WorkspaceContext resolveContext(String agentId) {
        if (catalogService.isBuiltin(agentId)) {
            Path ws = bootstrap.resolveWorkspace(agentId).normalize();
            return new WorkspaceContext(ws, newWorkspaceManager(ws));
        }
        UserAgentDefinitionStore.StoredEntry entry =
                catalogService
                        .findStoredEntry(agentId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Agent not found: " + agentId));
        Path ws = customAgentWorkspace(entry);
        return new WorkspaceContext(ws, newWorkspaceManager(ws));
    }

    // The workspace-backing filesystem is constructed in {@code virtualMode=true} so that
    // {@link AbstractFilesystem}-shaped absolute paths (e.g. {@code "/subagents"}, {@code
    // "/memory"})
    // resolve to the agent's workspace root instead of the host process root — the
    // {@link AbstractFilesystem} contract requires leading-slash paths everywhere and
    // {@code listSubagents} / {@code memory} depend on this. {@code maxFileSizeMb=10} matches the
    // {@code LocalFilesystem} default; {@code namespaceFactory=null} keeps claw single-tenant
    // (multi-user namespacing belongs in agentscope-builder, which uses CompositeFilesystem).
    //
    // The 3-arg constructor with a {@code null} index is used deliberately: this manager is a
    // short-lived, per-request object, and the 2-arg constructor would open (and never close) a
    // SQLite-backed {@link io.agentscope.harness.agent.workspace.WorkspaceIndex}, leaking a JDBC
    // connection/file handle on every request. On Windows that leaked handle prevents deleting
    // the workspace directory (e.g. a test's {@code @TempDir}) until the JVM exits.
    private static WorkspaceManager newWorkspaceManager(Path workspace) {
        return new WorkspaceManager(
                workspace, new LocalFilesystem(workspace, true, 10, null), null);
    }

    private Path customAgentWorkspace(UserAgentDefinitionStore.StoredEntry entry) {
        Path clawHome = bootstrap.clawHome();
        if (entry.workspacePath() != null && !entry.workspacePath().isBlank()) {
            Path p = Paths.get(entry.workspacePath());
            return p.isAbsolute() ? p.normalize() : clawHome.resolve(p).normalize();
        }
        return ClawBootstrap.defaultAgentWorkspace(clawHome, entry.id());
    }

    private static String relativize(Path workspace, Path target) {
        return workspace.relativize(target).toString().replace('\\', '/');
    }

    private record WorkspaceContext(Path workspace, WorkspaceManager manager) {}

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

    /**
     * Ensures an ISOLATED subagent gets an absolute default {@code workspacePath} rooted at
     * {@code <clawHome>/agents/<subName>/workspace} when the caller didn't specify one. This
     * keeps every subagent's runtime data under the app home ({@code ~/.agentscope/claw/})
     * rather than nested inside the parent agent's workspace.
     *
     * <p>Pass-through cases (returned unchanged):
     * <ul>
     *   <li>{@code workspaceMode} is {@code "shared"} (a shared-mode subagent reuses the parent
     *       workspace; {@code path} is ignored)
     *   <li>{@code workspacePath} was already supplied by the caller
     * </ul>
     */
    private SubagentUpsertRequest withDefaultWorkspacePath(
            SubagentUpsertRequest req, String subName) {
        if (req.workspacePath() != null && !req.workspacePath().isBlank()) {
            return req;
        }
        if ("shared".equalsIgnoreCase(req.workspaceMode())) {
            return req;
        }
        Path defaultPath =
                bootstrap.clawHome().resolve("agents").resolve(subName).resolve("workspace");
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
