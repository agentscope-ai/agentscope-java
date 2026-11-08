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
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Regression test for <a href="https://github.com/agentscope-ai/agentscope-java/issues/1441">
 * issue #1441</a>: a workspace supplied as a relative path with redundant segments (e.g.
 * {@code "./ws"}) used to break every {@link WorkspaceManager#readManagedWorkspaceFileUtf8}
 * call. The containment guard compared a normalized candidate path against the raw
 * (un-normalized) workspace field, so the element-wise {@link Path#startsWith} never matched
 * and all managed-file reads returned an empty string — {@code memory_search} therefore
 * never saw content that {@code memory_save} had written.
 */
class WorkspaceManagerRelativeWorkspacePathTest {

    @Test
    void relativeWorkspaceWithRedundantDotSegmentStillReadsManagedFiles() throws Exception {
        // Physical location, created in normalized form: <module-cwd>/target/rel-ws-<uuid>.
        Path physical =
                Path.of("target")
                        .resolve("rel-ws-" + UUID.randomUUID())
                        .toAbsolutePath()
                        .normalize();
        Files.createDirectories(physical.resolve("memory"));
        Files.writeString(
                physical.resolve("MEMORY.md"), "- favorite number: 42", StandardCharsets.UTF_8);
        Files.writeString(
                physical.resolve("memory").resolve("2026-01-01.md"),
                "- favorite number: 42",
                StandardCharsets.UTF_8);

        // The same location expressed as a RELATIVE path with a redundant "." segment, so
        // normalize(raw) != raw — the exact shape that used to poison the containment guard.
        Path relativeWorkspace = Path.of("target", ".", physical.getFileName().toString());
        RuntimeContext rc = RuntimeContext.builder().userId("u1").build();

        try {
            try (WorkspaceManager manager = new WorkspaceManager(relativeWorkspace)) {
                assertEquals(
                        "- favorite number: 42",
                        manager.readManagedWorkspaceFileUtf8(rc, "MEMORY.md"));
                assertEquals(
                        "- favorite number: 42",
                        manager.readManagedWorkspaceFileUtf8(rc, "memory/2026-01-01.md"));

                List<String> paths = manager.listMemoryFilePaths(rc);
                assertTrue(paths.contains("MEMORY.md"), () -> "paths=" + paths);
                assertTrue(paths.contains("memory/2026-01-01.md"), () -> "paths=" + paths);
            }
        } finally {
            deleteRecursively(physical);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(
                            p -> {
                                try {
                                    Files.deleteIfExists(p);
                                } catch (IOException ignored) {
                                    // best-effort cleanup
                                }
                            });
        }
    }
}
