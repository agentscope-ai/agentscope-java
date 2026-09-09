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
package io.agentscope.builder.web.managed;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class ManagedMemoryAccessTest {
    @Test
    void readOnlyAppliesToDeleteAndMoveAsWellAsWrite() {
        var docs = Mockito.mock(MemoryDocumentStore.class);
        var fs = new MemoryStoreFilesystem(docs, "alice", "store", "notes", "read_only");
        fs.delete(null, "note.md");
        fs.move(null, "note.md", "other.md");
        fs.write(null, "note.md", "bad");
        fs.edit(null, "note.md", "a", "b", false);
        Mockito.verifyNoInteractions(docs);
    }

    @Test
    void editsUseObservedVersionAndLiteralReplacement() {
        var docs = Mockito.mock(MemoryDocumentStore.class);
        Mockito.when(docs.get("note.md"))
                .thenReturn(new MemoryDto("id", "store", "note.md", "before", 7, 0, 0));
        var fs = new MemoryStoreFilesystem(docs, "alice", "store", "notes", "read_write");
        fs.edit(null, "note.md", "before", "$value", false);
        Mockito.verify(docs).put("note.md", "$value", 7);
    }
}
