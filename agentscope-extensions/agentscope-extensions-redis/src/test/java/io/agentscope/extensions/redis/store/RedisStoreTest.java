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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.AbstractPipeline;
import redis.clients.jedis.Response;
import redis.clients.jedis.UnifiedJedis;

class RedisStoreTest {
    @Test
    @SuppressWarnings("unchecked")
    void getAllReadsAllKeysThroughOnePipeline() {
        var jedis = mock(UnifiedJedis.class);
        var pipeline = mock(AbstractPipeline.class);
        when(jedis.pipelined()).thenReturn(pipeline);

        var hashes = new LinkedHashMap<String, Map<String, String>>();
        hashes.put("a", Map.of("value", "{\"bytes\":\"eA==\"}", "version", "1"));
        hashes.put("b", Map.of("value", "{\"bytes\":\"eQ==\"}", "version", "2"));
        hashes.put("missing", Map.of());

        when(pipeline.hgetAll(anyString()))
                .thenAnswer(
                        invocation -> {
                            String key = invocation.getArgument(0);
                            String item = key.substring(key.lastIndexOf('\0') + 1);
                            return response(hashes.getOrDefault(item, Map.of()));
                        });

        var store = new RedisStore(jedis, "test:");
        Map<String, StoreItem> items =
                store.getAll(List.of("ns"), List.of("a", "missing", "b", "a"));

        assertEquals(2, items.size());
        assertEquals("eA==", items.get("a").value().get("bytes"));
        assertEquals(1L, items.get("a").version());
        assertEquals(2L, items.get("b").version());
        assertFalse(items.containsKey("missing"));
        verify(pipeline, times(3)).hgetAll(anyString());
        verify(jedis, never()).hgetAll(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchPipelinesThePageAndSkipsStaleEntries() {
        var jedis = mock(UnifiedJedis.class);
        var pipeline = mock(AbstractPipeline.class);
        when(jedis.pipelined()).thenReturn(pipeline);
        when(jedis.zrangeByLex(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(List.of("a", "stale", "b"));

        var hashes = new LinkedHashMap<String, Map<String, String>>();
        hashes.put("a", Map.of("value", "{\"bytes\":\"eA==\"}", "version", "1"));
        hashes.put("b", Map.of("value", "{\"bytes\":\"eQ==\"}", "version", "2"));

        when(pipeline.hgetAll(anyString()))
                .thenAnswer(
                        invocation -> {
                            String key = invocation.getArgument(0);
                            String item = key.substring(key.lastIndexOf('\0') + 1);
                            return response(hashes.getOrDefault(item, Map.of()));
                        });

        var store = new RedisStore(jedis, "test:");
        List<StoreItem> items = store.search(List.of("ns"), 10, 0);

        assertEquals(2, items.size());
        assertEquals("a", items.get(0).key());
        assertEquals("b", items.get(1).key());
        verify(jedis).pipelined();
        verify(jedis, never()).hgetAll(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchChunksLargePages() {
        var jedis = mock(UnifiedJedis.class);
        var pipeline = mock(AbstractPipeline.class);
        when(jedis.pipelined()).thenReturn(pipeline);
        when(jedis.zrangeByLex(anyString(), anyString(), anyString(), anyInt(), anyInt()))
                .thenReturn(keys(300));
        var empty = response(Map.of());
        when(pipeline.hgetAll(anyString())).thenReturn(empty);

        var store = new RedisStore(jedis, "test:");
        assertTrue(store.search(List.of("ns"), 300, 0).isEmpty());

        verify(jedis, times(2)).pipelined();
        verify(pipeline, times(300)).hgetAll(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void getAllChunksLargeKeySets() {
        var jedis = mock(UnifiedJedis.class);
        var pipeline = mock(AbstractPipeline.class);
        when(jedis.pipelined()).thenReturn(pipeline);

        var empty = response(Map.of());
        when(pipeline.hgetAll(anyString())).thenReturn(empty);

        var store = new RedisStore(jedis, "test:");
        assertTrue(store.getAll(List.of("ns"), keys(300)).isEmpty());

        verify(jedis, times(2)).pipelined();
        verify(pipeline, times(300)).hgetAll(anyString());
    }

    private static List<String> keys(int count) {
        var keys = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) keys.add("key" + i);
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static Response<Map<String, String>> response(Map<String, String> hash) {
        Response<Map<String, String>> response = mock(Response.class);
        when(response.get()).thenReturn(hash);
        return response;
    }
}
