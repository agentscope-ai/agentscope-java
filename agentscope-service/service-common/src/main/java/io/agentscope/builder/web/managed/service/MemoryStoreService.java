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
package io.agentscope.builder.web.managed.service;

import io.agentscope.builder.web.managed.MemoryDto;
import io.agentscope.builder.web.managed.MemoryStoreDto;
import io.agentscope.builder.web.managed.MemoryVersionDto;
import io.agentscope.builder.web.managed.ResourceShareDto;
import io.agentscope.builder.web.persistence.jpa.MemoryEntity;
import io.agentscope.builder.web.persistence.jpa.MemoryEntityRepository;
import io.agentscope.builder.web.persistence.jpa.MemoryStoreEntity;
import io.agentscope.builder.web.persistence.jpa.MemoryStoreEntityRepository;
import io.agentscope.builder.web.persistence.jpa.MemoryVersionEntity;
import io.agentscope.builder.web.persistence.jpa.MemoryVersionEntityRepository;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import io.agentscope.builder.web.share.ResourceAccessService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** CRUD for memory stores and versioned memory documents. */
@Service
@Transactional
public class MemoryStoreService {

    /** Resource-type key used for {@link ResourceAccessService} share grants. */
    public static final String RESOURCE_TYPE = "memory_store";

    /** Request body for creating a memory store. */
    public record CreateMemoryStoreRequest(String name, String description) {}

    /** Request body for writing a memory document. */
    public record PutMemoryRequest(String content) {}

    private final MemoryStoreEntityRepository storeRepository;
    private final MemoryEntityRepository memoryRepository;
    private final MemoryVersionEntityRepository versionRepository;
    private final ResourceAccessService resourceAccessService;

    public MemoryStoreService(
            MemoryStoreEntityRepository storeRepository,
            MemoryEntityRepository memoryRepository,
            MemoryVersionEntityRepository versionRepository,
            ResourceAccessService resourceAccessService) {
        this.storeRepository = storeRepository;
        this.memoryRepository = memoryRepository;
        this.versionRepository = versionRepository;
        this.resourceAccessService = resourceAccessService;
    }

    /** Lists memory stores owned by the user. */
    @Transactional(readOnly = true)
    public List<MemoryStoreDto> listStores(String ownerId) {
        return storeRepository.findByOwnerIdOrderByCreatedAtAsc(ownerId).stream()
                .map(this::toStoreDto)
                .toList();
    }

    /** Returns a single memory store when owned by, or shared (at least RUN) with, the caller. */
    @Transactional(readOnly = true)
    public MemoryStoreDto getStore(String ownerId, String storeId) {
        return toStoreDto(requireAccess(ownerId, storeId, Tier.RUN));
    }

    /** Creates a memory store. */
    public MemoryStoreDto createStore(String ownerId, CreateMemoryStoreRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name is required");
        }
        if (storeRepository.existsByOwnerIdAndName(ownerId, request.name())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Memory store name already exists: " + request.name());
        }
        long now = System.currentTimeMillis();
        MemoryStoreEntity entity = new MemoryStoreEntity();
        entity.setStoreId(ManagedJsonHelper.randomId("mst_"));
        entity.setOwnerId(ownerId);
        entity.setName(request.name());
        entity.setDescription(request.description());
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        return toStoreDto(storeRepository.save(entity));
    }

    /** Deletes a memory store and all contained memories. */
    public void deleteStore(String ownerId, String storeId) {
        requireAccess(ownerId, storeId, Tier.EDIT);
        for (MemoryEntity memory : memoryRepository.findByStoreIdOrderByPathAsc(storeId)) {
            versionRepository.deleteByMemoryId(memory.getMemoryId());
        }
        memoryRepository.deleteByStoreId(storeId);
        storeRepository.findByStoreId(storeId).ifPresent(storeRepository::delete);
    }

    /** Lists all memories in a store. */
    @Transactional(readOnly = true)
    public List<MemoryDto> listMemories(String ownerId, String storeId) {
        requireAccess(ownerId, storeId, Tier.RUN);
        return memoryRepository.findByStoreIdOrderByPathAsc(storeId).stream()
                .map(this::toMemoryDto)
                .toList();
    }

    /** Returns a memory document by path. */
    @Transactional(readOnly = true)
    public MemoryDto getMemory(String ownerId, String storeId, String path) {
        requireAccess(ownerId, storeId, Tier.RUN);
        return memoryRepository
                .findByStoreIdAndPath(storeId, path)
                .map(this::toMemoryDto)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Memory not found: " + path));
    }

    /** Creates or updates a memory document, bumping the head version. */
    public MemoryDto putMemory(
            String ownerId, String storeId, String path, PutMemoryRequest request) {
        requireAccess(ownerId, storeId, Tier.EDIT);
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "path is required");
        }
        long now = System.currentTimeMillis();
        MemoryEntity memory =
                memoryRepository
                        .findByStoreIdAndPath(storeId, path)
                        .orElseGet(
                                () -> {
                                    MemoryEntity fresh = new MemoryEntity();
                                    fresh.setMemoryId(ManagedJsonHelper.randomId("mem_"));
                                    fresh.setStoreId(storeId);
                                    fresh.setPath(path);
                                    fresh.setHeadVersion(0);
                                    fresh.setCreatedAt(now);
                                    return fresh;
                                });
        int nextVersion = memory.getHeadVersion() + 1;
        memory.setContent(request.content());
        memory.setHeadVersion(nextVersion);
        memory.setUpdatedAt(now);
        MemoryEntity saved = memoryRepository.save(memory);

        MemoryVersionEntity version = new MemoryVersionEntity();
        version.setMemoryId(saved.getMemoryId());
        version.setVersion(nextVersion);
        version.setContent(saved.getContent());
        version.setCreatedAt(now);
        versionRepository.save(version);

        MemoryStoreEntity store = storeRepository.findByStoreId(storeId).orElseThrow();
        store.setUpdatedAt(now);
        storeRepository.save(store);
        return toMemoryDto(saved);
    }

    /** Deletes a memory document and its version history. */
    public void deleteMemory(String ownerId, String storeId, String path) {
        requireAccess(ownerId, storeId, Tier.EDIT);
        MemoryEntity memory =
                memoryRepository
                        .findByStoreIdAndPath(storeId, path)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND, "Memory not found: " + path));
        versionRepository.deleteByMemoryId(memory.getMemoryId());
        memoryRepository.delete(memory);
    }

    /** Lists all versions of a memory document. */
    @Transactional(readOnly = true)
    public List<MemoryVersionDto> listVersions(String ownerId, String storeId, String path) {
        MemoryEntity memory = requireMemory(ownerId, storeId, path);
        return versionRepository.findByMemoryIdOrderByVersionAsc(memory.getMemoryId()).stream()
                .map(this::toVersionDto)
                .toList();
    }

    /** Lists share grants on a memory store. Owner/EDIT-tier only. */
    @Transactional(readOnly = true)
    public List<ResourceShareDto> listShares(String ownerId, String storeId) {
        requireAccess(ownerId, storeId, Tier.EDIT);
        return resourceAccessService.listShares(RESOURCE_TYPE, storeId);
    }

    /** Shares a memory store with a user or the whole workspace. Owner/EDIT-tier only. */
    public ResourceShareDto share(
            String ownerId, String storeId, String granteeType, String granteeId, Tier tier) {
        MemoryStoreEntity store = requireAccess(ownerId, storeId, Tier.EDIT);
        return resourceAccessService.share(
                RESOURCE_TYPE, storeId, store.getOwnerId(), granteeType, granteeId, tier, ownerId);
    }

    /** Revokes a share grant on a memory store. Owner/EDIT-tier only. */
    public void unshare(String ownerId, String storeId, String shareId) {
        requireAccess(ownerId, storeId, Tier.EDIT);
        resourceAccessService.unshare(RESOURCE_TYPE, storeId, shareId);
    }

    /** Resolves the store and verifies {@code callerId} holds at least {@code required}. */
    private MemoryStoreEntity requireAccess(String callerId, String storeId, Tier required) {
        MemoryStoreEntity store =
                storeRepository
                        .findByStoreId(storeId)
                        .orElseThrow(
                                () ->
                                        new ResponseStatusException(
                                                HttpStatus.NOT_FOUND,
                                                "Memory store not found: " + storeId));
        resourceAccessService.require(
                callerId, store.getOwnerId(), RESOURCE_TYPE, storeId, required);
        return store;
    }

    private MemoryEntity requireMemory(String ownerId, String storeId, String path) {
        requireAccess(ownerId, storeId, Tier.RUN);
        return memoryRepository
                .findByStoreIdAndPath(storeId, path)
                .orElseThrow(
                        () ->
                                new ResponseStatusException(
                                        HttpStatus.NOT_FOUND, "Memory not found: " + path));
    }

    private MemoryStoreDto toStoreDto(MemoryStoreEntity entity) {
        return new MemoryStoreDto(
                entity.getStoreId(),
                entity.getOwnerId(),
                entity.getName(),
                entity.getDescription(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private MemoryDto toMemoryDto(MemoryEntity entity) {
        return new MemoryDto(
                entity.getMemoryId(),
                entity.getStoreId(),
                entity.getPath(),
                entity.getContent(),
                entity.getHeadVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }

    private MemoryVersionDto toVersionDto(MemoryVersionEntity entity) {
        return new MemoryVersionDto(
                entity.getMemoryId(),
                entity.getVersion(),
                entity.getContent(),
                entity.getCreatedAt());
    }
}
