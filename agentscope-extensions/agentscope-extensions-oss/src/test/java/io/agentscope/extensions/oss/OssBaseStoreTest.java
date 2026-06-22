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
package io.agentscope.extensions.oss;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.OSSObject;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OssBaseStoreTest {

    private OSS mockOss;
    private OssBaseStore store;

    @BeforeEach
    void setUp() {
        mockOss = mock(OSS.class);
        store =
                OssBaseStore.builder()
                        .ossClient(mockOss)
                        .bucketName("test-bucket")
                        .keyPrefix("test/store/")
                        .build();
    }

    @Test
    void builderRejectsNullClient() {
        assertThrows(
                NullPointerException.class, () -> OssBaseStore.builder().bucketName("b").build());
    }

    @Test
    void builderRejectsBlankBucket() {
        assertThrows(
                IllegalArgumentException.class,
                () -> OssBaseStore.builder().ossClient(mockOss).bucketName("").build());
    }

    @Test
    void putCallsPutObject() {
        store.put(List.of("ns1", "ns2"), "my-key", Map.of("foo", "bar"));
        verify(mockOss)
                .putObject(
                        eq("test-bucket"),
                        eq("test/store/ns1/ns2/my-key.json"),
                        any(InputStream.class));
    }

    @Test
    void getReturnsNull_whenNotExists() {
        when(mockOss.doesObjectExist("test-bucket", "test/store/ns1/my-key.json"))
                .thenReturn(false);

        StoreItem result = store.get(List.of("ns1"), "my-key");
        assertNull(result);
    }

    @Test
    void getReturnsItem_whenExists() {
        String dataKey = "test/store/ns1/my-key.json";
        String versionKey = "test/store/ns1/my-key.version";

        when(mockOss.doesObjectExist("test-bucket", dataKey)).thenReturn(true);
        OSSObject dataObj = new OSSObject();
        dataObj.setObjectContent(
                new ByteArrayInputStream("{\"name\":\"test\"}".getBytes(StandardCharsets.UTF_8)));
        when(mockOss.getObject("test-bucket", dataKey)).thenReturn(dataObj);

        when(mockOss.doesObjectExist("test-bucket", versionKey)).thenReturn(true);
        OSSObject versionObj = new OSSObject();
        versionObj.setObjectContent(new ByteArrayInputStream("3".getBytes(StandardCharsets.UTF_8)));
        when(mockOss.getObject("test-bucket", versionKey)).thenReturn(versionObj);

        StoreItem item = store.get(List.of("ns1"), "my-key");
        assertNotNull(item);
        assertEquals("my-key", item.key());
        assertEquals("test", item.value().get("name"));
        assertEquals(3L, item.version());
    }

    @Test
    void putIfVersion_returnsFalse_onMismatch() {
        String versionKey = "test/store/ns1/my-key.version";
        when(mockOss.doesObjectExist("test-bucket", versionKey)).thenReturn(true);
        OSSObject versionObj = new OSSObject();
        versionObj.setObjectContent(new ByteArrayInputStream("5".getBytes(StandardCharsets.UTF_8)));
        when(mockOss.getObject("test-bucket", versionKey)).thenReturn(versionObj);

        boolean result = store.putIfVersion(List.of("ns1"), "my-key", Map.of("a", "b"), 3);
        assertFalse(result);
    }

    @Test
    void putIfVersion_returnsTrue_onMatch() {
        String versionKey = "test/store/ns1/my-key.version";
        when(mockOss.doesObjectExist("test-bucket", versionKey)).thenReturn(true);
        OSSObject versionObj = new OSSObject();
        versionObj.setObjectContent(new ByteArrayInputStream("5".getBytes(StandardCharsets.UTF_8)));
        when(mockOss.getObject("test-bucket", versionKey)).thenReturn(versionObj);

        boolean result = store.putIfVersion(List.of("ns1"), "my-key", Map.of("a", "b"), 5);
        assertTrue(result);
        verify(mockOss)
                .putObject(
                        eq("test-bucket"),
                        eq("test/store/ns1/my-key.json"),
                        any(InputStream.class));
    }

    @Test
    void deleteCallsDeleteObject() {
        when(mockOss.doesObjectExist("test-bucket", "test/store/ns1/my-key.version"))
                .thenReturn(true);

        store.delete(List.of("ns1"), "my-key");
        verify(mockOss).deleteObject("test-bucket", "test/store/ns1/my-key.json");
        verify(mockOss).deleteObject("test-bucket", "test/store/ns1/my-key.version");
    }

    @Test
    void putStripsLeadingSlashFromKey() {
        store.put(List.of("ns1", "ns2"), "/my-key", Map.of("foo", "bar"));
        verify(mockOss)
                .putObject(
                        eq("test-bucket"),
                        eq("test/store/ns1/ns2/my-key.json"),
                        any(InputStream.class));
    }

    @Test
    void getStripsLeadingSlashFromKey() {
        String dataKey = "test/store/ns1/my-key.json";
        String versionKey = "test/store/ns1/my-key.version";

        when(mockOss.doesObjectExist("test-bucket", dataKey)).thenReturn(true);
        OSSObject dataObj = new OSSObject();
        dataObj.setObjectContent(
                new ByteArrayInputStream("{\"v\":1}".getBytes(StandardCharsets.UTF_8)));
        when(mockOss.getObject("test-bucket", dataKey)).thenReturn(dataObj);

        when(mockOss.doesObjectExist("test-bucket", versionKey)).thenReturn(false);

        StoreItem item = store.get(List.of("ns1"), "/my-key");
        assertNotNull(item);
        assertEquals("/my-key", item.key());
    }
}
