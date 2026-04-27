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
package io.agentscope.harness.agent.memory.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.StoreFilesystemSpec;
import io.agentscope.harness.agent.store.InMemoryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SessionTreeMirrorTest {

    @TempDir Path workspace;

    @Test
    void mirrorsToFilesystemAndCanRestoreWhenLocalFilesMissing() throws Exception {
        InMemoryStore store = new InMemoryStore();
        AbstractFilesystem fs =
                new StoreFilesystemSpec(store)
                        .toFilesystem(workspace, "agent-a", List::of, () -> "user-1");

        Path context = workspace.resolve("agents/agent-a/sessions/s1.jsonl");
        SessionTree writer = new SessionTree(context, workspace, fs);
        writer.append(new SessionEntry.MessageEntry(null, null, null, "USER", "hello", null));
        writer.flush();

        assertTrue(Files.isRegularFile(context));
        assertTrue(Files.isRegularFile(writer.getLogFile()));

        Files.deleteIfExists(context);
        Files.deleteIfExists(writer.getLogFile());

        SessionTree reader = new SessionTree(context, workspace, fs);
        reader.load();
        assertEquals(1, reader.size());
        assertTrue(Files.isRegularFile(context));
    }
}
