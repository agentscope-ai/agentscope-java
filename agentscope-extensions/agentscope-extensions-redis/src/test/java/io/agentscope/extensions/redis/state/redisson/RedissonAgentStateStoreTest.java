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
package io.agentscope.extensions.redis.state.redisson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.core.state.AgentStateStore;
import io.agentscope.core.state.State;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RList;
import org.redisson.api.RScript;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.Codec;

/**
 * Unit tests for {@link RedissonAgentStateStore} optimistic-concurrency versioning and list-state
 * save semantics.
 *
 * <p>Uses Mockito to mock Redisson resources so no real Redis server is required. The list tests
 * back the mocked {@link RList} and {@link RBucket} with in-memory state so that save/list round
 * trips behave like a real Redis list plus its change-detection hash.
 */
@DisplayName("RedissonAgentStateStore versioning and list semantics")
class RedissonAgentStateStoreTest {

    record TestState(String value) implements State {}

    private RedissonClient redissonClient;

    private RList<String> rList;

    @BeforeEach
    void setUp() {
        redissonClient = mock(RedissonClient.class);
    }

    @Test
    @DisplayName("saveIfVersion with UNVERSIONED delegates to eval with UNCONDITIONAL sentinel")
    void saveIfVersionUnconditionalDelegatesToEval() {
        RedissonAgentStateStore store =
                RedissonAgentStateStore.builder()
                        .redissonClient(redissonClient)
                        .keyPrefix("test:")
                        .build();

        RScript rScript = mock(RScript.class);
        when(redissonClient.getScript(any(Codec.class))).thenReturn(rScript);
        doReturn(5L)
                .when(rScript)
                .eval(
                        any(RScript.Mode.class),
                        anyString(),
                        any(RScript.ReturnType.class),
                        any(List.class),
                        any(Object[].class));

        long version =
                store.saveIfVersion(
                        "user",
                        "session",
                        "agent_state",
                        new TestState("v"),
                        AgentStateStore.UNVERSIONED);

        assertEquals(5L, version);
        // Regression: the UNVERSIONED path must not call getVersioned (which reads back as
        // State.class — a marker interface Jackson cannot instantiate).
        verify(redissonClient, never()).getBucket(any(), any());
    }

    @Test
    @DisplayName("save(List) applies a shrink: save 3 items then 1 item yields a 1-item list")
    void saveListAppliesShrink() {
        List<String> backing = new ArrayList<>();
        AtomicReference<String> storedHash = new AtomicReference<>();
        RedissonAgentStateStore store = storeWithListStubs(backing, storedHash);

        store.save(
                null,
                "session",
                "messages",
                List.of(new TestState("a"), new TestState("b"), new TestState("c")));
        assertEquals(
                List.of(new TestState("a"), new TestState("b"), new TestState("c")),
                store.getList(null, "session", "messages", TestState.class));

        // Shrinking the list must trigger a full rewrite, not a no-op.
        store.save(null, "session", "messages", List.of(new TestState("a")));

        assertEquals(
                List.of(new TestState("a")),
                store.getList(null, "session", "messages", TestState.class));
    }

    @Test
    @DisplayName("save(List) applies a same-size edit: old content is not kept forever")
    void saveListAppliesSameSizeEdit() {
        List<String> backing = new ArrayList<>();
        AtomicReference<String> storedHash = new AtomicReference<>();
        RedissonAgentStateStore store = storeWithListStubs(backing, storedHash);

        store.save(null, "session", "messages", List.of(new TestState("a"), new TestState("b")));
        store.save(
                null, "session", "messages", List.of(new TestState("a"), new TestState("edited")));

        assertEquals(
                List.of(new TestState("a"), new TestState("edited")),
                store.getList(null, "session", "messages", TestState.class));
    }

    @Test
    @DisplayName("save(List) keeps the incremental append path and persists the change hash")
    void saveListStillAppendsIncrementally() {
        List<String> backing = new ArrayList<>();
        AtomicReference<String> storedHash = new AtomicReference<>();
        RedissonAgentStateStore store = storeWithListStubs(backing, storedHash);

        store.save(null, "session", "messages", List.of(new TestState("a"), new TestState("b")));
        store.save(
                null,
                "session",
                "messages",
                List.of(new TestState("a"), new TestState("b"), new TestState("c")));

        assertEquals(
                List.of(new TestState("a"), new TestState("b"), new TestState("c")),
                store.getList(null, "session", "messages", TestState.class));
        // Append-only saves must not rewrite the whole list.
        verify(rList, never()).delete();
        // The change-detection hash must be persisted for later comparisons.
        assertNotNull(storedHash.get());
    }

    private RedissonAgentStateStore storeWithListStubs(
            List<String> backing, AtomicReference<String> storedHash) {
        this.rList = bindListStubs(backing, storedHash);
        return RedissonAgentStateStore.builder()
                .redissonClient(redissonClient)
                .keyPrefix("test:")
                .build();
    }

    @SuppressWarnings("unchecked")
    private RList<String> bindListStubs(List<String> backing, AtomicReference<String> storedHash) {
        RList<String> rList = mock(RList.class);
        when(rList.size()).thenAnswer(inv -> backing.size());
        when(rList.isEmpty()).thenAnswer(inv -> backing.isEmpty());
        when(rList.add(anyString()))
                .thenAnswer(
                        inv -> {
                            backing.add(inv.getArgument(0, String.class));
                            return true;
                        });
        when(rList.delete())
                .thenAnswer(
                        inv -> {
                            backing.clear();
                            return true;
                        });
        // A fresh snapshot iterator per call: the backing list mutates between reads.
        when(rList.iterator()).thenAnswer(inv -> List.copyOf(backing).iterator());

        RBucket<String> hashBucket = mock(RBucket.class);
        when(hashBucket.get()).thenAnswer(inv -> storedHash.get());
        doAnswer(
                        inv -> {
                            storedHash.set(inv.getArgument(0, String.class));
                            return null;
                        })
                .when(hashBucket)
                .set(anyString());

        RSet<String> keysSet = mock(RSet.class);
        when(keysSet.add(anyString())).thenReturn(true);

        // doReturn/when style: the generic <V> client methods do not infer V through
        // when(...).thenReturn(...), so the explicit return form is required here.
        doReturn(rList).when(redissonClient).getList(anyString(), any(Codec.class));
        doReturn(hashBucket).when(redissonClient).getBucket(anyString(), any(Codec.class));
        doReturn(keysSet).when(redissonClient).getSet(anyString(), any(Codec.class));
        return rList;
    }
}
