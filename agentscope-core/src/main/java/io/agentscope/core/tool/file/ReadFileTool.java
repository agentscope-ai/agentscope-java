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

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Tool for viewing text file content with optional line range specification and listing directory
 * contents.
 *
 * <p>This tool provides the following capabilities:
 * <ul>
 *   <li>Viewing entire file content</li>
 *   <li>Viewing specific line ranges (e.g., lines 1-100)</li>
 *   <li>Viewing from the end using negative indices (e.g., last 100 lines: [-100, -1])</li>
 *   <li>Listing files and directories in a specified directory with full paths</li>
 * </ul>
 *
 * <p>Security: When baseDir is specified, all file operations are restricted to that directory
 * to prevent unauthorized file access.
 */
public class ReadFileTool {

    private static final Logger logger = LoggerFactory.getLogger(ReadFileTool.class);

    /**
     * Base directory to restrict file access. If null, no restriction is applied.
     * This prevents path traversal attacks and unauthorized file access.
     */
    private final Path baseDir;

    /**
     * Creates a ReadFileTool with no base directory restriction.
     */
    public ReadFileTool() {
        this(null);
    }

    /**
     * Creates a ReadFileTool with a base directory restriction.
     *
     * @param baseDir The base directory to restrict file access to. If null, no restriction is applied.
     */
    public ReadFileTool(String baseDir) {
        this.baseDir = baseDir != null ? Paths.get(baseDir).toAbsolutePath().normalize() : null;
        if (this.baseDir != null) {
            logger.info("ReadFileTool initialized with base directory: {}", this.baseDir);
        } else {
            logger.info("ReadFileTool initialized without base directory restriction");
        }
    }

    /**
     * View the file content in the specified range with line numbers.
     * If ranges is not provided, the entire file will be returned.
     *
     * @param filePath The target file path
     * @param ranges The range of lines to be viewed (e.g., lines 1 to 100: "1,100"),
     *               inclusive. If not provided, the entire file will be returned.
     *               To view the last 100 lines, use "-100,-1". Format: "start,end" or "[start,end]"
     * @return The file content with line numbers or an error message
     */
    @Tool(
            name = "view_text_file",
            description =
                    "View the file content in the specified range with line numbers. If ranges is"
                        + " not provided, the entire file will be returned. Use this to read and"
                        + " inspect file contents. Supports negative indices to view from the end"
                        + " (e.g., '-100,-1' for last 100 lines).")
    public Mono<ToolResultBlock> viewTextFile(
            @ToolParam(name = "file_path", description = "The target file path") String filePath,
            @ToolParam(
                            name = "ranges",
                            description =
                                    "The range of lines to be viewed (e.g., '1,100' or '[1,100]'),"
                                        + " inclusive. If not provided, the entire file will be"
                                        + " returned. To view the last 100 lines, use '-100,-1'.",
                            required = false)
                    String ranges) {

        logger.debug("view_text_file called: filePath='{}', ranges='{}'", filePath, ranges);

        return Mono.fromCallable(
                        () -> {
                            // Validate path is within base directory
                            Path path;
                            try {
                                path = FileToolUtils.validatePath(filePath, baseDir);
                            } catch (Exception e) {
                                logger.warn(
                                        "Path validation failed for '{}': {}",
                                        filePath,
                                        e.getMessage());
                                return ToolResultBlock.error(e.getMessage());
                            }

                            // Check if file exists
                            if (!Files.exists(path)) {
                                logger.warn("File does not exist: {}", filePath);
                                return ToolResultBlock.error(
                                        String.format("The file %s does not exist.", filePath));
                            }

                            // Check if path is a file
                            if (!Files.isRegularFile(path)) {
                                logger.warn("Path is not a regular file: {}", filePath);
                                return ToolResultBlock.error(
                                        String.format("The path %s is not a file.", filePath));
                            }

                            // Read all lines
                            List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                            logger.debug("Read {} lines from file: {}", lines.size(), filePath);

                            // Parse ranges if provided
                            if (ranges == null || ranges.trim().isEmpty()) {
                                // Return entire file content
                                logger.debug(
                                        "Returning entire file content ({} lines)", lines.size());
                                String content = formatLinesWithNumbers(lines, 1, lines.size());
                                return ToolResultBlock.text(
                                        String.format(
                                                "The content of %s:\n```\n%s```",
                                                filePath, content));
                            }

                            // Parse the range
                            int[] rangeArray = FileToolUtils.parseRanges(ranges);
                            if (rangeArray == null) {
                                logger.warn("Invalid range format: {}", ranges);
                                return ToolResultBlock.error(
                                        String.format(
                                                "Invalid range format. Expected '[start,end]' or"
                                                        + " 'start,end', but got %s.",
                                                ranges));
                            }

                            int start = rangeArray[0];
                            int end = rangeArray[1];

                            // Handle negative indices (count from the end)
                            if (start < 0) {
                                start = lines.size() + start + 1;
                            }
                            if (end < 0) {
                                end = lines.size() + end + 1;
                            }

                            // Validate range
                            if (start < 1) {
                                start = 1;
                            }
                            if (end > lines.size()) {
                                end = lines.size();
                            }
                            if (start > end) {
                                logger.warn(
                                        "Invalid range: start {} > end {} for file with {} lines",
                                        start,
                                        end,
                                        lines.size());
                                return ToolResultBlock.error(
                                        String.format(
                                                "Invalid range: start line %d is greater than end"
                                                        + " line %d.",
                                                start, end));
                            }

                            logger.debug("Viewing lines {}-{} from file: {}", start, end, filePath);

                            // Extract the specified range
                            String content = formatLinesWithNumbers(lines, start, end);

                            return ToolResultBlock.text(
                                    String.format(
                                            "The content of %s in lines [%d, %d]:\n```\n%s```",
                                            filePath, start, end, content));
                        })
                .onErrorResume(
                        e -> {
                            logger.error(
                                    "Error viewing file '{}' with ranges '{}': {}",
                                    filePath,
                                    ranges,
                                    e.getMessage(),
                                    e);
                            return Mono.just(ToolResultBlock.error("Error: " + e.getMessage()));
                        });
    }

    /**
     * List files and directories in the specified directory.
     *
     * @param dirPath The target directory path
     * @return A list of files and directories with their full paths
     */
    @Tool(
            name = "list_directory",
            description =
                    "List all files and directories in the specified directory. Returns the full"
                        + " paths of all files and folders in the directory. Use this to explore"
                        + " the file system structure.")
    public Mono<ToolResultBlock> listDirectory(
            @ToolParam(
                            name = "dir_path",
                            description = "The target directory path to list files and folders")
                    String dirPath) {

        logger.debug("list_directory called: dirPath='{}'", dirPath);

        return Mono.fromCallable(
                        () -> {
                            // Validate path is within base directory
                            Path path;
                            try {
                                path = FileToolUtils.validatePath(dirPath, baseDir);
                            } catch (Exception e) {
                                logger.warn(
                                        "Path validation failed for '{}': {}",
                                        dirPath,
                                        e.getMessage());
                                return ToolResultBlock.error(e.getMessage());
                            }

                            // Check if path exists
                            if (!Files.exists(path)) {
                                logger.warn("Directory does not exist: {}", dirPath);
                                return ToolResultBlock.error(
                                        String.format("The directory %s does not exist.", dirPath));
                            }

                            // Check if path is a directory
                            if (!Files.isDirectory(path)) {
                                logger.warn("Path is not a directory: {}", dirPath);
                                return ToolResultBlock.error(
                                        String.format("The path %s is not a directory.", dirPath));
                            }

                            // List files and directories
                            List<String> files = new ArrayList<>();
                            List<String> directories = new ArrayList<>();

                            try (Stream<Path> paths = Files.list(path)) {
                                paths.sorted(Comparator.comparing(Path::toString))
                                        .forEach(
                                                p -> {
                                                    String fullPath = p.toAbsolutePath().toString();
                                                    if (Files.isDirectory(p)) {
                                                        directories.add(fullPath);
                                                    } else {
                                                        files.add(fullPath);
                                                    }
                                                });
                            } catch (Exception e) {
                                logger.error(
                                        "Error listing directory '{}': {}",
                                        dirPath,
                                        e.getMessage(),
                                        e);
                                return ToolResultBlock.error(
                                        "Error listing directory: " + e.getMessage());
                            }

                            // Format output
                            StringBuilder result = new StringBuilder();
                            result.append(String.format("Contents of directory %s:\n\n", dirPath));

                            if (!directories.isEmpty()) {
                                result.append("Directories:\n");
                                for (String dir : directories) {
                                    result.append("  ").append(dir).append("\n");
                                }
                                result.append("\n");
                            }

                            if (!files.isEmpty()) {
                                result.append("Files:\n");
                                for (String file : files) {
                                    result.append("  ").append(file).append("\n");
                                }
                                result.append("\n");
                            }

                            if (directories.isEmpty() && files.isEmpty()) {
                                result.append("(empty directory)\n");
                            } else {
                                result.append(
                                        String.format(
                                                "Total: %d directory(ies), %d file(s)",
                                                directories.size(), files.size()));
                            }

                            logger.debug(
                                    "Listed {} directories and {} files in: {}",
                                    directories.size(),
                                    files.size(),
                                    dirPath);

                            return ToolResultBlock.text(result.toString());
                        })
                .onErrorResume(
                        e -> {
                            logger.error(
                                    "Error listing directory '{}': {}", dirPath, e.getMessage(), e);
                            return Mono.just(ToolResultBlock.error("Error: " + e.getMessage()));
                        });
    }

    /**
     * Format lines with line numbers for display.
     *
     * @param lines All lines from the file
     * @param start Start line number (1-based, inclusive)
     * @param end End line number (1-based, inclusive)
     * @return Formatted string with line numbers
     */
    private String formatLinesWithNumbers(List<String> lines, int start, int end) {
        StringBuilder result = new StringBuilder();

        // Adjust to 0-based index
        int startIdx = start - 1;
        int endIdx = end - 1;

        for (int i = startIdx; i <= endIdx && i < lines.size(); i++) {
            result.append(String.format("%d: %s\n", i + 1, lines.get(i)));
        }

        return result.toString();
    }
}
