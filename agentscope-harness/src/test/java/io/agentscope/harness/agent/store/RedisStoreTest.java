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
package io.agentscope.harness.agent.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.harness.agent.filesystem.remote.store.RedisStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import redis.clients.jedis.UnifiedJedis;

/**
 * Unit tests for {@link RedisStore} that verify Lua scripts are dispatched with the right keys
 * and arguments, and that Redis responses are decoded correctly. Tests use mocked
 * {@link UnifiedJedis} so a Redis server is not required.
 */
@SuppressWarnings("unchecked")
class RedisStoreTest {

    private static final List<String> NS = List.of("test", "items");
    private static final String NS_PATH = "test\u0000items";
    private static final String IDX_KEY = "rs:idx:" + NS_PATH;

    private UnifiedJedis jedis;
    private RedisStore store;

    @BeforeEach
    void setUp() {
        jedis = mock(UnifiedJedis.class);
        store = new RedisStore(jedis, "rs:");
    }

    @Test
    void put_invokesPutScriptWithJsonValueAndKey() {
        when(jedis.eval(anyString(), any(List.class), any(List.class))).thenReturn("1");

        store.put(NS, "k1", Map.of("v", 1));

        ArgumentCaptor<List<String>> keysCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> argsCap = ArgumentCaptor.forClass(List.class);
        verify(jedis).eval(anyString(), keysCap.capture(), argsCap.capture());

        List<String> keys = keysCap.getValue();
        assertEquals(2, keys.size());
        assertEquals("rs:item:" + NS_PATH + "\u0000k1", keys.get(0));
        assertEquals(IDX_KEY, keys.get(1));

        List<String> args = argsCap.getValue();
        assertEquals(2, args.size());
        assertEquals("{\"v\":1}", args.get(0));
        assertEquals("k1", args.get(1));
    }

    @Test
    void putIfVersion_passesExpectedVersionAsThirdArg() {
        when(jedis.eval(anyString(), any(List.class), any(List.class))).thenReturn("2");

        boolean ok = store.putIfVersion(NS, "k1", Map.of("v", 2), 1L);
        assertTrue(ok);

        ArgumentCaptor<List<String>> argsCap = ArgumentCaptor.forClass(List.class);
        verify(jedis).eval(anyString(), any(List.class), argsCap.capture());
        assertEquals("1", argsCap.getValue().get(2));
    }

    @Test
    void putIfVersion_returnsFalseOnZeroResponse() {
        when(jedis.eval(anyString(), any(List.class), any(List.class))).thenReturn("0");
        assertFalse(store.putIfVersion(NS, "k1", Map.of("v", 2), 99L));
    }

    @Test
    void putIfVersion_handlesByteArrayResponse() {
        // Some Jedis paths return raw byte arrays \u2014 make sure we still decode "0".
        when(jedis.eval(anyString(), any(List.class), any(List.class)))
                .thenReturn("0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(store.putIfVersion(NS, "k1", Map.of("v", 2), 99L));
    }

    @Test
    void putIfVersion_rejectsNegativeExpectedVersion() {
        assertThrows(
                IllegalArgumentException.class,
                () -> store.putIfVersion(NS, "k", Map.of("v", 1), -1L));
        verify(jedis, never()).eval(anyString(), any(List.class), any(List.class));
    }

    @Test
    void get_decodesHashIntoStoreItem() {
        when(jedis.hgetAll("rs:item:" + NS_PATH + "\u0000k1"))
                .thenReturn(Map.of("value", "{\"v\":42}", "version", "3"));

        StoreItem item = store.get(NS, "k1");
        assertNotNull(item);
        assertEquals("k1", item.key());
        assertEquals(42, item.value().get("v"));
        assertEquals(3L, item.version());
    }

    @Test
    void get_returnsNullOnMissingHash() {
        when(jedis.hgetAll(anyString())).thenReturn(Map.of());
        assertNull(store.get(NS, "missing"));
    }

    @Test
    void search_pagesZrangeByLexThenFetchesEachHash() {
        when(jedis.zrangeByLex(eq(IDX_KEY), eq("-"), eq("+"), anyInt(), anyInt()))
                .thenReturn(List.of("a", "b"));
        when(jedis.hgetAll("rs:item:" + NS_PATH + "\u0000a"))
                .thenReturn(Map.of("value", "{\"v\":1}", "version", "1"));
        when(jedis.hgetAll("rs:item:" + NS_PATH + "\u0000b"))
                .thenReturn(Map.of("value", "{\"v\":2}", "version", "1"));

        List<StoreItem> items = store.search(NS, 2, 0);
        assertEquals(2, items.size());
        assertEquals("a", items.get(0).key());
        assertEquals(1, items.get(0).value().get("v"));
        assertEquals("b", items.get(1).key());
        assertEquals(2, items.get(1).value().get("v"));
    }

    @Test
    void search_skipsStaleIndexMembers() {
        when(jedis.zrangeByLex(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of("a", "stale"));
        when(jedis.hgetAll("rs:item:" + NS_PATH + "\u0000a"))
                .thenReturn(Map.of("value", "{\"v\":1}", "version", "1"));
        when(jedis.hgetAll("rs:item:" + NS_PATH + "\u0000stale")).thenReturn(Map.of());

        List<StoreItem> items = store.search(NS, 10, 0);
        assertEquals(1, items.size());
        assertEquals("a", items.get(0).key());
    }

    @Test
    void search_zeroLimit_returnsEmptyWithoutRedisCall() {
        assertTrue(store.search(NS, 0, 0).isEmpty());
        verify(jedis, never())
                .zrangeByLex(anyString(), anyString(), anyString(), anyInt(), anyInt());
    }

    @Test
    void delete_invokesDeleteScript() {
        when(jedis.eval(anyString(), any(List.class), any(List.class))).thenReturn(1L);
        store.delete(NS, "k1");

        ArgumentCaptor<List<String>> keysCap = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<String>> argsCap = ArgumentCaptor.forClass(List.class);
        verify(jedis, times(1)).eval(anyString(), keysCap.capture(), argsCap.capture());
        assertEquals(IDX_KEY, keysCap.getValue().get(1));
        assertEquals("k1", argsCap.getValue().get(0));
    }

    @Test
    void emptyKey_rejected() {
        assertThrows(IllegalArgumentException.class, () -> store.get(NS, ""));
        assertThrows(IllegalArgumentException.class, () -> store.put(NS, "", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> store.delete(NS, ""));
    }

    @Test
    void keyContainingNul_rejected() {
        assertThrows(IllegalArgumentException.class, () -> store.put(NS, "k\u0000bad", Map.of()));
    }

    @Test
    void defaultKeyPrefix_isApplied() {
        UnifiedJedis j = mock(UnifiedJedis.class);
        new RedisStore(j).put(NS, "k", Map.of());
        ArgumentCaptor<List<String>> keysCap = ArgumentCaptor.forClass(List.class);
        verify(j).eval(anyString(), keysCap.capture(), any(List.class));
        assertTrue(keysCap.getValue().get(0).startsWith(RedisStore.DEFAULT_KEY_PREFIX + "item:"));
    }
}
