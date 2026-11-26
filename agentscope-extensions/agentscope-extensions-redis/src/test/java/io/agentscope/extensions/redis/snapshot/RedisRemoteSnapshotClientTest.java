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
package io.agentscope.extensions.redis.snapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.UnifiedJedis;

@DisplayName("RedisRemoteSnapshotClient")
class RedisRemoteSnapshotClientTest {

    private static final byte[] DEFAULT_KEY =
            "agentscope:sandbox:snapshots:snap-1.tar".getBytes(StandardCharsets.UTF_8);

    private final UnifiedJedis jedis = mock(UnifiedJedis.class);

    @Test
    @DisplayName("upload stores the archive and its TTL in a single command")
    void uploadWritesBytesAndTtlInSingleCommand() throws Exception {
        byte[] payload = "tar-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] key = key("myapp:snap-1.tar");

        client("myapp", 60).upload("snap-1", new ByteArrayInputStream(payload));

        verify(jedis).setex(aryEq(key), eq(60L), aryEq(payload));
        verify(jedis, never()).set(any(byte[].class), any(byte[].class));
        verify(jedis, never()).expire(any(byte[].class), anyLong());
    }

    @Test
    @DisplayName("upload skips the TTL when absent")
    void uploadStoresArchiveWithoutTtl() throws Exception {
        byte[] payload = "tar-bytes".getBytes(StandardCharsets.UTF_8);

        client(null, null).upload("snap-1", new ByteArrayInputStream(payload));

        verify(jedis).set(aryEq(DEFAULT_KEY), aryEq(payload));
        verify(jedis, never()).setex(any(byte[].class), anyLong(), any(byte[].class));
        verify(jedis, never()).expire(any(byte[].class), anyLong());
    }

    @Test
    @DisplayName("upload ignores a non-positive TTL")
    void uploadIgnoresNonPositiveTtl() throws Exception {
        client("myapp:", 0).upload("snap-1", new ByteArrayInputStream(new byte[0]));
        client("myapp:", -5).upload("snap-2", new ByteArrayInputStream(new byte[0]));

        verify(jedis, never()).setex(any(byte[].class), anyLong(), any(byte[].class));
        verify(jedis, never()).expire(any(byte[].class), anyLong());
    }

    @Test
    @DisplayName("upload rejects a blank snapshot id before touching Redis")
    void uploadRejectsBlankSnapshotId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> client(null, null).upload(" ", new ByteArrayInputStream(new byte[0])));

        verifyNoInteractions(jedis);
    }

    @Test
    @DisplayName("download returns the stored archive bytes")
    void downloadReturnsStoredBytes() throws Exception {
        byte[] payload = "tar-bytes".getBytes(StandardCharsets.UTF_8);
        when(jedis.get(aryEq(DEFAULT_KEY))).thenReturn(payload);

        InputStream data = client(null, null).download("snap-1");

        assertArrayEquals(payload, data.readAllBytes());
    }

    @Test
    @DisplayName("download fails with FileNotFoundException when the key is missing")
    void downloadFailsWhenSnapshotMissing() {
        when(jedis.get(aryEq(DEFAULT_KEY))).thenReturn(null);

        FileNotFoundException error =
                assertThrows(
                        FileNotFoundException.class, () -> client(null, null).download("snap-1"));

        assertTrue(error.getMessage().contains("agentscope:sandbox:snapshots:snap-1.tar"));
    }

    @Test
    @DisplayName("exists delegates to the Redis exists check")
    void existsDelegatesToKeyExists() throws Exception {
        when(jedis.exists(aryEq(DEFAULT_KEY))).thenReturn(true);
        assertTrue(client(null, null).exists("snap-1"));

        when(jedis.exists(aryEq(DEFAULT_KEY))).thenReturn(false);
        assertFalse(client(null, null).exists("snap-1"));
    }

    @Test
    @DisplayName("constructor rejects a null client")
    void constructorRejectsNullClient() {
        assertThrows(
                NullPointerException.class, () -> new RedisRemoteSnapshotClient(null, null, null));
    }

    private RedisRemoteSnapshotClient client(String keyPrefix, Integer ttlSeconds) {
        return new RedisRemoteSnapshotClient(jedis, keyPrefix, ttlSeconds);
    }

    private static byte[] key(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
