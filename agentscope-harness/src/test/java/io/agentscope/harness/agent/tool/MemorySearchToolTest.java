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
package io.agentscope.harness.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Unit tests for {@link MemorySearchTool} result bounding (issue #3266). */
class MemorySearchToolTest {

    private static final RuntimeContext RT = RuntimeContext.empty();

    @TempDir Path workspace;

    private WorkspaceManager workspaceManager;
    private MemorySearchTool tool;

    @BeforeEach
    void setUp() {
        workspaceManager = new WorkspaceManager(workspace);
        tool = new MemorySearchTool(workspaceManager);
    }

    @AfterEach
    void tearDown() {
        workspaceManager.close();
    }

    private void writeMemoryFile(String relativePath, String content) throws Exception {
        Path file = workspace.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    @Test
    void capsResultsAtDefaultLimit() throws Exception {
        // 40 matching lines across daily ledgers — more than the default cap of 30.
        StringBuilder ledger = new StringBuilder();
        for (int i = 1; i <= 40; i++) {
            ledger.append("fact no ").append(i).append(" about keyword\n");
        }
        writeMemoryFile("memory/2026-09-01.md", ledger.toString());

        String result = tool.memorySearch(RT, "keyword", null, null);

        assertTrue(result.startsWith("Found 30+ matches"), () -> "should cap at 30: " + result);
        assertFalse(result.contains("fact no 31"), "lines beyond the cap must not be returned");
    }

    @Test
    void capsResultsAtDefaultLimit_detectsExtraMatchOnFinalLine() throws Exception {
        // 31 matching lines where the 31st is the very last line of the file. The old cap
        // check peeked only at the *next* line (here the empty trailing element) and silently
        // dropped the 31st match with no "+" marker.
        StringBuilder ledger = new StringBuilder();
        for (int i = 1; i <= 31; i++) {
            ledger.append("fact no ").append(i).append(" about keyword\n");
        }
        writeMemoryFile("memory/2026-09-01.md", ledger.toString());

        String result = tool.memorySearch(RT, "keyword", null, null);

        assertTrue(
                result.startsWith("Found 30+ matches"),
                () -> "a 31st match on the final line must be flagged: " + result);
        assertFalse(result.contains("fact no 31"), "lines beyond the cap must not be returned");
    }

    @Test
    void capsResultsAtDefaultLimit_detectsExtraMatchInLaterFile() throws Exception {
        // The cap is reached in the first file; a match in a second file must still be seen
        // so the "+" marker stays accurate (this replaces the old next-file probe, which
        // only ever looked one file ahead).
        StringBuilder first = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            first.append("fact no ").append(i).append(" about keyword\n");
        }
        writeMemoryFile("memory/2026-09-01.md", first.toString());
        writeMemoryFile("memory/2026-09-02.md", "fact no 31 about keyword\n");

        String result = tool.memorySearch(RT, "keyword", null, null);

        assertTrue(
                result.startsWith("Found 30+ matches"),
                () -> "a match in a later file must be flagged: " + result);
        assertFalse(result.contains("fact no 31"), "lines beyond the cap must not be returned");
    }

    @Test
    void maxResultsParameterOverridesDefault() throws Exception {
        StringBuilder ledger = new StringBuilder();
        for (int i = 1; i <= 20; i++) {
            ledger.append("fact no ").append(i).append(" about keyword\n");
        }
        writeMemoryFile("memory/2026-09-01.md", ledger.toString());

        String result = tool.memorySearch(RT, "keyword", null, 5);

        assertTrue(result.startsWith("Found 5+ matches"), () -> result);
        assertFalse(result.contains("fact no 6"), "lines beyond maxResults must not be returned");
    }

    @Test
    void maxResultsIgnoredWhenInvalid() throws Exception {
        StringBuilder ledger = new StringBuilder();
        for (int i = 1; i <= 3; i++) {
            ledger.append("fact no ").append(i).append(" about keyword\n");
        }
        writeMemoryFile("memory/2026-09-01.md", ledger.toString());

        assertEquals("Found 3 matches", tool.memorySearch(RT, "keyword", null, 0).substring(0, 15));
        assertEquals(
                "Found 3 matches", tool.memorySearch(RT, "keyword", null, -1).substring(0, 15));
        assertEquals(
                "Found 3 matches", tool.memorySearch(RT, "keyword", null, null).substring(0, 15));
    }

    @Test
    void maxResultsClampedToHardCeiling() throws Exception {
        // maxResults=100000 is clamped to MAX_RESULTS_CEILING (100).
        StringBuilder ledger = new StringBuilder();
        for (int i = 1; i <= 110; i++) {
            ledger.append("fact no ").append(i).append(" about keyword\n");
        }
        writeMemoryFile("memory/2026-09-01.md", ledger.toString());

        String result = tool.memorySearch(RT, "keyword", null, 100000);

        assertTrue(result.startsWith("Found 100+ matches"), () -> "should clamp at 100: " + result);
        assertFalse(result.contains("fact no 101"), "line 101 must not be returned");
    }

    @Test
    void notesThatResultsWereTruncated() throws Exception {
        StringBuilder ledger = new StringBuilder();
        for (int i = 1; i <= 35; i++) {
            ledger.append("fact no ").append(i).append(" about keyword\n");
        }
        writeMemoryFile("memory/2026-09-01.md", ledger.toString());

        String result = tool.memorySearch(RT, "keyword", null, null);

        assertTrue(
                result.toLowerCase().contains("truncat"),
                () -> "truncation note expected when results are capped: " + result);
    }

    @Test
    void truncatesLongMatchedLines() throws Exception {
        String longLine = "keyword " + "x".repeat(5000);
        writeMemoryFile("memory/2026-09-01.md", longLine + "\n");

        String result = tool.memorySearch(RT, "keyword", null, null);

        int longest = 0;
        for (String line : result.split("\n", -1)) {
            longest = Math.max(longest, line.length());
        }
        // Source prefix + 5000-char line would be ~5050; require well under that.
        final int observed = longest;
        assertTrue(
                observed < 1000, () -> "long lines must be truncated, got " + observed + " chars");
        assertTrue(
                result.toLowerCase().contains("truncat"),
                "truncated line should carry a note: " + result);
    }

    @Test
    void truncateLineDoesNotSplitSurrogatePairs() throws Exception {
        // CJK Extension B character U+2000B (𠀋) — high surrogate \uD840, low \uDC0B.
        // If the cut falls between them, the result would be a lone high surrogate
        // followed by a low surrogate that forms a broken pair.
        String prefix = "keyword ";
        StringBuilder sb = new StringBuilder(prefix);
        int filler = MemorySearchTool.MAX_LINE_CHARS - prefix.length();
        sb.append("a".repeat(filler));
        sb.append("\uD840\uDC0B"); // CJK Ext B char straddling the cut point
        String line = sb.toString();
        writeMemoryFile("memory/2026-09-01.md", line + "\n");

        String result = tool.memorySearch(RT, "keyword", null, null);

        assertTrue(result.contains("[line truncated"), () -> "should truncate: " + result);
        // The truncated output must NOT end with a lone high surrogate.
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(
                        i + 1 < result.length() && Character.isLowSurrogate(result.charAt(i + 1)),
                        "lone high surrogate at offset " + i + " in: " + result);
            }
        }
    }

    @Test
    void noTruncationNoteWhenCapReachedAtExactEnd() throws Exception {
        // 30 exact matches — the cap is hit but there is no 31st match.
        StringBuilder ledger = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            ledger.append("fact no ").append(i).append(" about keyword\n");
        }
        ledger.append("a non-matching line\n");
        writeMemoryFile("memory/2026-09-01.md", ledger.toString());

        String result = tool.memorySearch(RT, "keyword", null, null);

        assertTrue(result.startsWith("Found 30 matches"), () -> result);
        assertFalse(
                result.toLowerCase().contains("truncat"),
                "no truncation note when cap reached at exact end: " + result);
    }

    @Test
    void noTruncationNoteWhenUnderLimit() throws Exception {
        writeMemoryFile("memory/2026-09-01.md", "short fact about keyword\n");

        String result = tool.memorySearch(RT, "keyword", null, null);

        assertEquals(
                "Found 1 matches:\n\nSource: memory/2026-09-01.md#1: short fact about keyword",
                result);
    }

    @Test
    void blankQueryStillRejected() {
        assertEquals("No query provided", tool.memorySearch(RT, "  ", null, null));
        assertEquals("No query provided", tool.memorySearch(RT, null, null, 5));
    }

    @Test
    void noMatchStillReportsQuery() {
        String result = tool.memorySearch(RT, "nothing-matches-this", null, null);
        assertEquals("No matching memories found for: nothing-matches-this", result);
    }
}
