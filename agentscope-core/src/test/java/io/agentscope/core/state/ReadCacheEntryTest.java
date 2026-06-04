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
package io.agentscope.core.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReadCacheEntryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void linesAreDefensivelyCopied() {
        List<String> source = new ArrayList<>(List.of("line1", "line2"));
        ReadCacheEntry entry = new ReadCacheEntry(source, 1_700_000_000.5, 0.25, "/tmp/file.txt");
        source.add("MUTATED");
        assertEquals(2, entry.lines().size());
        assertThrows(UnsupportedOperationException.class, () -> entry.lines().add("evil"));
    }

    @Test
    void rejectsNullLines() {
        assertThrows(
                NullPointerException.class,
                () -> new ReadCacheEntry(null, 1.0, 0.0, "/tmp/file.txt"));
    }

    @Test
    void rejectsNullFilePath() {
        assertThrows(
                NullPointerException.class, () -> new ReadCacheEntry(List.of(), 1.0, 0.0, null));
    }

    @Test
    void jsonRoundTrip() throws Exception {
        ReadCacheEntry original =
                new ReadCacheEntry(List.of("a", "b"), 1_700_000_000.123, 1.5, "/tmp/file.txt");
        String json = mapper.writeValueAsString(original);
        ReadCacheEntry decoded = mapper.readValue(json, ReadCacheEntry.class);
        assertEquals(original, decoded);
    }
}
