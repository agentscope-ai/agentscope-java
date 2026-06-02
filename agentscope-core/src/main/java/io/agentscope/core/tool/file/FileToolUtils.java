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
package io.agentscope.core.tool.file;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Utility methods shared by text file tools.
 */
public class FileToolUtils {

    private FileToolUtils() {
        // Utility class, prevent instantiation
    }

    /**
     * Validate that the given file path is within the base directory.
     * This prevents path traversal attacks and unauthorized file access.
     *
     * <p>When baseDir is specified:
     * <ul>
     *   <li>Relative paths are resolved relative to baseDir</li>
     *   <li>Absolute paths are validated to be within baseDir</li>
     * </ul>
     *
     * @param filePath The file path to validate
     * @param baseDir The base directory to restrict access to (null means no restriction)
     * @return The normalized absolute path if valid
     * @throws IOException if the path is invalid or outside the base directory
     */
    static Path validatePath(String filePath, Path baseDir) throws IOException {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IOException("File path cannot be null or empty.");
        }

        Path inputPath = Paths.get(filePath);

        // If baseDir is specified, relative paths should be resolved relative to baseDir
        Path path;
        if (baseDir != null && !inputPath.isAbsolute()) {
            // Relative path: resolve relative to baseDir
            path = baseDir.resolve(inputPath).normalize();
        } else {
            // Absolute path or no baseDir: convert to absolute path
            path = inputPath.toAbsolutePath().normalize();
        }

        // If baseDir is specified, ensure the path is within it
        if (baseDir != null) {
            Path normalizedBaseDir = baseDir.toAbsolutePath().normalize();
            if (!path.startsWith(normalizedBaseDir)) {
                throw new IOException(
                        String.format(
                                "Access denied: The file path '%s' is outside the allowed base"
                                        + " directory '%s'.",
                                filePath, normalizedBaseDir));
            }
        }

        return path;
    }

    /**
     * Calculate the view ranges for displaying file content after modifications.
     *
     * @param originalLineCount Original number of lines
     * @param newLineCount New number of lines after modification
     * @param modifyStart Start line of modification
     * @param modifyEnd End line of modification
     * @param extraViewLines Extra lines to show before and after
     * @return Array of [viewStart, viewEnd]
     */
    static int[] calculateViewRanges(
            int originalLineCount,
            int newLineCount,
            int modifyStart,
            int modifyEnd,
            int extraViewLines) {

        int viewStart = Math.max(1, modifyStart - extraViewLines);
        int viewEnd =
                Math.min(
                        newLineCount,
                        modifyStart
                                + (newLineCount - originalLineCount)
                                + (modifyEnd - modifyStart)
                                + extraViewLines);

        return new int[] {viewStart, viewEnd};
    }

    /**
     * View a specific range of lines from a text file.
     *
     * @param filePath The file path
     * @param startLine Start line number (1-based)
     * @param endLine End line number (1-based, inclusive)
     * @return The content with line numbers
     */
    static String viewTextFile(String filePath, int startLine, int endLine) {
        try {
            List<String> lines = Files.readAllLines(Paths.get(filePath), StandardCharsets.UTF_8);
            StringBuilder result = new StringBuilder();

            int start = Math.max(0, startLine - 1);
            int end = Math.min(lines.size(), endLine);

            for (int i = start; i < end; i++) {
                result.append(String.format("%d: %s\n", i + 1, lines.get(i)));
            }

            return result.toString();
        } catch (IOException e) {
            return "Error reading file: " + e.getMessage();
        }
    }

    /**
     * Parse range string like "[1,5]" or "1,5" into [start, end] array.
     *
     * @param ranges The range string
     * @return Array of [start, end] or null if invalid
     */
    static int[] parseRanges(String ranges) {
        try {
            // Remove brackets and spaces
            String cleaned = ranges.trim().replaceAll("^\\[", "").replaceAll("\\]$", "").trim();

            String[] parts = cleaned.split(",");
            if (parts.length != 2) {
                return null;
            }

            int start = Integer.parseInt(parts[0].trim());
            int end = Integer.parseInt(parts[1].trim());

            return new int[] {start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
