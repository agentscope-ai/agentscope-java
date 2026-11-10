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
package io.agentscope.core.skill;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.tool.Toolkit;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that {@link SkillBox} guards concurrent {@code uploadSkillFiles} writes with a
 * fixed-size stripe table instead of a per-path lock map: the same path always maps to the same
 * stripe so mutual exclusion is preserved, the number of lock objects never grows with the number
 * of distinct paths, and simultaneous uploads of one path serialise without interleaved writes.
 */
@Tag("unit")
class SkillBoxFileLockTest {

    @TempDir Path workDir;

    @Test
    void locksAreStripedNotAccumulated() {
        assertSame(
                SkillBox.fileLockFor(Path.of("skills", "alpha", "data.txt")),
                SkillBox.fileLockFor(Path.of("skills", "alpha", "data.txt")),
                "the same path must always map to the same stripe object");

        Set<Object> stripes = new HashSet<>();
        int sampleCount = SkillBox.FILE_LOCK_STRIPES * 32;
        for (int i = 0; i < sampleCount; i++) {
            stripes.add(
                    SkillBox.fileLockFor(Path.of("skills", "skill-" + i, "file-" + i + ".txt")));
        }
        assertTrue(
                stripes.size() <= SkillBox.FILE_LOCK_STRIPES,
                "distinct paths must reuse a fixed stripe table, never mint per-path locks");
        assertTrue(
                stripes.size() > 1,
                "distinct paths should spread across stripes, not collapse onto a single lock");
    }

    @Test
    void concurrentUploadsToSamePathSerializeWithoutInterleaving() throws Exception {
        int payloadSize = 64 * 1024;
        String payloadA = "A".repeat(payloadSize);
        String payloadB = "B".repeat(payloadSize);
        Path target = workDir.resolve("skills").resolve("concurrent_custom").resolve("data.txt");
        SkillBox boxA = boxUploading("concurrent", payloadA);
        SkillBox boxB = boxUploading("concurrent", payloadB);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> uploadA = pool.submit(() -> uploadWhenReleased(boxA, ready, start));
            Future<?> uploadB = pool.submit(() -> uploadWhenReleased(boxB, ready, start));
            assertTrue(ready.await(10, TimeUnit.SECONDS), "both uploaders must reach the gate");
            start.countDown();
            uploadA.get(30, TimeUnit.SECONDS);
            uploadB.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        String content = Files.readString(target);
        assertTrue(
                payloadA.equals(content) || payloadB.equals(content),
                "the file must hold exactly one writer's complete payload, got length="
                        + content.length()
                        + " head='"
                        + content.substring(0, 1)
                        + "' tail='"
                        + content.substring(content.length() - 1)
                        + "'");
    }

    private SkillBox boxUploading(String name, String content) {
        SkillBox box = new SkillBox(new Toolkit());
        box.setWorkDir(workDir);
        box.registerSkill(
                new AgentSkill(
                        name, "Concurrency fixture", "instructions", Map.of("data.txt", content)));
        return box;
    }

    private void uploadWhenReleased(SkillBox box, CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        try {
            start.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the start gate", e);
        }
        box.uploadSkillFiles();
    }
}
