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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Materializes session-bound memory stores into {@code workspace/memory/{storeName}/} and
 * writes filesystem changes back as versioned memory documents after a turn.
 */
@Service
public class MemoryMountService {

    private static final Logger log = LoggerFactory.getLogger(MemoryMountService.class);
    public static final String MEMORY_ROOT = "memory";

    private final MemoryStoreService memoryStoreService;

    public MemoryMountService(MemoryStoreService memoryStoreService) {
        this.memoryStoreService = memoryStoreService;
    }

    /**
     * Writes each memory document into the workspace mount directory. Returns mount descriptions
     * suitable for system-prompt injection.
     */
    public List<MountInfo> materialize(
            String ownerId, Path workspace, List<String> memoryStoreIds) {
        List<MountInfo> mounts = new ArrayList<>();
        if (memoryStoreIds == null || memoryStoreIds.isEmpty() || workspace == null) {
            return mounts;
        }
        for (String storeId : memoryStoreIds) {
            try {
                MemoryStoreDto store = memoryStoreService.getStore(ownerId, storeId);
                String safeName = sanitize(store.name());
                Path mountRoot = workspace.resolve(MEMORY_ROOT).resolve(safeName);
                Files.createDirectories(mountRoot);
                for (MemoryDto memory : memoryStoreService.listMemories(ownerId, storeId)) {
                    Path file = resolveSafe(mountRoot, memory.path());
                    Files.createDirectories(file.getParent());
                    String content = memory.content() == null ? "" : memory.content();
                    Files.writeString(file, content, StandardCharsets.UTF_8);
                }
                mounts.add(new MountInfo(storeId, store.name(), MEMORY_ROOT + "/" + safeName));
            } catch (Exception ex) {
                log.warn("Failed to materialize memory store {}: {}", storeId, ex.getMessage());
            }
        }
        return mounts;
    }

    /** Builds a system-prompt appendix describing mounted memory directories. */
    public String promptAppendix(List<MountInfo> mounts) {
        if (mounts == null || mounts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Mounted Memory Stores\n");
        sb.append(
                "The following directories contain persistent cross-session memory. Read and update"
                        + " files under these paths; changes are versioned after each turn.\n");
        for (MountInfo mount : mounts) {
            sb.append("- `")
                    .append(mount.relativePath())
                    .append("/` — store \"")
                    .append(mount.storeName())
                    .append("\"\n");
        }
        return sb.toString();
    }

    /**
     * Walks mounted directories and persists file contents back into the memory store (versioned).
     */
    public void writeback(String ownerId, Path workspace, List<String> memoryStoreIds) {
        if (memoryStoreIds == null || memoryStoreIds.isEmpty() || workspace == null) {
            return;
        }
        for (String storeId : memoryStoreIds) {
            try {
                MemoryStoreDto store = memoryStoreService.getStore(ownerId, storeId);
                Path mountRoot = workspace.resolve(MEMORY_ROOT).resolve(sanitize(store.name()));
                if (!Files.isDirectory(mountRoot)) {
                    continue;
                }
                Map<String, String> files = new LinkedHashMap<>();
                Files.walkFileTree(
                        mountRoot,
                        new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                    throws IOException {
                                if (attrs.isRegularFile()) {
                                    String rel =
                                            mountRoot
                                                    .relativize(file)
                                                    .toString()
                                                    .replace('\\', '/');
                                    files.put(rel, Files.readString(file, StandardCharsets.UTF_8));
                                }
                                return FileVisitResult.CONTINUE;
                            }
                        });
                for (Map.Entry<String, String> entry : files.entrySet()) {
                    String existing = null;
                    try {
                        existing =
                                memoryStoreService
                                        .getMemory(ownerId, storeId, entry.getKey())
                                        .content();
                    } catch (Exception ignored) {
                        // new file
                    }
                    if (existing == null || !existing.equals(entry.getValue())) {
                        memoryStoreService.putMemory(
                                ownerId,
                                storeId,
                                entry.getKey(),
                                new MemoryStoreService.PutMemoryRequest(entry.getValue()));
                    }
                }
            } catch (Exception ex) {
                log.warn("Memory writeback failed for store {}: {}", storeId, ex.getMessage());
            }
        }
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "store";
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private static Path resolveSafe(Path root, String relative) throws IOException {
        String rel = relative == null || relative.isBlank() ? "MEMORY.md" : relative;
        Path resolved = root.resolve(rel).normalize();
        if (!resolved.startsWith(root.normalize())) {
            throw new IOException("Path escapes memory mount: " + relative);
        }
        return resolved;
    }

    /** Description of one materialized memory mount. */
    public record MountInfo(String storeId, String storeName, String relativePath) {}
}
