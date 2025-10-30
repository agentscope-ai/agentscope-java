/*
 * Copyright 2024-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.agentscope.core.formatter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.AudioBlock;
import io.agentscope.core.message.Base64Source;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.ImageBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.Source;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ThinkingBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.URLSource;
import io.agentscope.core.message.VideoBlock;
import io.agentscope.core.model.GenerateOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Abstract base formatter providing common functionality for all formatter implementations.
 *
 * <p>This class contains shared logic across all formatters including:
 * <ul>
 *   <li>Text content extraction (with ThinkingBlock filtering)
 *   <li>Media content detection
 *   <li>Role label formatting
 *   <li>Shared ObjectMapper instance
 * </ul>
 *
 * @param <TReq>    Provider-specific request message type
 * @param <TResp>   Provider-specific response type
 * @param <TParams> Provider-specific request parameters builder type
 */
public abstract class AbstractBaseFormatter<TReq, TResp, TParams>
        implements Formatter<TReq, TResp, TParams> {

    private static final Logger log = LoggerFactory.getLogger(AbstractBaseFormatter.class);

    /**
     * Shared ObjectMapper instance for JSON serialization/deserialization.
     */
    protected final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Extract text content from a message, filtering out ThinkingBlock.
     *
     * <p><b>Important:</b> ThinkingBlock is NOT sent back to LLM APIs
     * (matching Python implementation behavior). ThinkingBlock is stored in memory
     * but skipped when formatting messages.
     *
     * @param msg The message to extract text from
     * @return Concatenated text content
     */
    protected String extractTextContent(Msg msg) {
        return msg.getContent().stream()
                .flatMap(
                        block -> {
                            if (block instanceof TextBlock tb) {
                                return Stream.of(tb.getText());
                            } else if (block instanceof ThinkingBlock) {
                                // IMPORTANT: ThinkingBlock is NOT sent back to LLM APIs
                                // (matching Python implementation behavior)
                                // ThinkingBlock is stored in memory but skipped when formatting
                                // messages
                                log.debug(
                                        "Skipping ThinkingBlock when formatting message for LLM"
                                                + " API");
                                return Stream.empty();
                            } else if (block instanceof ToolResultBlock toolResult) {
                                // Extract text from tool result output
                                return toolResult.getOutput().stream()
                                        .filter(output -> output instanceof TextBlock)
                                        .map(output -> ((TextBlock) output).getText());
                            }
                            return Stream.empty();
                        })
                .collect(Collectors.joining("\n"));
    }

    /**
     * Extract text content from a single ContentBlock.
     *
     * @param block The content block
     * @return Text content or empty string
     */
    protected String extractTextContent(ContentBlock block) {
        if (block instanceof TextBlock tb) {
            return tb.getText();
        }
        return "";
    }

    /**
     * Check if a message contains multimodal content (images, audio, video).
     *
     * @param msg The message to check
     * @return true if message contains media content
     */
    protected boolean hasMediaContent(Msg msg) {
        for (ContentBlock block : msg.getContent()) {
            if (block instanceof ImageBlock
                    || block instanceof AudioBlock
                    || block instanceof VideoBlock) {
                return true;
            }
        }
        return false;
    }

    /**
     * Format role label for conversation history.
     *
     * @param role The message role
     * @return Formatted role label (e.g., "User", "Assistant")
     */
    protected String formatRoleLabel(MsgRole role) {
        return switch (role) {
            case USER -> "User";
            case ASSISTANT -> "Assistant";
            case SYSTEM -> "System";
            case TOOL -> "Tool";
        };
    }

    /**
     * Get an option value from options or fall back to defaultOptions.
     *
     * <p>This helper method reduces boilerplate when applying generation options.
     * It first checks if the value is present in {@code options}, then falls back
     * to {@code defaultOptions}, and finally returns null if neither has a value.
     *
     * @param <T>            The type of the option value
     * @param options        The primary options object
     * @param defaultOptions The fallback options object
     * @param getter         Function to extract the value from GenerateOptions
     * @return The option value or null if not found in either options object
     */
    protected <T> T getOptionOrDefault(
            GenerateOptions options,
            GenerateOptions defaultOptions,
            Function<GenerateOptions, T> getter) {
        T value = options != null ? getter.apply(options) : null;
        return value != null
                ? value
                : (defaultOptions != null ? getter.apply(defaultOptions) : null);
    }

    /**
     * Convert tool result output to string representation.
     *
     * <p>Handles multimodal content by converting images/audio/video to textual references.
     * This method aligns with Python implementation in {@code FormatterBase.convert_tool_result_to_string()}.
     *
     * <p><b>Python Alignment:</b> This implementation matches the Python behavior:
     * <ul>
     *   <li>Text blocks are concatenated
     *   <li>URL sources are referenced as "The returned {type} can be found at: {url}"
     *   <li>Base64 sources are saved to temp files and referenced by path
     *   <li>Single output returns directly, multiple outputs use "- " prefix (Markdown style)
     * </ul>
     *
     * @param output The tool result output blocks (can be null or empty)
     * @return String representation of the tool result
     */
    protected String convertToolResultToString(List<ContentBlock> output) {
        if (output == null || output.isEmpty()) {
            return "";
        }

        List<String> textualOutput = new ArrayList<>();

        for (ContentBlock block : output) {
            if (block instanceof TextBlock tb) {
                textualOutput.add(tb.getText());
            } else if (block instanceof ImageBlock ib) {
                String reference = convertMediaBlockToTextReference(ib, "image");
                textualOutput.add(reference);
            } else if (block instanceof AudioBlock ab) {
                String reference = convertMediaBlockToTextReference(ab, "audio");
                textualOutput.add(reference);
            } else if (block instanceof VideoBlock vb) {
                String reference = convertMediaBlockToTextReference(vb, "video");
                textualOutput.add(reference);
            }
            // Other block types (e.g., ThinkingBlock) are ignored
        }

        // Python behavior: single item returns directly, multiple items use "- " prefix
        if (textualOutput.size() == 1) {
            return textualOutput.get(0);
        } else {
            return textualOutput.stream().map(s -> "- " + s).collect(Collectors.joining("\n"));
        }
    }

    /**
     * Convert a media block (image/audio/video) to a textual reference.
     *
     * <p>Handles URL and Base64 sources:
     * <ul>
     *   <li>URL sources: "The returned {type} can be found at: {url}"
     *   <li>Base64 sources: Save to temp file and return "The returned {type} can be found at: {path}"
     * </ul>
     *
     * @param block     The media block (ImageBlock, AudioBlock, or VideoBlock)
     * @param mediaType The media type string ("image", "audio", or "video")
     * @return Textual reference to the media
     */
    private String convertMediaBlockToTextReference(ContentBlock block, String mediaType) {
        Source source = getSourceFromBlock(block);

        if (source instanceof URLSource urlSource) {
            return String.format(
                    "The returned %s can be found at: %s", mediaType, urlSource.getUrl());
        } else if (source instanceof Base64Source base64Source) {
            // Save to temp file and return path
            try {
                String filePath =
                        saveBase64DataToTempFile(
                                base64Source.getMediaType(), base64Source.getData());
                return String.format("The returned %s can be found at: %s", mediaType, filePath);
            } catch (IOException e) {
                log.error("Failed to save base64 data to temp file", e);
                return String.format("[%s - failed to save file: %s]", mediaType, e.getMessage());
            }
        }

        return String.format("[%s - unsupported source type]", mediaType);
    }

    /**
     * Extract source from a media block.
     *
     * @param block The media block
     * @return The source object
     */
    private Source getSourceFromBlock(ContentBlock block) {
        if (block instanceof ImageBlock ib) {
            return ib.getSource();
        } else if (block instanceof AudioBlock ab) {
            return ab.getSource();
        } else if (block instanceof VideoBlock vb) {
            return vb.getSource();
        }
        throw new IllegalArgumentException("Unsupported block type: " + block.getClass());
    }

    /**
     * Save base64 data to a temporary file.
     *
     * <p>Aligns with Python implementation in {@code _common._save_base64_data()}.
     *
     * <p>The temporary file is created with:
     * <ul>
     *   <li>Extension extracted from MIME type (e.g., "audio/wav" → ".wav")
     *   <li>Prefix "agentscope_"
     *   <li>File is NOT deleted on JVM exit (matching Python's delete=False behavior)
     * </ul>
     *
     * @param mediaType  The MIME type (e.g., "image/png", "audio/wav")
     * @param base64Data The base64-encoded data (without prefix)
     * @return Absolute path to the temporary file
     * @throws IOException If file creation or writing fails
     */
    protected String saveBase64DataToTempFile(String mediaType, String base64Data)
            throws IOException {
        // Extract extension from MIME type (e.g., "image/png" → ".png")
        String extension = "." + (mediaType.contains("/") ? mediaType.split("/")[1] : mediaType);

        // Create temp file with extension
        Path tempFile = Files.createTempFile("agentscope_", extension);

        // Decode base64 data
        byte[] decodedData = Base64.getDecoder().decode(base64Data);

        // Write to file
        Files.write(tempFile, decodedData);

        log.debug("Saved base64 data to temp file: {}", tempFile);

        // Return absolute path
        return tempFile.toAbsolutePath().toString();
    }
}
