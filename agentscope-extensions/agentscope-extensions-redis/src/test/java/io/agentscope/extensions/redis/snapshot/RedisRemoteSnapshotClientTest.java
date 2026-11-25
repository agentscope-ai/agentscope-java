/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.extensions.redis.snapshot;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import redis.clients.jedis.UnifiedJedis;
import redis.clients.jedis.exceptions.JedisConnectionException;

class RedisRemoteSnapshotClientTest {

    private final UnifiedJedis jedis = mock(UnifiedJedis.class);
    private final byte[] key = "myapp:快照.tar".getBytes(StandardCharsets.UTF_8);
    private final byte[] payload = {0, (byte) 0xff, 1};

    @ParameterizedTest
    @ValueSource(ints = {1, 60, Integer.MAX_VALUE})
    void uploadStoresBytesAndPositiveTtlInOneCommand(int ttl) throws Exception {
        new RedisRemoteSnapshotClient(jedis, "myapp", ttl)
                .upload("快照", new ByteArrayInputStream(payload));

        verify(jedis).setex(aryEq(key), eq((long) ttl), aryEq(payload));
        verifyNoMoreInteractions(jedis);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, -1})
    void uploadWithoutPositiveTtlStillStoresPersistentBytes(Integer ttl) throws Exception {
        new RedisRemoteSnapshotClient(jedis, "myapp:", ttl)
                .upload("快照", new ByteArrayInputStream(payload));

        verify(jedis).set(aryEq(key), aryEq(payload));
        verifyNoMoreInteractions(jedis);
    }

    @Test
    void failedExpiringWriteDoesNotFallBackToPersistentData() {
        JedisConnectionException failure = new JedisConnectionException("write unavailable");
        when(jedis.setex(aryEq(key), eq(60L), aryEq(payload))).thenThrow(failure);

        assertSame(
                failure,
                assertThrows(
                        JedisConnectionException.class,
                        () ->
                                new RedisRemoteSnapshotClient(jedis, "myapp", 60)
                                        .upload("快照", new ByteArrayInputStream(payload))));
        verify(jedis).setex(aryEq(key), eq(60L), aryEq(payload));
        verifyNoMoreInteractions(jedis);
    }
}
