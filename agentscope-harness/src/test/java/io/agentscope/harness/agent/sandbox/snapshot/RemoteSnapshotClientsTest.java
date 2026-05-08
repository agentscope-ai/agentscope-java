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
package io.agentscope.harness.agent.sandbox.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.UnifiedJedis;

class RemoteSnapshotClientsTest {

    @Test
    void ossClient_uploadDownloadExists() throws Exception {
        OSS oss = mock(OSS.class);
        OSSObject object = new OSSObject();
        object.setObjectContent(
                new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8)));
        when(oss.doesObjectExist("bucket", "prefix/s1.tar")).thenReturn(true);
        when(oss.getObject("bucket", "prefix/s1.tar")).thenReturn(object);

        OssRemoteSnapshotClient client = new OssRemoteSnapshotClient(oss, "bucket", "prefix");
        client.upload("s1", new ByteArrayInputStream("payload".getBytes(StandardCharsets.UTF_8)));
        assertEquals(true, client.exists("s1"));
        String downloaded =
                new String(client.download("s1").readAllBytes(), StandardCharsets.UTF_8);
        assertEquals("payload", downloaded);

        verify(oss).putObject(eq("bucket"), eq("prefix/s1.tar"), any(InputStream.class));
    }

    @Test
    void redisClient_uploadDownloadWithTtl() throws Exception {
        UnifiedJedis jedis = mock(UnifiedJedis.class);
        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        when(jedis.get(any(byte[].class))).thenReturn(data);
        when(jedis.exists(any(byte[].class))).thenReturn(true);

        RedisRemoteSnapshotClient client = new RedisRemoteSnapshotClient(jedis, "snap", 60);
        client.upload("s1", new ByteArrayInputStream(data));

        verify(jedis).set(any(byte[].class), eq(data));
        verify(jedis).expire(any(byte[].class), eq(60L));

        assertEquals(
                "data", new String(client.download("s1").readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(true, client.exists("s1"));
    }

    @Test
    void redisClient_downloadMissing_throws() throws Exception {
        UnifiedJedis jedis = mock(UnifiedJedis.class);
        when(jedis.get(any(byte[].class))).thenReturn(null);

        RedisRemoteSnapshotClient client = new RedisRemoteSnapshotClient(jedis, null, null);
        assertThrows(FileNotFoundException.class, () -> client.download("missing"));
    }
}
