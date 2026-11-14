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

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.PutObjectRequest;
import io.agentscope.durable.DurableException;
import io.agentscope.durable.store.ObjectStore;
import java.io.ByteArrayInputStream;
import java.util.Objects;

/** Native OSS immutable content. SDK client is borrowed; bucket versioning must be disabled. */
public final class OssObjectStore implements ObjectStore {
    private final OSS client;
    private final String bucket;
    private final String id;

    public OssObjectStore(String id, String bucket, OSS client) {
        ObjectStore.reference(id, "0".repeat(64), new byte[0]);
        this.id = id;
        this.bucket = Objects.requireNonNull(bucket);
        this.client = Objects.requireNonNull(client);
        String versioning = client.getBucketVersioning(bucket).getStatus();
        if (versioning != null && !"Off".equals(versioning))
            throw new IllegalArgumentException("Immutable OSS requires bucket versioning disabled");
    }

    public String id() {
        return id;
    }

    public Reference put(String scope, byte[] content) {
        var reference = ObjectStore.reference(id, scope, content);
        var metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        metadata.setHeader("x-oss-forbid-overwrite", "true");
        try {
            client.putObject(
                    new PutObjectRequest(
                            bucket, reference.key(), new ByteArrayInputStream(content), metadata));
        } catch (OSSException failure) {
            if (!"FileAlreadyExists".equals(failure.getErrorCode()))
                throw unavailable("OSS immutable upload unconfirmed", failure);
            read(reference);
        } catch (RuntimeException failure) {
            throw unavailable("OSS immutable upload unconfirmed", failure);
        }
        return reference;
    }

    public byte[] read(Reference reference) {
        if (!id.equals(reference.store()))
            throw new IllegalArgumentException("Object store differs");
        try (var object = client.getObject(bucket, reference.key());
                var input = object.getObjectContent()) {
            return ObjectStore.verify(id, reference, input.readNBytes(MAX_BYTES + 1));
        } catch (DurableException failure) {
            throw failure;
        } catch (Exception failure) {
            throw unavailable("OSS object unavailable", failure);
        }
    }

    private static DurableException unavailable(String message, Exception failure) {
        return new DurableException(DurableException.Code.UNAVAILABLE, message, failure);
    }
}
