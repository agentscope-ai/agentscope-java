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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Resolves session-bound memory stores into {@link MemoryStoreFilesystem} routes mounted under
 * {@code memory-stores/{storeName}/} in the harness filesystem (see {@link
 * MemoryStoreFilesystem#routePrefix} and {@link
 * EnvironmentSpecFactory#applyMemoryStoreRoutes}).
 *
 * <p>Memory documents are read/written directly against the {@link MemoryStoreService} JPA store
 * — there is no host-disk materialization step, so mounts stay in sync across concurrent turns
 * and cached {@link io.agentscope.harness.agent.HarnessAgent} instances without an explicit
 * writeback pass.
 */
@Service
public class MemoryMountService {

    private static final Logger log = LoggerFactory.getLogger(MemoryMountService.class);

    /** Root path segment under which memory-store routes are mounted. */
    public static final String MEMORY_ROOT = "memory-stores";

    private final MemoryStoreService memoryStoreService;

    public MemoryMountService(MemoryStoreService memoryStoreService) {
        this.memoryStoreService = memoryStoreService;
    }

    /**
     * Loads store metadata for each requested memory store id and returns mount descriptions
     * suitable for system-prompt injection. Stores that fail to resolve (deleted, no access) are
     * skipped with a warning.
     */
    public List<MountInfo> resolveMounts(String ownerId, List<String> memoryStoreIds) {
        List<MountInfo> mounts = new ArrayList<>();
        if (memoryStoreIds == null || memoryStoreIds.isEmpty()) {
            return mounts;
        }
        for (String storeId : memoryStoreIds) {
            try {
                MemoryStoreDto store = memoryStoreService.getStore(ownerId, storeId);
                String safeName = sanitize(store.name());
                mounts.add(new MountInfo(storeId, store.name(), MEMORY_ROOT + "/" + safeName));
            } catch (Exception ex) {
                log.warn("Failed to resolve memory store {}: {}", storeId, ex.getMessage());
            }
        }
        return mounts;
    }

    /**
     * Builds one {@link MemoryStoreFilesystem} per resolvable memory store id, ready to be
     * mounted via {@link EnvironmentSpecFactory#applyMemoryStoreRoutes}. All mounts default to
     * {@code read_write}; use {@link #createFilesystems(String, List, Map)} to mount some stores
     * {@code read_only}.
     */
    public List<MemoryStoreFilesystem> createFilesystems(
            String ownerId, List<String> memoryStoreIds) {
        return createFilesystems(ownerId, memoryStoreIds, Map.of());
    }

    /**
     * Builds one {@link MemoryStoreFilesystem} per resolvable memory store id, applying a
     * per-store access mode ({@code read_only} | {@code read_write}, default {@code
     * read_write}) from {@code accessByStoreId} (typically sourced from the session's
     * environment config, e.g. {@code config.memoryAccess = {storeId: "read_only"}}).
     */
    public List<MemoryStoreFilesystem> createFilesystems(
            String ownerId, List<String> memoryStoreIds, Map<String, String> accessByStoreId) {
        List<MemoryStoreFilesystem> filesystems = new ArrayList<>();
        if (memoryStoreIds == null || memoryStoreIds.isEmpty()) {
            return filesystems;
        }
        Map<String, String> access = accessByStoreId != null ? accessByStoreId : Map.of();
        for (String storeId : memoryStoreIds) {
            try {
                MemoryStoreDto store = memoryStoreService.getStore(ownerId, storeId);
                filesystems.add(
                        new MemoryStoreFilesystem(
                                memoryStoreService,
                                ownerId,
                                storeId,
                                store.name(),
                                access.get(storeId)));
            } catch (Exception ex) {
                log.warn("Failed to mount memory store {}: {}", storeId, ex.getMessage());
            }
        }
        return filesystems;
    }

    /** Builds a system-prompt appendix describing mounted memory-store directories. */
    public String promptAppendix(List<MountInfo> mounts) {
        if (mounts == null || mounts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## Mounted Memory Stores\n");
        sb.append(
                "The following directories contain persistent cross-session memory. Read and update"
                    + " files under these paths; changes are versioned immediately on write.\n");
        for (MountInfo mount : mounts) {
            sb.append("- `")
                    .append(mount.relativePath())
                    .append("/` (also conceptually `/mnt/memory/")
                    .append(sanitize(mount.storeName()))
                    .append("/`) — store \"")
                    .append(mount.storeName())
                    .append("\"\n");
        }
        return sb.toString();
    }

    /**
     * @deprecated host-disk materialization is no longer used; memory stores are mounted live via
     *     {@link #createFilesystems}. Retained only so callers mid-migration keep compiling;
     *     delegates to {@link #resolveMounts}.
     */
    @Deprecated
    public List<MountInfo> materialize(
            String ownerId, Path workspace, List<String> memoryStoreIds) {
        return resolveMounts(ownerId, memoryStoreIds);
    }

    /**
     * @deprecated writeback is a no-op: {@link MemoryStoreFilesystem} writes directly through to
     *     {@link MemoryStoreService} on every tool call, so there is nothing left to reconcile
     *     after a turn.
     */
    @Deprecated
    public void writeback(String ownerId, Path workspace, List<String> memoryStoreIds) {
        // no-op — MemoryStoreFilesystem writes through immediately.
    }

    private static String sanitize(String name) {
        if (name == null || name.isBlank()) {
            return "store";
        }
        return name.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    /** Description of one mounted memory store. */
    public record MountInfo(String storeId, String storeName, String relativePath) {}
}
