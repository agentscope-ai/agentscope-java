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
package io.agentscope.harness.agent.workspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Prefix lookups must match the prefix literally. Agent, session and file names are interpolated
 * into a SQL {@code LIKE} pattern, so an {@code '_'} or {@code '%'} in any of them must not act as
 * a wildcard: {@code agents/bot_1/sessions/s_1/} must never pull in {@code agents/bot21/...}.
 */
class WorkspaceIndexPrefixMatchTest {

    @TempDir Path workspaceRoot;

    private WorkspaceIndex index;

    @BeforeEach
    void openIndex() {
        index = WorkspaceIndex.open(workspaceRoot);
        assertNotNull(index, "WorkspaceIndex should open under a temp workspace root");
    }

    @AfterEach
    void closeIndex() {
        if (index != null) {
            index.close();
        }
    }

    @Test
    void listByPrefixTreatsUnderscoreInAgentAndSessionNamesLiterally() {
        index.upsert("agents/bot_1/sessions/s_1/report.md", 7, null);
        // Same shape, different characters: 'bot21'/'s21' line up with 'bot_1'/'s_1' once
        // '_' is read as the single-character LIKE wildcard.
        index.upsert("agents/bot21/sessions/s21/report.md", 9, null);

        List<String> hits = index.listByPrefix("agents/bot_1/sessions/s_1/");

        assertEquals(
                List.of("agents/bot_1/sessions/s_1/report.md"),
                hits,
                "another agent's session file must not appear under this prefix");
    }

    @Test
    void hasPrefixDoesNotMatchOnUnderscore() {
        index.upsert("agents/bot21/sessions/s21/report.md", 9, null);

        assertFalse(
                index.hasPrefix("agents/bot_1/sessions/s_1/"),
                "a prefix with no entries must not report hits from a look-alike directory");
    }

    @Test
    void prefixMatchingIsCaseSensitive() {
        index.upsert("agents/Bot_1/sessions/s_1/report.md", 7, null);

        assertTrue(index.hasPrefix("agents/Bot_1/sessions/s_1/"));
        assertFalse(index.hasPrefix("agents/bot_1/sessions/s_1/"));
        assertEquals(List.of(), index.listByPrefix("agents/bot_1/sessions/s_1/"));
    }

    @Test
    void listByPrefixTreatsPercentLiterally() {
        index.upsert("agents/main/sessions/sess%2F1/notes.md", 3, null);
        // 'sess2F1' is what 'sess%2F1' collapses to when '%' matches the empty string.
        index.upsert("agents/main/sessions/sess2F1/notes.md", 4, null);

        List<String> hits = index.listByPrefix("agents/main/sessions/sess%2F1/");

        assertEquals(List.of("agents/main/sessions/sess%2F1/notes.md"), hits);
    }

    @Test
    void listByPrefixStillMatchesOrdinaryPrefix() {
        index.upsert("memory/MEMORY.md", 11, null);
        index.upsert("memory/daily/2026-10-31.md", 12, null);

        List<String> hits = index.listByPrefix("memory/");

        assertEquals(
                List.of("memory/MEMORY.md", "memory/daily/2026-10-31.md"),
                hits.stream().sorted().toList());
        assertTrue(index.hasPrefix("memory/"));
    }
}
