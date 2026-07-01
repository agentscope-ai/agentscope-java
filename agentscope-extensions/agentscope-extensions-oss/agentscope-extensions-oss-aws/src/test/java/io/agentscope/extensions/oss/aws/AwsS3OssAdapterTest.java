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
package io.agentscope.extensions.oss.aws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.agentscope.extensions.oss.base.OssListPage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

class AwsS3OssAdapterTest {

    private S3Client mockS3;
    private AwsS3OssAdapter adapter;

    @BeforeEach
    void setUp() {
        mockS3 = mock(S3Client.class);
        adapter = new AwsS3OssAdapter(mockS3, "test-bucket");
    }

    @Test
    void constructorValidatesArguments() {
        assertThrows(NullPointerException.class, () -> new AwsS3OssAdapter(null, "b"));
        assertThrows(IllegalArgumentException.class, () -> new AwsS3OssAdapter(mockS3, ""));
    }

    @Test
    void existsReturnsFalseWhenNoSuchKey() {
        when(mockS3.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build());
        assertFalse(adapter.exists("missing"));
    }

    @Test
    void existsReturnsTrueOnSuccess() {
        when(mockS3.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());
        assertTrue(adapter.exists("present"));
    }

    @Test
    void listMapsResponseAndContinuationToken() {
        ListObjectsV2Response response =
                ListObjectsV2Response.builder()
                        .contents(S3Object.builder().key("prefix/a").build())
                        .isTruncated(true)
                        .nextContinuationToken("next-token")
                        .build();
        when(mockS3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(response);

        OssListPage page = adapter.list("prefix/", null, 50);

        assertEquals(1, page.objects().size());
        assertEquals("prefix/a", page.objects().get(0).key());
        assertEquals("next-token", page.nextContinuationToken());
    }

    @Test
    void listReturnsNullTokenWhenNotTruncated() {
        ListObjectsV2Response response = ListObjectsV2Response.builder().isTruncated(false).build();
        when(mockS3.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(response);
        OssListPage page = adapter.list("prefix/", null, 50);
        assertNull(page.nextContinuationToken());
    }

    @Test
    void deleteBatchIgnoresEmptyList() {
        adapter.deleteBatch(List.of());
        // no interaction with the client
    }

    @Test
    void closeShutsDownClient() {
        adapter.close();
        verify(mockS3).close();
    }
}
