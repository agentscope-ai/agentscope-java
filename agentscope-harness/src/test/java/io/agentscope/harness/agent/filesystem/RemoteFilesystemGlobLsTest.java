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
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Verifies that {@link RemoteFilesystem#glob} and {@link RemoteFilesystem#ls} fall back to the
 * authoritative remote store when the local {@link WorkspaceIndex} fast path yields no results,
 * These branch-level tests deliberately simulate index states that may change between queries;
 * real workspace-relative indexes and composite routing are covered in RemoteFilesystemSpecTest.
 */
class RemoteFilesystemGlobLsTest {

    private static final RuntimeContext CTX = RuntimeContext.empty();

    @Test
    void glob_fallsBackToStoreWhenIndexHasPrefixButNoPatternMatch() {
        InMemoryStore store = new InMemoryStore();
        store.put(List.of("shared"), "/memory/notes.md", Map.of("content", "daily note"));

        WorkspaceIndex index = mock(WorkspaceIndex.class);
        when(index.hasPrefix("/memory")).thenReturn(true);
        when(index.listByPrefix("/memory")).thenReturn(List.of("/memory/irrelevant.json"));

        RemoteFilesystem fs = new RemoteFilesystem(store, List.of("shared")).withIndex(index);

        GlobResult result = fs.glob(CTX, "*.md", "/memory");
        assertTrue(result.isSuccess());
        assertEquals(
                1,
                result.matches().size(),
                () ->
                        "glob must fall back to store when index candidates do not match pattern;"
                                + " got: "
                                + result.matches());
        assertEquals("/memory/notes.md", result.matches().get(0).path());
    }

    @Test
    void glob_fallsBackToStoreWhenIndexCandidatesDisappear() {
        InMemoryStore store = new InMemoryStore();
        store.put(List.of("shared"), "/memory/notes.md", Map.of("content", "daily note"));
        WorkspaceIndex index = mock(WorkspaceIndex.class);
        when(index.hasPrefix("/memory")).thenReturn(true);
        when(index.listByPrefix("/memory")).thenReturn(List.of());

        GlobResult result =
                new RemoteFilesystem(store, List.of("shared"))
                        .withIndex(index)
                        .glob(CTX, "*.md", "/memory");

        assertTrue(result.isSuccess());
        assertEquals(
                List.of("/memory/notes.md"),
                result.matches().stream().map(info -> info.path()).toList());
    }

    @Test
    void glob_returnsIndexMatchesWhenIndexHasMatchingEntries() {
        InMemoryStore store = new InMemoryStore();
        store.put(List.of("shared"), "/memory/notes.md", Map.of("content", "daily note"));

        WorkspaceIndex index = mock(WorkspaceIndex.class);
        when(index.hasPrefix("/memory")).thenReturn(true);
        when(index.listByPrefix("/memory")).thenReturn(List.of("/memory/notes.md"));

        RemoteFilesystem fs = new RemoteFilesystem(store, List.of("shared")).withIndex(index);

        GlobResult result = fs.glob(CTX, "*.md", "/memory");
        assertTrue(result.isSuccess());
        assertEquals(1, result.matches().size());
        assertEquals("/memory/notes.md", result.matches().get(0).path());
        // Index fast path returns size 0 (metadata not fetched from store)
        assertEquals(0, result.matches().get(0).size());
    }

    @Test
    void glob_rootPath_fallsBackToStoreWhenIndexCandidatesDoNotMatch() {
        InMemoryStore store = new InMemoryStore();
        store.put(List.of("shared"), "/notes.md", Map.of("content", "root note"));

        WorkspaceIndex index = mock(WorkspaceIndex.class);
        when(index.hasPrefix("/")).thenReturn(true);
        when(index.listByPrefix("/")).thenReturn(List.of("/other.json"));

        RemoteFilesystem fs = new RemoteFilesystem(store, List.of("shared")).withIndex(index);

        GlobResult result = fs.glob(CTX, "*.md", "/");
        assertTrue(result.isSuccess());
        assertEquals(
                1,
                result.matches().size(),
                () ->
                        "glob at root must fall back to store when index candidates do not match"
                                + " pattern; got: "
                                + result.matches());
        assertEquals("/notes.md", result.matches().get(0).path());
    }

    @Test
    void glob_recursivePattern_fallsBackToStore() {
        InMemoryStore store = new InMemoryStore();
        store.put(List.of("shared"), "/memory/sub/nested.md", Map.of("content", "nested note"));

        WorkspaceIndex index = mock(WorkspaceIndex.class);
        when(index.hasPrefix("/memory")).thenReturn(true);
        when(index.listByPrefix("/memory")).thenReturn(List.of("/memory/other.txt"));

        RemoteFilesystem fs = new RemoteFilesystem(store, List.of("shared")).withIndex(index);

        GlobResult result = fs.glob(CTX, "**/*.md", "/memory");
        assertTrue(result.isSuccess());
        assertEquals(1, result.matches().size());
        assertEquals("/memory/sub/nested.md", result.matches().get(0).path());
    }

    @Test
    void ls_fallsBackToStoreWhenIndexHasPrefixButNoEntries() {
        InMemoryStore store = new InMemoryStore();
        store.put(List.of("shared"), "/memory/notes.md", Map.of("content", "daily note"));

        WorkspaceIndex index = mock(WorkspaceIndex.class);
        when(index.hasPrefix("/memory/")).thenReturn(true);
        when(index.listByPrefix("/memory/")).thenReturn(List.of());

        RemoteFilesystem fs = new RemoteFilesystem(store, List.of("shared")).withIndex(index);

        LsResult result = fs.ls(CTX, "/memory");
        assertTrue(result.isSuccess());
        assertEquals(
                1,
                result.entries().size(),
                () ->
                        "ls must fall back to store when index returns empty entries; got: "
                                + result.entries());
        assertEquals("/memory/notes.md", result.entries().get(0).path());
    }

    @Test
    void ls_returnsIndexEntriesWhenIndexHasEntries() {
        InMemoryStore store = new InMemoryStore();
        store.put(List.of("shared"), "/memory/notes.md", Map.of("content", "daily note"));

        WorkspaceIndex index = mock(WorkspaceIndex.class);
        when(index.hasPrefix("/memory/")).thenReturn(true);
        when(index.listByPrefix("/memory/")).thenReturn(List.of("/memory/notes.md"));

        RemoteFilesystem fs = new RemoteFilesystem(store, List.of("shared")).withIndex(index);

        LsResult result = fs.ls(CTX, "/memory");
        assertTrue(result.isSuccess());
        assertEquals(1, result.entries().size());
        assertEquals("/memory/notes.md", result.entries().get(0).path());
        assertEquals(0, result.entries().get(0).size());
    }

    @Test
    void ls_trailingSlashVariations_fallBackToStore() {
        InMemoryStore store = new InMemoryStore();
        store.put(List.of("shared"), "/memory/notes.md", Map.of("content", "daily note"));

        WorkspaceIndex index = mock(WorkspaceIndex.class);
        when(index.hasPrefix("/memory/")).thenReturn(true);
        when(index.listByPrefix("/memory/")).thenReturn(List.of());

        RemoteFilesystem fs = new RemoteFilesystem(store, List.of("shared")).withIndex(index);

        // Path without trailing slash
        LsResult resultWithout = fs.ls(CTX, "/memory");
        assertTrue(resultWithout.isSuccess());
        assertEquals(1, resultWithout.entries().size());
        assertEquals("/memory/notes.md", resultWithout.entries().get(0).path());

        // Path with trailing slash
        LsResult resultWith = fs.ls(CTX, "/memory/");
        assertTrue(resultWith.isSuccess());
        assertEquals(1, resultWith.entries().size());
        assertEquals("/memory/notes.md", resultWith.entries().get(0).path());
    }

    @Test
    void globAndLs_returnEmptyWhenIndexMissesAndStoreEmpty() {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem fs =
                new RemoteFilesystem(store, List.of("shared"))
                        .withIndex(mock(WorkspaceIndex.class));

        GlobResult globResult = fs.glob(CTX, "*.md", "/memory");
        assertTrue(globResult.isSuccess());
        assertTrue(globResult.matches().isEmpty());

        LsResult lsResult = fs.ls(CTX, "/memory");
        assertTrue(lsResult.isSuccess());
        assertTrue(lsResult.entries().isEmpty());
    }
}
