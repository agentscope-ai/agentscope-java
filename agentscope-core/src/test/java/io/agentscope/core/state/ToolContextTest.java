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
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class ToolContextTest {

    @Test
    void defaultsMatchSpec() {
        ToolContext ctx = ToolContext.builder().build();
        assertEquals(100, ctx.getMaxCacheFiles());
        assertEquals(25_000.0, ctx.getMaxCacheBytes());
        assertEquals(List.of(), ctx.getReadFileCache());
        assertEquals(List.of(), ctx.getActivatedGroups());
    }

    @Test
    void rejectsMaxCacheFilesBelowTwo() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolContext.builder().maxCacheFiles(1).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolContext.builder().maxCacheFiles(0).build());
    }

    @Test
    void rejectsMaxCacheBytesAtOrBelowTenThousand() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolContext.builder().maxCacheBytes(10_000).build());
        assertThrows(
                IllegalArgumentException.class,
                () -> ToolContext.builder().maxCacheBytes(9_999).build());
    }

    @Test
    void cacheFileAndGetCacheReturnEntry(@TempDir Path tmp) throws IOException {
        Path file = Files.writeString(tmp.resolve("a.txt"), "line1\nline2\n");
        ToolContext ctx = ToolContext.builder().build();

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
        ToolContext ctx = ToolContext.builder().build();
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
        ToolContext ctx = ToolContext.builder().build();
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
        ToolContext ctx = ToolContext.builder().build();
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
        ToolContext ctx = ToolContext.builder().maxCacheFiles(3).build();
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
        ToolContext ctx = ToolContext.builder().maxCacheBytes(10_001).build();
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
        ToolContext ctx = ToolContext.builder().maxCacheFiles(50).build();
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
}
