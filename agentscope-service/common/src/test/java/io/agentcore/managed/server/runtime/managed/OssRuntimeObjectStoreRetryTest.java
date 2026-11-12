/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentcore.managed.server.runtime.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.BucketVersioningConfiguration;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.PutObjectRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OssRuntimeObjectStoreRetryTest {
    private final OSS client = mock(OSS.class);

    private OssRuntimeObjectStore store() {
        when(client.getBucketVersioning("bucket")).thenReturn(new BucketVersioningConfiguration());
        return new OssRuntimeObjectStore("oss", "bucket", client);
    }

    private static OSSException serviceFailure(String code) {
        return new OSSException("service failure", code, "request", "host", null, null, null);
    }

    private static ClientException disconnected() {
        return new ClientException("peer closed connection", new IOException("connection closed"));
    }

    private static OSSObject object(byte[] bytes) {
        var object = new OSSObject();
        object.setObjectContent(new ByteArrayInputStream(bytes));
        return object;
    }

    @Test
    void existingCheckpointStillRequiresContentVerificationAfterDisconnectedRead() {
        when(client.putObject(any(PutObjectRequest.class)))
                .thenThrow(serviceFailure("FileAlreadyExists"));
        when(client.getObject("bucket", "scope/harness-object/key"))
                .thenThrow(disconnected())
                .thenReturn(object(new byte[] {1, 2}));
        try (var store = store()) {
            var ref = store.put("scope", "harness-object", "key", new byte[] {1, 2});
            assertEquals(RuntimeObjectStore.sha256(new byte[] {1, 2}), ref.sha256());
            verify(client, times(1)).putObject(any(PutObjectRequest.class));
            verify(client, times(2)).getObject("bucket", "scope/harness-object/key");
        }
    }

    @Test
    void lostUploadAcknowledgementUsesSameImmutableBytesAndChecksExistingObject() throws Exception {
        when(client.putObject(any(PutObjectRequest.class)))
                .thenThrow(disconnected())
                .thenThrow(serviceFailure("FileAlreadyExists"));
        when(client.getObject("bucket", "scope/harness-object/key"))
                .thenReturn(object(new byte[] {3}));
        try (var store = store()) {
            assertEquals(1, store.put("scope", "harness-object", "key", new byte[] {3}).size());
            var requests = ArgumentCaptor.forClass(PutObjectRequest.class);
            verify(client, times(2)).putObject(requests.capture());
            for (var request : requests.getAllValues()) {
                assertEquals("scope/harness-object/key", request.getKey());
                assertEquals(3, request.getInputStream().read());
                assertEquals(
                        "true",
                        request.getMetadata().getRawMetadata().get("x-oss-forbid-overwrite"));
            }
            verify(client).getObject("bucket", "scope/harness-object/key");
        }
    }

    @Test
    void persistentTransportFailureRemainsUnknownAndIsBounded() {
        when(client.getObject("bucket", "scope/harness-object/key")).thenThrow(disconnected());
        try (var store = store()) {
            assertThrows(
                    ManagedTransportException.class,
                    () -> store.read("scope", "harness-object", "key"));
            verify(client, times(2)).getObject("bucket", "scope/harness-object/key");
        }
    }

    @Test
    void permissionDenialAndConflictingContentAreNeverRetriedOrAccepted() {
        when(client.putObject(any(PutObjectRequest.class)))
                .thenThrow(serviceFailure("AccessDenied"));
        try (var store = store()) {
            assertThrows(
                    ManagedTransportException.class,
                    () -> store.put("scope", "harness-object", "key", new byte[] {1}));
            verify(client).putObject(any(PutObjectRequest.class));
        }
        when(client.putObject(any(PutObjectRequest.class)))
                .thenThrow(serviceFailure("FileAlreadyExists"));
        when(client.getObject("bucket", "scope/harness-object/key"))
                .thenReturn(object(new byte[] {2}));
        try (var store = store()) {
            assertThrows(
                    ManagedStateException.class,
                    () -> store.put("scope", "harness-object", "key", new byte[] {1}));
            verify(client, times(2)).putObject(any(PutObjectRequest.class));
            verify(client).getObject("bucket", "scope/harness-object/key");
        }
    }

    @Test
    void interruptedBodyReadIsClosedWithoutAnotherRequest() throws Exception {
        var input = mock(InputStream.class);
        when(input.readNBytes(RuntimeObjectStore.MAX_BYTES + 1))
                .thenThrow(new InterruptedIOException("cancelled"));
        var object = new OSSObject();
        object.setObjectContent(input);
        when(client.getObject("bucket", "scope/harness-object/key")).thenReturn(object);
        try (var store = store()) {
            assertThrows(
                    ManagedTransportException.class,
                    () -> store.read("scope", "harness-object", "key"));
            verify(client).getObject("bucket", "scope/harness-object/key");
            verify(input, times(2)).close();
        }
    }
}
