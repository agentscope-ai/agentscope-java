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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Fuzziness ladder for {@code SkillManageTool#patch}. The LLM rarely reproduces whitespace
 * exactly — indentation drift, trailing-space normalisation in editors, CRLF line endings from
 * a Windows-authored file, and the parser's frontmatter-whitespace eat (see
 * {@code MarkdownSkillParser.FRONTMATTER_PATTERN}) all create mismatches that a strict
 * {@code String.indexOf} would reject.
 *
 * <p>The matcher tries progressively looser comparisons and reports back which level made the
 * match so the caller can surface that to the LLM. Each looser level maintains a per-character
 * map back to the original {@code existing} string so the patch result is applied to the
 * unmodified bytes — we never touch whitespace the LLM didn't ask to change.
 *
 * <ul>
 *   <li>{@link Level#EXACT} — strict {@code String.indexOf}, preserved for byte-for-byte fidelity
 *   <li>{@link Level#TRAILING_WS_STRIPPED} — same after stripping {@code ' '/'\t'} at the end of
 *       every line on both sides, and after treating a CRLF terminator's {@code '\r'} as part of
 *       the line break rather than line content — a Windows-authored file otherwise matches no
 *       LF-only needle at any level. Catches editor-side trailing-whitespace normalisation.
 *   <li>{@link Level#WHITESPACE_COLLAPSED} — same after also stripping leading whitespace per
 *       line and collapsing every internal whitespace run to a single {@code ' '}. Catches
 *       indentation drift (tabs ↔ spaces, 2-space ↔ 4-space) and re-wrapped lines.
 * </ul>
 *
 * <p>CRLF terminators are folded to {@code '\n'} so a Windows-authored file matches an LF-only
 * needle. An emitted {@code '\n'} maps back to the {@code '\r'} that starts a CRLF terminator,
 * and both the leading and the trailing boundary resolve through that same map entry, so at the
 * normalising levels a span edge always lands on a terminator boundary and never splits one.
 * {@link Level#EXACT} carries no such guarantee: it is a raw {@code indexOf}, so a needle that
 * begins with a newline can open on the {@code '\n'} of a CRLF terminator and leave its
 * {@code '\r'} behind, exactly as it does before this change. Either way only the span itself is
 * ever replaced, so nothing outside it is rewritten.
 *
 * <p>Because the replacement is spliced in verbatim, a needle that opens on a line break hands
 * that terminator to the caller's own text — a replacement supplying {@code '\n'} leaves it as
 * {@code '\n'}, so one patch over a CRLF document can leave both styles present. That is the
 * caller's text taking effect rather than whitespace this class rewrote.
 *
 * <p>A lone {@code '\r'} — classic Mac line endings, or a file where only some lines end that way
 * — is still treated as ordinary content and matches nothing; that is out of scope here, but
 * stated because the rest of this class advertises newline tolerance.
 */
final class FuzzyTextMatcher {

    /** Match strictness level. Ordered most-strict-first so callers can compare with {@code <}. */
    enum Level {
        EXACT,
        TRAILING_WS_STRIPPED,
        WHITESPACE_COLLAPSED
    }

    /** A match in {@code existing}: original-string byte offsets, inclusive-exclusive. */
    record MatchRange(int start, int end, Level level) {
        int length() {
            return end - start;
        }
    }

    /** Outcome bundle for the caller. */
    record SearchResult(List<MatchRange> matches, Level level) {
        boolean isEmpty() {
            return matches.isEmpty();
        }
    }

    private FuzzyTextMatcher() {}

    /**
     * Searches {@code existing} for {@code needle} starting at the strictest level and walking
     * looser ladders until at least one match is found.
     *
     * @return {@link SearchResult} with every match found at the first non-empty level (so the
     *     caller can apply uniqueness checks against same-level peers); {@code matches} is empty
     *     when nothing matches at any level.
     */
    static SearchResult search(String existing, String needle) {
        if (existing == null || needle == null || needle.isEmpty()) {
            return new SearchResult(Collections.emptyList(), Level.EXACT);
        }

        // Level 1: exact. Cheap; common case.
        List<MatchRange> exact = findAll(existing, needle, Level.EXACT);
        if (!exact.isEmpty()) {
            return new SearchResult(exact, Level.EXACT);
        }

        // Level 2: trailing-ws stripped per line on both sides.
        Normalized existingTws = stripTrailingWhitespace(existing);
        Normalized needleTws = stripTrailingWhitespace(needle);
        List<MatchRange> tws =
                findAllNormalized(existingTws, needleTws, Level.TRAILING_WS_STRIPPED);
        if (!tws.isEmpty()) {
            return new SearchResult(tws, Level.TRAILING_WS_STRIPPED);
        }

        // Level 3: full whitespace collapse (strip leading + trailing per line, collapse internal
        // runs to single space). Most lenient — surfaced as a warning to the LLM.
        Normalized existingWsc = collapseWhitespace(existing);
        Normalized needleWsc = collapseWhitespace(needle);
        List<MatchRange> wsc =
                findAllNormalized(existingWsc, needleWsc, Level.WHITESPACE_COLLAPSED);
        if (!wsc.isEmpty()) {
            return new SearchResult(wsc, Level.WHITESPACE_COLLAPSED);
        }

        return new SearchResult(Collections.emptyList(), Level.EXACT);
    }

    // ---------------------------------------------------------------------
    //  Exact-mode helpers
    // ---------------------------------------------------------------------

    private static List<MatchRange> findAll(String haystack, String needle, Level level) {
        List<MatchRange> out = new ArrayList<>();
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            out.add(new MatchRange(idx, idx + needle.length(), level));
            idx += needle.length();
        }
        return out;
    }

    // ---------------------------------------------------------------------
    //  Normalised search
    // ---------------------------------------------------------------------

    /**
     * Finds every match of {@code needle.text} inside {@code haystack.text}, then maps the
     * normalised positions back to original-string offsets via {@link Normalized#originalIndex}.
     */
    private static List<MatchRange> findAllNormalized(
            Normalized haystack, Normalized needle, Level level) {
        List<MatchRange> out = new ArrayList<>();
        if (needle.text.isEmpty()) {
            return out;
        }
        int idx = 0;
        while ((idx = haystack.text.indexOf(needle.text, idx)) >= 0) {
            int origStart = haystack.originalIndex[idx];
            int needleEndExcl = idx + needle.text.length();
            int origEnd;
            if (needleEndExcl < haystack.originalIndex.length) {
                origEnd = haystack.originalIndex[needleEndExcl];
            } else {
                origEnd = haystack.originalLength();
            }
            out.add(new MatchRange(origStart, origEnd, level));
            idx = needleEndExcl;
        }
        return out;
    }

    /**
     * A normalised view of a string plus a per-character map back to the original string. For
     * every {@code i} in {@code [0, text.length())}, {@code originalIndex[i]} is the offset in
     * the original string of the source character that emitted {@code text.charAt(i)}.
     *
     * <p>A folded terminator maps to the {@code '\r'} that <em>starts</em> it, never to the
     * {@code '\n'} that ends it. Both span boundaries read this one map, so a match that opens or
     * closes on a newline lands on a terminator boundary from either side, and no boundary can
     * leave a stray {@code '\r'} behind. Resolving a leading newline forward to its {@code '\n'}
     * instead would split the terminator: the replacement is spliced verbatim, so any patch whose
     * text does not itself begin with a newline would strand the {@code '\r'} in the document and
     * downgrade that line's terminator to LF.
     */
    private record Normalized(String source, String text, int[] originalIndex) {
        int originalLength() {
            return source.length();
        }
    }

    /**
     * End (exclusive) of the line body within {@code [lineStart, lineEnd)}. A {@code '\r'} that
     * immediately precedes the line's {@code '\n'} belongs to a CRLF terminator, not to the line
     * body. Without this a file authored on Windows can never match an LF-only needle at any level
     * of the ladder; the same offset doubles as the map target for the emitted newline, which is
     * what keeps a match ending at a line boundary from swallowing the carriage return.
     */
    private static int lineBodyEnd(String s, int lineStart, int lineEnd) {
        if (lineEnd < s.length() && lineEnd > lineStart && s.charAt(lineEnd - 1) == '\r') {
            return lineEnd - 1;
        }
        return lineEnd;
    }

    /**
     * Drop trailing {@code ' '} and {@code '\t'} from each line. Newlines and inner-line content
     * are preserved verbatim. Mapping always points at the source character that produced the
     * emitted char (including the newline).
     */
    private static Normalized stripTrailingWhitespace(String s) {
        int len = s.length();
        StringBuilder out = new StringBuilder(len);
        int[] map = new int[len + 1];
        int mapLen = 0;
        int i = 0;
        while (i < len) {
            int lineStart = i;
            int lineEnd = s.indexOf('\n', i);
            if (lineEnd < 0) {
                lineEnd = len;
            }
            // CRLF: the carriage return is part of the terminator, not the line body.
            int bodyEnd = lineBodyEnd(s, lineStart, lineEnd);
            // Drop ' ' / '\t' from the tail of this line.
            int trimEnd = bodyEnd;
            while (trimEnd > lineStart
                    && (s.charAt(trimEnd - 1) == ' ' || s.charAt(trimEnd - 1) == '\t')) {
                trimEnd--;
            }
            for (int j = lineStart; j < trimEnd; j++) {
                out.append(s.charAt(j));
                map[mapLen++] = j;
            }
            if (lineEnd < len) {
                out.append('\n');
                map[mapLen++] = bodyEnd;
            }
            i = lineEnd + 1;
        }
        return new Normalized(s, out.toString(), Arrays.copyOf(map, mapLen));
    }

    /**
     * Aggressive normalisation: per line, strip leading + trailing whitespace, and collapse every
     * internal run of {@code ' '}/{@code '\t'} into a single {@code ' '}. Newlines are kept so
     * line structure still constrains the match.
     */
    private static Normalized collapseWhitespace(String s) {
        int len = s.length();
        StringBuilder out = new StringBuilder(len);
        int[] map = new int[len + 1];
        int mapLen = 0;
        int i = 0;
        while (i < len) {
            int lineStart = i;
            int lineEnd = s.indexOf('\n', i);
            if (lineEnd < 0) {
                lineEnd = len;
            }
            // CRLF: the carriage return is part of the terminator, not line content.
            int bodyEnd = lineBodyEnd(s, lineStart, lineEnd);
            // Skip leading whitespace.
            int j = lineStart;
            while (j < bodyEnd && (s.charAt(j) == ' ' || s.charAt(j) == '\t')) {
                j++;
            }
            boolean inRun = false;
            int runOrigStart = -1;
            int contentEmittedAt = mapLen;
            for (; j < bodyEnd; j++) {
                char c = s.charAt(j);
                if (c == ' ' || c == '\t') {
                    if (!inRun) {
                        inRun = true;
                        runOrigStart = j;
                    }
                } else {
                    if (inRun) {
                        // Emit a single collapsed space mapping back to where the run started.
                        out.append(' ');
                        map[mapLen++] = runOrigStart;
                        inRun = false;
                    }
                    out.append(c);
                    map[mapLen++] = j;
                }
            }
            // Trailing whitespace on this line is dropped along with `inRun`.
            // Only emit a newline if we actually had a line terminator AND we wrote some content
            // (suppressing newlines for fully-blank lines keeps the collapsed view denser and
            // less sensitive to inserted blank lines on the LLM side).
            if (lineEnd < len && mapLen > contentEmittedAt) {
                out.append('\n');
                map[mapLen++] = bodyEnd;
            }
            i = lineEnd + 1;
        }
        return new Normalized(s, out.toString(), Arrays.copyOf(map, mapLen));
    }
}
