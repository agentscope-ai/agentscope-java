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
package io.agentscope.builder.web.managed;

import io.agentscope.builder.web.managed.service.MemoryStoreService;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.EditResult;
import io.agentscope.harness.agent.filesystem.model.FileDownloadResponse;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.FileUploadResponse;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@link AbstractFilesystem} view over a single {@link MemoryStoreService} store, mounted under
 * {@code memory-stores/{storeName}/} via {@link
 * io.agentscope.harness.agent.HarnessAgent.Builder#filesystemRoute}.
 *
 * <p>Paths are relative to the store root (e.g. {@code notes.md} or {@code /notes.md} both
 * address the same memory document). Writes/edits create a new versioned {@code
 * MemoryVersionEntity} via {@link MemoryStoreService#putMemory}; reads/listing go against the
 * live JPA-backed documents.
 *
 * <p>This is a pragmatic, flat-namespace implementation: the store has no real subdirectories, so
 * {@code ls}/{@code grep}/{@code glob} operate over the full set of memory paths rather than
 * honoring hierarchical directory semantics. Upload/download of binary content is not supported
 * — memory documents are plain-text only.
 */
public final class MemoryStoreFilesystem implements AbstractFilesystem {

    private final MemoryStoreService memoryStoreService;
    private final String ownerId;
    private final String storeId;
    private final String storeName;

    public MemoryStoreFilesystem(
            MemoryStoreService memoryStoreService,
            String ownerId,
            String storeId,
            String storeName) {
        this(memoryStoreService, ownerId, storeId, storeName, "read_write");
    }

    public MemoryStoreFilesystem(
            MemoryStoreService memoryStoreService,
            String ownerId,
            String storeId,
            String storeName,
            String accessMode) {
        this.memoryStoreService = memoryStoreService;
        this.ownerId = ownerId;
        this.storeId = storeId;
        this.storeName = storeName;
        this.accessMode =
                accessMode == null || accessMode.isBlank() ? "read_write" : accessMode.trim();
    }

    private final String accessMode;

    public String storeId() {
        return storeId;
    }

    public String storeName() {
        return storeName;
    }

    public String accessMode() {
        return accessMode;
    }

    public boolean isReadOnly() {
        return "read_only".equalsIgnoreCase(accessMode);
    }

    /** Route prefix under which a memory store filesystem should be mounted. */
    public static String routePrefix(String storeName) {
        return "memory-stores/" + sanitize(storeName) + "/";
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "store";
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static String normalize(String path) {
        if (path == null) {
            return "";
        }
        String p = path.trim();
        while (p.startsWith("/")) {
            p = p.substring(1);
        }
        return p;
    }

    @Override
    public LsResult ls(RuntimeContext runtimeContext, String path) {
        try {
            List<FileInfo> infos = new ArrayList<>();
            for (MemoryDto memory : memoryStoreService.listMemories(ownerId, storeId)) {
                infos.add(toFileInfo(memory));
            }
            return LsResult.success(infos);
        } catch (ResponseStatusException e) {
            return LsResult.fail(e.getReason());
        }
    }

    @Override
    public ReadResult read(RuntimeContext runtimeContext, String filePath, int offset, int limit) {
        String key = normalize(filePath);
        if (key.isEmpty()) {
            return ReadResult.fail("Path must reference a memory document, not the store root");
        }
        try {
            MemoryDto memory = memoryStoreService.getMemory(ownerId, storeId, key);
            String content = memory.content() == null ? "" : memory.content();
            if (offset > 0 || limit > 0) {
                content = paginate(content, offset, limit);
            }
            return ReadResult.success(
                    new io.agentscope.harness.agent.filesystem.model.FileData(
                            content,
                            "utf-8",
                            Instant.ofEpochMilli(memory.createdAt()).toString(),
                            Instant.ofEpochMilli(memory.updatedAt()).toString()));
        } catch (ResponseStatusException e) {
            return ReadResult.fail("Memory not found: " + filePath);
        }
    }

    private static String paginate(String content, int offset, int limit) {
        String[] lines = content.split("\n", -1);
        int start = Math.max(0, offset);
        if (start >= lines.length) {
            return "";
        }
        int end = limit > 0 ? Math.min(lines.length, start + limit) : lines.length;
        return String.join("\n", java.util.Arrays.asList(lines).subList(start, end));
    }

    @Override
    public WriteResult write(RuntimeContext runtimeContext, String filePath, String content) {
        if (isReadOnly()) {
            return WriteResult.fail("Memory store is mounted read_only");
        }
        String key = normalize(filePath);
        if (key.isEmpty()) {
            return WriteResult.fail("Path must reference a memory document, not the store root");
        }
        boolean exists = memoryExists(key);
        if (exists) {
            return WriteResult.fail(
                    "Cannot write to "
                            + filePath
                            + " because it already exists. Read and then make an edit, or write"
                            + " to a new path.");
        }
        try {
            memoryStoreService.putMemory(
                    ownerId, storeId, key, new MemoryStoreService.PutMemoryRequest(content));
            return WriteResult.ok(filePath);
        } catch (ResponseStatusException e) {
            return WriteResult.fail("Error writing memory '" + filePath + "': " + e.getReason());
        }
    }

    @Override
    public EditResult edit(
            RuntimeContext runtimeContext,
            String filePath,
            String oldString,
            String newString,
            boolean replaceAll) {
        if (isReadOnly()) {
            return EditResult.fail("Memory store is mounted read_only");
        }
        String key = normalize(filePath);
        if (key.isEmpty()) {
            return EditResult.fail("Path must reference a memory document, not the store root");
        }
        MemoryDto memory;
        try {
            memory = memoryStoreService.getMemory(ownerId, storeId, key);
        } catch (ResponseStatusException e) {
            return EditResult.fail("Memory not found: " + filePath);
        }
        String content = memory.content() == null ? "" : memory.content();
        int occurrences = countOccurrences(content, oldString);
        if (occurrences == 0) {
            return EditResult.fail("oldString not found in " + filePath);
        }
        if (!replaceAll && occurrences > 1) {
            return EditResult.fail(
                    "oldString matches "
                            + occurrences
                            + " times in "
                            + filePath
                            + "; use replaceAll or a more specific match");
        }
        String updated =
                replaceAll
                        ? content.replace(oldString, newString)
                        : content.replaceFirst(java.util.regex.Pattern.quote(oldString), newString);
        memoryStoreService.putMemory(
                ownerId, storeId, key, new MemoryStoreService.PutMemoryRequest(updated));
        return EditResult.ok(filePath, replaceAll ? occurrences : 1);
    }

    private static int countOccurrences(String content, String needle) {
        if (needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int idx = 0;
        while ((idx = content.indexOf(needle, idx)) != -1) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    @Override
    public GrepResult grep(
            RuntimeContext runtimeContext, String pattern, String path, String glob) {
        List<GrepMatch> matches = new ArrayList<>();
        PathMatcher globMatcher = compileGlob(glob);
        try {
            for (MemoryDto memory : memoryStoreService.listMemories(ownerId, storeId)) {
                if (globMatcher != null && !globMatcher.matches(Path.of(memory.path()))) {
                    continue;
                }
                String content = memory.content() == null ? "" : memory.content();
                String[] lines = content.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    if (lines[i].contains(pattern)) {
                        matches.add(new GrepMatch("/" + memory.path(), i + 1, lines[i]));
                    }
                }
            }
            return GrepResult.success(matches);
        } catch (ResponseStatusException e) {
            return GrepResult.fail(e.getReason());
        }
    }

    @Override
    public GlobResult glob(RuntimeContext runtimeContext, String pattern, String path) {
        PathMatcher matcher = compileGlob(pattern);
        try {
            List<FileInfo> matches = new ArrayList<>();
            for (MemoryDto memory : memoryStoreService.listMemories(ownerId, storeId)) {
                if (matcher == null || matcher.matches(Path.of(memory.path()))) {
                    matches.add(toFileInfo(memory));
                }
            }
            return GlobResult.success(matches);
        } catch (ResponseStatusException e) {
            return GlobResult.fail(e.getReason());
        }
    }

    private static PathMatcher compileGlob(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        String effective = pattern.startsWith("/") ? pattern.substring(1) : pattern;
        try {
            return FileSystems.getDefault().getPathMatcher("glob:" + effective);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<FileUploadResponse> uploadFiles(
            RuntimeContext runtimeContext, List<Map.Entry<String, byte[]>> files) {
        List<FileUploadResponse> results = new ArrayList<>();
        for (Map.Entry<String, byte[]> file : files) {
            results.add(
                    FileUploadResponse.fail(
                            file.getKey(),
                            "Binary upload is not supported for memory-store filesystems"));
        }
        return results;
    }

    @Override
    public List<FileDownloadResponse> downloadFiles(
            RuntimeContext runtimeContext, List<String> paths) {
        List<FileDownloadResponse> results = new ArrayList<>();
        for (String path : paths) {
            results.add(
                    FileDownloadResponse.fail(
                            path, "Binary download is not supported for memory-store filesystems"));
        }
        return results;
    }

    @Override
    public WriteResult delete(RuntimeContext runtimeContext, String path) {
        String key = normalize(path);
        if (key.isEmpty()) {
            return WriteResult.fail("Path must reference a memory document, not the store root");
        }
        try {
            memoryStoreService.deleteMemory(ownerId, storeId, key);
            return WriteResult.ok(path);
        } catch (ResponseStatusException e) {
            if (e.getStatusCode().value() == 404) {
                return WriteResult.ok(path);
            }
            return WriteResult.fail("Error deleting memory '" + path + "': " + e.getReason());
        }
    }

    @Override
    public WriteResult move(RuntimeContext runtimeContext, String fromPath, String toPath) {
        String fromKey = normalize(fromPath);
        String toKey = normalize(toPath);
        if (fromKey.isEmpty() || toKey.isEmpty()) {
            return WriteResult.fail("Path must reference a memory document, not the store root");
        }
        MemoryDto memory;
        try {
            memory = memoryStoreService.getMemory(ownerId, storeId, fromKey);
        } catch (ResponseStatusException e) {
            return WriteResult.fail("Cannot read source for move: " + fromPath);
        }
        memoryStoreService.putMemory(
                ownerId, storeId, toKey, new MemoryStoreService.PutMemoryRequest(memory.content()));
        memoryStoreService.deleteMemory(ownerId, storeId, fromKey);
        return WriteResult.ok(toPath);
    }

    @Override
    public boolean exists(RuntimeContext runtimeContext, String path) {
        String key = normalize(path);
        return !key.isEmpty() && memoryExists(key);
    }

    private boolean memoryExists(String key) {
        try {
            memoryStoreService.getMemory(ownerId, storeId, key);
            return true;
        } catch (ResponseStatusException e) {
            return false;
        }
    }

    private static FileInfo toFileInfo(MemoryDto memory) {
        long size = memory.content() == null ? 0 : memory.content().length();
        return FileInfo.ofFile("/" + memory.path(), size, memory.updatedAt());
    }
}
