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
import io.agentscope.harness.agent.filesystem.model.GrepMatch;
import io.agentscope.harness.agent.filesystem.model.GrepResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression for #2448: upper-layer files must fully shadow lower-layer grep matches for the
 * same path, even when the upper file no longer contains the pattern.
 */
class OverlayFilesystemGrepTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    @Test
    void grep_excludesLowerMatches_whenUpperFileExistsButNoLongerMatches(
            @TempDir Path lowerRoot, @TempDir Path upperRoot) throws Exception {
        Files.writeString(lowerRoot.resolve("notes.md"), "alpha SECRET beta\n");
        Files.writeString(upperRoot.resolve("notes.md"), "alpha beta\n");
        Files.writeString(lowerRoot.resolve("other.md"), "still SECRET here\n");

        OverlayFilesystem overlay =
                new OverlayFilesystem(sandboxed(upperRoot), sandboxed(lowerRoot));

        GrepResult result = overlay.grep(RT, "SECRET", "/", null);

        assertTrue(result.isSuccess());
        List<String> paths =
                result.matches().stream().map(GrepMatch::path).collect(Collectors.toList());
        assertEquals(List.of("/other.md"), paths);
    }

    @Test
    void grep_upperMatchOverridesLowerMatchOnSamePathAndLine(
            @TempDir Path lowerRoot, @TempDir Path upperRoot) throws Exception {
        Files.writeString(lowerRoot.resolve("notes.md"), "old SECRET text");
        Files.writeString(upperRoot.resolve("notes.md"), "new SECRET text");

        OverlayFilesystem overlay =
                new OverlayFilesystem(sandboxed(upperRoot), sandboxed(lowerRoot));

        GrepResult result = overlay.grep(RT, "SECRET", "/", null);

        assertTrue(result.isSuccess());
        assertEquals(1, result.matches().size());
        GrepMatch match = result.matches().get(0);
        assertEquals("/notes.md", match.path());
        assertEquals(1, match.line());
        assertTrue(match.text().contains("new SECRET text"));
        assertTrue(!match.text().contains("old SECRET text"));
    }

    private static LocalFilesystem sandboxed(Path root) {
        return new LocalFilesystem(root, true, 10);
    }
}
