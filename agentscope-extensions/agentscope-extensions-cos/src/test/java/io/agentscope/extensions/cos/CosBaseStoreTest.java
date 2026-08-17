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
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.GetObjectRequest;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ObjectListing;
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

    @Test
    void search_returnsEmpty_whenNoMatches() {
        String prefix = "test/store/ns1/";
        ObjectListing listing = mock(ObjectListing.class);
        when(listing.getObjectSummaries()).thenReturn(List.of());
        when(listing.isTruncated()).thenReturn(false);
        when(mockCos.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);

        List<StoreItem> items = store.search(List.of("ns1"), 10, 0);
        assertTrue(items.isEmpty());
    }

    @Test
    void search_returnsSinglePage() {
        String prefix = "test/store/ns1/";
        COSObjectSummary s1 = new COSObjectSummary();
        s1.setKey("test/store/ns1/k1.json");
        COSObjectSummary s2 = new COSObjectSummary();
        s2.setKey("test/store/ns1/k2.json");

        ObjectListing listing = mock(ObjectListing.class);
        when(listing.getObjectSummaries()).thenReturn(List.of(s1, s2));
        when(listing.isTruncated()).thenReturn(false);
        when(mockCos.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);

        // All data keys exist
        when(mockCos.getObjectMetadata(any(), any(String.class))).thenReturn(new ObjectMetadata());
        // Version for k1 exists, version for k2 does not
        notFound("test/store/ns1/k2.version");

        when(mockCos.getObject(any(GetObjectRequest.class)))
                .thenAnswer(
                        inv -> {
                            GetObjectRequest req = inv.getArgument(0);
                            String k = req.getKey();
                            if (k.endsWith("k1.json")) return cosObjectWith("{\"val\":1}");
                            if (k.endsWith("k1.version")) return cosObjectWith("5");
                            // k2.json - valid JSON object
                            return cosObjectWith("{\"val\":2}");
                        });

        List<StoreItem> items = store.search(List.of("ns1"), 10, 0);
        assertEquals(2, items.size());
        assertEquals("k1", items.get(0).key());
        assertEquals("k2", items.get(1).key());
    }

    @Test
    void search_respectsOffsetAndLimit() {
        String prefix = "test/store/ns1/";
        COSObjectSummary s1 = new COSObjectSummary();
        s1.setKey("test/store/ns1/a.json");
        COSObjectSummary s2 = new COSObjectSummary();
        s2.setKey("test/store/ns1/b.json");
        COSObjectSummary s3 = new COSObjectSummary();
        s3.setKey("test/store/ns1/c.json");

        ObjectListing listing = mock(ObjectListing.class);
        when(listing.getObjectSummaries()).thenReturn(List.of(s1, s2, s3));
        when(listing.isTruncated()).thenReturn(false);
        when(mockCos.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);

        when(mockCos.getObjectMetadata(any(), any())).thenReturn(new ObjectMetadata());
        when(mockCos.getObject(any(GetObjectRequest.class)))
                .thenAnswer(
                        inv -> {
                            GetObjectRequest req = inv.getArgument(0);
                            if (req.getKey().endsWith(".json")) return cosObjectWith("{}");
                            return cosObjectWith("1");
                        });

        List<StoreItem> items = store.search(List.of("ns1"), 1, 1);
        assertEquals(1, items.size());
        assertEquals("b", items.get(0).key());
    }

    @Test
    void putIfVersion_firstPut_whenVersionNotExists() {
        notFound("test/store/ns1/my-key.version");

        boolean result = store.putIfVersion(List.of("ns1"), "my-key", Map.of("a", 1), 0);
        assertTrue(result);
        verify(mockCos, times(2)).putObject(any(PutObjectRequest.class));
    }

    @Test
    void emptyNamespace_usesPrefixOnly() {
        notFound("test/store/my-key.version");

        store.put(List.of(), "/my-key", Map.of("foo", "bar"));
        verify(mockCos, times(2)).putObject(any(PutObjectRequest.class));
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
