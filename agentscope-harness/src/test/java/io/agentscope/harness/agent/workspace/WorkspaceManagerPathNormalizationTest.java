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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceManagerPathNormalizationTest {

    private static final RuntimeContext RC = RuntimeContext.empty();

    @Test
    void toWorkspaceRelativePath_normalizesAbsoluteAndVirtualPaths(@TempDir Path tmp) {
        WorkspaceManager wsm = new WorkspaceManager(tmp);
        Path file = tmp.resolve("memory").resolve("2026-05-20.md");

        assertEquals("memory/2026-05-20.md", wsm.toWorkspaceRelativePath(file.toString()));
        assertEquals("memory/2026-05-20.md", wsm.toWorkspaceRelativePath("/memory/2026-05-20.md"));
        assertEquals("memory/2026-05-20.md", wsm.toWorkspaceRelativePath("memory/2026-05-20.md"));
    }

    @Test
    void toWorkspaceRelativePath_fallsBackWhenPathParsingFails(@TempDir Path tmp) {
        WorkspaceManager wsm = new WorkspaceManager(tmp);
        String invalidPath = "/memory/" + '\0' + "bad.md";

        assertEquals("memory/" + '\0' + "bad.md", wsm.toWorkspaceRelativePath(invalidPath));
    }

    @Test
    void readManagedWorkspaceFileUtf8_readsLocalAbsolutePath(@TempDir Path tmp) throws Exception {
        Path file = tmp.resolve("memory").resolve("2026-05-20.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "daily entry");

        LocalFilesystem fs = new LocalFilesystem(tmp);
        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs)) {
            assertEquals("daily entry", wsm.readManagedWorkspaceFileUtf8(RC, file.toString()));
        }
    }

    @Test
    void listKnowledgeFiles_deduplicatesAbsoluteFilesystemMatches(@TempDir Path tmp)
            throws Exception {
        Path file = tmp.resolve("knowledge").resolve("facts.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "knowledge");

        LocalFilesystem fs = new LocalFilesystem(tmp);
        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs)) {
            List<Path> files = wsm.listKnowledgeFiles(RC);

            assertEquals(1, files.size());
            assertEquals(file, files.get(0));
        }
    }

    @Test
    void listMemoryFilePaths_deduplicatesAbsoluteFilesystemMatches(@TempDir Path tmp)
            throws Exception {
        Files.writeString(tmp.resolve("MEMORY.md"), "curated memory");
        Path dailyFile = tmp.resolve("memory").resolve("2026-05-20.md");
        Files.createDirectories(dailyFile.getParent());
        Files.writeString(dailyFile, "daily entry");

        LocalFilesystem fs = new LocalFilesystem(tmp);
        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs)) {
            List<String> paths = wsm.listMemoryFilePaths(RC);

            assertEquals(2, paths.size());
            assertTrue(paths.contains("MEMORY.md"));
            assertTrue(paths.contains("memory/2026-05-20.md"));
            assertFalse(paths.contains(dailyFile.toAbsolutePath().toString().replace('\\', '/')));
        }
    }

    @Test
    void listSessionLogFiles_deduplicatesAbsoluteFilesystemMatches(@TempDir Path tmp)
            throws Exception {
        Path logFile =
                tmp.resolve("agents")
                        .resolve("demo")
                        .resolve("sessions")
                        .resolve("session-1.log.jsonl");
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, "{\"type\":\"msg\"}\n");

        LocalFilesystem fs = new LocalFilesystem(tmp);
        try (WorkspaceManager wsm = new WorkspaceManager(tmp, fs)) {
            List<String> paths = wsm.listSessionLogFiles(RC);

            assertEquals(1, paths.size());
            assertEquals("agents/demo/sessions/session-1.log.jsonl", paths.get(0));
        }
    }
}
