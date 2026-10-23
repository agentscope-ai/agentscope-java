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
package io.agentscope.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.util.JsonUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class ToolContextStateTest {

    @Test
    void defaultsMatchSpec() {
        ToolContextState ctx = ToolContextState.builder().build();
        assertEquals(100, ctx.getMaxCacheFiles());
        assertEquals(25_000.0, ctx.getMaxCacheBytes());
        assertEquals(List.of(), ctx.getReadFileCache());
        assertEquals(List.of(), ctx.getActivatedGroups());
    }

    @Test
    void rejectsMaxCacheFilesBelowTwo() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolContextState.builder().maxCacheFiles(1).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolContextState.builder().maxCacheFiles(0).build());
    }

    @Test
    void rejectsMaxCacheBytesAtOrBelowTenThousand() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolContextState.builder().maxCacheBytes(10_000).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolContextState.builder().maxCacheBytes(9_999).build());
    }

    @Test
    void cacheFileAndGetCacheReturnEntry(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("a.txt"), "line1\nline2\n");
        ToolContextState ctx = ToolContextState.builder().build();

        StepVerifier.create(ctx.cacheFile(file.toString(), List.of("line1", "line2")))
                .verifyComplete();

        StepVerifier.create(ctx.getCache(file.toString()))
                .assertNext(
                        opt -> {
                            assertEquals(true, opt.isPresent());
                            assertEquals(List.of("line1", "line2"), opt.get().lines());
                            assertEquals(file.toString(), opt.get().filePath());
                        })
                .verifyComplete();
    }

    @Test
    void mtimeChangeInvalidatesAndEvicts(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("b.txt"), "v1");
        ToolContextState ctx = ToolContextState.builder().build();
        ctx.cacheFile(file.toString(), List.of("v1")).block();

        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 60_000));

        StepVerifier.create(ctx.getCache(file.toString()))
                .assertNext(opt -> assertEquals(Optional.empty(), opt))
                .verifyComplete();

        assertEquals(0, ctx.getReadFileCache().size());
    }

    @Test
    void missingFileReturnsEmptyAndPurges(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("c.txt"), "v");
        ToolContextState ctx = ToolContextState.builder().build();
        ctx.cacheFile(file.toString(), List.of("v")).block();
        Files.delete(file);

        StepVerifier.create(ctx.getCache(file.toString()))
                .assertNext(opt -> assertEquals(Optional.empty(), opt))
                .verifyComplete();

        assertEquals(0, ctx.getReadFileCache().size());
    }

    @Test
    void repeatedCacheReplacesPreviousEntry(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("d.txt"), "v1");
        ToolContextState ctx = ToolContextState.builder().build();
        ctx.cacheFile(file.toString(), List.of("v1")).block();
        Files.setLastModifiedTime(file, FileTime.fromMillis(System.currentTimeMillis() + 1_000));
        ctx.cacheFile(file.toString(), List.of("v1", "v2")).block();

        assertEquals(1, ctx.getReadFileCache().size());
        StepVerifier.create(ctx.getCache(file.toString()))
                .assertNext(opt -> assertEquals(List.of("v1", "v2"), opt.orElseThrow().lines()))
                .verifyComplete();
    }

    @Test
    void evictsOldestWhenExceedingMaxCacheFiles(@TempDir Path tmp) throws IOException {
        ToolContextState ctx = ToolContextState.builder().maxCacheFiles(3).build();
        for (int i = 0; i < 4; i++) {
            Path file = Files.writeString(tmp.resolve("f" + i + ".txt"), "x");
            ctx.cacheFile(file.toString(), List.of("x")).block();
        }
        List<ReadCacheEntry> snapshot = ctx.getReadFileCache();
        assertEquals(3, snapshot.size());
        assertEquals(tmp.resolve("f1.txt").toString(), snapshot.get(0).filePath());
        assertEquals(tmp.resolve("f3.txt").toString(), snapshot.get(2).filePath());
    }

    @Test
    void evictsOldestWhenExceedingMaxCacheBytes(@TempDir Path tmp) throws IOException {
        // Cap = 10001 KB; each entry ~6144 KB (6 MB). Two entries (12288 KB) overflow the cap,
        // so each new insert should evict the previous one.
        ToolContextState ctx = ToolContextState.builder().maxCacheBytes(10_001).build();
        String chunk = "x".repeat(6 * 1024 * 1024);
        for (int i = 0; i < 3; i++) {
            Path file = Files.writeString(tmp.resolve("big" + i + ".txt"), chunk);
            ctx.cacheFile(file.toString(), List.of(chunk)).block();
        }
        List<ReadCacheEntry> snapshot = ctx.getReadFileCache();
        assertEquals(1, snapshot.size());
        assertEquals(tmp.resolve("big2.txt").toString(), snapshot.get(0).filePath());
    }

    @Test
    void concurrentCacheAndGetAreSafe(@TempDir Path tmp) throws IOException {
        ToolContextState ctx = ToolContextState.builder().maxCacheFiles(50).build();
        ConcurrentHashMap<String, Path> files = new ConcurrentHashMap<>();
        for (int i = 0; i < 30; i++) {
            Path file = Files.writeString(tmp.resolve("c" + i + ".txt"), "line" + i);
            files.put("c" + i, file);
        }

        StepVerifier.create(
                        Flux.range(0, 30)
                                .parallel(8)
                                .runOn(Schedulers.parallel())
                                .flatMap(
                                        i -> {
                                            Path f = files.get("c" + i);
                                            return ctx.cacheFile(f.toString(), List.of("line" + i))
                                                    .then(ctx.getCache(f.toString()));
                                        })
                                .sequential())
                .expectNextCount(30)
                .verifyComplete();

        // All 30 entries should fit under the 50-file cap
        assertEquals(30, ctx.getReadFileCache().size());
    }

    // ==================== Spawn Registry Tests ====================

    @Test
    void spawnRegistryDefaultsToEmpty() {
        ToolContextState ctx = ToolContextState.builder().build();
        assertNotNull(ctx.getSpawnRegistry());
        assertTrue(ctx.getSpawnRegistry().isEmpty());
    }

    @Test
    void putAndGetSpawnEntry() {
        ToolContextState ctx = ToolContextState.builder().build();
        ToolContextState.SpawnEntry entry =
                new ToolContextState.SpawnEntry(
                        "agent:gp:abc", "general-purpose", "sub-abc", "researcher", 1);

        ctx.putSpawnEntry("agent:gp:abc", entry);

        Map<String, ToolContextState.SpawnEntry> registry = ctx.getSpawnRegistry();
        assertEquals(1, registry.size());
        ToolContextState.SpawnEntry got = registry.get("agent:gp:abc");
        assertNotNull(got);
        assertEquals("general-purpose", got.agentId());
        assertEquals("sub-abc", got.sessionId());
        assertEquals("researcher", got.label());
        assertEquals(1, got.depth());
    }

    @Test
    void removeSpawnEntry() {
        ToolContextState ctx = ToolContextState.builder().build();
        ctx.putSpawnEntry(
                "agent:gp:abc",
                new ToolContextState.SpawnEntry(
                        "agent:gp:abc", "general-purpose", "sub-abc", null, 1));

        ctx.removeSpawnEntry("agent:gp:abc");
        assertTrue(ctx.getSpawnRegistry().isEmpty());
    }

    @Test
    void spawnEntryNullKeyIgnored() {
        ToolContextState ctx = ToolContextState.builder().build();
        ctx.putSpawnEntry(
                null,
                new ToolContextState.SpawnEntry(
                        "agent:gp:abc", "general-purpose", "sub-abc", null, 1));
        assertTrue(ctx.getSpawnRegistry().isEmpty());
    }

    @Test
    void spawnRegistrySerializationRoundTrip() {
        ToolContextState ctx = ToolContextState.builder().build();
        ctx.putSpawnEntry(
                "agent:gp:abc",
                new ToolContextState.SpawnEntry(
                        "agent:gp:abc", "general-purpose", "sub-abc", "researcher", 1));
        ctx.putSpawnEntry(
                "agent:coder:def",
                new ToolContextState.SpawnEntry("agent:coder:def", "coder", "sub-def", null, 2));

        String json = JsonUtils.getJsonCodec().toJson(ctx);
        assertNotNull(json);
        assertTrue(json.contains("spawn_registry"));
        assertTrue(json.contains("general-purpose"));
        assertTrue(json.contains("researcher"));

        ToolContextState restored = JsonUtils.getJsonCodec().fromJson(json, ToolContextState.class);
        assertNotNull(restored);
        Map<String, ToolContextState.SpawnEntry> registry = restored.getSpawnRegistry();
        assertEquals(2, registry.size());

        ToolContextState.SpawnEntry e1 = registry.get("agent:gp:abc");
        assertNotNull(e1);
        assertEquals("general-purpose", e1.agentId());
        assertEquals("sub-abc", e1.sessionId());
        assertEquals("researcher", e1.label());
        assertEquals(1, e1.depth());

        ToolContextState.SpawnEntry e2 = registry.get("agent:coder:def");
        assertNotNull(e2);
        assertEquals("coder", e2.agentId());
        assertNull(e2.label());
        assertEquals(2, e2.depth());
    }

    @Test
    void spawnRegistryBackwardCompatibility() {
        String jsonWithoutRegistry =
                "{\"max_cache_files\":100,\"max_cache_bytes\":25000.0,\"activated_groups\":[]}";
        ToolContextState restored =
                JsonUtils.getJsonCodec().fromJson(jsonWithoutRegistry, ToolContextState.class);
        assertNotNull(restored);
        assertNotNull(restored.getSpawnRegistry());
        assertTrue(restored.getSpawnRegistry().isEmpty());
    }
}
