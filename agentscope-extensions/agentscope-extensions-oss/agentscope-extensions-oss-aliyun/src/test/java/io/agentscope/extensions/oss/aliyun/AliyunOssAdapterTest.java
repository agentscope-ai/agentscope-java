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
package io.agentscope.extensions.oss.aliyun;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ListObjectsV2Request;
import com.aliyun.oss.model.ListObjectsV2Result;
import com.aliyun.oss.model.OSSObjectSummary;
import io.agentscope.extensions.oss.base.OssListPage;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AliyunOssAdapterTest {

    private OSS mockOss;
    private AliyunOssAdapter adapter;

    @BeforeEach
    void setUp() {
        mockOss = mock(OSS.class);
        adapter = new AliyunOssAdapter(mockOss, "test-bucket");
    }

    @Test
    void constructorValidatesArguments() {
        assertThrows(NullPointerException.class, () -> new AliyunOssAdapter(null, "b"));
        assertThrows(IllegalArgumentException.class, () -> new AliyunOssAdapter(mockOss, ""));
    }

    @Test
    void existsDelegatesToSdk() {
        when(mockOss.doesObjectExist("test-bucket", "k")).thenReturn(true);
        assertTrue(adapter.exists("k"));
    }

    @Test
    void putBytesInvokesPutObject() {
        adapter.putBytes("k", "hi".getBytes());
        verify(mockOss).putObject(eq("test-bucket"), eq("k"), any(InputStream.class));
    }

    @Test
    void getBytesReturnsNullWhenMissing() {
        when(mockOss.doesObjectExist("test-bucket", "k")).thenReturn(false);
        assertNull(adapter.getBytes("k"));
    }

    @Test
    void listMapsResultAndContinuationToken() {
        OSSObjectSummary summary = new OSSObjectSummary();
        summary.setKey("prefix/a");
        ListObjectsV2Result result = new ListObjectsV2Result();
        result.getObjectSummaries().add(summary);
        result.setTruncated(true);
        result.setNextContinuationToken("next-token");
        when(mockOss.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(result);

        OssListPage page = adapter.list("prefix/", null, 100);

        assertEquals(1, page.objects().size());
        assertEquals("prefix/a", page.objects().get(0).key());
        assertEquals("next-token", page.nextContinuationToken());
    }

    @Test
    void listReturnsNullTokenWhenNotTruncated() {
        ListObjectsV2Result result = new ListObjectsV2Result();
        result.setTruncated(false);
        when(mockOss.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(result);
        OssListPage page = adapter.list("prefix/", null, 100);
        assertNull(page.nextContinuationToken());
        assertTrue(page.objects().isEmpty());
    }

    @Test
    void deleteBatchIgnoresEmptyList() {
        adapter.deleteBatch(List.of());
        // no exception, no interaction with client
    }

    @Test
    void closeShutsDownClient() {
        adapter.close();
        verify(mockOss).shutdown();
    }
}
