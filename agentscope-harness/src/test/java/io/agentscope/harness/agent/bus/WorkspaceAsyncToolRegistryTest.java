/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.harness.agent.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceAsyncToolRegistryTest {

    @TempDir Path tempDir;
    private WorkspaceAsyncToolRegistry registry;

    @BeforeEach
    void setUp() {
        LocalFilesystem fs = new LocalFilesystem(tempDir, true, 10);
        registry = new WorkspaceAsyncToolRegistry(fs, "/async-tools");
    }

    @Test
    void registerAndFindStale() {
        AsyncToolRecord old =
                new AsyncToolRecord(
                        "r1",
                        "s1",
                        "slow_tool",
                        "tc1",
                        AsyncToolRecord.RUNNING,
                        Instant.now().minus(Duration.ofMinutes(10)));
        registry.register(old).block();

        List<AsyncToolRecord> stale = registry.findStale("s1", Duration.ofMinutes(5)).block();
        assertEquals(1, stale.size());
        assertEquals("r1", stale.get(0).id());
        assertEquals("slow_tool", stale.get(0).toolName());
    }

    @Test
    void recentRunningNotStale() {
        AsyncToolRecord recent =
                new AsyncToolRecord(
                        "r1", "s1", "tool", "tc1", AsyncToolRecord.RUNNING, Instant.now());
        registry.register(recent).block();

        List<AsyncToolRecord> stale = registry.findStale("s1", Duration.ofMinutes(5)).block();
        assertTrue(stale.isEmpty());
    }

    @Test
    void completedNotReturnedByFindStale() {
        AsyncToolRecord old =
                new AsyncToolRecord(
                        "r1",
                        "s1",
                        "tool",
                        "tc1",
                        AsyncToolRecord.RUNNING,
                        Instant.now().minus(Duration.ofMinutes(10)));
        registry.register(old).block();
        registry.complete("r1", "done").block();

        List<AsyncToolRecord> stale = registry.findStale("s1", Duration.ofMinutes(5)).block();
        assertTrue(stale.isEmpty());
    }

    @Test
    void markTimeoutRemovesFromStale() {
        AsyncToolRecord old =
                new AsyncToolRecord(
                        "r1",
                        "s1",
                        "tool",
                        "tc1",
                        AsyncToolRecord.RUNNING,
                        Instant.now().minus(Duration.ofMinutes(10)));
        registry.register(old).block();
        registry.markTimeout("r1").block();

        List<AsyncToolRecord> stale = registry.findStale("s1", Duration.ofMinutes(5)).block();
        assertTrue(stale.isEmpty());
    }

    @Test
    void isolatedBySession() {
        registry.register(
                        new AsyncToolRecord(
                                "r1",
                                "s1",
                                "tool",
                                "tc1",
                                AsyncToolRecord.RUNNING,
                                Instant.now().minus(Duration.ofMinutes(10))))
                .block();
        registry.register(
                        new AsyncToolRecord(
                                "r2",
                                "s2",
                                "tool",
                                "tc2",
                                AsyncToolRecord.RUNNING,
                                Instant.now().minus(Duration.ofMinutes(10))))
                .block();

        List<AsyncToolRecord> staleS1 = registry.findStale("s1", Duration.ofMinutes(5)).block();
        assertEquals(1, staleS1.size());
        assertEquals("r1", staleS1.get(0).id());

        List<AsyncToolRecord> staleS2 = registry.findStale("s2", Duration.ofMinutes(5)).block();
        assertEquals(1, staleS2.size());
        assertEquals("r2", staleS2.get(0).id());
    }

    @Test
    void survivesCrossInstanceRecovery() {
        registry.register(
                        new AsyncToolRecord(
                                "r1",
                                "s1",
                                "tool",
                                "tc1",
                                AsyncToolRecord.RUNNING,
                                Instant.now().minus(Duration.ofMinutes(10))))
                .block();

        LocalFilesystem fs2 = new LocalFilesystem(tempDir, true, 10);
        WorkspaceAsyncToolRegistry registry2 = new WorkspaceAsyncToolRegistry(fs2, "/async-tools");

        List<AsyncToolRecord> stale = registry2.findStale("s1", Duration.ofMinutes(5)).block();
        assertEquals(1, stale.size());
        assertEquals("r1", stale.get(0).id());
    }
}
