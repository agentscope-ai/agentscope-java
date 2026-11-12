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
package io.agentscope.harness.agent.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.JDBC;

class WorkspaceIndexLifecycleTest {

    @TempDir Path workspaceRoot;

    @Test
    void unavailableIndexDirectoryReturnsNull() throws Exception {
        Files.writeString(workspaceRoot.resolve(".index"), "occupied by a file");

        assertNull(WorkspaceIndex.open(workspaceRoot));
    }

    @Test
    void corruptDatabaseClosesConnectionWhenInitializationFails() throws Exception {
        Path dbFile =
                Files.createDirectories(workspaceRoot.resolve(".index")).resolve("workspace.db");
        Files.writeString(dbFile, "not a SQLite database".repeat(10));
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();

        // Keep a reference to the real SQLite connection to observe ownership after open fails.
        try (var connection = JDBC.createConnection(url, new Properties());
                var connections = mockStatic(JDBC.class, CALLS_REAL_METHODS)) {
            connections
                    .when(() -> JDBC.createConnection(eq(url), any(Properties.class)))
                    .thenReturn(connection);

            assertNull(WorkspaceIndex.open(workspaceRoot));

            assertTrue(connection.isClosed(), "failed initialization must release its connection");
        }
    }

    @Test
    void successfulOpenKeepsConnectionUntilIndexIsClosed() throws Exception {
        Path dbFile =
                Files.createDirectories(workspaceRoot.resolve(".index")).resolve("workspace.db");
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath();

        try (var connection = JDBC.createConnection(url, new Properties());
                var connections = mockStatic(JDBC.class, CALLS_REAL_METHODS)) {
            connections
                    .when(() -> JDBC.createConnection(eq(url), any(Properties.class)))
                    .thenReturn(connection);

            try (WorkspaceIndex index = WorkspaceIndex.open(workspaceRoot)) {
                assertNotNull(index);
                assertFalse(connection.isClosed());
                index.upsert("memory/MEMORY.md", 12, null);
                assertEquals(List.of("memory/MEMORY.md"), index.listByPrefix("memory/"));
            }

            assertTrue(connection.isClosed());
        }
    }
}
