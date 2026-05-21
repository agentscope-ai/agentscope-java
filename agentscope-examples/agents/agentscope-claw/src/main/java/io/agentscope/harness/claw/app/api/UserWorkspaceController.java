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
package io.agentscope.harness.claw.app.api;

import io.agentscope.harness.claw.app.session.UserWorkspaceProvisioner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * REST controller backing the per-user workspace file-tree browser.
 *
 * <ul>
 *   <li>{@code GET    /api/user/workspace/tree} — recursive directory listing
 *   <li>{@code GET    /api/user/workspace/file?path=...} — read a file (text or base64-encoded)
 *   <li>{@code PUT    /api/user/workspace/file?path=...} — overwrite an existing file
 *   <li>{@code POST   /api/user/workspace/file?path=...} — create a new file or directory
 *   <li>{@code DELETE /api/user/workspace/file?path=...} — delete a file (or empty directory)
 *   <li>{@code POST   /api/user/workspace/upload?path=...} — multipart upload into {@code knowledge/}
 * </ul>
 *
 * <p>All paths are resolved through
 * {@link UserWorkspaceProvisioner#resolveInsideUserWorkspace(String, String)} so traversal
 * sequences ({@code ..}, absolute paths) are rejected before any disk I/O happens. Writes are
 * additionally restricted to a per-call whitelist of subdirectories so that runtime-managed
 * directories (notably {@code sessions/}, which holds transcripts the user must not corrupt) stay
 * read-only from the UI.
 */
@RestController
@RequestMapping("/api/user/workspace")
public class UserWorkspaceController {

    private static final Logger log = LoggerFactory.getLogger(UserWorkspaceController.class);

    /**
     * Subdirectories users may write into through this controller. Top-level files have to live in
     * one of these (or be {@code AGENTS.md}); writes anywhere else are 403'd.
     */
    private static final Set<String> WRITABLE_TOP_LEVEL =
            Set.of("skills", "subagents", "knowledge", "memory");

    /** Top-level files the user may overwrite directly (everything else lives under a dir). */
    private static final Set<String> WRITABLE_TOP_FILES = Set.of("AGENTS.md");

    /** Upper bound for {@code depth} query param so we can't be asked to walk forever. */
    private static final int MAX_TREE_DEPTH = 12;

    /** Hard cap for any file returned to the browser as text or base64. */
    private static final long MAX_READ_BYTES = 2L * 1024 * 1024;

    /** Hard cap for the size of a single uploaded or written file. */
    private static final long MAX_WRITE_BYTES = 16L * 1024 * 1024;

    private final UserWorkspaceProvisioner provisioner;

    public UserWorkspaceController(UserWorkspaceProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    // -----------------------------------------------------------------
    //  Tree listing
    // -----------------------------------------------------------------

    @GetMapping("/tree")
    public Mono<TreeNode> tree(@RequestParam(defaultValue = "8") int depth, Authentication auth) {
        String userId = userId(auth);
        int cappedDepth = Math.max(1, Math.min(depth, MAX_TREE_DEPTH));
        return Mono.fromCallable(
                () -> {
                    Path root = provisioner.resolveUserWorkspace(userId);
                    return buildTree(root, root, cappedDepth);
                });
    }

    // -----------------------------------------------------------------
    //  File CRUD
    // -----------------------------------------------------------------

    @GetMapping("/file")
    public Mono<FileContent> readFile(@RequestParam String path, Authentication auth) {
        String userId = userId(auth);
        return Mono.fromCallable(
                () -> {
                    Path file = resolveExisting(userId, path);
                    if (!Files.isRegularFile(file)) {
                        throw badRequest("Not a regular file: " + path);
                    }
                    long size = Files.size(file);
                    if (size > MAX_READ_BYTES) {
                        throw new ResponseStatusException(
                                HttpStatus.PAYLOAD_TOO_LARGE,
                                "File too large to preview (" + size + " bytes)");
                    }
                    byte[] bytes = Files.readAllBytes(file);
                    boolean binary = looksBinary(bytes);
                    if (binary) {
                        return new FileContent(
                                path, size, true, null, Base64.getEncoder().encodeToString(bytes));
                    }
                    return new FileContent(
                            path, size, false, new String(bytes, StandardCharsets.UTF_8), null);
                });
    }

    @PutMapping("/file")
    public Mono<FileMeta> writeFile(
            @RequestParam String path, @RequestBody WriteRequest body, Authentication auth) {
        return writeText(auth, path, body, /* createOnly= */ false);
    }

    @PostMapping("/file")
    public Mono<FileMeta> createFileOrDir(
            @RequestParam String path,
            @RequestParam(defaultValue = "file") String kind,
            @RequestBody(required = false) WriteRequest body,
            Authentication auth) {
        String userId = userId(auth);
        if ("dir".equalsIgnoreCase(kind)) {
            return Mono.fromCallable(
                    () -> {
                        Path target = resolveForWrite(userId, path);
                        if (Files.exists(target)) {
                            throw new ResponseStatusException(
                                    HttpStatus.CONFLICT, "Already exists: " + path);
                        }
                        Files.createDirectories(target);
                        log.info("User {} created dir: {}", userId, path);
                        return toMeta(target, provisioner.resolveUserWorkspace(userId));
                    });
        }
        return writeText(auth, path, body, /* createOnly= */ true);
    }

    @DeleteMapping("/file")
    public Mono<Void> delete(@RequestParam String path, Authentication auth) {
        String userId = userId(auth);
        return Mono.fromRunnable(
                () -> {
                    Path target = resolveForWrite(userId, path);
                    if (!Files.exists(target)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Not found: " + path);
                    }
                    try {
                        if (Files.isDirectory(target)) {
                            // Only empty directories — wholesale recursive delete is too dangerous
                            // through a UI endpoint.
                            try (Stream<Path> entries = Files.list(target)) {
                                if (entries.findAny().isPresent()) {
                                    throw new ResponseStatusException(
                                            HttpStatus.CONFLICT, "Directory not empty: " + path);
                                }
                            }
                            Files.delete(target);
                        } else {
                            Files.delete(target);
                        }
                        log.info("User {} deleted: {}", userId, path);
                    } catch (IOException e) {
                        throw new ResponseStatusException(
                                HttpStatus.INTERNAL_SERVER_ERROR,
                                "Failed to delete: " + e.getMessage());
                    }
                });
    }

    // -----------------------------------------------------------------
    //  Upload (knowledge/)
    // -----------------------------------------------------------------

    @PostMapping("/upload")
    public Mono<FileMeta> upload(
            @RequestPart("file") FilePart filePart,
            @RequestParam(defaultValue = "knowledge") String dir,
            Authentication auth) {
        String userId = userId(auth);
        String filename = filePart.filename();
        if (filename == null || filename.isBlank()) {
            return Mono.error(badRequest("Uploaded file has no name"));
        }
        // Strip directory components clients might smuggle in via the filename.
        String safeName = filename.replaceAll("[\\\\/]", "_");
        String relative = dir.endsWith("/") ? dir + safeName : dir + "/" + safeName;
        Path target = resolveForWrite(userId, relative);

        return Mono.fromCallable(
                        () -> {
                            Files.createDirectories(target.getParent());
                            return target;
                        })
                .flatMap(
                        t ->
                                DataBufferUtils.write(
                                                filePart.content(),
                                                t,
                                                StandardOpenOption.CREATE,
                                                StandardOpenOption.TRUNCATE_EXISTING,
                                                StandardOpenOption.WRITE)
                                        .then(
                                                Mono.fromCallable(
                                                        () -> {
                                                            long size = Files.size(t);
                                                            if (size > MAX_WRITE_BYTES) {
                                                                Files.deleteIfExists(t);
                                                                throw new ResponseStatusException(
                                                                        HttpStatus
                                                                                .PAYLOAD_TOO_LARGE,
                                                                        "Upload exceeds "
                                                                                + MAX_WRITE_BYTES
                                                                                + " bytes");
                                                            }
                                                            log.info(
                                                                    "User {} uploaded {} ({}"
                                                                            + " bytes)",
                                                                    userId,
                                                                    relative,
                                                                    size);
                                                            return toMeta(
                                                                    t,
                                                                    provisioner
                                                                            .resolveUserWorkspace(
                                                                                    userId));
                                                        })));
    }

    // -----------------------------------------------------------------
    //  Internal helpers
    // -----------------------------------------------------------------

    private Mono<FileMeta> writeText(
            Authentication auth, String path, WriteRequest body, boolean createOnly) {
        String userId = userId(auth);
        return Mono.fromCallable(
                () -> {
                    Path target = resolveForWrite(userId, path);
                    if (createOnly && Files.exists(target)) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT, "Already exists: " + path);
                    }
                    if (!createOnly && !Files.isRegularFile(target)) {
                        throw new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "Not found: " + path);
                    }
                    String content = body != null && body.content() != null ? body.content() : "";
                    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > MAX_WRITE_BYTES) {
                        throw new ResponseStatusException(
                                HttpStatus.PAYLOAD_TOO_LARGE,
                                "Content exceeds " + MAX_WRITE_BYTES + " bytes");
                    }
                    Files.createDirectories(target.getParent());
                    Files.write(target, bytes);
                    log.info(
                            "User {} {} file: {} ({} bytes)",
                            userId,
                            createOnly ? "created" : "updated",
                            path,
                            bytes.length);
                    return toMeta(target, provisioner.resolveUserWorkspace(userId));
                });
    }

    private Path resolveExisting(String userId, String path) {
        Path resolved;
        try {
            resolved = provisioner.resolveInsideUserWorkspace(userId, path);
        } catch (IllegalArgumentException iae) {
            throw badRequest(iae.getMessage());
        }
        if (!Files.exists(resolved)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found: " + path);
        }
        return resolved;
    }

    private Path resolveForWrite(String userId, String path) {
        if (path == null || path.isBlank()) {
            throw badRequest("path is required");
        }
        String normalised = path.replace('\\', '/').replaceAll("^/+", "");
        if (!isWritablePath(normalised)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Writes are not allowed at: " + path);
        }
        try {
            return provisioner.resolveInsideUserWorkspace(userId, normalised);
        } catch (IllegalArgumentException iae) {
            throw badRequest(iae.getMessage());
        }
    }

    private static boolean isWritablePath(String relative) {
        if (relative.isEmpty()) return false;
        int slash = relative.indexOf('/');
        if (slash < 0) {
            return WRITABLE_TOP_FILES.contains(relative);
        }
        String head = relative.substring(0, slash);
        return WRITABLE_TOP_LEVEL.contains(head);
    }

    private static TreeNode buildTree(Path root, Path node, int remainingDepth) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(node, BasicFileAttributes.class);
        String name = node.equals(root) ? "" : node.getFileName().toString();
        String relativePath = relative(root, node);
        if (!attrs.isDirectory()) {
            return new TreeNode(
                    name,
                    relativePath,
                    "file",
                    attrs.size(),
                    attrs.lastModifiedTime().toMillis(),
                    null);
        }
        List<TreeNode> children = new ArrayList<>();
        if (remainingDepth > 0) {
            try (Stream<Path> entries = Files.list(node)) {
                List<Path> sorted =
                        entries.sorted(
                                        Comparator.<Path, Integer>comparing(
                                                        p -> Files.isDirectory(p) ? 0 : 1)
                                                .thenComparing(p -> p.getFileName().toString()))
                                .toList();
                for (Path child : sorted) {
                    try {
                        children.add(buildTree(root, child, remainingDepth - 1));
                    } catch (IOException ignored) {
                        // best-effort; skip unreadable entries
                    }
                }
            }
        }
        return new TreeNode(
                name,
                relativePath,
                "dir",
                attrs.size(),
                attrs.lastModifiedTime().toMillis(),
                children);
    }

    private static String relative(Path root, Path node) {
        if (node.equals(root)) return "";
        return root.relativize(node).toString().replace('\\', '/');
    }

    private static FileMeta toMeta(Path file, Path root) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        return new FileMeta(
                relative(root, file),
                attrs.isDirectory() ? "dir" : "file",
                attrs.size(),
                attrs.lastModifiedTime().toMillis());
    }

    private static boolean looksBinary(byte[] bytes) {
        int scan = Math.min(bytes.length, 4096);
        for (int i = 0; i < scan; i++) {
            if (bytes[i] == 0) return true;
        }
        return false;
    }

    private static String userId(Authentication auth) {
        Object principal = Objects.requireNonNull(auth, "auth").getPrincipal();
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No authenticated user");
        }
        return principal.toString();
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    // -----------------------------------------------------------------
    //  DTOs
    // -----------------------------------------------------------------

    public record TreeNode(
            String name,
            String path,
            String kind,
            long sizeBytes,
            long modifiedMs,
            List<TreeNode> children) {}

    public record FileContent(
            String path, long sizeBytes, boolean binary, String text, String base64) {}

    public record FileMeta(String path, String kind, long sizeBytes, long modifiedMs) {}

    public record WriteRequest(String content) {}
}
