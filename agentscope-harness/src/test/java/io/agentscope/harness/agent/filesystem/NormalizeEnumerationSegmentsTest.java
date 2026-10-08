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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Table-driven coverage for {@link AbstractFilesystem#normalizeEnumerationSegments} and its
 * composite integration (#3378): the resolved form reaches every branch — routing, root
 * aggregation, and default-backend delegation — and clean strings pass through
 * byte-for-byte.
 */
class NormalizeEnumerationSegmentsTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    @Test
    void normalizationTable() {
        // [input, expected]
        String[][] table = {
            {"/alice/../bob", "/bob"},
            {"/../bob", "/bob"},
            {"/tmp/..", "/"},
            {"//a//b", "/a/b"},
            {"/a/./b", "/a/b"},
            {"./", "."},
            {"a/../../b", "../b"},
            {"C:\\a\\..\\b", "C:/b"},
        };
        for (String[] row : table) {
            assertEquals(row[1], AbstractFilesystem.normalizeEnumerationSegments(row[0]), row[0]);
        }
        assertNull(AbstractFilesystem.normalizeEnumerationSegments(null));
    }

    @Test
    void cleanStringsPassThroughByteForByte() {
        String clean = "/memories/2026-05-13.md";
        assertSame(clean, AbstractFilesystem.normalizeEnumerationSegments(clean));

        // A backslash is normalized to a forward separator (Windows-spelling support) —
        // byte-for-byte passthrough only applies to strings with nothing to resolve.
        assertEquals(
                "notes/draft/final",
                AbstractFilesystem.normalizeEnumerationSegments("notes/draft\\final"));
    }

    @Test
    void relativeTraversalStaysLiteralSoTheGuardFires() {
        // A leading or unresolvable ".." stays literal so the backend traversal guard
        // (SecurityException) still fires instead of silently re-anchoring; a ".." that
        // cancels a real segment is resolved.
        assertEquals(
                "../other-user", AbstractFilesystem.normalizeEnumerationSegments("../other-user"));
        assertEquals("..", AbstractFilesystem.normalizeEnumerationSegments(".."));
        assertEquals("x", AbstractFilesystem.normalizeEnumerationSegments("sub/../x"));
    }

    @Test
    void routedPrefixWithTraversal_cannotEscapeTheRoute(@TempDir Path workspace) throws Exception {
        InMemoryStore routeStore = new InMemoryStore();
        RemoteFilesystem route =
                new RemoteFilesystem(
                        routeStore, List.of("agents", "agent-a", "users", "user-1", "sessions"));
        LocalFilesystem defaultBackend =
                new LocalFilesystem(workspace, false, 10, rc -> List.of("local-user"));
        AbstractFilesystem composite =
                new CompositeFilesystem(defaultBackend, Map.of("/memories/", route));

        // /memories/../bob canonicalizes to /bob — outside the /memories/ route, so it must
        // NOT reach the route backend; it aggregates/delegates via the default backend, which
        // is contained in the namespaced workspace.
        LsResult ls = composite.ls(RT, "/memories/../bob");
        assertFalse(ls.isSuccess(), "bob does not exist; any success would be a leak");
        assertTrue(
                routeStore.size() == 0,
                "the route store must stay untouched by a ..-escape attempt");

        // A real file inside the route remains reachable through its canonical path.
        composite.uploadFiles(RT, List.of(Map.entry("/memories/notes.md", "hi".getBytes())));
        LsResult routed = composite.ls(RT, "/memories");
        assertTrue(routed.isSuccess() && !routed.entries().isEmpty());
        Path bobFile = workspace.resolve("bob");
        assertFalse(Files.exists(bobFile), "nothing may be created outside the workspace");
    }
}
