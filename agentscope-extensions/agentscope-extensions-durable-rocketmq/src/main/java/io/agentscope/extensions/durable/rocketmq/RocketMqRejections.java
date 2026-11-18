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
package io.agentscope.extensions.durable.rocketmq;

import io.agentscope.durable.DurableJson;
import io.agentscope.durable.store.RecordStore;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Private, durable quarantine. Inspection/replay authorization and retention belong to the host. */
public final class RocketMqRejections {
    public record Rejection(
            String id,
            String topic,
            String reason,
            long receivedAt,
            long size,
            String sha256,
            String bodyBase64) {}

    private final RecordStore records;
    private final String prefix;

    public RocketMqRejections(RecordStore records, String topic) {
        this.records = Objects.requireNonNull(records);
        prefix = "mq-rejected:" + DurableJson.hash(DurableJson.text(topic, "topic")) + ":";
    }

    Rejection record(String topic, ByteBuffer original, String reason) {
        var body = original.asReadOnlyBuffer();
        int size = body.remaining();
        String digest;
        try {
            var sha = MessageDigest.getInstance("SHA-256");
            sha.update(body.asReadOnlyBuffer());
            digest = HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        String encoded = null;
        if (size <= RocketMqLoop.MAX_BYTES) {
            var bytes = new byte[size];
            body.get(bytes);
            encoded = Base64.getEncoder().encodeToString(bytes);
        }
        String id = prefix + digest;
        String savedBody = encoded;
        return records.transaction(
                id,
                tx -> {
                    var old = tx.get("");
                    if (old != null) return DurableJson.convert(old, Rejection.class);
                    var value = new Rejection(id, topic, reason, tx.now(), size, digest, savedBody);
                    tx.put("", DurableJson.value(value));
                    return value;
                });
    }

    public List<Rejection> list(String after, int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("Limit must be 1..1000");
        return records.keys(prefix, after, limit).stream()
                .map(
                        key ->
                                records.transaction(
                                        key,
                                        tx -> DurableJson.convert(tx.get(""), Rejection.class)))
                .toList();
    }
}
