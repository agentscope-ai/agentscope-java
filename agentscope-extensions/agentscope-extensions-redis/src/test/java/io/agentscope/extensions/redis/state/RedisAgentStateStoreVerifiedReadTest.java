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
package io.agentscope.extensions.redis.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the verified re-read fallback {@code RedisAgentStateStore#getVersioned} uses
 * when the {@link RedisClientAdapter} does not support an atomic MGET
 * ({@code supportsAtomicMget() == false}): payload is re-read after the version counter and the
 * pair is accepted only when both payload reads agree. Pure in-memory fake, no Docker required.
 */
class RedisAgentStateStoreVerifiedReadTest {

    private static final String PREFIX = "agentscope:session:";
    private static final String PAYLOAD_KEY = PREFIX + "{u/s}:agent:profile";
    private static final String VERSION_KEY = PAYLOAD_KEY + ":ver";

    /** Minimal State payload. */
    record TestState(String value) implements State {}

    /**
     * In-memory adapter WITHOUT an atomic MGET: it does not override {@code mget} (a legacy
     * adapter would inherit the sequential default), and to prove the store never calls mget on
     * this path the override here fails the test if invoked. Payload reads can be disturbed to
     * simulate a concurrent writer landing between the two reads of one attempt.
     */
    private static class NonAtomicAdapter implements RedisClientAdapter {
        final Map<String, String> data = new HashMap<>();
        int payloadGetCalls;

        /** Remaining payload reads that return racing (ever-changing) values. */
        int disturbances;

        @Override
        public void set(String key, String value) {
            data.put(key, value);
        }

        @Override
        public String get(String key) {
            if (PAYLOAD_KEY.equals(key)) {
                payloadGetCalls++;
                if (disturbances > 0) {
                    // A concurrent writer keeps changing the payload: every disturbed read sees
                    // a different value, so the two reads of an attempt never agree by accident.
                    return "{\"value\":\"racing-" + (disturbances--) + "\"}";
                }
            }
            return data.get(key);
        }

        @Override
        public List<String> mget(String... keys) {
            throw new UnsupportedOperationException(
                    "mget must not be used for a non-atomic adapter");
        }

        @Override
        public void rightPushList(String key, String value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> rangeList(String key, long start, long end) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getListLength(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void deleteKeys(String... keys) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addToSet(String key, String member) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<String> getSetMembers(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getSetSize(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean keyExists(String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<String> findKeysByPattern(String pattern) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long evalScript(String script, List<String> keys, List<String> args) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {}
    }

    private RedisAgentStateStore newStore(NonAtomicAdapter adapter) {
        return RedisAgentStateStore.builder().clientAdapter(adapter).build();
    }

    @Test
    @DisplayName("non-atomic adapter: stable read returns payload+version via 3 gets, never mget")
    void verifiedRead_stableData() {
        NonAtomicAdapter adapter = new NonAtomicAdapter();
        adapter.data.put(PAYLOAD_KEY, "{\"value\":\"v1\"}");
        adapter.data.put(VERSION_KEY, "7");

        VersionedState<TestState> result =
                newStore(adapter).getVersioned("u", "s", "agent:profile", TestState.class);

        assertEquals("v1", result.value().value());
        assertEquals(7L, result.version());
        // payload, version, payload again (verified), in one attempt
        assertEquals(2, adapter.payloadGetCalls);
    }

    @Test
    @DisplayName("non-atomic adapter: a racing payload read triggers retry until reads agree")
    void verifiedRead_retriesOnRace() {
        NonAtomicAdapter adapter = new NonAtomicAdapter();
        adapter.data.put(PAYLOAD_KEY, "{\"value\":\"v1\"}");
        adapter.data.put(VERSION_KEY, "7");
        adapter.disturbances = 2; // first attempt disagrees (racing-2 vs racing-1), second is clean

        VersionedState<TestState> result =
                newStore(adapter).getVersioned("u", "s", "agent:profile", TestState.class);

        assertEquals("v1", result.value().value());
        assertEquals(7L, result.version());
        assertEquals(4, adapter.payloadGetCalls); // 2 racing + 2 clean
    }

    @Test
    @DisplayName("non-atomic adapter: persistent races fail loudly instead of returning torn data")
    void verifiedRead_persistentRaceFails() {
        NonAtomicAdapter adapter = new NonAtomicAdapter();
        adapter.data.put(PAYLOAD_KEY, "{\"value\":\"v1\"}");
        adapter.data.put(VERSION_KEY, "7");
        adapter.disturbances = Integer.MAX_VALUE; // never agrees

        RuntimeException e =
                assertThrows(
                        RuntimeException.class,
                        () ->
                                newStore(adapter)
                                        .getVersioned("u", "s", "agent:profile", TestState.class));
        // The consistent-read failure is surfaced (wrapped) rather than swallowed.
        Throwable cause = e;
        boolean found = false;
        while (cause != null) {
            if (String.valueOf(cause.getMessage()).contains("consistent versioned read")) {
                found = true;
                break;
            }
            cause = cause.getCause();
        }
        assertTrue(found, "expected the consistent-read failure in the cause chain: " + e);
    }
}
