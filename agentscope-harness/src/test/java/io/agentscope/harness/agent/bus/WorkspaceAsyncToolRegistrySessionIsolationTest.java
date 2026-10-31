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

/**
 * The registry stores records under {@code sanitize(sessionId)}, which maps every
 * character outside {@code [a-zA-Z0-9._-]} to {@code _}. Two different session ids can
 * therefore share one directory, and a scan that trusts the directory name alone reports
 * the other session's records as its own.
 */
class WorkspaceAsyncToolRegistrySessionIsolationTest {

    /** Sanitizes to {@code team_alpha}. */
    private static final String SLASHED = "team/alpha";

    /**
     * Already equals its sanitized form, so it lands in the same directory as SLASHED.
     */
    private static final String UNDERSCORED = "team_alpha";

    @TempDir Path tempDir;

    private WorkspaceAsyncToolRegistry registry;

    @BeforeEach
    void setUp() {
        LocalFilesystem fs = new LocalFilesystem(tempDir, true, 10);
        registry = new WorkspaceAsyncToolRegistry(fs, "/async-tools");
    }

    private void registerStale(String recordId, String sessionId) {
        registry.register(
                        new AsyncToolRecord(
                                recordId,
                                sessionId,
                                "slow_tool",
                                "tc-" + recordId,
                                AsyncToolRecord.RUNNING,
                                Instant.now().minus(Duration.ofMinutes(10))))
                .block();
    }

    @Test
    void aSessionsStaleRecordsAreNotReportedToTheOtherSessionSharingItsDirectory() {
        registerStale("r-slashed", SLASHED);

        List<AsyncToolRecord> staleForOther =
                registry.findStale(UNDERSCORED, Duration.ofMinutes(5)).block();

        assertTrue(
                staleForOther.isEmpty(),
                "findStale("
                        + UNDERSCORED
                        + ") must not return records of "
                        + SLASHED
                        + " just because both sanitize to the same directory, but got: "
                        + staleForOther);
    }

    @Test
    void eachSessionStillSeesItsOwnStaleRecords() {
        registerStale("r-slashed", SLASHED);
        registerStale("r-underscored", UNDERSCORED);

        List<AsyncToolRecord> staleForSlashed =
                registry.findStale(SLASHED, Duration.ofMinutes(5)).block();
        List<AsyncToolRecord> staleForUnderscored =
                registry.findStale(UNDERSCORED, Duration.ofMinutes(5)).block();

        assertEquals(
                List.of("r-slashed"), staleForSlashed.stream().map(AsyncToolRecord::id).toList());
        assertEquals(
                List.of("r-underscored"),
                staleForUnderscored.stream().map(AsyncToolRecord::id).toList());
    }
}
