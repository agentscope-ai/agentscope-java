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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.workspace.WorkspaceIndex;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RemoteFilesystemExistsTest {

    private static final List<String> NS = List.of("workspace");
    private static final RuntimeContext RT = RuntimeContext.empty();

    @ParameterizedTest
    @CsvSource({"/target/child.txt, true", "/target, true", "/missing, false"})
    void existenceChecksDoNotReadValues(String path, boolean expected) {
        RecordingStore store = new RecordingStore();
        store.put(NS, "/target/child.txt", Map.of("content", "child"));
        for (int i = 0; i < 249; i++) {
            store.put(NS, "/other/" + i, Map.of("content", "unrelated"));
        }

        assertEquals(expected, new RemoteFilesystem(store, NS).exists(RT, path));
        assertEquals(0, store.valueReads, "existence checks must not fetch file values");
        assertEquals(0, store.searches, "existence checks must not enumerate StoreItems");
    }

    @ParameterizedTest
    @CsvSource({
        "target, true",
        "/target/, true",
        "/target/child.txt, true",
        "/Target, false",
        "/tar, false",
        "/, false",
        "//target, false",
        "/target//, false"
    })
    void preservesPathMatching(String path, boolean expected) {
        InMemoryStore store = new InMemoryStore();
        store.put(NS, "/target/child.txt", Map.of());

        assertEquals(expected, new RemoteFilesystem(store, NS).exists(RT, path));
    }

    @Test
    void preservesLegacyKeysWithoutLeadingSlash() {
        InMemoryStore store = new InMemoryStore();
        store.put(NS, "legacy/child.txt", Map.of());
        RemoteFilesystem filesystem = new RemoteFilesystem(store, NS);

        assertTrue(filesystem.exists(RT, "/legacy"));
        assertTrue(filesystem.exists(RT, "legacy/"));
        assertFalse(filesystem.exists(RT, "/legacy/child.txt"));
        assertFalse(filesystem.exists(RT, "//legacy"));
        assertFalse(filesystem.exists(RT, "/"));
    }

    @Test
    void rootStillMatchesAnExactKeyOrADoubleSlashChild() {
        InMemoryStore store = new InMemoryStore();
        RemoteFilesystem filesystem = new RemoteFilesystem(store, NS);
        store.put(NS, "/", Map.of());
        assertTrue(filesystem.exists(RT, "/"));
        store.delete(NS, "/");
        store.put(NS, "//child", Map.of());
        assertTrue(filesystem.exists(RT, "/"));
    }

    @Test
    void directorySearchIncludesChildNamespacesButNotSiblingNamespaces() {
        InMemoryStore store = new InMemoryStore();
        store.put(List.of("workspace", "child"), "/nested/file.txt", Map.of());
        store.put(List.of("workspace-other"), "/sibling/file.txt", Map.of());
        RemoteFilesystem filesystem = new RemoteFilesystem(store, NS);

        assertTrue(filesystem.exists(RT, "/nested"));
        assertFalse(filesystem.exists(RT, "/nested/file.txt"));
        assertFalse(filesystem.exists(RT, "/sibling"));
    }

    @Test
    void blankPathsAndEmptyStoresReturnFalse() {
        RemoteFilesystem filesystem = new RemoteFilesystem(new InMemoryStore(), NS);
        assertFalse(filesystem.exists(RT, null));
        assertFalse(filesystem.exists(RT, " "));
        assertFalse(filesystem.exists(RT, "/missing"));
    }

    @Test
    void indexHitsSkipTheRemoteStoreAndMissesFallBack() {
        RecordingStore store = new RecordingStore();
        store.put(NS, "/remote/file.txt", Map.of());
        WorkspaceIndex index = mock(WorkspaceIndex.class);
        when(index.exists("/cached/file.txt")).thenReturn(true);
        when(index.hasPrefix("/cached/")).thenReturn(true);
        RemoteFilesystem filesystem = new RemoteFilesystem(store, NS).withIndex(index);

        assertTrue(filesystem.exists(RT, "/cached/file.txt"));
        assertTrue(filesystem.exists(RT, "/cached"));
        assertTrue(filesystem.exists(RT, "/remote"));
        assertFalse(filesystem.exists(RT, "/missing"));
        assertEquals(0, store.valueReads);
        assertEquals(0, store.searches);
    }

    private static class RecordingStore extends InMemoryStore {
        int valueReads;
        int searches;

        @Override
        public StoreItem get(List<String> namespace, String key) {
            valueReads++;
            return super.get(namespace, key);
        }

        @Override
        public List<StoreItem> search(List<String> namespace, int limit, int offset) {
            searches++;
            return super.search(namespace, limit, offset);
        }
    }
}
