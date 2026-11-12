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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.subagent.task.TaskRecord;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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

    /**
     * Regression test for #2937: a gateway can hand over a session key (for example
     * {@code agent:abc:main}) as the session id. {@code :} is legal in the id but not in an NTFS
     * file name, so building the path used to fail with {@link java.nio.file.InvalidPathException}
     * instead of storing the record.
     */
    @Test
    void sessionIdWithReservedCharactersIsEncodedAndStillRoundTrips(@TempDir Path root)
            throws Exception {
        Path template = root.resolve("template");
        Path backend = root.resolve("backend");
        Files.createDirectories(template);
        Files.createDirectories(backend);
        RuntimeContext rc = RuntimeContext.empty();

        try (WorkspaceManager manager =
                new WorkspaceManager(
                        template,
                        new LocalFilesystem(
                                backend, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null))) {
            assertEquals(
                    "agent%3Aabc%3Amain.json",
                    manager.resolveSessionFile(rc, "agent-a", "agent:abc:main")
                            .getFileName()
                            .toString());

            TaskRecord record = new TaskRecord();
            record.setTaskId("t-1");
            manager.writeTaskRecord(rc, "agent-a", "agent:abc:main", record);

            // Writers and readers agree on the encoding, so the record is readable again.
            assertEquals(
                    "t-1",
                    manager.readTaskRecord(rc, "agent-a", "agent:abc:main", "t-1")
                            .orElseThrow()
                            .getTaskId());

            try (Stream<Path> files = Files.walk(backend)) {
                List<String> names =
                        files.filter(Files::isRegularFile)
                                .map(p -> p.getFileName().toString())
                                .collect(Collectors.toList());
                assertTrue(
                        names.contains("agent%3Aabc%3Amain.json"),
                        "task record should be stored under the encoded file name, got " + names);
                assertFalse(
                        names.contains("agent.json"),
                        "the id must not be truncated at the first reserved character, got "
                                + names);
            }
        }
    }

    @Test
    void ordinarySessionIdsKeepTheirBareFileName(@TempDir Path root) throws Exception {
        Path template = root.resolve("template");
        Path backend = root.resolve("backend");
        Files.createDirectories(template);
        Files.createDirectories(backend);

        try (WorkspaceManager manager =
                new WorkspaceManager(
                        template,
                        new LocalFilesystem(
                                backend, LocalFsMode.ROOTED, PathPolicy.empty(), 10, null))) {
            assertEquals(
                    "main-0f3a.json",
                    manager.resolveSessionFile(RuntimeContext.empty(), "agent-a", "main-0f3a")
                            .getFileName()
                            .toString());
        }
    }

    @Test
    void safeSegmentEscapesTheReservedSetAndStaysInjective() {
        assertEquals("main-0f3a", WorkspaceManager.safeSegment("main-0f3a"));
        assertEquals("a%3Ab", WorkspaceManager.safeSegment("a:b"));
        assertEquals("a%00b%1Fc%7Fd", WorkspaceManager.safeSegment("a\u0000b\u001Fc\u007Fd"));

        // '%' has to be escaped too, otherwise the escaped form of "a:b" would collide with the
        // literal id "a%3Ab" and two sessions would share one file.
        assertEquals("a%25b", WorkspaceManager.safeSegment("a%b"));
        assertNotEquals(WorkspaceManager.safeSegment("a:b"), WorkspaceManager.safeSegment("a%3Ab"));

        // Separators are escaped, so an id can never add a segment or climb out of its directory.
        assertFalse(WorkspaceManager.safeSegment("a/../../b").contains("/"));

        assertEquals("", WorkspaceManager.safeSegment(""));
        assertNull(WorkspaceManager.safeSegment(null));
    }
}
