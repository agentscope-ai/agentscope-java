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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

class OssBaseStoreTest {

    private S3Client mockS3;
    private OssBaseStore store;

    @BeforeEach
    void setUp() {
        mockS3 = mock(S3Client.class);
        when(mockS3.getObjectAsBytes(anyGetObjectRequest()))
                .thenThrow(S3Exception.builder().statusCode(404).message("missing").build());
        store =
                OssBaseStore.builder()
                        .s3Client(mockS3)
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
                () -> OssBaseStore.builder().s3Client(mockS3).bucketName("").build());
    }

    @Test
    void putCallsPutObject() {
        store.put(List.of("ns1", "ns2"), "my-key", Map.of("foo", "bar"));
        verify(mockS3)
                .putObject(
                        putObjectRequest("test/store/ns1/ns2/my-key.json"), any(RequestBody.class));
        verify(mockS3)
                .putObject(
                        putObjectRequest("test/store/ns1/ns2/my-key.version"),
                        any(RequestBody.class));
    }

    @Test
    void getReturnsNull_whenNotExists() {
        when(mockS3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(
                        software.amazon.awssdk.services.s3.model.S3Exception.builder()
                                .statusCode(404)
                                .message("missing")
                                .build());

        StoreItem result = store.get(List.of("ns1"), "my-key");
        assertNull(result);
    }

    @Test
    void getReturnsItem_whenExists() {
        ResponseBytes<GetObjectResponse> bytes =
                ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().build(),
                        "{\"name\":\"test\"}".getBytes(StandardCharsets.UTF_8));
        when(mockS3.getObjectAsBytes(getObjectRequest("test/store/ns1/my-key.json")))
                .thenReturn(bytes);

        StoreItem item = store.get(List.of("ns1"), "my-key");
        assertNotNull(item);
        assertEquals("my-key", item.key());
        assertEquals("test", item.value().get("name"));
    }

    @Test
    void putIfVersion_returnsFalse_onMismatch() {
        boolean result = store.putIfVersion(List.of("ns1"), "my-key", Map.of("a", "b"), 3);
        assertFalse(result);
    }

    @Test
    void putIfVersion_returnsTrue_onMatch() {
        boolean result = store.putIfVersion(List.of("ns1"), "my-key", Map.of("a", "b"), 0);
        assertTrue(result);
    }

    @Test
    void deleteCallsDeleteObject() {
        store.delete(List.of("ns1"), "my-key");
        verify(mockS3).deleteObject(deleteObjectRequest("test/store/ns1/my-key.json"));
        verify(mockS3).deleteObject(deleteObjectRequest("test/store/ns1/my-key.version"));
    }

    @Test
    void putStripsLeadingSlashFromKey() {
        store.put(List.of("ns1", "ns2"), "/my-key", Map.of("foo", "bar"));
        verify(mockS3)
                .putObject(
                        putObjectRequest("test/store/ns1/ns2/my-key.json"), any(RequestBody.class));
    }

    @Test
    void getStripsLeadingSlashFromKey() {
        ResponseBytes<GetObjectResponse> bytes =
                ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().build(),
                        "{\"v\":1}".getBytes(StandardCharsets.UTF_8));
        when(mockS3.getObjectAsBytes(getObjectRequest("test/store/ns1/my-key.json")))
                .thenReturn(bytes);

        StoreItem item = store.get(List.of("ns1"), "/my-key");
        assertNotNull(item);
        assertEquals("/my-key", item.key());
    }

    private static GetObjectRequest anyGetObjectRequest() {
        return any(GetObjectRequest.class);
    }

    private static GetObjectRequest getObjectRequest(String key) {
        return argThat(req -> req != null && key.equals(req.key()));
    }

    private static PutObjectRequest putObjectRequest(String key) {
        return argThat(req -> req != null && key.equals(req.key()));
    }

    private static DeleteObjectRequest deleteObjectRequest(String key) {
        return argThat(req -> req != null && key.equals(req.key()));
    }
}
