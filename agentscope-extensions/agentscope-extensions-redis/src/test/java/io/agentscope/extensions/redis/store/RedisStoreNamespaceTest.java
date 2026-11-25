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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import redis.clients.jedis.UnifiedJedis;

class RedisStoreNamespaceTest {
    @ParameterizedTest
    @MethodSource("invalidNamespaces")
    void rejectsDelimiterBeforeAnyRedisCommand(List<String> namespace) {
        UnifiedJedis client = mock(UnifiedJedis.class);
        RedisStore store = new RedisStore(client);

        assertTrue(
                assertThrows(IllegalArgumentException.class, () -> store.get(namespace, "key"))
                        .getMessage()
                        .contains("NUL"));
        assertThrows(IllegalArgumentException.class, () -> store.put(namespace, "key", Map.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.putIfVersion(namespace, "key", Map.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> store.search(namespace, 10, 0));
        assertThrows(IllegalArgumentException.class, () -> store.delete(namespace, "key"));
        verifyNoInteractions(client);
    }

    static Stream<List<String>> invalidNamespaces() {
        return Stream.of(
                List.of("tenant\0project"),
                List.of("\0tenant", "project"),
                List.of("tenant", "project\0"),
                List.of("tenant", "\0", "project"),
                List.of("tenant", "pro\0ject"));
    }

    @Test
    void validSegmentsKeepExistingKeysAndUnicode() {
        UnifiedJedis client = mock(UnifiedJedis.class);
        RedisStore store = new RedisStore(client, "namespace-test:");
        List<String> namespace = List.of("租户/一", "project:β");

        assertNull(store.get(namespace, "key"));
        assertTrue(store.search(namespace, 3, 2).isEmpty());

        verify(client).hgetAll("namespace-test:item:租户/一\0project:β\0key");
        verify(client).zrangeByLex("namespace-test:idx:租户/一\0project:β", "-", "+", 2, 3);
        verifyNoMoreInteractions(client);
    }
}
