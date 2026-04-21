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

    /**
     * Perform string replacement with occurrence validation.
     *
     * @return a two-element array {@code [newContent, occurrenceCount]} on success,
     *         or a single-element array {@code [errorMessage]} on failure
     */
    public static Object[] performStringReplacement(
            String content, String oldString, String newString, boolean replaceAll) {
        int occurrences = countOccurrences(content, oldString);

        if (occurrences == 0) {
            return new Object[] {"Error: String not found in file: '" + oldString + "'"};
        }

        if (occurrences > 1 && !replaceAll) {
            return new Object[] {
                "Error: String '"
                        + oldString
                        + "' appears "
                        + occurrences
                        + " times in file. "
                        + "Use replaceAll=true to replace all instances, or provide a more specific"
                        + " string with surrounding context."
            };
        }

        String newContent;
        if (replaceAll) {
            newContent = content.replace(oldString, newString);
        } else {
            int idx = content.indexOf(oldString);
            newContent =
                    content.substring(0, idx)
                            + newString
                            + content.substring(idx + oldString.length());
        }
        return new Object[] {newContent, occurrences};
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
