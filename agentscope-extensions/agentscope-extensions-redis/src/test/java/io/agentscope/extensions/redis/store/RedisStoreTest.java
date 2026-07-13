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
package io.agentscope.extensions.redis.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import redis.clients.jedis.UnifiedJedis;

class RedisStoreTest {

    @Mock private UnifiedJedis jedis;

    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
    }

    @AfterEach
    void tearDown() throws Exception {
        reset(jedis);
        if (mocks != null) {
            mocks.close();
        }
    }

    // -------------------------------------------------------------------------
    //  Constructor tests
    // -------------------------------------------------------------------------

    @Test
    void constructorRejectsNullJedis() {
        assertThrows(NullPointerException.class, () -> new RedisStore(null));
    }

    @Test
    void constructorRejectsNullObjectMapper() {
        assertThrows(NullPointerException.class, () -> new RedisStore(jedis, "prefix", null));
    }

    @Test
    void constructorWithDefaultPrefix() {
        RedisStore store = new RedisStore(jedis);
        assertNotNull(store);
    }

    @Test
    void constructorNormalizesPrefixWithoutTrailingColon() {
        RedisStore store = new RedisStore(jedis, "myprefix");
        assertNotNull(store);
        // Verify via key generation by doing a put — the key should use "myprefix:"
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn("1");
        store.put(List.of("ns"), "key", Map.of("k", "v"));
        verify(jedis)
                .eval(
                        anyString(),
                        eq(List.of("myprefix:item:{ns}\0key", "myprefix:idx:{ns}")),
                        anyList());
    }

    @Test
    void constructorNormalizesPrefixWithTrailingColon() {
        RedisStore store = new RedisStore(jedis, "myprefix:");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn("1");
        store.put(List.of("ns"), "key", Map.of("k", "v"));
        verify(jedis)
                .eval(
                        anyString(),
                        eq(List.of("myprefix:item:{ns}\0key", "myprefix:idx:{ns}")),
                        anyList());
    }

    @Test
    void constructorFallsBackToDefaultOnBlankPrefix() {
        RedisStore store = new RedisStore(jedis, "");
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn("1");
        store.put(List.of("ns"), "key", Map.of("k", "v"));
        verify(jedis)
                .eval(
                        anyString(),
                        eq(List.of("agentscope:store:item:{ns}\0key", "agentscope:store:idx:{ns}")),
                        anyList());
    }

    // -------------------------------------------------------------------------
    //  Hash tag / key generation tests
    // -------------------------------------------------------------------------

    @Test
    void itemKeyContainsHashTag() {
        RedisStore store = new RedisStore(jedis);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn("1");
        store.put(List.of("a", "b"), "mykey", Map.of());

        // item key = prefix + "item:{" + ns + "}\0" + key
        // idx key  = prefix + "idx:{" + ns + "}"
        verify(jedis)
                .eval(
                        anyString(),
                        eq(
                                List.of(
                                        "agentscope:store:item:{a\0b}\0mykey",
                                        "agentscope:store:idx:{a\0b}")),
                        anyList());
    }

    @Test
    void itemKeyAndIndexKeyShareSameHashTag() {
        RedisStore store = new RedisStore(jedis);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn("1");
        store.put(List.of("sessions", "sid123", "fs"), "file.txt", Map.of("data", 1));

        List<String> expectedKeys =
                List.of(
                        "agentscope:store:item:{sessions\0sid123\0fs}\0file.txt",
                        "agentscope:store:idx:{sessions\0sid123\0fs}");
        verify(jedis).eval(anyString(), eq(expectedKeys), anyList());
    }

    @Test
    void hashTagContainsOnlyNamespacePath() {
        // Verify that the hash tag wraps only the namespace, not the key
        RedisStore store = new RedisStore(jedis);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn("1");
        store.put(List.of("ns"), "k", Map.of());

        // The "{" starts right after "item:" and "}" is before "\0k"
        verify(jedis)
                .eval(
                        anyString(),
                        eq(List.of("agentscope:store:item:{ns}\0k", "agentscope:store:idx:{ns}")),
                        anyList());
    }

    @Test
    void singleComponentNamespaceHasHashTag() {
        RedisStore store = new RedisStore(jedis);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn("1");
        store.put(List.of("single"), "k", Map.of());

        verify(jedis)
                .eval(
                        anyString(),
                        eq(
                                List.of(
                                        "agentscope:store:item:{single}\0k",
                                        "agentscope:store:idx:{single}")),
                        anyList());
    }

    // -------------------------------------------------------------------------
    //  Namespace validation tests
    // -------------------------------------------------------------------------

    @Test
    void namespaceSegmentRejectsOpenBrace() {
        RedisStore store = new RedisStore(jedis);
        assertThrows(
                IllegalArgumentException.class,
                () -> store.put(List.of("ns{inner"), "k", Map.of()));
    }

    @Test
    void namespaceSegmentRejectsCloseBrace() {
        RedisStore store = new RedisStore(jedis);
        assertThrows(
                IllegalArgumentException.class, () -> store.put(List.of("ns}end"), "k", Map.of()));
    }

    @Test
    void namespaceSegmentRejectsBothBraces() {
        RedisStore store = new RedisStore(jedis);
        assertThrows(
                IllegalArgumentException.class, () -> store.put(List.of("{full}"), "k", Map.of()));
    }

    @Test
    void namespaceSegmentRejectsBracesInMiddleSegment() {
        RedisStore store = new RedisStore(jedis);
        assertThrows(
                IllegalArgumentException.class,
                () -> store.put(Arrays.asList("valid", "in{valid", "also-valid"), "k", Map.of()));
    }

    @Test
    void namespaceCannotBeNull() {
        RedisStore store = new RedisStore(jedis);
        assertThrows(NullPointerException.class, () -> store.put(null, "k", Map.of()));
    }

    @Test
    void namespaceSegmentCannotBeNull() {
        RedisStore store = new RedisStore(jedis);
        assertThrows(
                IllegalArgumentException.class,
                () -> store.put(Arrays.asList("a", null), "k", Map.of()));
    }

    @Test
    void namespaceSegmentCannotContainNul() {
        RedisStore store = new RedisStore(jedis);
        assertThrows(
                IllegalArgumentException.class, () -> store.put(List.of("a\0b"), "k", Map.of()));
    }

    // -------------------------------------------------------------------------
    //  Key validation tests
    // -------------------------------------------------------------------------

    @Test
    void keyCannotBeNull() {
        RedisStore store = new RedisStore(jedis);
        assertThrows(
                IllegalArgumentException.class, () -> store.put(List.of("ns"), null, Map.of()));
    }

    @Test
    void keyCannotBeEmpty() {
        RedisStore store = new RedisStore(jedis);
        assertThrows(IllegalArgumentException.class, () -> store.put(List.of("ns"), "", Map.of()));
    }

    @Test
    void keyCannotContainNul() {
        RedisStore store = new RedisStore(jedis);
        assertThrows(
                IllegalArgumentException.class,
                () -> store.put(List.of("ns"), "bad\0key", Map.of()));
    }

    // -------------------------------------------------------------------------
    //  putIfVersion validation tests
    // -------------------------------------------------------------------------

    @Test
    void putIfVersionRejectsNegativeExpectedVersion() {
        RedisStore store = new RedisStore(jedis);
        assertThrows(
                IllegalArgumentException.class,
                () -> store.putIfVersion(List.of("ns"), "k", Map.of(), -1L));
    }

    // -------------------------------------------------------------------------
    //  get tests
    // -------------------------------------------------------------------------

    @Test
    void getReturnsItem() {
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("value", "{\"k\":\"v\"}");
        hash.put("version", "3");
        when(jedis.hgetAll("agentscope:store:item:{ns}\0mykey")).thenReturn(hash);

        RedisStore store = new RedisStore(jedis);
        StoreItem item = store.get(List.of("ns"), "mykey");

        assertNotNull(item);
        assertEquals("mykey", item.key());
        assertEquals("v", item.value().get("k"));
        assertEquals(3L, item.version());
    }

    @Test
    void getReturnsNullWhenNotFound() {
        when(jedis.hgetAll(anyString())).thenReturn(Collections.emptyMap());

        RedisStore store = new RedisStore(jedis);
        assertNull(store.get(List.of("ns"), "nope"));
    }

    @Test
    void getReturnsNullWhenRedisReturnsNull() {
        when(jedis.hgetAll(anyString())).thenReturn(null);

        RedisStore store = new RedisStore(jedis);
        assertNull(store.get(List.of("ns"), "nope"));
    }

    @Test
    void getToleratesInvalidVersion() {
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("value", "{}");
        hash.put("version", "not-a-number");
        when(jedis.hgetAll(anyString())).thenReturn(hash);

        RedisStore store = new RedisStore(jedis);
        StoreItem item = store.get(List.of("ns"), "k");

        assertNotNull(item);
        assertEquals(0L, item.version());
    }

    // -------------------------------------------------------------------------
    //  put tests
    // -------------------------------------------------------------------------

    @Test
    void putEvalsScriptWithCorrectKeys() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn("1");

        RedisStore store = new RedisStore(jedis);
        store.put(List.of("a", "b"), "k", Map.of("x", 1));

        verify(jedis)
                .eval(
                        eq(RedisStoreHelper.PUT_SCRIPT),
                        eq(
                                List.of(
                                        "agentscope:store:item:{a\0b}\0k",
                                        "agentscope:store:idx:{a\0b}")),
                        anyList());
    }

    @Test
    void putSerializesNullValueAsEmptyMap() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn("1");

        RedisStore store = new RedisStore(jedis);
        store.put(List.of("ns"), "k", null);

        verify(jedis).eval(anyString(), anyList(), eq(List.of("{}", "k")));
    }

    // -------------------------------------------------------------------------
    //  putIfVersion tests
    // -------------------------------------------------------------------------

    @Test
    void putIfVersionReturnsTrueOnSuccess() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn("5");

        RedisStore store = new RedisStore(jedis);
        assertTrue(store.putIfVersion(List.of("ns"), "k", Map.of(), 4L));
    }

    @Test
    void putIfVersionReturnsFalseOnVersionMismatch() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn("0");

        RedisStore store = new RedisStore(jedis);
        assertFalse(store.putIfVersion(List.of("ns"), "k", Map.of(), 999L));
    }

    @Test
    void putIfVersionPassesExpectedVersionAsArg() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn("1");

        RedisStore store = new RedisStore(jedis);
        store.putIfVersion(List.of("ns"), "k", Map.of("v", 1), 0L);

        verify(jedis)
                .eval(
                        eq(RedisStoreHelper.PUT_IF_VERSION_SCRIPT),
                        eq(List.of("agentscope:store:item:{ns}\0k", "agentscope:store:idx:{ns}")),
                        eq(List.of("{\"v\":1}", "k", "0")));
    }

    // -------------------------------------------------------------------------
    //  search tests
    // -------------------------------------------------------------------------

    @Test
    void searchReturnsEmptyForNonPositiveLimit() {
        RedisStore store = new RedisStore(jedis);
        assertTrue(store.search(List.of("ns"), 0, 0).isEmpty());
        assertTrue(store.search(List.of("ns"), -1, 0).isEmpty());
    }

    @Test
    void searchReturnsEmptyWhenNoMembers() {
        when(jedis.zrangeByLex(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        RedisStore store = new RedisStore(jedis);
        assertTrue(store.search(List.of("ns"), 10, 0).isEmpty());
    }

    @Test
    void searchSkipsStaleIndexEntries() {
        when(jedis.zrangeByLex(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of("k1", "k2"));
        when(jedis.hgetAll("agentscope:store:item:{ns}\0k1"))
                .thenReturn(Map.of("value", "{\"a\":1}", "version", "1"));
        when(jedis.hgetAll("agentscope:store:item:{ns}\0k2")).thenReturn(Collections.emptyMap());

        RedisStore store = new RedisStore(jedis);
        List<StoreItem> items = store.search(List.of("ns"), 10, 0);

        assertEquals(1, items.size());
        assertEquals("k1", items.get(0).key());
    }

    @Test
    void searchAppliesOffsetAndLimit() {
        when(jedis.zrangeByLex(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of("k1"));
        when(jedis.hgetAll("agentscope:store:item:{ns}\0k1"))
                .thenReturn(Map.of("value", "{}", "version", "1"));

        RedisStore store = new RedisStore(jedis);
        store.search(List.of("ns"), 5, 3);

        verify(jedis).zrangeByLex("agentscope:store:idx:{ns}", "-", "+", 3, 5);
    }

    @Test
    void searchHandlesNullReturnFromZrange() {
        when(jedis.zrangeByLex(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(null);

        RedisStore store = new RedisStore(jedis);
        assertTrue(store.search(List.of("ns"), 10, 0).isEmpty());
    }

    @Test
    void searchUsesHashTagKeyForIndex() {
        when(jedis.zrangeByLex(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(Collections.emptyList());

        RedisStore store = new RedisStore(jedis);
        store.search(List.of("a", "b"), 10, 0);

        verify(jedis).zrangeByLex("agentscope:store:idx:{a\0b}", "-", "+", 0, 10);
    }

    // -------------------------------------------------------------------------
    //  delete tests
    // -------------------------------------------------------------------------

    @Test
    void deleteEvalsScriptWithCorrectKeys() {
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn(1L);

        RedisStore store = new RedisStore(jedis);
        store.delete(List.of("ns"), "k");

        verify(jedis)
                .eval(
                        eq(RedisStoreHelper.DELETE_SCRIPT),
                        eq(List.of("agentscope:store:item:{ns}\0k", "agentscope:store:idx:{ns}")),
                        eq(List.of("k")));
    }

    // -------------------------------------------------------------------------
    //  Serialization edge cases
    // -------------------------------------------------------------------------

    @Test
    void deserializeHandlesNullJson() {
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("value", null);
        hash.put("version", "1");
        when(jedis.hgetAll(anyString())).thenReturn(hash);

        RedisStore store = new RedisStore(jedis);
        StoreItem item = store.get(List.of("ns"), "k");

        assertNotNull(item);
        assertTrue(item.value().isEmpty());
    }

    @Test
    void deserializeHandlesEmptyJson() {
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("value", "");
        hash.put("version", "1");
        when(jedis.hgetAll(anyString())).thenReturn(hash);

        RedisStore store = new RedisStore(jedis);
        StoreItem item = store.get(List.of("ns"), "k");

        assertNotNull(item);
        assertTrue(item.value().isEmpty());
    }

    @Test
    void deserializeInvalidJsonThrows() {
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("value", "not-json");
        hash.put("version", "1");
        when(jedis.hgetAll(anyString())).thenReturn(hash);

        RedisStore store = new RedisStore(jedis);
        assertThrows(IllegalStateException.class, () -> store.get(List.of("ns"), "k"));
    }

    @Test
    void serializeFailureThrows() {
        ObjectMapper failingMapper =
                new ObjectMapper() {
                    @Override
                    public String writeValueAsString(Object value)
                            throws com.fasterxml.jackson.core.JsonProcessingException {
                        throw new com.fasterxml.jackson.core.JsonProcessingException("fail") {};
                    }
                };
        RedisStore store = new RedisStore(jedis, "prefix", failingMapper);
        assertThrows(
                IllegalArgumentException.class,
                () -> store.put(List.of("ns"), "k", Map.of("x", "y")));
    }

    // -------------------------------------------------------------------------
    //  Custom ObjectMapper test
    // -------------------------------------------------------------------------

    @Test
    void customObjectMapperRoundTrip() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("value", "{\"custom\":true}");
        hash.put("version", "7");
        when(jedis.hgetAll("agentscope:store:item:{ns}\0k")).thenReturn(hash);
        when(jedis.eval(anyString(), anyList(), anyList())).thenReturn("1");

        RedisStore store = new RedisStore(jedis, "agentscope:store:", mapper);
        store.put(List.of("ns"), "k", Map.of("custom", true));
        StoreItem item = store.get(List.of("ns"), "k");

        assertEquals(Boolean.TRUE, item.value().get("custom"));
        assertEquals(7L, item.version());
    }

    /**
     * Helper to access private script constants for verification in tests. Uses reflection because
     * the scripts are private static fields in {@link RedisStore}.
     */
    static final class RedisStoreHelper {
        static final String PUT_SCRIPT;
        static final String PUT_IF_VERSION_SCRIPT;
        static final String DELETE_SCRIPT;

        static {
            try {
                var putField = RedisStore.class.getDeclaredField("PUT_SCRIPT");
                putField.setAccessible(true);
                PUT_SCRIPT = (String) putField.get(null);

                var casField = RedisStore.class.getDeclaredField("PUT_IF_VERSION_SCRIPT");
                casField.setAccessible(true);
                PUT_IF_VERSION_SCRIPT = (String) casField.get(null);

                var delField = RedisStore.class.getDeclaredField("DELETE_SCRIPT");
                delField.setAccessible(true);
                DELETE_SCRIPT = (String) delField.get(null);
            } catch (Exception e) {
                throw new RuntimeException("Failed to access RedisStore script fields", e);
            }
        }
    }
}
