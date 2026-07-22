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
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceManagerPathSafetyTest {

    @Test
    void workspaceWritesRejectRelativeTraversal(@TempDir Path root) throws Exception {
        Path base = root.resolve("base");
        Path template = base.resolve("template");
        Path backend = base.resolve("backend");
        Files.createDirectories(template);
        Files.createDirectories(backend);
        RuntimeContext rc = RuntimeContext.empty();

        try (WorkspaceManager manager =
                new WorkspaceManager(
                        template,
                        new LocalFilesystem(
                                backend, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> manager.writeUtf8WorkspaceRelative(rc, "../escape.txt", "ESCAPE_WRITE"));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            manager.appendUtf8WorkspaceRelative(
                                    rc, "../../append-escape.txt", "ESCAPE_APPEND"));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            manager.writeUtf8WorkspaceRelative(
                                    rc, "..\\windows-escape.txt", "ESCAPE_WINDOWS"));
        }

        assertFalse(Files.exists(base.resolve("escape.txt")));
        assertFalse(Files.exists(root.resolve("append-escape.txt")));
        assertFalse(Files.exists(base.resolve("windows-escape.txt")));
    }

    @Test
    void workspaceWritesAllowLogicalAbsoluteAndLiteralDoubleDotNames(@TempDir Path root)
            throws Exception {
        Path template = root.resolve("template");
        Path backend = root.resolve("backend");
        Files.createDirectories(template);
        Files.createDirectories(backend);

        try (WorkspaceManager manager =
                new WorkspaceManager(
                        template,
                        new LocalFilesystem(
                                backend, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null))) {
            manager.writeUtf8WorkspaceRelative(
                    RuntimeContext.empty(), "/absolute-inside.txt", "ABSOLUTE");
            manager.writeUtf8WorkspaceRelative(
                    RuntimeContext.empty(), "some..dir/note.txt", "LITERAL");
        }

        assertEquals(
                "ABSOLUTE",
                Files.readString(backend.resolve("absolute-inside.txt"), StandardCharsets.UTF_8));
        assertEquals(
                "LITERAL",
                Files.readString(backend.resolve("some..dir/note.txt"), StandardCharsets.UTF_8));
    }
}
