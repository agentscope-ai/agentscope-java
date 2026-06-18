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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

class OssRemoteSnapshotClientTest {

    @Test
    void constructorRejectsBlankBucket() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OssRemoteSnapshotClient(mock(S3Client.class), "", "prefix/"));
    }

    @Test
    void upload_usesTarObjectKey() throws Exception {
        S3Client mockS3 = mock(S3Client.class);
        OssRemoteSnapshotClient client = new OssRemoteSnapshotClient(mockS3, "bucket", "/prefix/");

        client.upload("snap-1", new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8)));

        verify(mockS3)
                .putObject(
                        argThat(
                                (PutObjectRequest req) ->
                                        req != null
                                                && "bucket".equals(req.bucket())
                                                && "prefix/snap-1.tar".equals(req.key())),
                        any(RequestBody.class));
    }

    @Test
    void exists_checksPrefix() throws Exception {
        S3Client mockS3 = mock(S3Client.class);
        when(mockS3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(
                        ListObjectsV2Response.builder()
                                .contents(
                                        List.of(
                                                S3Object.builder()
                                                        .key("prefix/snap-1.tar")
                                                        .build()))
                                .build());

        OssRemoteSnapshotClient client = new OssRemoteSnapshotClient(mockS3, "bucket", "prefix/");

        assertTrue(client.exists("snap-1"));
    }

    @Test
    void download_throwsWhenMissing() {
        S3Client mockS3 = mock(S3Client.class);
        when(mockS3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(ListObjectsV2Response.builder().contents(List.of()).build());
        OssRemoteSnapshotClient client = new OssRemoteSnapshotClient(mockS3, "bucket", "prefix/");

        assertThrows(FileNotFoundException.class, () -> client.download("snap-1"));
    }

    @Test
    void download_returnsStream_whenPresent() throws Exception {
        S3Client mockS3 = mock(S3Client.class);
        when(mockS3.listObjectsV2(any(ListObjectsV2Request.class)))
                .thenReturn(
                        ListObjectsV2Response.builder()
                                .contents(
                                        List.of(
                                                S3Object.builder()
                                                        .key("prefix/snap-1.tar")
                                                        .build()))
                                .build());
        GetObjectRequest request =
                argThat(
                        (GetObjectRequest req) ->
                                req != null
                                        && "bucket".equals(req.bucket())
                                        && "prefix/snap-1.tar".equals(req.key()));
        when(mockS3.getObjectAsBytes(request))
                .thenReturn(
                        ResponseBytes.fromByteArray(
                                GetObjectResponse.builder().build(),
                                "payload".getBytes(StandardCharsets.UTF_8)));
        OssRemoteSnapshotClient client = new OssRemoteSnapshotClient(mockS3, "bucket", "prefix/");

        try (InputStream in = client.download("snap-1")) {
            assertEquals("payload", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
