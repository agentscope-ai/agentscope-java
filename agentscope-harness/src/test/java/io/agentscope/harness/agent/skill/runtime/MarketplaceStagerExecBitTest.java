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
package io.agentscope.harness.agent.skill.runtime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Focused unit test for {@link MarketplaceStager#shouldBeExecutable(Path, byte[])} — the
 * heuristic that re-derives an exec bit after the ingestion path lost POSIX mode info.
 *
 * <p>Kept narrow on purpose: doesn't construct a {@code MarketplaceStager}, doesn't touch the
 * filesystem, and only exercises the byte/path predicate. Plain unit test, runs on any
 * platform.
 */
class MarketplaceStagerExecBitTest {

    @Test
    @DisplayName("Shebang at byte 0/1 → executable regardless of suffix")
    void shebangAlwaysTriggers() {
        byte[] bytes = "#!/usr/bin/env python3\nprint(1)\n".getBytes(StandardCharsets.UTF_8);
        // Filename with no script suffix; the shebang alone must carry the decision.
        assertTrue(
                MarketplaceStager.shouldBeExecutable(Paths.get("scripts/no-ext"), bytes),
                "shebang should make non-suffixed file executable");
    }

    @Test
    @DisplayName(".sh / .bash / .zsh / .py / .rb / .pl / .js / .mjs all trigger")
    void knownSuffixesTrigger() {
        byte[] empty = new byte[0];
        for (String suffix :
                new String[] {".sh", ".bash", ".zsh", ".ksh", ".py", ".rb", ".pl", ".js", ".mjs"}) {
            Path p = Paths.get("scripts/foo" + suffix);
            assertTrue(
                    MarketplaceStager.shouldBeExecutable(p, empty),
                    "suffix " + suffix + " should be recognised as a script");
        }
    }

    @Test
    @DisplayName("Suffix matching is case-insensitive (Foo.SH → executable)")
    void suffixCaseInsensitive() {
        assertTrue(
                MarketplaceStager.shouldBeExecutable(Paths.get("Foo.SH"), new byte[0]),
                "uppercase .SH should still trigger");
        assertTrue(
                MarketplaceStager.shouldBeExecutable(Paths.get("Bar.Py"), new byte[0]),
                "mixed case .Py should still trigger");
    }

    @Test
    @DisplayName("Static-asset suffixes (.md, .json, .txt, .yaml) do NOT trigger")
    void staticAssetsStayNonExec() {
        byte[] empty = new byte[0];
        for (String suffix : new String[] {".md", ".json", ".txt", ".yaml", ".yml", ".html"}) {
            Path p = Paths.get("references/foo" + suffix);
            assertFalse(
                    MarketplaceStager.shouldBeExecutable(p, empty),
                    "suffix " + suffix + " should NOT be flagged executable");
        }
    }

    @Test
    @DisplayName("Filename without extension AND without shebang → not executable")
    void noExtensionNoShebang() {
        assertFalse(
                MarketplaceStager.shouldBeExecutable(
                        Paths.get("scripts/data"),
                        "no shebang here\n".getBytes(StandardCharsets.UTF_8)),
                "plain file with no script signal should remain non-exec");
    }

    @Test
    @DisplayName("Short byte payload (<2 bytes) does NOT crash the shebang check")
    void shortPayloadTolerated() {
        assertFalse(
                MarketplaceStager.shouldBeExecutable(Paths.get("README"), new byte[] {'#'}),
                "single '#' is not a shebang");
        assertFalse(
                MarketplaceStager.shouldBeExecutable(Paths.get("README"), new byte[0]),
                "empty byte[] must be safe");
        assertFalse(
                MarketplaceStager.shouldBeExecutable(Paths.get("README"), null),
                "null byte[] must be safe (no NPE)");
    }

    @Test
    @DisplayName("Shebang must be at byte 0/1 — leading whitespace defeats it")
    void shebangMustBeAtStart() {
        byte[] withLeadingSpace = " #!/bin/bash\necho hi\n".getBytes(StandardCharsets.UTF_8);
        assertFalse(
                MarketplaceStager.shouldBeExecutable(
                        Paths.get("scripts/leading-space"), withLeadingSpace),
                "shebang only counts at byte 0");
    }
}
