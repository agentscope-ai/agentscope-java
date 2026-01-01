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
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

/**
 * Text file writing and insertion tools.
 *
 * <p>Provides tools for manipulating text files, including:
 * <ul>
 *   <li>Inserting content at specific line numbers</li>
 *   <li>Writing/replacing content with optional line range specification</li>
 * </ul>
 *
 * <p>Security: When baseDir is specified, all file operations are restricted to that directory
 * to prevent unauthorized file access.
 */
public class WriteFileTool {

    private static final Logger logger = LoggerFactory.getLogger(WriteFileTool.class);

    /**
     * Base directory to restrict file access. If null, no restriction is applied.
     * This prevents path traversal attacks and unauthorized file access.
     */
    private final Path baseDir;

    /**
     * Creates a WriteFileTool with no base directory restriction.
     */
    public WriteFileTool() {
        this(null);
    }

    /**
     * Creates a WriteFileTool with a base directory restriction.
     *
     * @param baseDir The base directory to restrict file access to. If null, no restriction is applied.
     */
    public WriteFileTool(String baseDir) {
        this.baseDir = baseDir != null ? Paths.get(baseDir).toAbsolutePath().normalize() : null;
        if (this.baseDir != null) {
            logger.info("WriteFileTool initialized with base directory: {}", this.baseDir);
        } else {
            logger.info("WriteFileTool initialized without base directory restriction");
        }
    }

    /**
     * Insert content at the specified line number in a text file.
     *
     * @param filePath The target file path
     * @param content The content to be inserted
     * @param lineNumber The line number at which the content should be inserted, starting
     *                   from 1. If it exceeds the number of lines in the file, it will be
     *                   appended to the end of the file.
     * @return The result of the insertion operation
     */
    @Tool(
            name = "insert_text_file",
            description =
                    "Insert the content at the specified line number in a text file. Use this when"
                        + " you need to add content at a specific position without replacing"
                        + " existing content. The content will be inserted as a new line, pushing"
                        + " existing lines down.")
    public Mono<ToolResultBlock> insertTextFile(
            @ToolParam(name = "file_path", description = "The target file path") String filePath,
            @ToolParam(name = "content", description = "The content to be inserted") String content,
            @ToolParam(
                            name = "line_number",
                            description =
                                    "The line number at which the content should be inserted,"
                                        + " starting from 1. If it exceeds the number of lines, it"
                                        + " will be appended to the end.")
                    Integer lineNumber) {

        logger.debug(
                "insert_text_file called: filePath='{}', lineNumber={}, contentLength={}",
                filePath,
                lineNumber,
                content != null ? content.length() : 0);

        return Mono.fromCallable(
                        () -> {
                            // Validate line number
                            if (lineNumber <= 0) {
                                logger.warn("Invalid line number: {}", lineNumber);
                                return ToolResultBlock.error(
                                        String.format(
                                                "InvalidArgumentsError: The line number %d is"
                                                        + " invalid.",
                                                lineNumber));
                            }

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
                                        String.format(
                                                "InvalidArgumentsError: The target file %s does not"
                                                        + " exist.",
                                                filePath));
                            }

                            // Read original lines
                            List<String> originalLines =
                                    Files.readAllLines(path, StandardCharsets.UTF_8);
                            logger.debug(
                                    "Read {} lines from file: {}", originalLines.size(), filePath);

                            List<String> newLines = new ArrayList<>();

                            // Insert content at the specified line
                            if (lineNumber == originalLines.size() + 1) {
                                // Append to the end
                                logger.debug(
                                        "Appending content to end of file at line {}", lineNumber);
                                newLines.addAll(originalLines);
                                newLines.add(content);
                            } else if (lineNumber <= originalLines.size()) {
                                // Insert at the specified position
                                logger.debug("Inserting content at line {}", lineNumber);
                                newLines.addAll(originalLines.subList(0, lineNumber - 1));
                                newLines.add(content);
                                newLines.addAll(
                                        originalLines.subList(
                                                lineNumber - 1, originalLines.size()));
                            } else {
                                logger.warn(
                                        "Line number {} out of valid range [1, {}]",
                                        lineNumber,
                                        originalLines.size() + 1);
                                return ToolResultBlock.error(
                                        String.format(
                                                "InvalidArgumentsError: The given line_number (%d)"
                                                        + " is not in the valid range [1, %d].",
                                                lineNumber, originalLines.size() + 1));
                            }

                            // Write the new content
                            Files.write(path, newLines, StandardCharsets.UTF_8);
                            logger.info(
                                    "Successfully inserted content into '{}' at line {}",
                                    filePath,
                                    lineNumber);

                            // Re-read to get the actual new lines
                            List<String> updatedLines =
                                    Files.readAllLines(path, StandardCharsets.UTF_8);

                            // Calculate view range
                            int[] viewRange =
                                    FileToolUtils.calculateViewRanges(
                                            originalLines.size(),
                                            updatedLines.size(),
                                            lineNumber,
                                            lineNumber,
                                            5);

                            // Get the content snippet to show
                            String showContent =
                                    FileToolUtils.viewTextFile(
                                            filePath, viewRange[0], viewRange[1]);

                            return ToolResultBlock.text(
                                    String.format(
                                            "Insert content into %s at line %d successfully. The"
                                                    + " new content between lines %d-%d is:\n"
                                                    + "```\n"
                                                    + "%s```",
                                            filePath,
                                            lineNumber,
                                            viewRange[0],
                                            viewRange[1],
                                            showContent));
                        })
                .onErrorResume(
                        e -> {
                            logger.error(
                                    "Error inserting content into file '{}' at line {}: {}",
                                    filePath,
                                    lineNumber,
                                    e.getMessage(),
                                    e);
                            return Mono.just(ToolResultBlock.error("Error: " + e.getMessage()));
                        });
    }

    /**
     * Create/Replace/Overwrite content in a text file.
     * When ranges is provided, the content will be replaced in the specified range.
     * Otherwise, the entire file (if exists) will be overwritten.
     *
     * @param filePath The target file path
     * @param content The content to be written
     * @param ranges The range of lines to be replaced as [start, end]. If null, the entire
     *               file will be overwritten. Format: "[1,5]" or "1,5"
     * @return The result of the writing operation
     */
    @Tool(
            name = "write_text_file",
            description =
                    "Create/Replace/Overwrite content in a text file. When ranges is provided, the"
                        + " content will be replaced in the specified range. Otherwise, the entire"
                        + " file (if exists) will be overwritten. Use this for creating new files"
                        + " or replacing large sections of content.")
    public Mono<ToolResultBlock> writeTextFile(
            @ToolParam(name = "file_path", description = "The target file path") String filePath,
            @ToolParam(name = "content", description = "The content to be written") String content,
            @ToolParam(
                            name = "ranges",
                            description =
                                    "The range of lines to be replaced as [start, end], e.g.,"
                                            + " '[1,5]' or '1,5'. If null or empty, the entire file"
                                            + " will be overwritten.",
                            required = false)
                    String ranges) {

        logger.debug(
                "write_text_file called: filePath='{}', ranges='{}', contentLength={}",
                filePath,
                ranges,
                content != null ? content.length() : 0);

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

                            // If file doesn't exist, create it
                            if (!Files.exists(path)) {
                                logger.debug(
                                        "File does not exist, creating new file: {}", filePath);
                                // Create parent directories if they don't exist
                                Path parentDir = path.getParent();
                                if (parentDir != null && !Files.exists(parentDir)) {
                                    Files.createDirectories(parentDir);
                                    logger.debug("Created parent directories: {}", parentDir);
                                }
                                Files.writeString(path, content, StandardCharsets.UTF_8);
                                logger.info(
                                        "Successfully created and wrote to new file: {}", filePath);

                                if (ranges != null && !ranges.trim().isEmpty()) {
                                    logger.debug(
                                            "Ignoring ranges '{}' for new file creation", ranges);
                                    return ToolResultBlock.text(
                                            String.format(
                                                    "Create and write %s successfully. The ranges"
                                                        + " %s is ignored because the file does not"
                                                        + " exist.",
                                                    filePath, ranges));
                                }
                                return ToolResultBlock.text(
                                        String.format(
                                                "Create and write %s successfully.", filePath));
                            }

                            // Read original lines
                            List<String> originalLines =
                                    Files.readAllLines(path, StandardCharsets.UTF_8);
                            logger.debug(
                                    "Read {} lines from existing file: {}",
                                    originalLines.size(),
                                    filePath);

                            // If ranges is provided, replace content in the specified range
                            if (ranges != null && !ranges.trim().isEmpty()) {
                                int[] rangeArray = FileToolUtils.parseRanges(ranges);

                                if (rangeArray == null) {
                                    logger.warn("Invalid range format: {}", ranges);
                                    return ToolResultBlock.error(
                                            String.format(
                                                    "Invalid range format. Expected '[start,end]'"
                                                            + " or 'start,end', but got %s.",
                                                    ranges));
                                }

                                int start = rangeArray[0];
                                int end = rangeArray[1];
                                logger.debug(
                                        "Replacing lines {}-{} in file: {}", start, end, filePath);

                                if (start > originalLines.size()) {
                                    logger.warn(
                                            "Start line {} exceeds file length {} for file: {}",
                                            start,
                                            originalLines.size(),
                                            filePath);
                                    return ToolResultBlock.error(
                                            String.format(
                                                    "The start line %d is invalid. The file only"
                                                            + " has %d lines.",
                                                    start, originalLines.size()));
                                }

                                // Build new content
                                List<String> newContent = new ArrayList<>();
                                if (start > 1) {
                                    newContent.addAll(originalLines.subList(0, start - 1));
                                }
                                newContent.add(content);
                                if (end < originalLines.size()) {
                                    newContent.addAll(
                                            originalLines.subList(end, originalLines.size()));
                                }

                                // Write the new content
                                String joinedContent = String.join("\n", newContent);
                                Files.writeString(path, joinedContent, StandardCharsets.UTF_8);
                                logger.info(
                                        "Successfully replaced lines {}-{} in file: {}",
                                        start,
                                        end,
                                        filePath);

                                // Calculate view range using newContent size
                                int[] viewRange =
                                        FileToolUtils.calculateViewRanges(
                                                originalLines.size(),
                                                newContent.size(),
                                                start,
                                                end,
                                                5);

                                // Get content snippet
                                String snippet =
                                        FileToolUtils.viewTextFile(
                                                filePath, viewRange[0], viewRange[1]);

                                return ToolResultBlock.text(
                                        String.format(
                                                "Write %s successfully. The new content snippet:\n"
                                                        + "```\n"
                                                        + "%s```",
                                                filePath, snippet));
                            }

                            // No ranges specified, overwrite the entire file
                            logger.debug("Overwriting entire file: {}", filePath);
                            Files.writeString(path, content, StandardCharsets.UTF_8);
                            logger.info("Successfully overwrote file: {}", filePath);

                            return ToolResultBlock.text(
                                    String.format("Overwrite %s successfully.", filePath));
                        })
                .onErrorResume(
                        e -> {
                            logger.error(
                                    "Error writing to file '{}' with ranges '{}': {}",
                                    filePath,
                                    ranges,
                                    e.getMessage(),
                                    e);
                            return Mono.just(ToolResultBlock.error("Error: " + e.getMessage()));
                        });
    }
}
