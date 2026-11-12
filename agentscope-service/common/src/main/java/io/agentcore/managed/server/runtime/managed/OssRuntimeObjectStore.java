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

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.ClientException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.concurrent.Callable;

/** Native OSS backend. The bucket must have versioning disabled for forbid-overwrite semantics. */
public final class OssRuntimeObjectStore implements RuntimeObjectStore {
    private final OSS client;
    private final String bucket;
    private final String id;

    public OssRuntimeObjectStore(
            String id, String endpoint, String bucket, String access, String secret) {
        this(id, endpoint, bucket, access, secret, 8);
    }

    public OssRuntimeObjectStore(
            String id,
            String endpoint,
            String bucket,
            String access,
            String secret,
            int connections) {
        this(
                id,
                bucket,
                new OSSClientBuilder().build(endpoint, access, secret, configuration(connections)));
    }

    private static ClientBuilderConfiguration configuration(int connections) {
        var config = new ClientBuilderConfiguration();
        config.setConnectionTimeout(5000);
        config.setSocketTimeout(30000);
        config.setMaxConnections(connections);
        config.setMaxErrorRetry(0);
        return config;
    }

    OssRuntimeObjectStore(String id, String bucket, OSS client) {
        RuntimeObjectStore.validateBackend(id);
        this.id = id;
        this.bucket = bucket;
        this.client = client;
        String versioning = client.getBucketVersioning(bucket).getStatus();
        if (versioning != null && !"Off".equals(versioning)) {
            client.shutdown();
            throw new IllegalArgumentException(
                    "Immutable OSS backend requires versioning disabled");
        }
    }

    @Override
    public String backendId() {
        return id;
    }

    @Override
    public Reference put(String scope, String kind, String key, byte[] content) {
        String name = RuntimeObjectStore.objectKey(scope, kind, key);
        if (content.length < 1 || content.length > MAX_BYTES)
            throw new IllegalArgumentException("Object exceeds limit");
        transfer(
                "OSS upload failed",
                () -> {
                    var metadata = new ObjectMetadata();
                    metadata.setContentLength(content.length);
                    metadata.setHeader("x-oss-forbid-overwrite", "true");
                    try {
                        client.putObject(
                                new PutObjectRequest(
                                        bucket, name, new ByteArrayInputStream(content), metadata));
                    } catch (OSSException e) {
                        if (!"FileAlreadyExists".equals(e.getErrorCode())) throw e;
                        if (!Arrays.equals(read(scope, kind, key), content))
                            throw new ManagedStateException(
                                    "runtime_object_conflict", "Immutable object cannot change");
                    }
                    return null;
                });
        return new Reference(
                id, scope, kind, key, RuntimeObjectStore.sha256(content), content.length);
    }

    @Override
    public byte[] read(String scope, String kind, String key) {
        String name = RuntimeObjectStore.objectKey(scope, kind, key);
        return transfer(
                "OSS object unavailable",
                () -> {
                    try (var object = client.getObject(bucket, name);
                            var input = object.getObjectContent()) {
                        byte[] content = input.readNBytes(MAX_BYTES + 1);
                        if (content.length < 1 || content.length > MAX_BYTES)
                            throw new ManagedStateException(
                                    "runtime_object_invalid", "Object exceeds limit");
                        return content;
                    }
                });
    }

    private static <T> T transfer(String message, Callable<T> operation) {
        for (int attempt = 0; ; attempt++) {
            try {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException("Object operation cancelled");
                return operation.call();
            } catch (ManagedStateException | ManagedTransportException known) {
                throw known;
            } catch (Exception failure) {
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                // Only repeat immutable I/O, once, on a fresh SDK request. A lost upload
                // acknowledgement still requires forbid-overwrite plus byte comparison.
                // Never retry permissions, content conflicts, cancellation or model/tool work.
                Throwable cause = failure instanceof ClientException ? failure.getCause() : failure;
                if (attempt == 0
                        && cause instanceof IOException
                        && !(cause instanceof InterruptedIOException)
                        && !Thread.currentThread().isInterrupted()) continue;
                throw new ManagedTransportException(message, failure);
            }
        }
    }

    @Override
    public boolean exists(String scope, String kind, String key) {
        return client.doesObjectExist(bucket, RuntimeObjectStore.objectKey(scope, kind, key));
    }

    @Override
    public void close() {
        client.shutdown();
    }
}
