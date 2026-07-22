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
package io.agentscope.harness.agent.filesystem.local;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileData;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.util.FilesystemUtils;
import io.agentscope.harness.agent.workspace.LocalFsMode;
import io.agentscope.harness.agent.workspace.PathPolicy;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AbstractFilesystem} implementation that reads and writes files on the local disk.
 *
 * <p>Path-resolution behaviour is controlled by the {@link LocalFsMode} passed at construction:
 *
 * <ul>
 *   <li>{@link LocalFsMode#SANDBOXED} — paths anchored to {@code rootDir}, {@code ..} and
 *       outside-root absolute paths blocked. Equivalent to legacy {@code virtualMode=true}.
 *   <li>{@link LocalFsMode#ROOTED} — absolute paths accepted only when they fall under one of
 *       the configured {@link PathPolicy} roots; relative paths anchor to {@code rootDir}.
 *   <li>{@link LocalFsMode#UNRESTRICTED} — absolute paths pass through; relative paths anchor
 *       to {@code rootDir}. Equivalent to legacy {@code virtualMode=false}.
 * </ul>
 */
public class LocalFilesystem implements AbstractFilesystem {

    private static final Logger log = LoggerFactory.getLogger(LocalFilesystem.class);

    private static final int DEFAULT_MAX_FILE_SIZE_MB = 10;

    private final Path cwd;
    private final LocalFsMode mode;
    private final PathPolicy pathPolicy;
    private final long maxFileSizeBytes;
    private final NamespaceFactory namespaceFactory;

    /**
     * Per-path locks for the read-modify-write cycle inside {@link #edit}.
     * Keyed by the absolute, normalized path string so that two callers operating on
     * the same file (even with different input paths) always share the same lock.
     */
    private final ConcurrentHashMap<String, ReentrantLock> fileLocks = new ConcurrentHashMap<>();

    /**
     * Same as {@link #LocalFilesystem(Path)} with {@link Path#of(String, String...) Path.of(path)}
     * after {@link String#strip()}. Pass {@code null} for the same CWD semantics as a {@code null}
     * {@link Path}. Blank strings are rejected.
     *
     * @param rootDir filesystem root as a path string, or {@code null} for process working directory
     */
    public LocalFilesystem(String rootDir) {
        this(rootDirFromString(rootDir), false, DEFAULT_MAX_FILE_SIZE_MB, null);
    }

    /**
     * Creates a abstract filesystem rooted at the given directory.
     *
     * @param rootDir root directory for all operations ({@code null} means CWD)
     */
    public LocalFilesystem(Path rootDir) {
        this(rootDir, false, DEFAULT_MAX_FILE_SIZE_MB, null);
    }

    /**
     * Creates a abstract filesystem with explicit configuration.
     *
     * @param rootDir root directory for all operations ({@code null} means CWD)
     * @param virtualMode when true, all paths are anchored to rootDir and traversal is blocked
     * @param maxFileSizeMb maximum file size in megabytes for search operations
     */
    public LocalFilesystem(Path rootDir, boolean virtualMode, int maxFileSizeMb) {
        this(rootDir, virtualMode, maxFileSizeMb, null);
    }

    /**
     * Same as {@link #LocalFilesystem(Path, boolean, int)} with a path string; see
     * {@link #LocalFilesystem(String)} for {@code null} / blank rules.
     */
    public LocalFilesystem(String rootDir, boolean virtualMode, int maxFileSizeMb) {
        this(rootDirFromString(rootDir), virtualMode, maxFileSizeMb, null);
    }

    /**
     * Legacy constructor: maps {@code virtualMode} to {@link LocalFsMode#SANDBOXED} or
     * {@link LocalFsMode#UNRESTRICTED} and uses an empty {@link PathPolicy}. Prefer the
     * mode-aware constructor below when you need {@link LocalFsMode#ROOTED}.
     *
     * @param rootDir root directory for all operations ({@code null} means CWD)
     * @param virtualMode when true, all paths are anchored to rootDir and traversal is blocked
     * @param maxFileSizeMb maximum file size in megabytes for search operations
     * @param namespaceFactory optional namespace factory for path scoping ({@code null} for none)
     */
    public LocalFilesystem(
            Path rootDir,
            boolean virtualMode,
            int maxFileSizeMb,
            NamespaceFactory namespaceFactory) {
        this(
                rootDir,
                virtualMode ? LocalFsMode.SANDBOXED : LocalFsMode.UNRESTRICTED,
                null,
                maxFileSizeMb,
                namespaceFactory);
    }

    /**
     * Same as {@link #LocalFilesystem(Path, boolean, int, NamespaceFactory)} with a path string;
     * see {@link #LocalFilesystem(String)} for {@code null} / blank rules.
     */
    public LocalFilesystem(
            String rootDir,
            boolean virtualMode,
            int maxFileSizeMb,
            NamespaceFactory namespaceFactory) {
        this(rootDirFromString(rootDir), virtualMode, maxFileSizeMb, namespaceFactory);
    }

    /**
     * Creates a filesystem with explicit mode and path policy.
     *
     * <p>The {@code mode} controls how the agent's absolute paths are validated; see
     * {@link LocalFsMode} for the three options. In {@link LocalFsMode#ROOTED} mode, an absolute
     * path is accepted only when it falls under one of the {@code pathPolicy} roots or under
     * {@code rootDir} itself; in {@link LocalFsMode#SANDBOXED} every path is anchored to
     * {@code rootDir}; in {@link LocalFsMode#UNRESTRICTED} absolute paths pass through unchanged.
     *
     * @param rootDir root directory for relative paths ({@code null} means CWD)
     * @param mode path-resolution policy ({@code null} defaults to {@link LocalFsMode#UNRESTRICTED})
     * @param pathPolicy allow-list for {@link LocalFsMode#ROOTED}; ignored otherwise
     *     ({@code null} treated as empty)
     * @param maxFileSizeMb maximum file size in megabytes for search operations
     * @param namespaceFactory optional namespace factory for path scoping ({@code null} for none)
     */
    public LocalFilesystem(
            Path rootDir,
            LocalFsMode mode,
            PathPolicy pathPolicy,
            int maxFileSizeMb,
            NamespaceFactory namespaceFactory) {
        this.cwd =
                rootDir != null
                        ? rootDir.toAbsolutePath().normalize()
                        : Path.of("").toAbsolutePath();
        this.mode = mode != null ? mode : LocalFsMode.UNRESTRICTED;
        this.pathPolicy = pathPolicy != null ? pathPolicy : PathPolicy.empty();
        this.maxFileSizeBytes = (long) maxFileSizeMb * 1024 * 1024;
        this.namespaceFactory = namespaceFactory;
    }

    /**
     * Converts a root path string to {@link Path}. {@code null} yields {@code null} (CWD). Non-null
     * values must be non-blank after {@link String#strip()}.
     */
    static Path rootDirFromString(String rootDir) {
        if (rootDir == null) {
            return null;
        }
        String trimmed = rootDir.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("root directory path must not be blank");
        }
        return Path.of(trimmed);
    }

    /**
     * Returns the root directory for this filesystem.
     */
    public Path getCwd() {
        return cwd;
    }

    /** Returns the active path-resolution mode. */
    public LocalFsMode getMode() {
        return mode;
    }

    /**
     * Returns the configured path policy. Empty when {@link #getMode()} is not
     * {@link LocalFsMode#ROOTED}; even with an empty policy, {@link LocalFsMode#ROOTED} still
     * implicitly accepts paths under {@link #getCwd()}.
     */
    public PathPolicy getPathPolicy() {
        return pathPolicy;
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        Path dirPath = resolvePath(runtimeContext, path);
        if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
            return LsResult.success(List.of());
        }

        List<FileInfo> results = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dirPath)) {
            for (Path entry : ds) {
                try {
                    BasicFileAttributes attrs =
                            Files.readAttributes(entry, BasicFileAttributes.class);
                    String entryPath = resolveEntryPath(runtimeContext, entry);
                    String modifiedAt =
                            Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis()).toString();

                    if (attrs.isDirectory()) {
                        results.add(FileInfo.ofDir(entryPath + "/", modifiedAt));
                    } else {
                        results.add(FileInfo.ofFile(entryPath, attrs.size(), modifiedAt));
                    }
                } catch (IOException e) {
                    log.debug("Skipping unreadable entry: {}", entry);
                }
            }
        } catch (IOException e) {
            log.warn("ls failed for {}: {}", path, e.getMessage());
        }

        results.sort(Comparator.comparing(FileInfo::path));
        return LsResult.success(results);
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        Path resolved = resolvePath(runtimeContext, filePath);

        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            return ReadResult.fail("File '" + filePath + "' not found");
        }

        try {
            if (!"text".equals(FilesystemUtils.getFileType(filePath))) {
                byte[] raw = Files.readAllBytes(resolved);
                String encoded = Base64.getEncoder().encodeToString(raw);
                return ReadResult.success(new FileData(encoded, "base64"));
            }

            String content = Files.readString(resolved, StandardCharsets.UTF_8);

            if (content.isEmpty() || content.isBlank()) {
                return ReadResult.success(
                        new FileData(
                                "System reminder: File exists but has empty contents", "utf-8"));
            }

            String[] lines = content.split("\n", -1);
            int startIdx = Math.max(0, offset);
            int endIdx = limit > 0 ? Math.min(startIdx + limit, lines.length) : lines.length;

            if (startIdx >= lines.length) {
                return ReadResult.fail(
                        "Line offset "
                                + offset
                                + " exceeds file length ("
                                + lines.length
                                + " lines)");
            }

            StringBuilder sb = new StringBuilder();
            for (int i = startIdx; i < endIdx; i++) {
                if (i > startIdx) {
                    sb.append('\n');
                }
                sb.append(lines[i]);
            }
            return ReadResult.success(new FileData(sb.toString(), "utf-8"));

        } catch (IOException e) {
            return ReadResult.fail("Error reading file '" + filePath + "': " + e.getMessage());
        }
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        Path resolved = resolvePath(runtimeContext, filePath);

        if (Files.exists(resolved)) {
            return WriteResult.fail(
                    "Cannot write to "
                            + filePath
                            + " because it already exists. Read and then make an edit,"
                            + " or write to a new path.");
        }

        try {
            if (resolved.getParent() != null) {
                Files.createDirectories(resolved.getParent());
            }
            Files.writeString(resolved, content, StandardCharsets.UTF_8);
            return WriteResult.ok(filePath);
        } catch (IOException e) {
            return WriteResult.fail("Error writing file '" + filePath + "': " + e.getMessage());
        }
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        Path resolved = resolvePath(runtimeContext, filePath);

        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            return EditResult.fail("Error: File '" + filePath + "' not found");
        }

        // Serialize concurrent edits to the same file to prevent lost-update races.
        String lockKey = resolved.toAbsolutePath().normalize().toString();
        ReentrantLock lock = fileLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        lock.lock();
        try {
            String content =
                    Files.readString(resolved, StandardCharsets.UTF_8)
                            .replace("\r\n", "\n")
                            .replace("\r", "\n");
            String normalizedOld = oldString.replace("\r\n", "\n").replace("\r", "\n");
            String normalizedNew = newString.replace("\r\n", "\n").replace("\r", "\n");

            Object[] result =
                    FilesystemUtils.performStringReplacement(
                            content, normalizedOld, normalizedNew, replaceAll);

            if (result.length == 1) {
                return EditResult.fail((String) result[0]);
            }

            String newContent = (String) result[0];
            int occurrences = (int) result[1];

            Files.writeString(resolved, newContent, StandardCharsets.UTF_8);
            return EditResult.ok(filePath, occurrences);
        } catch (IOException e) {
            return EditResult.fail("Error editing file '" + filePath + "': " + e.getMessage());
        } finally {
            lock.unlock();
        }
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        Path basePath;
        try {
            basePath = resolvePath(runtimeContext, path != null ? path : ".");
        } catch (SecurityException e) {
            return GrepResult.success(List.of());
        }

        if (!Files.exists(basePath)) {
            return GrepResult.success(List.of());
        }

        List<GrepMatch> matches = ripgrepSearch(runtimeContext, pattern, basePath, glob);
        if (matches == null) {
            matches = javaSearch(runtimeContext, pattern, basePath, glob);
        }
        return GrepResult.success(matches);
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        String effectivePattern = pattern;
        if (effectivePattern.startsWith("/")) {
            effectivePattern = effectivePattern.substring(1);
        }

        Path searchPath;
        if ("/".equals(path) || path == null) {
            searchPath = hasNamespace(runtimeContext) ? resolvePath(runtimeContext, ".") : cwd;
        } else {
            searchPath = resolvePath(runtimeContext, path);
        }

        if (!Files.exists(searchPath) || !Files.isDirectory(searchPath)) {
            return GlobResult.success(List.of());
        }

        String globExpr =
                effectivePattern.startsWith("**") ? effectivePattern : "**/" + effectivePattern;
        FileSystem fs = FileSystems.getDefault();
        PathMatcher matcher = fs.getPathMatcher("glob:" + globExpr);
        // Java's PathMatcher requires at least one separator for `**/<x>`, so depth-1 files
        // never satisfy patterns like `**/*`. Strip the leading `**/` so the direct matcher
        // catches files at the search root too.
        String directExpr;
        if (effectivePattern.startsWith("**/")) {
            directExpr = effectivePattern.substring(3);
        } else if (effectivePattern.equals("**")) {
            directExpr = "*";
        } else {
            directExpr = effectivePattern;
        }
        PathMatcher directMatcher = fs.getPathMatcher("glob:" + directExpr);

        List<FileInfo> results = new ArrayList<>();
        try {
            Files.walkFileTree(
                    searchPath,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            Path rel = searchPath.relativize(file);
                            if (matcher.matches(rel) || directMatcher.matches(rel)) {
                                String filePath = resolveEntryPath(runtimeContext, file);
                                String modifiedAt =
                                        Instant.ofEpochMilli(attrs.lastModifiedTime().toMillis())
                                                .toString();
                                results.add(FileInfo.ofFile(filePath, attrs.size(), modifiedAt));
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFileFailed(Path file, IOException exc) {
                            return FileVisitResult.CONTINUE;
                        }
                    });
        } catch (IOException e) {
            log.warn("glob failed for {}: {}", pattern, e.getMessage());
        }

        results.sort(Comparator.comparing(FileInfo::path));
        return GlobResult.success(results);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        List<FileUploadResponse> responses = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : files) {
            String filePath = entry.getKey();
            byte[] content = entry.getValue();
            try {
                Path resolved = resolvePath(runtimeContext, filePath);
                if (resolved.getParent() != null) {
                    Files.createDirectories(resolved.getParent());
                }
                Files.write(resolved, content);
                responses.add(FileUploadResponse.success(filePath));
            } catch (IOException e) {
                responses.add(FileUploadResponse.fail(filePath, e.getMessage()));
            } catch (SecurityException e) {
                responses.add(FileUploadResponse.fail(filePath, "permission_denied"));
            }
        }
        return responses;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        List<FileDownloadResponse> responses = new ArrayList<>();
        for (String filePath : paths) {
            try {
                Path resolved = resolvePath(runtimeContext, filePath);
                if (!Files.exists(resolved)) {
                    responses.add(FileDownloadResponse.fail(filePath, "file_not_found"));
                    continue;
                }
                if (Files.isDirectory(resolved)) {
                    responses.add(FileDownloadResponse.fail(filePath, "is_directory"));
                    continue;
                }
                byte[] content = Files.readAllBytes(resolved);
                responses.add(FileDownloadResponse.success(filePath, content));
            } catch (IOException e) {
                responses.add(FileDownloadResponse.fail(filePath, e.getMessage()));
            } catch (SecurityException e) {
                responses.add(FileDownloadResponse.fail(filePath, "permission_denied"));
            }
        }
        return responses;
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        AbstractFilesystem.validatePath(path);
        Path resolved = resolvePath(runtimeContext, path);
        if (!Files.exists(resolved)) {
            return WriteResult.ok(path); // idempotent
        }
        try {
            if (Files.isDirectory(resolved)) {
                try (Stream<Path> walk = Files.walk(resolved)) {
                    walk.sorted(Comparator.reverseOrder())
                            .forEach(
                                    p -> {
                                        try {
                                            Files.delete(p);
                                        } catch (IOException e) {
                                            log.warn("Failed to delete {}: {}", p, e.getMessage());
                                        }
                                    });
                }
            } else {
                Files.delete(resolved);
            }
            return WriteResult.ok(path);
        } catch (IOException e) {
            return WriteResult.fail("Error deleting '" + path + "': " + e.getMessage());
        }
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        AbstractFilesystem.validatePath(fromPath);
        AbstractFilesystem.validatePath(toPath);
        Path from = resolvePath(runtimeContext, fromPath);
        Path to = resolvePath(runtimeContext, toPath);
        if (!Files.exists(from)) {
            return WriteResult.fail("Source does not exist: " + fromPath);
        }
        try {
            if (to.getParent() != null) {
                Files.createDirectories(to.getParent());
            }
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
            return WriteResult.ok(toPath);
        } catch (IOException e) {
            return WriteResult.fail(
                    "Error moving '" + fromPath + "' to '" + toPath + "': " + e.getMessage());
        }
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        try {
            return Files.exists(resolvePath(runtimeContext, path));
        } catch (SecurityException e) {
            return false;
        }
    }

    // ==================== Path resolution ====================

    protected NamespaceFactory getNamespaceFactory() {
        return namespaceFactory;
    }

    protected Path resolvePath(RuntimeContext rc, String key) {
        String effectiveKey = applyNamespacePrefix(rc, key);
        if (effectiveKey == null || effectiveKey.isBlank()) {
            return cwd;
        }

        return switch (mode) {
            case SANDBOXED -> resolveSandboxed(effectiveKey);
            case ROOTED -> resolveRooted(effectiveKey);
            case UNRESTRICTED -> resolveUnrestricted(effectiveKey);
        };
    }

    private Path resolveSandboxed(String effectiveKey) {
        String vpath = effectiveKey.startsWith("/") ? effectiveKey : "/" + effectiveKey;
        if (vpath.contains("..") || vpath.startsWith("~")) {
            throw new SecurityException("Path traversal not allowed");
        }
        // Strip Windows drive prefix ("C:\" / "C:/") so absolute Windows paths get re-rooted
        // under the sandbox the same way Unix absolute paths do; no-op on Unix input.
        Path full = cwd.resolve(stripWindowsDrive(vpath.substring(1))).normalize();
        if (!full.startsWith(cwd)) {
            throw new SecurityException("Path " + full + " outside root directory: " + cwd);
        }
        return full;
    }

    private static String stripWindowsDrive(String key) {
        if (key.length() >= 3
                && Character.isLetter(key.charAt(0))
                && key.charAt(1) == ':'
                && (key.charAt(2) == '\\' || key.charAt(2) == '/')) {
            return key.substring(3);
        }
        return key;
    }

    private Path resolveRooted(String effectiveKey) {
        Path target = Path.of(effectiveKey);
        if (target.isAbsolute()) {
            Path normalized = target.normalize();
            if (normalized.startsWith(cwd) || pathPolicy.isAllowed(normalized)) {
                return normalized;
            }
            if (Files.exists(normalized)) {
                throw rootAccessDenied(normalized);
            }
            if (!effectiveKey.startsWith("/")) {
                throw rootAccessDenied(normalized);
            }
        }

        if (effectiveKey.startsWith("/")) {
            String stripped = effectiveKey.substring(1);
            if (stripped.isEmpty()) {
                return cwd;
            }
            if (stripped.startsWith("~")) {
                throw new SecurityException("Path traversal not allowed: " + effectiveKey);
            }
            Path full = cwd.resolve(stripped).normalize();
            if (!full.startsWith(cwd)) {
                throw new SecurityException("Path " + full + " outside root directory: " + cwd);
            }
            return full;
        }

        return cwd.resolve(target).normalize();
    }

    private SecurityException rootAccessDenied(Path normalized) {
        return new SecurityException(
                "Absolute path "
                        + normalized
                        + " is not within an allowed root. Filesystem root: "
                        + cwd
                        + "; additional roots: "
                        + pathPolicy.roots());
    }

    private Path resolveUnrestricted(String effectiveKey) {
        Path target = Path.of(effectiveKey);
        if (target.isAbsolute()) {
            return target;
        }
        return cwd.resolve(target).normalize();
    }

    private String applyNamespacePrefix(RuntimeContext rc, String key) {
        if (namespaceFactory == null || key == null || key.isBlank()) {
            return key;
        }
        // Absolute paths identify specific host locations and must not be namespace-scoped.
        // Prepending a namespace prefix would turn them into relative paths, causing them to
        // resolve incorrectly under the workspace root (e.g. /abs/path → ns//abs/path).
        // On Windows, absolute paths start with a drive letter ("C:\") or UNC prefix ("\\"),
        // not "/", so we must check for those forms explicitly.
        if (isAbsolutePathString(key)) {
            return key;
        }
        List<String> ns = namespaceFactory.getNamespace(rc);
        if (ns == null || ns.isEmpty()) {
            return key;
        }
        String prefix = String.join("/", ns);
        return prefix + "/" + key;
    }

    /**
     * Returns {@code true} when {@code key} looks like an absolute path on any supported OS:
     * Unix ({@code /...}), Windows drive-letter ({@code C:\} or {@code C:/}), or Windows UNC
     * ({@code \\server\share}).
     */
    private static boolean isAbsolutePathString(String key) {
        if (key.startsWith("/")) {
            return true;
        }
        // Windows drive-letter: "X:\" or "X:/"
        if (key.length() >= 3
                && Character.isLetter(key.charAt(0))
                && key.charAt(1) == ':'
                && (key.charAt(2) == '\\' || key.charAt(2) == '/')) {
            return true;
        }
        // Windows UNC: "\\server\share"
        return key.startsWith("\\\\");
    }

    protected String toVirtualPath(Path path) {
        return "/"
                + path.toAbsolutePath()
                        .normalize()
                        .toString()
                        .substring(cwd.toString().length())
                        .replace('\\', '/')
                        .replaceFirst("^/+", "");
    }

    private boolean hasNamespace(RuntimeContext rc) {
        if (namespaceFactory == null) {
            return false;
        }
        List<String> ns = namespaceFactory.getNamespace(rc);
        return ns != null && !ns.isEmpty();
    }

    private String resolveEntryPath(RuntimeContext rc, Path entry) {
        if (mode == LocalFsMode.SANDBOXED) {
            return toVirtualPath(entry);
        }
        if (hasNamespace(rc)) {
            return stripNamespacePrefix(rc, entry);
        }
        return toCwdRelativePath(entry);
    }

    private String toCwdRelativePath(Path path) {
        return cwd.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private String stripNamespacePrefix(RuntimeContext rc, Path absolutePath) {
        String relPath = toCwdRelativePath(absolutePath);
        String nsPrefix = String.join("/", namespaceFactory.getNamespace(rc));
        if (relPath.startsWith(nsPrefix + "/")) {
            return relPath.substring(nsPrefix.length() + 1);
        }
        return relPath;
    }

    // ==================== Grep implementations ====================

    private List<GrepMatch> ripgrepSearch(
            RuntimeContext rc, String pattern, Path basePath, String includeGlob) {
        List<String> cmd = new ArrayList<>();
        cmd.add("rg");
        cmd.add("--json");
        cmd.add("-F");
        if (includeGlob != null && !includeGlob.isBlank()) {
            cmd.add("--glob");
            cmd.add(includeGlob);
        }
        cmd.add("--");
        cmd.add(pattern);
        cmd.add(basePath.toString());

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process proc = pb.start();

            List<GrepMatch> matches = new ArrayList<>();
            try (BufferedReader reader =
                    new BufferedReader(
                            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    GrepMatch match = parseRipgrepJsonLine(rc, line);
                    if (match != null) {
                        matches.add(match);
                    }
                }
            }

            proc.waitFor();
            return matches;

        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return null;
        }
    }

    private GrepMatch parseRipgrepJsonLine(RuntimeContext rc, String jsonLine) {
        try {
            if (!jsonLine.contains("\"type\":\"match\"")) {
                return null;
            }
            String pathText = extractJsonStringField(jsonLine, "text", "path");
            String lineNumStr = extractJsonField(jsonLine, "line_number");
            String linesText = extractJsonStringField(jsonLine, "text", "lines");

            if (pathText == null || lineNumStr == null) {
                return null;
            }
            String filePath = resolveEntryPath(rc, Path.of(pathText));
            int lineNum = Integer.parseInt(lineNumStr.trim());
            String text = linesText != null ? linesText.replaceAll("[\r\n]+$", "") : "";
            return new GrepMatch(filePath, lineNum, text);
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractJsonStringField(String json, String field, String parentField) {
        String searchKey = "\"" + parentField + "\":{";
        int parentIdx = json.indexOf(searchKey);
        if (parentIdx < 0) {
            return extractSimpleJsonString(json, field);
        }
        String sub = json.substring(parentIdx + searchKey.length());
        return extractSimpleJsonString(sub, field);
    }

    private static String extractSimpleJsonString(String json, String field) {
        String key = "\"" + field + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) {
            return null;
        }
        start += key.length();
        int end = json.indexOf('"', start);
        if (end < 0) {
            return null;
        }
        return json.substring(start, end);
    }

    private static String extractJsonField(String json, String field) {
        String key = "\"" + field + "\":";
        int start = json.indexOf(key);
        if (start < 0) {
            return null;
        }
        start += key.length();
        int end = start;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(start, end).trim();
    }

    private List<GrepMatch> javaSearch(
            RuntimeContext rc, String pattern, Path basePath, String includeGlob) {
        Pattern compiledPattern = Pattern.compile(Pattern.quote(pattern));
        PathMatcher globMatcher = null;
        if (includeGlob != null && !includeGlob.isBlank()) {
            globMatcher = FileSystems.getDefault().getPathMatcher("glob:" + includeGlob);
        }

        List<GrepMatch> matches = new ArrayList<>();
        Path root = Files.isDirectory(basePath) ? basePath : basePath.getParent();

        try (Stream<Path> walk = Files.walk(root)) {
            PathMatcher finalGlobMatcher = globMatcher;
            walk.filter(Files::isRegularFile)
                    .filter(
                            p -> {
                                if (finalGlobMatcher != null) {
                                    return finalGlobMatcher.matches(p.getFileName());
                                }
                                return true;
                            })
                    .filter(
                            p -> {
                                try {
                                    return Files.size(p) <= maxFileSizeBytes;
                                } catch (IOException e) {
                                    return false;
                                }
                            })
                    .forEach(
                            file -> {
                                try {
                                    List<String> lines =
                                            Files.readAllLines(file, StandardCharsets.UTF_8);
                                    for (int i = 0; i < lines.size(); i++) {
                                        if (compiledPattern.matcher(lines.get(i)).find()) {
                                            String filePath = resolveEntryPath(rc, file);
                                            matches.add(
                                                    new GrepMatch(filePath, i + 1, lines.get(i)));
                                        }
                                    }
                                } catch (IOException e) {
                                    // Skip binary/unreadable files
                                }
                            });
        } catch (IOException e) {
            log.warn("Java search failed: {}", e.getMessage());
        }

        return matches;
    }
}
