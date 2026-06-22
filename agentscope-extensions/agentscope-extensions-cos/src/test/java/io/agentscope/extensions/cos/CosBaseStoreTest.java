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
package io.agentscope.extensions.cos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.COSObject;
import com.qcloud.cos.model.COSObjectInputStream;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CosBaseStoreTest {

    private COSClient mockCos;
    private CosBaseStore store;

    @BeforeEach
    void setUp() {
        mockCos = mock(COSClient.class);
        store =
                CosBaseStore.builder()
                        .cosClient(mockCos)
                        .bucketName("test-bucket")
                        .keyPrefix("test/store/")
                        .build();
    }

    @Test
    void builderRejectsNullClient() {
        assertThrows(
                NullPointerException.class, () -> CosBaseStore.builder().bucketName("b").build());
    }

    @Test
    void builderRejectsBlankBucket() {
        assertThrows(
                IllegalArgumentException.class,
                () -> CosBaseStore.builder().cosClient(mockCos).bucketName("").build());
    }

    @Test
    void putCallsPutObject() {
        // version key does not exist
        notFound("test/store/ns1/ns2/my-key.version");

        store.put(List.of("ns1", "ns2"), "my-key", Map.of("foo", "bar"));

        // put writes data + version
        verify(mockCos, times(2)).putObject(any(PutObjectRequest.class));
    }

    @Test
    void getReturnsNull_whenNotExists() {
        notFound("test/store/ns1/my-key.json");

        StoreItem result = store.get(List.of("ns1"), "my-key");
        assertNull(result);
    }

    @Test
    void getReturnsItem_whenExists() {
        String dataKey = "test/store/ns1/my-key.json";
        String versionKey = "test/store/ns1/my-key.version";

        when(mockCos.getObjectMetadata("test-bucket", dataKey)).thenReturn(new ObjectMetadata());
        when(mockCos.getObjectMetadata("test-bucket", versionKey)).thenReturn(new ObjectMetadata());
        when(mockCos.getObject(any(GetObjectRequest.class)))
                .thenAnswer(
                        inv -> {
                            GetObjectRequest req = inv.getArgument(0);
                            if (req.getKey().equals(dataKey))
                                return cosObjectWith("{\"name\":\"test\"}");
                            return cosObjectWith("3");
                        });

        StoreItem item = store.get(List.of("ns1"), "my-key");
        assertNotNull(item);
        assertEquals("my-key", item.key());
        assertEquals("test", item.value().get("name"));
        assertEquals(3L, item.version());
    }

    @Test
    void putIfVersion_returnsFalse_onMismatch() {
        String versionKey = "test/store/ns1/my-key.version";
        when(mockCos.getObjectMetadata("test-bucket", versionKey)).thenReturn(new ObjectMetadata());
        when(mockCos.getObject(any(GetObjectRequest.class))).thenReturn(cosObjectWith("5"));

        boolean result = store.putIfVersion(List.of("ns1"), "my-key", Map.of("a", "b"), 3);
        assertFalse(result);
        verify(mockCos, never()).putObject(any(PutObjectRequest.class));
    }

    @Test
    void putIfVersion_returnsTrue_onMatch() {
        String versionKey = "test/store/ns1/my-key.version";
        when(mockCos.getObjectMetadata("test-bucket", versionKey)).thenReturn(new ObjectMetadata());
        when(mockCos.getObject(any(GetObjectRequest.class))).thenReturn(cosObjectWith("5"));

        boolean result = store.putIfVersion(List.of("ns1"), "my-key", Map.of("a", "b"), 5);
        assertTrue(result);
        verify(mockCos, times(2)).putObject(any(PutObjectRequest.class));
    }

    @Test
    void deleteCallsDeleteObject_dataOnly_whenVersionAbsent() {
        notFound("test/store/ns1/my-key.version");

        store.delete(List.of("ns1"), "my-key");
        verify(mockCos).deleteObject("test-bucket", "test/store/ns1/my-key.json");
        verify(mockCos, never()).deleteObject("test-bucket", "test/store/ns1/my-key.version");
    }

    @Test
    void deleteCallsDeleteObjectForVersion_whenExists() {
        when(mockCos.getObjectMetadata("test-bucket", "test/store/ns1/my-key.version"))
                .thenReturn(new ObjectMetadata());

        store.delete(List.of("ns1"), "my-key");
        verify(mockCos).deleteObject("test-bucket", "test/store/ns1/my-key.json");
        verify(mockCos).deleteObject("test-bucket", "test/store/ns1/my-key.version");
    }

    @Test
    void putStripsLeadingSlashFromKey() {
        notFound("test/store/ns1/ns2/my-key.version");

        store.put(List.of("ns1", "ns2"), "/my-key", Map.of("foo", "bar"));
        verify(mockCos, times(2)).putObject(any(PutObjectRequest.class));
    }

    @Test
    void getStripsLeadingSlashFromKey() {
        String dataKey = "test/store/ns1/my-key.json";
        when(mockCos.getObjectMetadata("test-bucket", dataKey)).thenReturn(new ObjectMetadata());
        notFound("test/store/ns1/my-key.version");
        when(mockCos.getObject(any(GetObjectRequest.class))).thenReturn(cosObjectWith("{\"v\":1}"));

        StoreItem item = store.get(List.of("ns1"), "/my-key");
        assertNotNull(item);
        assertEquals("/my-key", item.key());
    }

    // ---- helpers ----

    private void notFound(String key) {
        CosServiceException ex = new CosServiceException("not found");
        ex.setStatusCode(404);
        when(mockCos.getObjectMetadata("test-bucket", key)).thenThrow(ex);
    }

    private static COSObject cosObjectWith(String content) {
        COSObject obj = new COSObject();
        InputStream is = new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
        org.apache.http.client.methods.HttpGet req =
                mock(org.apache.http.client.methods.HttpGet.class);
        obj.setObjectContent(new COSObjectInputStream(is, req));
        return obj;
    }
}
