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
package io.agentscope.harness.agent.filesystem.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;

class LocalFilesystemSingleFileGrepTest {

    @TempDir Path root;

    @ParameterizedTest
    @ValueSource(strings = {"target.txt", "nested/target.txt", "with space/目标.txt"})
    void fileSearchExcludesSiblingAndNestedFiles(String path) throws Exception {
        Path target = root.resolve(path);
        Files.createDirectories(target.getParent().resolve("child"));
        Files.writeString(target, "start\nneedle target\n");
        Files.writeString(target.resolveSibling("sibling.txt"), "needle sibling\n");
        Files.writeString(target.getParent().resolve("child/other.txt"), "needle nested\n");

        GrepResult result = grepWithoutRipgrep("/" + path, null);

        assertTrue(result.isSuccess(), result.error());
        assertEquals(List.of(new GrepMatch("/" + path, 2, "needle target")), result.matches());
    }

    @Test
    void fileWithoutMatchesDoesNotReturnSiblingMatches() throws Exception {
        Files.writeString(root.resolve("target.txt"), "no match\n");
        Files.writeString(root.resolve("sibling.txt"), "needle sibling\n");

        assertEquals(List.of(), grepWithoutRipgrep("/target.txt", null).matches());
    }

    @Test
    void directorySearchStillRecurses() throws Exception {
        Files.createDirectories(root.resolve("search/child"));
        Files.writeString(root.resolve("search/top.txt"), "needle top\n");
        Files.writeString(root.resolve("search/child/nested.txt"), "needle nested\n");
        Files.writeString(root.resolve("outside.txt"), "needle outside\n");

        GrepResult result = grepWithoutRipgrep("/search", null);

        assertEquals(
                List.of("/search/child/nested.txt", "/search/top.txt"),
                result.matches().stream().map(GrepMatch::path).sorted().toList());
    }

    @Test
    void directorySearchStillHonorsGlobFilter() throws Exception {
        Files.writeString(root.resolve("included.txt"), "needle included\n");
        Files.writeString(root.resolve("excluded.md"), "needle excluded\n");

        assertEquals(
                List.of(new GrepMatch("/included.txt", 1, "needle included")),
                grepWithoutRipgrep("/", "*.txt").matches());
    }

    private GrepResult grepWithoutRipgrep(String path, String glob) {
        try (MockedConstruction<ProcessBuilder> ignored =
                mockConstruction(
                        ProcessBuilder.class,
                        (builder, context) ->
                                when(builder.start())
                                        .thenThrow(new IOException("rg unavailable")))) {
            return new LocalFilesystem(root, true, 10)
                    .grep(RuntimeContext.empty(), "needle", path, glob);
        }
    }
}
