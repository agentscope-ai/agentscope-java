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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic unit tests for {@link RedisAgentStateStore#listSessionIds(String)}: the SCAN pattern
 * it builds and the sessionId extraction it performs, without a real Redis. The {@link
 * RedisClientAdapter} is mocked.
 */
class RedisAgentStateStoreListSessionIdsTest {

    @Test
    void parsesSessionIdsFromScannedKeys() {
        RedisClientAdapter adapter = mock(RedisClientAdapter.class);
        when(adapter.findKeysByPattern(anyString()))
                .thenReturn(
                        Set.of(
                                "agentscope:session:{user1/sessA}:_keys",
                                "agentscope:session:{user1/sessB}:_keys"));
        RedisAgentStateStore store = RedisAgentStateStore.builder().clientAdapter(adapter).build();

        Set<String> ids = store.listSessionIds("user1");

        assertEquals(Set.of("sessA", "sessB"), ids);
    }

    @Test
    void escapesGlobMetacharactersInScanPattern() {
        RedisClientAdapter adapter = mock(RedisClientAdapter.class);
        when(adapter.findKeysByPattern(anyString())).thenReturn(Set.of());
        RedisAgentStateStore store = RedisAgentStateStore.builder().clientAdapter(adapter).build();

        store.listSessionIds("us*er?");

        // '*' and '?' must be backslash-escaped so they match literally, not as globs.
        verify(adapter).findKeysByPattern("agentscope:session:{us\\*er\\?/*}:_keys");
    }

    @Test
    void usesAnonUserForBlankUserId() {
        RedisClientAdapter adapter = mock(RedisClientAdapter.class);
        when(adapter.findKeysByPattern(anyString())).thenReturn(Set.of());
        RedisAgentStateStore store = RedisAgentStateStore.builder().clientAdapter(adapter).build();

        store.listSessionIds("   ");

        verify(adapter).findKeysByPattern("agentscope:session:{__anon__/*}:_keys");
    }
}
