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

import io.agentscope.builder.web.managed.MemoryDto;
import io.agentscope.builder.web.managed.MemoryStoreDto;
import io.agentscope.builder.web.managed.MemoryStoreService;
import io.agentscope.builder.web.managed.MemoryStoreService.CreateMemoryStoreRequest;
import io.agentscope.builder.web.managed.MemoryStoreService.PutMemoryRequest;
import io.agentscope.builder.web.managed.MemoryVersionDto;
import io.agentscope.builder.web.managed.ResourceShareDto;
import io.agentscope.builder.web.share.AgentAclService.Tier;
import io.agentscope.builder.web.share.ShareRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/** REST controller for cross-session memory stores. */
@RestController
@RequestMapping("/api/memory-stores")
public class MemoryStoreController {

    private final MemoryStoreService memoryStoreService;

    public MemoryStoreController(MemoryStoreService memoryStoreService) {
        this.memoryStoreService = memoryStoreService;
    }

    /** Lists memory stores owned by the authenticated user. */
    @GetMapping
    public Mono<List<MemoryStoreDto>> listStores(Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> memoryStoreService.listStores(userId));
    }

    /** Returns a single memory store. */
    @GetMapping("/{id}")
    public Mono<MemoryStoreDto> getStore(@PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> memoryStoreService.getStore(userId, id));
    }

    /** Creates a memory store. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<MemoryStoreDto> createStore(
            @RequestBody CreateMemoryStoreRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> memoryStoreService.createStore(userId, req));
    }

    /** Deletes a memory store. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteStore(@PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(() -> memoryStoreService.deleteStore(userId, id));
    }

    /** Lists memories in a store. */
    @GetMapping("/{id}/memories")
    public Mono<List<MemoryDto>> listMemories(@PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> memoryStoreService.listMemories(userId, id));
    }

    /** Returns a memory document by path. */
    @GetMapping("/{id}/memories/{*path}")
    public Mono<MemoryDto> getMemory(
            @PathVariable("id") String id, @PathVariable("path") String path, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> memoryStoreService.getMemory(userId, id, normalizePath(path)));
    }

    /** Creates or updates a memory document. */
    @PutMapping("/{id}/memories/{*path}")
    public Mono<MemoryDto> putMemory(
            @PathVariable("id") String id,
            @PathVariable("path") String path,
            @RequestBody PutMemoryRequest req,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> memoryStoreService.putMemory(userId, id, normalizePath(path), req));
    }

    /** Deletes a memory document. */
    @DeleteMapping("/{id}/memories/{*path}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteMemory(
            @PathVariable("id") String id, @PathVariable("path") String path, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(
                () -> memoryStoreService.deleteMemory(userId, id, normalizePath(path)));
    }

    /** Lists versions of a memory document. */
    @GetMapping("/{id}/memories/{*path}/versions")
    public Mono<List<MemoryVersionDto>> listVersions(
            @PathVariable("id") String id, @PathVariable("path") String path, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () -> memoryStoreService.listVersions(userId, id, stripVersionsSuffix(path)));
    }

    /** Lists share grants on a memory store. Owner-only. */
    @GetMapping("/{id}/shares")
    public Mono<List<ResourceShareDto>> listShares(
            @PathVariable("id") String id, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(() -> memoryStoreService.listShares(userId, id));
    }

    /** Shares a memory store with a user or the whole workspace. Owner-only. */
    @PostMapping("/{id}/shares")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ResourceShareDto> share(
            @PathVariable("id") String id, @RequestBody ShareRequest req, Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromCallable(
                () ->
                        memoryStoreService.share(
                                userId,
                                id,
                                req.granteeType(),
                                req.granteeId(),
                                Tier.valueOf(req.tier())));
    }

    /** Revokes a share grant on a memory store. Owner-only. */
    @DeleteMapping("/{id}/shares/{shareId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> unshare(
            @PathVariable("id") String id,
            @PathVariable("shareId") String shareId,
            Authentication auth) {
        String userId = (String) auth.getPrincipal();
        return Mono.fromRunnable(() -> memoryStoreService.unshare(userId, id, shareId));
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private static String stripVersionsSuffix(String path) {
        String normalized = normalizePath(path);
        if (normalized.endsWith("/versions")) {
            return normalized.substring(0, normalized.length() - "/versions".length());
        }
        return normalized;
    }
}
