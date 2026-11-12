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

import io.agentscope.durable.DurableException;
import io.agentscope.durable.store.ObjectStore;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import java.util.Map;
import java.util.Objects;

/** S3-compatible, content-addressed single PUT. SDK client, credentials and bucket are host-owned. */
public final class S3ObjectStore implements ObjectStore {
    private final MinioClient client;
    private final String bucket;
    private final String id;

    public S3ObjectStore(String id, String bucket, MinioClient client) {
        ObjectStore.reference(id, "0".repeat(64), new byte[0]);
        this.id = id;
        this.bucket = Objects.requireNonNull(bucket);
        this.client = Objects.requireNonNull(client);
    }

    public String id() {
        return id;
    }

    public Reference put(String scope, byte[] content) {
        var reference = ObjectStore.reference(id, scope, content);
        try {
            client.putObject(
                    PutObjectArgs.builder().bucket(bucket).object(reference.key()).stream(
                                    new ByteArrayInputStream(content),
                                    content.length,
                                    Math.max(5 * 1024 * 1024, content.length))
                            .extraHeaders(Map.of("If-None-Match", "*"))
                            .build());
        } catch (ErrorResponseException failure) {
            if (failure.response().code() != 409 && failure.response().code() != 412)
                throw unavailable("S3 immutable upload unconfirmed", failure);
            read(reference);
        } catch (Exception failure) {
            throw unavailable("S3 immutable upload unconfirmed", failure);
        }
        return reference;
    }

    public byte[] read(Reference reference) {
        if (!id.equals(reference.store()))
            throw new IllegalArgumentException("Object store differs");
        try (var input =
                client.getObject(
                        GetObjectArgs.builder().bucket(bucket).object(reference.key()).build())) {
            return ObjectStore.verify(id, reference, input.readNBytes(MAX_BYTES + 1));
        } catch (DurableException failure) {
            throw failure;
        } catch (Exception failure) {
            throw unavailable("S3 object unavailable", failure);
        }
    }

    private static DurableException unavailable(String message, Exception failure) {
        return new DurableException(DurableException.Code.UNAVAILABLE, message, failure);
    }
}
