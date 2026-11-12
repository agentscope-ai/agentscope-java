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
package io.agentscope.durable.store;

import io.agentscope.durable.DurableException;
import io.agentscope.durable.DurableJson;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

/** Database fallback for small installations; production hosts may supply object storage. */
public final class RecordObjectStore implements ObjectStore {
    private final RecordStore records;

    public RecordObjectStore(RecordStore records) {
        this.records = Objects.requireNonNull(records);
    }

    public String id() {
        return "records";
    }

    private static final int CHUNK_BYTES = 1024 * 1024;

    public Reference put(String scope, byte[] bytes) {
        var reference = ObjectStore.reference(id(), scope, bytes);
        records.transaction(
                "object:" + reference.key(),
                tx -> {
                    var existing = tx.get("");
                    if (existing != null) {
                        if (!reference.equals(DurableJson.convert(existing, Reference.class)))
                            throw new DurableException(
                                    DurableException.Code.CONFLICT, "Immutable object changed");
                        return null;
                    }
                    for (int offset = 0; offset < bytes.length; offset += CHUNK_BYTES) {
                        byte[] chunk =
                                Arrays.copyOfRange(
                                        bytes,
                                        offset,
                                        Math.min(bytes.length, offset + CHUNK_BYTES));
                        tx.put(
                                "chunk:" + offset / CHUNK_BYTES,
                                DurableJson.value(Base64.getEncoder().encodeToString(chunk)));
                    }
                    tx.put("", DurableJson.value(reference));
                    return null;
                });
        return reference;
    }

    public byte[] read(Reference reference) {
        if (!id().equals(reference.store()))
            throw new DurableException(DurableException.Code.INVALID, "Object store differs");
        return records.transaction(
                "object:" + reference.key(),
                tx -> {
                    var existing = tx.get("");
                    if (existing == null)
                        throw new DurableException(
                                DurableException.Code.NOT_FOUND, "Object not found");
                    if (!reference.equals(DurableJson.convert(existing, Reference.class)))
                        throw new DurableException(
                                DurableException.Code.INVALID, "Object header differs");
                    var bytes = new ByteArrayOutputStream();
                    for (long offset = 0; offset < reference.size(); offset += CHUNK_BYTES) {
                        var chunk = tx.get("chunk:" + offset / CHUNK_BYTES);
                        if (chunk == null)
                            throw new DurableException(
                                    DurableException.Code.INVALID, "Object chunk missing");
                        byte[] decoded = Base64.getDecoder().decode(chunk.asText());
                        if (decoded.length != Math.min(CHUNK_BYTES, reference.size() - offset))
                            throw new DurableException(
                                    DurableException.Code.INVALID, "Object chunk size differs");
                        bytes.writeBytes(decoded);
                    }
                    return ObjectStore.verify(id(), reference, bytes.toByteArray());
                });
    }
}
