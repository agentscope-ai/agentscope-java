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
package io.agentscope.harness.agent.filesystem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.local.LocalFilesystem;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies glob behavior for local and remote filesystem implementations. */
class FilesystemGlobTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    @Test
    void local_glob_plainPattern_matchesFilesInSearchRootAndSubdirectories(@TempDir Path tmp)
            throws Exception {
        Path memoryDir = Files.createDirectories(tmp.resolve("memory"));
        Files.writeString(memoryDir.resolve("2026-05-13.md"), "daily note");
        Files.createDirectories(memoryDir.resolve("sub"));
        Files.writeString(memoryDir.resolve("sub").resolve("2026-05-14.md"), "next note");

        LocalFilesystem fs = new LocalFilesystem(tmp);
        GlobResult result = fs.glob(RT, "*.md", "memory");

        assertTrue(result.isSuccess());
        Set<String> relPaths =
                result.matches().stream()
                        .map(fi -> tmp.relativize(Path.of(fi.path())).toString().replace('\\', '/'))
                        .collect(Collectors.toSet());

        assertEquals(Set.of("memory/2026-05-13.md", "memory/sub/2026-05-14.md"), relPaths);
    }

    @Test
    void remote_glob_plainPattern_matchesFilesInSearchRootAndSubdirectories() {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("test-ns");
        store.put(ns, "/agents/demo-session.log.jsonl", Map.of("content", "root log"));
        store.put(ns, "/agents/sub/demo-session.log.jsonl", Map.of("content", "sub log"));

        RemoteFilesystem fs = new RemoteFilesystem(store, ns);
        GlobResult result = fs.glob(RT, "*.log.jsonl", "agents");

        assertTrue(result.isSuccess());
        Set<String> paths =
                result.matches().stream()
                        .map(fi -> fi.path().replace('\\', '/'))
                        .collect(Collectors.toSet());
        assertEquals(
                Set.of("/agents/demo-session.log.jsonl", "/agents/sub/demo-session.log.jsonl"),
                paths);
    }
}
