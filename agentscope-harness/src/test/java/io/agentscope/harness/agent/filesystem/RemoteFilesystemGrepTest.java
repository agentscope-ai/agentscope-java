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
package io.agentscope.harness.agent.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.store.InMemoryStore;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies {@link RemoteFilesystem#grep} stays correct on a multi-node deployment.
 *
 * <p>The local {@link WorkspaceIndex} is best-effort and only sees writes that pass through its
 * owning instance. On node B, an index-only grep would silently miss files that node A just wrote.
 * The implementation must fall back to a full store scan when the index returns no candidates.
 */
class RemoteFilesystemGrepTest {

    private static final RuntimeContext CTX = RuntimeContext.empty();

    @Test
    void grep_findsContentWrittenBySiblingInstance(@TempDir Path tmp) throws IOException {
        // Two RemoteFilesystem instances sharing the SAME backing store but with SEPARATE
        // workspace indexes — exactly the multi-node case.
        InMemoryStore store = new InMemoryStore();
        Path indexDirA = Files.createDirectories(tmp.resolve("nodeA"));
        Path indexDirB = Files.createDirectories(tmp.resolve("nodeB"));
        try (WorkspaceIndex indexA = WorkspaceIndex.open(indexDirA);
                WorkspaceIndex indexB = WorkspaceIndex.open(indexDirB)) {
            RemoteFilesystem nodeA =
                    new RemoteFilesystem(store, List.of("shared")).withIndex(indexA);
            RemoteFilesystem nodeB =
                    new RemoteFilesystem(store, List.of("shared")).withIndex(indexB);

            // Write via node A and seed A's index for the new file. (RemoteFilesystem.write does
            // not touch the index directly — WorkspaceManager owns index population. We simulate
            // that here so A's index sees its own write while B's index does not.) The path lives
            // under {@code memory/} so it falls within the index's tracked prefixes.
            assertTrue(nodeA.write(CTX, "/memory/notes.md", "hello SENTINEL world").isSuccess());
            indexA.upsert("memory/notes.md", 0L, null);
            assertTrue(indexA.exists("memory/notes.md"));
            assertEquals(0, indexB.listByPrefix("").size(), "node B's index must be empty");

            // Grep via node B must still find the match via the store-scan fallback. Without the
            // fallback, B's empty index would short-circuit to zero matches.
            GrepResult result = nodeB.grep(CTX, "SENTINEL", "/", null);
            assertTrue(result.isSuccess());
            assertEquals(
                    1,
                    result.matches().size(),
                    () ->
                            "node B grep must find sibling-written content via store fallback;"
                                    + " got: "
                                    + result.matches());
            assertEquals("/memory/notes.md", result.matches().get(0).path());
            assertEquals("hello SENTINEL world", result.matches().get(0).text());
        }
    }
}
