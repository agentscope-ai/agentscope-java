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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression tests for issue #3197: {@link RemoteFilesystem#ls} and {@link RemoteFilesystem#glob}
 * must not treat an empty result from the local index fast path as authoritative. The index is
 * best-effort and node-local; when its filtered result is empty, the operation must fall through
 * to the authoritative remote-store scan, mirroring {@code grep()}.
 */
class RemoteFilesystemIndexFallbackTest {

    private static final RuntimeContext CTX = RuntimeContext.empty();

    @Test
    void ls_fallsBackToStore_whenIndexFastPathYieldsNoEntries() {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem fs = new RemoteFilesystem(store, List.of("shared"));
        assertTrue(fs.write(CTX, "/memory/notes.md", "hello").isSuccess());

        // Index claims the prefix is known but returns no entries — the stale-index shape
        // described in #3197 (also reachable via a concurrent index change).
        WorkspaceIndex index = mock(WorkspaceIndex.class);
        when(index.hasPrefix("/memory/")).thenReturn(true);
        when(index.listByPrefix("/memory/")).thenReturn(List.of());
        fs.withIndex(index);

        LsResult result = fs.ls(CTX, "/memory");
        assertTrue(result.isSuccess());
        assertEquals(
                1,
                result.entries().size(),
                () ->
                        "ls must fall back to the store when the index fast path is empty: "
                                + result);
        assertEquals("/memory/notes.md", result.entries().get(0).path());
    }

    @Test
    void glob_fallsBackToStore_whenIndexFastPathYieldsNoMatches() {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem fs = new RemoteFilesystem(store, List.of("shared"));
        assertTrue(fs.write(CTX, "/memory/facts.md", "facts").isSuccess());

        // Index claims the prefix is known but its candidates are filtered out by the glob,
        // so the fast path produces zero results while the store still has a matching file.
        WorkspaceIndex index = mock(WorkspaceIndex.class);
        when(index.hasPrefix("/memory")).thenReturn(true);
        when(index.listByPrefix("/memory")).thenReturn(List.of("/memory/other.txt"));
        fs.withIndex(index);

        GlobResult result = fs.glob(CTX, "*.md", "/memory");
        assertTrue(result.isSuccess());
        assertEquals(
                1,
                result.matches().size(),
                () ->
                        "glob must fall back to the store when the index fast path is empty: "
                                + result);
        assertEquals("/memory/facts.md", result.matches().get(0).path());
    }
}
