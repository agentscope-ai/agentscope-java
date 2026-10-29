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

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A save must persist the list it was handed. Editing an element without changing the list length is
 * a normal case (tool result eviction and compaction rewrite history in place), and it must not be
 * skipped just because the edited element is not one of the positions the change detector reads.
 */
class JsonFileAgentStateStoreListEditTest {

    private static final String USER = "user-1";
    private static final String SESSION = "session-1";
    private static final String KEY = "memory_messages";

    @TempDir Path tempDir;

    @Test
    void savePersistsAnEditOutsideSampledPositions() {
        var store = new JsonFileAgentStateStore(tempDir);
        List<Msg> messages = messages(10);
        store.save(USER, SESSION, KEY, messages);

        // Same length, same ids, one rewritten element: the list has changed in place.
        List<Msg> edited = new ArrayList<>(messages);
        edited.set(3, message("EDITED"));
        store.save(USER, SESSION, KEY, edited);

        List<Msg> reloaded = store.getList(USER, SESSION, KEY, Msg.class);
        assertEquals(10, reloaded.size());
        assertEquals("EDITED", reloaded.get(3).getTextContent());
        assertEquals("message 4", reloaded.get(4).getTextContent());
    }

    @Test
    void saveIsStillIncrementalWhenNothingChanged() {
        var store = new JsonFileAgentStateStore(tempDir);
        List<Msg> messages = messages(10);
        store.save(USER, SESSION, KEY, messages);
        store.save(USER, SESSION, KEY, messages);

        // An unchanged list may be skipped, and the stored content must stay intact.
        List<Msg> reloaded = store.getList(USER, SESSION, KEY, Msg.class);
        assertEquals(10, reloaded.size());
        assertEquals("message 0", reloaded.get(0).getTextContent());
    }

    private List<Msg> messages(int size) {
        List<Msg> list = new ArrayList<>();
        for (int i = 0; i < size; i++) list.add(message("message " + i));
        return list;
    }

    private Msg message(String text) {
        return Msg.builder()
                .role(MsgRole.USER)
                .content(TextBlock.builder().text(text).build())
                .build();
    }
}
