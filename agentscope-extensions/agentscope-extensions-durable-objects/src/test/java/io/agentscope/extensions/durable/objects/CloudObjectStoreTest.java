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

package io.agentscope.extensions.durable.objects;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.BucketVersioningConfiguration;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.PutObjectRequest;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.store.ObjectStore;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CloudObjectStoreTest {
    @Test
    void ossConditionalRetryChecksExistingBytesAndRejectsCorruption() throws Exception {
        var client = mock(OSS.class);
        when(client.getBucketVersioning("bucket")).thenReturn(new BucketVersioningConfiguration());
        var store = new OssObjectStore("oss", "bucket", client);
        byte[] bytes = "content".getBytes(StandardCharsets.UTF_8);
        var reference = store.put("0".repeat(64), bytes);
        var argument = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(client).putObject(argument.capture());
        assertEquals(
                "true",
                argument.getValue().getMetadata().getRawMetadata().get("x-oss-forbid-overwrite"));
        var conflict = mock(OSSException.class);
        when(conflict.getErrorCode()).thenReturn("FileAlreadyExists");
        when(client.putObject(any(PutObjectRequest.class))).thenThrow(conflict);
        var object = new OSSObject();
        object.setObjectContent(new ByteArrayInputStream(bytes));
        when(client.getObject("bucket", reference.key())).thenReturn(object);
        assertEquals(reference, store.put("0".repeat(64), bytes));
        var corrupt = new OSSObject();
        corrupt.setObjectContent(new ByteArrayInputStream(new byte[] {0}));
        when(client.getObject("bucket", reference.key())).thenReturn(corrupt);
        assertThrows(DurableException.class, () -> store.read(reference));
        verify(client, never()).shutdown();
    }

    @Test
    void s3UsesConditionalSinglePutAndVerifiesReference() throws Exception {
        var client = mock(MinioClient.class);
        var store = new S3ObjectStore("s3", "bucket", client);
        byte[] bytes = new byte[] {1, 2, 3};
        var reference = store.put("0".repeat(64), bytes);
        var argument = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(client).putObject(argument.capture());
        assertEquals(
                "*", argument.getValue().extraHeaders().get("If-None-Match").iterator().next());
        var response =
                new GetObjectResponse(
                        Headers.of(),
                        "bucket",
                        null,
                        reference.key(),
                        new ByteArrayInputStream(bytes));
        when(client.getObject(any(GetObjectArgs.class))).thenReturn(response);
        assertArrayEquals(bytes, store.read(reference));
        assertThrows(
                IllegalArgumentException.class,
                () -> store.read(ObjectStore.reference("other", "0".repeat(64), bytes)));
        verify(client, never()).close();
    }
}
