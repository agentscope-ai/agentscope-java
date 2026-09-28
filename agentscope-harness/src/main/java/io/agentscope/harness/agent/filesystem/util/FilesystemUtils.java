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

import io.agentscope.harness.agent.filesystem.model.EditResult;
import java.util.Set;

/**
 * Shared utility functions for filesystem implementations.
 */
public final class FilesystemUtils {

    private FilesystemUtils() {}

    private static final Set<String> BINARY_EXTENSIONS =
            Set.of(
                    ".png", ".jpg", ".jpeg", ".gif", ".webp", ".heic", ".heif", ".bmp", ".ico",
                    ".svg", ".mp4", ".mpeg", ".mov", ".avi", ".flv", ".mpg", ".webm", ".wmv",
                    ".3gpp", ".wav", ".mp3", ".aiff", ".aac", ".ogg", ".flac", ".pdf", ".ppt",
                    ".pptx", ".doc", ".docx", ".xls", ".xlsx", ".zip", ".tar", ".gz", ".bz2", ".7z",
                    ".rar", ".class", ".jar", ".war", ".ear", ".so", ".dll", ".dylib", ".exe");

    /**
     * Classify a file as "text" or "binary" based on extension.
     */
    public static String getFileType(String path) {
        if (path == null) {
            return "text";
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return "text";
        }
        String ext = path.substring(dot).toLowerCase();
        return BINARY_EXTENSIONS.contains(ext) ? "binary" : "text";
    }

    /** Result of a string replacement operation. */
    public record ReplacementResult(String content, int occurrences, String error) {

        public static ReplacementResult success(String content, int occurrences) {
            return new ReplacementResult(content, occurrences, null);
        }

        public static ReplacementResult error(String message) {
            return new ReplacementResult(null, 0, message);
        }

        public boolean isSuccess() {
            return error == null;
        }
    }

    /**
     * Validates {@code edit()} arguments shared by every filesystem implementation.
     *
     * <p>Both are rejected uniformly so all implementations behave identically: a {@code null}
     * {@code newString} is not a deletion (pass {@code ""} for that, which keeps the normal
     * replacement semantics and occurrence count), and an empty {@code oldString} would make
     * {@link #countOccurrences} loop forever.
     *
     * @return an {@link EditResult#fail} result when the arguments are invalid, otherwise
     *         {@code null} to signal that the edit may proceed
     */
    public static EditResult validateEditArguments(String filePath, String oldStr, String newStr) {
        if (oldStr == null || oldStr.isEmpty()) {
            return EditResult.fail("Error: oldString must not be null or empty");
        }
        if (newStr == null) {
            return EditResult.fail(
                    "Error: newString must not be null; pass an empty string to delete the"
                            + " matched text");
        }
        return null;
    }

    /**
     * Perform string replacement with occurrence validation.
     *
     * @return {@code Object[]} of {@code [newContent, occurrenceCount]} on success, or a
     *         single-element array {@code [errorMessage]} on failure
     * @deprecated superseded by {@link #stringReplacement(String, String, String, boolean)},
     *     which returns the same result as a typed value. {@code agentscope-harness} is
     *     published to Maven Central and this method shipped with the {@code Object[]}
     *     signature in v2.0.1-v2.0.3, so the old signature is retained as a source/binary
     *     compatibility bridge for downstream callers.
     */
    @Deprecated
    public static Object[] performStringReplacement(
            String content, String oldString, String newString, boolean replaceAll) {
        ReplacementResult result = stringReplacement(content, oldString, newString, replaceAll);
        if (!result.isSuccess()) {
            return new Object[] {result.error()};
        }
        return new Object[] {result.content(), result.occurrences()};
    }

    /**
     * Perform string replacement with occurrence validation.
     *
     * <p>Line endings are normalized to {@code \n} on the content and on both patterns first, so
     * a caller need not care whether the file uses CRLF or lone CR. The returned content is
     * therefore always LF-terminated: on a Unix-style filesystem CRLF is a defect worth removing,
     * and every implementation must agree on this or the same edit would match on one backend and
     * not on another.
     *
     * @return {@link ReplacementResult#success(String, int)} on success, or
     *         {@link ReplacementResult#error(String)} on failure
     */
    public static ReplacementResult stringReplacement(
            String content, String oldString, String newString, boolean replaceAll) {
        String normalizedContent = normalizeLineEndings(content);
        String normalizedOld = normalizeLineEndings(oldString);
        String normalizedNew = normalizeLineEndings(newString);

        int occurrences = countOccurrences(normalizedContent, normalizedOld);

        if (occurrences == 0) {
            return ReplacementResult.error("Error: String not found in file: '" + oldString + "'");
        }

        if (occurrences > 1 && !replaceAll) {
            return ReplacementResult.error(
                    "Error: String '"
                            + oldString
                            + "' appears "
                            + occurrences
                            + " times in file. "
                            + "Use replaceAll=true to replace all instances, or provide a more"
                            + " specific string with surrounding context.");
        }

        String newContent;
        if (replaceAll) {
            newContent = normalizedContent.replace(normalizedOld, normalizedNew);
        } else {
            int idx = normalizedContent.indexOf(normalizedOld);
            newContent =
                    normalizedContent.substring(0, idx)
                            + normalizedNew
                            + normalizedContent.substring(idx + normalizedOld.length());
        }
        return ReplacementResult.success(newContent, occurrences);
    }

    /** Normalize CRLF and lone CR to LF. */
    private static String normalizeLineEndings(String text) {
        if (text.indexOf('\r') < 0) {
            return text;
        }
        return text.replace("\r\n", "\n").replace("\r", "\n");
    }

    /** Count non-overlapping occurrences of a substring. */
    public static int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    /** Shell-escape a string for safe use in shell commands. */
    public static String shellQuote(String s) {
        if (s == null || s.isEmpty()) {
            return "''";
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
