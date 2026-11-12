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

import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/** S3-compatible provider using conditional single-PUT creation, never mutable latest objects. */
public final class S3RuntimeObjectStore implements RuntimeObjectStore {
    private final MinioClient client;
    private final String bucket;
    private final String id;
    private final Semaphore requests;

    public S3RuntimeObjectStore(
            String id,
            String endpoint,
            String bucket,
            String region,
            String access,
            String secret) {
        this(id, endpoint, bucket, region, access, secret, 8);
    }

    public S3RuntimeObjectStore(
            String id,
            String endpoint,
            String bucket,
            String region,
            String access,
            String secret,
            int connections) {
        RuntimeObjectStore.validateBackend(id);
        this.requests = new Semaphore(connections);
        this.id = id;
        this.bucket = bucket;
        client =
                MinioClient.builder()
                        .endpoint(endpoint)
                        .credentials(access, secret)
                        .region(region)
                        .build();
        client.setTimeout(
                TimeUnit.SECONDS.toMillis(5),
                TimeUnit.SECONDS.toMillis(30),
                TimeUnit.SECONDS.toMillis(30));
        verifyConditionalCreation();
    }

    /** Fail closed if a compatible endpoint ignores conditional creation. Probe objects stay tiny. */
    private void verifyConditionalCreation() {
        String key = UUID.randomUUID().toString();
        try {
            var reference = put("provider-probe", "probe", key, new byte[] {1});
            boolean rejected = false;
            try {
                put("provider-probe", "probe", key, new byte[] {2});
            } catch (ManagedStateException conflict) {
                if (!"runtime_object_conflict".equals(conflict.code())) throw conflict;
                rejected = true;
            }
            if (!rejected || !Arrays.equals(read(reference), new byte[] {1}))
                throw new IllegalArgumentException(
                        "S3 endpoint does not enforce immutable creation");
        } catch (RuntimeException invalid) {
            close();
            throw invalid;
        }
    }

    @Override
    public String backendId() {
        return id;
    }

    private void acquire() {
        try {
            if (!requests.tryAcquire(5, TimeUnit.SECONDS))
                throw new ManagedTransportException("Object store capacity exceeded");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ManagedTransportException("Object operation cancelled", e);
        }
    }

    @Override
    public Reference put(String scope, String kind, String key, byte[] content) {
        String name = RuntimeObjectStore.objectKey(scope, kind, key);
        if (content.length < 1 || content.length > MAX_BYTES)
            throw new IllegalArgumentException("Object exceeds limit");
        acquire();
        boolean conflict = false;
        try {
            client.putObject(
                    PutObjectArgs.builder().bucket(bucket).object(name).stream(
                                    new ByteArrayInputStream(content),
                                    content.length,
                                    Math.max(5 * 1024 * 1024, content.length))
                            .extraHeaders(Map.of("If-None-Match", "*"))
                            .build());
        } catch (ErrorResponseException e) {
            if (e.response().code() == 409 || e.response().code() == 412) conflict = true;
            else throw new ManagedTransportException("S3 immutable upload failed", e);
        } catch (Exception e) {
            throw new ManagedTransportException("S3 immutable upload failed", e);
        } finally {
            requests.release();
        }
        if (conflict && !Arrays.equals(read(scope, kind, key), content))
            throw new ManagedStateException(
                    "runtime_object_conflict", "Immutable object cannot change");
        return new Reference(
                id, scope, kind, key, RuntimeObjectStore.sha256(content), content.length);
    }

    @Override
    public byte[] read(String scope, String kind, String key) {
        acquire();
        try (var response =
                client.getObject(
                        GetObjectArgs.builder()
                                .bucket(bucket)
                                .object(RuntimeObjectStore.objectKey(scope, kind, key))
                                .build())) {
            byte[] content = response.readNBytes(MAX_BYTES + 1);
            if (content.length < 1 || content.length > MAX_BYTES)
                throw new ManagedStateException("runtime_object_invalid", "Object exceeds limit");
            return content;
        } catch (ManagedStateException invalid) {
            throw invalid;
        } catch (Exception e) {
            throw new ManagedTransportException("S3 object unavailable", e);
        } finally {
            requests.release();
        }
    }

    @Override
    public boolean exists(String scope, String kind, String key) {
        acquire();
        try {
            client.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucket)
                            .object(RuntimeObjectStore.objectKey(scope, kind, key))
                            .build());
            return true;
        } catch (ErrorResponseException e) {
            if (e.response().code() == 404) return false;
            throw new ManagedTransportException("S3 stat failed", e);
        } catch (Exception e) {
            throw new ManagedTransportException("S3 stat failed", e);
        } finally {
            requests.release();
        }
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception e) {
            throw new ManagedTransportException("Cannot close S3 client", e);
        }
    }
}
