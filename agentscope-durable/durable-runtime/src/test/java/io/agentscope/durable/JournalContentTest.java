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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.durable.store.ObjectStore;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class JournalContentTest {
    @Test
    void growingHistorySharesBytesAndRecoversEveryVersion() {
        Map<String, byte[]> chunks = new HashMap<>();
        ObjectStore objects =
                new ObjectStore() {
                    public String id() {
                        return "test";
                    }

                    public Reference put(String scope, byte[] bytes) {
                        var ref = ObjectStore.reference(id(), scope, bytes);
                        chunks.putIfAbsent(ref.key(), bytes);
                        return ref;
                    }

                    public byte[] read(Reference ref) {
                        return chunks.get(ref.key());
                    }
                };
        byte[] history = new byte[512 * 2048];
        new Random(42).nextBytes(history);
        String scope = ObjectStore.digest(new byte[0]);
        var roots = new java.util.ArrayList<JournalContent.Root>();
        for (int turn = 1; turn <= 512; turn++) {
            roots.add(JournalContent.put(objects, scope, Arrays.copyOf(history, turn * 2048)));
        }
        assertTrue(roots.get(511).levels() >= 2);
        long stored = chunks.values().stream().mapToLong(chunk -> chunk.length).sum();
        assertTrue(stored < history.length * 10L, "Unique chunk and index bytes: " + stored);
        for (int i = 0; i < roots.size(); i++)
            assertArrayEquals(
                    Arrays.copyOf(history, (i + 1) * 2048),
                    JournalContent.read(objects, scope, roots.get(i)));
    }
}
