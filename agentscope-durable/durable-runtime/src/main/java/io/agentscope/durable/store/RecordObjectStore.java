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
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/** Database fallback for small installations; production hosts may supply object storage. */
public final class RecordObjectStore implements ObjectStore {
    private final RecordStore records;
    // Large journal entries revisit the same immutable chunks on both append and recovery.
    // Retain at most 16 MiB of verified content per store, allowing cold readers to use the DB.
    private final LinkedHashMap<Reference, byte[]> chunks = new LinkedHashMap<>(64, 0.75f, true);

    private synchronized byte[] cached(Reference ref) {
        return chunks.get(ref);
    }

    private synchronized void remember(Reference ref, byte[] bytes) {
        if (bytes.length > 8192) return;
        chunks.put(ref, bytes.clone());
        if (chunks.size() > 2048) chunks.remove(chunks.keySet().iterator().next());
    }

    public RecordObjectStore(RecordStore records) {
        this.records = Objects.requireNonNull(records);
    }

    public String id() {
        return "records";
    }

    private static final int CHUNK_BYTES = 1024 * 1024;

    public Reference put(String scope, byte[] bytes) {
        var reference = ObjectStore.reference(id(), scope, bytes);
        if (cached(reference) != null) return reference;
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
                        tx.put("chunk:" + offset / CHUNK_BYTES, DurableJson.value(encode(chunk)));
                    }
                    tx.put("", DurableJson.value(reference));
                    return null;
                });
        remember(reference, bytes);
        return reference;
    }

    // Keep incompressible objects raw. The marker is outside the Base64 alphabet.
    private static String encode(byte[] chunk) {
        if (chunk.length >= 256) {
            var encoder = new Deflater(Deflater.BEST_SPEED);
            try {
                encoder.setInput(chunk);
                encoder.finish();
                byte[] compressed = new byte[chunk.length - 16];
                int length = encoder.deflate(compressed);
                if (encoder.finished())
                    return "~"
                            + Base64.getEncoder().encodeToString(Arrays.copyOf(compressed, length));
            } finally {
                encoder.end();
            }
        }
        return Base64.getEncoder().encodeToString(chunk);
    }

    private static byte[] decode(String stored, int size) {
        if (!stored.startsWith("~")) return Base64.getDecoder().decode(stored);
        var decoder = new Inflater();
        try {
            decoder.setInput(Base64.getDecoder().decode(stored.substring(1)));
            byte[] bytes = new byte[size];
            if (decoder.inflate(bytes) == size && decoder.finished() && decoder.getRemaining() == 0)
                return bytes;
            throw new DurableException(
                    DurableException.Code.INVALID, "Invalid compressed object size");
        } catch (DataFormatException invalid) {
            throw new DurableException(
                    DurableException.Code.INVALID, "Invalid compressed object", invalid);
        } finally {
            decoder.end();
        }
    }

    public byte[] read(Reference reference) {
        if (!id().equals(reference.store()))
            throw new DurableException(DurableException.Code.INVALID, "Object store differs");
        byte[] hit = cached(reference);
        if (hit != null) return hit.clone();
        byte[] loaded =
                records.transaction(
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
                            for (long offset = 0;
                                    offset < reference.size();
                                    offset += CHUNK_BYTES) {
                                var chunk = tx.get("chunk:" + offset / CHUNK_BYTES);
                                if (chunk == null)
                                    throw new DurableException(
                                            DurableException.Code.INVALID, "Object chunk missing");
                                byte[] decoded =
                                        decode(
                                                chunk.asText(),
                                                (int)
                                                        Math.min(
                                                                CHUNK_BYTES,
                                                                reference.size() - offset));
                                if (decoded.length
                                        != Math.min(CHUNK_BYTES, reference.size() - offset))
                                    throw new DurableException(
                                            DurableException.Code.INVALID,
                                            "Object chunk size differs");
                                bytes.writeBytes(decoded);
                            }
                            return ObjectStore.verify(id(), reference, bytes.toByteArray());
                        });
        remember(reference, loaded);
        return loaded;
    }
}
