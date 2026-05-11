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
import io.agentscope.harness.agent.filesystem.util.FilesystemUtils;
import io.agentscope.harness.agent.store.NamespaceFactory;
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
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link AbstractFilesystem} implementation that reads and writes files on the local disk.
 *
 * <p>When {@code virtualMode} is enabled, paths are anchored to {@code rootDir} and traversal is
 * blocked.
 */
public class LocalFilesystem implements AbstractFilesystem {

    private static final Logger log = LoggerFactory.getLogger(LocalFilesystem.class);

    private static final int DEFAULT_MAX_FILE_SIZE_MB = 10;

    private final Path cwd;
    private final boolean virtualMode;
    private final long maxFileSizeBytes;
    private final NamespaceFactory namespaceFactory;

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
     * Creates a abstract filesystem with explicit configuration and namespace support.
     *
     * <p>When a {@link NamespaceFactory} is provided, all paths are prefixed with the
     * namespace segments joined as subdirectories. For example, with namespace {@code ["user123"]},
     * a read of {@code "MEMORY.md"} resolves to {@code {rootDir}/user123/MEMORY.md}.
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
        this.cwd =
                rootDir != null
                        ? rootDir.toAbsolutePath().normalize()
                        : Path.of("").toAbsolutePath();
        this.virtualMode = virtualMode;
        this.maxFileSizeBytes = (long) maxFileSizeMb * 1024 * 1024;
        this.namespaceFactory = namespaceFactory;
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

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        Path dirPath = resolvePath(path);
        if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
            return LsResult.success(List.of());
        }

        List<FileInfo> results = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dirPath)) {
            for (Path entry : ds) {
                try {
                    BasicFileAttributes attrs =
                            Files.readAttributes(entry, BasicFileAttributes.class);
                    String entryPath =
                            virtualMode ? toVirtualPath(entry) : entry.toAbsolutePath().toString();
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
        Path resolved = resolvePath(filePath);

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
        Path resolved = resolvePath(filePath);

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
        Path resolved = resolvePath(filePath);

        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            return EditResult.fail("Error: File '" + filePath + "' not found");
        }

        try {
            String content = Files.readString(resolved, StandardCharsets.UTF_8);
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
        }
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        Path basePath;
        try {
            basePath = resolvePath(path != null ? path : ".");
        } catch (SecurityException e) {
            return GrepResult.success(List.of());
        }

        if (!Files.exists(basePath)) {
            return GrepResult.success(List.of());
        }

        List<GrepMatch> matches = ripgrepSearch(pattern, basePath, glob);
        if (matches == null) {
            matches = javaSearch(pattern, basePath, glob);
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
            searchPath = cwd;
        } else {
            searchPath = resolvePath(path);
        }

        if (!Files.exists(searchPath) || !Files.isDirectory(searchPath)) {
            return GlobResult.success(List.of());
        }

        String globExpr =
                effectivePattern.startsWith("**") ? effectivePattern : "**/" + effectivePattern;
        FileSystem fs = FileSystems.getDefault();
        PathMatcher matcher = fs.getPathMatcher("glob:" + globExpr);

        List<FileInfo> results = new ArrayList<>();
        try {
            Files.walkFileTree(
                    searchPath,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            Path rel = searchPath.relativize(file);
                            if (matcher.matches(rel)) {
                                String filePath =
                                        virtualMode
                                                ? toVirtualPath(file)
                                                : file.toAbsolutePath().toString();
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
                Path resolved = resolvePath(filePath);
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
                Path resolved = resolvePath(filePath);
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
        Path resolved = resolvePath(path);
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
        Path from = resolvePath(fromPath);
        Path to = resolvePath(toPath);
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
            return Files.exists(resolvePath(path));
        } catch (SecurityException e) {
            return false;
        }
    }

    // ==================== Path resolution ====================

    protected NamespaceFactory getNamespaceFactory() {
        return namespaceFactory;
    }

    protected Path resolvePath(String key) {
        String effectiveKey = applyNamespacePrefix(key);
        if (effectiveKey == null || effectiveKey.isBlank()) {
            return cwd;
        }

        if (virtualMode) {
            String vpath = effectiveKey.startsWith("/") ? effectiveKey : "/" + effectiveKey;
            if (vpath.contains("..") || vpath.startsWith("~")) {
                throw new SecurityException("Path traversal not allowed");
            }
            Path full = cwd.resolve(vpath.substring(1)).normalize();
            if (!full.startsWith(cwd)) {
                throw new SecurityException("Path " + full + " outside root directory: " + cwd);
            }
            return full;
        }

        Path target = Path.of(effectiveKey);
        if (target.isAbsolute()) {
            return target;
        }
        return cwd.resolve(target).normalize();
    }

    private String applyNamespacePrefix(String key) {
        if (namespaceFactory == null || key == null || key.isBlank()) {
            return key;
        }
        List<String> ns = namespaceFactory.getNamespace();
        if (ns == null || ns.isEmpty()) {
            return key;
        }
        String prefix = String.join("/", ns);
        return prefix + "/" + key;
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

    // ==================== Grep implementations ====================

    private List<GrepMatch> ripgrepSearch(String pattern, Path basePath, String includeGlob) {
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
                    GrepMatch match = parseRipgrepJsonLine(line);
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

    private GrepMatch parseRipgrepJsonLine(String jsonLine) {
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
            String filePath = virtualMode ? toVirtualPath(Path.of(pathText)) : pathText;
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

    private List<GrepMatch> javaSearch(String pattern, Path basePath, String includeGlob) {
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
                                            String filePath =
                                                    virtualMode
                                                            ? toVirtualPath(file)
                                                            : file.toAbsolutePath().toString();
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
