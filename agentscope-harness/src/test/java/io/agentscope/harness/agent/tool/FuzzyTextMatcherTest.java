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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.harness.agent.tool.FuzzyTextMatcher.Level;
import io.agentscope.harness.agent.tool.FuzzyTextMatcher.MatchRange;
import io.agentscope.harness.agent.tool.FuzzyTextMatcher.SearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Behavioural coverage for the fuzziness ladder used by {@code SkillManageTool#patch}. Each
 * test exercises one rung of the ladder, plus the cross-rung short-circuiting (stricter wins
 * when any match found) and original-string offset recovery (the patch must apply to the
 * original bytes, not the normalised view).
 */
class FuzzyTextMatcherTest {

    @Test
    @DisplayName("Empty/null needles return an empty result without crashing")
    void emptyNeedle() {
        assertTrue(FuzzyTextMatcher.search("anything", "").isEmpty());
        assertTrue(FuzzyTextMatcher.search("anything", null).isEmpty());
        assertTrue(FuzzyTextMatcher.search(null, "x").isEmpty());
    }

    @Test
    @DisplayName("Exact match returns EXACT level with byte-precise offsets")
    void exactMatch() {
        String existing = "alpha beta gamma";
        SearchResult r = FuzzyTextMatcher.search(existing, "beta");
        assertEquals(Level.EXACT, r.level());
        assertEquals(1, r.matches().size());
        MatchRange m = r.matches().get(0);
        assertEquals(6, m.start());
        assertEquals(10, m.end());
        assertEquals("beta", existing.substring(m.start(), m.end()));
    }

    @Test
    @DisplayName("Exact match finds all occurrences in order")
    void exactMultiple() {
        String existing = "foo bar foo baz foo";
        SearchResult r = FuzzyTextMatcher.search(existing, "foo");
        assertEquals(Level.EXACT, r.level());
        assertEquals(3, r.matches().size());
        assertEquals(0, r.matches().get(0).start());
        assertEquals(8, r.matches().get(1).start());
        assertEquals(16, r.matches().get(2).start());
    }

    @Test
    @DisplayName("Trailing-whitespace ladder matches when needle missed trailing spaces")
    void trailingWhitespaceStripped() {
        // 'existing' has trailing spaces on the second line; needle does not.
        String existing = "line one   \nline two\nline three\n";
        String needle = "line one\nline two\nline three";
        SearchResult r = FuzzyTextMatcher.search(existing, needle);
        assertEquals(Level.TRAILING_WS_STRIPPED, r.level());
        assertEquals(1, r.matches().size());
        // Original offsets should span from start of "line one" to the end of "line three".
        MatchRange m = r.matches().get(0);
        assertEquals(0, m.start());
        // End of "line three" — character before final newline is at index 31.
        // The trailing newline is included only if the needle included one; ours did not.
        assertTrue(existing.substring(m.start(), m.end()).contains("line three"));
    }

    @Test
    @DisplayName("Whitespace-collapsed ladder catches indentation drift")
    void whitespaceCollapsed() {
        // 'existing' uses 4-space indent; needle uses tab indent. Trailing-ws strip alone
        // would NOT match (leading whitespace still differs). Collapse handles it.
        String existing = "if cond:\n    return 1\n    return 2\n";
        String needle = "if cond:\n\treturn 1\n\treturn 2";
        SearchResult r = FuzzyTextMatcher.search(existing, needle);
        assertEquals(Level.WHITESPACE_COLLAPSED, r.level());
        assertEquals(1, r.matches().size());
        // Match must START at the beginning of 'if' in the original (no leading whitespace
        // bytes outside the matched range).
        MatchRange m = r.matches().get(0);
        assertEquals(0, m.start());
        // The substring picked out of original should still be meaningful and include
        // 'return 2'.
        assertTrue(existing.substring(m.start(), m.end()).contains("return 2"));
    }

    @Test
    @DisplayName("Strict level wins even if looser levels would also match")
    void strictWins() {
        // 'existing' has the needle in two distinct shapes:
        //   - one exact occurrence  ("hello world")
        //   - one whitespace-drifted occurrence ("hello  world" — double space)
        // Exact match should find ONLY the first occurrence and short-circuit.
        String existing = "hello world\n---\nhello  world";
        String needle = "hello world";
        SearchResult r = FuzzyTextMatcher.search(existing, needle);
        assertEquals(Level.EXACT, r.level());
        assertEquals(1, r.matches().size());
        assertEquals(0, r.matches().get(0).start());
    }

    @Test
    @DisplayName("Returns empty when no level matches")
    void noMatch() {
        SearchResult r = FuzzyTextMatcher.search("foo bar baz", "absent");
        assertTrue(r.isEmpty());
    }

    @Test
    @DisplayName(
            "Range mapping correctness — replacing in original substring yields expected patch")
    void rangeMapsToOriginal() {
        String existing = "  prefix\n    body  \n    tail\n";
        String needle = "body";
        SearchResult r = FuzzyTextMatcher.search(existing, needle);
        // EXACT match here.
        MatchRange m = r.matches().get(0);
        String patched = existing.substring(0, m.start()) + "BODY" + existing.substring(m.end());
        assertEquals("  prefix\n    BODY  \n    tail\n", patched);
    }

    @Test
    @DisplayName("Trailing-ws match preserves the original trailing whitespace in surrounding text")
    void trailingWsPreservesSurroundings() {
        // The needle DOES NOT include the trailing spaces, but the original DOES. After patching,
        // those trailing spaces must remain — we only replace what the LLM asked to replace.
        String existing = "header  \nold value  \nfooter\n";
        String needle = "old value";
        SearchResult r = FuzzyTextMatcher.search(existing, needle);
        assertEquals(Level.EXACT, r.level());
        MatchRange m = r.matches().get(0);
        String patched =
                existing.substring(0, m.start()) + "new value" + existing.substring(m.end());
        // 'header  ' (with trailing spaces) and 'old value  ' → 'new value  ' should survive.
        assertEquals("header  \nnew value  \nfooter\n", patched);
    }

    @Test
    @DisplayName("Whitespace-collapsed match in middle of buffer preserves prefix/suffix bytes")
    void wsCollapsedMidBufferPreservesBytes() {
        // Insert a fuzzy-matching block sandwiched between byte-precise prefix/suffix that
        // include their own whitespace. After patching only the fuzzy block, prefix/suffix
        // must come back byte-identical.
        String existing = "PREFIX_KEEP\n\n  if x:\n    foo\nSUFFIX_KEEP";
        String needle = "if x:\n\tfoo";
        SearchResult r = FuzzyTextMatcher.search(existing, needle);
        assertNotEquals(Level.EXACT, r.level());
        assertEquals(1, r.matches().size());
        MatchRange m = r.matches().get(0);
        String patched =
                existing.substring(0, m.start()) + "REPLACED" + existing.substring(m.end());
        assertTrue(patched.startsWith("PREFIX_KEEP\n\n"), "prefix bytes must survive verbatim");
        assertTrue(patched.endsWith("SUFFIX_KEEP"), "suffix bytes must survive verbatim");
        assertTrue(patched.contains("REPLACED"));
    }

    // ---------------------------------------------------------------------
    //  CRLF coverage. A skill file authored on Windows ends every line with CRLF, while the
    //  LLM almost always hands back LF. Before the fix, '\r' was carried through both
    //  normalisers as ordinary content, so a CRLF 'existing' matched an LF needle at NO rung
    //  of the ladder — including the most lenient one, whose whole purpose is to absorb
    //  exactly this kind of whitespace difference.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("CRLF file matches an LF needle at the trailing-whitespace level")
    void crlfExistingMatchesLfNeedle() {
        String existing = "alpha   \r\nbeta\r\ngamma\r\n";
        String needle = "alpha\nbeta\ngamma";
        SearchResult r = FuzzyTextMatcher.search(existing, needle);
        assertEquals(Level.TRAILING_WS_STRIPPED, r.level());
        assertEquals(1, r.matches().size());
    }

    @Test
    @DisplayName("CRLF match range maps back without eating the line terminator")
    void crlfMatchRangePreservesTerminators() {
        String existing = "PREFIX_KEEP\r\nalpha   \r\nbeta\r\nSUFFIX_KEEP\r\n";
        String needle = "alpha\nbeta";
        SearchResult r = FuzzyTextMatcher.search(existing, needle);
        assertEquals(Level.TRAILING_WS_STRIPPED, r.level());
        assertEquals(1, r.matches().size());
        MatchRange m = r.matches().get(0);
        // The range covers exactly the needle's bytes in the original — CRLF included — and
        // stops before the '\r' that terminates the last matched line. Mapping the emitted
        // newline back to the '\n' instead would swallow that '\r' and silently rewrite the
        // file's line ending at the patch boundary.
        assertEquals("alpha   \r\nbeta", existing.substring(m.start(), m.end()));
        String patched = existing.substring(0, m.start()) + "ALPHA" + existing.substring(m.end());
        assertEquals("PREFIX_KEEP\r\nALPHA\r\nSUFFIX_KEEP\r\n", patched);
    }

    @Test
    @DisplayName("CRLF file still reaches the collapsed level when indentation drifts")
    void crlfCollapseLevelHandlesIndentDrift() {
        String existing = "if cond:\r\n    return 1\r\n    return 2\r\n";
        String needle = "if cond:\n\treturn 1\n\treturn 2";
        SearchResult r = FuzzyTextMatcher.search(existing, needle);
        assertEquals(Level.WHITESPACE_COLLAPSED, r.level());
        assertEquals(1, r.matches().size());
    }

    @Test
    @DisplayName("A CRLF document behaves exactly like its LF twin")
    void crlfBehavesLikeLfTwin() {
        String lf = "header\n\nalpha   \n   beta\nfooter\n";
        String crlf = lf.replace("\n", "\r\n");
        for (String needle : new String[] {"alpha\nbeta", "header\nalpha\nbeta\nfooter"}) {
            SearchResult onLf = FuzzyTextMatcher.search(lf, needle);
            SearchResult onCrlf = FuzzyTextMatcher.search(crlf, needle);
            assertEquals(onLf.isEmpty(), onCrlf.isEmpty(), "emptiness differs for: " + needle);
            assertEquals(onLf.level(), onCrlf.level(), "level differs for: " + needle);
            assertEquals(
                    onLf.matches().size(),
                    onCrlf.matches().size(),
                    "match count differs for: " + needle);
            for (int i = 0; i < onLf.matches().size(); i++) {
                MatchRange lfRange = onLf.matches().get(i);
                MatchRange crlfRange = onCrlf.matches().get(i);
                assertEquals(
                        lf.substring(lfRange.start(), lfRange.end()),
                        crlf.substring(crlfRange.start(), crlfRange.end()).replace("\r", ""),
                        "mapped range differs for: " + needle);
            }
        }
    }

    @Test
    @DisplayName("A newline-leading needle does not begin inside a CRLF terminator")
    void crlfLeadingNewlineKeepsTerminatorOutsideSpan() {
        String existing = "alpha\r\nbeta   \r\ngamma\r\n";
        String needle = "\nbeta\ngamma";
        SearchResult r = FuzzyTextMatcher.search(existing, needle);
        assertEquals(Level.TRAILING_WS_STRIPPED, r.level());
        assertEquals(1, r.matches().size());
        MatchRange m = r.matches().get(0);
        // The span starts on the '\n' of the first terminator, so the '\r' in front of it stays
        // outside. Resolving the start to the '\r' instead would let the replacement's LF stand
        // in for a CRLF, silently downgrading that line's terminator.
        assertEquals("\nbeta   \r\ngamma", existing.substring(m.start(), m.end()));
        assertEquals('\r', existing.charAt(m.start() - 1));
        // Patching therefore leaves the document fully CRLF rather than mixing styles.
        String patched = existing.substring(0, m.start()) + "\nBETA" + existing.substring(m.end());
        assertEquals("alpha\r\nBETA\r\n", patched);
    }
}
