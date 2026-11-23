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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("LettuceRemoteSnapshotClient")
class LettuceRemoteSnapshotClientTest {

    private static final byte[] DEFAULT_KEY =
            "agentscope:sandbox:snapshots:snap-1.tar".getBytes(StandardCharsets.UTF_8);

    @SuppressWarnings("unchecked")
    private final RedisCommands<byte[], byte[]> commands = mock(RedisCommands.class);

    @Test
    @DisplayName("upload stores the archive under the default key and skips TTL when absent")
    void uploadStoresArchiveWithoutTtl() throws Exception {
        byte[] payload = "tar-bytes".getBytes(StandardCharsets.UTF_8);

        client(null, null).upload("snap-1", new ByteArrayInputStream(payload));

        verify(commands).set(aryEq(DEFAULT_KEY), aryEq(payload));
        verify(commands, never()).expire(aryEq(DEFAULT_KEY), anyLong());
    }

    @Test
    @DisplayName("upload normalizes the prefix and applies a positive TTL")
    void uploadAppliesPositiveTtlWithNormalizedPrefix() throws Exception {
        byte[] payload = "tar-bytes".getBytes(StandardCharsets.UTF_8);
        byte[] key = key("myapp:snap-1.tar");

        client("myapp", 60).upload("snap-1", new ByteArrayInputStream(payload));

        verify(commands).set(aryEq(key), aryEq(payload));
        verify(commands).expire(aryEq(key), eq(60L));
    }

    @Test
    @DisplayName("upload ignores a non-positive TTL")
    void uploadIgnoresNonPositiveTtl() throws Exception {
        client("myapp:", 0).upload("snap-1", new ByteArrayInputStream(new byte[0]));
        client("myapp:", -5).upload("snap-2", new ByteArrayInputStream(new byte[0]));

        verify(commands, never()).expire(aryEq(key("myapp:snap-1.tar")), anyLong());
        verify(commands, never()).expire(aryEq(key("myapp:snap-2.tar")), anyLong());
    }

    @Test
    @DisplayName("upload rejects a blank snapshot id before touching Redis")
    void uploadRejectsBlankSnapshotId() {
        assertThrows(
                IllegalArgumentException.class,
                () -> client(null, null).upload(" ", new ByteArrayInputStream(new byte[0])));

        verifyNoInteractions(commands);
    }

    @Test
    @DisplayName("download returns the stored archive bytes")
    void downloadReturnsStoredBytes() throws Exception {
        byte[] payload = "tar-bytes".getBytes(StandardCharsets.UTF_8);
        when(commands.get(aryEq(DEFAULT_KEY))).thenReturn(payload);

        InputStream data = client(null, null).download("snap-1");

        assertArrayEquals(payload, data.readAllBytes());
    }

    @Test
    @DisplayName("download fails with FileNotFoundException when the key is missing")
    void downloadFailsWhenSnapshotMissing() {
        when(commands.get(aryEq(DEFAULT_KEY))).thenReturn(null);

        FileNotFoundException error =
                assertThrows(
                        FileNotFoundException.class, () -> client(null, null).download("snap-1"));

        assertTrue(error.getMessage().contains("agentscope:sandbox:snapshots:snap-1.tar"));
    }

    @Test
    @DisplayName("exists delegates to the Redis exists check")
    void existsDelegatesToKeyExists() throws Exception {
        when(commands.exists(aryEq(DEFAULT_KEY))).thenReturn(1L);
        assertTrue(client(null, null).exists("snap-1"));

        when(commands.exists(aryEq(DEFAULT_KEY))).thenReturn(0L);
        assertFalse(client(null, null).exists("snap-1"));
    }

    @Test
    @DisplayName("cluster sync commands are supported through the dedicated constructor")
    @SuppressWarnings("unchecked")
    void clusterCommandsAreSupported() throws Exception {
        RedisAdvancedClusterCommands<byte[], byte[]> clusterCommands =
                mock(RedisAdvancedClusterCommands.class);
        when(clusterCommands.exists(aryEq(DEFAULT_KEY))).thenReturn(1L);

        assertTrue(new LettuceRemoteSnapshotClient(clusterCommands, null, null).exists("snap-1"));
    }

    @Test
    @DisplayName("constructor rejects null commands")
    void constructorRejectsNullCommands() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new LettuceRemoteSnapshotClient(
                                (RedisCommands<byte[], byte[]>) null, null, null));
    }

    @Test
    @DisplayName("RedisSnapshotSpec wraps the Lettuce client for user wiring")
    void specWrapsLettuceClient() {
        RedisSnapshotSpec spec = new RedisSnapshotSpec(commands, "myapp", null);

        assertTrue(spec.getClient() instanceof LettuceRemoteSnapshotClient);
    }

    private LettuceRemoteSnapshotClient client(String keyPrefix, Integer ttlSeconds) {
        return new LettuceRemoteSnapshotClient(commands, keyPrefix, ttlSeconds);
    }

    private static byte[] key(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
