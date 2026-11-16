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
package io.agentscope.durable;

import io.agentscope.durable.store.ObjectStore;
import io.agentscope.durable.store.ObjectStore.Reference;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HexFormat;

/** Content-defined chunks let successive full-history journal entries share their stable bytes. */
final class JournalContent {
    private static final int MIN = 1024;
    private static final int MAX = 8192;
    private static final int DESCRIPTOR = 36;
    // The manifest changes on every append. Small, descriptor-aligned pages allow the
    // stable interior of the index to be reused before it reaches data-chunk size.
    private static final int MAX_INDEX = 32 * DESCRIPTOR;
    private static final HexFormat HEX = HexFormat.of();

    record Root(Reference reference, int levels, int size, String digest) {}

    static Root put(ObjectStore store, String scope, byte[] bytes) {
        if (bytes.length > ObjectStore.MAX_BYTES)
            throw new IllegalArgumentException("Journal content too large");
        byte[] layer = bytes;
        int levels = 0;
        if (layer.length > MAX) {
            var index = new ByteArrayOutputStream();
            int hash = 0;
            int start = 0;
            for (int i = 0; i < layer.length; i++) {
                hash = (hash << 1) + GEAR[layer[i] & 255];
                int length = i - start + 1;
                if (length >= MAX
                        || (length >= MIN && (hash & 2047) == 0)
                        || i == layer.length - 1) {
                    appendChunk(store, scope, layer, start, i + 1, index);
                    start = i + 1;
                    hash = 0;
                }
            }
            layer = index.toByteArray();
            levels++;
        }
        while (levels > 0 && layer.length > MAX_INDEX) {
            var index = new ByteArrayOutputStream();
            int start = 0;
            for (int end = DESCRIPTOR; end <= layer.length; end += DESCRIPTOR) {
                int length = end - start;
                // The last digest byte is stable even when preceding descriptors change.
                if (length == MAX_INDEX
                        || (length >= 4 * DESCRIPTOR && (layer[end - 1] & 7) == 0)
                        || end == layer.length) {
                    appendChunk(store, scope, layer, start, end, index);
                    start = end;
                }
            }
            layer = index.toByteArray();
            levels++;
        }
        return new Root(store.put(scope, layer), levels, bytes.length, ObjectStore.digest(bytes));
    }

    private static void appendChunk(
            ObjectStore store,
            String scope,
            byte[] layer,
            int start,
            int end,
            ByteArrayOutputStream index) {
        var chunk = store.put(scope, Arrays.copyOfRange(layer, start, end));
        index.writeBytes(
                ByteBuffer.allocate(DESCRIPTOR)
                        .putInt(end - start)
                        .put(HEX.parseHex(chunk.digest()))
                        .array());
    }

    private static final int[] GEAR = gearTable();

    private static int[] gearTable() {
        var random = new java.util.Random(0x4167656e74L);
        var table = new int[256];
        for (int i = 0; i < table.length; i++) table[i] = random.nextInt();
        return table;
    }

    static byte[] read(ObjectStore store, String scope, Root root) {
        if (!root.reference().scope().equals(scope) || root.levels() < 0 || root.levels() > 8)
            throw new DurableException(DurableException.Code.INVALID, "Invalid journal index");
        var expected = new Reference(store.id(), scope, root.digest(), root.size());
        byte[] layer = store.read(root.reference());
        for (int level = root.levels(); level > 0; level--) {
            if (layer.length % DESCRIPTOR != 0)
                throw new DurableException(
                        DurableException.Code.INVALID, "Incomplete journal index");
            var index = ByteBuffer.wrap(layer);
            var restored = new ByteArrayOutputStream();
            while (index.hasRemaining()) {
                int length = index.getInt();
                if (length <= 0 || length > MAX || length > root.size() - restored.size())
                    throw new DurableException(
                            DurableException.Code.INVALID, "Invalid journal chunk size");
                byte[] digest = new byte[32];
                index.get(digest);
                restored.writeBytes(
                        store.read(
                                new Reference(store.id(), scope, HEX.formatHex(digest), length)));
            }
            layer = restored.toByteArray();
        }
        return ObjectStore.verify(store.id(), expected, layer);
    }
}
