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
package io.agentscope.harness.agent.transcript;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FilesystemTranscriptStoreTest {

    @TempDir Path root;

    @Test
    void appendListRead_andConcurrentWritersDoNotOverwrite() throws Exception {
        FilesystemTranscriptStore store = new FilesystemTranscriptStore(root);
        TranscriptRef ref = new TranscriptRef("t", "agent", "sess");

        String k1 =
                store.appendSegment(
                        ref, 0, 0, "writer-a", "line-a\n".getBytes(StandardCharsets.UTF_8));
        String k2 =
                store.appendSegment(
                        ref, 1, 1, "writer-b", "line-b\n".getBytes(StandardCharsets.UTF_8));

        List<TranscriptStore.SegmentInfo> segs = store.listSegments(ref);
        assertEquals(2, segs.size());
        assertEquals(0, segs.get(0).seqStart());
        assertEquals(1, segs.get(1).seqStart());

        try (var in = store.readSegment(k1)) {
            assertEquals("line-a\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        try (var in = store.readSegment(k2)) {
            assertEquals("line-b\n", new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }

        store.compact(ref);
        List<TranscriptStore.SegmentInfo> after = store.listSegments(ref);
        assertEquals(1, after.size());
        try (var in = store.readSegment(after.get(0).key())) {
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(body.contains("line-a"));
            assertTrue(body.contains("line-b"));
        }
    }
}
