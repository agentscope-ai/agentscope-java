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
package io.agentscope.extensions.oss.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.state.State;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Behavioural tests for {@link AbstractOssAgentStateStore} using the in-memory adapter. */
class AbstractOssAgentStateStoreTest {

    private InMemoryOssAdapter adapter;
    private AbstractOssAgentStateStore store;

    /** Simple {@link State} value used across the tests. */
    public record Note(String text) implements State {}

    private static final class TestStore extends AbstractOssAgentStateStore {
        TestStore(OssAdapter adapter, String bucketName, String keyPrefix) {
            super(adapter, bucketName, keyPrefix);
        }
    }

    @BeforeEach
    void setUp() {
        adapter = new InMemoryOssAdapter();
        store = new TestStore(adapter, "test-bucket", "test/state/");
    }

    @Test
    void saveAndGetSingleValue() {
        store.save("alice", "s1", "profile", new Note("hello"));
        Optional<Note> loaded = store.get("alice", "s1", "profile", Note.class);
        assertTrue(loaded.isPresent());
        assertEquals("hello", loaded.get().text());
        assertTrue(adapter.objects.containsKey("test/state/alice/s1/profile.json"));
    }

    @Test
    void getReturnsEmptyWhenMissing() {
        assertTrue(store.get("alice", "s1", "profile", Note.class).isEmpty());
    }

    @Test
    void saveAndLoadList() {
        store.save("alice", "s1", "log", List.of(new Note("a"), new Note("b"), new Note("c")));
        List<Note> loaded = store.getList("alice", "s1", "log", Note.class);
        assertEquals(3, loaded.size());
        assertEquals("a", loaded.get(0).text());
        assertTrue(adapter.objects.containsKey("test/state/alice/s1/log.list.json"));
        assertTrue(adapter.objects.containsKey("test/state/alice/s1/log.list.hash"));
    }

    @Test
    void appendedListUsesIncrementalRewriteWhenPossible() {
        store.save("alice", "s1", "log", List.of(new Note("a")));
        byte[] hashV1 = adapter.objects.get("test/state/alice/s1/log.list.hash").clone();

        store.save("alice", "s1", "log", List.of(new Note("a"), new Note("b")));
        byte[] hashV2 = adapter.objects.get("test/state/alice/s1/log.list.hash").clone();

        // hash must change; list body written both times, but the hash object always updates
        assertFalse(java.util.Arrays.equals(hashV1, hashV2));
        List<Note> loaded = store.getList("alice", "s1", "log", Note.class);
        assertEquals(2, loaded.size());
    }

    @Test
    void existsAndDeleteSessionAll() {
        store.save("alice", "s1", "profile", new Note("hi"));
        store.save("alice", "s1", "log", List.of(new Note("a")));
        assertTrue(store.exists("alice", "s1"));
        store.delete("alice", "s1");
        assertFalse(store.exists("alice", "s1"));
        assertTrue(adapter.objects.isEmpty());
    }

    @Test
    void deleteSingleKeyRemovesAllVariants() {
        store.save("alice", "s1", "log", List.of(new Note("a")));
        store.delete("alice", "s1", "log");
        assertFalse(adapter.objects.containsKey("test/state/alice/s1/log.list.json"));
        assertFalse(adapter.objects.containsKey("test/state/alice/s1/log.list.hash"));
    }

    @Test
    void listSessionIdsReturnsUnique() {
        store.save("alice", "s1", "k", new Note("x"));
        store.save("alice", "s2", "k", new Note("x"));
        store.save("alice", "s1", "k2", new Note("y"));
        Set<String> ids = store.listSessionIds("alice");
        assertEquals(Set.of("s1", "s2"), ids);
    }

    @Test
    void anonymousUserFallback() {
        store.save(null, "s1", "k", new Note("x"));
        assertTrue(adapter.objects.containsKey("test/state/__anon__/s1/k.json"));
    }

    @Test
    void closeDelegatesToAdapter() {
        store.close();
        assertTrue(adapter.closed);
    }
}
