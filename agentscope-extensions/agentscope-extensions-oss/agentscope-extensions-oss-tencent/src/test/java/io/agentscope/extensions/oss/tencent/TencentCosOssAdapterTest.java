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
package io.agentscope.extensions.oss.tencent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.COSObjectSummary;
import com.qcloud.cos.model.ListObjectsRequest;
import com.qcloud.cos.model.ObjectListing;
import io.agentscope.extensions.oss.base.OssListObjectPage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TencentCosOssAdapterTest {

    private COSClient mockCos;
    private TencentCosOssAdapter adapter;

    @BeforeEach
    void setUp() {
        mockCos = mock(COSClient.class);
        adapter = new TencentCosOssAdapter(mockCos, "test-bucket");
    }

    @Test
    void constructorValidatesArguments() {
        assertThrows(NullPointerException.class, () -> new TencentCosOssAdapter(null, "b"));
        assertThrows(IllegalArgumentException.class, () -> new TencentCosOssAdapter(mockCos, ""));
    }

    @Test
    void existsReturnsFalseOn404() {
        CosServiceException e = new CosServiceException("not found");
        e.setStatusCode(404);
        when(mockCos.getObjectMetadata("test-bucket", "missing")).thenThrow(e);
        assertFalse(adapter.exists("missing"));
    }

    @Test
    void existsReturnsTrueOnSuccess() {
        when(mockCos.getObjectMetadata("test-bucket", "present")).thenReturn(null);
        assertTrue(adapter.exists("present"));
    }

    @Test
    void listMapsResultAndContinuationToken() {
        COSObjectSummary summary = new COSObjectSummary();
        summary.setKey("prefix/a");
        ObjectListing listing = new ObjectListing();
        listing.getObjectSummaries().add(summary);
        listing.setTruncated(true);
        listing.setNextMarker("marker-b");
        when(mockCos.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);

        OssListObjectPage page = adapter.list("prefix/", null, 100);

        assertEquals(1, page.objects().size());
        assertEquals("prefix/a", page.objects().get(0).key());
        assertEquals("marker-b", page.nextContinuationToken());
    }

    @Test
    void listReturnsNullTokenWhenNotTruncated() {
        ObjectListing listing = new ObjectListing();
        listing.setTruncated(false);
        when(mockCos.listObjects(any(ListObjectsRequest.class))).thenReturn(listing);
        OssListObjectPage page = adapter.list("prefix/", null, 100);
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
        verify(mockCos).shutdown();
    }
}
