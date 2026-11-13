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

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** {@link RemoteFilesystem#exists} must not load the whole namespace to answer one question. */
class RemoteFilesystemExistsScanTest {

    private static final RuntimeContext RT = RuntimeContext.empty();
    private static final List<String> NS = List.of("ns");

    /** Counts how many pages the filesystem asks the store for. */
    private static final class CountingStore extends InMemoryStore {
        final AtomicInteger searches = new AtomicInteger();

        @Override
        public List<StoreItem> search(List<String> namespace, int limit, int offset) {
            searches.incrementAndGet();
            return super.search(namespace, limit, offset);
        }
    }

    private static CountingStore storeWithManyFiles() {
        CountingStore store = new CountingStore();
        store.put(NS, "/aaa/first.md", Map.of("content", "x"));
        for (int i = 0; i < 350; i++) {
            store.put(NS, "/zzz/file-" + i + ".md", Map.of("content", "x"));
        }
        return store;
    }

    @Test
    void exists_directoryStopsPagingAtFirstChild() {
        CountingStore store = storeWithManyFiles();
        RemoteFilesystem fs = new RemoteFilesystem(store, NS);

        assertTrue(fs.exists(RT, "/aaa"));
        assertEquals(1, store.searches.get(), "the first page already holds a child of /aaa");
    }

    @Test
    void exists_directoryFoundOnLaterPage() {
        CountingStore store = storeWithManyFiles();
        RemoteFilesystem fs = new RemoteFilesystem(store, NS);

        assertTrue(fs.exists(RT, "/zzz"));
    }

    @Test
    void exists_missingPathIsFalse() {
        CountingStore store = storeWithManyFiles();
        RemoteFilesystem fs = new RemoteFilesystem(store, NS);

        assertFalse(fs.exists(RT, "/nope"));
        assertFalse(fs.exists(RT, "/aa"), "a name prefix is not a directory");
    }
}
