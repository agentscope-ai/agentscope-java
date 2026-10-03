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
package io.agentscope.harness.agent.filesystem.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.filesystem.model.EditResult;
import org.junit.jupiter.api.Test;

class FilesystemUtilsTest {

    // ================================================================
    // stringReplacement
    // ================================================================

    @Test
    void stringReplacement_singleOccurrence() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("Hello World", "World", "Java", false);

        assertTrue(r.isSuccess());
        assertEquals("Hello Java", r.content());
        assertEquals(1, r.occurrences());
    }

    @Test
    void stringReplacement_replaceAll() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("a b a b a", "a", "x", true);

        assertTrue(r.isSuccess());
        assertEquals("x b x b x", r.content());
        assertEquals(3, r.occurrences());
    }

    @Test
    void stringReplacement_notFound() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("hello", "nope", "x", false);

        assertFalse(r.isSuccess());
        assertTrue(r.error().contains("String not found"));
    }

    @Test
    void stringReplacement_multipleWithoutReplaceAll() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("foo bar foo", "foo", "x", false);

        assertFalse(r.isSuccess());
        assertTrue(r.error().contains("2 times"));
    }

    @Test
    void stringReplacement_emptyNewString_deletes() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("Hello World", "World", "", false);

        assertTrue(r.isSuccess());
        assertEquals("Hello ", r.content());
        assertEquals(1, r.occurrences());
    }

    // ================================================================
    // Line-ending normalization — shared by all filesystem implementations
    // ================================================================

    @Test
    void stringReplacement_matchesAcrossCrLf() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("a\r\nb\r\nc", "a\nb", "x", false);

        assertTrue(r.isSuccess(), "a CRLF file must match an LF pattern: " + r.error());
        assertEquals(1, r.occurrences());
    }

    @Test
    void stringReplacement_matchesAcrossLoneCr() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("a\rb\rc", "a\nb", "x", false);

        assertTrue(r.isSuccess());
        assertEquals(1, r.occurrences());
    }

    @Test
    void stringReplacement_normalizesCrlfPatternGivenByCaller() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("a\nb", "a\r\nb", "x", false);

        assertTrue(r.isSuccess(), "a CRLF pattern must match an LF file: " + r.error());
        assertEquals(1, r.occurrences());
    }

    @Test
    void stringReplacement_writesBackLfOnly() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("keep\r\nthis\r\nx\r\n", "x", "y", false);

        assertTrue(r.isSuccess());
        // CRLF is normalized away on a Unix-style filesystem, matching LocalFilesystem's
        // long-standing behavior so all three implementations agree.
        assertEquals("keep\nthis\ny\n", r.content());
        assertFalse(r.content().contains("\r"));
    }

    @Test
    void stringReplacement_replaceAll_countsAcrossMixedLineEndings() {
        // CRLF, LF and a lone CR in one content: all three must count as the same separator,
        // so the caller gets the same occurrence count on every filesystem.
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("a\r\na\ra\na", "a\na", "-", true);

        assertTrue(r.isSuccess());
        // 4 separators-joined 'a' pairs, but occurrences are counted non-overlapping
        assertEquals(2, r.occurrences());
        assertEquals("-\n-", r.content());
    }

    @Test
    void stringReplacement_multipleAcrossCrLf_preservesOccurrenceGuard() {
        // The >1 guard must fire on CRLF content exactly as it does on LF content, otherwise the
        // same edit would be accepted on one backend and rejected on another.
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("a\r\na", "a", "x", false);

        assertFalse(r.isSuccess());
        assertTrue(r.error().contains("2 times"));
    }

    @Test
    void stringReplacement_lfOnlyFileIsUnchangedSemantics() {
        FilesystemUtils.ReplacementResult r =
                FilesystemUtils.stringReplacement("Hello World", "World", "Java", false);

        assertTrue(r.isSuccess());
        assertEquals("Hello Java", r.content());
    }

    // ================================================================
    // Deprecated bridge — the v2.0.1-v2.0.3 Object[] contract
    // ================================================================

    @SuppressWarnings("deprecation")
    @Test
    void performStringReplacement_bridge_returnsTwoElementArrayOnSuccess() {
        Object[] result =
                FilesystemUtils.performStringReplacement("Hello World", "World", "Java", false);

        assertEquals(2, result.length);
        assertEquals("Hello Java", result[0]);
        assertEquals(1, result[1]);
    }

    @SuppressWarnings("deprecation")
    @Test
    void performStringReplacement_bridge_returnsSingleElementArrayOnError() {
        Object[] notFound = FilesystemUtils.performStringReplacement("hello", "nope", "x", false);
        assertEquals(1, notFound.length);
        assertTrue(((String) notFound[0]).contains("String not found"));

        Object[] multiple =
                FilesystemUtils.performStringReplacement("foo bar foo", "foo", "x", false);
        assertEquals(1, multiple.length);
        assertTrue(((String) multiple[0]).contains("2 times"));
    }

    @SuppressWarnings("deprecation")
    @Test
    void performStringReplacement_bridge_agreesWithTypedResult() {
        for (String[] pair :
                new String[][] {
                    {"Hello World", "World"},
                    {"a b a", "a"},
                    {"hello", "nope"},
                    {"foo foo", "foo"}
                }) {
            String content = pair[0];
            String needle = pair[1];
            Object[] legacy = FilesystemUtils.performStringReplacement(content, needle, "x", false);
            FilesystemUtils.ReplacementResult typed =
                    FilesystemUtils.stringReplacement(content, needle, "x", false);

            if (typed.isSuccess()) {
                assertEquals(2, legacy.length, content);
                assertEquals(typed.content(), legacy[0]);
                assertEquals(typed.occurrences(), legacy[1]);
            } else {
                assertEquals(1, legacy.length, content);
                assertEquals(typed.error(), legacy[0]);
            }
        }
    }

    // ================================================================
    // validateEditArguments
    // ================================================================

    @Test
    void validateEditArguments_acceptsDeletionAsEmptyString() {
        assertNull(FilesystemUtils.validateEditArguments("/f.txt", "World", ""));
    }

    @Test
    void validateEditArguments_rejectsNullNewString() {
        EditResult r = FilesystemUtils.validateEditArguments("/f.txt", "World", null);

        assertNotNull(r);
        assertFalse(r.isSuccess());
        assertTrue(r.error().contains("newString must not be null"));
        assertTrue(r.error().contains("empty string to delete"));
    }

    @Test
    void validateEditArguments_rejectsEmptyOrNullOldString() {
        EditResult empty = FilesystemUtils.validateEditArguments("/f.txt", "", "x");
        assertNotNull(empty);
        assertTrue(empty.error().contains("oldString must not be null or empty"));

        EditResult nulled = FilesystemUtils.validateEditArguments("/f.txt", null, "x");
        assertNotNull(nulled);
        assertTrue(nulled.error().contains("oldString must not be null or empty"));
    }
}
