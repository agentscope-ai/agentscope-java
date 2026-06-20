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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;

class S3ObjectStoreSupportTest {

    @Test
    void normalizePrefix_handlesEmptyAndSlashes() {
        assertEquals(
                "agentscope/state/",
                S3ObjectStoreSupport.normalizePrefix(null, "agentscope/state/"));
        assertEquals(
                "agentscope/state/",
                S3ObjectStoreSupport.normalizePrefix("   ", "agentscope/state/"));
        assertEquals(
                "foo/bar/",
                S3ObjectStoreSupport.normalizePrefix("\\foo\\bar", "agentscope/state/"));
        assertEquals(
                "foo/bar/",
                S3ObjectStoreSupport.normalizePrefix("///foo/bar", "agentscope/state/"));
        assertEquals(
                "foo/bar/", S3ObjectStoreSupport.normalizePrefix("foo/bar", "agentscope/state/"));
    }

    @Test
    void getString_returnsNull_forMissingObject() {
        S3Client mockS3 = mock(S3Client.class);
        when(mockS3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("missing").build());

        assertNull(S3ObjectStoreSupport.getString(mockS3, "bucket", "missing.txt"));
    }

    @Test
    void getString_returnsNull_forNotFoundS3Exception() {
        S3Client mockS3 = mock(S3Client.class);
        when(mockS3.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(
                        S3Exception.builder()
                                .statusCode(404)
                                .awsErrorDetails(
                                        software.amazon.awssdk.awscore.exception.AwsErrorDetails
                                                .builder()
                                                .errorCode("NotFound")
                                                .build())
                                .build());

        assertNull(S3ObjectStoreSupport.getString(mockS3, "bucket", "missing.txt"));
    }

    @Test
    void getString_returnsContent_whenPresent() {
        S3Client mockS3 = mock(S3Client.class);
        ResponseBytes<GetObjectResponse> bytes =
                ResponseBytes.fromByteArray(
                        GetObjectResponse.builder().build(),
                        "hello".getBytes(StandardCharsets.UTF_8));
        when(mockS3.getObjectAsBytes(any(GetObjectRequest.class))).thenReturn(bytes);

        assertEquals("hello", S3ObjectStoreSupport.getString(mockS3, "bucket", "hello.txt"));
    }

    @Test
    void hasObjectsWithPrefix_detectsEmptyAndPresentResponses() {
        S3Client mockS3 = mock(S3Client.class);
        when(mockS3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(List.of()).build())
                .thenReturn(
                        ListObjectsV2Response.builder()
                                .contents(List.of(S3Object.builder().key("a").build()))
                                .build());

        assertFalse(S3ObjectStoreSupport.hasObjectsWithPrefix(mockS3, "bucket", "prefix/"));
        assertTrue(S3ObjectStoreSupport.hasObjectsWithPrefix(mockS3, "bucket", "prefix/"));
    }

    @Test
    void listAllKeys_handlesTruncationAndNullFlag() {
        S3Client mockS3 = mock(S3Client.class);
        when(mockS3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(
                        ListObjectsV2Response.builder()
                                .contents(List.of(S3Object.builder().key("a/1.json").build()))
                                .isTruncated(true)
                                .nextContinuationToken("next")
                                .build())
                .thenReturn(
                        ListObjectsV2Response.builder()
                                .contents(List.of(S3Object.builder().key("a/2.json").build()))
                                .build());

        assertEquals(
                List.of("a/1.json", "a/2.json"),
                S3ObjectStoreSupport.listAllKeys(mockS3, "bucket", "a/"));
    }

    @Test
    void deleteKeys_batchesByThousand() {
        S3Client mockS3 = mock(S3Client.class);
        List<String> keys = new java.util.ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            keys.add("key-" + i);
        }
        when(mockS3.deleteObjects(any(DeleteObjectsRequest.class)))
                .thenReturn(DeleteObjectsResponse.builder().build());

        S3ObjectStoreSupport.deleteKeys(mockS3, "bucket", keys);

        ArgumentCaptor<DeleteObjectsRequest> captor =
                ArgumentCaptor.forClass(DeleteObjectsRequest.class);
        verify(mockS3, times(2)).deleteObjects(captor.capture());
        List<DeleteObjectsRequest> requests = captor.getAllValues();
        assertEquals("bucket", requests.get(0).bucket());
        assertTrue(requests.get(0).delete().quiet());
        assertEquals(1000, requests.get(0).delete().objects().size());
        assertEquals(1, requests.get(1).delete().objects().size());
    }

    @Test
    void buildClient_usesEndpointOverridePathStyleAndAwsSigV4() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    requestPath.set(exchange.getRequestURI().getRawPath());
                    authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                });
        server.start();

        try (S3Client client =
                S3ObjectStoreSupport.buildClient(
                        URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                        Region.US_WEST_2,
                        "ak",
                        "sk")) {
            client.putObject(
                    PutObjectRequest.builder().bucket("test-bucket").key("my-key.txt").build(),
                    RequestBody.fromString("hello"));
        } finally {
            server.stop(0);
        }

        assertEquals("/test-bucket/my-key.txt", requestPath.get());
        assertNotNull(authorization.get());
        assertTrue(authorization.get().contains("AWS4-HMAC-SHA256"));
        assertTrue(authorization.get().contains("Credential=ak/"));
        assertTrue(authorization.get().contains("/us-west-2/s3/aws4_request"));
    }
}
