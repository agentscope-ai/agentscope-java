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
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.remote.RemoteFilesystem;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Direct coverage for the root-spelling contract on {@link RemoteFilesystem}: {@code null},
 * blank, {@code "/"} and {@code "."} must all list the store root, not a literal
 * {@code "./"} / whitespace prefix (#3253 review).
 */
class RemoteFilesystemRootSpellingTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    @Test
    void allRootSpellingsListTheStoreRoot() {
        InMemoryStore store = new InMemoryStore();
        List<String> ns = List.of("test-ns");
        store.put(ns, "/root-file.md", Map.of("content", "root"));
        store.put(ns, "/uploads/scan.md", Map.of("content", "nested"));

        RemoteFilesystem fs = new RemoteFilesystem(store, ns);

        List<String> expected = List.of("root-file.md", "uploads");
        for (String root : new String[] {"/", ".", null, "", "   "}) {
            LsResult ls = fs.ls(RT, root);
            assertTrue(ls.isSuccess(), () -> "ls('" + root + "') failed: " + ls.error());
            assertEquals(
                    expected,
                    ls.entries().stream()
                            .map(f -> f.path().replaceFirst("^/", ""))
                            .map(p -> p.endsWith("/") ? p.substring(0, p.length() - 1) : p)
                            .sorted()
                            .collect(Collectors.toList()),
                    "ls('" + root + "') must list the store root");
        }
    }
}
