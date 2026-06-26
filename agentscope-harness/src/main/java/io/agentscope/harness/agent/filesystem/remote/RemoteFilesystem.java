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
package io.agentscope.harness.agent.filesystem.remote;

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
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.NamespaceFactory;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.filesystem.util.FilesystemUtils;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link AbstractFilesystem} backed by a {@link BaseStore} (persistent, cross-thread).
 *
 * <p>Files are organized via namespaces and persist across threads/sessions. The namespace can be
 * static (fixed at construction time) or dynamic (resolved at every operation via a {@link
 * NamespaceFactory}).
 *
 * <p>Dynamic namespace example:
 *
 * <pre>{@code
 * RemoteFilesystem fs = new RemoteFilesystem(store,
 *     () -> List.of("sessions", sessionIdSupplier.get(), "filesystem"));
 * }</pre>
 */
public class RemoteFilesystem implements AbstractFilesystem {

    private final BaseStore store;
    private final NamespaceFactory namespaceFactory;

    /**
     * Optional best-effort local index. When non-null, {@code ls}, {@code glob}, {@code exists}
     * and {@code grep} consult the index first and fall back to the remote store only when the
     * index has no matching entries.
     */
    private WorkspaceIndex index;

    /**
     * Creates a RemoteFilesystem with a {@link NamespaceFactory} that is called on every operation,
     * allowing the namespace to vary based on runtime context.
     *
     * @param store the store to use for persistence
     * @param namespaceFactory factory that returns the namespace tuple per operation
     */
    public RemoteFilesystem(BaseStore store, NamespaceFactory namespaceFactory) {
        if (store == null) {
            throw new IllegalArgumentException("store must not be null");
        }
        if (namespaceFactory == null) {
            throw new IllegalArgumentException("namespaceFactory must not be null");
        }
        this.store = store;
        this.namespaceFactory = namespaceFactory;
    }

    /**
     * Creates a RemoteFilesystem with a fixed namespace.
     *
     * @param store the store to use for persistence
     * @param namespace the namespace tuple for organizing files
     */
    public RemoteFilesystem(BaseStore store, List<String> namespace) {
        this(store, toFactory(namespace));
    }

    /**
     * Creates a RemoteFilesystem with a default "filesystem" namespace.
     *
     * @param store the store to use for persistence
     */
    public RemoteFilesystem(BaseStore store) {
        this(store, List.of("filesystem"));
    }

    /**
     * Attaches a best-effort {@link WorkspaceIndex} to accelerate list/glob/exists/grep
     * operations. The index may be {@code null} (disabled) or become stale.
     *
     * <p>Fallback semantics differ per operation:
     *
     * <ul>
     *   <li>{@code ls}, {@code glob}, {@code exists} — when the index has no matching prefix
     *       the operation falls back to a full remote-store scan, so results remain correct
     *       even if this node's index is stale.
     *   <li>{@code grep} — when the index is non-{@code null} the operation first enumerates
     *       candidates from the index. If the index path yields zero matches, the operation
     *       falls back to a full remote-store scan so that sibling-node writes not yet seen by
     *       this node's index are not silently missed. Content for each candidate is always
     *       fetched authoritatively from the remote store.
     * </ul>
     *
     * @param index workspace index; {@code null} disables index-backed fast paths
     * @return this instance (fluent)
     */
    public RemoteFilesystem withIndex(WorkspaceIndex index) {
        this.index = index;
        return this;
    }

    private static NamespaceFactory toFactory(List<String> namespace) {
        if (namespace == null || namespace.isEmpty()) {
            throw new IllegalArgumentException("namespace must not be empty");
        }
        List<String> frozen = List.copyOf(namespace);
        return rc -> frozen;
    }

    private List<String> getNamespace(RuntimeContext rc) {
        List<String> ns = namespaceFactory.getNamespace(rc);
        if (ns == null || ns.isEmpty()) {
            throw new IllegalStateException("NamespaceFactory returned null or empty namespace");
        }
        return ns;
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        String normalizedPath = path.endsWith("/") ? path : path + "/";

        // Fast path: index has entries for this prefix
        if (index != null && index.hasPrefix(normalizedPath)) {
            List<String> indexPaths = index.listByPrefix(normalizedPath);
            List<FileInfo> infos = new ArrayList<>();
            Set<String> subdirs = new LinkedHashSet<>();
            for (String p : indexPaths) {
                String relative = p.substring(normalizedPath.length());
                if (relative.contains("/")) {
                    String subdirName = relative.substring(0, relative.indexOf('/'));
                    subdirs.add(normalizedPath + subdirName + "/");
                } else {
                    infos.add(FileInfo.ofFile(p, 0, ""));
                }
            }
            for (String subdir : subdirs) {
                infos.add(FileInfo.ofDir(subdir, ""));
            }
            infos.sort(Comparator.comparing(FileInfo::path));
            return LsResult.success(infos);
        }

        // Fallback: full remote scan
        List<StoreItem> items = searchAllItems(runtimeContext);
        List<FileInfo> infos = new ArrayList<>();
        Set<String> subdirs = new LinkedHashSet<>();

        for (StoreItem item : items) {
            if (!item.key().startsWith(normalizedPath)) {
                continue;
            }

            String relative = item.key().substring(normalizedPath.length());

            if (relative.contains("/")) {
                String subdirName = relative.substring(0, relative.indexOf('/'));
                subdirs.add(normalizedPath + subdirName + "/");
                continue;
            }

            FileData fd = convertItemToFileData(item);
            if (fd == null) {
                continue;
            }
            int size = fd.content() != null ? fd.content().length() : 0;
            infos.add(
                    FileInfo.ofFile(
                            item.key(), size, fd.modifiedAt() != null ? fd.modifiedAt() : ""));
        }

        for (String subdir : subdirs) {
            infos.add(FileInfo.ofDir(subdir, ""));
        }

        infos.sort(Comparator.comparing(FileInfo::path));
        return LsResult.success(infos);
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        StoreItem item = store.get(getNamespace(runtimeContext), filePath);
        if (item == null) {
            return ReadResult.fail("File '" + filePath + "' not found");
        }

        FileData fileData = convertItemToFileData(item);
        if (fileData == null) {
            return ReadResult.fail("Invalid file data for '" + filePath + "'");
        }

        if (!"text".equals(FilesystemUtils.getFileType(filePath))) {
            return ReadResult.success(fileData);
        }

        String content = fileData.content();
        if (content == null || content.isBlank()) {
            return ReadResult.success(fileData);
        }

        String[] lines = content.split("\n", -1);
        int startIdx = Math.max(0, offset);
        int endIdx = limit > 0 ? Math.min(startIdx + limit, lines.length) : lines.length;

        if (startIdx >= lines.length) {
            return ReadResult.fail(
                    "Line offset " + offset + " exceeds file length (" + lines.length + " lines)");
        }

        StringBuilder sb = new StringBuilder();
        for (int i = startIdx; i < endIdx; i++) {
            if (i > startIdx) {
                sb.append('\n');
            }
            sb.append(lines[i]);
        }

        return ReadResult.success(
                new FileData(
                        sb.toString(),
                        fileData.encoding(),
                        fileData.createdAt(),
                        fileData.modifiedAt()));
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        List<String> ns = getNamespace(runtimeContext);
        FileData fileData = FileData.create(content);
        // CAS create-if-absent: atomic under concurrent writers across nodes.
        boolean written = store.putIfVersion(ns, filePath, fileDataToStoreValue(fileData), 0L);
        if (!written) {
            return WriteResult.fail(
                    "Cannot write to "
                            + filePath
                            + " because it already exists. Read and then make an edit,"
                            + " or write to a new path.");
        }
        return WriteResult.ok(filePath);
    }

    /** Maximum CAS retry attempts for {@link #edit} under concurrent writers. */
    static final int EDIT_MAX_RETRIES = 5;

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        List<String> ns = getNamespace(runtimeContext);
        // Bounded CAS retry loop: re-read the current version on each attempt and retry on
        // version mismatch. After EDIT_MAX_RETRIES failed CAS attempts we surface a conflict
        // error rather than spinning indefinitely.
        for (int attempt = 0; attempt < EDIT_MAX_RETRIES; attempt++) {
            StoreItem item = store.get(ns, filePath);
            if (item == null) {
                return EditResult.fail("Error: File '" + filePath + "' not found");
            }

            FileData fileData = convertItemToFileData(item);
            if (fileData == null) {
                return EditResult.fail("Error: Invalid file data");
            }

            String content = fileData.content() != null ? fileData.content() : "";
            Object[] result =
                    FilesystemUtils.performStringReplacement(
                            content, oldString, newString, replaceAll);

            if (result.length == 1) {
                return EditResult.fail((String) result[0]);
            }

            String newContent = (String) result[0];
            int occurrences = (int) result[1];

            FileData updated = fileData.withContent(newContent);
            boolean ok =
                    store.putIfVersion(ns, filePath, fileDataToStoreValue(updated), item.version());
            if (ok) {
                return EditResult.ok(filePath, occurrences);
            }
            // Version mismatch — another writer raced us. Re-read and retry.
        }
        return EditResult.fail(
                "Edit conflict on '"
                        + filePath
                        + "' after "
                        + EDIT_MAX_RETRIES
                        + " retries. Another writer is concurrently modifying this file.");
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        String normalizedPath = normalizePath(path);

        PathMatcher globMatcher = null;
        if (glob != null && !glob.isBlank()) {
            globMatcher = FileSystems.getDefault().getPathMatcher("glob:" + glob);
        }

        // Fast path: use the index to enumerate candidate paths under {@code normalizedPath},
        // then fetch each file's content from the remote store and scan it line-by-line. The
        // index only narrows the candidate set; content is always read authoritatively from
        // the store (never from local disk), so stale/missing local copies cannot mask matches.
        if (index != null) {
            List<String> candidates =
                    "/".equals(normalizedPath)
                            ? index.listByPrefix("")
                            : index.listByPrefix(normalizedPath);
            List<GrepMatch> matches = new ArrayList<>();
            for (String key : candidates) {
                if (!matchesPathPrefix(key, normalizedPath)) {
                    continue;
                }
                if (globMatcher != null) {
                    String fileName =
                            key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
                    if (!globMatcher.matches(java.nio.file.Path.of(fileName))) {
                        continue;
                    }
                }
                StoreItem item = store.get(getNamespace(runtimeContext), key);
                if (item == null) {
                    continue;
                }
                FileData fd = convertItemToFileData(item);
                if (fd == null || fd.content() == null) {
                    continue;
                }
                String[] lines = fd.content().split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].contains(pattern)) {
                        matches.add(new GrepMatch(key, i + 1, lines[i]));
                    }
                }
            }
            // Index returned matches — return them. Otherwise fall through to the authoritative
            // store scan: on a multi-node deployment, this node's index may not yet know about
            // files written via another node, and we must not silently miss them.
            if (!matches.isEmpty()) {
                return GrepResult.success(matches);
            }
        }

        // Fallback: full remote scan
        List<StoreItem> items = searchAllItems(runtimeContext);
        List<GrepMatch> matches = new ArrayList<>();
        for (StoreItem item : items) {
            String key = item.key();

            if (!matchesPathPrefix(key, normalizedPath)) {
                continue;
            }

            if (globMatcher != null) {
                String fileName = key.contains("/") ? key.substring(key.lastIndexOf('/') + 1) : key;
                if (!globMatcher.matches(java.nio.file.Path.of(fileName))) {
                    continue;
                }
            }

            FileData fd = convertItemToFileData(item);
            if (fd == null || fd.content() == null) {
                continue;
            }

            String[] lines = fd.content().split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].contains(pattern)) {
                    matches.add(new GrepMatch(key, i + 1, lines[i]));
                }
            }
        }

        return GrepResult.success(matches);
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        String normalizedPath = normalizePath(path);
        String effectivePattern = pattern.startsWith("/") ? pattern.substring(1) : pattern;

        PathMatcher matcher =
                FileSystems.getDefault()
                        .getPathMatcher(
                                "glob:"
                                        + (effectivePattern.startsWith("**")
                                                ? effectivePattern
                                                : "**/" + effectivePattern));
        PathMatcher directMatcher =
                FileSystems.getDefault().getPathMatcher("glob:" + effectivePattern);

        // Fast path: index has entries for this prefix
        if (index != null && index.hasPrefix(normalizedPath)) {
            List<String> candidates = index.listByPrefix(normalizedPath);
            List<FileInfo> results = new ArrayList<>();
            for (String key : candidates) {
                if (!matchesPathPrefix(key, normalizedPath)) {
                    continue;
                }
                String relativePath;
                if ("/".equals(normalizedPath)) {
                    relativePath = key.startsWith("/") ? key.substring(1) : key;
                } else {
                    relativePath = key.substring(normalizedPath.length() + 1);
                }
                if (matcher.matches(Path.of(relativePath))
                        || directMatcher.matches(Path.of(relativePath))) {
                    results.add(FileInfo.ofFile(key, 0, ""));
                }
            }
            results.sort(Comparator.comparing(FileInfo::path));
            return GlobResult.success(results);
        }

        // Fallback: full remote scan
        List<StoreItem> items = searchAllItems(runtimeContext);
        List<FileInfo> results = new ArrayList<>();
        for (StoreItem item : items) {
            String key = item.key();
            if (!matchesPathPrefix(key, normalizedPath)) {
                continue;
            }

            String relativePath;
            if ("/".equals(normalizedPath)) {
                relativePath = key.startsWith("/") ? key.substring(1) : key;
            } else {
                relativePath = key.substring(normalizedPath.length() + 1);
            }

            if (matcher.matches(java.nio.file.Path.of(relativePath))
                    || directMatcher.matches(java.nio.file.Path.of(relativePath))) {
                FileData fd = convertItemToFileData(item);
                int size = (fd != null && fd.content() != null) ? fd.content().length() : 0;
                String modifiedAt = (fd != null && fd.modifiedAt() != null) ? fd.modifiedAt() : "";
                results.add(FileInfo.ofFile(key, size, modifiedAt));
            }
        }

        results.sort(Comparator.comparing(FileInfo::path));
        return GlobResult.success(results);
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        List<String> ns = getNamespace(runtimeContext);
        List<FileUploadResponse> responses = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : files) {
            String filePath = entry.getKey();
            byte[] content = entry.getValue();

            String contentStr;
            String encoding;
            try {
                contentStr = new String(content, StandardCharsets.UTF_8);
                encoding = "utf-8";
            } catch (Exception e) {
                contentStr = Base64.getEncoder().encodeToString(content);
                encoding = "base64";
            }

            FileData fileData = FileData.create(contentStr, encoding);
            // Last-write-wins: uploadFiles is the snapshot-push API (session mirror, audit-log
            // rotation, WorkspaceManager.writeUtf8WorkspaceRelative). Callers needing
            // create-if-absent semantics should use {@link #write} instead, which preserves
            // CAS-create-if-absent for the tool-surface write path.
            store.put(ns, filePath, fileDataToStoreValue(fileData));
            responses.add(FileUploadResponse.success(filePath));
        }
        return responses;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        List<String> ns = getNamespace(runtimeContext);
        List<FileDownloadResponse> responses = new ArrayList<>();
        for (String filePath : paths) {
            StoreItem item = store.get(ns, filePath);
            if (item == null) {
                responses.add(FileDownloadResponse.fail(filePath, "file_not_found"));
                continue;
            }

            FileData fd = convertItemToFileData(item);
            if (fd == null || fd.content() == null) {
                responses.add(FileDownloadResponse.fail(filePath, "invalid file data"));
                continue;
            }

            byte[] contentBytes;
            if ("base64".equals(fd.encoding())) {
                contentBytes = Base64.getMimeDecoder().decode(fd.content());
            } else {
                contentBytes = fd.content().getBytes(StandardCharsets.UTF_8);
            }
            responses.add(FileDownloadResponse.success(filePath, contentBytes));
        }
        return responses;
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String normalized = normalizePath(path);

        // Fast path: consult index first
        if (index != null) {
            if (index.exists(normalized)) {
                return true;
            }
            if (index.hasPrefix(normalized + "/")) {
                return true;
            }
            // Index miss — fall through to remote (may not be indexed yet)
        }

        List<String> ns = getNamespace(runtimeContext);
        if (store.get(ns, normalized) != null) {
            return true;
        }
        // Also check if any child exists (directory-like)
        List<StoreItem> items = searchAllItems(runtimeContext);
        for (StoreItem item : items) {
            if (item.key().startsWith(normalized + "/")) {
                return true;
            }
        }
        return false;
    }

    // Also update index on delete/move so entries don't linger
    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        AbstractFilesystem.validatePath(path);
        List<String> ns = getNamespace(runtimeContext);
        List<StoreItem> items = searchAllItems(runtimeContext);
        String normalizedPath = normalizePath(path);
        for (StoreItem item : items) {
            if (item.key().equals(normalizedPath) || item.key().startsWith(normalizedPath + "/")) {
                store.delete(ns, item.key());
                if (index != null) {
                    index.remove(item.key());
                }
            }
        }
        // idempotent — not found is still success
        return WriteResult.ok(path);
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        AbstractFilesystem.validatePath(fromPath);
        AbstractFilesystem.validatePath(toPath);
        List<String> ns = getNamespace(runtimeContext);
        List<StoreItem> items = searchAllItems(runtimeContext);
        String normFrom = normalizePath(fromPath);
        String normTo = normalizePath(toPath);
        boolean found = false;
        for (StoreItem item : items) {
            String key = item.key();
            if (key.equals(normFrom) || key.startsWith(normFrom + "/")) {
                String newKey = normTo + key.substring(normFrom.length());
                store.put(ns, newKey, item.value());
                store.delete(ns, key);
                if (index != null) {
                    index.rename(key, newKey);
                }
                found = true;
            }
        }
        if (!found) {
            return WriteResult.fail("Source does not exist: " + fromPath);
        }
        return WriteResult.ok(toPath);
    }

    // ==================== Internal helpers ====================

    private List<StoreItem> searchAllItems(RuntimeContext runtimeContext) {
        List<String> ns = getNamespace(runtimeContext);
        List<StoreItem> all = new ArrayList<>();
        int offset = 0;
        int pageSize = 100;
        while (true) {
            List<StoreItem> page = store.search(ns, pageSize, offset);
            if (page.isEmpty()) {
                break;
            }
            for (StoreItem item : page) {
                all.add(normalizeItemKey(item));
            }
            if (page.size() < pageSize) {
                break;
            }
            offset += pageSize;
        }
        return all;
    }

    private static StoreItem normalizeItemKey(StoreItem item) {
        String key = item.key();
        if (key != null && !key.startsWith("/")) {
            return new StoreItem("/" + key, item.value(), item.version());
        }
        return item;
    }

    private static FileData convertItemToFileData(StoreItem item) {
        if (item == null || item.value() == null) {
            return null;
        }
        Map<String, Object> value = item.value();
        Object contentObj = value.get("content");
        if (contentObj == null) {
            return null;
        }

        String content;
        if (contentObj instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    sb.append('\n');
                }
                sb.append(list.get(i));
            }
            content = sb.toString();
        } else if (contentObj instanceof String s) {
            content = s;
        } else {
            return null;
        }

        String encoding =
                value.containsKey("encoding") ? String.valueOf(value.get("encoding")) : "utf-8";
        String createdAt =
                value.containsKey("created_at") ? String.valueOf(value.get("created_at")) : null;
        String modifiedAt =
                value.containsKey("modified_at") ? String.valueOf(value.get("modified_at")) : null;

        return new FileData(content, encoding, createdAt, modifiedAt);
    }

    private static Map<String, Object> fileDataToStoreValue(FileData fd) {
        Map<String, Object> result = new HashMap<>();
        result.put("content", fd.content());
        result.put("encoding", fd.encoding());
        if (fd.createdAt() != null) {
            result.put("created_at", fd.createdAt());
        }
        if (fd.modifiedAt() != null) {
            result.put("modified_at", fd.modifiedAt());
        }
        return result;
    }

    private static String normalizePath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        String normalized = path.startsWith("/") ? path : "/" + path;
        if (!"/".equals(normalized) && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static boolean matchesPathPrefix(String key, String normalizedPath) {
        if ("/".equals(normalizedPath)) {
            return key.startsWith("/");
        }
        return key.equals(normalizedPath) || key.startsWith(normalizedPath + "/");
    }
}
