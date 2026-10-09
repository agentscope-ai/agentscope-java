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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
    void rejectsGlobMetacharactersInUserId() {
        RedisClientAdapter adapter = mock(RedisClientAdapter.class);
        RedisAgentStateStore store = RedisAgentStateStore.builder().clientAdapter(adapter).build();

        // Glob metacharacters are rejected (not escaped) so that listSessionIds stays consistent
        // with save/slotId, which also reject them via validateUserSegment. build() performs the
        // one-time legacy-layout detection scan; the rejection must not issue an ADDITIONAL scan.
        verify(adapter, times(1)).findKeysByPattern(anyString());
        assertThrows(IllegalArgumentException.class, () -> store.listSessionIds("us*er?"));
        verify(adapter, times(1)).findKeysByPattern(anyString());
    }

    @Test
    void parsesCompositeSessionIdWithSlash() {
        RedisClientAdapter adapter = mock(RedisClientAdapter.class);
        when(adapter.findKeysByPattern(anyString()))
                .thenReturn(Set.of("agentscope:session:{user1/agent1/run42}:_keys"));
        RedisAgentStateStore store = RedisAgentStateStore.builder().clientAdapter(adapter).build();

        // The sessionId segment may contain '/': extraction strips the fixed "{user1/" prefix
        // and the "}:_keys" tail, so the composite id is returned intact.
        assertEquals(Set.of("agent1/run42"), store.listSessionIds("user1"));
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
